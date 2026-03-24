# Project Overview

This project is an Android on-device Text-to-Speech (TTS) app focused on fast, low-latency speech generation without cloud APIs.

## Core Goal

Deliver a single-app, offline TTS experience that:
- starts speaking quickly,
- sounds natural (not robotic),
- runs on mobile hardware tiers,
- supports multilingual input.

## Current Runtime Architecture

The app currently uses a native JNI pipeline driven from Kotlin:
1. Kotlin UI + coroutine producer/consumer pipeline chunks and schedules text.
2. JNI (`app/src/main/cpp/native-lib.cpp`) handles TTS inference calls.
3. Audio output is streamed through `AudioTrack` via `AudioPlayer.kt`.

Main runtime files:
- `app/src/main/java/com/example/tts/MainActivity.kt`
- `app/src/main/java/com/example/tts/NativeTts.kt`
- `app/src/main/java/com/example/tts/AudioPlayer.kt`
- `app/src/main/cpp/native-lib.cpp`
- `app/src/main/java/com/example/tts/AssetUtils.kt`

## Model/Asset Direction

The app is now moving to MMS-style VITS language packs in `app/src/main/assets/mms/` (language `model.onnx`, `tokens.txt`, optional `lexicon.txt`, optional/shared `espeak-ng-data`).

Current product direction is to ship reliable multilingual on-device quality on Snapdragon-class devices first (English, Tamil, Hindi), then iterate model quality.

## Active Workstream

Immediate work is focused on MMS deployment and tuning:
- robust language routing (English/Hindi/Tamil),
- runtime profiling on Snapdragon 860 (TTFA + RTF),
- chunking/prosody/thread tuning for low latency,
- consistent asset packaging and fallback behavior.

## Non-Goals

- No server-side inference.
- No dependency on online TTS APIs for end-user speech generation.
- No runtime behavior that introduces unpredictable latency spikes.

## Success Criteria

- Stable on-device generation speed (low TTFA and acceptable RTF),
- naturalness improvement for Indian languages,
- reproducible training/deployment flow,
- clean fallback behavior on constrained devices.
