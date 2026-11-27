#!/usr/bin/env bash
# Fine-tunes Qwen3-TTS-12Hz-0.6B-Base on Ryan's voice (official QwenLM kit) and uploads results.
# Env: DATA_URL (SAS URL to data.tar.gz), OUT_URL_PREFIX (container SAS URL prefix, ends with '?<sas>' handled below),
#      EPOCHS, LR, BATCH (defaults = official one-click example).
set -euo pipefail
nvidia-smi --query-gpu=name,memory.total --format=csv
mkdir -p /work && cd /work
curl -sSfL "$DATA_URL" -o data.tar.gz && tar xzf data.tar.gz
cd /opt/Qwen3-TTS/finetuning
python prepare_data.py --device cuda:0 --tokenizer_model_path Qwen/Qwen3-TTS-Tokenizer-12Hz \
  --input_jsonl /work/data/train_raw.jsonl --output_jsonl /work/train_with_codes.jsonl
python sft_12hz.py --init_model_path Qwen/Qwen3-TTS-12Hz-0.6B-Base --output_model_path /work/output \
  --train_jsonl /work/train_with_codes.jsonl --batch_size "${BATCH:-2}" --lr "${LR:-2e-5}" \
  --num_epochs "${EPOCHS:-3}" --speaker_name ryan
python /opt/job/sample.py /work/output /work/samples
cd /work && tar czf results.tar.gz output samples
curl -sSf -X PUT -H "x-ms-blob-type: BlockBlob" --upload-file results.tar.gz "${OUT_BASE}/results-${RUN_ID}.tar.gz?${OUT_SAS}"
echo "UPLOADED results-${RUN_ID}.tar.gz"
