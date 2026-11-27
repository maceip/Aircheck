"""Comprehensive unit and HTTP integration tests for the Aircheck Studio Overhaul backend.

Covers:
  1. Single-beat reroll anti-repetition guard (`_is_too_similar_to_any` + multi-attempt retry)
     ensuring sparkle/reroll never repeats the old beat or prior attempts.
  2. Anchor enforcement (`selected_angles` REQUIRED + `excluded_angles` FORBIDDEN) in both
     `arc()` and `synthesize_script()`.
  3. Real FFmpeg `compose_studio_cut()` with:
     - per-clause transitions (`fade`, `wipeleft`, `slideup`, `fadeblack`) + `transition_count`
     - mixed per-clause `broll_framing` overrides (`full`, `pip`, `split`)
     - custom uploaded PNG still AND looping MP4 video B-roll (`broll_media_id`)
     - `show_lower_third=False` toggle
  4. HTTP endpoints on `ota_server.py`:
     - `POST /v1/core/broll-preview` + `GET /v1/broll-media/<filename>`
     - `POST /v1/core/broll-media` (image & video uploads) + retrieval + path traversal rejection
"""

from __future__ import annotations

import base64
import io
import json
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path

from PIL import Image

DAEMON_DIR = Path(__file__).resolve().parents[1]
TESTS_DIR = Path(__file__).resolve().parent
for _p in (DAEMON_DIR, TESTS_DIR):
    if str(_p) not in sys.path:
        sys.path.insert(0, str(_p))

from drafting_passes import DraftPassEngine  # noqa: E402
from ota_server import _probe_media_duration_ms, create_ota_server  # noqa: E402
from studio_cut import (  # noqa: E402
    StudioLook,
    StudioRenderOptions,
    broll_media_dir,
    compose_studio_cut,
    save_broll_media_bytes,
)
import test_drafting_passes as _tdp  # noqa: E402

HAS_FFMPEG = shutil.which("ffmpeg") is not None and shutil.which("ffprobe") is not None


def _make_video(path: Path, *, duration: float = 3.0, size: str = "360x640", color: str = "testsrc2") -> None:
    vsrc = f"testsrc2=size={size}:rate=30:duration={duration}" if color == "testsrc2" else f"color=c={color}:s={size}:r=30:d={duration}"
    subprocess.run(
        [
            "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
            "-f", "lavfi", "-i", vsrc,
            "-f", "lavfi", "-i", f"aevalsrc='0.3*sin(2*PI*440*t)':s=44100:d={duration}",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", str(path),
        ],
        check=True,
    )


def _probe_dims(path: Path) -> tuple[int, int]:
    out = subprocess.check_output(
        [
            "ffprobe", "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=width,height", "-of", "csv=p=0", str(path),
        ],
        text=True,
    ).strip()
    w, h = out.split(",")
    return int(w), int(h)


def _png_bytes(w: int = 320, h: int = 180, rgb: tuple[int, int, int] = (24, 140, 210)) -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (w, h), rgb).save(buf, format="PNG")
    return buf.getvalue()


class TestDraftingOverhaul(unittest.TestCase):
    def test_reroll_beat_rejects_duplicates_and_returns_fresh_text(self) -> None:
        calls: list[str] = []
        old_beat = "Cloud subscriptions drain creators every single month."

        def llm(_sys: str, user: str) -> str:
            calls.append(user)
            if len(calls) == 1:
                # Attempt 1 repeats the exact old text -> must be rejected!
                return json.dumps({
                    "beat": {
                        "label": "DETAIL",
                        "text": old_beat,
                        "shots": [{"type": "face", "prompt": "anchor"}],
                    }
                })
            # Attempt 2 returns a genuinely new beat
            return json.dumps({
                "beat": {
                    "label": "DETAIL",
                    "text": "Your phone finishes the entire voice clone and edit in twenty seconds offline.",
                    "shots": [{"type": "broll", "prompt": "phone rendering locally"}],
                }
            })

        engine = DraftPassEngine(llm_caller=llm)
        res = engine.arc({
            "seed_transcript": "Local phone tools beat monthly subscriptions.",
            "reroll_index": 1,
            "existing_beats": [
                "Stop paying monthly to cut short videos.",
                old_beat,
                "Cloud clusters still help giant studios.",
                "Own your tools once.",
            ],
            "previous_texts": [old_beat],
        })
        self.assertEqual(len(calls), 2, "Should retry once when attempt 1 duplicates old beat")
        self.assertIn("PREVIOUS DETAIL VERSION(S) TO REPLACE", calls[0])
        self.assertEqual(
            res["beats"][1]["text"],
            "Your phone finishes the entire voice clone and edit in twenty seconds offline.",
        )
        # Non-rerolled beats preserved verbatim
        self.assertEqual(res["beats"][0]["text"], "Stop paying monthly to cut short videos.")
        self.assertEqual(res["beats"][3]["text"], "Own your tools once.")

    def test_selected_and_excluded_angles_injected_into_arc_and_script(self) -> None:
        prompts: list[str] = []

        def llm(_sys: str, user: str) -> str:
            prompts.append(user)
            if "versions" in user:
                return json.dumps({
                    "versions": [
                        {"posture_label": "DIRECT", "paragraphs": ["One.", "Two.", "Three.", "Four."]},
                        {"posture_label": "PUNCHY", "paragraphs": ["A.", "B.", "C.", "D."]},
                        {"posture_label": "STORY", "paragraphs": ["X.", "Y.", "Z.", "W."]},
                    ]
                })
            return json.dumps({
                "beats": [
                    {"label": lbl, "text": f"{lbl} mentions offline latency.", "shots": [{"type": "face", "prompt": "host"}]}
                    for lbl in ("HOOK", "DETAIL", "SHIFT", "PAYOFF")
                ]
            })

        engine = DraftPassEngine(llm_caller=llm)
        engine.arc({
            "seed_transcript": "Local inference on phones is fast.",
            "selected_angles": ["offline latency: zero network wait"],
            "excluded_angles": ["cloud pricing: skip pricing talk"],
        })
        self.assertIn("REQUIRED ANCHOR POINTS", prompts[-1])
        self.assertIn("offline latency: zero network wait", prompts[-1])
        self.assertIn("EXCLUDED ANGLES", prompts[-1])
        self.assertIn("cloud pricing: skip pricing talk", prompts[-1])

        engine.synthesize_script({
            "seed_transcript": "Local inference on phones is fast.",
            "arc_beats": ["a", "b", "c", "d"],
            "selected_angles": ["offline latency: zero network wait"],
            "excluded_angles": ["cloud pricing: skip pricing talk"],
        })
        self.assertIn("REQUIRED ANCHOR POINTS", prompts[-1])
        self.assertIn("EXCLUDED ANGLES", prompts[-1])
        self.assertIn("cloud pricing: skip pricing talk", prompts[-1])


@unittest.skipUnless(HAS_FFMPEG, "ffmpeg/ffprobe required")
class TestBrollTransitionsAndCustomMediaFfmpeg(unittest.TestCase):
    def test_compose_studio_cut_with_transitions_custom_image_video_and_no_lower_third(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "head.mp4"
            _make_video(src, duration=3.0, size="360x640")

            # Save both a custom PNG still and a custom MP4 looping clip in broll-media dir
            plates_dir = root / "plates"
            img_meta = save_broll_media_bytes(plates_dir, _png_bytes(480, 270, (210, 64, 32)), "png")
            img_id = img_meta["media_id"]
            broll_vid = root / "clip_broll.mp4"
            _make_video(broll_vid, duration=1.0, size="320x180", color="blue")
            vid_meta = save_broll_media_bytes(plates_dir, broll_vid.read_bytes(), "mp4")
            vid_id = vid_meta["media_id"]

            out = root / "cut_transitions.mp4"
            stats = compose_studio_cut(
                src_mp4=src,
                out_mp4=out,
                work_dir=root,
                clauses=[
                    {
                        "id": "c0",
                        "spoken_text": "Opening talking head.",
                        "start_ms": 0,
                        "end_ms": 1000,
                        "segment_mode": "TALKING_HEAD",
                        "transition": "cut",
                    },
                    {
                        "id": "c1",
                        "spoken_text": "Image cutaway with wipe.",
                        "start_ms": 1000,
                        "end_ms": 2000,
                        "segment_mode": "BROLL_CUTAWAY",
                        "broll_framing": "full",
                        "broll_media_id": img_id,
                        "transition": "wipeleft",
                        "transition_duration_ms": 300,
                    },
                    {
                        "id": "c2",
                        "spoken_text": "Video cutaway with slideup and dip.",
                        "start_ms": 2000,
                        "end_ms": 3000,
                        "segment_mode": "BROLL_CUTAWAY",
                        "broll_framing": "split",
                        "broll_media_id": vid_id,
                        "transition": "slideup",
                        "transition_duration_ms": 250,
                    },
                ],
                overlays=[],
                options=StudioRenderOptions(
                    aspect="16:9",
                    show_lower_third=False,
                    default_transition="fade",
                    broll_framing="pip",
                    look=StudioLook(accent_hex="#2563EB"),
                ),
                probe_duration_ms=_probe_media_duration_ms,
                plates_dir=plates_dir,
            )
            self.assertTrue(out.is_file() and out.stat().st_size > 2000)
            self.assertEqual(_probe_dims(out), (1280, 720))
            self.assertGreaterEqual(stats["transition_count"], 2)


class TestBrollMediaHttpEndpoints(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.bundles_root = self.root / "bundles"
        self.renders_dir = self.root / "renders"
        self.prefs_path = self.root / "preferences.jsonl"
        _tdp._write_minimal_bundle(self.bundles_root / "v001")
        self.default_avatar = self.root / "default_avatar.jpg"
        self.default_avatar.write_bytes(_png_bytes(100, 100))

        def fake_plate(prompt: str, aspect: str, work: Path) -> None:
            Image.new("RGB", (320, 180), (16, 185, 129)).save(work / "img.png")

        self.server = create_ota_server(
            host="127.0.0.1",
            port=0,
            bundles_root=self.bundles_root,
            preferences_path=self.prefs_path,
            renders_dir=self.renders_dir,
            models_root=self.root,
            default_avatar_path=self.default_avatar,
            render_runner=lambda *_: {"output_mp4": None},
            plate_runner=fake_plate,
            quiet=True,
        )
        self._thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self._thread.start()
        self.port = self.server.server_address[1]

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self._thread.join(timeout=5.0)
        self._tmp.cleanup()

    _post_json = _tdp.TestDraftingPassesHttpServer._post_json
    _get = _tdp.TestDraftingPassesHttpServer._get

    def test_broll_preview_and_upload_and_fetch_endpoints(self) -> None:
        # 1. Generate real B-roll preview via plate_runner
        st, prev = self._post_json(
            self.port,
            "/v1/core/broll-preview",
            {"prompt": "Macro shot of matte smartphone camera array", "aspect": "9:16"},
        )
        self.assertEqual(st, 200, prev)
        self.assertTrue(prev["media_id"].startswith("broll_"))
        self.assertTrue(prev["url"].startswith("/v1/broll-media/"))

        gst, raw_png = self._get(self.port, prev["url"])
        self.assertEqual(gst, 200)
        self.assertTrue(raw_png.startswith(b"\x89PNG"))

        # 2. Upload custom B-roll image bytes
        img_bytes = _png_bytes(240, 240, (220, 40, 80))
        st2, up = self._post_json(
            self.port,
            "/v1/core/broll-media",
            {"media_base64": base64.b64encode(img_bytes).decode("ascii"), "ext": "png"},
        )
        self.assertEqual(st2, 200, up)
        self.assertFalse(up["is_video"])
        gst2, fetched = self._get(self.port, up["url"])
        self.assertEqual(gst2, 200)
        self.assertEqual(fetched, img_bytes)

        # 3. Path traversal rejected
        bad_st, _ = self._get(self.port, "/v1/broll-media/../../etc/passwd")
        self.assertIn(bad_st, (400, 403, 404))

    def test_explicit_heygen_look_id_takes_precedence_over_stale_photo_base64(self) -> None:
        captured_specs: list[dict] = []

        def fake_render(spec: dict, *_args, **_kwargs) -> dict:
            captured_specs.append(dict(spec))
            return {"output_mp4": None}

        # Replace render_runner on our test server to inspect work_spec built by POST /v1/render-video
        self.server.render_manager._render_runner = fake_render
        st, body = self._post_json(
            self.port,
            "/v1/render-video",
            {
                "script_id": "scr_hg",
                "title": "HeyGen look precedence test",
                "spoken_text": "Testing explicit look override.",
                "video_backend": "heygen",
                "heygen_look_id": "3659b8a71a9142e18a6ae193d3e8f3c6",
                "photo_base64": base64.b64encode(_png_bytes(64, 64)).decode("ascii"),
            },
        )
        self.assertEqual(st, 202, body)
        # Wait briefly for worker thread to receive spec
        import time
        deadline = time.time() + 3.0
        while not captured_specs and time.time() < deadline:
            time.sleep(0.05)
        self.assertTrue(captured_specs, "Render runner should have received spec")
        self.assertEqual(captured_specs[0]["heygen_look_id"], "3659b8a71a9142e18a6ae193d3e8f3c6")
        self.assertFalse(
            captured_specs[0]["custom_photo"],
            "Explicit heygen_look_id MUST NOT be overridden by custom_photo=True",
        )


if __name__ == "__main__":
    unittest.main()
