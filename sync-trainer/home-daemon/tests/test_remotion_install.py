from __future__ import annotations

import json
import threading
import urllib.request
from pathlib import Path

from ota_server import create_ota_server


def test_remotion_unused_by_default_and_user_install_request_persists(tmp_path: Path) -> None:
    bundles_dir = tmp_path / "bundles"
    bundles_dir.mkdir(parents=True)
    prefs_file = tmp_path / "preferences.jsonl"
    renders_dir = tmp_path / "renders"
    renders_dir.mkdir(parents=True)

    srv = create_ota_server(
        host="127.0.0.1",
        port=0,
        bundles_root=bundles_dir,
        preferences_path=prefs_file,
        renders_dir=renders_dir,
        quiet=True,
    )
    thread = threading.Thread(target=srv.serve_forever, daemon=True)
    thread.start()
    host, port = srv.server_address[:2]
    base_url = f"http://{host}:{port}"

    try:
        with urllib.request.urlopen(f"{base_url}/health", timeout=5) as resp:
            health_before = json.loads(resp.read().decode("utf-8"))
        assert health_before["remotion_worker_present"] is True
        assert health_before["remotion_enabled"] is False
        assert health_before["remotion_renders_completed"] == 0

        payload = json.dumps(
            {
                "user_request": "Please install Remotion for live news pop-ups and B-roll",
                "enable_broll_popups": True,
            }
        ).encode("utf-8")
        req = urllib.request.Request(
            f"{base_url}/v1/remotion/install",
            data=payload,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=15) as resp:
            install_res = json.loads(resp.read().decode("utf-8"))

        assert install_res["status"] == "installed"
        assert install_res["remotion_enabled"] is True
        assert install_res["package_provider_id"] == "mac_rig_remotion"
        assert install_res["user_request"] == "Please install Remotion for live news pop-ups and B-roll"

        with urllib.request.urlopen(f"{base_url}/health", timeout=5) as resp:
            health_after = json.loads(resp.read().decode("utf-8"))
        assert health_after["remotion_enabled"] is True
    finally:
        srv.shutdown()
        srv.server_close()
