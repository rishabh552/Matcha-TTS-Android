package com.example.tts

import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.tts.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MatchaTts"
        private const val ENGINE_PROVIDER = "cpu"
        private const val SHORT_TEXT_SPEED = 1.35f
        private const val NORMAL_TEXT_SPEED = 1.10f

        // Keep synthesis requests bounded to avoid very large JNI/audio allocations.
        private const val MAX_CHUNK_CHARS = 280
        private const val MAX_CHUNK_WORDS = 50
    }

    private lateinit var binding: ActivityMainBinding
    private val audioPlayer = AudioPlayer()
    private var initialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.speakButton.isEnabled = false
        binding.statusText.text = "Preparing model assets..."

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val assetsStart = SystemClock.elapsedRealtimeNanos()
                    val paths = AssetUtils.ensureMatchaAssets(this@MainActivity)
                    val assetsMs = (SystemClock.elapsedRealtimeNanos() - assetsStart) / 1_000_000
                    Log.i(TAG, "Asset copy/check took ${assetsMs} ms")

                    val preferredThreads = chooseSafeCpuThreads()
                    val initStart = SystemClock.elapsedRealtimeNanos()
                    val initOk = initWithCpuFallback(paths, preferredThreads)
                    val initMs = (SystemClock.elapsedRealtimeNanos() - initStart) / 1_000_000
                    Log.i(TAG, "Native init took ${initMs} ms")

                    initOk
                }.getOrElse {
                    Log.e(TAG, "Startup failed", it)
                    false
                }
            }

            initialized = ok
            if (!ok) {
                binding.speakButton.isEnabled = false
                binding.statusText.text = "Initialization failed. Check assets/libs."
                return@launch
            }

            val provider = NativeTts.runtimeProvider()
            val threads = NativeTts.runtimeThreads()
            binding.speakButton.isEnabled = true
            binding.statusText.text = "Ready (${NativeTts.sampleRate()} Hz, $provider/$threads, uncached)"
        }

        binding.speakButton.setOnClickListener {
            val text = binding.inputEditText.text?.toString()?.trim().orEmpty()
            if (!initialized || text.isEmpty()) return@setOnClickListener

            binding.speakButton.isEnabled = false
            binding.statusText.text = "Generating..."

            lifecycleScope.launch {
                val chunks = splitForSynthesis(text)
                val sampleRate = NativeTts.sampleRate()
                var totalSamples = 0
                var totalGenerateMs = 0L

                // Reset previous playback session before a new request.
                audioPlayer.stop()

                for ((index, chunk) in chunks.withIndex()) {
                    val speed = chooseSpeed(chunk)
                    binding.statusText.text = "Generating ${index + 1}/${chunks.size}..."

                    val genStart = SystemClock.elapsedRealtimeNanos()
                    val samples = withContext(Dispatchers.Default) {
                        NativeTts.generate(chunk, speed)
                    }
                    val genMs = (SystemClock.elapsedRealtimeNanos() - genStart) / 1_000_000
                    totalGenerateMs += genMs

                    Log.i(
                        TAG,
                        "Generate chunk ${index + 1}/${chunks.size} took ${genMs} ms for ${samples.size} samples (speed=$speed, chars=${chunk.length})"
                    )

                    if (samples.isEmpty()) {
                        Log.w(TAG, "Chunk ${index + 1}/${chunks.size} returned empty audio")
                        continue
                    }

                    totalSamples += samples.size

                    val playStart = SystemClock.elapsedRealtimeNanos()
                    withContext(Dispatchers.Default) {
                        audioPlayer.play(samples, sampleRate)
                    }
                    val playMs = (SystemClock.elapsedRealtimeNanos() - playStart) / 1_000_000
                    Log.i(TAG, "Playback write chunk ${index + 1}/${chunks.size} took ${playMs} ms")
                }

                if (totalSamples > 0) {
                    binding.statusText.text = "Done (${chunks.size} chunks, $totalSamples samples, ${totalGenerateMs} ms gen)"
                } else {
                    binding.statusText.text = "Generation returned empty audio"
                }

                binding.speakButton.isEnabled = true
            }
        }
    }

    private fun initWithCpuFallback(paths: MatchaPaths, preferredThreads: Int): Boolean {
        val attempts = linkedSetOf(preferredThreads, 1)
        for (threads in attempts) {
            val attemptStart = SystemClock.elapsedRealtimeNanos()
            val setConfigOk = NativeTts.setEngineConfig(ENGINE_PROVIDER, threads)
            if (!setConfigOk) {
                Log.w(TAG, "Set engine config failed for cpu/$threads")
                continue
            }

            val initOk = runCatching {
                NativeTts.init(
                    paths.acousticModelPath,
                    paths.vocoderPath,
                    paths.tokensPath,
                    paths.lexiconPath,
                    paths.dataDir
                )
            }.getOrElse {
                Log.e(TAG, "Native init failed for cpu/$threads", it)
                false
            }

            val attemptMs = (SystemClock.elapsedRealtimeNanos() - attemptStart) / 1_000_000
            Log.i(TAG, "Init candidate cpu/$threads took ${attemptMs} ms (ok=$initOk)")

            if (initOk) {
                return true
            }
        }

        return false
    }

    private fun chooseSafeCpuThreads(): Int {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memClass = activityManager.memoryClass
        val cores = Runtime.getRuntime().availableProcessors()

        val hw = Build.HARDWARE.lowercase()
        val board = Build.BOARD.lowercase()
        val product = Build.PRODUCT.lowercase()
        val isSd460Like = hw.contains("sm4250") || board.contains("sm4250") ||
            product.contains("sm4250") || hw.contains("sdm460") || board.contains("sdm460")

        val threads = if (isSd460Like || memClass <= 192 || cores <= 4) 2 else 4
        Log.i(
            TAG,
            "Device profile: hardware=${Build.HARDWARE}, board=${Build.BOARD}, memClass=${memClass}MB, cores=$cores -> cpu/$threads"
        )
        return threads
    }

    private fun chooseSpeed(text: String): Float {
        val words = countWords(text)
        return if (text.length <= 32 && words <= 5) SHORT_TEXT_SPEED else NORMAL_TEXT_SPEED
    }

    private fun splitForSynthesis(text: String): List<String> {
        if (text.length <= MAX_CHUNK_CHARS && countWords(text) <= MAX_CHUNK_WORDS) {
            return listOf(text)
        }

        val sentenceRegex = Regex("(?<=[.!?;:])\\s+")
        val sentences = text.split(sentenceRegex)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (sentences.isEmpty()) {
            return splitLongSentence(text)
        }

        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var currentWords = 0

        fun flushCurrent() {
            if (current.isNotEmpty()) {
                chunks += current.toString().trim()
                current.clear()
                currentWords = 0
            }
        }

        for (sentence in sentences) {
            val sentenceWords = countWords(sentence)

            if (sentence.length > MAX_CHUNK_CHARS || sentenceWords > MAX_CHUNK_WORDS) {
                flushCurrent()
                chunks += splitLongSentence(sentence)
                continue
            }

            val nextLen = if (current.isEmpty()) sentence.length else current.length + 1 + sentence.length
            if (nextLen > MAX_CHUNK_CHARS || currentWords + sentenceWords > MAX_CHUNK_WORDS) {
                flushCurrent()
            }

            if (current.isNotEmpty()) {
                current.append(' ')
            }
            current.append(sentence)
            currentWords += sentenceWords
        }

        flushCurrent()
        return if (chunks.isNotEmpty()) chunks else splitLongSentence(text)
    }

    private fun splitLongSentence(text: String): List<String> {
        val words = text.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (words.isEmpty()) {
            return emptyList()
        }

        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var currentWords = 0

        for (word in words) {
            val nextLen = if (current.isEmpty()) word.length else current.length + 1 + word.length
            if ((nextLen > MAX_CHUNK_CHARS || currentWords >= MAX_CHUNK_WORDS) && current.isNotEmpty()) {
                chunks += current.toString()
                current.clear()
                currentWords = 0
            }

            if (current.isNotEmpty()) {
                current.append(' ')
            }
            current.append(word)
            currentWords += 1
        }

        if (current.isNotEmpty()) {
            chunks += current.toString()
        }

        return chunks
    }

    private fun countWords(text: String): Int {
        return text.split(Regex("\\s+")).count { it.isNotEmpty() }
    }

    override fun onDestroy() {
        audioPlayer.stop()
        NativeTts.release()
        super.onDestroy()
    }
}
