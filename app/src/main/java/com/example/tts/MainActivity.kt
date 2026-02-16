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
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity() {
    private data class GeneratedChunk(
        val index: Int,
        val total: Int,
        val samples: FloatArray
    )

    private data class SynthesisProfile(
        val name: String,
        val maxChunkChars: Int,
        val maxChunkWords: Int,
        val maxTotalChunks: Int,
        val queueCapacity: Int,
        val shortSpeed: Float,
        val normalSpeed: Float,
        val longSpeed: Float,
        val longTextThresholdWords: Int
    )

    private data class RuntimeProfile(
        val tier: String,
        val threads: Int,
        val synthesis: SynthesisProfile
    )

    companion object {
        private const val TAG = "MatchaTts"
        private const val ENGINE_PROVIDER = "cpu"
        private const val MAX_INPUT_CHARS = 8000

        private val SENTENCE_SPLIT_REGEX = Regex("(?<=[.!?;:])\\s+")
        private val WHITESPACE_REGEX = Regex("\\s+")

        private val LOW_TIER_PROFILE = SynthesisProfile(
            name = "low",
            maxChunkChars = 120,
            maxChunkWords = 20,
            maxTotalChunks = 80,
            queueCapacity = 2,
            shortSpeed = 1.35f,
            normalSpeed = 1.10f,
            longSpeed = 1.10f,
            longTextThresholdWords = 120
        )

        private val MID_TIER_PROFILE = SynthesisProfile(
            name = "mid",
            maxChunkChars = 120,
            maxChunkWords = 20,
            maxTotalChunks = 80,
            queueCapacity = 2,
            shortSpeed = 1.35f,
            normalSpeed = 1.10f,
            longSpeed = 1.10f,
            longTextThresholdWords = 140
        )

        private val HIGH_TIER_PROFILE = SynthesisProfile(
            name = "high",
            maxChunkChars = 120,
            maxChunkWords = 20,
            maxTotalChunks = 80,
            queueCapacity = 2,
            shortSpeed = 1.35f,
            normalSpeed = 1.10f,
            longSpeed = 1.10f,
            longTextThresholdWords = 160
        )
    }

    private lateinit var binding: ActivityMainBinding
    private val audioPlayer = AudioPlayer()
    private var initialized = false
    private var synthesisJob: Job? = null
    private var runtimeProfile = RuntimeProfile(
        tier = "low",
        threads = 2,
        synthesis = LOW_TIER_PROFILE
    )

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

                    runtimeProfile = chooseRuntimeProfile()

                    val initStart = SystemClock.elapsedRealtimeNanos()
                    val initOk = initWithCpuFallback(paths, runtimeProfile.threads)
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
            val synthesis = runtimeProfile.synthesis
            binding.speakButton.isEnabled = true
            binding.statusText.text = "Ready (${NativeTts.sampleRate()} Hz, $provider/$threads, ${synthesis.name} profile)"
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
                    val requestStartNs = SystemClock.elapsedRealtimeNanos()
                    val synthesis = runtimeProfile.synthesis
                    val inputWords = countWords(text)

                    val rawChunks = splitForSynthesis(
                        text = text,
                        maxChunkChars = synthesis.maxChunkChars,
                        maxChunkWords = synthesis.maxChunkWords
                    )
                    val chunks = if (rawChunks.size > synthesis.maxTotalChunks) {
                        Log.w(
                            TAG,
                            "Chunk count ${rawChunks.size} exceeds cap ${synthesis.maxTotalChunks}. Truncating request."
                        )
                        rawChunks.take(synthesis.maxTotalChunks)
                    } else {
                        rawChunks
                    }

                    val sampleRate = NativeTts.sampleRate()
                    val chunkQueue = Channel<GeneratedChunk>(capacity = synthesis.queueCapacity)

                    val totalGenerateMs = AtomicLong(0L)
                    val totalSamples = AtomicInteger(0)
                    val playableChunks = AtomicInteger(0)
                    val generatedChunks = AtomicInteger(0)
                    val firstAudioMs = AtomicLong(-1L)

                    audioPlayer.stop()

                    val producer = launch(Dispatchers.Default) {
                        try {
                            for ((index, chunkText) in chunks.withIndex()) {
                                if (!isActive) break

                                withContext(Dispatchers.Main) {
                                    binding.statusText.text = "Generating ${index + 1}/${chunks.size}..."
                                }

                                val speed = chooseSpeed(chunkText, inputWords, synthesis)
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

                                try {
                                    chunkQueue.send(
                                        GeneratedChunk(
                                            index = index + 1,
                                            total = chunks.size,
                                            samples = samples
                                        )
                                    )
                                } catch (closed: ClosedSendChannelException) {
                                    Log.w(TAG, "Playback queue closed. Stopping generation.")
                                    break
                                }
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

                                if (firstAudioMs.get() < 0L) {
                                    val nowMs = (SystemClock.elapsedRealtimeNanos() - requestStartNs) / 1_000_000
                                    firstAudioMs.compareAndSet(-1L, nowMs)
                                }

                                val playStart = SystemClock.elapsedRealtimeNanos()
                                val playOk = audioPlayer.play(chunk.samples, sampleRate)
                                val playMs = (SystemClock.elapsedRealtimeNanos() - playStart) / 1_000_000

                                if (!playOk) {
                                    hadFailure.set(true)
                                    Log.e(TAG, "Playback failed for chunk ${chunk.index}/${chunk.total}")
                                    chunkQueue.cancel(CancellationException("Playback failed"))
                                    break
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
                            val cancelCause = CancellationException("Consumer failed").apply {
                                initCause(t)
                            }
                            chunkQueue.cancel(cancelCause)
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

                    val wallMs = (SystemClock.elapsedRealtimeNanos() - requestStartNs) / 1_000_000
                    val firstMs = firstAudioMs.get()

                    binding.statusText.text = when {
                        wasCancelled.get() -> "Cancelled"
                        playableChunks.get() > 0 -> "Done (${playableChunks.get()}/${chunks.size} played, first=${if (firstMs >= 0) "${firstMs}ms" else "-"}, wall=${wallMs}ms, gen=${totalGenerateMs.get()}ms)"
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

    private fun chooseRuntimeProfile(): RuntimeProfile {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memClass = activityManager.memoryClass
        val cores = Runtime.getRuntime().availableProcessors()

        val hw = Build.HARDWARE.lowercase()
        val board = Build.BOARD.lowercase()
        val product = Build.PRODUCT.lowercase()
        val isSd460Like = hw.contains("sm4250") || board.contains("sm4250") ||
            product.contains("sm4250") || hw.contains("sdm460") || board.contains("sdm460")

        val runtimeProfile = when {
            isSd460Like || memClass <= 192 || cores <= 4 -> RuntimeProfile(
                tier = "low",
                threads = 2,
                synthesis = LOW_TIER_PROFILE
            )

            cores >= 8 && memClass >= 256 -> RuntimeProfile(
                tier = "high",
                threads = 4,
                synthesis = HIGH_TIER_PROFILE
            )

            else -> RuntimeProfile(
                tier = "mid",
                threads = 4,
                synthesis = MID_TIER_PROFILE
            )
        }

        Log.i(
            TAG,
            "Device profile: hardware=${Build.HARDWARE}, board=${Build.BOARD}, memClass=${memClass}MB, cores=$cores -> cpu/${runtimeProfile.threads}, synthesis=${runtimeProfile.synthesis.name}(chars=${runtimeProfile.synthesis.maxChunkChars}, words=${runtimeProfile.synthesis.maxChunkWords}, maxChunks=${runtimeProfile.synthesis.maxTotalChunks}, queue=${runtimeProfile.synthesis.queueCapacity})"
        )

        return runtimeProfile
    }

    private fun chooseSpeed(text: String, totalInputWords: Int, profile: SynthesisProfile): Float {
        val words = countWords(text)
        return when {
            text.length <= 32 && words <= 5 -> profile.shortSpeed
            totalInputWords >= profile.longTextThresholdWords && words >= 8 -> profile.longSpeed
            else -> profile.normalSpeed
        }
    }

    private fun splitForSynthesis(
        text: String,
        maxChunkChars: Int,
        maxChunkWords: Int
    ): List<String> {
        if (text.length <= maxChunkChars && countWords(text) <= maxChunkWords) {
            return listOf(text)
        }

        val sentences = text.split(SENTENCE_SPLIT_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (sentences.isEmpty()) {
            return splitLongSentence(text, maxChunkChars, maxChunkWords)
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

            if (sentence.length > maxChunkChars || sentenceWords > maxChunkWords) {
                flushCurrent()
                chunks += splitLongSentence(sentence, maxChunkChars, maxChunkWords)
                continue
            }

            val nextLen = if (current.isEmpty()) sentence.length else current.length + 1 + sentence.length
            if (nextLen > maxChunkChars || currentWords + sentenceWords > maxChunkWords) {
                flushCurrent()
            }

            if (current.isNotEmpty()) {
                current.append(' ')
            }
            current.append(sentence)
            currentWords += sentenceWords
        }

        flushCurrent()
        return if (chunks.isNotEmpty()) chunks else splitLongSentence(text, maxChunkChars, maxChunkWords)
    }

    private fun splitLongSentence(
        text: String,
        maxChunkChars: Int,
        maxChunkWords: Int
    ): List<String> {
        val words = text.split(WHITESPACE_REGEX)
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
            if ((nextLen > maxChunkChars || currentWords >= maxChunkWords) && current.isNotEmpty()) {
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
        return text.split(WHITESPACE_REGEX).count { it.isNotEmpty() }
    }

    override fun onDestroy() {
        synthesisJob?.cancel()
        audioPlayer.stop()
        NativeTts.release()
        super.onDestroy()
    }
}
