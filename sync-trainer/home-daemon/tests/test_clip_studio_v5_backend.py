"""Automated unit and HTTP integration tests for Clip Studio v5 Mac-rig backend capabilities.

Covers:
  1. Optional ``tone`` parameter across ``angles``, ``arc``, ``reroll_beat``, ``synthesize``,
     ``synthesize_script`` (``script``), ``brush_swap`` (``brush_nudge``), and ``followup``,
     including space/hyphen normalization, prompt injection, and 100% backward compatibility.
  2. Shot plan generated WITH outline (``POST /v1/draft/arc`` & ``POST /v1/draft/reroll-beat``):
     1-3 ``shots`` per beat validated into ``('face', 'pip', 'broll', 'card')``, corrective retry
     when omitted, and verbatim preservation of non-rerolled beat shots.
  3. Real follow-up drafts endpoint (``POST /v1/draft/followup``) via ``LlmRouter``:
     ``overview`` (``answer_beats`` + ``shitpost`` & ``solarpunk`` split-panel directions) and
     angle switching (``answer``, ``part_2``, ``update``, ``shitpost``, ``solarpunk``), plus strict
     400/502 error propagation with zero fabricated fallback beats.
  4. Real immediate shot preview endpoint (``POST /v1/studio/shot-preview``):
     renders & caches real ``180x252`` JPEG frames in ``renders_dir`` for ``face``, ``broll``,
     ``pip``, and ``card``, served via ``GET /v1/renders/shotprev_<sha16>.jpg``.
"""

from __future__ import annotations

import base64
import io
import json
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from typing import Any, Callable

from PIL import Image, ImageDraw

DAEMON_DIR = Path(__file__).resolve().parents[1]
TESTS_DIR = Path(__file__).resolve().parent
for _p in (DAEMON_DIR, TESTS_DIR):
    if str(_p) not in sys.path:
        sys.path.insert(0, str(_p))

from drafting_passes import (  # noqa: E402
    VALID_DRAFT_TONES,
    VALID_SHOT_TYPES,
    DraftPassEngine,
    normalize_draft_tone,
    normalize_shot_type,
)
from llm_router import LlmRouter  # noqa: E402
from ota_server import create_ota_server  # noqa: E402
import test_drafting_passes as _tdp  # noqa: E402


def _make_real_avatar_jpeg_bytes(
    width: int = 360,
    height: int = 480,
    color: tuple[int, int, int] = (46, 178, 112),
) -> bytes:
    """Builds a real decodable RGB JPEG portrait with distinct emerald/gold pixels for verification."""
    img = Image.new("RGB", (width, height), color)
    draw = ImageDraw.Draw(img)
    draw.ellipse(
        [(width // 4, height // 5), (3 * width // 4, 3 * height // 5)],
        fill=(238, 196, 92),
    )
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=92)
    return buf.getvalue()


class TestClipStudioV5Backend(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.bundles_root = self.root / "bundles"
        self.renders_dir = self.root / "renders"
        self.prefs_path = self.root / "preferences.jsonl"
        _tdp._write_minimal_bundle(self.bundles_root / "v001")

        self.default_avatar = self.root / "default_avatar.jpg"
        self.default_avatar.write_bytes(_make_real_avatar_jpeg_bytes())

        self.server = None
        self._thread = None

    def tearDown(self) -> None:
        if self.server is not None:
            self.server.shutdown()
            self.server.server_close()
        if self._thread is not None:
            self._thread.join(timeout=5.0)
        self._tmp.cleanup()

    def _start_server(
        self,
        *,
        llm_caller: Callable[[str, str], str] | None = None,
        llm_router: LlmRouter | None = None,
        plate_runner: Callable[[str, str, Path], None] | None = None,
        default_avatar_path: Path | None = None,
    ) -> int:
        avatar_p = self.default_avatar if default_avatar_path is None else default_avatar_path
        engine = (
            DraftPassEngine(llm_router=llm_router)
            if llm_router is not None
            else DraftPassEngine(llm_caller=llm_caller)
        )
        self.server = create_ota_server(
            host="127.0.0.1",
            port=0,
            bundles_root=self.bundles_root,
            preferences_path=self.prefs_path,
            renders_dir=self.renders_dir,
            models_root=self.root,
            default_avatar_path=avatar_p,
            render_runner=lambda *_: {"output_mp4": None},
            draft_engine=engine,
            llm_router=llm_router,
            plate_runner=plate_runner,
            quiet=True,
        )
        self._thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self._thread.start()
        return self.server.server_address[1]

    _post_json = _tdp.TestDraftingPassesHttpServer._post_json
    _get = _tdp.TestDraftingPassesHttpServer._get

    def test_tone_normalization_and_prompt_injection_across_passes(self) -> None:
        self.assertEqual(normalize_draft_tone(None), ("hot_take", False))
        self.assertEqual(normalize_draft_tone("   "), ("hot_take", False))
        self.assertEqual(normalize_draft_tone("hot take"), ("hot_take", True))
        self.assertEqual(normalize_draft_tone("HOT-TAKE"), ("hot_take", True))
        for valid_t in VALID_DRAFT_TONES:
            self.assertEqual(normalize_draft_tone(valid_t), (valid_t, True))
        for bad_t in ("hype_beast", 42, True):
            with self.assertRaises(ValueError):
                normalize_draft_tone(bad_t)

        seen_prompts: list[str] = []

        def fake_llm(_sys: str, user: str) -> str:
            seen_prompts.append(user)
            if "extracted_angles" in user:
                return json.dumps(
                    {
                        "extracted_angles": [
                            {"id": "ex_1", "label": "Open local tools", "preview": "Users own the weights on disk."},
                            {"id": "ex_2", "label": "Zero rent", "preview": "Hardware paid once runs indefinitely."},
                        ],
                        "blindspots": [
                            {"id": "bs_1", "label": "Power draw", "preview": "Solar battery budgets still cap wattage."},
                            {"id": "bs_2", "label": "Model updates", "preview": "Peer sync requires signed manifests."},
                            {"id": "bs_3", "label": "RAM ceiling", "preview": "Unified memory limits batch concurrency."},
                        ],
                    }
                )
            if "versions" in user:
                return json.dumps(
                    {
                        "versions": [
                            {"posture_label": "DIRECT", "paragraphs": ["P1.", "P2.", "P3.", "P4."]},
                            {"posture_label": "PUNCHY", "paragraphs": ["S1.", "S2.", "S3.", "S4."]},
                            {"posture_label": "STORY", "paragraphs": ["M1.", "M2.", "M3.", "M4."]},
                        ]
                    }
                )
            if "alternatives" in user:
                return json.dumps({"alternatives": ["open craft", "shared kit"]})
            return json.dumps(
                {
                    "beats": [
                        {
                            "label": lbl,
                            "text": f"{lbl} line in tone.",
                            "shots": [{"type": "face", "prompt": f"{lbl} speaker cue"}],
                        }
                        for lbl in ("HOOK", "DETAIL", "SHIFT", "PAYOFF")
                    ]
                }
            )

        port = self._start_server(llm_caller=fake_llm)

        # 1. Angles with space-separated "hot take" and "solarpunk"
        st, ang = self._post_json(
            port,
            "/v1/draft/angles",
            {"seed_transcript": "Local weights let communities run their own studio.", "tone": "solarpunk"},
        )
        self.assertEqual(st, 200, ang)
        self.assertEqual(ang["tone"], "solarpunk")
        self.assertIn("SELECTED TONE (solarpunk)", seen_prompts[-1])
        self.assertIn("solarpunk", seen_prompts[-1].lower())

        # Omitted tone preserves backward-compatible key set & defaults prompt to hot_take
        st, ang_default = self._post_json(
            port,
            "/v1/draft/angles",
            {"seed_transcript": "Local weights let communities run their own studio."},
        )
        self.assertEqual(st, 200)
        self.assertEqual(set(ang_default.keys()), {"extracted_angles", "blindspots", "llm_meta"})
        self.assertIn("SELECTED TONE (hot_take)", seen_prompts[-1])

        # 2. Arc with "hot take" (space normalized -> "hot_take")
        st, arc_res = self._post_json(
            port,
            "/v1/draft/arc",
            {"seed_transcript": "Subscriptions rot tools.", "tone": "hot take"},
        )
        self.assertEqual(st, 200, arc_res)
        self.assertEqual(arc_res["tone"], "hot_take")
        self.assertIn("SELECTED TONE (hot_take)", seen_prompts[-1])

        # 3. Synthesize-script with "shitpost"
        st, scr = self._post_json(
            port,
            "/v1/draft/synthesize-script",
            {
                "seed_transcript": "Subscriptions rot tools.",
                "arc_beats": ["a", "b", "c", "d"],
                "tone": "shitpost",
            },
        )
        self.assertEqual(st, 200, scr)
        self.assertEqual(scr["tone"], "shitpost")
        self.assertIn("SELECTED TONE (shitpost)", seen_prompts[-1])

        # 4. Brush-swap with "explainer" + v5 "punchier" nudge alias
        st, swap = self._post_json(
            port,
            "/v1/draft/brush-swap",
            {
                "marked_text": "We built <replace>closed SaaS wrappers</replace> yesterday.",
                "nudge": "punchier",
                "tone": "explainer",
            },
        )
        self.assertEqual(st, 200, swap)
        self.assertEqual(swap["tone"], "explainer")
        self.assertEqual(swap["nudge"], "punchier")
        self.assertEqual(swap["alternatives"], ["open craft", "shared kit", ""])
        self.assertIn("SELECTED TONE (explainer)", seen_prompts[-1])

        # Direct Python aliases `engine.script(...)` and `engine.brush_nudge(...)`
        engine = self.server.draft_engine
        self.assertEqual(engine.script({"seed_transcript": "test", "tone": "story"})["tone"], "story")
        self.assertEqual(
            engine.brush_nudge(
                {"marked_text": "Use <replace>slow tools</replace>.", "nudge": "plainer", "tone": "direct"}
            )["tone"],
            "direct",
        )

        # Invalid tone -> HTTP 400
        st, err = self._post_json(
            port,
            "/v1/draft/arc",
            {"seed_transcript": "Subscriptions rot tools.", "tone": "corporate_synergy"},
        )
        self.assertEqual(st, 400, err)

    def test_arc_and_reroll_beat_generate_and_normalize_shot_plans(self) -> None:
        self.assertEqual(normalize_shot_type("talking_head"), "face")
        self.assertEqual(normalize_shot_type("corner head"), "pip")
        self.assertEqual(normalize_shot_type("B-Roll"), "broll")
        self.assertEqual(normalize_shot_type("quote_card"), "card")
        self.assertEqual(normalize_shot_type("unrecognized_camera"), "broll")

        calls = {"arc": 0, "reroll": 0}

        def fake_llm(_sys: str, user: str) -> str:
            if "Rewrite ONLY" in user:
                calls["reroll"] += 1
                return json.dumps(
                    {
                        "beat": "Local chips pay off in two months flat.",
                        "shots": [
                            {"type": "corner_head", "prompt": "Thermal camera view of Mac Studio"},
                            {"type": "quote_card", "prompt": "Break-even: 60 days"},
                        ],
                    }
                )
            calls["arc"] += 1
            # Attempt 1 omits shots -> triggers corrective retry so real LLMs supply shots!
            if calls["arc"] == 1:
                return json.dumps({"beats": ["H1.", "D1.", "S1.", "P1."]})
            return json.dumps(
                {
                    "beats": [
                        {
                            "label": "HOOK",
                            "text": "Cloud rent quietly doubles hardware cost.",
                            "shots": [
                                {"type": "talking_head", "prompt": "Direct presenter medium shot"},
                                {"type": "pip", "prompt": "Monthly invoice ledger scrolling"},
                            ],
                        },
                        {
                            "label": "DETAIL",
                            "text": "Every API minute bills idle queue overhead.",
                            "shots": [
                                {"type": "b-roll", "prompt": "Latency waterfall trace"},
                                {"type": "broll", "prompt": "GPU rack utilization meter"},
                            ],
                        },
                        {
                            "label": "SHIFT",
                            "text": "Teams move inference onto desk workstations.",
                            "shots": [{"type": "pip", "prompt": "Ethernet cable into local node"}],
                        },
                        {
                            "label": "PAYOFF",
                            "text": "Buy the silicon once and keep every margin.",
                            "shots": [
                                {"type": "face", "prompt": "Tight close-up closing line"},
                                {"type": "stat_card", "prompt": "$0 per-minute egress"},
                            ],
                        },
                    ]
                }
            )

        port = self._start_server(llm_caller=fake_llm)
        st, arc = self._post_json(
            port,
            "/v1/draft/arc",
            {"seed_transcript": "Cloud rent vs owning desktop silicon.", "target_sec": 60, "tone": "direct"},
        )
        self.assertEqual(st, 200, arc)
        self.assertEqual(calls["arc"], 2)  # Retried once when attempt 1 omitted shots
        self.assertEqual(len(arc["beats"]), 4)

        expected_windows = ["0-8s", "8-28s", "28-48s", "48-60s"]
        for idx, beat in enumerate(arc["beats"]):
            self.assertEqual(beat["id"], f"beat_{idx}")
            self.assertEqual(beat["window"], expected_windows[idx])
            self.assertIn("start_sec", beat)
            self.assertIn("end_sec", beat)
            self.assertTrue(1 <= len(beat["shots"]) <= 3)
            for shot in beat["shots"]:
                self.assertIn(shot["type"], VALID_SHOT_TYPES)
                self.assertTrue(shot["prompt"])

        self.assertEqual([s["type"] for s in arc["beats"][0]["shots"]], ["face", "pip"])
        self.assertEqual([s["type"] for s in arc["beats"][3]["shots"]], ["face", "card"])

        # Reroll beat 2 via /v1/draft/reroll-beat preserving user-edited shots on beat 0!
        custom_shots = [
            [{"type": "card", "prompt": "Custom user-edited opener card"}],
            arc["beats"][1]["shots"],
            arc["beats"][2]["shots"],
            arc["beats"][3]["shots"],
        ]
        st, rerolled = self._post_json(
            port,
            "/v1/draft/reroll-beat",
            {
                "seed_transcript": "Cloud rent vs owning desktop silicon.",
                "beat_index": 2,
                "existing_beats": [b["text"] for b in arc["beats"]],
                "existing_beat_shots": custom_shots,
                "tone": "direct",
            },
        )
        self.assertEqual(st, 200, rerolled)
        self.assertEqual(rerolled["beats"][0]["shots"], custom_shots[0])
        self.assertEqual(rerolled["beats"][2]["text"], "Local chips pay off in two months flat.")
        self.assertEqual(
            [s["type"] for s in rerolled["beats"][2]["shots"]],
            ["pip", "card"],
        )

    def test_followup_endpoint_uses_real_llm_router_for_overview_and_angles(self) -> None:
        router_calls: list[dict[str, Any]] = []

        def mlx_caller(
            *,
            stage: str,
            model: str,
            system_prompt: str,
            user_prompt: str,
            max_tokens: int,
        ) -> str:
            router_calls.append(
                {
                    "stage": stage,
                    "model": model,
                    "system_prompt": system_prompt,
                    "user_prompt": user_prompt,
                    "max_tokens": max_tokens,
                }
            )
            if "'directions'" in user_prompt or '"directions"' in user_prompt:
                return json.dumps(
                    {
                        "answer_beats": [
                            {
                                "label": "HOOK",
                                "text": "Battery drain stayed under four percent per clip.",
                                "shots": [{"type": "face", "prompt": "Presenter holding phone battery screen"}],
                            },
                            {
                                "label": "DETAIL",
                                "text": "Heavy Whisper and TTS stages run on the Mac over LAN.",
                                "shots": [{"type": "pip", "prompt": "Battery graph flatlining during render"}],
                            },
                            {
                                "label": "SHIFT",
                                "text": "The phone only records PCM audio and previews frames.",
                                "shots": [{"type": "broll", "prompt": "Waveform capture on Android screen"}],
                            },
                            {
                                "label": "PAYOFF",
                                "text": "Your phone stays cool even across ten takes.",
                                "shots": [{"type": "card", "prompt": "4% battery across 10 takes"}],
                            },
                        ],
                        "directions": [
                            {
                                "id": "shitpost",
                                "theme": "SHITPOST",
                                "words": "calling it, cloud thermal throttling lost",
                                "beats": [
                                    {
                                        "label": lbl,
                                        "text": f"Shitpost {lbl.lower()} beat about battery life.",
                                        "shots": [{"type": "pip", "prompt": f"Shitpost {lbl.lower()} visual"}],
                                    }
                                    for lbl in ("HOOK", "DETAIL", "SHIFT", "PAYOFF")
                                ],
                            },
                            {
                                "id": "solarpunk",
                                "theme": "SOLARPUNK",
                                "words": "plot twist: cool phones last years",
                                "beats": [
                                    {
                                        "label": lbl,
                                        "text": f"Solarpunk {lbl.lower()} beat about longevity.",
                                        "shots": [{"type": "broll", "prompt": f"Solarpunk {lbl.lower()} visual"}],
                                    }
                                    for lbl in ("HOOK", "DETAIL", "SHIFT", "PAYOFF")
                                ],
                            },
                        ],
                    }
                )
            return json.dumps(
                {
                    "beats": [
                        {
                            "label": "HOOK",
                            "text": "Part two covers what happens away from home Wi-Fi.",
                            "shots": [{"type": "face", "prompt": "Walking outside studio door"}],
                        },
                        {
                            "label": "DETAIL",
                            "text": "Tailscale keeps the exact same rig socket reachable on LTE.",
                            "shots": [{"type": "broll", "prompt": "Tailscale tunnel status screen"}],
                        },
                        {
                            "label": "SHIFT",
                            "text": "Round-trip latency rises forty milliseconds and still beats queues.",
                            "shots": [{"type": "pip", "prompt": "LTE ping chart next to speaker"}],
                        },
                        {
                            "label": "PAYOFF",
                            "text": "One home box serves every sidewalk voice note.",
                            "shots": [{"type": "card", "prompt": "+40ms over Tailscale LTE"}],
                        },
                    ]
                }
            )

        router = LlmRouter(
            config_path=self.root / "llm_config.json",
            callers={"local_mlx": mlx_caller},
        )
        router.update_config({"stages": {"draft.arc": {"provider": "local_mlx", "model": "mlx-qwen"}}})
        port = self._start_server(llm_router=router)

        # 1. Overview call populates answer_beats + both themed split-panel directions
        st, overview = self._post_json(
            port,
            "/v1/draft/followup",
            {
                "last_title": "Why we moved video rendering off the cloud",
                "last_transcript": "Paying per minute slowed iteration so we put the pipeline on a Mac Studio.",
                "top_reply": "what about phone battery life?",
                "angle": "overview",
            },
        )
        self.assertEqual(st, 200, overview)
        self.assertEqual(overview["angle"], "overview")
        self.assertEqual(overview["llm_meta"]["provider"], "local_mlx")
        self.assertEqual(len(overview["beats"]), 4)
        self.assertEqual(overview["beats"], overview["answer_beats"])
        self.assertEqual([d["id"] for d in overview["directions"]], ["shitpost", "solarpunk"])
        self.assertEqual([d["theme"] for d in overview["directions"]], ["SHITPOST", "SOLARPUNK"])
        for d in overview["directions"]:
            self.assertTrue(3 <= len(d["words"].split()) <= 8)
            self.assertEqual(len(d["beats"]), 4)
            self.assertTrue(all(b["shots"] for b in d["beats"]))
        self.assertIn("what about phone battery life?", router_calls[-1]["user_prompt"])

        # 2. Angle switch ("part 2" normalized -> "part_2")
        st, part2 = self._post_json(
            port,
            "/v1/draft/followup",
            {
                "last_title": "Why we moved video rendering off the cloud",
                "last_transcript": "Paying per minute slowed iteration so we put the pipeline on a Mac Studio.",
                "angle": "part 2",
            },
        )
        self.assertEqual(st, 200, part2)
        self.assertEqual(part2["angle"], "part_2")
        self.assertEqual(len(part2["beats"]), 4)
        self.assertEqual(part2["directions"], [])
        self.assertIn("FOLLOW-UP ANGLE (part_2)", router_calls[-1]["user_prompt"])

        # 3. Validation errors -> 400
        for bad_payload in (
            {"last_title": "", "last_transcript": "Valid"},
            {"last_title": "Valid", "last_transcript": "   "},
            {"last_title": "Valid", "last_transcript": "Valid", "angle": "clickbait"},
        ):
            st_bad, _ = self._post_json(port, "/v1/draft/followup", bad_payload)
            self.assertEqual(st_bad, 400, f"Expected 400 for {bad_payload}")

        # 4. Remaining angles ("answer", "update", "shitpost", "solarpunk") succeed via LlmRouter
        for ang_name in ("answer", "update", "shitpost", "solarpunk"):
            st_ang, res_ang = self._post_json(
                port,
                "/v1/draft/followup",
                {
                    "last_title": "Why we moved video rendering off the cloud",
                    "last_transcript": "Paying per minute slowed iteration.",
                    "angle": ang_name,
                },
            )
            self.assertEqual(st_ang, 200, res_ang)
            self.assertEqual(res_ang["angle"], ang_name)
            self.assertEqual(len(res_ang["beats"]), 4)

    def test_followup_llm_failure_returns_502_never_hardcoded_beats(self) -> None:
        port = self._start_server(llm_caller=lambda _s, _u: "not valid json")
        st, err = self._post_json(
            port,
            "/v1/draft/followup",
            {
                "last_title": "Local inference latency",
                "last_transcript": "Sub-second local ASR changed our workflow.",
                "angle": "overview",
            },
        )
        self.assertEqual(st, 502, err)
        self.assertEqual(err["stage"], "draft.followup")

    def test_shot_preview_renders_real_180x252_jpegs_for_all_four_shot_types(self) -> None:
        plate_calls: list[tuple[str, str]] = []

        def fake_plate_runner(prompt: str, aspect: str, work: Path) -> None:
            plate_calls.append((prompt, aspect))
            img = Image.new("RGB", (360, 640), (30, 62, 118))
            img.save(work / "plate_0.png")

        port = self._start_server(plate_runner=fake_plate_runner)

        def fetch_and_open_jpeg(preview_url: str) -> Image.Image:
            st_get, raw_bytes = self._get(port, preview_url)
            self.assertEqual(st_get, 200)
            self.assertTrue(raw_bytes.startswith(b"\xff\xd8\xff"))
            im = Image.open(io.BytesIO(raw_bytes))
            self.assertEqual(im.format, "JPEG")
            self.assertEqual(im.size, (180, 252))
            return im.convert("RGB")

        # 1. FACE: center-cropped 180x252 from default_avatar_path + deterministic cache
        st, face_res = self._post_json(
            port,
            "/v1/studio/shot-preview",
            {"shot_type": "face", "prompt": "Anchor medium shot"},
        )
        self.assertEqual(st, 200, face_res)
        self.assertRegex(face_res["preview_id"], r"^shotprev_[0-9a-f]{16}$")
        self.assertEqual(face_res["preview_url"], f"/v1/renders/{face_res['preview_id']}.jpg")
        face_img = fetch_and_open_jpeg(face_res["preview_url"])
        # Corner pixel reflects our emerald default_avatar background (~46, 178, 112)
        r0, g0, b0 = face_img.getpixel((8, 8))
        self.assertGreater(g0, r0 + 60)

        # Also accessible via /v1/renders/previews/<filename>
        st_prev, prev_bytes = self._get(port, f"/v1/renders/previews/{face_res['preview_id']}.jpg")
        self.assertEqual(st_prev, 200)
        self.assertGreater(len(prev_bytes), 500)

        # 2. BROLL (procedural slate frame when plates_dir empty)
        st, broll_res = self._post_json(
            port,
            "/v1/studio/shot-preview",
            {"shot_type": "broll", "prompt": "Thermal camera view of M5 Max"},
        )
        self.assertEqual(st, 200, broll_res)
        broll_img = fetch_and_open_jpeg(broll_res["preview_url"])

        # 3. PIP: real composite of 180x252 B-roll frame + 56x72 portrait inset at bottom-right (114..170, 170..242)
        st, pip_res = self._post_json(
            port,
            "/v1/studio/shot-preview",
            {"shot_type": "pip", "prompt": "Thermal camera view of M5 Max"},
        )
        self.assertEqual(st, 200, pip_res)
        pip_img = fetch_and_open_jpeg(pip_res["preview_url"])
        # Top-left B-roll badge area matches broll_img, while bottom-right inset has emerald/gold portrait pixels!
        self.assertEqual(broll_img.getpixel((30, 50)), pip_img.getpixel((30, 50)))
        self.assertNotEqual(broll_img.getpixel((140, 205)), pip_img.getpixel((140, 205)))

        # 4. BROLL with generate_backdrop=True invokes plate_runner and composites real plate
        st, broll_gen = self._post_json(
            port,
            "/v1/studio/shot-preview",
            {
                "shot_type": "broll",
                "prompt": "Warm dusk broadcast desk",
                "generate_backdrop": True,
            },
        )
        self.assertEqual(st, 200, broll_gen)
        self.assertEqual(len(plate_calls), 1)
        fetch_and_open_jpeg(broll_gen["preview_url"])

        # 5. CARD: real #1A1A1E card with #D71921 red accent bar & rendered headline/prompt text
        st, card_res = self._post_json(
            port,
            "/v1/studio/shot-preview",
            {
                "shot_type": "card",
                "headline": "ZERO CLOUD RENT",
                "prompt": "Pay once for silicon.",
            },
        )
        self.assertEqual(st, 200, card_res)
        card_img = fetch_and_open_jpeg(card_res["preview_url"])
        # Verify #1A1A1E (26, 26, 30) background & #D71921 (215, 25, 33) red bar at y=52
        bg_r, bg_g, bg_b = card_img.getpixel((90, 235))
        self.assertTrue(abs(bg_r - 26) <= 6 and abs(bg_g - 26) <= 6 and abs(bg_b - 30) <= 6)
        bar_r, bar_g, bar_b = card_img.getpixel((60, 52))
        self.assertGreater(bar_r, 185)
        self.assertLess(bar_g, 55)
        self.assertLess(bar_b, 55)
        # Verify bright text pixels rendered below y=70
        bright_text_pixels = sum(
            1
            for y in range(68, 175)
            for x in range(14, 166)
            if card_img.getpixel((x, y))[0] > 150 and card_img.getpixel((x, y))[1] > 150
        )
        self.assertGreater(bright_text_pixels, 80)

        # Ensure shotprev_*.jpg files do NOT pollute GET /v1/renders MP4 list
        st_list, list_raw = self._get(port, "/v1/renders")
        self.assertEqual(st_list, 200)
        self.assertEqual(json.loads(list_raw.decode("utf-8")), {"renders": []})

        # Invalid shot_type -> 400
        st_bad, _ = self._post_json(port, "/v1/studio/shot-preview", {"shot_type": "hologram"})
        self.assertEqual(st_bad, 400)

    def test_shot_preview_face_returns_404_when_avatar_missing_and_accepts_base64_photo(self) -> None:
        missing_avatar = self.root / "nonexistent_avatar.jpg"
        port = self._start_server(default_avatar_path=missing_avatar)

        st_404, err = self._post_json(
            port,
            "/v1/studio/shot-preview",
            {"shot_type": "face", "prompt": "Missing avatar check"},
        )
        self.assertEqual(st_404, 404, err)

        b64_photo = base64.b64encode(_make_real_avatar_jpeg_bytes(color=(180, 60, 90))).decode("ascii")
        st_ok, ok_res = self._post_json(
            port,
            "/v1/studio/shot-preview",
            {"shot_type": "face", "prompt": "Supplied base64 photo", "photo_jpeg_base64": b64_photo},
        )
        self.assertEqual(st_ok, 200, ok_res)
        st_img, raw_img = self._get(port, ok_res["preview_url"])
        self.assertEqual(st_img, 200)
        with Image.open(io.BytesIO(raw_img)) as decoded_im:
            self.assertEqual(decoded_im.size, (180, 252))

    def test_generate_backdrop_plate_uses_openai_when_bl_quota_exhausted(self) -> None:
        from http.server import BaseHTTPRequestHandler, HTTPServer
        from unittest.mock import patch
        import studio_cut

        seen_requests: list[dict] = []
        buf = io.BytesIO()
        Image.new("RGB", (320, 480), (24, 36, 58)).save(buf, format="PNG")
        png_b64 = base64.b64encode(buf.getvalue()).decode("ascii")

        class FakeImagesHandler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:  # noqa: N802
                n = int(self.headers.get("Content-Length", "0"))
                seen_requests.append(json.loads(self.rfile.read(n).decode("utf-8")))
                resp = json.dumps({"data": [{"b64_json": png_b64}]}).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(resp)))
                self.end_headers()
                self.wfile.write(resp)

            def log_message(self, *_args: object) -> None:
                return

        srv = HTTPServer(("127.0.0.1", 0), FakeImagesHandler)
        t = threading.Thread(target=srv.serve_forever, daemon=True)
        t.start()
        try:
            failing_bl = self.root / "failing_bl.sh"
            failing_bl.write_text("#!/bin/sh\necho '429 Throttling.AllocationQuota' >&2\nexit 1\n", encoding="utf-8")
            failing_bl.chmod(0o755)
            plates_dir = self.root / "plates_test"
            with patch.dict("os.environ", {"OPENAI_API_KEY": "sk-test-img"}, clear=False):
                out = studio_cut.generate_backdrop_plate(
                    prompt="Warm tungsten broadcast desk",
                    aspect="9:16",
                    plates_dir=plates_dir,
                    bl_bin=failing_bl,
                    openai_base_url=f"http://127.0.0.1:{srv.server_port}/v1",
                )
            self.assertTrue(out["plate_id"].startswith("plate_"))
            self.assertEqual((out["width"], out["height"]), (320, 480))
            self.assertEqual(len(seen_requests), 1)
            self.assertEqual(seen_requests[0]["size"], "1024x1536")
            self.assertTrue((plates_dir / f"{out['plate_id']}.png").is_file())
        finally:
            srv.shutdown()
            srv.server_close()


if __name__ == "__main__":
    unittest.main()
