package com.example.tts

import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.tts.databinding.ActivityMainBinding
import com.example.tts.databinding.DialogSettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs

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

    private data class MatchaSynthesisSpeedConfig(
        val shortSpeed: Float,
        val normalSpeed: Float,
        val longSpeed: Float
    )

    private data class GoogleSpeechConfig(
        val rate: Float,
        val pitch: Float
    )

    private enum class SpeechRoute {
        MATCHA_ENGLISH,
        GOOGLE_HINDI,
        GOOGLE_TAMIL
    }

    companion object {
        private const val TAG = "MatchaTts"
        private const val ENGINE_PROVIDER = "cpu"
        private const val MAX_INPUT_CHARS = 8000
        private const val SETTINGS_PREFS = "tts_settings"

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
        private val DEVANAGARI_RANGE = 0x0900..0x097F
        private val TAMIL_RANGE = 0x0B80..0x0BFF
        private const val GOOGLE_TTS_ENGINE = "com.google.android.tts"
        private const val HINDI_GOOGLE_VOICE = "hi-in-x-hic-lstm-embedded"
        private const val TAMIL_GOOGLE_VOICE = "ta-in-x-tac-lstm-embedded"
        private const val DEFAULT_GOOGLE_RATE = 1.0f
        private const val DEFAULT_GOOGLE_PITCH = 1.0f
        private const val MIN_GOOGLE_RATE = 0.50f
        private const val MAX_GOOGLE_RATE = 1.50f
        private const val MIN_GOOGLE_PITCH = 0.50f
        private const val MAX_GOOGLE_PITCH = 1.50f
        private const val MIN_MATCHA_SPEED = 0.70f
        private const val MAX_MATCHA_SPEED = 1.30f
        private const val PREF_MATCHA_LENGTH = "matcha_length_scale"
        private const val PREF_MATCHA_NOISE = "matcha_noise_scale"
        private const val PREF_MATCHA_SILENCE = "matcha_silence_scale"
        private const val PREF_MATCHA_SHORT_SPEED = "matcha_short_speed"
        private const val PREF_MATCHA_NORMAL_SPEED = "matcha_normal_speed"
        private const val PREF_MATCHA_LONG_SPEED = "matcha_long_speed"
        private const val PREF_GOOGLE_HI_RATE = "google_hi_rate"
        private const val PREF_GOOGLE_HI_PITCH = "google_hi_pitch"
        private const val PREF_GOOGLE_TA_RATE = "google_ta_rate"
        private const val PREF_GOOGLE_TA_PITCH = "google_ta_pitch"

        // Human-like baseline: keep Matcha near neutral speech rate, with moderate
        // stochasticity and sentence pause duration.
        private val HUMANLIKE_BASE_PROSODY = ProsodyProfile(
            lengthScale = 1.00f,
            noiseScale = 0.67f,
            silenceScale = 0.20f
        )

        private val LOW_TIER_PROSODY = HUMANLIKE_BASE_PROSODY.copy(
            noiseScale = 0.62f
        )

        private val MID_TIER_PROSODY = HUMANLIKE_BASE_PROSODY.copy(
            noiseScale = 0.64f
        )

        private val HIGH_TIER_PROSODY = HUMANLIKE_BASE_PROSODY

        private val A23_PROSODY = HUMANLIKE_BASE_PROSODY.copy(
            noiseScale = 0.60f,
            silenceScale = 0.21f
        )

        private val NOTHING_2A_PAD5_PROSODY = HUMANLIKE_BASE_PROSODY

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

        private val NOTHING_2A_PROFILE = SynthesisProfile(
            name = "nothing2a",
            maxChunkChars = 190,
            maxChunkWords = 32,
            maxTotalChunks = 80,
            queueCapacity = 2,
            shortSpeed = 1.02f,
            normalSpeed = 1.00f,
            longSpeed = 1.00f,
            longTextThresholdWords = 160
        )

        private const val DEFAULT_WARMUP_SPEED = 1.00f
    }

    private lateinit var binding: ActivityMainBinding
    private val audioPlayer = AudioPlayer()
    private var googleTts: TextToSpeech? = null
    private var matchaPaths: MatchaPaths? = null
    private var initialized = false
    private var applyingProsody = false
    private var synthesisJob: Job? = null
    private var defaultDeviceProsody = LOW_TIER_PROSODY
    private var defaultSynthesisSpeeds = MatchaSynthesisSpeedConfig(
        shortSpeed = LOW_TIER_PROFILE.shortSpeed,
        normalSpeed = LOW_TIER_PROFILE.normalSpeed,
        longSpeed = LOW_TIER_PROFILE.longSpeed
    )
    private var currentProsody = LOW_TIER_PROSODY
    private var currentSynthesisSpeeds = defaultSynthesisSpeeds
    private var hindiSpeechConfig = GoogleSpeechConfig(
        rate = DEFAULT_GOOGLE_RATE,
        pitch = DEFAULT_GOOGLE_PITCH
    )
    private var tamilSpeechConfig = GoogleSpeechConfig(
        rate = DEFAULT_GOOGLE_RATE,
        pitch = DEFAULT_GOOGLE_PITCH
    )
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
        setSupportActionBar(binding.topAppBar)

        binding.speakButton.isEnabled = false
        binding.statusText.text = "Preparing model assets..."
        loadGoogleSpeechSettings()

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val assetsStart = SystemClock.elapsedRealtimeNanos()
                    val paths = AssetUtils.ensureMatchaAssets(this@MainActivity)
                    val assetsMs = (SystemClock.elapsedRealtimeNanos() - assetsStart) / 1_000_000
                    Log.i(TAG, "Asset copy/check took ${assetsMs} ms")

                    matchaPaths = paths
                    runtimeProfile = chooseRuntimeProfile()
                    defaultDeviceProsody = runtimeProfile.prosody
                    defaultSynthesisSpeeds = synthesisSpeedsFromProfile(runtimeProfile.synthesis)
                    currentProsody = loadSavedProsody(defaultDeviceProsody)
                    currentSynthesisSpeeds = loadSavedSynthesisSpeeds(defaultSynthesisSpeeds)
                    runtimeProfile = runtimeProfile.copy(
                        prosody = currentProsody,
                        synthesis = profileWithSynthesisSpeeds(
                            runtimeProfile.synthesis,
                            currentSynthesisSpeeds
                        )
                    )
                    Log.i(
                        TAG,
                        "Effective settings: prosody=${formatProsodySummary(currentProsody)} (default=${formatProsodySummary(defaultDeviceProsody)}), synthesis=${formatSynthesisSpeedSummary(currentSynthesisSpeeds)} (default=${formatSynthesisSpeedSummary(defaultSynthesisSpeeds)})"
                    )
                    audioPlayer.setLowLatencyBufferMode(runtimeProfile.useA23ShortUtteranceWorkaround)

                    val prosody = currentProsody
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

                    if (initOk) {
                        val warmupStart = SystemClock.elapsedRealtimeNanos()
                        val warmupSpeed = DEFAULT_WARMUP_SPEED
                        val warmupOk = runCatching {
                            NativeTts.warmup(warmupSpeed)
                        }.getOrElse {
                            Log.e(TAG, "Native warmup failed", it)
                            false
                        }
                        val warmupMs = (SystemClock.elapsedRealtimeNanos() - warmupStart) / 1_000_000
                        Log.i(TAG, "Native warmup took ${warmupMs} ms (ok=$warmupOk, speed=$warmupSpeed)")
                    }

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
                    val route = chooseSpeechRoute(text)
                    if (route != SpeechRoute.MATCHA_ENGLISH) {
                        val routeLabel = when (route) {
                            SpeechRoute.GOOGLE_HINDI -> "Hindi (Google)"
                            SpeechRoute.GOOGLE_TAMIL -> "Tamil (Google)"
                            SpeechRoute.MATCHA_ENGLISH -> "English (Matcha)"
                        }
                        withContext(Dispatchers.Main) {
                            binding.statusText.text = "Speaking via $routeLabel..."
                        }

                        audioPlayer.stop()

                        val startNs = SystemClock.elapsedRealtimeNanos()
                        val ok = speakWithGoogleTts(text, route)
                        val wallMs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000

                        if (!ok) {
                            hadFailure.set(true)
                            binding.statusText.text = "Google TTS failed ($routeLabel)"
                        } else {
                            binding.statusText.text = "Done ($routeLabel, wall=${wallMs}ms)"
                        }
                        return@launch
                    }

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

                    // Always flush pending queued samples from any previous request.
                    // This prevents stale short utterances from being heard on the next tap.
                    audioPlayer.resetForRequest(sampleRate)

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
                                    .onFailure {
                                        Log.e(
                                            TAG,
                                            "Generate failed for chunk ${index + 1}",
                                            it
                                        )
                                    }
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
                                var playMs = (SystemClock.elapsedRealtimeNanos() - playStart) / 1_000_000

                                if (!playOk) {
                                    hadFailure.set(true)
                                    Log.e(TAG, "Playback failed for chunk ${chunk.index}/${chunk.total}")
                                    chunkQueue.cancel(CancellationException("Playback failed"))
                                    break
                                }

                                val audioMs = (playbackSamples.size * 1000L) / sampleRate
                                // AudioTrack.write() returns when data is queued, not necessarily audible.
                                // For single short clips, wait for expected playout to complete so a
                                // follow-up request does not replay delayed previous audio.
                                if (chunk.total == 1 && audioMs <= SHORT_UTTERANCE_MAX_MS) {
                                    val drainWaitMs = (audioMs - playMs + 40L).coerceAtLeast(0L)
                                    if (drainWaitMs > 0L) {
                                        delay(drainWaitMs)
                                        playMs += drainWaitMs
                                        Log.i(
                                            TAG,
                                            "Applied short-utterance playback drain wait ${drainWaitMs} ms (audio=${audioMs} ms)"
                                        )
                                    }
                                }

                                totalPlaybackMs.addAndGet(playMs)
                                if (playMs > maxPlaybackMs.get()) {
                                    maxPlaybackMs.set(playMs)
                                }
                                totalSamples.addAndGet(playbackSamples.size)
                                playableChunks.incrementAndGet()

                                Log.i(
                                    TAG,
                                    "Playback write chunk ${chunk.index}/${chunk.total} took ${playMs} ms (audio=${audioMs} ms)"
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
                        googleTts?.stop()
                    }
                    synthesisJob = null
                    binding.speakButton.isEnabled = initialized
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                showSettingsDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSettingsDialog() {
        val dialogBinding = DialogSettingsBinding.inflate(layoutInflater)
        configureSettingsSliderRanges(dialogBinding)
        setSettingsSliderValues(
            dialogBinding = dialogBinding,
            prosody = currentProsody,
            synthesisConfig = currentSynthesisSpeeds,
            hindiConfig = hindiSpeechConfig,
            tamilConfig = tamilSpeechConfig
        )

        fun refreshLabels() {
            dialogBinding.settingsMatchaProsodyDefaultsText.text =
                "Defaults: ${formatProsodySummary(defaultDeviceProsody)}"
            dialogBinding.settingsMatchaSynthesisDefaultsText.text =
                "Defaults: ${formatSynthesisSpeedSummary(defaultSynthesisSpeeds)}"

            dialogBinding.settingsMatchaLengthLabelText.text =
                "Length scale: ${formatScale(dialogBinding.settingsMatchaLengthSlider.value)}"
            dialogBinding.settingsMatchaNoiseLabelText.text =
                "Noise scale: ${formatScale(dialogBinding.settingsMatchaNoiseSlider.value)}"
            dialogBinding.settingsMatchaSilenceLabelText.text =
                "Silence scale: ${formatScale(dialogBinding.settingsMatchaSilenceSlider.value)}"
            dialogBinding.settingsMatchaShortSpeedLabelText.text =
                "Short speed: ${formatScale(dialogBinding.settingsMatchaShortSpeedSlider.value)}"
            dialogBinding.settingsMatchaNormalSpeedLabelText.text =
                "Normal speed: ${formatScale(dialogBinding.settingsMatchaNormalSpeedSlider.value)}"
            dialogBinding.settingsMatchaLongSpeedLabelText.text =
                "Long speed: ${formatScale(dialogBinding.settingsMatchaLongSpeedSlider.value)}"
            dialogBinding.settingsHindiRateLabelText.text =
                "Speech rate: ${formatScale(dialogBinding.settingsHindiRateSlider.value)}"
            dialogBinding.settingsHindiPitchLabelText.text =
                "Pitch: ${formatScale(dialogBinding.settingsHindiPitchSlider.value)}"
            dialogBinding.settingsTamilRateLabelText.text =
                "Speech rate: ${formatScale(dialogBinding.settingsTamilRateSlider.value)}"
            dialogBinding.settingsTamilPitchLabelText.text =
                "Pitch: ${formatScale(dialogBinding.settingsTamilPitchSlider.value)}"
        }

        refreshLabels()
        dialogBinding.settingsMatchaLengthSlider.addOnChangeListener { _, _, _ -> refreshLabels() }
        dialogBinding.settingsMatchaNoiseSlider.addOnChangeListener { _, _, _ -> refreshLabels() }
        dialogBinding.settingsMatchaSilenceSlider.addOnChangeListener { _, _, _ -> refreshLabels() }
        dialogBinding.settingsMatchaShortSpeedSlider.addOnChangeListener { _, _, _ -> refreshLabels() }
        dialogBinding.settingsMatchaNormalSpeedSlider.addOnChangeListener { _, _, _ -> refreshLabels() }
        dialogBinding.settingsMatchaLongSpeedSlider.addOnChangeListener { _, _, _ -> refreshLabels() }
        dialogBinding.settingsHindiRateSlider.addOnChangeListener { _, _, _ -> refreshLabels() }
        dialogBinding.settingsHindiPitchSlider.addOnChangeListener { _, _, _ -> refreshLabels() }
        dialogBinding.settingsTamilRateSlider.addOnChangeListener { _, _, _ -> refreshLabels() }
        dialogBinding.settingsTamilPitchSlider.addOnChangeListener { _, _, _ -> refreshLabels() }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_dialog_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.settings_cancel, null)
            .setNeutralButton(R.string.settings_reset_defaults, null)
            .setPositiveButton(R.string.settings_apply, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                setSettingsSliderValues(
                    dialogBinding = dialogBinding,
                    prosody = defaultDeviceProsody,
                    synthesisConfig = defaultSynthesisSpeeds,
                    hindiConfig = GoogleSpeechConfig(
                        rate = DEFAULT_GOOGLE_RATE,
                        pitch = DEFAULT_GOOGLE_PITCH
                    ),
                    tamilConfig = GoogleSpeechConfig(
                        rate = DEFAULT_GOOGLE_RATE,
                        pitch = DEFAULT_GOOGLE_PITCH
                    )
                )
                refreshLabels()
            }

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selectedProsody = ProsodyProfile(
                    lengthScale = dialogBinding.settingsMatchaLengthSlider.value,
                    noiseScale = dialogBinding.settingsMatchaNoiseSlider.value,
                    silenceScale = dialogBinding.settingsMatchaSilenceSlider.value
                )
                val selectedSynthesis = MatchaSynthesisSpeedConfig(
                    shortSpeed = dialogBinding.settingsMatchaShortSpeedSlider.value,
                    normalSpeed = dialogBinding.settingsMatchaNormalSpeedSlider.value,
                    longSpeed = dialogBinding.settingsMatchaLongSpeedSlider.value
                )
                val selectedHindi = GoogleSpeechConfig(
                    rate = dialogBinding.settingsHindiRateSlider.value,
                    pitch = dialogBinding.settingsHindiPitchSlider.value
                )
                val selectedTamil = GoogleSpeechConfig(
                    rate = dialogBinding.settingsTamilRateSlider.value,
                    pitch = dialogBinding.settingsTamilPitchSlider.value
                )

                applySettingsFromDialog(
                    selectedProsody = selectedProsody,
                    selectedSynthesis = selectedSynthesis,
                    selectedHindi = selectedHindi,
                    selectedTamil = selectedTamil
                )
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun configureSettingsSliderRanges(dialogBinding: DialogSettingsBinding) {
        dialogBinding.settingsMatchaLengthSlider.valueFrom = 0.50f
        dialogBinding.settingsMatchaLengthSlider.valueTo = 1.50f
        dialogBinding.settingsMatchaLengthSlider.stepSize = 0.01f

        dialogBinding.settingsMatchaNoiseSlider.valueFrom = 0.10f
        dialogBinding.settingsMatchaNoiseSlider.valueTo = 2.00f
        dialogBinding.settingsMatchaNoiseSlider.stepSize = 0.01f

        dialogBinding.settingsMatchaSilenceSlider.valueFrom = 0.00f
        dialogBinding.settingsMatchaSilenceSlider.valueTo = 0.50f
        dialogBinding.settingsMatchaSilenceSlider.stepSize = 0.01f

        dialogBinding.settingsMatchaShortSpeedSlider.valueFrom = MIN_MATCHA_SPEED
        dialogBinding.settingsMatchaShortSpeedSlider.valueTo = MAX_MATCHA_SPEED
        dialogBinding.settingsMatchaShortSpeedSlider.stepSize = 0.01f

        dialogBinding.settingsMatchaNormalSpeedSlider.valueFrom = MIN_MATCHA_SPEED
        dialogBinding.settingsMatchaNormalSpeedSlider.valueTo = MAX_MATCHA_SPEED
        dialogBinding.settingsMatchaNormalSpeedSlider.stepSize = 0.01f

        dialogBinding.settingsMatchaLongSpeedSlider.valueFrom = MIN_MATCHA_SPEED
        dialogBinding.settingsMatchaLongSpeedSlider.valueTo = MAX_MATCHA_SPEED
        dialogBinding.settingsMatchaLongSpeedSlider.stepSize = 0.01f

        dialogBinding.settingsHindiRateSlider.valueFrom = MIN_GOOGLE_RATE
        dialogBinding.settingsHindiRateSlider.valueTo = MAX_GOOGLE_RATE
        dialogBinding.settingsHindiRateSlider.stepSize = 0.01f

        dialogBinding.settingsHindiPitchSlider.valueFrom = MIN_GOOGLE_PITCH
        dialogBinding.settingsHindiPitchSlider.valueTo = MAX_GOOGLE_PITCH
        dialogBinding.settingsHindiPitchSlider.stepSize = 0.01f

        dialogBinding.settingsTamilRateSlider.valueFrom = MIN_GOOGLE_RATE
        dialogBinding.settingsTamilRateSlider.valueTo = MAX_GOOGLE_RATE
        dialogBinding.settingsTamilRateSlider.stepSize = 0.01f

        dialogBinding.settingsTamilPitchSlider.valueFrom = MIN_GOOGLE_PITCH
        dialogBinding.settingsTamilPitchSlider.valueTo = MAX_GOOGLE_PITCH
        dialogBinding.settingsTamilPitchSlider.stepSize = 0.01f
    }

    private fun setSettingsSliderValues(
        dialogBinding: DialogSettingsBinding,
        prosody: ProsodyProfile,
        synthesisConfig: MatchaSynthesisSpeedConfig,
        hindiConfig: GoogleSpeechConfig,
        tamilConfig: GoogleSpeechConfig
    ) {
        val normalizedProsody = clampProsody(prosody)
        val normalizedSynthesis = normalizeMatchaSynthesisSpeeds(synthesisConfig)
        val normalizedHindi = normalizeGoogleSpeechConfig(hindiConfig)
        val normalizedTamil = normalizeGoogleSpeechConfig(tamilConfig)

        dialogBinding.settingsMatchaLengthSlider.value = normalizedProsody.lengthScale
        dialogBinding.settingsMatchaNoiseSlider.value = normalizedProsody.noiseScale
        dialogBinding.settingsMatchaSilenceSlider.value = normalizedProsody.silenceScale
        dialogBinding.settingsMatchaShortSpeedSlider.value = normalizedSynthesis.shortSpeed
        dialogBinding.settingsMatchaNormalSpeedSlider.value = normalizedSynthesis.normalSpeed
        dialogBinding.settingsMatchaLongSpeedSlider.value = normalizedSynthesis.longSpeed

        dialogBinding.settingsHindiRateSlider.value = normalizedHindi.rate
        dialogBinding.settingsHindiPitchSlider.value = normalizedHindi.pitch
        dialogBinding.settingsTamilRateSlider.value = normalizedTamil.rate
        dialogBinding.settingsTamilPitchSlider.value = normalizedTamil.pitch
    }

    private fun applySettingsFromDialog(
        selectedProsody: ProsodyProfile,
        selectedSynthesis: MatchaSynthesisSpeedConfig,
        selectedHindi: GoogleSpeechConfig,
        selectedTamil: GoogleSpeechConfig
    ) {
        val normalizedSynthesis = normalizeMatchaSynthesisSpeeds(selectedSynthesis)
        val normalizedHindi = normalizeGoogleSpeechConfig(selectedHindi)
        val normalizedTamil = normalizeGoogleSpeechConfig(selectedTamil)

        val synthesisChanged = !sameSynthesisSpeeds(normalizedSynthesis, currentSynthesisSpeeds)
        if (synthesisChanged) {
            currentSynthesisSpeeds = normalizedSynthesis
            runtimeProfile = runtimeProfile.copy(
                synthesis = profileWithSynthesisSpeeds(runtimeProfile.synthesis, normalizedSynthesis)
            )
            saveMatchaSynthesisSpeeds(normalizedSynthesis)
        }

        val googleChanged = normalizedHindi != hindiSpeechConfig || normalizedTamil != tamilSpeechConfig
        if (googleChanged) {
            hindiSpeechConfig = normalizedHindi
            tamilSpeechConfig = normalizedTamil
            saveGoogleSpeechSettings()
        }

        val normalizedProsody = clampProsody(selectedProsody)
        val prosodyChanged = !sameProsody(normalizedProsody, currentProsody)

        if (!prosodyChanged) {
            binding.statusText.text = when {
                synthesisChanged && googleChanged -> "Settings saved (Matcha synthesis + Google Hindi/Tamil updated)"
                synthesisChanged -> "Settings saved (Matcha synthesis updated)"
                googleChanged -> "Settings saved (Google Hindi/Tamil updated)"
                else -> "No setting changes"
            }
            return
        }

        val started = applyMatchaProsody(normalizedProsody)
        if (!started) {
            binding.statusText.text = when {
                synthesisChanged && googleChanged ->
                    "Synthesis + Google saved. Matcha prosody not applied while busy."
                synthesisChanged ->
                    "Synthesis saved. Matcha prosody not applied while busy."
                googleChanged ->
                    "Google settings saved. Matcha prosody not applied while busy."
                else ->
                    "Matcha prosody not applied while busy."
            }
        }
    }

    private fun applyMatchaProsody(selectedProsody: ProsodyProfile): Boolean {
        if (!initialized) {
            binding.statusText.text = "Model is not initialized yet"
            return false
        }

        if (synthesisJob?.isActive == true || applyingProsody) {
            binding.statusText.text = "Wait for current synthesis to finish"
            return false
        }

        val paths = matchaPaths
        if (paths == null) {
            binding.statusText.text = "Matcha assets are not ready"
            return false
        }

        lifecycleScope.launch {
            applyingProsody = true
            binding.speakButton.isEnabled = false
            binding.statusText.text = "Applying Matcha settings..."

            try {
                audioPlayer.stop()
                googleTts?.stop()

                val start = SystemClock.elapsedRealtimeNanos()
                val ok = withContext(Dispatchers.IO) {
                    val setOk = NativeTts.setProsodyConfig(
                        selectedProsody.lengthScale,
                        selectedProsody.noiseScale,
                        selectedProsody.silenceScale
                    )
                    if (!setOk) {
                        Log.e(TAG, "Failed to set prosody config before re-init")
                        return@withContext false
                    }

                    val initOk = initWithCpuFallback(paths, runtimeProfile.threads)
                    if (!initOk) {
                        Log.e(TAG, "Failed to re-init native TTS after prosody change")
                        return@withContext false
                    }

                    runCatching {
                        NativeTts.warmup(DEFAULT_WARMUP_SPEED)
                    }.getOrElse {
                        Log.e(TAG, "Warmup failed after prosody apply", it)
                        false
                    }
                }

                val wallMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
                if (ok) {
                    currentProsody = selectedProsody
                    runtimeProfile = runtimeProfile.copy(prosody = selectedProsody)
                    saveMatchaProsodySettings(selectedProsody)
                    val provider = NativeTts.runtimeProvider()
                    val threads = NativeTts.runtimeThreads()
                    binding.statusText.text =
                        "Matcha settings applied (prosody=${formatProsodySummary(selectedProsody)}, synthesis=${formatSynthesisSpeedSummary(currentSynthesisSpeeds)}, $provider/$threads, ${wallMs}ms)"
                } else {
                    binding.statusText.text = "Matcha settings apply failed. Previous values kept."
                }
            } catch (ce: CancellationException) {
                binding.statusText.text = "Settings apply cancelled"
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "Matcha settings apply failed", t)
                binding.statusText.text = "Matcha settings apply failed (${t.javaClass.simpleName})"
            } finally {
                applyingProsody = false
                binding.speakButton.isEnabled = initialized
            }
        }

        return true
    }

    private fun normalizeGoogleSpeechConfig(config: GoogleSpeechConfig): GoogleSpeechConfig {
        return GoogleSpeechConfig(
            rate = config.rate.coerceIn(MIN_GOOGLE_RATE, MAX_GOOGLE_RATE),
            pitch = config.pitch.coerceIn(MIN_GOOGLE_PITCH, MAX_GOOGLE_PITCH)
        )
    }

    private fun normalizeMatchaSynthesisSpeeds(
        config: MatchaSynthesisSpeedConfig
    ): MatchaSynthesisSpeedConfig {
        return MatchaSynthesisSpeedConfig(
            shortSpeed = config.shortSpeed.coerceIn(MIN_MATCHA_SPEED, MAX_MATCHA_SPEED),
            normalSpeed = config.normalSpeed.coerceIn(MIN_MATCHA_SPEED, MAX_MATCHA_SPEED),
            longSpeed = config.longSpeed.coerceIn(MIN_MATCHA_SPEED, MAX_MATCHA_SPEED)
        )
    }

    private fun synthesisSpeedsFromProfile(profile: SynthesisProfile): MatchaSynthesisSpeedConfig {
        return MatchaSynthesisSpeedConfig(
            shortSpeed = profile.shortSpeed,
            normalSpeed = profile.normalSpeed,
            longSpeed = profile.longSpeed
        )
    }

    private fun profileWithSynthesisSpeeds(
        profile: SynthesisProfile,
        speeds: MatchaSynthesisSpeedConfig
    ): SynthesisProfile {
        val normalized = normalizeMatchaSynthesisSpeeds(speeds)
        return profile.copy(
            shortSpeed = normalized.shortSpeed,
            normalSpeed = normalized.normalSpeed,
            longSpeed = normalized.longSpeed
        )
    }

    private fun clampProsody(prosody: ProsodyProfile): ProsodyProfile {
        return ProsodyProfile(
            lengthScale = prosody.lengthScale.coerceIn(0.50f, 1.50f),
            noiseScale = prosody.noiseScale.coerceIn(0.10f, 2.00f),
            silenceScale = prosody.silenceScale.coerceIn(0.00f, 0.50f)
        )
    }

    private fun sameSynthesisSpeeds(
        lhs: MatchaSynthesisSpeedConfig,
        rhs: MatchaSynthesisSpeedConfig
    ): Boolean {
        return abs(lhs.shortSpeed - rhs.shortSpeed) < 0.0005f &&
            abs(lhs.normalSpeed - rhs.normalSpeed) < 0.0005f &&
            abs(lhs.longSpeed - rhs.longSpeed) < 0.0005f
    }

    private fun sameProsody(lhs: ProsodyProfile, rhs: ProsodyProfile): Boolean {
        return abs(lhs.lengthScale - rhs.lengthScale) < 0.0005f &&
            abs(lhs.noiseScale - rhs.noiseScale) < 0.0005f &&
            abs(lhs.silenceScale - rhs.silenceScale) < 0.0005f
    }

    private fun formatScale(value: Float): String {
        return String.format(Locale.US, "%.2f", value)
    }

    private fun formatProsodySummary(prosody: ProsodyProfile): String {
        return "L=${formatScale(prosody.lengthScale)}, N=${formatScale(prosody.noiseScale)}, S=${formatScale(prosody.silenceScale)}"
    }

    private fun formatSynthesisSpeedSummary(config: MatchaSynthesisSpeedConfig): String {
        return "Short=${formatScale(config.shortSpeed)}, Normal=${formatScale(config.normalSpeed)}, Long=${formatScale(config.longSpeed)}"
    }

    private fun settingsPrefs() = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)

    private fun loadSavedProsody(defaultProsody: ProsodyProfile): ProsodyProfile {
        val prefs = settingsPrefs()
        val hasSaved = prefs.contains(PREF_MATCHA_LENGTH) &&
            prefs.contains(PREF_MATCHA_NOISE) &&
            prefs.contains(PREF_MATCHA_SILENCE)
        if (!hasSaved) return defaultProsody

        return clampProsody(
            ProsodyProfile(
                lengthScale = prefs.getFloat(PREF_MATCHA_LENGTH, defaultProsody.lengthScale),
                noiseScale = prefs.getFloat(PREF_MATCHA_NOISE, defaultProsody.noiseScale),
                silenceScale = prefs.getFloat(PREF_MATCHA_SILENCE, defaultProsody.silenceScale)
            )
        )
    }

    private fun saveMatchaProsodySettings(prosody: ProsodyProfile) {
        val normalized = clampProsody(prosody)
        settingsPrefs().edit()
            .putFloat(PREF_MATCHA_LENGTH, normalized.lengthScale)
            .putFloat(PREF_MATCHA_NOISE, normalized.noiseScale)
            .putFloat(PREF_MATCHA_SILENCE, normalized.silenceScale)
            .apply()
    }

    private fun loadSavedSynthesisSpeeds(
        defaultConfig: MatchaSynthesisSpeedConfig
    ): MatchaSynthesisSpeedConfig {
        val prefs = settingsPrefs()
        val hasSaved = prefs.contains(PREF_MATCHA_SHORT_SPEED) &&
            prefs.contains(PREF_MATCHA_NORMAL_SPEED) &&
            prefs.contains(PREF_MATCHA_LONG_SPEED)
        if (!hasSaved) return defaultConfig

        return normalizeMatchaSynthesisSpeeds(
            MatchaSynthesisSpeedConfig(
                shortSpeed = prefs.getFloat(PREF_MATCHA_SHORT_SPEED, defaultConfig.shortSpeed),
                normalSpeed = prefs.getFloat(PREF_MATCHA_NORMAL_SPEED, defaultConfig.normalSpeed),
                longSpeed = prefs.getFloat(PREF_MATCHA_LONG_SPEED, defaultConfig.longSpeed)
            )
        )
    }

    private fun saveMatchaSynthesisSpeeds(config: MatchaSynthesisSpeedConfig) {
        val normalized = normalizeMatchaSynthesisSpeeds(config)
        settingsPrefs().edit()
            .putFloat(PREF_MATCHA_SHORT_SPEED, normalized.shortSpeed)
            .putFloat(PREF_MATCHA_NORMAL_SPEED, normalized.normalSpeed)
            .putFloat(PREF_MATCHA_LONG_SPEED, normalized.longSpeed)
            .apply()
    }

    private fun loadGoogleSpeechSettings() {
        val prefs = settingsPrefs()
        hindiSpeechConfig = normalizeGoogleSpeechConfig(
            GoogleSpeechConfig(
                rate = prefs.getFloat(PREF_GOOGLE_HI_RATE, DEFAULT_GOOGLE_RATE),
                pitch = prefs.getFloat(PREF_GOOGLE_HI_PITCH, DEFAULT_GOOGLE_PITCH)
            )
        )
        tamilSpeechConfig = normalizeGoogleSpeechConfig(
            GoogleSpeechConfig(
                rate = prefs.getFloat(PREF_GOOGLE_TA_RATE, DEFAULT_GOOGLE_RATE),
                pitch = prefs.getFloat(PREF_GOOGLE_TA_PITCH, DEFAULT_GOOGLE_PITCH)
            )
        )
    }

    private fun saveGoogleSpeechSettings() {
        settingsPrefs().edit()
            .putFloat(PREF_GOOGLE_HI_RATE, hindiSpeechConfig.rate)
            .putFloat(PREF_GOOGLE_HI_PITCH, hindiSpeechConfig.pitch)
            .putFloat(PREF_GOOGLE_TA_RATE, tamilSpeechConfig.rate)
            .putFloat(PREF_GOOGLE_TA_PITCH, tamilSpeechConfig.pitch)
            .apply()
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

        val isNothing2a = manufacturer.contains("nothing") && (
            model.contains("a142") ||
                model.contains("2a") ||
                board.contains("pacman") ||
                device.contains("pacman") ||
                product.contains("pacman")
            )

        val isXiaomiPad5 = manufacturer.contains("xiaomi") && (
            model.contains("pad 5") ||
                model.contains("mipad 5") ||
                model.contains("21051182") ||
                board.contains("nabu") ||
                device.contains("nabu") ||
                product.contains("nabu")
            )

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
            isNothing2a -> RuntimeProfile(
                tier = "nothing2a",
                threads = 2,
                synthesis = NOTHING_2A_PROFILE,
                prosody = NOTHING_2A_PAD5_PROSODY,
                // Reuse short-utterance guard path for Nothing Phone 2a.
                useA23ShortUtteranceWorkaround = true
            )

            isXiaomiPad5 -> RuntimeProfile(
                tier = "xiaomi_pad5",
                threads = 4,
                synthesis = HIGH_TIER_PROFILE,
                prosody = NOTHING_2A_PAD5_PROSODY,
                useA23ShortUtteranceWorkaround = false
            )

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
            "Device profile: hardware=${Build.HARDWARE}, board=${Build.BOARD}, model=${Build.MODEL}, memClass=${memClass}MB, cores=$cores -> cpu/${tunedProfile.threads}, synthesis=${tunedProfile.synthesis.name}(chars=${tunedProfile.synthesis.maxChunkChars}, words=${tunedProfile.synthesis.maxChunkWords}, maxChunks=${tunedProfile.synthesis.maxTotalChunks}, queue=${tunedProfile.synthesis.queueCapacity}, short=${tunedProfile.synthesis.shortSpeed}, normal=${tunedProfile.synthesis.normalSpeed}, long=${tunedProfile.synthesis.longSpeed}), prosody=L${tunedProfile.prosody.lengthScale}/N${tunedProfile.prosody.noiseScale}/S${tunedProfile.prosody.silenceScale}, short_utterance_guard=${tunedProfile.useA23ShortUtteranceWorkaround}"
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

    private fun chooseSpeechRoute(text: String): SpeechRoute {
        var devanagariCount = 0
        var tamilCount = 0

        for (ch in text) {
            val codePoint = ch.code
            when {
                codePoint in DEVANAGARI_RANGE -> devanagariCount += 1
                codePoint in TAMIL_RANGE -> tamilCount += 1
            }
        }

        return when {
            tamilCount > devanagariCount && tamilCount > 0 -> SpeechRoute.GOOGLE_TAMIL
            devanagariCount > 0 -> SpeechRoute.GOOGLE_HINDI
            else -> SpeechRoute.MATCHA_ENGLISH
        }
    }

    private suspend fun speakWithGoogleTts(text: String, route: SpeechRoute): Boolean {
        val tts = ensureGoogleTtsReady() ?: return false
        return withContext(Dispatchers.Main) {
            val voice = configureGoogleTtsVoice(tts, route)
            val speechConfig = googleSpeechConfigForRoute(route)
            applyGoogleSpeechConfig(tts, speechConfig)
            val routeLabel = when (route) {
                SpeechRoute.GOOGLE_HINDI -> "Hindi"
                SpeechRoute.GOOGLE_TAMIL -> "Tamil"
                SpeechRoute.MATCHA_ENGLISH -> "English"
            }
            if (voice != null) {
                Log.i(
                    TAG,
                    "Google TTS route=$routeLabel voice=${voice.name} rate=${speechConfig.rate} pitch=${speechConfig.pitch}"
                )
            } else {
                Log.i(
                    TAG,
                    "Google TTS route=$routeLabel using locale fallback rate=${speechConfig.rate} pitch=${speechConfig.pitch}"
                )
            }

            val spokenText = text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace(Regex("\\s+"), " ")
                .trim()
            if (spokenText.isEmpty()) return@withContext true

            val utteranceId = "google-${route.name.lowercase(Locale.US)}-${SystemClock.elapsedRealtimeNanos()}"
            val completion = CompletableDeferred<Boolean>()

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(doneId: String?) {
                    if (doneId == utteranceId && !completion.isCompleted) {
                        completion.complete(true)
                    }
                }

                override fun onError(errorId: String?) {
                    if (errorId == utteranceId && !completion.isCompleted) {
                        completion.complete(false)
                    }
                }

                override fun onError(errorId: String?, errorCode: Int) {
                    if (errorId == utteranceId && !completion.isCompleted) {
                        completion.complete(false)
                    }
                }
            })

            val speakResult = tts.speak(spokenText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (speakResult != TextToSpeech.SUCCESS) {
                Log.e(TAG, "Google TTS speak() failed route=$routeLabel result=$speakResult")
                return@withContext false
            }

            try {
                completion.await()
            } catch (ce: CancellationException) {
                tts.stop()
                throw ce
            }
        }
    }

    private suspend fun ensureGoogleTtsReady(): TextToSpeech? {
        googleTts?.let { return it }

        return withContext(Dispatchers.Main) {
            googleTts?.let { return@withContext it }

            val preferred = initTextToSpeech(enginePackage = GOOGLE_TTS_ENGINE)
            if (preferred != null) {
                googleTts = preferred
                Log.i(TAG, "Google TTS engine initialized: ${preferred.defaultEngine}")
                return@withContext preferred
            }

            Log.w(TAG, "Google TTS engine '$GOOGLE_TTS_ENGINE' unavailable. Falling back to default TTS engine.")
            val fallback = initTextToSpeech(enginePackage = null)
            if (fallback != null) {
                googleTts = fallback
                Log.i(TAG, "Fallback TTS engine initialized: ${fallback.defaultEngine}")
                return@withContext fallback
            }

            Log.e(TAG, "Unable to initialize any Android TTS engine")
            null
        }
    }

    private suspend fun initTextToSpeech(enginePackage: String?): TextToSpeech? {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val holder = arrayOfNulls<TextToSpeech>(1)
            val listener = TextToSpeech.OnInitListener { status ->
                val tts = holder[0]
                if (tts == null || !cont.isActive) {
                    tts?.shutdown()
                    return@OnInitListener
                }

                if (status == TextToSpeech.SUCCESS) {
                    cont.resume(tts)
                } else {
                    tts.shutdown()
                    cont.resume(null)
                }
            }

            val tts = if (enginePackage != null) {
                TextToSpeech(this@MainActivity, listener, enginePackage)
            } else {
                TextToSpeech(this@MainActivity, listener)
            }
            holder[0] = tts

            cont.invokeOnCancellation {
                holder[0]?.shutdown()
            }
        }
    }

    private fun configureGoogleTtsVoice(tts: TextToSpeech, route: SpeechRoute): Voice? {
        val (preferredVoiceName, locale, voicePrefix) = when (route) {
            SpeechRoute.GOOGLE_HINDI -> Triple(
                HINDI_GOOGLE_VOICE,
                Locale("hi", "IN"),
                "hi-in-x-"
            )
            SpeechRoute.GOOGLE_TAMIL -> Triple(
                TAMIL_GOOGLE_VOICE,
                Locale("ta", "IN"),
                "ta-in-x-"
            )
            SpeechRoute.MATCHA_ENGLISH -> return null
        }

        val availableVoices = tts.voices.orEmpty().toList()
        val selectedVoice = selectBestGoogleVoice(
            availableVoices = availableVoices,
            locale = locale,
            preferredVoiceName = preferredVoiceName,
            voicePrefix = voicePrefix
        )

        if (selectedVoice == null) {
            val hasLanguageVoice = availableVoices.any { voice ->
                voice.locale?.language.equals(locale.language, ignoreCase = true)
            }
            if (!hasLanguageVoice) {
                Log.w(
                    TAG,
                    "Google TTS has no installed voices for ${locale.toLanguageTag()}. Install that language voice pack in Google TTS settings."
                )
            }
        }

        if (selectedVoice != null) {
            if (applyGoogleVoice(tts, selectedVoice)) {
                return selectedVoice
            }
            Log.e(TAG, "Failed to apply Google TTS voice '${selectedVoice.name}'. Falling back to locale.")
        }

        val langResult = tts.setLanguage(locale)
        val langOk = langResult != TextToSpeech.LANG_MISSING_DATA &&
            langResult != TextToSpeech.LANG_NOT_SUPPORTED
        if (!langOk) {
            Log.e(TAG, "Google TTS locale fallback failed for $locale (result=$langResult)")
            return null
        }

        return tts.voice?.takeIf { voice ->
            voice.locale.language.equals(locale.language, ignoreCase = true)
        }
    }

    private fun selectBestGoogleVoice(
        availableVoices: List<Voice>,
        locale: Locale,
        preferredVoiceName: String,
        voicePrefix: String
    ): Voice? {
        if (availableVoices.isEmpty()) return null

        val preferredLower = preferredVoiceName.lowercase(Locale.US)
        val prefixLower = voicePrefix.lowercase(Locale.US)
        val localeLanguage = locale.language.lowercase(Locale.US)
        val localeCountry = locale.country.lowercase(Locale.US)

        fun matchesLanguage(voice: Voice): Boolean {
            val vLocale = voice.locale ?: return false
            return vLocale.language.equals(locale.language, ignoreCase = true)
        }

        fun matchesLocaleCountry(voice: Voice): Boolean {
            val vLocale = voice.locale ?: return false
            if (!vLocale.language.equals(locale.language, ignoreCase = true)) return false
            return vLocale.country.isBlank() || vLocale.country.equals(locale.country, ignoreCase = true)
        }

        fun scoreVoice(voice: Voice): Int {
            val nameLower = voice.name.lowercase(Locale.US)
            var score = 0

            if (nameLower == preferredLower) score += 1_000_000
            if (matchesLocaleCountry(voice)) score += 100_000
            if (nameLower.startsWith(prefixLower)) score += 50_000
            if (nameLower.contains("lstm-embedded")) score += 25_000
            else if (nameLower.contains("embedded")) score += 12_000
            if (!voice.isNetworkConnectionRequired) score += 8_000
            if (nameLower.contains("$localeLanguage-$localeCountry")) score += 4_000

            score += voice.quality
            score -= voice.latency
            return score
        }

        val languageVoices = availableVoices.filter(::matchesLanguage)
        if (languageVoices.isEmpty()) return null

        return languageVoices.maxByOrNull(::scoreVoice)
    }

    private fun applyGoogleVoice(tts: TextToSpeech, voice: Voice): Boolean {
        return runCatching {
            tts.voice = voice
            true
        }.getOrElse { t ->
            Log.e(TAG, "Failed to set Google TTS voice '${voice.name}'", t)
            false
        }
    }

    private fun googleSpeechConfigForRoute(route: SpeechRoute): GoogleSpeechConfig {
        return when (route) {
            SpeechRoute.GOOGLE_HINDI -> hindiSpeechConfig
            SpeechRoute.GOOGLE_TAMIL -> tamilSpeechConfig
            SpeechRoute.MATCHA_ENGLISH -> GoogleSpeechConfig(
                rate = DEFAULT_GOOGLE_RATE,
                pitch = DEFAULT_GOOGLE_PITCH
            )
        }
    }

    private fun applyGoogleSpeechConfig(tts: TextToSpeech, config: GoogleSpeechConfig) {
        val normalized = normalizeGoogleSpeechConfig(config)
        tts.setSpeechRate(normalized.rate)
        tts.setPitch(normalized.pitch)
    }

    override fun onDestroy() {
        synthesisJob?.cancel()
        audioPlayer.stop()
        googleTts?.stop()
        googleTts?.shutdown()
        googleTts = null
        NativeTts.release()
        super.onDestroy()
    }
}
