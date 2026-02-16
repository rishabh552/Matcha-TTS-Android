#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   ./scripts/download_matcha_model.sh [package_name|url|local_archive]
# Examples:
#   ./scripts/download_matcha_model.sh
#   ./scripts/download_matcha_model.sh matcha-icefall-en_US-ljspeech.tar.bz2
#   ./scripts/download_matcha_model.sh https://.../matcha-model.tar.bz2
#   ./scripts/download_matcha_model.sh /path/to/matcha-model.tar.bz2

DEFAULT_PACKAGE="matcha-icefall-en_US-ljspeech.tar.bz2"
INPUT_SRC="${1:-$DEFAULT_PACKAGE}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSET_ROOT="$PROJECT_ROOT/app/src/main/assets/matcha"
TMP_DIR="$(mktemp -d)"
BASE_URL="${MATCHA_BASE_URL:-https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models}"
VOCODER_URL="${MATCHA_VOCODER_URL:-https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/vocos-22khz-univ.onnx}"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

mkdir -p "$ASSET_ROOT"
ARCHIVE="$TMP_DIR/model.tar.bz2"

download_with_curl() {
  local url="$1"
  local out="$2"
  curl -L --fail --retry 5 --retry-delay 2 --retry-connrefused \
    --connect-timeout 20 --continue-at - -o "$out" "$url"
}

download_with_wget() {
  local url="$1"
  local out="$2"
  wget -O "$out" --tries=5 --waitretry=2 "$url"
}

download_archive() {
  local src="$1"
  local out="$2"

  if [[ "$src" == /* || "$src" == ./* || "$src" == ../* || "$src" == *"/"* ]]; then
    if [[ ! -f "$src" ]]; then
      echo "Local archive not found: $src"
      return 1
    fi
  fi

  if [[ -f "$src" ]]; then
    echo "Using local archive: $src"
    cp -f "$src" "$out"
    return 0
  fi

  local url="$src"
  if [[ ! "$src" =~ ^https?:// ]]; then
    url="$BASE_URL/$src"
  fi

  echo "Downloading $url"

  if command -v curl >/dev/null 2>&1; then
    if download_with_curl "$url" "$out"; then
      return 0
    fi
    echo "curl failed for: $url"
  fi

  if command -v wget >/dev/null 2>&1; then
    if download_with_wget "$url" "$out"; then
      return 0
    fi
    echo "wget failed for: $url"
  fi

  return 1
}

if ! download_archive "$INPUT_SRC" "$ARCHIVE"; then
  cat <<EOM
ERROR: Unable to download Matcha model archive.

Tried source: $INPUT_SRC
Resolved base URL: $BASE_URL

Troubleshooting:
1) Test DNS/network in terminal:
   nslookup github.com
   curl -I https://github.com/

2) If GitHub is blocked in terminal, download in browser and pass local path:
   ./scripts/download_matcha_model.sh /absolute/path/to/model.tar.bz2

3) Use an alternate mirror/base URL:
   MATCHA_BASE_URL=https://your.mirror/tts-models ./scripts/download_matcha_model.sh $DEFAULT_PACKAGE
EOM
  exit 1
fi

if ! tar -tf "$ARCHIVE" >/dev/null 2>&1; then
  echo "ERROR: Downloaded file is not a valid tar archive: $ARCHIVE" >&2
  if command -v file >/dev/null 2>&1; then
    file "$ARCHIVE" || true
  fi
  exit 1
fi

tar -xf "$ARCHIVE" -C "$TMP_DIR"
MODEL_DIR="$(find "$TMP_DIR" -mindepth 1 -maxdepth 1 -type d | head -n 1 || true)"
if [[ -z "$MODEL_DIR" ]]; then
  echo "Could not find extracted model directory" >&2
  exit 1
fi

find "$ASSET_ROOT" -mindepth 1 ! -name ".gitkeep" -exec rm -rf {} +

echo "Copying model files to $ASSET_ROOT"
cp -rv "$MODEL_DIR"/* "$ASSET_ROOT"/

find_onnx_by_hint() {
  local hint="$1"
  find "$ASSET_ROOT" -maxdepth 2 -type f -name "*.onnx" | grep -Ei "$hint" | head -n 1 || true
}

ACOUSTIC_FILE=""
VOCODER_FILE=""
TOKENS_FILE=""
LEXICON_FILE=""

if [[ -f "$ASSET_ROOT/acoustic_model.onnx" ]]; then
  ACOUSTIC_FILE="$ASSET_ROOT/acoustic_model.onnx"
else
  ACOUSTIC_FILE="$(find_onnx_by_hint 'acoustic|matcha|model-steps')"
fi

if [[ -f "$ASSET_ROOT/vocoder.onnx" ]]; then
  VOCODER_FILE="$ASSET_ROOT/vocoder.onnx"
else
  VOCODER_FILE="$(find_onnx_by_hint 'vocoder|hifigan|vocos')"
fi

if [[ -z "$ACOUSTIC_FILE" ]]; then
  mapfile -t ONNX_FILES < <(find "$ASSET_ROOT" -maxdepth 2 -type f -name "*.onnx" | sort)
  if [[ ${#ONNX_FILES[@]} -ge 1 ]]; then
    ACOUSTIC_FILE="${ONNX_FILES[0]}"
  fi
fi

TOKENS_FILE="$(find "$ASSET_ROOT" -maxdepth 2 -type f -name 'tokens.txt' | head -n 1 || true)"
LEXICON_FILE="$(find "$ASSET_ROOT" -maxdepth 2 -type f \( -name 'lexicon.txt' -o -name 'lexicon-us-en.txt' -o -name 'lexicon-en.txt' -o -name 'lexicon*.txt' \) | head -n 1 || true)"

if [[ -z "$VOCODER_FILE" ]]; then
  echo "Vocoder model not present in package. Downloading from:"
  echo "  $VOCODER_URL"
  VOCODER_FILE="$ASSET_ROOT/vocos-22khz-univ.onnx"
  if command -v curl >/dev/null 2>&1; then
    download_with_curl "$VOCODER_URL" "$VOCODER_FILE"
  elif command -v wget >/dev/null 2>&1; then
    download_with_wget "$VOCODER_URL" "$VOCODER_FILE"
  else
    echo "ERROR: Need curl or wget to download vocoder model" >&2
    exit 1
  fi
fi

if [[ ! -d "$ASSET_ROOT/espeak-ng-data" ]]; then
  echo "ERROR: Missing espeak-ng-data/ in extracted model package" >&2
  exit 1
fi

if [[ -z "$ACOUSTIC_FILE" || -z "$VOCODER_FILE" || -z "$TOKENS_FILE" ]]; then
  echo "ERROR: Could not locate required Matcha files in package." >&2
  echo "Expected acoustic model, vocoder model, tokens.txt, espeak-ng-data/." >&2
  exit 1
fi

copy_if_needed() {
  local src="$1"
  local dst="$2"
  if [[ "$src" == "$dst" ]]; then
    return 0
  fi
  cp -f "$src" "$dst"
}

copy_if_needed "$ACOUSTIC_FILE" "$ASSET_ROOT/acoustic_model.onnx"
copy_if_needed "$VOCODER_FILE" "$ASSET_ROOT/vocoder.onnx"
copy_if_needed "$TOKENS_FILE" "$ASSET_ROOT/tokens.txt"

if [[ -n "$LEXICON_FILE" ]]; then
  copy_if_needed "$LEXICON_FILE" "$ASSET_ROOT/lexicon.txt"
else
  echo "No lexicon file found in package. Continuing without lexicon.txt"
fi

echo "Done. Installed Matcha files:"
find "$ASSET_ROOT" -maxdepth 2 -type f | sort
