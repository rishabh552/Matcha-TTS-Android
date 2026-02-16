#!/usr/bin/env bash
set -euo pipefail

echo "[deprecated] This project now uses Matcha-only runtime. Forwarding to ./scripts/download_matcha_model.sh" >&2
exec "$(cd "$(dirname "$0")" && pwd)/download_matcha_model.sh" "$@"
