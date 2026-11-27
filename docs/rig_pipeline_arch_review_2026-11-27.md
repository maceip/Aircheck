# Rig pipeline and architecture review — 2026-11-27

Scope: `tools/aircheck-rig` (supervisor), `sync-trainer/home-daemon` (daemon, 5.6k-line
`ota_server.py` plus helpers), `backend/remotion-worker`, `backend/lambda-av-studio`, and the
app-to-rig contract. Read from source and from the live state on this Mac. Nothing was started,
stopped or changed for this review. Items marked *not checked* were not verified.

## How it is wired today

```
phone app ──LAN http://<mac>:8443 ──────────────────────────┐
phone app ──5G── trycloudflare URL /t/<secret> ─ proxy :8445 ┤
                                                             ▼
                                         ota_server.py :8443  (bound 0.0.0.0, no auth)
        ┌───────────────┬──────────────────┬─────────────────┼──────────────────┐
        ▼               ▼                  ▼                 ▼                  ▼
  Remotion :8444   MLX LLM :8080     SSH tunnel to      HeyGen CLI         OpenAI images /
  (supervised)     (NOT supervised)  Lambda 2xA6000     (subprocess)       `bl` CLI (b-roll)
                                     :8451 video (unused)
                                     :8452 voice (used)
```

Stages, in order: draft (transcribe, angles, synthesize, script) → `core/tts` (voice + forced
alignment + gates) → `render-video` (talking head) → `core/chop-broll` (b-roll stills + final
cut, ffmpeg or Remotion) → download to the phone.

## Findings, most serious first

### 1. The daemon has no authentication and listens on the whole LAN
`main.go:527` starts the daemon with `--host 0.0.0.0`; `ota_server.py` has no token or
`Authorization` check on any route (the only `Authorization` in the file is the outbound Rybbit
log shipper). The capability secret only protects the cloudflared path. Anyone on the same
Wi-Fi can:
- start HeyGen renders, OpenAI image generations and LLM calls on your accounts
  (`/v1/render-video`, `/v1/core/chop-broll`, `/v1/newsroom/backdrop`, `/v1/draft/*`);
- download every render, cloned-voice WAV and alignment file: `/v1/renders/<name>` serves any
  top-level file in the renders directory (`ota_server.py:1733`), which today also includes
  `llm_config.json` and `remotion_state.json` (no API keys in either — checked);
- read `/v1/logs` and trigger `npm run build` via `/v1/remotion/install`.

Fix: bind to `127.0.0.1` and send LAN traffic through the same capability proxy (bind the proxy
on the LAN interface), so there is one authenticated entry point. The existing
`adb reverse tcp:8443` already covers the USB case.

### 2. Nothing brings the rig back; it died this morning and left no trace
There is no LaunchAgent for `aircheck-rig` (none in `~/Library/LaunchAgents`). `state.json` was
last written at 04:03 local today by pid 14414, with every service healthy, and then stops —
no shutdown line, because the supervisor only writes `supervisor.log` when launched with
`start`; in foreground `run` its output goes to the terminal and is lost. That log's last entry
is from 25 Nov. So the cause of this morning's exit is unrecoverable.

Fix: a LaunchAgent with `KeepAlive`, always tee supervisor output to a rotated log file, and
record child exit codes in `state.json`.

### 3. Dead wiring left over from the removed self-hosted lip-sync path
`backend/lambda-av-studio/video_engine.py` (LivePortrait + LatentSync 1.6, port 8451) is still
forwarded by the SSH tunnel and polled every 2 s, and the supervisor still exports
`AIRCHECK_VIDEO_ENDPOINT` (`main.go`). No Python file reads that variable.

Correction (same day): this was a deliberate removal, not an accident. Commit `a0664f1` made
HeyGen Avatar 6 the single talking-head engine and deleted the LatentSync/Lambda path. What
remains is leftover: a tunnelled port, an env var, a "Lambda Video" status line and the GPU
box's video half running with no caller. Either remove those, or reuse the slot if a
self-hosted engine (for example EchoMimicV2) is wanted again.

### 4. `--media-config` is unreachable and the local adapters cannot work on this host
- `aircheck-rig` never passes `--media-config`, so `chatterbox_local` and `musetalk` always
  report unavailable.
- The MuseTalk adapter is a local subprocess that refuses to run without CUDA
  (`media_providers/musetalk.py:81-83`); the daemon runs on an M5 Max. It can never run here.
- Chatterbox local reloads the model for every clause (README: "not a warm or streaming
  engine").
- Enabling either needs a hand-written manifest with hashes, a pinned upstream commit and
  license-review fields — a lot of ceremony for a single-user rig.

Fix: load the config from `.aircheck-rig/media-providers.json` when present; move MuseTalk
behind the GPU endpoint from finding 3 or delete it; keep a warm Chatterbox worker if local
voice is wanted.

### 5. Silent substitutions in the pipeline
- **Voice, `auto`:** tries Chatterbox on the Lambda box and, if the tunnel is down, switches to
  local S2-Pro with only a log warning (`ota_server.py:894`). Two different voice models, and
  the phone is not told which one spoke. *Not checked:* whether the engine name reaches the UI.
- **B-roll prompts:** when the LLM returns a shot with no prompt, or no shots at all, the
  spoken sentence is used as the image prompt (`drafting_passes.py:874-905`). That is where
  the "talking head explaining shift to AI-driven security" cutaways in the 26 Nov renders came
  from.
- **Remotion stats:** `caption_count` is `len(words)`, which is 0 whenever captions came from
  `alignUrl` (`ota_server.py:3048`). All 13 logged Remotion renders report 0 captions while
  showing captions.
- **HeyGen voice** turns the quality gates off (`strict_gates = False`).

Fix: return the engine actually used and show it; fail the shot instead of prompting with the
sentence, or mark it as unplanned; count caption phrases from the compiled timeline.

### 6. Tunnel and secrets
- `ssh -o StrictHostKeyChecking=no` to a hard-coded public IP committed in source
  (`main.go:36`, `:504`). A reassigned IP or an on-path attacker gets the voice requests and
  can return audio. Pin the host key; move the host to `.aircheck-rig/env`.
- The public URL includes the secret and is written to `state.json` (0644), the supervisor log
  and stdout. The secret never rotates.
- `trycloudflare` quick tunnels have no uptime guarantee and a new hostname every start, which
  is why the phone-sync machinery exists. A named Cloudflare tunnel gives a fixed hostname and
  removes the adb push for the remote case.
- `loadDotEnvFile` copies everything in `~/.zshenv` and `.env` into the environment of every
  child, including `cloudflared` and `ssh`. `.aircheck-rig/env` is 0644.
- The Remotion worker is started without `REMOTION_WORKER_TOKEN`; it is loopback-only, but any
  local process can make it write an MP4 to an arbitrary `outputLocalPath`.

### 7. Supervisor behaviour
- MLX on :8080 is health-checked and shown in `status` but not started or restarted by the rig.
  If it dies, local LLM stages fail until someone starts it by hand.
- `start` and `status` call the rig ready when only the daemon and Remotion are up; GPU and MLX
  health are displayed but gate nothing, and nothing alerts when they drop.
- `cleanupPortListeners` runs `pkill -f` on loose patterns and SIGTERMs whatever is listening
  on 8443/8444/8445, including processes the rig did not start.
- Every URL sync runs `am start` on the phone, which pulls the app to the foreground whenever
  the phone reconnects or the tunnel URL changes.
- Restart backoff caps at 15 s forever; a child that can never start (missing key, bad venv)
  loops silently.
- Python is "first of `$AIRCHECK_PYTHON`, `~/.venv`, Homebrew"; `pyproject.toml` declares
  `dependencies = []` and there is no lock file. The daemon's real dependencies (numpy, Pillow,
  torch for S2-Pro, …) are whatever that interpreter happens to have. I could not run the
  daemon tests without supplying packages by hand.

### 8. Daemon structure and throughput
- `ota_server.py` is 5,618 lines holding HTTP routing, the job manager, TTS orchestration,
  HeyGen option caching, Remotion integration and ffmpeg compositing. Routing is two long
  `if path ==` chains. It is hard to change one stage without reading the rest.
- One render worker thread, one TTS thread, two async threads. A HeyGen render that takes
  minutes blocks every other talking-head job; fine for one user, a hard ceiling otherwise.
- Jobs are one JSON file each plus an in-memory dict, loaded in full at start (148 files now).
  Restart recovery is sound: unfinished jobs are marked failed with `rig_restarted`
  (`ota_server.py:1412`). But a rig restart mid-HeyGen-render loses a render you may already
  have paid for; the HeyGen job id is not persisted for resume. *Not checked* beyond the job
  record fields.
- No retention: the renders directory is 1.8 GB, 507 top-level files (405 shot previews), and I
  found no cleanup code.
- Heavy stages run as blocking subprocesses with `capture_output`, so progress inside a HeyGen
  or ffmpeg step is invisible until it ends.

### 9. Renderer split (ffmpeg and Remotion)
Two compositors implement overlapping features with different gaps. As of branch
`remotion-default` Remotion is the default and ffmpeg is used when a cut needs trim pauses,
non-cut transitions or a look grade. Known Remotion defects from the logged renders: 16:9 crops
the portrait anchor to chest and tie (`objectFit: cover`, `BroadcastTemplate.tsx:518`); the
quote card is pinned for the whole clip over the anchor's forehead and truncates mid-word; one
still is held across consecutive beats. Each feature added to one renderer has to be added to
the other or routed around; pick one as the long-term compositor.

### 10. Tests and CI
22 daemon test files, a Go test for the supervisor (107 lines), 21 worker tests including real
renders. No CI workflow in the repo, so nothing runs them automatically. The path the product
depends on — phone → tunnel → daemon → GPU voice → HeyGen → chop → download — has no automated
end-to-end test on the rig side.

## What is solid
- Capability proxy: constant-time prefix compare, path-boundary check, 128-bit secret.
- File serving: traversal markers rejected up front, single-segment names, resolved-path
  containment (`ota_server.py:1733-1745`).
- Job records written atomically with fsync; async tasks persisted so the phone can resume
  polling instead of resubmitting.
- Bounded queues with an explicit "queue full" error.
- Remotion worker: strict input validation, body-size limits, real-render tests.
- Explicit local backends fail closed instead of falling through to a paid provider.

## Suggested order
1. Bind the daemon to loopback and put LAN behind the capability proxy (finding 1).
2. LaunchAgent + persistent supervisor log (finding 2).
3. Pin the SSH host key; move the GPU host out of source (finding 6).
4. Remove or reuse the dead GPU video wiring; load media config from `.aircheck-rig/` (3, 4).
5. Report the engine actually used; stop prompting b-roll with the spoken sentence (5).
6. Supervise MLX; lock the Python environment; add CI (7, 10).
7. Split `ota_server.py` by stage; add render retention (8).
8. Choose one compositor (9).

## Fixed on 2026-11-27 (branch `remotion-default`)
- Supervisor: `restart`, `url`, `doctor`, `install-service` / `uninstall-service`; foreground runs
  now write `.aircheck-rig/logs/supervisor.log` (rotated at 10 MB) and log the stopping signal,
  SIGHUP included; `--media-config` is passed when `.aircheck-rig/media-providers.json` exists.
- Remotion: portrait anchor framed whole in 16:9; overlays timed by `durationSec`; overlay text
  cut at word boundaries; `caption_count` comes from the worker.
- Voice: the TTS result carries `voice_engine`, the engine that actually spoke.

Not changed: authentication (by request), the b-roll prompt substitution in finding 5 (it is
relied on by the arc and reroll flows and by the chop itself, so removing it is a product
decision), MLX supervision, the Python lock file, CI, the daemon split and render retention.
