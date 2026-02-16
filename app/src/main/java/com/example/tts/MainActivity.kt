package com.example.tts

import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.tts.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MatchaTts"
        private const val ENGINE_PROVIDER = "cpu"
        private const val SHORT_TEXT_SPEED = 1.35f
        private const val NORMAL_TEXT_SPEED = 1.10f

        // Phase-1 stability caps to keep per-request memory bounded.
        private const val MAX_CHUNK_CHARS = 120
        private const val MAX_CHUNK_WORDS = 20
        private const val MAX_TOTAL_CHUNKS = 80
        private const val MAX_INPUT_CHARS = 8000

        // Phase-2: bounded queue to overlap generation with playback.
        private const val PLAYBACK_QUEUE_CAPACITY = 2
    }

    private data class GeneratedChunk(
        val index: Int,
        val total: Int,
        val samples: FloatArray
    )

    private lateinit var binding: ActivityMainBinding
    private val audioPlayer = AudioPlayer()
    private var initialized = false
    private var synthesisJob: Job? = null

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

            if (text.length > MAX_INPUT_CHARS) {
                binding.statusText.text = "Input too long (${text.length} chars). Max: $MAX_INPUT_CHARS"
                return@setOnClickListener
            }

            if (synthesisJob?.isActive == true) {
                binding.statusText.text = "Synthesis already running..."
                return@setOnClickListener
            }

            binding.speakButton.isEnabled = false
            binding.statusText.text = "Preparing synthesis..."

            synthesisJob = lifecycleScope.launch {
                val hadFailure = AtomicBoolean(false)
                val wasCancelled = AtomicBoolean(false)

                try {
                    val rawChunks = splitForSynthesis(text)
                    val chunks = if (rawChunks.size > MAX_TOTAL_CHUNKS) {
                        Log.w(
                            TAG,
                            "Chunk count ${rawChunks.size} exceeds cap $MAX_TOTAL_CHUNKS. Truncating request."
                        )
                        rawChunks.take(MAX_TOTAL_CHUNKS)
                    } else {
                        rawChunks
                    }

                    val sampleRate = NativeTts.sampleRate()
                    val chunkQueue = Channel<GeneratedChunk>(capacity = PLAYBACK_QUEUE_CAPACITY)

                    val totalGenerateMs = AtomicLong(0L)
                    val totalSamples = AtomicInteger(0)
                    val playableChunks = AtomicInteger(0)
                    val generatedChunks = AtomicInteger(0)

                    // Reset previous playback session before a new request.
                    audioPlayer.stop()

                    val producer = launch(Dispatchers.Default) {
                        try {
                            for ((index, chunkText) in chunks.withIndex()) {
                                if (!isActive) break

                                withContext(Dispatchers.Main) {
                                    binding.statusText.text = "Generating ${index + 1}/${chunks.size}..."
                                }

                                val speed = chooseSpeed(chunkText)
                                val genStart = SystemClock.elapsedRealtimeNanos()
                                val samples = runCatching { NativeTts.generate(chunkText, speed) }
                                    .onFailure { Log.e(TAG, "Generate failed for chunk ${index + 1}", it) }
                                    .getOrDefault(FloatArray(0))
                                val genMs = (SystemClock.elapsedRealtimeNanos() - genStart) / 1_000_000

                                totalGenerateMs.addAndGet(genMs)
                                generatedChunks.incrementAndGet()

                                Log.i(
                                    TAG,
                                    "Generate chunk ${index + 1}/${chunks.size} took ${genMs} ms for ${samples.size} samples (speed=$speed, chars=${chunkText.length})"
                                )

                                if (samples.isEmpty()) {
                                    hadFailure.set(true)
                                    continue
                                }

                                chunkQueue.send(
                                    GeneratedChunk(
                                        index = index + 1,
                                        total = chunks.size,
                                        samples = samples
                                    )
                                )
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            hadFailure.set(true)
                            Log.e(TAG, "Producer failed", t)
                        } finally {
                            chunkQueue.close()
                        }
                    }

                    val consumer = launch(Dispatchers.Default) {
                        try {
                            for (chunk in chunkQueue) {
                                if (!isActive) break

                                withContext(Dispatchers.Main) {
                                    binding.statusText.text = "Playing ${chunk.index}/${chunk.total}..."
                                }

                                val playStart = SystemClock.elapsedRealtimeNanos()
                                val playOk = audioPlayer.play(chunk.samples, sampleRate)
                                val playMs = (SystemClock.elapsedRealtimeNanos() - playStart) / 1_000_000

                                if (!playOk) {
                                    hadFailure.set(true)
                                    Log.e(TAG, "Playback failed for chunk ${chunk.index}/${chunk.total}")
                                    continue
                                }

                                totalSamples.addAndGet(chunk.samples.size)
                                playableChunks.incrementAndGet()

                                Log.i(
                                    TAG,
                                    "Playback write chunk ${chunk.index}/${chunk.total} took ${playMs} ms"
                                )
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            hadFailure.set(true)
                            Log.e(TAG, "Consumer failed", t)
                        }
                    }

                    try {
                        producer.join()
                        consumer.join()
                    } catch (ce: CancellationException) {
                        wasCancelled.set(true)
                        throw ce
                    }

                    binding.statusText.text = when {
                        wasCancelled.get() -> "Cancelled"
                        playableChunks.get() > 0 -> "Done (${playableChunks.get()}/${chunks.size} played, ${totalSamples.get()} samples, ${totalGenerateMs.get()} ms gen)"
                        generatedChunks.get() > 0 -> "Generated audio but playback failed"
                        else -> "Generation returned empty audio"
                    }
                } catch (ce: CancellationException) {
                    wasCancelled.set(true)
                    binding.statusText.text = "Cancelled"
                    throw ce
                } catch (t: Throwable) {
                    hadFailure.set(true)
                    Log.e(TAG, "Synthesis pipeline failed", t)
                    binding.statusText.text = "Playback error (${t.javaClass.simpleName})"
                } finally {
                    if (hadFailure.get() || wasCancelled.get()) {
                        audioPlayer.stop()
                    }
                    synthesisJob = null
                    binding.speakButton.isEnabled = initialized
                }
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
        synthesisJob?.cancel()
        audioPlayer.stop()
        NativeTts.release()
        super.onDestroy()
    }
}
