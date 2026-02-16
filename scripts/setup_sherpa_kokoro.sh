#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   ./scripts/setup_sherpa_kokoro.sh [sherpa_version]
# Example:
#   ./scripts/setup_sherpa_kokoro.sh 1.12.24

SHERPA_VER="${1:-1.12.24}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP_DIR="$(mktemp -d)"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

echo "Using sherpa-onnx version: $SHERPA_VER"

echo "Downloading Android shared libs..."
curl -L -o "$TMP_DIR/sherpa-onnx-android.tar.bz2" \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VER}/sherpa-onnx-v${SHERPA_VER}-android.tar.bz2"

tar -xjf "$TMP_DIR/sherpa-onnx-android.tar.bz2" -C "$TMP_DIR"

mkdir -p "$PROJECT_ROOT/app/src/main/jniLibs/arm64-v8a"
cp -v "$TMP_DIR/jniLibs/arm64-v8a/libonnxruntime.so" "$PROJECT_ROOT/app/src/main/jniLibs/arm64-v8a/"
cp -v "$TMP_DIR/jniLibs/arm64-v8a/libsherpa-onnx-c-api.so" "$PROJECT_ROOT/app/src/main/jniLibs/arm64-v8a/"

mkdir -p "$PROJECT_ROOT/app/src/main/cpp/include/sherpa-onnx/c-api"
curl -L -o "$PROJECT_ROOT/app/src/main/cpp/include/sherpa-onnx/c-api/c-api.h" \
  "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v${SHERPA_VER}/sherpa-onnx/c-api/c-api.h"

cat <<MSG

Native dependencies are ready.

Now place Matcha assets under:
  $PROJECT_ROOT/app/src/main/assets/matcha/

Required:
  acoustic_model.onnx
  vocoder.onnx
  tokens.txt
  lexicon.txt
  espeak-ng-data/...

Or run:
  ./scripts/download_matcha_model.sh

MSG
