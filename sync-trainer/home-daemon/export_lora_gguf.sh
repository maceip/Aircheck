#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF' >&2
Usage:
  export_lora_gguf.sh \
    --adapter-dir <peft_lora_dir> \
    --base-model <base_model_dir_or_ckpt> \
    --llama-cpp-dir <llama_cpp_repo_dir> \
    --output <path/to/voice_lora_vXXX.gguf> \
    [--outtype f16] \
    [--python python3]

Positional shorthand:
  export_lora_gguf.sh <adapter_dir> <base_model> <llama_cpp_dir> <output_gguf> [outtype]
EOF
}

ADAPTER_DIR=""
BASE_MODEL=""
LLAMA_CPP_DIR=""
OUTPUT_PATH=""
OUTTYPE="f16"
PYTHON_BIN="${PYTHON_BIN:-python3}"

if [[ $# -eq 0 ]]; then
  usage
  exit 2
fi

if [[ "${1:-}" != --* && "${1:-}" != "-h" ]]; then
  if [[ $# -lt 4 || $# -gt 5 ]]; then
    usage
    exit 2
  fi
  ADAPTER_DIR="$1"
  BASE_MODEL="$2"
  LLAMA_CPP_DIR="$3"
  OUTPUT_PATH="$4"
  OUTTYPE="${5:-f16}"
else
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --adapter-dir)
        [[ $# -ge 2 ]] || { echo "ERROR: --adapter-dir requires a value" >&2; exit 2; }
        ADAPTER_DIR="$2"
        shift 2
        ;;
      --base-model)
        [[ $# -ge 2 ]] || { echo "ERROR: --base-model requires a value" >&2; exit 2; }
        BASE_MODEL="$2"
        shift 2
        ;;
      --llama-cpp-dir)
        [[ $# -ge 2 ]] || { echo "ERROR: --llama-cpp-dir requires a value" >&2; exit 2; }
        LLAMA_CPP_DIR="$2"
        shift 2
        ;;
      --output)
        [[ $# -ge 2 ]] || { echo "ERROR: --output requires a value" >&2; exit 2; }
        OUTPUT_PATH="$2"
        shift 2
        ;;
      --outtype)
        [[ $# -ge 2 ]] || { echo "ERROR: --outtype requires a value" >&2; exit 2; }
        OUTTYPE="$2"
        shift 2
        ;;
      --python)
        [[ $# -ge 2 ]] || { echo "ERROR: --python requires a value" >&2; exit 2; }
        PYTHON_BIN="$2"
        shift 2
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        echo "ERROR: Unknown argument: $1" >&2
        usage
        exit 2
        ;;
    esac
  done
fi

if [[ -z "$ADAPTER_DIR" || -z "$BASE_MODEL" || -z "$LLAMA_CPP_DIR" || -z "$OUTPUT_PATH" ]]; then
  echo "ERROR: --adapter-dir, --base-model, --llama-cpp-dir, and --output are all required." >&2
  usage
  exit 2
fi

case "$OUTTYPE" in
  f32|f16|bf16|q8_0|auto) ;;
  *)
    echo "ERROR: Unsupported --outtype '$OUTTYPE' (expected one of: f32, f16, bf16, q8_0, auto)." >&2
    exit 2
    ;;
esac

if [[ ! -d "$ADAPTER_DIR" ]]; then
  echo "ERROR: PEFT LoRA adapter directory does not exist: $ADAPTER_DIR" >&2
  exit 1
fi

if [[ ! -f "$ADAPTER_DIR/adapter_config.json" ]]; then
  echo "ERROR: Missing adapter_config.json in adapter directory: $ADAPTER_DIR" >&2
  exit 1
fi

if [[ ! -f "$ADAPTER_DIR/adapter_model.safetensors" && ! -f "$ADAPTER_DIR/adapter_model.bin" ]]; then
  echo "ERROR: Missing adapter_model.safetensors (or adapter_model.bin) in: $ADAPTER_DIR" >&2
  exit 1
fi

if [[ ! -e "$BASE_MODEL" ]]; then
  echo "ERROR: Base model checkpoint path does not exist: $BASE_MODEL" >&2
  exit 1
fi

CONVERTER="$LLAMA_CPP_DIR/convert_lora_to_gguf.py"
if [[ ! -f "$CONVERTER" ]]; then
  echo "ERROR: llama.cpp converter script not found: $CONVERTER" >&2
  exit 1
fi

if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
  echo "ERROR: Python interpreter not found: $PYTHON_BIN" >&2
  exit 1
fi

OUT_BASENAME="$(basename "$OUTPUT_PATH")"
if [[ "$OUT_BASENAME" != *.gguf ]]; then
  echo "ERROR: Output path must end with .gguf (expected e.g. voice_lora_v001.gguf), got: $OUTPUT_PATH" >&2
  exit 2
fi

OUT_DIR="$(dirname "$OUTPUT_PATH")"
mkdir -p "$OUT_DIR"

TMP_OUTPUT="${OUTPUT_PATH}.tmp.$$"
cleanup() {
  if [[ -f "$TMP_OUTPUT" ]]; then
    rm -f "$TMP_OUTPUT"
  fi
}
trap cleanup EXIT

"$PYTHON_BIN" "$CONVERTER" \
  "$ADAPTER_DIR" \
  --base "$BASE_MODEL" \
  --outfile "$TMP_OUTPUT" \
  --outtype "$OUTTYPE"

if [[ ! -s "$TMP_OUTPUT" ]]; then
  echo "ERROR: Converter exited without producing a non-empty GGUF file: $TMP_OUTPUT" >&2
  exit 1
fi

MAGIC="$(LC_ALL=C head -c 4 "$TMP_OUTPUT" || true)"
if [[ "$MAGIC" != "GGUF" ]]; then
  echo "ERROR: Emitted file does not start with valid GGUF magic header: $TMP_OUTPUT" >&2
  exit 1
fi

mv -f "$TMP_OUTPUT" "$OUTPUT_PATH"
trap - EXIT

if command -v sha256sum >/dev/null 2>&1; then
  SHA256="$(sha256sum "$OUTPUT_PATH" | awk '{print $1}')"
elif command -v shasum >/dev/null 2>&1; then
  SHA256="$(shasum -a 256 "$OUTPUT_PATH" | awk '{print $1}')"
else
  SHA256="$("$PYTHON_BIN" -c 'import hashlib, sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' "$OUTPUT_PATH")"
fi

echo "EXPORTED_GGUF=${OUTPUT_PATH}"
echo "SHA256=${SHA256}"
