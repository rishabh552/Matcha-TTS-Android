# Matcha Android TTS (Kotlin + JNI + Sherpa-ONNX C++)

Android TTS demo app with hybrid routing:
- English: on-device Matcha (Sherpa-ONNX C API via JNI)
- Hindi/Tamil: Android TTS (prefers Google TTS voices)

Audio playback uses `AudioTrack` float PCM for Matcha output, while Hindi/Tamil routes use Android `TextToSpeech`.

## Features

- On-device Matcha inference on CPU (offline for English).
- JNI bridge to Sherpa-ONNX C API.
- Language-aware routing:
  - Tamil script dominant -> Google Tamil route
  - Devanagari present -> Google Hindi route
  - Otherwise -> Matcha English route
- Device-aware runtime tuning (Nothing Phone 2a, Xiaomi Pad 5, Samsung A23, generic tiers).
- Settings UI for:
  - Matcha English prosody (`length/noise/silence`)
  - Matcha English synthesis speeds (`short/normal/long`)
  - Google Hindi speech rate/pitch
  - Google Tamil speech rate/pitch
- Short-utterance guard path for devices prone to clipping very short outputs.

## Requirements

- Android Studio (Koala or newer recommended).
- Android SDK with NDK/CMake support.
- `arm64-v8a` device/emulator (native libs provided for arm64 only).
- Git Bash or WSL for provided shell scripts on Windows.
- For Hindi/Tamil routes: Android TTS engine with Hindi/Tamil voices installed (Google TTS preferred).

## Quick Start

1. Prepare native libraries and headers:

```bash
./scripts/setup_sherpa_kokoro.sh 1.12.33
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

You can also pass a package URL or local archive:

```bash
./scripts/download_matcha_model.sh matcha-icefall-en_US-ljspeech.tar.bz2
./scripts/download_matcha_model.sh https://example.com/matcha-model.tar.bz2
./scripts/download_matcha_model.sh /absolute/path/to/matcha-model.tar.bz2
```

`download_kokoro_model.sh` remains as a compatibility wrapper.

Note: `matcha-icefall-en_US-ljspeech` does not include a vocoder in the archive. The script downloads `vocos-22khz-univ.onnx` and installs it as `vocoder.onnx`.

Option B: copy manually into `app/src/main/assets/matcha/`.

Required files:
- `acoustic_model.onnx`
- `vocoder.onnx`
- `tokens.txt`
- `espeak-ng-data/`

Optional:
- `lexicon.txt` (if missing, engine runs without lexicon)

3. Open and run

Open the project in Android Studio, sync Gradle, and run on an `arm64-v8a` device.

On first launch, app assets are copied to `filesDir/matcha`, Matcha native is initialized/warmed up, and the app is ready to synthesize.

## Runtime Behavior

- Provider: CPU (`provider=cpu`).
- Threading: device/tier tuned with fallback candidates.
- English route:
  - Matcha synthesis with chunking and queue-based generation/playback.
  - Per-device synthesis profile (chunk sizes, queue depth, speed profile).
- Hindi/Tamil routes:
  - Uses Android `TextToSpeech` (tries `com.google.android.tts`, then fallback engine).
  - Preferred voices:
    - `hi-in-x-hic-lstm-embedded`
    - `ta-in-x-tac-lstm-embedded`

Important routing note:
- Routing is request-level, not segment-level. A single input is sent to one route based on script counts.
- Mixed-language text in one input is therefore not optimal yet; it does not split by sentence/segment automatically.

## Defaults (Current)

Matcha English prosody baseline:
- Human-like base: `length=1.00`, `noise=0.67`, `silence=0.20`
- Used directly for Nothing Phone 2a and Xiaomi Pad 5 profiles.

Other tier prosody defaults:
- Low tier: `1.00 / 0.62 / 0.20`
- Mid tier: `1.00 / 0.64 / 0.20`
- A23 override: `1.00 / 0.60 / 0.21`

Matcha synthesis speed defaults:
- Low: `short=1.00`, `normal=0.98`, `long=0.98`
- Mid: `short=1.01`, `normal=0.99`, `long=0.99`
- High/Nothing2a: `short=1.02`, `normal=1.00`, `long=1.00`

Google Hindi/Tamil defaults:
- `rate=1.00`, `pitch=1.00`

## Settings UI

Use top-right menu -> **Settings**.

Configurable ranges:
- Matcha `length`: `0.50 .. 1.50`
- Matcha `noise`: `0.10 .. 2.00`
- Matcha `silence`: `0.00 .. 0.50`
- Matcha `short/normal/long speed`: `0.70 .. 1.30`
- Google Hindi/Tamil `rate/pitch`: `0.50 .. 1.50`

Behavior:
- Matcha prosody changes require native re-init + warmup.
- Matcha synthesis speed and Google Hindi/Tamil controls update immediately.
- Settings persist in `SharedPreferences`.
- **Defaults** button restores device defaults + Google defaults.

## Build and Install

### Build from Android Studio

1. Open project
2. Wait for Gradle sync
3. Build -> Build APK(s)
4. Output: `app/build/outputs/apk/debug/app-debug.apk`

### Build from command line

Windows:
```powershell
.\gradlew assembleDebug
```

macOS/Linux/Git Bash:
```bash
./gradlew assembleDebug
```

### Install via ADB

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

If install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, uninstall old app first:

```bash
adb uninstall com.example.tts
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Troubleshooting

- Missing `arm64-v8a` libs:
  - Re-run `./scripts/setup_sherpa_kokoro.sh 1.12.33`
  - Verify `app/src/main/jniLibs/arm64-v8a/` exists
- App crashes at startup:
  - Verify required files in `app/src/main/assets/matcha/`
- Hindi/Tamil says voice unavailable:
  - Open Google TTS settings and install Hindi/Tamil voice data
  - Keep Google TTS updated
  - App will fallback to locale voice if exact named voice is unavailable
- Build fails with Java errors:
  - Ensure JDK is installed and `JAVA_HOME` is set

## Project Structure

- `app/src/main/java/` Kotlin UI, routing, settings, JNI calls.
- `app/src/main/cpp/` native JNI + Sherpa-ONNX integration.
- `app/src/main/assets/` model assets.
- `app/src/main/jniLibs/arm64-v8a/` native shared libraries.
- `scripts/` setup/download helpers.

## License and Attributions

- Model/tokenizer files have their own licenses in `app/src/main/assets/*`.
- Sherpa-ONNX and ONNX Runtime are licensed by their respective projects.
