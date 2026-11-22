import os
import tempfile
import unittest
from pathlib import Path

from prepare_clone_dataset import classify_clip, sha256_file
from train_persona import build_head_archive, require_env


class TrainingJobTests(unittest.TestCase):
    def test_clip_classification_respects_exclusions(self) -> None:
        self.assertEqual(classify_clip(Path("VID_20261025_154029.mp4")), "exclude")
        self.assertEqual(classify_clip(Path("PXL_20261120_232709514.mp4")), "exclude")
        self.assertEqual(classify_clip(Path("lv_0_20261116082307.mp4")), "head_only")
        self.assertEqual(classify_clip(Path("PXL_20261016_215422134.mp4")), "voice_and_head")
        self.assertEqual(classify_clip(Path("PXL_20260925_191922074.mp4")), "voice_and_head")

    def test_head_archive_builds_deterministic_sha256(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp)
            f1 = p / "frame_0001.jpg"
            f1.write_bytes(b"fake-jpeg-bytes-for-hash-test")
            manifest = {
                "artifacts": [
                    {
                        "kind": "head_frame",
                        "source_clip": "lv_0.mp4",
                        "path": str(f1),
                        "sha256": sha256_file(f1),
                        "size_bytes": f1.stat().st_size,
                    }
                ]
            }
            zip_out = p / "head.zip"
            digest = build_head_archive(manifest, zip_out)
            self.assertEqual(len(digest), 64)
            self.assertTrue(zip_out.exists())

    def test_missing_secret_fails_loudly(self) -> None:
        os.environ.pop("AIRCHECK_NONEXISTENT_TEST_SECRET", None)
        with self.assertRaises(RuntimeError):
            require_env("AIRCHECK_NONEXISTENT_TEST_SECRET")


if __name__ == "__main__":
    unittest.main()
