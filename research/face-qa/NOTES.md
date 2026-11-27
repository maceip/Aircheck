liveavatar4 (2026-11-25): A100 80GB, SIZE=704*384 (cond cache hard-coded 2800 tokens; 480*832 fails), --offload_kv_cache required (OOM otherwise). 12.2s clip -> 948s generation, 384x704 25fps.

## 2026-11-25 state (pre-reboot)
- outputs/: kandinsky3/4.mp4, heygen_av4(_cover).mp4, side_by_side.mp4, anchor.jpg, voice/epoch0-4.wav (gitignored media; azure-specs/ holds secrets, gitignored)
- HeyGen: CLI ~/.local/bin/heygen, OAuth login (Pro plan credits; API key has 0 API credit). Avatar group 49475c7a39704a95bcba875769333c75.
  Studio photo look 8a828b8147694bd79faba33eab869ae9; news-anchor look (charcoal suit, desk, 9:16) 5004722ca57c7c0a0f12297250c84857.
- Next: idea seed -> script -> TTS with run9 checkpoint (blob run9/checkpoint-epoch-N.tar) -> render anchor look with Avatar V + motion_prompt -> b-roll + corner PiP + overlays.
