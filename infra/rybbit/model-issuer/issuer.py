#!/usr/bin/env python3
"""Rate-limited presigned-URL issuer for Aircheck on-device model weights in DO Spaces.

The Spaces secret lives only here (server-side). The app requests a short-lived presigned GET URL
and downloads directly from Spaces, so the object is not publicly scrapeable and the secret never
ships in the APK. Abuse protection: per-IP request cap + 1-hour URL expiry + private bucket.

GET /models/manifest?model=<id>  -> {id, file_name, size, sha256}
GET /models/url?model=<id>       -> {url, expires_in, size, sha256}
GET /models/health               -> {ok: true}
"""
import json
import os
import time
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

import boto3

ENDPOINT = os.environ.get("SPACES_ENDPOINT", "https://nyc3.digitaloceanspaces.com")
BUCKET = os.environ.get("SPACES_BUCKET", "aircheck-models")
REGION = os.environ.get("SPACES_REGION", "nyc3")
KEY = os.environ["SPACES_ACCESS_KEY"]
SECRET = os.environ["SPACES_SECRET_KEY"]
MANIFEST_PATH = os.environ.get("MANIFEST_PATH", "/app/manifest.json")
URL_TTL = int(os.environ.get("URL_TTL_SECONDS", "3600"))
RATE_LIMIT = int(os.environ.get("RATE_LIMIT_PER_HOUR", "30"))

with open(MANIFEST_PATH) as fh:
    MANIFEST = json.load(fh)

_s3 = boto3.client(
    "s3",
    endpoint_url=ENDPOINT,
    region_name=REGION,
    aws_access_key_id=KEY,
    aws_secret_access_key=SECRET,
)

_hits = {}
_lock = threading.Lock()


def _allowed(ip: str) -> bool:
    now = time.time()
    with _lock:
        window = [t for t in _hits.get(ip, []) if now - t < 3600]
        if len(window) >= RATE_LIMIT:
            _hits[ip] = window
            return False
        window.append(now)
        _hits[ip] = window
        return True


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        parsed = urlparse(self.path)
        qs = parse_qs(parsed.query)
        if parsed.path == "/models/health":
            self._send(200, {"ok": True})
            return
        ip = self.headers.get("X-Forwarded-For", self.client_address[0]).split(",")[0].strip()
        if not _allowed(ip):
            self._send(429, {"error": "rate_limited", "detail": "Too many requests; try later."})
            return
        model = (qs.get("model", [""])[0]).strip()
        entry = MANIFEST.get(model)
        if not entry:
            self._send(404, {"error": "unknown_model"})
            return
        if parsed.path == "/models/manifest":
            self._send(200, {"id": model, **entry})
            return
        if parsed.path == "/models/url":
            url = _s3.generate_presigned_url(
                "get_object",
                Params={"Bucket": BUCKET, "Key": entry["file_name"]},
                ExpiresIn=URL_TTL,
            )
            self._send(200, {"url": url, "expires_in": URL_TTL, **entry})
            return
        self._send(404, {"error": "not_found"})

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8090"))
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
