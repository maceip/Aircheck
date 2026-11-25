#!/usr/bin/env bash
set -euo pipefail

ROOT="/home/ubuntu/aircheck-av"
mkdir -p "$ROOT/logs" "$ROOT/work"

# Cleanly stop any prior listeners on :8451 and :8452 without matching our own shell cmdline
fuser -k 8451/tcp >/dev/null 2>&1 || true
fuser -k 8452/tcp >/dev/null 2>&1 || true
sleep 1

nohup env CUDA_VISIBLE_DEVICES=0 PYTHONPATH="$ROOT" \
  "$ROOT/.venvs/video/bin/python" "$ROOT/av_gateway.py" \
  --role video --host 127.0.0.1 --port 8451 \
  >"$ROOT/logs/video_gateway.log" 2>&1 </dev/null &
echo $! >"$ROOT/logs/video_gateway.pid"

nohup env CUDA_VISIBLE_DEVICES=1 PYTHONPATH="$ROOT" \
  "$ROOT/.venvs/voice/bin/python" "$ROOT/av_gateway.py" \
  --role voice --host 127.0.0.1 --port 8452 \
  --default-ref-wav "$ROOT/assets/studio_ref_10s.wav" \
  >"$ROOT/logs/voice_gateway.log" 2>&1 </dev/null &
echo $! >"$ROOT/logs/voice_gateway.pid"

echo "Waiting for GPU-0 (:8451) and GPU-1 (:8452) gateways to warm up..."
for i in $(seq 1 90); do
  v_ok=0
  s_ok=0
  if curl -fsS http://127.0.0.1:8451/health >/dev/null 2>&1; then v_ok=1; fi
  if curl -fsS http://127.0.0.1:8452/health >/dev/null 2>&1; then s_ok=1; fi
  if [ "$v_ok" = "1" ] && [ "$s_ok" = "1" ]; then
    echo "Gateways healthy after ${i}s:"
    curl -fsS http://127.0.0.1:8451/health && echo ""
    curl -fsS http://127.0.0.1:8452/health && echo ""
    exit 0
  fi
  sleep 2
done

echo "ERROR: Gateways failed to warm within 180s" >&2
tail -n 40 "$ROOT/logs/video_gateway.log" "$ROOT/logs/voice_gateway.log" >&2 || true
exit 1
