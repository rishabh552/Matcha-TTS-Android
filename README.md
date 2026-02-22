# Matcha Android TTS (Kotlin + JNI + Sherpa-ONNX C++)

Android text-to-speech demo app using the Matcha acoustic model and a vocoder via Sherpa-ONNX C API. The Kotlin UI calls into a JNI layer that loads ONNX models from app assets and streams PCM audio through `AudioTrack`.

## Features

- Matcha TTS inference on-device (CPU).
- JNI bridge to Sherpa-ONNX C API.
- Asset-based model packaging for offline use.
- Simple playback pipeline using float PCM.

## Requirements

- Android Studio (Koala or newer recommended).
- Android SDK with NDK/CMake support.
- Device or emulator with `arm64-v8a` (native libs are provided for arm64 only).
- Git Bash or WSL to run the provided shell scripts on Windows.

## Quick start

1. Prepare native libraries and headers:

```bash
./scripts/setup_sherpa_kokoro.sh 1.12.24
```

This installs:
- `app/src/main/jniLibs/arm64-v8a/libonnxruntime.so`
- `app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-c-api.so`
- `app/src/main/cpp/include/sherpa-onnx/c-api/c-api.h`

2. Install Matcha model assets.

Option A: download automatically

```bash
./scripts/download_matcha_model.sh
```

You can pass a package URL or local archive:

```bash
./scripts/download_matcha_model.sh matcha-icefall-en_US-ljspeech.tar.bz2
./scripts/download_matcha_model.sh https://example.com/matcha-model.tar.bz2
./scripts/download_matcha_model.sh /absolute/path/to/matcha-model.tar.bz2
```

`download_kokoro_model.sh` is a compatibility wrapper to the Matcha downloader.

Note: `matcha-icefall-en_US-ljspeech` does not include a vocoder in the archive. The script downloads `vocos-22khz-univ.onnx` from Sherpa-ONNX releases and installs it as `vocoder.onnx`.

Option B: copy manually into `app/src/main/assets/matcha/`.

Required files:
- `acoustic_model.onnx`
- `vocoder.onnx`
- `tokens.txt`
- `espeak-ng-data/`

Optional:
- `lexicon.txt` (if present, native uses it; otherwise it runs without lexicon)

3. Open and run

Open the project in Android Studio, sync Gradle, and run on an `arm64-v8a` device.

On first launch, the app copies `assets/matcha` to `filesDir/matcha`, initializes native Matcha TTS, and plays audio using `AudioTrack`.

## Project structure

- `app/src/main/java/` Kotlin UI and JNI bindings.
- `app/src/main/cpp/` C++ JNI and Sherpa-ONNX integration.
- `app/src/main/assets/` model assets (Matcha or Kokoro as provided).
- `app/src/main/jniLibs/arm64-v8a/` native shared libraries.
- `scripts/` download and setup helpers.

## Runtime behavior

- Model family: Matcha only.
- Provider: CPU-first (Kotlin requests `cpu`).
- Threads: device-aware CPU thread choice (`2` on low-end class, else `4`), fallback to `1`.
- Generation: uncached per request.

## Troubleshooting

- Missing `arm64-v8a` libs: re-run `./scripts/setup_sherpa_kokoro.sh` and confirm `jniLibs/arm64-v8a` exists.
- App crashes on startup: verify `app/src/main/assets/matcha/` contains the required files.
- No audio output: check device volume, and confirm playback permissions and sample rate support.

## License and attributions

- Model and tokenizer files have their own licenses. See the `README.md` and `LICENSE` files in `app/src/main/assets/*`.
- Sherpa-ONNX and ONNX Runtime are licensed by their respective projects.
