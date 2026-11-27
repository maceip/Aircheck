# Optional Mac media providers

This is an opt-in integration layer, not an installation or replacement of the running rig. No dependency, model, service, launch agent, or default backend is changed. Without `--media-config`, the existing voice-service/S2-Pro selection and HeyGen rendering are unchanged.

## Implemented versus deferred

| Project | Integration in this change |
|---|---|
| Chatterbox | Local Original/Turbo/Nano/Multilingual V3 adapter through existing broadcast verification/mastering; CPU/MPS selection |
| MuseTalk | Reviewed v1.5 CLI adapter for an explicitly provisioned **CUDA worker**; no native Apple acceleration claim |
| OpenAvatarChat | Independently implemented sample-clock/session/cancellation primitives inspired by its architecture; no WebRTC server added |
| Amphion | Independently implemented pre-aligned contour RMSE/correlation and real-time-factor utilities; no restricted model imports |
| RVC | Cataloged as optional future performance conversion; not added after synthesis |
| SadTalker | Cataloged; no runnable adapter yet |
| EchoMimic V2 | Cataloged and unavailable pending checkpoint license clearance |
| FaceFusion | Cataloged and unavailable pending governing license/model review |
| Vidu-S | Cataloged as a hosted service; no API calls or credentials added |
| OmniTalker | Cataloged as unreleased inference; cannot be activated |
| deepfake-live-for-macos | Cataloged as restricted camera swapping; not imported |

All eleven appear in `GET /v1/media/providers`. This endpoint reports **configuration and research status**, not model readiness, benchmark results, or license certification. It does not load models or disclose local config paths. Only `legacy`/`chatterbox` TTS and `legacy`/`musetalk` video can be selected. Other IDs fail closed.

## Safe opt-in configuration

Keep the config outside the checkout. Pass it explicitly to a separately started daemon:

```text
python ota_server.py --host 127.0.0.1 --port 18443 \
  --bundles-dir /absolute/existing/bundles \
  --preferences-file /absolute/sandbox/preferences.jsonl \
  --renders-dir /absolute/sandbox/renders \
  --media-config /absolute/sandbox/media-providers.json
```

This is an example only; this change does not start a server. Use a different port and fresh output/preferences directories when evaluating. Never point a test server at the live jobs/output directory. Do not invoke unrelated endpoints that change settings, install services, or call image providers during evaluation.

Start with the supplied `examples/media-providers.disabled.json`. Both backends remain `legacy` until deliberately selected. Invalid/missing explicitly supplied config is an error, not a silent fallback.

A local TTS configuration looks like this (paths are placeholders):

```json
{
  "schema_version": 1,
  "tts_backend": "chatterbox",
  "video_backend": "legacy",
  "chatterbox": {
    "variant": "turbo",
    "device": "auto",
    "python_bin": "/absolute/reviewed-chatterbox-venv/bin/python",
    "model_dir": "/absolute/models/chatterbox-turbo",
    "artifact_manifest": "/absolute/manifests/chatterbox-turbo.json",
    "reference_audio": "/absolute/consented-voice.wav",
    "reference_consent": true,
    "timeout_seconds": 600
  }
}
```

No loader uses Chatterbox `from_pretrained()`. Model files are preverified and `from_local()` is used. The child process gets Hugging Face/Transformers offline settings and a Python audit hook denying network connections. These are safeguards for reviewed Python code, **not an operating-system sandbox against malicious native extensions or arbitrary subprocesses**. Provision and trust the Python runtime separately.

- `auto` selects MPS when available, otherwise CPU. Explicit `mps` fails when unavailable.
- `original`, `turbo`, `nano`, and `multilingual_v3` are supported API variants. V3 is selected explicitly, not left at upstream's V2 default. Multilingual accepts `language_id` (default `en`). Existing Aircheck gates remain English-oriented; multilingual generation is not a promise of multilingual production verification.
- Reference conditioning is prepared once per worker request, reused across its takes, then discarded when the process exits. Separate processes prevent cross-request mutable conditioning leaks.
- The model is reloaded per clause request. This favors isolation over latency; it is **not** a warm or streaming engine. No performance claim is made.
- Upstream `generate()` is used with its PerTh watermark intact. Detection is not independently verified, including after Aircheck mastering.
- The returned audio is resampled to 44.1 kHz and enters the existing strict verifier, real alignment, mastering, and publication path. No gate is bypassed and no fabricated tokens/scores or silent tails are inserted by this adapter.
- Explicit local-backend failure never silently switches to a cloud provider. Existing `legacy` fallback behavior is unchanged.
- Aircheck's existing verification/mastering assets must still be provisioned; choosing a new synthesizer does not remove those requirements.

## Artifact manifest

A manifest is an **operator record of an actual review**, not a way to grant yourself rights to a model. Do not set approval fields without the corresponding permission. Keep it outside the model directory where practical.

```json
{
  "provider": "chatterbox",
  "variant": "turbo",
  "source_revision": "reviewed-upstream-commit",
  "model_revision": "reviewed-model-repository-revision",
  "license_review": "reference to the completed code/model/dependency review",
  "commercial_use_approved": true,
  "files": {
    "ve.safetensors": "64-character-lowercase-sha256",
    "t3_turbo_v1.safetensors": "64-character-lowercase-sha256",
    "s3gen_meanflow.safetensors": "64-character-lowercase-sha256",
    "tokenizer_config.json": "64-character-lowercase-sha256",
    "vocab.json": "64-character-lowercase-sha256",
    "merges.txt": "64-character-lowercase-sha256"
  }
}
```

The example is intentionally not a valid manifest. Use real hashes for every local model/tokenizer file, including optional `conds.pt`, token files, and other JSON/text assets. Variant-specific required filenames are in `chatterbox.py`. Files with pickle-based serialization are not safe merely because they have a hash: hashes establish integrity relative to the reviewed manifest, not trustworthiness.

Path traversal and symlinks escaping the model root are rejected. Missing files, checksum mismatch, missing revision, missing license-review record, and absent approval fail before loading. Models must remain immutable during a request. Checksums do not defend against a malicious local process modifying the files between validation and loading. Chatterbox's source revision is operator-attested metadata, not independently verified against the installed Python package.

## MuseTalk: prepared worker, not native MPS

The adapter intentionally refuses execution without CUDA. It does not claim that an Apple Silicon port has been completed. This makes the rendering interface usable on a separately provisioned CUDA worker while preventing a misleading Mac CPU fallback.

Required configuration fields are:

- `python_bin`: absolute interpreter in the prepared MuseTalk environment.
- `inference_script`: absolute `<checkout>/scripts/inference.py`.
- `model_dir`: absolute directory with the complete upstream `models/` layout.
- `artifact_manifest`: all model/dependency hashes and license-review record.
- `reference_consent: true`.
- `cache_dir`: absolute, dedicated, owner-controlled cache directory.
- `timeout_seconds`: 1..7200, default 1800.

The source checkout must be clean at revision `2536eaf06b85f775d8e04b7fd81bf6699155a58d`. No checkout operation or dependency installation is performed. A MuseTalk manifest requires `provider: "musetalk"`, that `source_revision`, a `model_revision`, `commercial_use_approved: true`, a `license_review`, and a nonempty **`hdtf_clearance`** record. This is required because the [official model NOTICE](https://huggingface.co/TMElyralab/MuseTalk/blob/main/NOTICE) raises commercialization issues despite the MIT declaration.

Include every auxiliary file in the hash manifest. Required paths include the v1.5 UNet/config, DWPose, face parser, VAE/Whisper configs, and `s3fd/s3fd.pth`. Provision VAE and Whisper weights as well. The isolated worker stages the reviewed Python sources and points its S3FD loader at the verified local detector, preventing its normal missing-weight download path. Python network connections are denied; offline model loading fails when an auxiliary asset is absent.

Rendering behavior:

1. Normalize the approved voice input using the existing canonical-master path.
2. Stage portrait, audio, reviewed sources, and a generated JSON inference config in a private temporary directory; leave upstream checkout unchanged.
3. Invoke the reviewed CLI using only controlled, space-free paths where upstream constructs shell commands.
4. Replace generated audio with the canonical master, normalize dimensions, and run existing FFprobe audiovisual checks.
5. Publish only after validation, using a no-overwrite filesystem link.

The MP4 uses AAC delivery encoding; “input voice” means the selected source track, not byte-identical lossless audio. The cache currently stores **face coordinates only**, not VAE latents or a live avatar session. Its identity includes portrait content, artifact hashes, source revision, and preprocessing settings. Treat this pickle cache as trusted local application state, not uploaded user data.

## Session and evaluation primitives

`session.py` provides sample-index timestamps, a monotonic session clock, and cancellation generations that reject results from previous utterances. These are standalone building blocks, not a completed realtime transport or Android client.

`evaluation.py` compares already aligned pitch/energy contours and calculates measured real-time factor. Constant contours have undefined correlation (`null`), not a fabricated perfect score. The utilities do not extract pitch, predict MOS, or replace the existing verifier.

## Rollback and verification

Omit `--media-config` or select both `legacy` defaults. No migration, download, package removal, or live-service edit is required. Keep the task worktree until the owner chooses whether to merge.

Tests use fixture models and injected runners; no model inference or licensing claim is validated by them. Run the new suite with an existing test environment:

```text
python -m pytest tests/test_media_providers.py
```

Also run the existing render bridge, talking-head, broadcast studio, core pipeline, alignment, and studio regression suites. Actual quality, memory, latency, and watermark survival must be measured later with separately authorized, provisioned assets.
