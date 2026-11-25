#!/usr/bin/env bash
set -euo pipefail

ROOT="/home/ubuntu/aircheck-av"
mkdir -p "$ROOT/.venvs" "$ROOT/assets" "$ROOT/work" "$ROOT/logs"
cd "$ROOT"

echo "[1/5] Checking system packages and installing uv..."
if ! command -v ffmpeg >/dev/null 2>&1 || ! dpkg -s libgl1 libsndfile1 >/dev/null 2>&1; then
  sudo apt-get update -qq
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq ffmpeg git curl unzip libgl1 libglib2.0-0 libsndfile1 build-essential python3-dev
fi

if ! command -v uv >/dev/null 2>&1; then
  curl -LsSf https://astral.sh/uv/install.sh | sh
fi
export PATH="$HOME/.local/bin:$PATH"
export HF_HUB_ENABLE_HF_TRANSFER=1

echo "[2/5] Cloning ByteDance/LatentSync and KwaiVGI/LivePortrait..."
if [ ! -d "$ROOT/LatentSync/.git" ]; then
  git clone --depth 1 https://github.com/ByteDance/LatentSync.git "$ROOT/LatentSync"
fi
if [ ! -d "$ROOT/LivePortrait/.git" ]; then
  git clone --depth 1 https://github.com/KwaiVGI/LivePortrait.git "$ROOT/LivePortrait"
fi

echo "[3/5] Building GPU-0 Video venv (.venvs/video: PyTorch 2.5.1+cu121 + LatentSync 1.6 + LivePortrait)..."
if [ ! -x "$ROOT/.venvs/video/bin/python" ]; then
  uv venv --python 3.10 "$ROOT/.venvs/video"
fi
uv pip install --python "$ROOT/.venvs/video/bin/python" \
  --extra-index-url https://download.pytorch.org/whl/cu121 \
  torch==2.5.1 torchvision==0.20.1 torchaudio==2.5.1 \
  diffusers==0.32.2 transformers==4.48.0 accelerate==0.26.1 \
  decord==0.6.0 einops==0.7.0 omegaconf==2.3.0 \
  opencv-python==4.9.0.80 mediapipe==0.10.11 python_speech_features==0.6 \
  librosa==0.10.1 scenedetect==0.6.1 ffmpeg-python==0.2.0 \
  imageio==2.34.2 imageio-ffmpeg==0.5.1 lpips==0.1.4 \
  face-alignment==1.4.1 "huggingface-hub[cli,hf_transfer]>=0.30.2" \
  numpy==1.26.4 kornia==0.8.0 insightface==0.7.3 onnxruntime-gpu==1.21.0 \
  DeepCache==0.1.1 soundfile scipy==1.13.1 scikit-image==0.24.0 \
  albumentations==1.4.10 pykalman==0.9.7 tyro==0.8.5 pyyaml rich tqdm \
  fastapi uvicorn python-multipart

echo "[4/5] Downloading LatentSync 1.6, LivePortrait, SD-VAE, and InsightFace weights..."
"$ROOT/.venvs/video/bin/python" - <<'PY'
import os
from pathlib import Path
from huggingface_hub import hf_hub_download, snapshot_download

os.environ["HF_HUB_ENABLE_HF_TRANSFER"] = "1"
root = Path("/home/ubuntu/aircheck-av")

ls_ckpt = root / "LatentSync" / "checkpoints"
ls_ckpt.mkdir(parents=True, exist_ok=True)
for fname in ["latentsync_unet.pt", "whisper/tiny.pt", "stable_syncnet.pt", "auxiliary/sfd_face.pth"]:
    print(f"Downloading ByteDance/LatentSync-1.6 :: {fname}", flush=True)
    hf_hub_download(repo_id="ByteDance/LatentSync-1.6", filename=fname, local_dir=str(ls_ckpt))

lp_ckpt = root / "LivePortrait" / "pretrained_weights"
lp_ckpt.mkdir(parents=True, exist_ok=True)
print("Downloading KlingTeam/LivePortrait (human weights)...", flush=True)
snapshot_download(
    repo_id="KlingTeam/LivePortrait",
    local_dir=str(lp_ckpt),
    ignore_patterns=["*animal*", "*.git*", "docs/*", "*.gif", "README.md"],
)

print("Pre-caching stabilityai/sd-vae-ft-mse and InsightFace buffalo_l...", flush=True)
from diffusers import AutoencoderKL
AutoencoderKL.from_pretrained("stabilityai/sd-vae-ft-mse")

from insightface.app import FaceAnalysis
aux_dir = ls_ckpt / "auxiliary"
aux_dir.mkdir(parents=True, exist_ok=True)
app = FaceAnalysis(
    name="buffalo_l",
    root=str(aux_dir),
    providers=["CPUExecutionProvider"],
)
app.prepare(ctx_id=-1, det_size=(512, 512))
print("Video weights verified!", flush=True)
PY

echo "[5/5] Building GPU-1 Voice venv (.venvs/voice: F5-TTS v1 + Chatterbox TTS)..."
if [ ! -x "$ROOT/.venvs/voice/bin/python" ]; then
  uv venv --python 3.11 "$ROOT/.venvs/voice"
fi
uv pip install --python "$ROOT/.venvs/voice/bin/python" \
  --extra-index-url https://download.pytorch.org/whl/cu124 \
  torch==2.6.0 torchaudio==2.6.0 torchvision==0.21.0 \
  f5-tts chatterbox-tts "huggingface-hub[cli,hf_transfer]" \
  soundfile scipy librosa fastapi uvicorn python-multipart

"$ROOT/.venvs/voice/bin/python" - <<'PY'
import os
os.environ["HF_HUB_ENABLE_HF_TRANSFER"] = "1"
print("Pre-caching F5-TTS and Chatterbox checkpoints...", flush=True)
from f5_tts.api import F5TTS
_ = F5TTS(device="cpu")
from chatterbox.tts import ChatterboxTTS
_ = ChatterboxTTS.from_pretrained(device="cpu")
print("Voice weights verified!", flush=True)
PY

echo "=== BOOTSTRAP COMPLETE ==="
