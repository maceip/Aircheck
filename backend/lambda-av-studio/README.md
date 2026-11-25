# Aircheck Lambda AV Studio (`2x RTX A6000`)

Dedicated dual-GPU studio backend replacing `wan3.0-video` R2V hallucination and 16 kHz audio downsampling with:

- **GPU 0 (`:8451`, `video_engine.py`)**:
  - `LivePortrait` (`--animation-region pose`, `--driving-multiplier 0.30`) for natural head pose without sideways eye-glance or expression drift from `d0.mp4`.
  - `DISOpticalFlow` 1080p high-frequency camera texture restoration (restricted to interior portrait mask + 20px border feather) preserving real pores, beard, and iris sharpness from `avatar_ref.jpg`.
  - `ByteDance/LatentSync-1.6` (`stage2_512.yaml`, `512x512` mouth inpainting + `DeepCache`).
  - Direct mux of untouched `48 kHz` PCM16 studio audio to `48 kHz / 256 kbps AAC` (`1080x1920` H.264 `yuv420p`) + 512-d `InsightFace ArcFace` (`w600k_r50`) identity `CSIM` verification.
- **GPU 1 (`:8452`, `voice_engine.py`)**:
  - `Chatterbox` BF16 + `F5-TTS v1` BF16 zero-shot studio voice synthesis conditioned on indoor `-16 LUFS` `studio_ref_10s.wav`, scored locally through Aircheck's 4-gate verifier (`WeSpeaker ResNet34`, `Microsoft DNSMOS p808`, `Whisper CER`, cadence, and Sherpa CTC word alignment).

## Quick Start

1. Bootstrap a fresh Lambda Ubuntu GPU instance (`2x RTX A6000` or `A100`/`H100`):
   ```bash
   scp bootstrap.sh video_engine.py voice_engine.py av_gateway.py start_gateways.sh ubuntu@<LAMBDA_IP>:/home/ubuntu/aircheck-av/
   ssh ubuntu@<LAMBDA_IP> 'bash /home/ubuntu/aircheck-av/bootstrap.sh && /home/ubuntu/aircheck-av/start_gateways.sh'
   ```
2. Open local loopback SSH tunnel on the Mac rig:
   ```bash
   ssh -N -L 127.0.0.1:8451:127.0.0.1:8451 -L 127.0.0.1:8452:127.0.0.1:8452 ubuntu@<LAMBDA_IP>
   ```
3. Run `ota_server.py` with `AIRCHECK_VIDEO_ENDPOINT=http://127.0.0.1:8451` and `AIRCHECK_VOICE_ENDPOINT=http://127.0.0.1:8452`.
