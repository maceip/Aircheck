#!/usr/bin/env bash
set -euo pipefail

# Aircheck Real-Device Validation Script (Limrun)
# Reads LIMRUN_API_KEY strictly from the shell environment (never hardcoded).
# Usage: LIMRUN_API_KEY=... ./scripts/run_limrun_e2e.sh

if [[ -z "${LIMRUN_API_KEY:-}" ]]; then
  echo "ERROR: LIMRUN_API_KEY is not set in the environment." >&2
  echo "Export LIMRUN_API_KEY before running real-device validation on major commits." >&2
  exit 1
fi

echo "==> Building Aircheck debug APK..."
gradle :app:assembleDebug

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$APK_PATH" ]]; then
  echo "ERROR: Debug APK not found at $APK_PATH" >&2
  exit 1
fi

echo "==> Submitting $APK_PATH to Limrun real-device verification..."
if command -v limrun >/dev/null 2>&1; then
  limrun android run \
    --api-key "$LIMRUN_API_KEY" \
    --apk "$APK_PATH" \
    --package "com.aistudio.aircheck.vqxkrm" \
    --verify-launch
else
  echo "limrun CLI not found on PATH. Install @limrun/cli or run inside CI runner with limrun preinstalled."
  echo "APK ready for upload at: $APK_PATH"
fi
