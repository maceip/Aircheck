"""Tests for the Clip Studio v4 rig backends.

* /v1/draft/arc (+ single-beat reroll), /v1/draft/synthesize-script, brush-swap tone nudges
* studio_cut: silencedetect parsing, timeline remapping, and a REAL ffmpeg composition that applies
  strip-silence, punch-in, captions, 16:9 aspect, and a full Newsroom look over a backdrop plate
* /v1/core/silence-gaps, /v1/newsroom/backdrop, GET /v1/plates/<id>.png, chop-broll render_options
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import unittest
from pathlib import Path
from typing import Any

DAEMON_DIR = Path(__file__).resolve().parents[1]
TESTS_DIR = Path(__file__).resolve().parent
for p in (DAEMON_DIR, TESTS_DIR):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

from studio_cut import (  # noqa: E402
    StudioLook,
    StudioRenderOptions,
    compose_studio_cut,
    keep_segments,
    parse_silencedetect_log,
    remap_ms,
)
from ota_server import _probe_media_duration_ms  # noqa: E402
import test_drafting_passes as _tdp  # noqa: E402

HAS_FFMPEG = shutil.which("ffmpeg") is not None and shutil.which("ffprobe") is not None


def _make_test_clip(path: Path) -> None:
    """3.0s 9:16 clip: tone 0-1.0s, silence 1.0-2.0s, tone 2.0-3.0s."""
    subprocess.run(
        [
            "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
            "-f", "lavfi", "-i", "testsrc2=size=360x640:rate=30:duration=3",
            "-f", "lavfi", "-i",
            "aevalsrc='if(between(t,1,2),0,0.5*sin(2*PI*440*t))':s=44100:d=3",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", str(path),
        ],
        check=True,
    )


def _probe_dims(path: Path) -> tuple[int, int]:
    out = subprocess.check_output(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
         "stream=width,height", "-of", "csv=p=0", str(path)],
        text=True,
    ).strip()
    w, h = out.split(",")
    return int(w), int(h)



def _fake_image_runner(full_prompt: str, aspect: str, work: Path) -> None:
    """Stands in for Bailian/OpenAI image generation in tests (no network)."""
    from PIL import Image

    Image.new("RGB", (576, 1024) if aspect == "9:16" else (1024, 576), (20, 60, 90)).save(work / "img.png")


class TestStudioCutPure(unittest.TestCase):
    def test_parse_silencedetect_ignores_edges_and_pads(self) -> None:
        log = (
            "[silencedetect @ 0x1] silence_start: 0\n"
            "[silencedetect @ 0x1] silence_end: 0.4 | silence_duration: 0.4\n"
            "[silencedetect @ 0x1] silence_start: 1.0\n"
            "[silencedetect @ 0x1] silence_end: 2.0 | silence_duration: 1.0\n"
            "[silencedetect @ 0x1] silence_start: 2.9\n"
        )
        self.assertEqual(parse_silencedetect_log(log, 3000), [(1090, 1910)])

    def test_keep_segments_and_remap(self) -> None:
        gaps = [(1000, 2000)]
        self.assertEqual(keep_segments(3000, gaps), [(0, 1000), (2000, 3000)])
        self.assertEqual(remap_ms(500, gaps), 500)
        self.assertEqual(remap_ms(1500, gaps), 1000)
        self.assertEqual(remap_ms(2500, gaps), 1500)

    def test_tts_token_budget_scales_with_script(self) -> None:
        from ota_server import tts_token_budget

        self.assertEqual(tts_token_budget("one two three"), 320)
        sixty_sec = " ".join(["word"] * 150)
        self.assertEqual(tts_token_budget(sixty_sec), 1806)
        self.assertEqual(tts_token_budget(" ".join(["w"] * 1000)), 2400)

    def test_split_points_prefer_pauses_and_respect_cap(self) -> None:
        from studio_cut import pick_split_points

        self.assertEqual(pick_split_points(25_000, [], 30_000), [])
        self.assertEqual(pick_split_points(62_000, [(27_000, 27_600), (55_000, 55_400)], 30_000), [27_300, 55_200])
        self.assertEqual(pick_split_points(70_000, [], 30_000), [30_000, 60_000])

    def test_options_validation(self) -> None:
        with self.assertRaises(ValueError):
            StudioRenderOptions.from_payload({"aspect": "4:3"})
        with self.assertRaises(ValueError):
            StudioLook.from_payload({"blur_percent": 140})
        with self.assertRaises(ValueError):
            StudioLook.from_payload({"light": "pink"})
        with self.assertRaises(ValueError):
            StudioLook.from_payload({"plate_id": "../../etc/passwd"})
        opts = StudioRenderOptions.from_payload(
            {"punch_in": True, "captions": True, "aspect": "16:9", "breaking_style": "developing",
             "look": {"blur_percent": 65, "brightness_ev": -0.5, "light": "warm", "grain": True, "border": True}}
        )
        self.assertEqual(opts.aspect, "16:9")
        self.assertEqual(opts.breaking_style, "developing")
        self.assertEqual(opts.look.brightness_ev, -0.5)

    def test_word_safe_truncation_never_cuts_mid_word(self) -> None:
        from studio_cut import _truncate_words

        raw = "Instead of shipping your voice biometrics to third-party servers, your personal voice model runs locally."
        truncated = _truncate_words(raw, 88)
        self.assertTrue(truncated.endswith("…"))
        self.assertNotIn("voice mo…", truncated)
        self.assertIn("personal voice…", truncated)


@unittest.skipUnless(HAS_FFMPEG, "ffmpeg/ffprobe required")
class TestStudioCutFfmpeg(unittest.TestCase):
    def test_split_and_concat_round_trip(self) -> None:
        import tempfile

        from studio_cut import concat_mp4_segments, split_wav_at_pauses

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            wav = root / "v.wav"
            subprocess.run(
                ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                 "aevalsrc='if(between(t,2,2.6),0,0.5*sin(2*PI*300*t))':s=16000:d=5", str(wav)],
                check=True,
            )
            chunks = split_wav_at_pauses(wav, root, max_chunk_sec=3)
            self.assertEqual(len(chunks), 2)
            self.assertAlmostEqual(_probe_media_duration_ms(chunks[0]) / 1000.0, 2.3, delta=0.15)
            segs = []
            for i in range(2):
                seg = root / f"s{i}.mp4"
                _make_test_clip(seg)
                segs.append(seg)
            out = concat_mp4_segments(segs, root / "joined.mp4")
            self.assertAlmostEqual(_probe_media_duration_ms(out) / 1000.0, 6.0, delta=0.3)

    def test_real_composition_applies_every_option(self) -> None:
        import tempfile

        from PIL import Image

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "head.mp4"
            _make_test_clip(src)
            plates = root / "plates"
            plates.mkdir()
            Image.new("RGB", (640, 360), (40, 30, 20)).save(plates / "plate_0123456789ab.png")
            out = root / "out.mp4"
            stats = compose_studio_cut(
                src_mp4=src,
                out_mp4=out,
                work_dir=root,
                clauses=[
                    {"id": "c0", "spoken_text": "First line here.", "start_ms": 0, "end_ms": 1000},
                    {"id": "c1", "spoken_text": "Pause.", "start_ms": 1000, "end_ms": 2000,
                     "segment_mode": "BROLL_CUTAWAY", "broll_caption": "Sensor macro"},
                    {"id": "c2", "spoken_text": "Second line here.", "start_ms": 2000, "end_ms": 3000},
                ],
                overlays=[{"headline": "Quote", "subline": "sub", "duration_sec": 1}],
                options=StudioRenderOptions.from_payload(
                    {"strip_silence": True, "punch_in": True, "captions": True, "aspect": "16:9",
                     "look": {"plate_id": "plate_0123456789ab", "blur_percent": 50,
                              "brightness_ev": 0.5, "light": "cool", "grain": True, "border": True}}
                ),
                plates_dir=plates,
                probe_duration_ms=_probe_media_duration_ms,
                image_runner=_fake_image_runner,
            )
            self.assertTrue(out.is_file())
            self.assertEqual(_probe_dims(out), (1280, 720))
            self.assertEqual(stats["removed_silence_gap_count"], 1)
            self.assertGreater(stats["removed_silence_ms"], 600)
            # 3s source minus ~0.8s removed pause.
            self.assertLess(_probe_media_duration_ms(out), 2500)
            self.assertEqual(stats["caption_count"], 2)
            self.assertEqual(stats["punch_in_count"], 1)
            self.assertEqual(stats["broll_cut_count"], 1)
            self.assertEqual(stats["applied_overlay_count"], 1)
            self.assertEqual([c["id"] for c in stats["clauses"]], ["c0", "c1", "c2"])
            self.assertLess(stats["clauses"][2]["start_ms"], 2000)

    def test_missing_plate_is_rejected(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "head.mp4"
            _make_test_clip(src)
            with self.assertRaises(ValueError):
                compose_studio_cut(
                    src_mp4=src, out_mp4=root / "o.mp4", work_dir=root, clauses=[], overlays=[],
                    options=StudioRenderOptions.from_payload({"look": {"plate_id": "plate_ffffffffffff"}}),
                    plates_dir=root / "plates", probe_duration_ms=_probe_media_duration_ms,
                )

    def test_compose_studio_cut_preserves_1080p_and_48khz(self) -> None:
        import subprocess
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "head_1080p.mp4"
            subprocess.run(
                [
                    "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                    "-f", "lavfi", "-i", "color=c=navy:s=1080x1920:r=25:d=2.400",
                    "-f", "lavfi", "-i", "sine=frequency=220:sample_rate=48000:duration=2.400",
                    "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-ar", "48000", "-b:a", "256k",
                    str(src),
                ],
                check=True,
                timeout=30,
            )
            out = root / "cut_1080p.mp4"
            stats = compose_studio_cut(
                src_mp4=src,
                out_mp4=out,
                work_dir=root,
                clauses=[
                    {"id": "c0", "spoken_text": "Anchor lead.", "start_ms": 0, "end_ms": 1200},
                    {"id": "c1", "spoken_text": "Cutaway proof.", "start_ms": 1200, "end_ms": 2400, "segment_mode": "BROLL_CUTAWAY"},
                ],
                overlays=[{"kind": "LOWER_THIRD", "breaking_style": "breaking", "headline": "1080P DESK", "subline": "STUDIO VERIFIED", "duration_sec": 3}],
                options=StudioRenderOptions.from_payload({"captions": True, "aspect": "9:16"}),
                plates_dir=root / "plates",
                probe_duration_ms=_probe_media_duration_ms,
                image_runner=_fake_image_runner,
            )
            self.assertEqual(_probe_dims(out), (1080, 1920))
            self.assertEqual(stats["broll_cut_count"], 1)
            self.assertEqual(stats["applied_overlay_count"], 1)
            probe = subprocess.run(
                ["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=sample_rate", "-of", "default=nw=1:nk=1", str(out)],
                capture_output=True, text=True, check=True, timeout=15,
            )
            self.assertEqual(probe.stdout.strip(), "48000")


class _RigServerBase(unittest.TestCase):
    setUp = _tdp.TestDraftingPassesHttpServer.setUp
    tearDown = _tdp.TestDraftingPassesHttpServer.tearDown
    _post_json = _tdp.TestDraftingPassesHttpServer._post_json
    _get = _tdp.TestDraftingPassesHttpServer._get

    def _start(self, *, llm_caller: Any = None, plate_runner: Any = None) -> int:
        import threading

        from drafting_passes import DraftPassEngine
        from ota_server import create_ota_server

        self.server = create_ota_server(
            host="127.0.0.1",
            port=0,
            bundles_root=self.bundles_root,
            preferences_path=self.prefs_path,
            renders_dir=self.renders_dir,
            models_root=self.root,
            default_avatar_path=self.default_avatar,
            render_runner=lambda *_: {"output_mp4": None},
            draft_engine=DraftPassEngine(llm_caller=llm_caller),
            plate_runner=plate_runner,
            quiet=True,
        )
        self._thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self._thread.start()
        return self.server.server_address[1]


class TestClipStudioDraftEndpoints(_RigServerBase):
    def test_arc_full_and_reroll(self) -> None:
        prompts: list[str] = []

        def llm(_sys: str, user: str) -> str:
            prompts.append(user)
            if "Rewrite ONLY" in user:
                return json.dumps({"beat": "A brand new shift line."})
            return json.dumps({"beats": ["Hook line.", "Detail line.", "Shift line.", "Payoff line."]})

        port = self._start(llm_caller=llm)
        status, body = self._post_json(port, "/v1/draft/arc", {"seed_transcript": "sensors", "target_sec": 60})
        self.assertEqual(status, 200, body)
        self.assertEqual(body["word_budget"], 150)
        self.assertEqual([b["label"] for b in body["beats"]], ["HOOK", "DETAIL", "SHIFT", "PAYOFF"])
        self.assertEqual([(b["start_sec"], b["end_sec"]) for b in body["beats"]], [(0, 8), (8, 28), (28, 48), (48, 60)])

        texts = [b["text"] for b in body["beats"]]
        status, body = self._post_json(
            port, "/v1/draft/arc",
            {"seed_transcript": "sensors", "reroll_index": 2, "existing_beats": texts},
        )
        self.assertEqual(status, 200, body)
        self.assertEqual(body["beats"][2]["text"], "A brand new shift line.")
        self.assertEqual(body["beats"][0]["text"], "Hook line.")

        status, _ = self._post_json(port, "/v1/draft/arc", {"seed_transcript": "x", "reroll_index": 1, "existing_beats": ["a"]})
        self.assertEqual(status, 400)

    def test_synthesize_script_returns_three_paragraphed_versions(self) -> None:
        def llm(_sys: str, _user: str) -> str:
            return json.dumps({"versions": [
                {"posture_label": lbl, "paragraphs": [f"{lbl} p{i}." for i in range(4)]}
                for lbl in ("DIRECT", "PUNCHY", "STORY")
            ]})

        port = self._start(llm_caller=llm)
        status, body = self._post_json(
            port, "/v1/draft/synthesize-script",
            {"seed_transcript": "sensors", "arc_beats": ["a", "b", "c", "d"], "target_sec": 60},
        )
        self.assertEqual(status, 200, body)
        self.assertEqual([c["posture_label"] for c in body["candidates"]], ["DIRECT", "PUNCHY", "STORY"])
        self.assertEqual(body["candidates"][1]["text"].count("\n\n"), 3)

    def test_synthesize_script_folds_extra_paragraphs(self) -> None:
        def llm(_sys: str, _user: str) -> str:
            return json.dumps({"versions": [
                {"posture_label": lbl, "paragraphs": [f"{lbl} p{i}." for i in range(6)]}
                for lbl in ("DIRECT", "PUNCHY", "STORY")
            ]})

        port = self._start(llm_caller=llm)
        status, body = self._post_json(
            port, "/v1/draft/synthesize-script",
            {"seed_transcript": "s", "arc_beats": ["a", "b", "c", "d"]},
        )
        self.assertEqual(status, 200, body)
        self.assertTrue(body["candidates"][0]["text"].endswith("DIRECT p3. DIRECT p4. DIRECT p5."))

    def test_brush_swap_nudge_and_more(self) -> None:
        seen: list[str] = []

        def llm(_sys: str, user: str) -> str:
            seen.append(user)
            if "three fresh options" in user:
                return json.dumps({"alternatives": ["old one", "fresh a", "fresh b", "fresh c"]})
            return json.dumps({"alternatives": ["dims it", "dulls it"]})

        port = self._start(llm_caller=llm)
        marked = "The pipeline <replace>crushes the shadows</replace> before saving."
        status, body = self._post_json(port, "/v1/draft/brush-swap", {"marked_text": marked, "nudge": "simpler"})
        self.assertEqual(status, 200, body)
        self.assertEqual(body["alternatives"], ["dims it", "dulls it", ""])
        self.assertIn("shorter, plainer", seen[-1])

        status, body = self._post_json(
            port, "/v1/draft/brush-swap",
            {"marked_text": marked, "nudge": "more", "exclude_alternatives": ["old one"]},
        )
        self.assertEqual(status, 200, body)
        self.assertEqual(body["alternatives"], ["fresh a", "fresh b", "fresh c", ""])

        status, _ = self._post_json(port, "/v1/draft/brush-swap", {"marked_text": marked, "nudge": "louder"})
        self.assertEqual(status, 400)


class TestClipStudioRenderEndpoints(_RigServerBase):
    def test_backdrop_generation_and_plate_download(self) -> None:
        from PIL import Image

        def plate_runner(prompt: str, aspect: str, work: Path) -> None:
            self.assertIn("warm desk", prompt)
            self.assertEqual(aspect, "9:16")
            Image.new("RGB", (90, 160), (20, 20, 22)).save(work / "plate_0.jpg")

        port = self._start(plate_runner=plate_runner)
        status, body = self._post_json(port, "/v1/newsroom/backdrop", {"prompt": "warm desk", "aspect": "9:16"})
        self.assertEqual(status, 200, body)
        self.assertRegex(body["plate_id"], r"^plate_[0-9a-f]{12}$")
        status, raw = self._get(port, body["url"])
        self.assertEqual(status, 200)
        self.assertTrue(raw.startswith(b"\x89PNG"))
        status, _ = self._get(port, "/v1/plates/plate_000000000000.png")
        self.assertEqual(status, 404)
        status, _ = self._post_json(port, "/v1/newsroom/backdrop", {"prompt": "  "})
        self.assertEqual(status, 400)

    def test_async_backdrop_task_is_polled_to_ready_and_failures_surface(self) -> None:
        import time

        from PIL import Image

        def plate_runner(_p: str, _a: str, work: Path) -> None:
            time.sleep(0.3)
            Image.new("RGB", (90, 160), (1, 2, 3)).save(work / "plate_0.png")

        port = self._start(plate_runner=plate_runner)
        status, body = self._post_json(port, "/v1/newsroom/backdrop?async=1", {"prompt": "desk"})
        self.assertEqual(status, 202, body)
        task_id = body["job_id"]
        self.assertTrue(task_id.startswith("task_"))
        deadline = time.time() + 10
        job: dict[str, Any] = {}
        while time.time() < deadline:
            s2, raw = self._get(port, f"/v1/jobs/{task_id}")
            self.assertEqual(s2, 200)
            job = json.loads(raw)
            if job["status"] != "RUNNING":
                break
            time.sleep(0.1)
        self.assertEqual(job["status"], "READY", job)
        self.assertRegex(job["result"]["plate_id"], r"^plate_[0-9a-f]{12}$")

        status, body = self._post_json(port, "/v1/newsroom/backdrop?async=1", {"prompt": ""})
        self.assertEqual(status, 202)
        for _ in range(50):
            job = json.loads(self._get(port, f"/v1/jobs/{body['job_id']}")[1])
            if job["status"] != "RUNNING":
                break
            time.sleep(0.1)
        self.assertEqual(job["status"], "FAILED")
        self.assertEqual(job["error_kind"], "invalid_request")

    @unittest.skipUnless(HAS_FFMPEG, "ffmpeg/ffprobe required")
    def test_silence_gaps_and_chop_with_render_options(self) -> None:
        self.renders_dir.mkdir(parents=True, exist_ok=True)
        _make_test_clip(self.renders_dir / "head_job_1.mp4")
        port = self._start()
        status, body = self._post_json(port, "/v1/core/silence-gaps", {"source_job_id": "head_job_1"})
        self.assertEqual(status, 200, body)
        self.assertEqual(len(body["gaps"]), 1)
        self.assertGreater(body["removable_ms"], 600)

        status, body = self._post_json(port, "/v1/core/chop-broll", {
            "source_job_id": "head_job_1",
            "clauses": [{"id": "a", "spoken_text": "Hello there.", "start_ms": 0, "end_ms": 3000}],
            "overlays": [],
            "render_options": {"strip_silence": True, "captions": True, "aspect": "9:16",
                               "look": {"light": "warm", "brightness_ev": -0.5}},
        })
        self.assertEqual(status, 200, body)
        self.assertEqual(body["removed_silence_gap_count"], 1)
        self.assertEqual(body["caption_count"], 1)
        self.assertLess(body["duration_ms"], 2500)

        status, _ = self._post_json(port, "/v1/core/silence-gaps", {"source_job_id": "nope"})
        self.assertEqual(status, 400)


if __name__ == "__main__":
    unittest.main()
