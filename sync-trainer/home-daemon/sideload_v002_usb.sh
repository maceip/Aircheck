#!/usr/bin/env bash
set -euo pipefail

# Pre-loads the verified 10-artifact VoiceStudio v002 OTA bundle directly onto an attached
# Android device over USB (zero Wi-Fi download, zero "Voice OTA bundle not synced" wall).
#
# Usage:
#   ./sync-trainer/home-daemon/sideload_v002_usb.sh [adb-serial]

BUNDLE_VERSION="${BUNDLE_VERSION:-v002}"
SRC_DIR="${SRC_DIR:-/Users/mac/Downloads/voicestudio-models/bundles/${BUNDLE_VERSION}}"
PKG="com.aistudio.aircheck.vqxkrm"
SERIAL="${1:-$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')}"

if [[ -z "${SERIAL}" ]]; then
  echo "ERROR: No connected Android device found via adb." >&2
  exit 1
fi
if [[ ! -f "${SRC_DIR}/bundle_manifest.json" ]]; then
  echo "ERROR: Missing ${SRC_DIR}/bundle_manifest.json" >&2
  exit 1
fi

TMP_STAGE="/data/local/tmp/aircheck_ota_${BUNDLE_VERSION}"
echo "[1/4] Pushing verified ${BUNDLE_VERSION} bundle (${SRC_DIR}) to ${SERIAL} over USB..."
adb -s "${SERIAL}" shell "rm -rf '${TMP_STAGE}' && mkdir -p '${TMP_STAGE}'"

# Push ONLY the manifest + the exact 10 files declared in bundle_manifest.json
FILES=$(/Users/mac/.venv/bin/python -c "import json; m=json.load(open('${SRC_DIR}/bundle_manifest.json')); print('\n'.join([a['filename'] for a in m['artifacts']]))")
adb -s "${SERIAL}" push "${SRC_DIR}/bundle_manifest.json" "${TMP_STAGE}/bundle_manifest.json"
while IFS= read -r fname; do
  [[ -z "${fname}" ]] && continue
  adb -s "${SERIAL}" push "${SRC_DIR}/${fname}" "${TMP_STAGE}/${fname}"
done <<< "${FILES}"

echo "[2/4] Moving bundle into ${PKG} no_backup/bundles/${BUNDLE_VERSION} + extracting sherpa_ctc..."
adb -s "${SERIAL}" shell "run-as ${PKG} sh -c '
  set -e
  DST=\"no_backup/bundles/${BUNDLE_VERSION}\"
  rm -rf \"\$DST\" && mkdir -p \"\$DST/sherpa_ctc\"
  cp \"${TMP_STAGE}\"/* \"\$DST/\"
  tar -xzf \"\$DST/sherpa_ctc_aligner.tar.gz\" -C \"\$DST/sherpa_ctc\"
  printf \"%s\" \"${BUNDLE_VERSION}\" > no_backup/bundles/active_bundle.txt
  ls -lh \"\$DST\"
'"

echo "[3/4] Cleaning temporary staging dir..."
adb -s "${SERIAL}" shell "rm -rf '${TMP_STAGE}'"

echo "[4/4] Verifying active_bundle.txt + restarting Aircheck..."
adb -s "${SERIAL}" shell "run-as ${PKG} cat no_backup/bundles/active_bundle.txt && echo ''"
adb -s "${SERIAL}" shell am force-stop "${PKG}"
adb -s "${SERIAL}" reverse tcp:8443 tcp:8443 || true
adb -s "${SERIAL}" shell am start -n "${PKG}/com.example.MainActivity"
echo "DONE: ${BUNDLE_VERSION} pre-loaded on ${SERIAL} with zero Wi-Fi download."
