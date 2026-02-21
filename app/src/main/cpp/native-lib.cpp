#include <jni.h>
#include <android/log.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cctype>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "sherpa-onnx/c-api/c-api.h"

namespace {
constexpr const char* kTag = "MatchaNativeTts";
constexpr int32_t kDefaultSampleRate = 24000;
constexpr int32_t kDefaultThreads = 2;
constexpr int32_t kMaxThreads = 16;
constexpr float kDefaultLengthScale = 1.00f;
constexpr float kDefaultNoiseScale = 0.64f;
constexpr float kDefaultSilenceScale = 0.12f;
constexpr float kMinLengthScale = 0.50f;
constexpr float kMaxLengthScale = 1.50f;
constexpr float kMinNoiseScale = 0.10f;
constexpr float kMaxNoiseScale = 2.00f;
constexpr float kMinSilenceScale = 0.00f;
constexpr float kMaxSilenceScale = 0.50f;

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, kTag, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, kTag, __VA_ARGS__)

std::mutex g_mutex;
const SherpaOnnxOfflineTts* g_tts = nullptr;
int32_t g_sample_rate = kDefaultSampleRate;
std::string g_provider = "cpu";
int32_t g_num_threads = kDefaultThreads;

std::string g_preferred_provider = "cpu";
int32_t g_preferred_threads = 4;
float g_preferred_length_scale = kDefaultLengthScale;
float g_preferred_noise_scale = kDefaultNoiseScale;
float g_preferred_silence_scale = kDefaultSilenceScale;

bool Exists(const std::string& path) {
  return access(path.c_str(), F_OK) == 0;
}

std::string NormalizeProvider(std::string provider) {
  std::string out;
  out.reserve(provider.size());
  for (char c : provider) {
    out.push_back(static_cast<char>(std::tolower(static_cast<unsigned char>(c))));
  }
  return out;
}

bool IsValidProvider(const std::string& provider) {
  return provider == "cpu" || provider == "xnnpack";
}

float Clampf(float value, float min_value, float max_value) {
  return std::max(min_value, std::min(value, max_value));
}

void DestroyTtsLocked() {
  if (g_tts != nullptr) {
    SherpaOnnxDestroyOfflineTts(g_tts);
    g_tts = nullptr;
    g_sample_rate = kDefaultSampleRate;
    g_provider = "cpu";
    g_num_threads = kDefaultThreads;
  }
}

const SherpaOnnxOfflineTts* CreateTtsWithProviderPreferenceLocked(
    SherpaOnnxOfflineTtsConfig* config, const std::string& preferred_provider,
    std::string* selected_provider) {
  if (preferred_provider == "xnnpack") {
    config->model.provider = "xnnpack";
    const SherpaOnnxOfflineTts* tts = SherpaOnnxCreateOfflineTts(config);
    if (tts != nullptr) {
      *selected_provider = "xnnpack";
      return tts;
    }
    LOGI("init(): provider=xnnpack failed, falling back to cpu");
  }

  config->model.provider = "cpu";
  const SherpaOnnxOfflineTts* tts = SherpaOnnxCreateOfflineTts(config);
  if (tts != nullptr) {
    *selected_provider = "cpu";
    return tts;
  }

  LOGI("init(): provider=cpu failed");
  return nullptr;
}

jfloatArray EmptyFloatArray(JNIEnv* env) {
  return env->NewFloatArray(0);
}

bool InitCoreLocked(const std::string& acoustic_model_path,
                    const std::string& vocoder_path,
                    const std::string& tokens_path,
                    const std::string& lexicon_path,
                    const std::string& data_dir,
                    int32_t num_threads,
                    const std::string& preferred_provider,
                    const std::chrono::steady_clock::time_point& start_time) {
  if (!Exists(acoustic_model_path) || !Exists(vocoder_path) ||
      !Exists(tokens_path) || !Exists(data_dir)) {
    LOGE("init(): missing acoustic/vocoder/tokens/data_dir");
    return false;
  }

  const bool has_lexicon = !lexicon_path.empty() && Exists(lexicon_path);

  SherpaOnnxOfflineTtsConfig config;
  std::memset(&config, 0, sizeof(config));

  config.model.matcha.acoustic_model = acoustic_model_path.c_str();
  config.model.matcha.vocoder = vocoder_path.c_str();
  config.model.matcha.tokens = tokens_path.c_str();
  if (has_lexicon) {
    config.model.matcha.lexicon = lexicon_path.c_str();
  }
  config.model.matcha.data_dir = data_dir.c_str();
  config.model.matcha.length_scale = g_preferred_length_scale;
  config.model.matcha.noise_scale = g_preferred_noise_scale;

  config.model.num_threads = std::clamp(num_threads, 1, kMaxThreads);
  config.model.debug = 0;
  config.max_num_sentences = 1;
  config.silence_scale = g_preferred_silence_scale;

  DestroyTtsLocked();

  std::string selected_provider;
  g_tts = CreateTtsWithProviderPreferenceLocked(&config, preferred_provider,
                                                &selected_provider);
  if (!g_tts) {
    LOGE("init(): SherpaOnnxCreateOfflineTts failed");
    return false;
  }

  g_provider = selected_provider;
  g_num_threads = config.model.num_threads;
  g_sample_rate = SherpaOnnxOfflineTtsSampleRate(g_tts);

  const auto end = std::chrono::steady_clock::now();
  const auto ms =
      std::chrono::duration_cast<std::chrono::milliseconds>(end - start_time)
          .count();
  LOGI(
      "init(): success, sample_rate=%d, provider=%s, requested_provider=%s, threads=%d, length_scale=%.2f, noise_scale=%.3f, silence_scale=%.3f, lexicon=%s, took=%lld ms",
      g_sample_rate, g_provider.c_str(), preferred_provider.c_str(),
      g_num_threads, config.model.matcha.length_scale,
      config.model.matcha.noise_scale, config.silence_scale,
      has_lexicon ? "on" : "off",
      static_cast<long long>(ms));

  return true;
}
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_tts_NativeTts_setEngineConfig(JNIEnv* env, jobject /*thiz*/,
                                                jstring provider,
                                                jint numThreads) {
  if (!provider) {
    return JNI_FALSE;
  }

  const char* provider_c = env->GetStringUTFChars(provider, nullptr);
  if (!provider_c) {
    return JNI_FALSE;
  }

  std::string provider_norm = NormalizeProvider(provider_c);
  env->ReleaseStringUTFChars(provider, provider_c);

  if (!IsValidProvider(provider_norm)) {
    LOGE("setEngineConfig(): invalid provider=%s", provider_norm.c_str());
    return JNI_FALSE;
  }

  const int32_t threads = std::clamp(static_cast<int32_t>(numThreads), 1,
                                     kMaxThreads);

  std::lock_guard<std::mutex> lock(g_mutex);
  g_preferred_provider = provider_norm;
  g_preferred_threads = threads;
  LOGI("setEngineConfig(): provider=%s threads=%d", g_preferred_provider.c_str(),
       g_preferred_threads);
  return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_tts_NativeTts_setProsodyConfig(JNIEnv* /*env*/, jobject /*thiz*/,
                                                 jfloat lengthScale,
                                                 jfloat noiseScale,
                                                 jfloat silenceScale) {
  std::lock_guard<std::mutex> lock(g_mutex);
  g_preferred_length_scale = Clampf(lengthScale, kMinLengthScale, kMaxLengthScale);
  g_preferred_noise_scale = Clampf(noiseScale, kMinNoiseScale, kMaxNoiseScale);
  g_preferred_silence_scale = Clampf(silenceScale, kMinSilenceScale, kMaxSilenceScale);

  LOGI("setProsodyConfig(): length_scale=%.2f noise_scale=%.3f silence_scale=%.3f",
       g_preferred_length_scale, g_preferred_noise_scale,
       g_preferred_silence_scale);
  return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_tts_NativeTts_init(JNIEnv* env, jobject /*thiz*/,
                                    jstring acousticModelPath,
                                    jstring vocoderPath,
                                    jstring tokensPath,
                                    jstring lexiconPath,
                                    jstring dataDir) {
  const auto start = std::chrono::steady_clock::now();
  if (!acousticModelPath || !vocoderPath || !tokensPath || !lexiconPath ||
      !dataDir) {
    LOGE("init(): null path argument");
    return JNI_FALSE;
  }

  const char* acoustic_c = env->GetStringUTFChars(acousticModelPath, nullptr);
  const char* vocoder_c = env->GetStringUTFChars(vocoderPath, nullptr);
  const char* tokens_c = env->GetStringUTFChars(tokensPath, nullptr);
  const char* lexicon_c = env->GetStringUTFChars(lexiconPath, nullptr);
  const char* data_c = env->GetStringUTFChars(dataDir, nullptr);

  if (!acoustic_c || !vocoder_c || !tokens_c || !lexicon_c || !data_c) {
    if (acoustic_c) env->ReleaseStringUTFChars(acousticModelPath, acoustic_c);
    if (vocoder_c) env->ReleaseStringUTFChars(vocoderPath, vocoder_c);
    if (tokens_c) env->ReleaseStringUTFChars(tokensPath, tokens_c);
    if (lexicon_c) env->ReleaseStringUTFChars(lexiconPath, lexicon_c);
    if (data_c) env->ReleaseStringUTFChars(dataDir, data_c);
    LOGE("init(): failed to read UTF chars");
    return JNI_FALSE;
  }

  std::string acoustic_model_path(acoustic_c);
  std::string vocoder_path(vocoder_c);
  std::string tokens_path(tokens_c);
  std::string lexicon_path(lexicon_c);
  std::string data_dir(data_c);

  env->ReleaseStringUTFChars(acousticModelPath, acoustic_c);
  env->ReleaseStringUTFChars(vocoderPath, vocoder_c);
  env->ReleaseStringUTFChars(tokensPath, tokens_c);
  env->ReleaseStringUTFChars(lexiconPath, lexicon_c);
  env->ReleaseStringUTFChars(dataDir, data_c);

  std::lock_guard<std::mutex> lock(g_mutex);
  const bool ok = InitCoreLocked(acoustic_model_path, vocoder_path,
                                 tokens_path, lexicon_path, data_dir,
                                 g_preferred_threads, g_preferred_provider,
                                 start);
  return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_tts_NativeTts_runtimeProvider(JNIEnv* env,
                                                jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_mutex);
  return env->NewStringUTF(g_provider.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_tts_NativeTts_runtimeThreads(JNIEnv* /*env*/,
                                               jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_mutex);
  return g_num_threads;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_tts_NativeTts_warmup(JNIEnv* /*env*/, jobject /*thiz*/,
                                      jfloat /*speed*/) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g_tts) {
    LOGE("warmup(): called before init()");
    return JNI_FALSE;
  }

  LOGI("warmup(): disabled (uncached mode)");
  return JNI_TRUE;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_tts_NativeTts_generate(JNIEnv* env, jobject /*thiz*/,
                                        jstring text, jfloat speed) {
  const auto start = std::chrono::steady_clock::now();
  if (!text) {
    return EmptyFloatArray(env);
  }

  const char* text_c = env->GetStringUTFChars(text, nullptr);
  if (!text_c) {
    return EmptyFloatArray(env);
  }

  std::string input(text_c);
  env->ReleaseStringUTFChars(text, text_c);

  if (input.empty()) {
    return EmptyFloatArray(env);
  }

  if (speed <= 0.0f) {
    speed = 1.0f;
  }

  std::vector<float> out_samples;
  out_samples.reserve(32000);
  std::string provider_used;
  int32_t sample_rate = kDefaultSampleRate;
  int32_t threads_used = kDefaultThreads;

  {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_tts) {
      LOGE("generate(): called before init()");
      return EmptyFloatArray(env);
    }

    provider_used = g_provider;
    threads_used = g_num_threads;

    const SherpaOnnxGeneratedAudio* audio =
        SherpaOnnxOfflineTtsGenerate(g_tts, input.c_str(), 0, speed);

    if (!audio) {
      LOGE("generate(): SherpaOnnxOfflineTtsGenerate returned null");
      return EmptyFloatArray(env);
    }

    if (!audio->samples || audio->n <= 0) {
      LOGE("generate(): invalid audio output (samples=%p, n=%d)",
           audio->samples, audio->n);
      SherpaOnnxDestroyOfflineTtsGeneratedAudio(audio);
      return EmptyFloatArray(env);
    }

    sample_rate = audio->sample_rate;
    g_sample_rate = sample_rate;
    out_samples.assign(audio->samples, audio->samples + audio->n);
    SherpaOnnxDestroyOfflineTtsGeneratedAudio(audio);
  }

  jfloatArray out = env->NewFloatArray(static_cast<jsize>(out_samples.size()));
  if (out) {
    env->SetFloatArrayRegion(out, 0, static_cast<jsize>(out_samples.size()),
                             out_samples.data());
  }

  const auto end = std::chrono::steady_clock::now();
  const auto ms =
      std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
  LOGI(
      "generate(): provider=%s threads=%d cache=0 speed=%.2f took=%lld ms, samples=%d, sr=%d",
      provider_used.c_str(), threads_used, speed, static_cast<long long>(ms),
      out ? static_cast<int>(out_samples.size()) : 0, sample_rate);

  if (!out) {
    return EmptyFloatArray(env);
  }

  return out;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_tts_NativeTts_sampleRate(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_mutex);
  return g_sample_rate;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_tts_NativeTts_release(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_mutex);
  DestroyTtsLocked();
}

jint JNI_OnLoad(JavaVM* /*vm*/, void* /*reserved*/) {
  return JNI_VERSION_1_6;
}

void JNI_OnUnload(JavaVM* /*vm*/, void* /*reserved*/) {
  std::lock_guard<std::mutex> lock(g_mutex);
  DestroyTtsLocked();
}
