"""Pulse (is this idea hot in my areas right now?) and Name-drop (who can I tag?) for the :8443 rig.

Stdlib only. Every number comes from a source that answered; a source that failed or was not
configured is reported with ``ok: false`` and a reason, never a guess.

Sources (approved by Ryan 2026-11-28): GDELT DOC 2.0, Hacker News (Algolia), Wikimedia pageviews,
Bluesky (public AppView; search and DMs need an app password), arXiv, OpenAlex, ORCID public API.
The paid X API is not used.
"""

from __future__ import annotations

import concurrent.futures
import datetime as _dt
import http.client
import ipaddress
import json
import os
import re
import socket
import ssl
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any, Callable

USER_AGENT = "Aircheck-rig/1.0 (+https://github.com/maceip/Aircheck)"
HTTP_TIMEOUT_SEC = 8.0
CACHE_TTL_SEC = 15 * 60
MAX_TEXT_CHARS = 2000
KEY_AUTHOR_LOOKUPS = 5
MAX_CITED_LINKS = 3
FIRST_LOOK_WORKERS = 6
FIRST_LOOK_DEADLINE_SEC = 60.0

GDELT_DOC_URL = "https://api.gdeltproject.org/api/v2/doc/doc"
HN_SEARCH_URL = "https://hn.algolia.com/api/v1/search_by_date"
WIKI_OPENSEARCH_URL = "https://en.wikipedia.org/w/api.php"
WIKI_PAGEVIEWS_URL = "https://wikimedia.org/api/rest_v1/metrics/pageviews/per-article/en.wikipedia/all-access/user"
BSKY_PUBLIC_URL = "https://public.api.bsky.app/xrpc"
BSKY_PDS_URL = "https://bsky.social/xrpc"
PLC_DIRECTORY_URL = "https://plc.directory"
BSKY_CHAT_PROXY = "did:web:api.bsky.chat#bsky_chat"
ARXIV_API_URL = "https://export.arxiv.org/api/query"
OPENALEX_URL = "https://api.openalex.org"
ORCID_URL = "https://pub.orcid.org/v3.0"

PLATFORMS = ("x", "bsky", "email")
_ATOM = "{http://www.w3.org/2005/Atom}"


class FetchError(Exception):
    """A source did not answer usefully. The message is shown to the user as the reason."""


Fetcher = Callable[..., Any]


def http_fetch(
    url: str,
    *,
    method: str = "GET",
    headers: dict[str, str] | None = None,
    body: dict[str, Any] | None = None,
    want: str = "json",
    timeout: float = HTTP_TIMEOUT_SEC,
) -> Any:
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("User-Agent", USER_AGENT)
    req.add_header("Accept", "application/json" if want == "json" else "*/*")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read(4 * 1024 * 1024)
    except urllib.error.HTTPError as exc:
        raise FetchError(f"HTTP {exc.code}") from exc
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise FetchError(f"unreachable ({exc.__class__.__name__})") from exc
    text = raw.decode("utf-8", errors="replace")
    if want == "text":
        return text
    try:
        return json.loads(text)
    except json.JSONDecodeError as exc:
        raise FetchError("not JSON") from exc


class _TtlCache:
    def __init__(self, ttl: float = CACHE_TTL_SEC) -> None:
        self._ttl = ttl
        self._lock = threading.Lock()
        self._items: dict[str, tuple[float, Any]] = {}

    def get_or(self, key: str, fn: Callable[[], Any]) -> Any:
        now = time.monotonic()
        with self._lock:
            hit = self._items.get(key)
            if hit and now - hit[0] < self._ttl:
                return hit[1]
        value = fn()
        with self._lock:
            self._items[key] = (now, value)
        return value


def _json_store_read(path: Path, default: Any) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError):
        return default


def _json_store_write(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    os.replace(tmp, path)


def _utc_now() -> _dt.datetime:
    return _dt.datetime.now(_dt.timezone.utc)


def _iso(ts: _dt.datetime) -> str:
    return ts.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _extract_json_object(text: str) -> dict[str, Any] | None:
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        value = json.loads(text[start : end + 1])
    except json.JSONDecodeError:
        return None
    return value if isinstance(value, dict) else None


_URL_RE = re.compile(r"https?://[^\s<>\"')]+")

_STOPWORDS = frozenset(
    "a an and are as at be but by for from got has have he her his i if in is it its me my of on or "
    "our she so that the their them they this to was we what when who will with you your just very "
    "really about".split()
)


def naive_query(text: str) -> str:
    # Links aren't topic words: "https example com" would be measured as the idea's heat.
    text = _URL_RE.sub(" ", text)
    words = [w for w in re.findall(r"[A-Za-z0-9][A-Za-z0-9'\-]+", text.lower()) if w not in _STOPWORDS]
    return " ".join(words[:5])


# --------------------------------------------------------------------------------------------
# Heat sources. Each returns {"name", "ok", "count_24h", "avg_7d", "detail", ...}.
# --------------------------------------------------------------------------------------------


def _source_result(name: str, *, ok: bool, count_24h: float | None = None, avg_7d: float | None = None,
                   detail: str = "", **extra: Any) -> dict[str, Any]:
    out = {"name": name, "ok": ok, "count_24h": count_24h, "avg_7d": avg_7d, "detail": detail}
    out.update(extra)
    return out


def heat_gdelt(query: str, fetch: Fetcher, now: _dt.datetime) -> dict[str, Any]:
    url = GDELT_DOC_URL + "?" + urllib.parse.urlencode(
        {"query": query, "mode": "timelinevolraw", "timespan": "8d", "format": "json"}
    )
    try:
        data = fetch(url)
        series = data["timeline"][0]["data"]
    except FetchError as exc:
        return _source_result("news", ok=False, detail=f"GDELT {exc}")
    except (KeyError, IndexError, TypeError):
        return _source_result("news", ok=False, detail="GDELT returned no timeline")
    if not isinstance(series, list):
        return _source_result("news", ok=False, detail="GDELT returned no timeline")
    cutoff = now - _dt.timedelta(hours=24)
    start = now - _dt.timedelta(days=8)
    last, prior, points = 0.0, 0.0, 0
    for point in series:
        try:
            ts = _dt.datetime.strptime(str(point["date"]), "%Y%m%dT%H%M%SZ").replace(tzinfo=_dt.timezone.utc)
            value = float(point["value"])
        except (KeyError, ValueError, TypeError):
            continue
        points += 1
        if ts > cutoff:
            last += value
        elif ts > start:
            prior += value
    if not points:
        # A reply with no readable points is an upstream error, not "no coverage".
        return _source_result("news", ok=False, detail="GDELT returned no readable timeline points")
    return _source_result("news", ok=True, count_24h=last, avg_7d=round(prior / 7.0, 2),
                          detail="GDELT articles, last 24h vs daily average of the 7 days before")


def heat_hn(query: str, fetch: Fetcher, now: _dt.datetime) -> dict[str, Any]:
    def count_since(seconds: int, until: int | None = None) -> int:
        filt = f"created_at_i>{int(now.timestamp()) - seconds}"
        if until is not None:
            filt += f",created_at_i<={int(now.timestamp()) - until}"
        url = HN_SEARCH_URL + "?" + urllib.parse.urlencode(
            {"query": query, "tags": "(story,comment)", "numericFilters": filt, "hitsPerPage": 0}
        )
        return int(fetch(url)["nbHits"])

    try:
        last = count_since(86400)
        prior = count_since(8 * 86400, until=86400)
    except FetchError as exc:
        return _source_result("hn", ok=False, detail=f"Hacker News {exc}")
    except (KeyError, TypeError, ValueError):
        return _source_result("hn", ok=False, detail="Hacker News returned no count")
    return _source_result("hn", ok=True, count_24h=last, avg_7d=round(prior / 7.0, 2),
                          detail="Hacker News stories + comments")


def heat_wiki(term: str, fetch: Fetcher, now: _dt.datetime) -> dict[str, Any]:
    try:
        found = fetch(WIKI_OPENSEARCH_URL + "?" + urllib.parse.urlencode(
            {"action": "opensearch", "search": term, "limit": 1, "namespace": 0, "format": "json"}))
        titles = found[1] if isinstance(found, list) and len(found) > 1 else []
        if not titles:
            return _source_result("wiki", ok=False, detail=f"no Wikipedia article for '{term}'")
        title = str(titles[0])
        end = (now - _dt.timedelta(days=1)).strftime("%Y%m%d")
        start = (now - _dt.timedelta(days=8)).strftime("%Y%m%d")
        data = fetch(f"{WIKI_PAGEVIEWS_URL}/{urllib.parse.quote(title.replace(' ', '_'), safe='')}/daily/{start}/{end}")
        views = [int(item["views"]) for item in data["items"]]
    except FetchError as exc:
        return _source_result("wiki", ok=False, detail=f"Wikipedia {exc}")
    except (KeyError, TypeError, ValueError, IndexError):
        return _source_result("wiki", ok=False, detail="Wikipedia returned no pageviews")
    if len(views) < 2:
        return _source_result("wiki", ok=False, detail="not enough Wikipedia pageview days")
    prior = views[:-1]
    return _source_result("wiki", ok=True, count_24h=float(views[-1]),
                          avg_7d=round(sum(prior) / len(prior), 2),
                          detail=f"Wikipedia views of '{title}', latest full day vs the days before",
                          article=title)


def heat_bsky(query: str, fetch: Fetcher, now: _dt.datetime, session: "BskySession") -> dict[str, Any]:
    if not session.configured:
        return _source_result("bsky", ok=False, detail="Bluesky search needs an app password on the rig")
    try:
        token = session.token(fetch)
        url = session.pds(fetch) + "/app.bsky.feed.searchPosts?" + urllib.parse.urlencode(
            {"q": query, "since": _iso(now - _dt.timedelta(hours=24)), "limit": 100, "sort": "latest"})
        data = fetch(url, headers={"Authorization": f"Bearer {token}"})
    except FetchError as exc:
        return _source_result("bsky", ok=False, detail=f"Bluesky {exc}")
    posts = data.get("posts") if isinstance(data, dict) else None
    if not isinstance(posts, list):
        return _source_result("bsky", ok=False, detail="Bluesky returned no post list")
    count = len(posts)
    # One page only: a full page means "at least 100", reported as capped rather than invented.
    return _source_result("bsky", ok=True, count_24h=float(count), avg_7d=None, capped=count >= 100,
                          detail="Bluesky posts in the last 24h (one page; no 7-day baseline)")


def heat_ratio(sources: list[dict[str, Any]]) -> float | None:
    ratios = []
    for s in sources:
        if s.get("ok") and s.get("avg_7d") and s["count_24h"] is not None and s["avg_7d"] > 0:
            ratios.append(float(s["count_24h"]) / float(s["avg_7d"]))
    if not ratios:
        return None
    ratios.sort()
    mid = len(ratios) // 2
    median = ratios[mid] if len(ratios) % 2 else (ratios[mid - 1] + ratios[mid]) / 2.0
    return round(median, 3)


class BskySession:
    """Bluesky app-password session (handle + app password from the rig env, never the repo)."""

    def __init__(self, handle: str | None = None, app_password: str | None = None) -> None:
        self.handle = (handle if handle is not None else os.environ.get("AIRCHECK_BSKY_HANDLE", "")).strip()
        self.app_password = (app_password if app_password is not None
                             else os.environ.get("AIRCHECK_BSKY_APP_PASSWORD", "")).strip()
        self._lock = threading.Lock()
        self._jwt: str | None = None
        self._did: str | None = None
        self._pds: str | None = None
        self._at = 0.0

    @property
    def configured(self) -> bool:
        return bool(self.handle and self.app_password)

    def pds(self, fetch: Fetcher) -> str:
        """The sender's own PDS xrpc base (authenticated calls must go there), from handle -> DID
        -> DID document. Falls back to bsky.social only when the lookup can't be done."""
        with self._lock:
            if self._pds:
                return self._pds
        found: str | None = None
        try:
            did = str(fetch(BSKY_PUBLIC_URL + "/com.atproto.identity.resolveHandle?" +
                            urllib.parse.urlencode({"handle": self.handle.lstrip("@")}))["did"])
            if did.startswith("did:plc:"):
                doc = fetch(PLC_DIRECTORY_URL + "/" + urllib.parse.quote(did, safe=":"))
            elif did.startswith("did:web:"):
                doc = fetch("https://" + did[len("did:web:"):] + "/.well-known/did.json")
            else:
                doc = {}
            for svc in (doc.get("service") or []) if isinstance(doc, dict) else []:
                endpoint = str(svc.get("serviceEndpoint") or "") if isinstance(svc, dict) else ""
                if str(svc.get("id") or "").endswith("#atproto_pds") and endpoint.startswith("https://"):
                    found = endpoint.rstrip("/") + "/xrpc"
                    break
        except (FetchError, KeyError, TypeError, AttributeError):
            pass
        if found is None:
            return BSKY_PDS_URL  # not cached, so the next call tries discovery again
        with self._lock:
            self._pds = found
        return found

    def token(self, fetch: Fetcher) -> str:
        pds = self.pds(fetch)
        with self._lock:
            if self._jwt and time.monotonic() - self._at < 30 * 60:
                return self._jwt
            data = fetch(pds + "/com.atproto.server.createSession", method="POST",
                         body={"identifier": self.handle, "password": self.app_password})
            try:
                self._jwt = str(data["accessJwt"])
                self._did = str(data.get("did") or "")
            except (KeyError, TypeError) as exc:
                raise FetchError("login failed") from exc
            self._at = time.monotonic()
            return self._jwt


# --------------------------------------------------------------------------------------------
# Pulse
# --------------------------------------------------------------------------------------------

ANALYZE_SYSTEM = (
    "You read a short social post idea and return strict JSON only. You never invent facts, numbers, "
    "or sources. If you are not sure who a name refers to, leave corrected_name empty."
)
ANALYZE_USER = (
    "Idea: {text}\n\nReturn JSON with this schema:\n"
    '{{"query":"2-5 word news search query for the topic, using corrected names",'
    '"entities":[{{"name":"as written","corrected_name":"likely intended real name or empty","kind":"person|org|topic"}}],'
    '"claims":[{{"text":"the factual claim as a short phrase","about":"person or org it is about, corrected",'
    '"kind":"allegation|fact|opinion|joke","search":"3-6 word query to find news reporting it"}}]}}\n'
    "Allegation = says a real person or org did something wrong. List every allegation, even if it is phrased as a joke."
)
BOOST_SYSTEM = (
    "You suggest ways to make a short social video post land better. Return strict JSON only. "
    "Base every suggestion on the facts given; do not invent numbers, quotes, events or handles."
)
BOOST_USER = (
    "Idea: {text}\nField: {field}\nCreator's areas: {areas}\nMeasured heat: {heat}\n{held}\n"
    "Return JSON: {{\"boosts\":[{{\"kind\":\"hook|timing|thread|angle|reframe\",\"text\":\"the rewritten line\","
    "\"why\":\"one short sentence, citing the measured heat or areas when used\"}}]}} with {n} items."
)
HELD_NOTE = (
    "The idea makes an unsourced allegation about a real person. Every suggestion must be a reframe that does "
    "NOT assert the allegation (for example: drop it, or ask what is actually known). kind must be reframe."
)


def _llm_json(llm_router: Any, system: str, user: str, *, max_tokens: int = 700) -> tuple[dict[str, Any] | None, str]:
    if llm_router is None:
        return None, "no LLM configured"
    try:
        result = llm_router.complete("ai_assist", system, user, temperature=0.2, max_tokens=max_tokens)
    except Exception as exc:  # LlmUnavailableError / LlmProviderError / transport
        return None, f"LLM unavailable: {exc}"
    parsed = _extract_json_object(getattr(result, "text", "") or "")
    if parsed is None:
        return None, "LLM did not return JSON"
    return parsed, f"{getattr(result, 'provider', '?')}/{getattr(result, 'model', '?')}"


def _as_list(value: Any) -> list[Any]:
    """LLM JSON fields that should be arrays; anything else counts as empty, never a crash."""
    return value if isinstance(value, list) else []


def public_addresses(url: str, resolve: Callable[[str], list[str]] | None = None) -> list[str]:
    """The addresses an http(s) URL's host resolves to, or [] unless every one is public
    (no loopback, LAN, link-local)."""
    try:
        parts = urllib.parse.urlsplit(url)
    except ValueError:
        return []
    if parts.scheme not in ("http", "https") or not parts.hostname:
        return []
    resolver = resolve or (lambda host: [ai[4][0] for ai in socket.getaddrinfo(host, None)])
    try:
        addrs = resolver(parts.hostname)
    except (OSError, UnicodeError):
        return []
    for addr in addrs:
        try:
            if not ipaddress.ip_address(addr.split("%", 1)[0]).is_global:
                return []
        except ValueError:
            return []
    return list(addrs)


def is_public_http_url(url: str, resolve: Callable[[str], list[str]] | None = None) -> bool:
    return bool(public_addresses(url, resolve))


class _PinnedHTTPConnection(http.client.HTTPConnection):
    """Connects to an address already checked as public, so DNS can't change between the check
    and the request (rebinding)."""

    def __init__(self, host: str, ip: str, **kw: Any) -> None:
        super().__init__(host, **kw)
        self._ip = ip

    def connect(self) -> None:
        self.sock = socket.create_connection((self._ip, self.port), self.timeout)


class _PinnedHTTPSConnection(http.client.HTTPSConnection):
    """As above; TLS still verifies the certificate against the hostname (SNI + Host header)."""

    def __init__(self, host: str, ip: str, **kw: Any) -> None:
        super().__init__(host, context=ssl.create_default_context(), **kw)
        self._ip = ip

    def connect(self) -> None:
        raw = socket.create_connection((self._ip, self.port), self.timeout)
        self.sock = self._context.wrap_socket(raw, server_hostname=self.host)  # type: ignore[attr-defined]


def fetch_public_page(url: str, *, max_bytes: int = 512 * 1024,
                      resolve: Callable[[str], list[str]] | None = None) -> str:
    """Fetch a user-cited page for the claim check. Public hosts only, connected to the address
    that was checked, redirects not followed, so a request to the rig can't reach LAN services."""
    addrs = public_addresses(url, resolve)
    if not addrs:
        raise FetchError("not a public web address")
    parts = urllib.parse.urlsplit(url)
    host = parts.hostname or ""
    path = (parts.path or "/") + (f"?{parts.query}" if parts.query else "")
    cls = _PinnedHTTPSConnection if parts.scheme == "https" else _PinnedHTTPConnection
    conn = cls(host, addrs[0].split("%", 1)[0], port=parts.port, timeout=HTTP_TIMEOUT_SEC)
    try:
        conn.request("GET", path, headers={"User-Agent": USER_AGENT, "Accept": "text/html,*/*",
                                           "Host": parts.netloc.rsplit("@", 1)[-1]})
        resp = conn.getresponse()
        if resp.status >= 300:
            # 3xx included: a redirect could point back at the LAN, so it is never followed.
            raise FetchError(f"HTTP {resp.status}")
        return resp.read(max_bytes).decode("utf-8", errors="replace")
    except (http.client.HTTPException, TimeoutError, OSError, ssl.SSLError) as exc:
        raise FetchError(f"unreachable ({exc.__class__.__name__})") from exc
    finally:
        conn.close()


def _link_mentions(url: str, about: str, fetch: Callable[[str], str], claim: str = "") -> bool:
    """True only if the cited page loads, names who the claim is about, and also uses the claim's
    own words (at least half of them, matched on their first 5 letters so "cheated" covers
    "cheating"). A bio or homepage that only names the person doesn't count."""
    words = [w for w in re.findall(r"[^\W\d_][\w'\-]+", about) if len(w) > 2]
    claim_words = [w.lower()[:5] for w in re.findall(r"[^\W\d_][\w'\-]+", claim)
                   if len(w) > 3 and w.lower() not in _STOPWORDS]
    if not words or not claim_words:
        return False
    try:
        page = fetch(url)
    except FetchError:
        return False
    if not isinstance(page, str):
        return False
    lowered = page.lower()
    if words[-1].lower() not in lowered:
        return False
    hits = sum(1 for w in dict.fromkeys(claim_words) if w in lowered)
    return hits * 2 >= len(dict.fromkeys(claim_words))


def check_claim(claim: dict[str, Any], fetch: Fetcher) -> dict[str, Any]:
    query = str(claim.get("search") or "").strip() or f"{claim.get('about', '')} {claim.get('text', '')}".strip()
    out = {"text": str(claim.get("text") or ""), "about": str(claim.get("about") or ""),
           "kind": str(claim.get("kind") or ""), "query": query, "status": "unknown", "sources": [], "detail": ""}
    if not query:
        out["detail"] = "nothing to search"
        return out
    url = GDELT_DOC_URL + "?" + urllib.parse.urlencode(
        {"query": query, "mode": "artlist", "maxrecords": 5, "timespan": "3months", "format": "json"})
    try:
        data = fetch(url)
    except FetchError as exc:
        out["detail"] = f"news lookup failed: GDELT {exc}"
        return out
    articles = data.get("articles") if isinstance(data, dict) else None
    articles = articles or []
    out["sources"] = [
        {"title": str(a.get("title") or ""), "url": str(a.get("url") or ""), "domain": str(a.get("domain") or "")}
        for a in articles[:3] if isinstance(a, dict) and a.get("url")
    ]
    out["status"] = "sourced" if out["sources"] else "unsourced"
    out["detail"] = ("news articles match this search; read them before posting" if out["sources"]
                     else "no news article in the last 3 months matches this claim")
    return out


def _held_reason(held: list[dict[str, Any]], truncated: bool) -> str:
    if held:
        c = held[0]
        state = "Claim about" if c["status"] == "sourced" else "Unsourced claim about"
        tail = " (news matches the search; cite one that backs it)" if c["status"] == "sourced" else ""
        return f"{state} {c['about'] or 'a real person'}: \"{c['text']}\"{tail}"
    if truncated:
        return f"Only the first {MAX_TEXT_CHARS} characters were checked"
    return ""


class PulseService:
    def __init__(self, state_dir: Path, *, llm_router: Any = None, fetch: Fetcher = http_fetch,
                 bsky: BskySession | None = None, clock: Callable[[], _dt.datetime] = _utc_now,
                 fetch_citation: Callable[[str], str] = fetch_public_page) -> None:
        self.areas_path = Path(state_dir) / "pulse_areas.json"
        self.fetch_citation = fetch_citation
        self.llm_router = llm_router
        self.fetch = fetch
        self.bsky = bsky or BskySession()
        self.clock = clock
        self._cache = _TtlCache()
        self._lock = threading.Lock()

    def get_areas(self) -> dict[str, Any]:
        data = _json_store_read(self.areas_path, {})
        areas = data.get("areas") if isinstance(data, dict) else None
        return {"areas": [str(a) for a in areas] if isinstance(areas, list) else [],
                "updated_at": data.get("updated_at") if isinstance(data, dict) else None}

    def put_areas(self, payload: Any) -> dict[str, Any]:
        if not isinstance(payload, dict) or not isinstance(payload.get("areas"), list):
            raise ValueError("body must be {\"areas\": [\"...\"]}")
        cleaned: list[str] = []
        for a in payload["areas"]:
            s = str(a).strip()[:80]
            if s and s.lower() not in {c.lower() for c in cleaned}:
                cleaned.append(s)
        if len(cleaned) > 40:
            raise ValueError("at most 40 areas")
        value = {"areas": cleaned, "updated_at": _iso(self.clock())}
        with self._lock:
            _json_store_write(self.areas_path, value)
        return value

    def _cached_fetch(self, *args: Any, **kwargs: Any) -> Any:
        key = json.dumps([args, kwargs], sort_keys=True, default=str)
        return self._cache.get_or(key, lambda: self.fetch(*args, **kwargs))

    def score(self, payload: Any) -> dict[str, Any]:
        if not isinstance(payload, dict):
            raise ValueError("body must be a JSON object")
        full = str(payload.get("text") or "").strip()
        if not full:
            raise ValueError("text is required")
        text = full[:MAX_TEXT_CHARS]
        # Claims past the cutoff were never checked, so the result can't release boosts.
        truncated = len(full) > MAX_TEXT_CHARS
        field = str(payload.get("field") or "note").strip()[:40]
        now = self.clock()
        fetch = self._cached_fetch

        analysis, analysis_via = _llm_json(self.llm_router, ANALYZE_SYSTEM, ANALYZE_USER.format(text=text))
        if analysis is not None and not isinstance(analysis.get("claims"), list):
            # No claims list means the claim check didn't really run: fail closed, like no JSON.
            analysis, analysis_via = None, f"claim check returned no claims list ({analysis_via})"
        entities: list[dict[str, str]] = []
        raw_claims: list[dict[str, Any]] = []
        if analysis:
            for e in _as_list(analysis.get("entities")):
                if isinstance(e, dict) and str(e.get("name") or "").strip():
                    entities.append({"name": str(e.get("name")).strip(),
                                     "corrected_name": str(e.get("corrected_name") or "").strip(),
                                     "kind": str(e.get("kind") or "topic")})
            raw_claims = [c for c in _as_list(analysis.get("claims")) if isinstance(c, dict)]
        query = (str(analysis.get("query") or "").strip() if analysis else "") or naive_query(text)

        # At most MAX_CITED_LINKS links, each fetched once for all claims, so citation checks stay
        # well inside the app's read timeout.
        cited = [{"title": "", "url": u, "domain": urllib.parse.urlsplit(u).netloc}
                 for u in list(dict.fromkeys(_URL_RE.findall(text)))[:MAX_CITED_LINKS]]
        pages: dict[str, Any] = {}

        def fetch_citation_once(u: str) -> str:
            if u not in pages:
                try:
                    pages[u] = self.fetch_citation(u)
                except FetchError as exc:
                    pages[u] = exc
            if isinstance(pages[u], FetchError):
                raise pages[u]
            return pages[u]

        claims = []
        for c in raw_claims:
            if c.get("kind") not in ("allegation", "fact"):
                continue
            supporting = [link for link in cited
                          if _link_mentions(link["url"], str(c.get("about") or ""), fetch_citation_once,
                                        str(c.get("text") or ""))]
            if supporting:
                claims.append({"text": str(c.get("text") or ""), "about": str(c.get("about") or ""),
                               "kind": str(c.get("kind") or ""), "query": "", "status": "cited",
                               "sources": supporting[:3],
                               "detail": f"your link names {c.get('about') or 'them'} and uses the claim's words; check it backs the claim"})
            else:
                # A link that doesn't even name the person can't stand in for a source.
                claims.append(check_claim(c, fetch))
        # A search hit only means articles share the query words, not that they back the claim,
        # so allegations stay held until a link the creator cites names who it's about.
        held = [c for c in claims if c["kind"] == "allegation" and c["status"] != "cited"]
        held_any = bool(held) or truncated

        wiki_term = next((e["corrected_name"] or e["name"] for e in entities if e["kind"] in ("person", "org")), query)
        if query:
            sources = [heat_gdelt(query, fetch, now), heat_hn(query, fetch, now),
                       heat_wiki(wiki_term, fetch, now), heat_bsky(query, fetch, now, self.bsky)]
        else:
            # Searching an empty query would count site-wide activity as this idea's heat.
            sources = [_source_result(n, ok=False, detail="no topic words to search for")
                       for n in ("news", "hn", "wiki", "bsky")]
        ratio = heat_ratio(sources)

        areas = self.get_areas()["areas"]
        heat_summary = "; ".join(
            f"{s['name']}: {s['count_24h']:g} in 24h vs {s['avg_7d']:g}/day avg" if s["ok"] and s["avg_7d"] is not None
            else (f"{s['name']}: {s['count_24h']:g} in 24h" if s["ok"] else f"{s['name']}: unknown")
            for s in sources)
        boosts: list[dict[str, str]] = []
        boosts_via = ""
        if analysis is None:
            # Fail closed: without a claim check we can't know the idea is safe to amplify.
            boost_json, boosts_via = None, f"skipped: claim check didn't run ({analysis_via})"
        else:
            boost_json, boosts_via = _llm_json(self.llm_router, BOOST_SYSTEM, BOOST_USER.format(
                text=text, field=field, areas=", ".join(areas) or "unknown", heat=heat_summary,
                held=HELD_NOTE if held_any else "", n=2 if held_any else 3))
        if boost_json:
            for b in _as_list(boost_json.get("boosts")):
                if not isinstance(b, dict) or not str(b.get("text") or "").strip():
                    continue
                kind = str(b.get("kind") or "angle")
                if held_any and kind != "reframe":
                    continue
                boosts.append({"kind": kind, "text": str(b["text"]).strip(), "why": str(b.get("why") or "").strip()})

        return {
            "text": text,
            "field": field,
            "query": query,
            "entities": entities,
            "claims": claims,
            "boosts_held": held_any,
            "truncated": truncated,
            "held_reason": _held_reason(held, truncated),
            "heat": {"ratio": ratio, "sources": sources},
            "boosts": boosts,
            "analysis_via": analysis_via,
            "boosts_via": boosts_via,
            "areas": areas,
            "checked_at": _iso(now),
        }


# --------------------------------------------------------------------------------------------
# Name-drop
# --------------------------------------------------------------------------------------------

_ARXIV_RE = re.compile(r"^/(?:abs|pdf)/([0-9]{4}\.[0-9]{4,5}(?:v\d+)?|[a-z\-]+(?:\.[A-Za-z\-]+)?/[0-9]{7})", re.I)
_DOI_RE = re.compile(r"\b(10\.\d{4,9}/[^\s\"<>]+)", re.I)
_BSKY_POST_RE = re.compile(r"^/profile/([^/\s]+)/post/([A-Za-z0-9]+)")
_X_POST_RE = re.compile(r"^/([A-Za-z0-9_]{1,15})/status/(\d+)", re.I)
# "arxiv.org/abs/…" typed without a scheme; the lookbehind keeps it from starting mid-host or mid-URL.
_BARE_LINK_RE = re.compile(r"(?<![\w.@/:-])((?:www\.)?[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+/[^\s<>\"')]*)")
_ARXIV_HOSTS = {"arxiv.org", "www.arxiv.org", "export.arxiv.org"}
_BSKY_HOSTS = {"bsky.app"}
_X_HOSTS = {"x.com", "www.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com", "mobile.x.com"}
_AT_HANDLE_RE = re.compile(r"(?<![\w@])@([A-Za-z0-9_]{1,15})(?![\w.])")
_X_URL_RE = re.compile(r"^https?://(?:www\.)?(?:x|twitter)\.com/([A-Za-z0-9_]{1,15})/?$", re.I)
_BSKY_URL_RE = re.compile(r"^https?://bsky\.app/profile/([^/?#\s]+)/?$", re.I)


def _handle(handle: str, status: str, evidence: str) -> dict[str, str]:
    return {"handle": handle, "status": status, "evidence": evidence}


def _norm_name(s: str) -> str:
    """Unicode-aware: keeps letters in any script, so 张伟 and 王芳 stay distinct (never both "")."""
    return "".join(ch for ch in s.casefold() if ch.isalpha())


def _link_match(src: str, hosts: set[str], path_re: re.Pattern[str]) -> re.Match[str] | None:
    """Matches [path_re] against the path of a link in [src] whose hostname is exactly one of
    [hosts], so lookalikes such as notx.com or x.com.evil.example never count."""
    links = _URL_RE.findall(src) + ["https://" + b for b in _BARE_LINK_RE.findall(src)]
    for raw in links:
        try:
            parsed = urllib.parse.urlsplit(raw)
        except ValueError:
            continue
        if (parsed.hostname or "").lower() in hosts:
            m = path_re.match(parsed.path)
            if m:
                return m
    return None


class NameDropService:
    def __init__(self, state_dir: Path, *, fetch: Fetcher = http_fetch, bsky: BskySession | None = None,
                 clock: Callable[[], _dt.datetime] = _utc_now) -> None:
        self.first_look_path = Path(state_dir) / "first_look.json"
        self.fetch = fetch
        self.bsky = bsky or BskySession()
        self.clock = clock
        self._lock = threading.Lock()

    # ---- resolve ----
    def resolve(self, payload: Any) -> dict[str, Any]:
        if not isinstance(payload, dict):
            raise ValueError("body must be a JSON object")
        url = str(payload.get("url") or "").strip()
        text = str(payload.get("text") or "").strip()[:20000]
        src = url or text
        if not src:
            raise ValueError("url or text is required")
        notes: list[str] = []
        m = _link_match(src, _ARXIV_HOSTS, _ARXIV_RE)
        bm = None if m else _link_match(src, _BSKY_HOSTS, _BSKY_POST_RE)
        xm = None if m or bm else _link_match(src, _X_HOSTS, _X_POST_RE)
        if m:
            people, title = self._from_arxiv(m.group(1), notes)
            kind = "paper"
        elif bm:
            people, title = self._from_bsky_thread(bm.group(1), bm.group(2), notes)
            kind = "bsky_thread"
        elif xm:
            # X opens a status link whatever name is in it, so the name is only a hint.
            people = [{"name": "", "role": "post author",
                       "handles": {"x": _handle("@" + xm.group(1), "likely",
                                                "name in the post link; X doesn't check it"),
                                   "bsky": _handle("", "unknown", "")}}]
            title = ""
            kind = "x_post"
            notes.append("X threads are not read (X API not used); only the author from the link is known.")
        elif _DOI_RE.search(src):
            dm = _DOI_RE.search(src)
            assert dm is not None
            doi = re.split(r"[?#&\s]", dm.group(1), maxsplit=1)[0].rstrip(".,);]")
            people, title = self._from_openalex_doi(doi, notes)
            kind = "paper"
        else:
            people = [{"name": "", "role": "mentioned in text",
                       "handles": {"x": _handle("@" + h, "in_text", "written in the text"),
                                   "bsky": _handle("", "unknown", "")}}
                      for h in dict.fromkeys(_AT_HANDLE_RE.findall(src))]
            title = ""
            kind = "text"
            if not people:
                notes.append("No paper, thread link or @handle found.")
        taggable = {p: sum(1 for person in people if person["handles"].get(p, {}).get("status") in ("verified", "in_text"))
                    for p in ("x", "bsky")}
        return {"kind": kind, "title": title, "people": people, "taggable": taggable, "notes": notes,
                "checked_at": _iso(self.clock())}

    def _from_arxiv(self, arxiv_id: str, notes: list[str]) -> tuple[list[dict[str, Any]], str]:
        base_id = re.sub(r"v\d+$", "", arxiv_id)
        names: list[str] = []
        title = ""
        try:
            xml = self.fetch(ARXIV_API_URL + "?" + urllib.parse.urlencode({"id_list": base_id}), want="text")
            root = ET.fromstring(xml)
            entry = root.find(f"{_ATOM}entry")
            if entry is not None:
                title = " ".join((entry.findtext(f"{_ATOM}title") or "").split())
                names = [" ".join((a.findtext(f"{_ATOM}name") or "").split()) for a in entry.findall(f"{_ATOM}author")]
        except (FetchError, ET.ParseError) as exc:
            notes.append(f"arXiv lookup failed: {exc}")
        orcids = self._openalex_orcids(f"10.48550/arXiv.{base_id}", notes)
        if not names:
            names = [raw for raw, _ in orcids.values()]
        return [self._person(n, i, len(names), (orcids.get(_norm_name(n)) or ("", None))[1], notes)
                for i, n in enumerate(names) if n], title

    def _from_openalex_doi(self, doi: str, notes: list[str]) -> tuple[list[dict[str, Any]], str]:
        try:
            work = self.fetch(f"{OPENALEX_URL}/works/doi:{urllib.parse.quote(doi, safe='/.')}")
        except FetchError as exc:
            notes.append(f"OpenAlex lookup failed: {exc}")
            return [], ""
        auths = work.get("authorships") or []
        people = []
        for i, a in enumerate(auths):
            author = a.get("author") or {}
            name = str(author.get("display_name") or "")
            orcid = str(author.get("orcid") or "").rsplit("/", 1)[-1] or None
            if name:
                people.append(self._person(name, i, len(auths), orcid, notes))
        return people, str(work.get("title") or work.get("display_name") or "")

    def _openalex_orcids(self, doi: str, notes: list[str]) -> dict[str, tuple[str, str | None]]:
        try:
            work = self.fetch(f"{OPENALEX_URL}/works/doi:{doi}")
        except FetchError as exc:
            notes.append(f"OpenAlex lookup failed: {exc}")
            return {}
        out: dict[str, tuple[str, str | None]] = {}
        for a in work.get("authorships") or []:
            author = a.get("author") or {}
            orcid = str(author.get("orcid") or "").rsplit("/", 1)[-1] or None
            raw_name = str(a.get("raw_author_name") or author.get("display_name") or "")
            if raw_name and _norm_name(raw_name):
                out[_norm_name(raw_name)] = (raw_name, orcid)
        return out

    def _person(self, name: str, index: int, total: int, orcid: str | None, notes: list[str]) -> dict[str, Any]:
        role = "1st author" if index == 0 else ("last author" if index == total - 1 and total > 1 else "author")
        handles: dict[str, dict[str, str]] = {"x": _handle("", "unknown", ""), "bsky": _handle("", "unknown", "")}
        if index >= KEY_AUTHOR_LOOKUPS and index != total - 1:
            # Big collaborations would take minutes of lookups; only key authors are checked.
            skipped = f"Handles looked up for the first {KEY_AUTHOR_LOOKUPS} and last author only."
            if skipped not in notes:
                notes.append(skipped)
            gap = _handle("", "unknown", "not looked up")
            return {"name": name, "role": role, "orcid": orcid or "", "handles": {"x": gap, "bsky": dict(gap)}}
        if orcid:
            try:
                data = self.fetch(f"{ORCID_URL}/{orcid}/researcher-urls")
                for item in data.get("researcher-url") or []:
                    link = str((item.get("url") or {}).get("value") or "").strip()
                    xm = _X_URL_RE.match(link)
                    bm = _BSKY_URL_RE.match(link)
                    if xm:
                        handles["x"] = _handle("@" + xm.group(1), "verified", f"listed on ORCID {orcid}")
                    elif bm:
                        actor = bm.group(1)
                        if actor.startswith("did:"):
                            # Profile links can use the DID; tags and DMs need the handle.
                            actor = self._bsky_handle_for(actor, notes)
                        if actor:
                            handles["bsky"] = _handle(actor, "verified", f"listed on ORCID {orcid}")
            except FetchError as exc:
                notes.append(f"ORCID {orcid} lookup failed: {exc}")
        if handles["bsky"]["status"] != "verified":
            handles["bsky"] = self._bsky_candidate(name, orcid, notes)
        return {"name": name, "role": role, "orcid": orcid or "", "handles": handles}

    def _bsky_handle_for(self, did: str, notes: list[str]) -> str:
        try:
            prof = self.fetch(BSKY_PUBLIC_URL + "/app.bsky.actor.getProfile?" + urllib.parse.urlencode({"actor": did}))
        except FetchError as exc:
            notes.append(f"Bluesky profile {did} lookup failed: {exc}")
            return ""
        handle = str(prof.get("handle") or "") if isinstance(prof, dict) else ""
        return "" if not handle or handle == "handle.invalid" else handle

    def _bsky_candidate(self, name: str, orcid: str | None, notes: list[str]) -> dict[str, str]:
        try:
            data = self.fetch(BSKY_PUBLIC_URL + "/app.bsky.actor.searchActors?" +
                              urllib.parse.urlencode({"q": name, "limit": 5}))
        except FetchError as exc:
            notes.append(f"Bluesky search for {name} failed: {exc}")
            return _handle("", "unknown", "")
        target = _norm_name(name)
        if not target:
            return _handle("", "unknown", "")
        actors = data.get("actors") if isinstance(data, dict) else None
        if not isinstance(actors, list):
            notes.append(f"Bluesky search for {name} returned an unexpected reply")
            return _handle("", "unknown", "")
        for actor in actors:
            if not isinstance(actor, dict) or _norm_name(str(actor.get("displayName") or "")) != target:
                continue
            handle = str(actor.get("handle") or "")
            desc = str(actor.get("description") or "")
            if orcid and orcid in desc:
                # A bio can claim any ORCID; only the ORCID record linking back verifies.
                return _handle(handle, "likely", "Bluesky bio lists the same ORCID; ORCID record doesn't link back")
            return _handle(handle, "likely", "same display name on Bluesky; not cross-linked")
        return _handle("", "unknown", "no Bluesky profile with this name")

    def _from_bsky_thread(self, actor: str, rkey: str, notes: list[str]) -> tuple[list[dict[str, Any]], str]:
        uri = f"at://{actor}/app.bsky.feed.post/{rkey}"
        try:
            data = self.fetch(BSKY_PUBLIC_URL + "/app.bsky.feed.getPostThread?" +
                              urllib.parse.urlencode({"uri": uri, "depth": 0, "parentHeight": 0}))
            post = data["thread"]["post"]
        except FetchError as exc:
            notes.append(f"Bluesky thread lookup failed: {exc}")
            return [], ""
        except (KeyError, TypeError):
            notes.append("Bluesky did not return that post")
            return [], ""
        author = post.get("author") or {}
        people = [{"name": str(author.get("displayName") or ""), "role": "post author",
                   "handles": {"bsky": _handle(str(author.get("handle") or actor), "verified", "author of the post"),
                               "x": _handle("", "unknown", "")}}]
        record = post.get("record") or {}
        dids: list[str] = []
        for facet in record.get("facets") or []:
            for feat in facet.get("features") or []:
                if str(feat.get("$type")) == "app.bsky.richtext.facet#mention" and feat.get("did"):
                    dids.append(str(feat["did"]))
        for did in dict.fromkeys(dids):
            try:
                prof = self.fetch(BSKY_PUBLIC_URL + "/app.bsky.actor.getProfile?" + urllib.parse.urlencode({"actor": did}))
                people.append({"name": str(prof.get("displayName") or ""), "role": "mentioned",
                               "handles": {"bsky": _handle(str(prof.get("handle") or did), "verified", "mentioned in the post"),
                                           "x": _handle("", "unknown", "")}})
            except FetchError as exc:
                notes.append(f"Bluesky profile {did} lookup failed: {exc}")
        return people, " ".join(str(record.get("text") or "").split())[:140]

    # ---- First look ----
    def get_first_look(self) -> dict[str, Any]:
        data = _json_store_read(self.first_look_path, {})
        people = data.get("people") if isinstance(data, dict) else None
        return {"people": people if isinstance(people, list) else [],
                "bsky_dm_ready": self.bsky.configured,
                "last_send": data.get("last_send") if isinstance(data, dict) else None}

    def put_first_look(self, payload: Any) -> dict[str, Any]:
        if not isinstance(payload, dict) or not isinstance(payload.get("people"), list):
            raise ValueError("body must be {\"people\": [...]}")
        people = []
        seen: set[tuple[str, str]] = set()
        for raw in payload["people"]:
            if not isinstance(raw, dict):
                raise ValueError("each person must be an object")
            platform = str(raw.get("platform") or "").strip().lower()
            address = str(raw.get("address") or "").strip()
            if platform not in PLATFORMS:
                raise ValueError(f"platform must be one of {list(PLATFORMS)}")
            if not address:
                raise ValueError("address is required")
            if platform == "email" and "@" not in address.strip("@"):
                raise ValueError(f"not an email address: {address}")
            key = (platform, address.lstrip("@").casefold())
            if key in seen:
                continue  # one DM per person, however the address was typed
            seen.add(key)
            people.append({"id": str(raw.get("id") or uuid.uuid4().hex[:12]),
                           "name": str(raw.get("name") or "").strip()[:80],
                           "platform": platform, "address": address[:254], "on": bool(raw.get("on", True))})
        if len(people) > 50:
            raise ValueError("at most 50 people")
        with self._lock:
            current = _json_store_read(self.first_look_path, {})
            value = {"people": people, "last_send": current.get("last_send") if isinstance(current, dict) else None}
            _json_store_write(self.first_look_path, value)
        return self.get_first_look()

    def send_first_look(self, payload: Any) -> dict[str, Any]:
        if not isinstance(payload, dict):
            raise ValueError("body must be a JSON object")
        post_url = str(payload.get("post_url") or "").strip()
        text = str(payload.get("text") or "").strip()[:900]
        if not post_url and not text:
            raise ValueError("post_url or text is required")
        message = (text + ("\n" + post_url if post_url else "")).strip()
        results: list[dict[str, Any]] = []
        people = [p for p in self.get_first_look()["people"] if p.get("on")]
        bsky_jobs: list[tuple[int, Any]] = []
        # Bluesky DMs go out in parallel with a deadline, so the reply reaches the phone before its
        # 120 s read timeout; a DM not started by then is reported as not sent, never retried here.
        pool = concurrent.futures.ThreadPoolExecutor(max_workers=FIRST_LOOK_WORKERS)
        for person in people:
            platform, address = person["platform"], person["address"]
            base = {"id": person["id"], "name": person.get("name", ""), "platform": platform, "address": address}
            if platform == "bsky":
                results.append(base)
                bsky_jobs.append((len(results) - 1, pool.submit(self._send_bsky_dm, address, message)))
            elif platform == "x":
                handle = address.lstrip("@")
                # X DM links need a numeric user id we don't have (no X API), so open this person's
                # profile and hand over the message to paste.
                results.append({**base, "status": "needs_you",
                                "detail": f"opens @{handle} on X; tap Message and paste (message copied)",
                                "open_url": "https://x.com/" + urllib.parse.quote(handle, safe=""),
                                "copy_text": message})
            else:
                results.append({**base, "status": "needs_you", "detail": "send from your mail app",
                                "open_url": "mailto:" + urllib.parse.quote(address) + "?" +
                                urllib.parse.urlencode({"subject": "New post", "body": message},
                                                       quote_via=urllib.parse.quote)})
        if bsky_jobs:
            concurrent.futures.wait([f for _, f in bsky_jobs], timeout=FIRST_LOOK_DEADLINE_SEC)
        # Cancel everything not yet started in one pass, before waiting on the ones in flight.
        cancelled = {i for i, fut in bsky_jobs if fut.cancel()}
        for i, fut in bsky_jobs:
            if i in cancelled:
                outcome = {"status": "failed", "detail": "not sent: ran out of time; send again for this person"}
            else:
                try:
                    outcome = fut.result()  # already running: bounded by its own HTTP timeouts
                except Exception as exc:  # one bad reply must not hide the others' results
                    outcome = {"status": "failed",
                               "detail": f"unexpected error ({exc.__class__.__name__}); it may not have sent"}
            results[i] = {**results[i], **outcome}
        pool.shutdown(wait=False)
        summary = {"at": _iso(self.clock()), "sent": sum(r["status"] == "sent" for r in results),
                   "needs_you": sum(r["status"] == "needs_you" for r in results),
                   "failed": sum(r["status"] == "failed" for r in results)}
        with self._lock:
            current = _json_store_read(self.first_look_path, {})
            if isinstance(current, dict):
                current["last_send"] = summary
                _json_store_write(self.first_look_path, current)
        return {"results": results, "summary": summary}

    def _send_bsky_dm(self, handle: str, message: str) -> dict[str, str]:
        if not self.bsky.configured:
            return {"status": "failed", "detail": "Bluesky DMs need an app password on the rig"}
        try:
            token = self.bsky.token(self.fetch)
            did = self.fetch(BSKY_PUBLIC_URL + "/com.atproto.identity.resolveHandle?" +
                             urllib.parse.urlencode({"handle": handle.lstrip("@")}))["did"]
            hdr = {"Authorization": f"Bearer {token}", "atproto-proxy": BSKY_CHAT_PROXY}
            pds = self.bsky.pds(self.fetch)
            convo = self.fetch(pds + "/chat.bsky.convo.getConvoForMembers?" +
                               urllib.parse.urlencode({"members": did}), headers=hdr)["convo"]["id"]
            sent = self.fetch(pds + "/chat.bsky.convo.sendMessage", method="POST", headers=hdr,
                              body={"convoId": convo, "message": {"text": message}})
            if not isinstance(sent, dict) or not sent.get("id"):
                return {"status": "failed", "detail": "Bluesky did not confirm the message"}
        except FetchError as exc:
            return {"status": "failed", "detail": f"Bluesky {exc}"}
        except (KeyError, TypeError):
            return {"status": "failed", "detail": "Bluesky returned an unexpected reply"}
        return {"status": "sent", "detail": "Bluesky confirmed the DM"}
