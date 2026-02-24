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
import java.util.Locale

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

    private data class ProsodyProfile(
        val lengthScale: Float,
        val noiseScale: Float,
        val silenceScale: Float
    )

    private data class RuntimeProfile(
        val tier: String,
        val threads: Int,
        val synthesis: SynthesisProfile,
        val prosody: ProsodyProfile,
        val useA23ShortUtteranceWorkaround: Boolean
    )

    companion object {
        private const val TAG = "MatchaTts"
        private const val ENGINE_PROVIDER = "cpu"
        private const val MAX_INPUT_CHARS = 8000

        private val SENTENCE_SPLIT_REGEX = Regex("(?<=[.!?;:])\\s+")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val TERMINAL_PUNCTUATION = setOf('.', '!', '?', ';', ':')
        private val CLAUSE_TERMINATORS = setOf(',', ';', ':')
        private val CLAUSE_BREAK_WORDS = setOf(
            "and", "but", "or", "because", "so", "while", "though", "although",
            "however", "therefore", "meanwhile"
        )

        private const val SHORT_UTTERANCE_MAX_MS = 1800L
        private const val SHORT_UTTERANCE_PREROLL_MS = 320L
        private const val SHORT_UTTERANCE_POSTROLL_MS = 180L
        private const val VERY_SHORT_SPEED = 0.96f

        private val LOW_TIER_PROSODY = ProsodyProfile(
            lengthScale = 1.03f,
            noiseScale = 0.58f,
            silenceScale = 0.17f
        )

        private val MID_TIER_PROSODY = ProsodyProfile(
            lengthScale = 1.00f,
            noiseScale = 0.64f,
            silenceScale = 0.15f
        )

        private val HIGH_TIER_PROSODY = ProsodyProfile(
            lengthScale = 1.01f,
            noiseScale = 0.62f,
            silenceScale = 0.16f
        )

        private val A23_PROSODY = ProsodyProfile(
            lengthScale = 1.02f,
            noiseScale = 0.56f,
            silenceScale = 0.18f
        )

        private val LOW_TIER_PROFILE = SynthesisProfile(
            name = "low",
            maxChunkChars = 140,
            maxChunkWords = 24,
            maxTotalChunks = 80,
            queueCapacity = 2,
            shortSpeed = 1.00f,
            normalSpeed = 0.98f,
            longSpeed = 0.98f,
            longTextThresholdWords = 120
        )

        private val MID_TIER_PROFILE = SynthesisProfile(
            name = "mid",
            maxChunkChars = 170,
            maxChunkWords = 28,
            maxTotalChunks = 80,
            queueCapacity = 2,
            shortSpeed = 1.01f,
            normalSpeed = 0.99f,
            longSpeed = 0.99f,
            longTextThresholdWords = 140
        )

        private val HIGH_TIER_PROFILE = SynthesisProfile(
            name = "high",
            maxChunkChars = 190,
            maxChunkWords = 32,
            maxTotalChunks = 80,
            queueCapacity = 2,
            shortSpeed = 1.02f,
            normalSpeed = 1.00f,
            longSpeed = 1.00f,
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
        synthesis = LOW_TIER_PROFILE,
        prosody = LOW_TIER_PROSODY,
        useA23ShortUtteranceWorkaround = false
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
                    audioPlayer.setLowLatencyBufferMode(runtimeProfile.useA23ShortUtteranceWorkaround)

                    val prosody = runtimeProfile.prosody
                    val prosodyOk = NativeTts.setProsodyConfig(
                        prosody.lengthScale,
                        prosody.noiseScale,
                        prosody.silenceScale
                    )
                    if (!prosodyOk) {
                        Log.w(TAG, "Native prosody config failed. Using engine defaults.")
                    }

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
                    val normalizedText = normalizeForSpeech(text)
                    if (normalizedText != text) {
                        Log.i(TAG, "Input normalized for speech (chars=${text.length}->${normalizedText.length})")
                    }
                    val inputWords = countWords(normalizedText)

                    val rawChunks = splitForSynthesis(
                        text = normalizedText,
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
                    val totalPlaybackMs = AtomicLong(0L)
                    val maxGenerateMs = AtomicLong(0L)
                    val maxPlaybackMs = AtomicLong(0L)
                    val totalSamples = AtomicInteger(0)
                    val playableChunks = AtomicInteger(0)
                    val generatedChunks = AtomicInteger(0)
                    val firstAudioMs = AtomicLong(-1L)

                    if (runtimeProfile.useA23ShortUtteranceWorkaround) {
                        audioPlayer.resetForRequest(sampleRate)
                    }

                    val producer = launch(Dispatchers.Default) {
                        try {
                            for ((index, chunkText) in chunks.withIndex()) {
                                if (!isActive) break

                                withContext(Dispatchers.Main) {
                                    binding.statusText.text = "Generating ${index + 1}/${chunks.size}..."
                                }

                                val preparedChunk = if (runtimeProfile.useA23ShortUtteranceWorkaround) {
                                    ensureTerminalPunctuation(chunkText.trim())
                                } else {
                                    chunkText.trim()
                                }

                                val speed = chooseSpeed(
                                    text = preparedChunk,
                                    totalInputWords = inputWords,
                                    profile = synthesis,
                                    applyVeryShortOverride = runtimeProfile.useA23ShortUtteranceWorkaround
                                )

                                val genStart = SystemClock.elapsedRealtimeNanos()
                                val samples = runCatching { NativeTts.generate(preparedChunk, speed) }
                                    .onFailure { Log.e(TAG, "Generate failed for chunk ${index + 1}", it) }
                                    .getOrDefault(FloatArray(0))
                                val genMs = (SystemClock.elapsedRealtimeNanos() - genStart) / 1_000_000

                                totalGenerateMs.addAndGet(genMs)
                                if (genMs > maxGenerateMs.get()) {
                                    maxGenerateMs.set(genMs)
                                }
                                generatedChunks.incrementAndGet()

                                Log.i(
                                    TAG,
                                    "Generate chunk ${index + 1}/${chunks.size} took ${genMs} ms for ${samples.size} samples (speed=$speed, chars=${preparedChunk.length})"
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

                                val playbackSamples = if (runtimeProfile.useA23ShortUtteranceWorkaround) {
                                    withShortUtterancePreroll(
                                        samples = chunk.samples,
                                        sampleRate = sampleRate,
                                        chunkIndex = chunk.index,
                                        totalChunks = chunk.total
                                    )
                                } else {
                                    chunk.samples
                                }

                                val playStart = SystemClock.elapsedRealtimeNanos()
                                val playOk = audioPlayer.play(playbackSamples, sampleRate)
                                val playMs = (SystemClock.elapsedRealtimeNanos() - playStart) / 1_000_000

                                if (!playOk) {
                                    hadFailure.set(true)
                                    Log.e(TAG, "Playback failed for chunk ${chunk.index}/${chunk.total}")
                                    chunkQueue.cancel(CancellationException("Playback failed"))
                                    break
                                }
                                totalPlaybackMs.addAndGet(playMs)
                                if (playMs > maxPlaybackMs.get()) {
                                    maxPlaybackMs.set(playMs)
                                }
                                totalSamples.addAndGet(playbackSamples.size)
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
                    val generatedCount = generatedChunks.get()
                    val playedCount = playableChunks.get()
                    val genAvgMs = if (generatedCount > 0) totalGenerateMs.get() / generatedCount else 0L
                    val playAvgMs = if (playedCount > 0) totalPlaybackMs.get() / playedCount else 0L
                    val audioSeconds = if (sampleRate > 0) {
                        totalSamples.get().toDouble() / sampleRate.toDouble()
                    } else {
                        0.0
                    }
                    val genRtf = if (audioSeconds > 0.0) {
                        (totalGenerateMs.get().toDouble() / 1000.0) / audioSeconds
                    } else {
                        0.0
                    }

                    Log.i(
                        TAG,
                        "Request summary: chunks=$playedCount/${chunks.size}, first=${if (firstMs >= 0) "${firstMs}ms" else "-"}, wall=${wallMs}ms, gen_total=${totalGenerateMs.get()}ms, gen_avg=${genAvgMs}ms, gen_max=${maxGenerateMs.get()}ms, play_total=${totalPlaybackMs.get()}ms, play_avg=${playAvgMs}ms, play_max=${maxPlaybackMs.get()}ms, samples=${totalSamples.get()}, rtf=${String.format(Locale.US, "%.3f", genRtf)}"
                    )

                    binding.statusText.text = when {
                        wasCancelled.get() -> "Cancelled"
                        playedCount > 0 -> "Done ($playedCount/${chunks.size} played, first=${if (firstMs >= 0) "${firstMs}ms" else "-"}, wall=${wallMs}ms, gen=${totalGenerateMs.get()}ms, play=${totalPlaybackMs.get()}ms, rtf=${String.format(Locale.US, "%.2f", genRtf)})"
                        generatedCount > 0 -> "Generated audio but playback failed"
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
        val manufacturer = Build.MANUFACTURER.lowercase()
        val model = Build.MODEL.lowercase()
        val device = Build.DEVICE.lowercase()

        val isSd460Like = hw.contains("sm4250") || board.contains("sm4250") ||
            product.contains("sm4250") || hw.contains("sdm460") || board.contains("sdm460")

        val isSamsungA23 = manufacturer.contains("samsung") && (
            model.contains("a23") ||
                model.contains("sm-a23") ||
                model.contains("sm-a235") ||
                model.contains("sm-a236") ||
                device.contains("a23") ||
                product.contains("a23") ||
                product.contains("a235") ||
                product.contains("a236")
            )

        val runtimeProfile = when {
            isSd460Like || memClass <= 192 || cores <= 4 -> RuntimeProfile(
                tier = "low",
                threads = 2,
                synthesis = LOW_TIER_PROFILE,
                prosody = LOW_TIER_PROSODY,
                useA23ShortUtteranceWorkaround = isSamsungA23
            )

            cores >= 8 && memClass >= 256 -> RuntimeProfile(
                tier = "high",
                threads = 4,
                synthesis = HIGH_TIER_PROFILE,
                prosody = HIGH_TIER_PROSODY,
                useA23ShortUtteranceWorkaround = isSamsungA23
            )

            else -> RuntimeProfile(
                tier = "mid",
                threads = 4,
                synthesis = MID_TIER_PROFILE,
                prosody = MID_TIER_PROSODY,
                useA23ShortUtteranceWorkaround = isSamsungA23
            )
        }


        val tunedProfile = if (isSamsungA23) {
            runtimeProfile.copy(prosody = A23_PROSODY)
        } else {
            runtimeProfile
        }

        Log.i(
            TAG,
            "Device profile: hardware=${Build.HARDWARE}, board=${Build.BOARD}, model=${Build.MODEL}, memClass=${memClass}MB, cores=$cores -> cpu/${tunedProfile.threads}, synthesis=${tunedProfile.synthesis.name}(chars=${tunedProfile.synthesis.maxChunkChars}, words=${tunedProfile.synthesis.maxChunkWords}, maxChunks=${tunedProfile.synthesis.maxTotalChunks}, queue=${tunedProfile.synthesis.queueCapacity}), prosody=L${tunedProfile.prosody.lengthScale}/N${tunedProfile.prosody.noiseScale}/S${tunedProfile.prosody.silenceScale}, a23_workaround=${tunedProfile.useA23ShortUtteranceWorkaround}"
        )

        return tunedProfile
    }

    private fun chooseSpeed(
        text: String,
        totalInputWords: Int,
        profile: SynthesisProfile,
        applyVeryShortOverride: Boolean
    ): Float {
        val words = countWords(text)
        return when {
            applyVeryShortOverride && words <= 2 -> VERY_SHORT_SPEED
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
        val softBreakWordThreshold = maxOf(8, (maxChunkWords * 9) / 10)
        val punctuationBreakThreshold = maxOf(7, (maxChunkWords * 3) / 4)

        fun flushCurrent() {
            if (current.isNotEmpty()) {
                chunks += current.toString().trim()
                current.clear()
                currentWords = 0
            }
        }

        for (word in words) {
            val nextLen = if (current.isEmpty()) word.length else current.length + 1 + word.length
            val hardLimitReached = nextLen > maxChunkChars || currentWords >= maxChunkWords
            if (hardLimitReached && current.isNotEmpty()) {
                flushCurrent()
            }

            if (current.isNotEmpty()) {
                current.append(' ')
            }
            current.append(word)
            currentWords += 1

            val token = word.trim { !it.isLetterOrDigit() }.lowercase()
            val hasClausePunctuation = word.lastOrNull() in CLAUSE_TERMINATORS
            val shouldSoftBreakOnWord = currentWords >= softBreakWordThreshold && token in CLAUSE_BREAK_WORDS
            val shouldSoftBreakOnPunctuation = hasClausePunctuation && currentWords >= punctuationBreakThreshold

            if (shouldSoftBreakOnWord || shouldSoftBreakOnPunctuation) {
                flushCurrent()
            }
        }

        flushCurrent()
        return chunks
    }

    private fun normalizeForSpeech(text: String): String {
        if (text.isBlank()) {
            return text
        }

        var normalized = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        val protectedTokens = LinkedHashMap<String, String>()

        fun protect(pattern: Regex, label: String) {
            normalized = pattern.replace(normalized) { match ->
                val token = "PROTECTED${label}${protectedTokens.size}TOKEN"
                protectedTokens[token] = match.value
                token
            }
        }

        protect(Regex("https?://\\S+|www\\.\\S+"), "URL")
        protect(Regex("\\b[\\w.+%-]+@[\\w.-]+\\.[A-Za-z]{2,}\\b"), "MAIL")
        protect(Regex("\\bv?\\d+(?:\\.\\d+){1,3}\\b"), "VER")
        protect(Regex("\\b\\d+\\.\\d+\\b"), "DEC")

        normalized = normalized
            .replace(Regex("(?m)^\\s*[-*•▪◦●◆■]+\\s+"), "")
            .replace(Regex("(?i)\\be\\.g\\.?\\b"), " for example ")
            .replace(Regex("(?i)\\bi\\.e\\.?\\b"), " that is ")
            .replace(Regex("(?i)\\betc\\.?\\b"), " et cetera ")
            .replace(Regex("(?i)\\bvs\\.?\\b"), " versus ")
            .replace(Regex("(?i)\\bet\\s+al\\.?\\b"), " and others ")
            .replace(Regex("(?i)\\bdr\\.?\\b"), " doctor ")
            .replace(Regex("(?i)\\bmr\\.?\\b"), " mister ")
            .replace(Regex("(?i)\\bmrs\\.?\\b"), " missus ")
            .replace(Regex("(?i)\\bms\\.?\\b"), " miss ")
            .replace(Regex("(?i)\\bprof\\.?\\b"), " professor ")
            .replace(Regex("(?i)\\bapprox\\.?\\b"), " approximately ")
            .replace(Regex("[\\n]+"), ". ")
            .replace(Regex("[\\[\\]{}<>]"), " ")
            .replace(Regex("(?<=[\\p{L}\\p{N}])-(?=[\\p{L}\\p{N}])"), " ")
            .replace(Regex("(?<=[\\p{L}\\p{N}])/(?=[\\p{L}\\p{N}])"), " or ")
            .replace(Regex("(?<=\\s)-(?=\\s|$)"), ", ")
            .replace("(", ", ")
            .replace(")", ", ")
            .replace(":", ", ")
            .replace(";", ", ")
            .replace("—", ", ")
            .replace("–", ", ")
            .replace("…", ". ")
            .replace("\\", " ")
            .replace("\"", "")
            .replace("“", "")
            .replace("”", "")
            .replace(Regex("[#*_`|~]+"), " ")
            .replace(Regex("(?<=\\s)\\.(?=\\s)"), " ")
            .replace(Regex("[,;:]{2,}"), ", ")
            .replace(Regex("[.!?]{2,}"), ". ")
            .replace(Regex("\\s*([,])\\s*"), ", ")
            .replace(Regex("\\s*([.!?])\\s*"), "$1 ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (protectedTokens.isNotEmpty()) {
            for ((token, value) in protectedTokens) {
                normalized = normalized.replace(token, value)
            }
        }

        return normalized
    }

    private fun ensureTerminalPunctuation(text: String): String {
        if (text.isEmpty()) return text
        if (TERMINAL_PUNCTUATION.contains(text.last())) return text
        return "$text."
    }

    private fun withShortUtterancePreroll(
        samples: FloatArray,
        sampleRate: Int,
        chunkIndex: Int,
        totalChunks: Int
    ): FloatArray {
        if (samples.isEmpty() || sampleRate <= 0) return samples
        if (totalChunks != 1 || chunkIndex != 1) return samples

        val durationMs = (samples.size * 1000L) / sampleRate
        if (durationMs >= SHORT_UTTERANCE_MAX_MS) return samples

        val preRollSamples = ((sampleRate * SHORT_UTTERANCE_PREROLL_MS) / 1000L)
            .toInt()
            .coerceAtLeast(1)
        val postRollSamples = ((sampleRate * SHORT_UTTERANCE_POSTROLL_MS) / 1000L)
            .toInt()
            .coerceAtLeast(1)

        val out = FloatArray(preRollSamples + samples.size + postRollSamples)
        System.arraycopy(samples, 0, out, preRollSamples, samples.size)

        Log.i(
            TAG,
            "Applied short-utterance guard (duration=${durationMs}ms, pre=${SHORT_UTTERANCE_PREROLL_MS}ms, post=${SHORT_UTTERANCE_POSTROLL_MS}ms, samples=${samples.size}->${out.size})"
        )

        return out
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
