#!/usr/bin/env bash
set -euo pipefail

# Pre-seed verified VoiceStudio OTA bundle (default: v002) onto a connected Android phone
# over USB ADB so the phone is 100% ready for offline synthesis BEFORE leaving home.
#
# Uses the Mac :8443 ota_server.py over USB `adb reverse` OR direct `adb push` into the
# app's noBackupFilesDir (on debug builds via `run-as`).
#
# Usage:
#   ./scripts/push_v002_bundle_usb.sh [BUNDLE_VERSION] [ADB_SERIAL]

BUNDLE_VERSION="${1:-v002}"
ADB_SERIAL="${2:-}"
PKG="com.aistudio.aircheck.vqxkrm"
SRC_DIR="${VOICESTUDIO_BUNDLES_DIR:-$HOME/Downloads/voicestudio-models/bundles}/$BUNDLE_VERSION"

if [[ ! -f "$SRC_DIR/bundle_manifest.json" ]]; then
  echo "ERROR: Missing manifest: $SRC_DIR/bundle_manifest.json" >&2
  exit 1
fi

ADB_ARGS=()
if [[ -n "$ADB_SERIAL" ]]; then
  ADB_ARGS=(-s "$ADB_SERIAL")
fi

echo "==> Checking connected Android device..."
adb "${ADB_ARGS[@]}" get-state >/dev/null

echo "==> Setting up USB reverse tunnel tcp:8443 -> tcp:8443..."
adb "${ADB_ARGS[@]}" reverse tcp:8443 tcp:8443

echo "==> Pushing verified $BUNDLE_VERSION artifacts to device staging folder..."
STAGE_DIR="/data/local/tmp/aircheck_ota_$BUNDLE_VERSION"
adb "${ADB_ARGS[@]}" shell "rm -rf '$STAGE_DIR' && mkdir -p '$STAGE_DIR'"

python3 - "$SRC_DIR/bundle_manifest.json" "$SRC_DIR" <<'PY' | while IFS= read -r filename; do
import json, sys, pathlib
manifest = json.loads(pathlib.Path(sys.argv[1]).read_text())
src = pathlib.Path(sys.argv[2])
print("bundle_manifest.json")
for art in manifest["artifacts"]:
    p = src / art["filename"]
    if not p.is_file():
        raise SystemExit(f"Missing artifact: {p}")
    print(art["filename"])
PY
  echo "  -> pushing $filename"
  adb "${ADB_ARGS[@]}" push "$SRC_DIR/$filename" "$STAGE_DIR/$filename" >/dev/null
done

echo "==> Installing into app noBackupFilesDir/bundles/$BUNDLE_VERSION via run-as..."
adb "${ADB_ARGS[@]}" shell "run-as $PKG sh -c '
  set -e
  DST=\"\$PWD/no_backup/bundles/$BUNDLE_VERSION\"
  mkdir -p \"\$DST\" \"\$DST/sherpa_ctc\"
  cp \"$STAGE_DIR\"/* \"\$DST/\"
  if [ -f \"\$DST/sherpa_ctc_aligner.tar.gz\" ]; then
    tar -xzf \"\$DST/sherpa_ctc_aligner.tar.gz\" -C \"\$DST/sherpa_ctc\"
  fi
  printf \"%s\n\" \"$BUNDLE_VERSION\" > \"\$PWD/no_backup/bundles/active_bundle.txt\"
  ls -lh \"\$DST\"
'"
adb "${ADB_ARGS[@]}" shell "rm -rf '$STAGE_DIR'"

echo "==> DONE! $BUNDLE_VERSION is pre-seeded on device for 100% offline synthesis."
