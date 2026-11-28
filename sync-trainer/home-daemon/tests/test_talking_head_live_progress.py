"""Tests for live talking-head progress streaming (generate_talking_head.py + ota_server.py)."""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

HOME_DAEMON_DIR = Path(__file__).resolve().parents[1]
if str(HOME_DAEMON_DIR) not in sys.path:
    sys.path.insert(0, str(HOME_DAEMON_DIR))

import generate_talking_head as gth
import ota_server


class TalkingHeadLiveProgressTests(unittest.TestCase):
    def test_heygen_invoke_emits_real_substage_milestones(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            wav = root / "voice.wav"
            wav.write_bytes(b"RIFF")
            avatar = root / "avatar.jpg"
            avatar.write_bytes(b"\xff\xd8")
            out_mp4 = root / "out.mp4"
            prog = root / "head_progress.json"
            fake_bin = root / "heygen"
            fake_bin.write_text("#!/bin/sh\n", encoding="utf-8")

            observed: list[dict] = []
            real_write = gth._write_progress_file

            def _capture_write(p: Path | None, **kwargs: object) -> None:
                real_write(p, **kwargs)
                if p is not None and p.is_file():
                    observed.append(json.loads(p.read_text(encoding="utf-8")))

            polls = iter(
                [
                    {"data": {"status": "pending"}},
                    {"data": {"status": "processing"}},
                    {"data": {"status": "processing", "progress": 50}},
                    {"data": {"status": "completed"}},
                ]
            )

            def _fake_heygen_json(args: list[str], timeout: int = 120) -> dict:
                if args[:2] == ["asset", "create"]:
                    return {"data": {"asset_id": "asset_audio_1"}}
                if args[:2] == ["video", "create"]:
                    return {"data": {"video_id": "vid_123"}}
                if args[:2] == ["video", "get"]:
                    return next(polls)
                raise AssertionError(f"unexpected heygen args: {args}")

            def _fake_download(cmd: list[str], **_kwargs: object) -> object:
                out_mp4.write_bytes(b"mp4-bytes")
                return type("Proc", (), {"returncode": 0, "stdout": "", "stderr": ""})()

            with (
                patch.object(gth, "HEYGEN_BIN", fake_bin),
                patch.object(gth, "HEYGEN_POLL_SEC", 0.0),
                patch.object(gth, "_heygen_json", side_effect=_fake_heygen_json),
                patch.object(gth, "_write_progress_file", side_effect=_capture_write),
                patch.object(gth.subprocess, "run", side_effect=_fake_download),
                patch.object(gth.time, "sleep", return_value=None),
            ):
                meta = gth._invoke_heygen_avatar(
                    master_wav=wav,
                    avatar_path=avatar,
                    use_custom_photo=False,
                    output_mp4=out_mp4,
                    ratio="9:16",
                    resolution="1080P",
                    timeout_sec=60,
                    heygen_engine="heygen-avatar-6",
                    heygen_resolution="2k",
                    progress_file=prog,
                )

            self.assertEqual(meta["heygen_video_id"], "vid_123")
            details = [item["stage_detail"] for item in observed]
            fracs = [item["progress_fraction"] for item in observed]
            self.assertEqual(details[0], "Uploading voice to HeyGen (2k)")
            self.assertEqual(details[1], "Submitting HeyGen 2k render")
            self.assertIn("HeyGen 2k queued", details)
            self.assertIn("HeyGen 2k rendering", details)
            self.assertEqual(details[-1], "Downloading HeyGen 2k video")
            # Every transition is monotonically non-decreasing and stays within real milestones.
            self.assertEqual(fracs, sorted(fracs))
            self.assertAlmostEqual(fracs[-1], 0.85, places=3)
            # When HeyGen reports real progress=50, fraction maps cleanly to 0.78 (0.72 + 0.5 * 0.12).
            self.assertIn(0.78, fracs)

    def test_ota_popen_watcher_streams_real_milestones_and_elapsed_clock(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            prog = root / "head_progress.json"
            worker = root / "fake_head.py"
            worker.write_text(
                "\n".join(
                    [
                        "import json, sys, time",
                        "from pathlib import Path",
                        "p = Path(sys.argv[1])",
                        "p.write_text(json.dumps({'progress_fraction': 0.68, 'stage_detail': 'HeyGen 2k queued'}))",
                        "time.sleep(0.35)",
                        "p.write_text(json.dumps({'progress_fraction': 0.72, 'stage_detail': 'HeyGen 2k rendering'}))",
                        "time.sleep(0.85)",
                        "p.write_text(json.dumps({'progress_fraction': 0.85, 'stage_detail': 'Downloading HeyGen 2k video'}))",
                        "time.sleep(0.25)",
                        "print(json.dumps({'engine': 'heygen:heygen-avatar-6'}))",
                    ]
                ),
                encoding="utf-8",
            )
            updates: list[tuple[float, str]] = []

            def _report(*, status: str, progress_fraction: float, stage_detail: str, metrics: object) -> None:
                self.assertEqual(status, "LIPSYNC")
                updates.append((progress_fraction, stage_detail))

            proc = ota_server._run_talking_head_with_progress(
                [sys.executable, str(worker), str(prog)],
                progress_file=prog,
                timeout_sec=15,
                initial_fraction=0.60,
                initial_detail="Starting HeyGen 2k render",
                report_progress=_report,
                metrics={"voice_engine": "mic"},
                poll_interval_sec=0.15,
            )

            self.assertEqual(proc.returncode, 0)
            self.assertEqual(json.loads(proc.stdout)["engine"], "heygen:heygen-avatar-6")
            self.assertGreaterEqual(len(updates), 3)
            all_details = [d for _, d in updates]
            all_fracs = [f for f, _ in updates]
            self.assertTrue(any("HeyGen 2k queued · " in d for d in all_details), all_details)
            self.assertTrue(any("HeyGen 2k rendering · " in d for d in all_details), all_details)
            self.assertTrue(any("· 0:01" in d for d in all_details), all_details)
            self.assertEqual(all_fracs, sorted(all_fracs))
            self.assertGreaterEqual(max(all_fracs), 0.72)


if __name__ == "__main__":
    unittest.main()
