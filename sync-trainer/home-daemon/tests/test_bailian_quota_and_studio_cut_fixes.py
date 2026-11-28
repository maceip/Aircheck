"""Regression tests for Bailian 429 quota cooldown and FFmpeg studio_cut fixes."""

from __future__ import annotations

import datetime as dt
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

DAEMON_DIR = Path(__file__).resolve().parents[1]
if str(DAEMON_DIR) not in sys.path:
    sys.path.insert(0, str(DAEMON_DIR))

from bailian_quota import (  # noqa: E402
    active_bailian_quota_block,
    clear_bailian_quota_state,
    parse_bailian_quota_error,
    record_bailian_quota_exhausted,
)
from llm_router import LlmProviderError, LlmRouter  # noqa: E402
from studio_cut import (  # noqa: E402
    StudioRenderOptions,
    _generate_image_into,
    broll_media_dir,
    compose_studio_cut,
    resolve_broll_media_ref,
)


def _make_clip(path: Path, dur_s: float = 4.0) -> None:
    subprocess.run(
        [
            "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
            "-f", "lavfi", "-i", f"color=c=navy:s=720x1280:r=25:d={dur_s:.2f}",
            "-f", "lavfi", "-i", f"sine=frequency=220:sample_rate=44100:duration={dur_s:.2f}",
            "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-ar", "44100", "-b:a", "128k",
            str(path),
        ],
        check=True,
        timeout=30,
    )


def _probe_ms(path: Path) -> int:
    proc = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", str(path)],
        capture_output=True, text=True, check=True, timeout=15,
    )
    return int(round(float(proc.stdout.strip()) * 1000.0))


class BailianQuotaAndStudioCutFixesTest(unittest.TestCase):
    def test_bailian_429_parser_and_router_availability(self) -> None:
        raw_err = (
            'bl text chat failed (rc=1): {"error":{"code":1,"message":"Your token-plan 1-month quota has been exhausted. '
            'The quota will reset at 12-21 16:00:00 UTC.","http_status":429,"api_code":"insufficient_quota"}}'
        )
        now = dt.datetime(2026, 11, 28, 8, 0, 0, tzinfo=dt.timezone.utc).timestamp()
        is_q, until_ts, label = parse_bailian_quota_error(raw_err, now_ts=now)
        self.assertTrue(is_q)
        self.assertEqual(label, "2026-12-21 16:00:00 UTC")
        self.assertGreater(until_ts or 0.0, now)

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fake_bl = root / "bl"
            fake_bl.write_text("#!/bin/sh\necho '{\"error\":{\"http_status\":429,\"api_code\":\"insufficient_quota\",\"message\":\"Your token-plan 1-month quota has been exhausted. The quota will reset at 12-21 16:00:00 UTC.\"}}' >&2\nexit 1\n")
            fake_bl.chmod(0o755)
            fake_cfg = root / "config.json"
            fake_cfg.write_text('{"api_key":"dummy"}')
            q_state = root / "aircheck_quota_state.json"

            router = LlmRouter(
                bl_bin=fake_bl,
                bailian_config_path=fake_cfg,
                bailian_quota_state_path=q_state,
            )
            before = {p["id"]: p for p in router.list_providers()["providers"]}
            self.assertTrue(before["remote_bailian"]["available"])

            with self.assertRaises(LlmProviderError):
                router.complete(
                    stage="draft.arc",
                    system_prompt="sys",
                    user_prompt="usr",
                    llm_provider="remote_bailian",
                    llm_model="qwen3.8-flash",
                )

            after = {p["id"]: p for p in router.list_providers()["providers"]}
            self.assertFalse(after["remote_bailian"]["available"])
            self.assertIn("429", after["remote_bailian"]["detail"])
            self.assertIn("12-21 16:00:00 UTC", after["remote_bailian"]["detail"])

            # Expiry clears the block automatically
            self.assertIsNone(active_bailian_quota_block(state_path=q_state, now_ts=(until_ts or now) + 5.0))
            clear_bailian_quota_state(state_path=q_state)
            self.assertIsNone(active_bailian_quota_block(state_path=q_state))

    def test_generate_image_into_short_circuits_bl_during_429_cooldown(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            q_state = root / "quota.json"
            record_bailian_quota_exhausted(
                '{"http_status":429,"api_code":"Throttling.AllocationQuota","message":"The quota will reset at 12-21 16:00:00 UTC."}',
                state_path=q_state,
            )
            work = root / "work"
            work.mkdir()
            fake_bl = root / "bl"
            fake_bl.write_text("#!/bin/sh\nexit 99\n")
            fake_bl.chmod(0o755)

            with mock.patch.dict(os.environ, {"OPENAI_API_KEY": "sk-test-key"}), \
                 mock.patch("studio_cut.subprocess.run") as mock_subproc, \
                 mock.patch("studio_cut._generate_backdrop_via_openai") as mock_openai:
                _generate_image_into(
                    "studio desk",
                    "9:16",
                    work,
                    bl_bin=fake_bl,
                    quota_state_path=q_state,
                )
                mock_subproc.assert_not_called()
                mock_openai.assert_called_once()

    def test_compose_studio_cut_resolves_broll_url_and_renders_word_level_captions(self) -> None:
        from PIL import Image

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "head.mp4"
            _make_clip(src, dur_s=4.0)
            plates = root / "plates"
            plates.mkdir()
            broll_file = broll_media_dir(plates) / "broll_9b1d6e1eb7d70a13.png"
            Image.new("RGB", (720, 1280), (20, 40, 80)).save(broll_file)

            self.assertEqual(
                resolve_broll_media_ref(plates, "/v1/broll-media/broll_9b1d6e1eb7d70a13.png"),
                broll_file.resolve(),
            )
            self.assertIsNone(resolve_broll_media_ref(plates, "/v1/broll-media/../../etc/passwd"))

            called_gen = {"count": 0}

            def _should_not_generate(*_args: object, **_kwargs: object) -> None:
                called_gen["count"] += 1
                raise AssertionError("image_runner must not be called when broll_url resolves to local media")

            words = [
                {"word": "What", "start_ms": 0, "end_ms": 250},
                {"word": "I", "start_ms": 250, "end_ms": 400},
                {"word": "didn't", "start_ms": 400, "end_ms": 700},
                {"word": "do:", "start_ms": 700, "end_ms": 950},
                {"word": "introduce", "start_ms": 950, "end_ms": 1350},
                {"word": "custom", "start_ms": 1350, "end_ms": 1700},
                {"word": "brand", "start_ms": 1700, "end_ms": 2000},
                {"word": "adaptation", "start_ms": 2000, "end_ms": 2500},
                {"word": "keeps", "start_ms": 2500, "end_ms": 2900},
                {"word": "ownership", "start_ms": 2900, "end_ms": 3400},
                {"word": "clear.", "start_ms": 3400, "end_ms": 3900},
            ]
            out = root / "out_words.mp4"
            stats = compose_studio_cut(
                src_mp4=src,
                out_mp4=out,
                work_dir=root / "work_words",
                clauses=[
                    {"id": "c0", "spoken_text": "What I didn't do: introduce custom", "start_ms": 0, "end_ms": 1700},
                    {
                        "id": "c1",
                        "spoken_text": "brand adaptation keeps ownership clear.",
                        "start_ms": 1700,
                        "end_ms": 3900,
                        "segment_mode": "BROLL_CUTAWAY",
                        "broll_url": "/v1/broll-media/broll_9b1d6e1eb7d70a13.png",
                    },
                ],
                overlays=[{
                    "kind": "LOWER_THIRD",
                    "headline": "BRAND-CONSISTENT ADAPTATION",
                    "subline": "Who holds the work, what was recorded, and why it changed",
                    "duration_sec": 4,
                }],
                options=StudioRenderOptions.from_payload({"captions": True, "aspect": "9:16"}),
                plates_dir=plates,
                probe_duration_ms=_probe_ms,
                image_runner=_should_not_generate,
                words=words,
            )
            self.assertEqual(called_gen["count"], 0)
            self.assertTrue(out.is_file())
            self.assertEqual(stats["broll_cut_count"], 1)
            # Word-level captions span both talking head and B-roll cutaway without dropping words.
            self.assertGreaterEqual(stats["caption_count"], 2)

            # Without words, a long single clause paginates into >= 2 pages instead of slicing [-3:].
            out_paged = root / "out_paged.mp4"
            stats_paged = compose_studio_cut(
                src_mp4=src,
                out_mp4=out_paged,
                work_dir=root / "work_paged",
                clauses=[
                    {
                        "id": "c0",
                        "spoken_text": (
                            "What I didn't do: introduce a custom brand palette, new typography, "
                            "an ownable motion language, or bespoke illustrations for the deck."
                        ),
                        "start_ms": 0,
                        "end_ms": 3900,
                    },
                ],
                overlays=[],
                options=StudioRenderOptions.from_payload({"captions": True, "aspect": "9:16"}),
                plates_dir=plates,
                probe_duration_ms=_probe_ms,
            )
            self.assertTrue(out_paged.is_file())
            self.assertGreaterEqual(stats_paged["caption_count"], 2)

    def test_fonts_loads_real_scaled_truetype_fonts(self) -> None:
        from studio_cut import _fonts

        f_title, f_body, f_tag = _fonts(1.5)
        self.assertGreaterEqual(getattr(f_title, "size", 0), 48)
        self.assertGreaterEqual(getattr(f_body, "size", 0), 32)
        self.assertGreaterEqual(getattr(f_tag, "size", 0), 26)


if __name__ == "__main__":
    unittest.main()
