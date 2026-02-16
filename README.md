# Matcha Android TTS (Kotlin + JNI + Sherpa-ONNX C++ Port)

## 1. Prepare native libs and headers

```bash
./scripts/setup_sherpa_kokoro.sh 1.12.24
```

This installs:
- `app/src/main/jniLibs/arm64-v8a/libonnxruntime.so`
- `app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-c-api.so`
- `app/src/main/cpp/include/sherpa-onnx/c-api/c-api.h`

## 2. Install Matcha model assets

Option A: download automatically

```bash
./scripts/download_matcha_model.sh
```

You can pass package/url/local archive too:

```bash
./scripts/download_matcha_model.sh matcha-icefall-en_US-ljspeech.tar.bz2
./scripts/download_matcha_model.sh https://example.com/matcha-model.tar.bz2
./scripts/download_matcha_model.sh /absolute/path/to/matcha-model.tar.bz2
```

`download_kokoro_model.sh` is now a compatibility wrapper to the Matcha downloader.

Note: `matcha-icefall-en_US-ljspeech` does not include a vocoder in the archive. The script automatically downloads `vocos-22khz-univ.onnx` from sherpa-onnx releases and installs it as `vocoder.onnx`.

Option B: manual copy into:
- `app/src/main/assets/matcha/`

Required files:
- `acoustic_model.onnx`
- `vocoder.onnx`
- `tokens.txt`
- `espeak-ng-data/`

Optional:
- `lexicon.txt` (if present, native will use it; otherwise it runs without lexicon)

## 3. Runtime strategy

- Model family: Matcha only
- Provider: CPU-first (Kotlin requests `cpu`)
- Threads: device-aware CPU thread choice (`2` on low-end class, else `4`), fallback to `1`
- Generation: uncached each request

## 4. Open and run

Open this project in Android Studio and run on an `arm64-v8a` device.

Startup copies `assets/matcha` to `filesDir/matcha`, initializes native Matcha TTS once, and playback uses float PCM via `AudioTrack`.
