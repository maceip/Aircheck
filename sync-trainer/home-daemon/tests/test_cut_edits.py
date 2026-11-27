"""Tests for Cut editor edits on the rig: trim/split edit ranges and timed B-roll segments.

* pure helpers: merge/complement ranges, edit_ranges + broll_segments parsing, segment -> clause mapping
* a REAL ffmpeg studio cut honoring a split-out middle, an off-sentence B-roll segment, and silence removal
* /v1/core/chop-broll end to end with edit_ranges + broll_segments (Remotion request falls back to FFmpeg)
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

DAEMON_DIR = Path(__file__).resolve().parents[1]
TESTS_DIR = Path(__file__).resolve().parent
for p in (DAEMON_DIR, TESTS_DIR):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

from studio_cut import (  # noqa: E402
    StudioRenderOptions,
    apply_broll_segments_to_clauses,
    complement_ranges,
    compose_studio_cut,
    merge_ranges,
    parse_broll_segments,
    parse_edit_ranges,
)
from ota_server import _probe_media_duration_ms  # noqa: E402
from test_clip_studio_v4 import (  # noqa: E402
    HAS_FFMPEG,
    _fake_image_runner,
    _make_test_clip,
    _RigServerBase,
)


class CutEditHelperTests(unittest.TestCase):
    def test_merge_and_complement(self) -> None:
        self.assertEqual(merge_ranges([(500, 900), (0, 200), (150, 300), (900, 1000)]), [(0, 300), (500, 1000)])
        self.assertEqual(complement_ranges([(0, 300), (500, 1000)], 1200), [(300, 500), (1000, 1200)])
        self.assertEqual(complement_ranges([(0, 1200)], 1200), [])

    def test_parse_edit_ranges(self) -> None:
        self.assertIsNone(parse_edit_ranges(None, 3000))
        self.assertIsNone(parse_edit_ranges([], 3000))
        # One clip covering the whole source is no edit.
        self.assertIsNone(parse_edit_ranges([{"start_ms": 0, "end_ms": 3000}], 3000))
        self.assertEqual(
            parse_edit_ranges([{"start_ms": 2000, "end_ms": 9999}, [0, 1000]], 3000),
            [(0, 1000), (2000, 3000)],
        )
        with self.assertRaises(ValueError):
            parse_edit_ranges([{"start_ms": 10, "end_ms": 20}], 3000)
        with self.assertRaises(ValueError):
            parse_edit_ranges("0-1000", 3000)

    def test_parse_broll_segments(self) -> None:
        self.assertIsNone(parse_broll_segments(None))
        items = parse_broll_segments([
            {"id": "s2", "start_ms": 2100, "duration_ms": 600, "b_roll_prompt": "clock", "broll_framing": "full"},
            {"id": "tiny", "start_ms": 0, "duration_ms": 50},
            {"id": "s1", "start_ms": 100, "duration_ms": 400, "broll_media_id": "bmedia_0123456789ab.jpg"},
        ])
        self.assertEqual([i["id"] for i in items], ["s1", "s2"])
        self.assertEqual((items[1]["start_ms"], items[1]["end_ms"]), (2100, 2700))
        self.assertEqual(items[1]["segment_mode"], "BROLL_CUTAWAY")
        self.assertEqual(items[1]["broll_framing"], "full")
        self.assertEqual(items[0]["broll_media_id"], "bmedia_0123456789ab.jpg")

    def test_segments_to_clauses_alignment(self) -> None:
        clauses = [
            {"id": "a", "start_ms": 0, "end_ms": 1000, "segment_mode": "BROLL_CUTAWAY"},
            {"id": "b", "start_ms": 1000, "end_ms": 2000},
        ]
        aligned_segs = parse_broll_segments([{"id": "s", "start_ms": 1000, "duration_ms": 1000, "b_roll_prompt": "x"}])
        out, aligned = apply_broll_segments_to_clauses(clauses, aligned_segs)
        self.assertTrue(aligned)
        self.assertEqual([c["segment_mode"] for c in out], ["TALKING_HEAD", "BROLL_CUTAWAY"])
        self.assertEqual(out[1]["b_roll_prompt"], "x")
        off = parse_broll_segments([{"id": "s", "start_ms": 1200, "duration_ms": 500}])
        _, aligned = apply_broll_segments_to_clauses(clauses, off)
        self.assertFalse(aligned)


@unittest.skipUnless(HAS_FFMPEG, "ffmpeg/ffprobe required")
class CutEditCompositionTests(unittest.TestCase):
    def test_split_out_middle_and_offsentence_broll(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "head.mp4"
            _make_test_clip(src)
            plates = root / "plates"
            plates.mkdir()
            out = root / "out.mp4"
            clauses = [
                {"id": "c0", "spoken_text": "First line here.", "start_ms": 0, "end_ms": 1000,
                 "segment_mode": "BROLL_CUTAWAY"},
                {"id": "c1", "spoken_text": "Pause.", "start_ms": 1000, "end_ms": 2000},
                {"id": "c2", "spoken_text": "Second line here.", "start_ms": 2000, "end_ms": 3000},
            ]
            stats = compose_studio_cut(
                src_mp4=src,
                out_mp4=out,
                work_dir=root,
                clauses=clauses,
                overlays=[],
                options=StudioRenderOptions.from_payload({"captions": True, "aspect": "9:16"}),
                plates_dir=plates,
                probe_duration_ms=_probe_media_duration_ms,
                image_runner=_fake_image_runner,
                # Split at 1.0s and 2.0s, then trim the middle clip away.
                edit_ranges=[(0, 1000), (2000, 3000)],
                broll_segments=parse_broll_segments(
                    [{"id": "seg", "start_ms": 2200, "duration_ms": 500, "b_roll_prompt": "wall clock"}]
                ),
            )
            self.assertTrue(out.is_file())
            self.assertAlmostEqual(_probe_media_duration_ms(out), 2000, delta=250)
            self.assertEqual(stats["edit_range_count"], 2)
            self.assertAlmostEqual(stats["removed_edit_ms"], 1000, delta=1)
            # The sentence tag on c0 is ignored: only the timed segment is a cutaway.
            self.assertEqual(stats["broll_cut_count"], 1)
            seg = stats["broll_segments"][0]
            self.assertAlmostEqual(seg["start_ms"], 1200, delta=5)
            self.assertAlmostEqual(seg["end_ms"], 1700, delta=5)
            by_id = {c["id"]: c for c in stats["clauses"]}
            self.assertEqual((by_id["c1"]["start_ms"], by_id["c1"]["end_ms"]), (0, 0))
            self.assertAlmostEqual(by_id["c2"]["start_ms"], 1000, delta=5)
            # Captions for both kept talking-head lines, none for the cut-out middle.
            self.assertEqual(stats["caption_count"], 2)

    def test_trim_combines_with_silence_removal(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "head.mp4"
            _make_test_clip(src)
            out = root / "out.mp4"
            stats = compose_studio_cut(
                src_mp4=src,
                out_mp4=out,
                work_dir=root,
                clauses=[{"id": "a", "spoken_text": "Hello.", "start_ms": 0, "end_ms": 3000}],
                overlays=[],
                options=StudioRenderOptions.from_payload({"strip_silence": True, "aspect": "9:16"}),
                plates_dir=root,
                probe_duration_ms=_probe_media_duration_ms,
                image_runner=_fake_image_runner,
                edit_ranges=[(0, 2600)],
                broll_segments=[],
            )
            # 3.0s - 0.4s trimmed tail - ~0.8s silence.
            self.assertLess(_probe_media_duration_ms(out), 2000)
            self.assertEqual(stats["removed_silence_gap_count"], 1)
            self.assertEqual(stats["broll_cut_count"], 0)


@unittest.skipUnless(HAS_FFMPEG, "ffmpeg/ffprobe required")
class CutEditEndpointTests(_RigServerBase):
    def test_chop_honors_edit_ranges_and_segments(self) -> None:
        self.renders_dir.mkdir(parents=True, exist_ok=True)
        _make_test_clip(self.renders_dir / "head_job_2.mp4")
        port = self._start(plate_runner=_fake_image_runner)
        status, body = self._post_json(port, "/v1/core/chop-broll", {
            "source_job_id": "head_job_2",
            "clauses": [
                {"id": "a", "spoken_text": "Hello there.", "start_ms": 0, "end_ms": 1500},
                {"id": "b", "spoken_text": "Second.", "start_ms": 1500, "end_ms": 3000},
            ],
            "overlays": [],
            "edit_ranges": [{"start_ms": 0, "end_ms": 1000}, {"start_ms": 2000, "end_ms": 3000}],
            "broll_segments": [],
            "render_options": {"aspect": "9:16", "use_remotion": True},
        })
        self.assertEqual(status, 200, body)
        self.assertFalse(body["remotion_used"])
        self.assertEqual(body["renderer"], "ffmpeg")
        self.assertEqual(body["edit_range_count"], 2)
        self.assertAlmostEqual(body["duration_ms"], 2000, delta=250)

        status, body = self._post_json(port, "/v1/core/chop-broll", {
            "source_job_id": "head_job_2",
            "clauses": [{"id": "a", "spoken_text": "Hello there.", "start_ms": 0, "end_ms": 3000}],
            "overlays": [],
            "edit_ranges": [{"start_ms": 0, "end_ms": 50}],
            "render_options": {"aspect": "9:16"},
        })
        self.assertEqual(status, 400)

if __name__ == "__main__":
    unittest.main()
