#!/usr/bin/env python3
"""
Phase A · Cloud Persona Trainer (Head LoRA `head_model_id` + Persistent Voice `voice_id`).

Runs once upfront (20-45 min GPU job on Cloud Run Jobs / serverless GPU trainer)
so daily phone creation requires only 1 photo + script.
Zero silent fallbacks: fails loudly if any required provider credential is absent.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import zipfile
from pathlib import Path
from typing import Dict, List, Optional
import urllib.request


def require_env(name: str) -> str:
    val = os.environ.get(name, "").strip()
    if not val:
        raise RuntimeError(f"Required environment secret {name} is not set. Refusing silent fallback.")
    return val


def build_head_archive(manifest: dict, archive_path: Path) -> str:
    frames = [Path(a["path"]) for a in manifest["artifacts"] if a["kind"] == "head_frame"]
    if not frames:
        raise RuntimeError("Manifest contains zero head_frame artifacts.")
    archive_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for frame in frames:
            zf.write(frame, arcname=frame.name)
    digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
    return digest


def enroll_persistent_voice(manifest: dict, voice_provider: str, display_name: str) -> str:
    stems = [Path(a["path"]) for a in manifest["artifacts"] if a["kind"] == "voice_stem"]
    if not stems:
        raise RuntimeError("Manifest contains zero voice_stem artifacts.")

    if voice_provider == "elevenlabs_pvc":
        api_key = require_env("ELEVENLABS_API_KEY")
        # Multipart upload via curl/httpx in Cloud Run container
        import httpx

        files = [("files", (s.name, s.read_bytes(), "audio/wav")) for s in stems]
        resp = httpx.post(
            "https://api.elevenlabs.io/v1/voices/add",
            headers={"xi-api-key": api_key},
            data={"name": display_name, "description": "Aircheck Phase A personal voice clone"},
            files=files,
            timeout=180.0,
        )
        resp.raise_for_status()
        voice_id = resp.json().get("voice_id", "").strip()
        if not voice_id:
            raise RuntimeError("ElevenLabs enrollment returned empty voice_id.")
        return voice_id

    if voice_provider == "fish_s2":
        api_key = require_env("FISH_AUDIO_API_KEY")
        import httpx

        files = [("voices", (s.name, s.read_bytes(), "audio/wav")) for s in stems]
        resp = httpx.post(
            "https://api.fish.audio/model",
            headers={"Authorization": f"Bearer {api_key}"},
            data={"title": display_name, "type": "tts", "train_mode": "fast"},
            files=files,
            timeout=180.0,
        )
        resp.raise_for_status()
        voice_id = resp.json().get("_id", "").strip()
        if not voice_id:
            raise RuntimeError("Fish Audio enrollment returned empty model _id.")
        return voice_id

    raise ValueError(f"Unsupported voice_provider '{voice_provider}'. Use 'elevenlabs_pvc' or 'fish_s2'.")


def launch_head_lora_training(dataset_gcs_uri: str, trigger_word: str) -> str:
    trainer_endpoint = require_env("LORA_TRAINER_ENDPOINT")
    trainer_api_key = require_env("LORA_TRAINER_API_KEY")
    payload = json.dumps(
        {
            "dataset_zip_url": dataset_gcs_uri,
            "trigger_word": trigger_word,
            "steps": 1200,
            "extract_viseme_bank": True,
        }
    ).encode("utf-8")
    req = urllib.request.Request(
        trainer_endpoint,
        data=payload,
        method="POST",
        headers={
            "Authorization": f"Bearer {trainer_api_key}",
            "Content-Type": "application/json",
        },
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    head_model_id = str(data.get("head_model_id") or data.get("model_id") or "").strip()
    if not head_model_id:
        raise RuntimeError("LoRA trainer returned no head_model_id.")
    return head_model_id


def write_persona_to_firestore(
    uid: str,
    head_model_id: str,
    voice_id: str,
    database_id: Optional[str] = None,
) -> None:
    from google.cloud import firestore  # type: ignore

    client = firestore.Client(database=database_id) if database_id else firestore.Client()
    doc_ref = (
        client.collection("users")
        .document(uid)
        .collection("profile")
        .document("persona")
    )
    doc_ref.set(
        {
            "headModelId": head_model_id,
            "voiceId": voice_id,
            "trainingStatus": "READY",
            "trainingError": "",
            "updatedAt": firestore.SERVER_TIMESTAMP,
        },
        merge=True,
    )


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Aircheck Phase A Persona Training Runner")
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--uid", type=str, required=True)
    parser.add_argument("--voice-provider", type=str, default="elevenlabs_pvc")
    parser.add_argument("--display-name", type=str, default="aircheck_host_v1")
    parser.add_argument("--dataset-gcs-uri", type=str, required=True)
    parser.add_argument("--database-id", type=str, default=None)
    args = parser.parse_args(argv)

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    voice_id = enroll_persistent_voice(manifest, args.voice_provider, args.display_name)
    head_model_id = launch_head_lora_training(args.dataset_gcs_uri, trigger_word=args.display_name)
    write_persona_to_firestore(args.uid, head_model_id, voice_id, database_id=args.database_id)
    print(json.dumps({"headModelId": head_model_id, "voiceId": voice_id, "status": "READY"}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
