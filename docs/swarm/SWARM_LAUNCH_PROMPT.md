# Ready-to-Paste Swarm Launch Command

Copy and paste the block below into chat (with `/teamwork`) to launch the full initiative immediately:

```text
/teamwork Launch the Aircheck Bare-Metal On-Device Synthesis, 4-Gate C++ Verifier, 48kHz RVC v2 + Room IR Resynthesis, Hairline Audio/Video Clipper (Compose + Media3 + Oboe), and Nightly GRPO/OPSD Self-Distillation Rig initiative.

Read these frozen specs and contracts FIRST before spawning any worker:
1. /Users/mac/Aircheck/docs/swarm/ARCHITECTURE.md
2. /Users/mac/Aircheck/docs/swarm/SWARM_PLAN.md
3. /Users/mac/Aircheck/docs/swarm/schemas/clause_node.schema.json
4. /Users/mac/Aircheck/docs/swarm/schemas/ota_bundle_manifest.schema.json
5. /Users/mac/Aircheck/docs/swarm/schemas/preference_tuple.schema.json
6. /Users/mac/Aircheck/app/src/main/java/com/example/data/synthesis/SynthesisContracts.kt
7. /Users/mac/Aircheck/app/src/main/java/com/example/data/synthesis/SynthesisInterfaces.kt

Swarm Execution Rules:
- Load `swarm_pane` skill and write `plan.json` immediately after spawning Wave 1.
- Follow the 3-wave DAG and strict non-overlapping ownership matrix in `docs/swarm/SWARM_PLAN.md`:
  * Wave 1 (parallel): `build-scaffold` (share) + `rig-trainer` (share). Merge `build-scaffold` to the coordinator branch before spawning Wave 2.
  * Wave 2 (4-way parallel, all `workspace: "share"`): `native-dsp`, `g2p-storage`, `synth-workers`, `hairline-clipper`. Do NOT modify frozen Wave-0 contract files without coordinator approval.
  * Wave 3: `integrator` (share) merges and wires Nav3/Room v3/DI/ViewModel/SystemDeck -> `verifier` (inherit) runs C++ ctest, Python pytest, Gradle release APK 16KB/zero-model audit, and Compose E2E instrumentation with screenshots.
- Quota guard: use default/Gemini models (97% quota); do NOT assign 0%-quota Claude or GPT-OSS models.
- Hard constraints:
  * ZERO local Android emulators — verify on physical USB phone or Limrun remote Android (`LIM_API_KEY` in `~/trap/.env`, delete instance afterward).
  * Constitutional Law 2: ZERO fabricated TTS PCM, ZERO hardcoded verifier metrics, ZERO dead UI controls; missing OTA model bundles must fail cleanly with real Tailscale sync UX.
  * Max 2 adjacent labels per label in every Compose component.
  * Constitutional Law 3: Commit and push continuously to `origin` after every wave merges.
```
