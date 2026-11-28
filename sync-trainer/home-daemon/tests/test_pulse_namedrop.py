"""Pulse + Name-drop: every number comes from a source that answered; failures stay visible."""
from __future__ import annotations

import datetime as dt
import json
import urllib.parse
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

import pulse_namedrop as pn

NOW = dt.datetime(2026, 11, 28, 12, 0, tzinfo=dt.timezone.utc)


class FakeFetch:
    """Routes by URL substring; unknown URLs raise FetchError like an unreachable host."""

    def __init__(self, routes: dict[str, Any]) -> None:
        self.routes = routes
        self.calls: list[tuple[str, dict[str, Any]]] = []

    def __call__(self, url: str, **kwargs: Any) -> Any:
        self.calls.append((url, kwargs))
        for key, value in self.routes.items():
            if key in url:
                if isinstance(value, Exception):
                    raise value
                return value(url, kwargs) if callable(value) else value
        raise pn.FetchError("unreachable (test)")


class FakeLlm:
    def __init__(self, *texts: str) -> None:
        self.texts = list(texts)
        self.prompts: list[str] = []

    def complete(self, stage: str, system: str, user: str, **kwargs: Any) -> Any:
        assert stage == "ai_assist"
        self.prompts.append(user)
        return SimpleNamespace(text=self.texts.pop(0), provider="local_mlx", model="m")


def _gdelt_timeline(url: str, _kw: Any) -> Any:
    if "artlist" in url:
        return {}
    points = []
    for h in range(8 * 24):
        ts = NOW - dt.timedelta(hours=h)
        points.append({"date": ts.strftime("%Y%m%dT%H%M%SZ"), "value": 2 if h < 24 else 1})
    return {"timeline": [{"data": points}]}


ANALYSIS = json.dumps({
    "query": "Jensen Huang cheating",
    "entities": [{"name": "jhensen wang", "corrected_name": "Jensen Huang", "kind": "person"}],
    "claims": [{"text": "caught cheating", "about": "Jensen Huang", "kind": "allegation",
                "search": "Jensen Huang cheating"}],
})


def test_unsourced_allegation_holds_boosts_and_only_reframes_come_back(tmp_path: Path) -> None:
    fetch = FakeFetch({"timelinevolraw": _gdelt_timeline, "artlist": {"articles": []}})
    llm = FakeLlm(ANALYSIS, json.dumps({"boosts": [
        {"kind": "hook", "text": "He CHEATED", "why": "x"},
        {"kind": "reframe", "text": "What's actually going on at Nvidia this week?", "why": "drops the claim"},
    ]}))
    svc = pn.PulseService(tmp_path, llm_router=llm, fetch=fetch, bsky=pn.BskySession("", ""), clock=lambda: NOW)
    out = svc.score({"text": "jhensen wang got caught ceating what a wang", "field": "note"})

    assert out["boosts_held"] is True
    assert "Jensen Huang" in out["held_reason"]
    assert out["claims"][0]["status"] == "unsourced"
    assert [b["kind"] for b in out["boosts"]] == ["reframe"]
    assert pn.HELD_NOTE in llm.prompts[1]
    assert out["entities"][0]["corrected_name"] == "Jensen Huang"
    news = next(s for s in out["heat"]["sources"] if s["name"] == "news")
    assert news["ok"] and news["count_24h"] == 48 and news["avg_7d"] == 24.0
    for name in ("hn", "wiki"):
        src = next(s for s in out["heat"]["sources"] if s["name"] == name)
        assert src["ok"] is False and src["count_24h"] is None and "unreachable" in src["detail"]
    bsky = next(s for s in out["heat"]["sources"] if s["name"] == "bsky")
    assert bsky["ok"] is False and "app password" in bsky["detail"]
    assert out["heat"]["ratio"] == 2.0


def test_news_match_shows_sources_but_keeps_allegation_boosts_held(tmp_path: Path) -> None:
    fetch = FakeFetch({"artlist": {"articles": [{"title": "T", "url": "https://n.example/a", "domain": "n.example"}]},
                       "timelinevolraw": _gdelt_timeline})
    llm = FakeLlm(ANALYSIS, json.dumps({"boosts": [{"kind": "hook", "text": "Lead line", "why": "news heat 2x"},
                                                   {"kind": "reframe", "text": "Ask it", "why": "safer"}]}))
    svc = pn.PulseService(tmp_path, llm_router=llm, fetch=fetch, bsky=pn.BskySession("", ""), clock=lambda: NOW)
    out = svc.score({"text": "idea"})
    assert out["claims"][0]["status"] == "sourced"
    assert out["claims"][0]["sources"][0]["url"] == "https://n.example/a"
    # A search hit isn't proof the article backs the allegation.
    assert out["boosts_held"] is True
    assert "cite one that backs it" in out["held_reason"]
    assert [b["kind"] for b in out["boosts"]] == ["reframe"]


def test_factual_claim_with_news_releases_boosts(tmp_path: Path) -> None:
    fetch = FakeFetch({"artlist": {"articles": [{"title": "T", "url": "https://n.example/a", "domain": "n.example"}]},
                       "timelinevolraw": _gdelt_timeline})
    analysis = json.dumps({"query": "rust 2.0 release", "entities": [],
                           "claims": [{"text": "rust 2.0 shipped", "about": "Rust", "kind": "fact",
                                       "search": "rust 2.0 release"}]})
    llm = FakeLlm(analysis, json.dumps({"boosts": [{"kind": "hook", "text": "Lead line", "why": "news heat 2x"}]}))
    svc = pn.PulseService(tmp_path, llm_router=llm, fetch=fetch, bsky=pn.BskySession("", ""), clock=lambda: NOW)
    out = svc.score({"text": "idea"})
    assert out["boosts_held"] is False and out["truncated"] is False
    assert out["boosts"] == [{"kind": "hook", "text": "Lead line", "why": "news heat 2x"}]


def test_oversized_text_holds_boosts_and_says_so(tmp_path: Path) -> None:
    analysis = json.dumps({"query": "rust", "entities": [], "claims": []})
    llm = FakeLlm(analysis, json.dumps({"boosts": [{"kind": "hook", "text": "Lead", "why": "w"},
                                                   {"kind": "reframe", "text": "R", "why": "w"}]}))
    svc = pn.PulseService(tmp_path, llm_router=llm, fetch=FakeFetch({}), bsky=pn.BskySession("", ""),
                          clock=lambda: NOW)
    out = svc.score({"text": "rust " * 600})
    assert out["truncated"] is True and out["boosts_held"] is True
    assert "first 2000 characters" in out["held_reason"]
    assert [b["kind"] for b in out["boosts"]] == ["reframe"]


def test_naive_query_ignores_links() -> None:
    assert pn.naive_query("https://example.com/foo") == ""
    assert pn.naive_query("https://example.com/foo rust compile times") == "rust compile times"


def test_first_look_dedupes_addresses(tmp_path: Path) -> None:
    svc = pn.NameDropService(tmp_path, fetch=lambda url, **kw: {}, clock=lambda: NOW)
    out = svc.put_first_look({"people": [{"platform": "bsky", "address": "Ada.bsky.social"},
                                         {"platform": "bsky", "address": "@ada.bsky.social"},
                                         {"platform": "x", "address": "@ada.bsky.social"}]})
    assert [(p["platform"], p["address"]) for p in out["people"]] == [("bsky", "Ada.bsky.social"),
                                                                     ("x", "@ada.bsky.social")]


def test_bsky_session_uses_the_senders_own_pds() -> None:
    calls: list[str] = []

    def fetch(url: str, **kw: Any) -> Any:
        calls.append(url)
        if "resolveHandle" in url:
            return {"did": "did:plc:abc"}
        if url.startswith(pn.PLC_DIRECTORY_URL):
            return {"service": [{"id": "#atproto_pds", "type": "AtprotoPersonalDataServer",
                                 "serviceEndpoint": "https://pds.example.net"}]}
        if "createSession" in url:
            return {"accessJwt": "jwt", "did": "did:plc:abc"}
        raise pn.FetchError("unexpected")

    session = pn.BskySession("me.example.net", "pw")
    assert session.token(fetch) == "jwt"
    assert calls[-1] == "https://pds.example.net/xrpc/com.atproto.server.createSession"


def test_large_author_lists_only_look_up_key_authors(tmp_path: Path) -> None:
    looked: list[str] = []

    def fetch(url: str, **kw: Any) -> Any:
        looked.append(url)
        return {"actors": []}

    svc = pn.NameDropService(tmp_path, fetch=fetch, clock=lambda: NOW)
    notes: list[str] = []
    people = [svc._person(f"Author {i}", i, 40, None, notes) for i in range(40)]
    assert len(looked) == pn.KEY_AUTHOR_LOOKUPS + 1
    assert people[20]["handles"]["bsky"]["evidence"] == "not looked up"
    assert len(notes) == 1


def test_fetch_public_page_connects_to_the_checked_address(monkeypatch: Any) -> None:
    seen: list[Any] = []

    def fake_conn(addr: Any, timeout: Any = None, *a: Any) -> Any:
        seen.append(addr)
        raise OSError("stop")

    monkeypatch.setattr(pn.socket, "create_connection", fake_conn)
    with pytest.raises(pn.FetchError):
        pn.fetch_public_page("http://news.example/a", resolve=lambda h: ["93.184.216.34"])
    assert seen == [("93.184.216.34", 80)]


def test_no_llm_and_no_network_reports_everything_unknown(tmp_path: Path) -> None:
    svc = pn.PulseService(tmp_path, llm_router=None, fetch=FakeFetch({}), bsky=pn.BskySession("", ""),
                          clock=lambda: NOW)
    out = svc.score({"text": "rust compile times on apple silicon"})
    assert out["heat"]["ratio"] is None
    assert all(not s["ok"] and s["count_24h"] is None for s in out["heat"]["sources"])
    assert out["boosts"] == [] and out["claims"] == []
    assert out["analysis_via"] == "no LLM configured"
    assert out["query"] == "rust compile times apple silicon"


def test_hn_and_wiki_counts(tmp_path: Path) -> None:
    def hn(url: str, _kw: Any) -> Any:
        filt = urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)["numericFilters"][0]
        return {"nbHits": 35 if "<=" in filt else 10}

    fetch = FakeFetch({
        "hn.algolia": hn,
        "opensearch": ["q", ["Rust (programming language)"], [], []],
        "pageviews": {"items": [{"views": v} for v in (100, 100, 100, 100, 100, 100, 100, 300)]},
    })
    hn_out = pn.heat_hn("rust", fetch, NOW)
    assert hn_out["count_24h"] == 10 and hn_out["avg_7d"] == 5.0
    wiki = pn.heat_wiki("rust", fetch, NOW)
    assert wiki["ok"] and wiki["count_24h"] == 300 and wiki["avg_7d"] == 100.0
    assert wiki["article"] == "Rust (programming language)"


def test_bsky_search_reports_capped_page(tmp_path: Path) -> None:
    fetch = FakeFetch({"createSession": {"accessJwt": "jwt", "did": "did:plc:me"},
                       "searchPosts": {"posts": [{}] * 100}})
    out = pn.heat_bsky("q", fetch, NOW, pn.BskySession("me.bsky.social", "app-pw"))
    assert out["ok"] and out["count_24h"] == 100 and out["capped"] is True and out["avg_7d"] is None


def test_areas_roundtrip_and_validation(tmp_path: Path) -> None:
    svc = pn.PulseService(tmp_path, fetch=FakeFetch({}), clock=lambda: NOW)
    assert svc.get_areas() == {"areas": [], "updated_at": None}
    saved = svc.put_areas({"areas": [" AI chips ", "ai chips", "Rust", ""]})
    assert saved["areas"] == ["AI chips", "Rust"]
    assert svc.get_areas()["areas"] == ["AI chips", "Rust"]
    with pytest.raises(ValueError):
        svc.put_areas({"areas": "nope"})


ARXIV_XML = """<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"><entry>
<title>A  Paper</title><author><name>Ada Lovelace</name></author><author><name>Alan Turing</name></author>
</entry></feed>"""


def test_namedrop_paper_verifies_only_cross_linked_handles(tmp_path: Path) -> None:
    fetch = FakeFetch({
        "export.arxiv.org": ARXIV_XML,
        "api.openalex.org/works/doi:10.48550/arXiv.2501.00001": {"authorships": [
            {"raw_author_name": "Ada Lovelace", "author": {"orcid": "https://orcid.org/0000-0001"}},
            {"raw_author_name": "Alan Turing", "author": {}}]},
        "pub.orcid.org/v3.0/0000-0001/researcher-urls": {"researcher-url": [
            {"url": {"value": "https://x.com/ada_l"}}]},
        "searchActors": lambda url, _kw: {"actors": [
            {"displayName": "Alan Turing", "handle": "alan.bsky.social", "description": ""}]
            if "Alan" in url else []},
    })
    svc = pn.NameDropService(tmp_path, fetch=fetch, clock=lambda: NOW)
    out = svc.resolve({"url": "https://arxiv.org/abs/2501.00001v2"})
    assert out["kind"] == "paper" and out["title"] == "A Paper"
    ada, alan = out["people"]
    assert ada["role"] == "1st author" and ada["handles"]["x"] == {
        "handle": "@ada_l", "status": "verified", "evidence": "listed on ORCID 0000-0001"}
    assert ada["handles"]["bsky"]["status"] == "unknown"
    assert alan["role"] == "last author"
    assert alan["handles"]["bsky"]["status"] == "likely"
    assert alan["handles"]["x"]["status"] == "unknown"
    assert out["taggable"] == {"x": 1, "bsky": 0}


def test_namedrop_bsky_thread_and_x_link(tmp_path: Path) -> None:
    fetch = FakeFetch({
        "getPostThread": {"thread": {"post": {
            "author": {"handle": "op.bsky.social", "displayName": "OP"},
            "record": {"text": "hi @friend", "facets": [{"features": [
                {"$type": "app.bsky.richtext.facet#mention", "did": "did:plc:friend"}]}]}}}},
        "getProfile": {"handle": "friend.bsky.social", "displayName": "Friend"},
    })
    svc = pn.NameDropService(tmp_path, fetch=fetch, clock=lambda: NOW)
    out = svc.resolve({"url": "https://bsky.app/profile/op.bsky.social/post/3abc"})
    assert [p["handles"]["bsky"]["handle"] for p in out["people"]] == ["op.bsky.social", "friend.bsky.social"]
    assert out["taggable"]["bsky"] == 2

    x = svc.resolve({"url": "https://x.com/someone/status/123"})
    assert x["people"][0]["handles"]["x"]["handle"] == "@someone"
    assert any("X API not used" in n for n in x["notes"])


def test_first_look_store_and_send(tmp_path: Path) -> None:
    sent_bodies: list[Any] = []

    def send(url: str, kw: Any) -> Any:
        sent_bodies.append(kw["body"])
        assert kw["headers"]["atproto-proxy"] == pn.BSKY_CHAT_PROXY
        return {"id": "msg1"}

    fetch = FakeFetch({
        "createSession": {"accessJwt": "jwt", "did": "did:plc:me"},
        "resolveHandle": {"did": "did:plc:pal"},
        "getConvoForMembers": {"convo": {"id": "c1"}},
        "sendMessage": send,
    })
    svc = pn.NameDropService(tmp_path, fetch=fetch, bsky=pn.BskySession("me.bsky.social", "pw"), clock=lambda: NOW)
    with pytest.raises(ValueError):
        svc.put_first_look({"people": [{"platform": "fax", "address": "1"}]})
    saved = svc.put_first_look({"people": [
        {"name": "Pal", "platform": "bsky", "address": "pal.bsky.social"},
        {"name": "X Pal", "platform": "x", "address": "@xpal"},
        {"name": "Mail", "platform": "email", "address": "m@example.com"},
        {"name": "Off", "platform": "x", "address": "@off", "on": False},
    ]})
    assert len(saved["people"]) == 4 and saved["bsky_dm_ready"] is True
    out = svc.send_first_look({"post_url": "https://x.com/me/status/1", "text": "new clip"})
    statuses = {r["name"]: r["status"] for r in out["results"]}
    assert statuses == {"Pal": "sent", "X Pal": "needs_you", "Mail": "needs_you"}
    assert out["summary"] == {"at": "2026-11-28T12:00:00Z", "sent": 1, "needs_you": 2, "failed": 0}
    assert sent_bodies[0]["message"]["text"] == "new clip\nhttps://x.com/me/status/1"
    assert svc.get_first_look()["last_send"]["sent"] == 1


def test_bsky_dm_without_app_password_fails_visibly(tmp_path: Path) -> None:
    svc = pn.NameDropService(tmp_path, fetch=FakeFetch({}), bsky=pn.BskySession("", ""), clock=lambda: NOW)
    svc.put_first_look({"people": [{"platform": "bsky", "address": "pal.bsky.social"}]})
    out = svc.send_first_look({"text": "hi"})
    assert out["results"][0]["status"] == "failed"
    assert "app password" in out["results"][0]["detail"]


def test_http_routes(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    import http.client

    from ota_server import create_ota_server

    monkeypatch.delenv("AIRCHECK_BSKY_HANDLE", raising=False)
    server = create_ota_server(host="127.0.0.1", port=0, bundles_root=tmp_path / "bundles",
                               preferences_path=tmp_path / "prefs.jsonl", quiet=True)
    import threading

    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()
    try:
        def call(method: str, path: str, body: Any | None = None) -> tuple[int, Any]:
            conn = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=5)
            data = json.dumps(body).encode() if body is not None else None
            conn.request(method, path, body=data, headers={"Content-Type": "application/json"} if data else {})
            r = conn.getresponse()
            return r.status, json.loads(r.read() or b"null")

        assert call("PUT", "/v1/pulse/areas", {"areas": ["AI chips"]})[1]["areas"] == ["AI chips"]
        assert call("GET", "/v1/pulse/areas")[1]["areas"] == ["AI chips"]
        status, body = call("PUT", "/v1/namedrop/first-look", {"people": [{"platform": "x", "address": "@a"}]})
        assert status == 200 and body["people"][0]["address"] == "@a"
        assert call("POST", "/v1/namedrop/resolve", {})[0] == 400
        assert call("PUT", "/v1/nope", {})[0] == 404
    finally:
        server.shutdown()
        server.server_close()


def test_cited_link_in_text_releases_boosts_without_news_lookup(tmp_path: Path) -> None:
    fetch = FakeFetch({"timelinevolraw": _gdelt_timeline})
    pages = {"https://news.example/story-1": "<p>Reports say Jensen Huang was caught cheating this week</p>"}
    llm = FakeLlm(ANALYSIS, json.dumps({"boosts": [{"kind": "hook", "text": "L", "why": "w"}]}))
    svc = pn.PulseService(tmp_path, llm_router=llm, fetch=fetch, bsky=pn.BskySession("", ""), clock=lambda: NOW,
                          fetch_citation=lambda u: pages[u])
    out = svc.score({"text": "jensen story https://news.example/story-1"})
    assert out["claims"][0]["status"] == "cited"
    assert out["claims"][0]["sources"][0]["url"] == "https://news.example/story-1"
    assert out["boosts_held"] is False and out["boosts"][0]["kind"] == "hook"
    assert not any("artlist" in url for url, _ in fetch.calls)


def test_page_that_only_names_the_person_does_not_release_an_allegation(tmp_path: Path) -> None:
    fetch = FakeFetch({"timelinevolraw": _gdelt_timeline, "artlist": {"articles": []}})
    llm = FakeLlm(ANALYSIS, json.dumps({"boosts": [{"kind": "hook", "text": "L", "why": "w"}]}))
    svc = pn.PulseService(tmp_path, llm_router=llm, fetch=fetch, bsky=pn.BskySession("", ""), clock=lambda: NOW,
                          fetch_citation=lambda u: "<p>Jensen Huang is the CEO of NVIDIA.</p>")
    out = svc.score({"text": "jensen story https://bio.example/jensen"})
    assert out["claims"][0]["status"] != "cited"
    assert out["boosts_held"] is True


def test_malformed_upstream_replies_are_unmeasured() -> None:
    bad_gdelt = FakeFetch({"timelinevolraw": {"timeline": [{"data": {"oops": 1}}]}})
    assert pn.heat_gdelt("q", bad_gdelt, NOW)["ok"] is False
    junk = FakeFetch({"timelinevolraw": {"timeline": [{"data": [{"x": 1}]}]}})
    assert pn.heat_gdelt("q", junk, NOW)["ok"] is False
    session = pn.BskySession("me.bsky.social", "app-pw")
    for reply in ({}, {"posts": None}, [1, 2]):
        f = FakeFetch({"createSession": {"accessJwt": "jwt", "did": "did:plc:me"}, "searchPosts": reply})
        assert pn.heat_bsky("q", f, NOW, session)["ok"] is False


def test_pds_fallback_is_not_cached() -> None:
    state = {"up": False}

    def fetch(url: str, **kw: Any) -> Any:
        if not state["up"]:
            raise pn.FetchError("HTTP 503")
        if "resolveHandle" in url:
            return {"did": "did:plc:abc"}
        return {"service": [{"id": "#atproto_pds", "serviceEndpoint": "https://pds.example.net"}]}

    session = pn.BskySession("me.example.net", "pw")
    assert session.pds(fetch) == pn.BSKY_PDS_URL
    state["up"] = True
    assert session.pds(fetch) == "https://pds.example.net/xrpc"


def test_unrelated_link_does_not_release_an_allegation(tmp_path: Path) -> None:
    fetch = FakeFetch({"timelinevolraw": _gdelt_timeline, "artlist": {"articles": []}})
    llm = FakeLlm(ANALYSIS, json.dumps({"boosts": [{"kind": "hook", "text": "He CHEATED", "why": "w"}]}))
    svc = pn.PulseService(tmp_path, llm_router=llm, fetch=fetch, bsky=pn.BskySession("", ""), clock=lambda: NOW,
                          fetch_citation=lambda u: "<p>my homepage about baking</p>")
    out = svc.score({"text": "jensen cheated lol https://me.example/"})
    assert out["claims"][0]["status"] == "unsourced"
    assert out["boosts_held"] is True and out["boosts"] == []


def test_malformed_llm_collections_degrade_to_empty(tmp_path: Path) -> None:
    llm = FakeLlm(json.dumps({"query": "q", "entities": "x", "claims": 1}), json.dumps({"boosts": {"a": 1}}))
    svc = pn.PulseService(tmp_path, llm_router=llm, fetch=FakeFetch({}), bsky=pn.BskySession("", ""),
                          clock=lambda: NOW)
    out = svc.score({"text": "some idea about chips"})
    assert out["entities"] == [] and out["claims"] == [] and out["boosts"] == []


def test_legacy_dotted_arxiv_id_is_recognised() -> None:
    m = pn._link_match("https://arxiv.org/abs/math.GT/0309136", pn._ARXIV_HOSTS, pn._ARXIV_RE)
    assert m is not None and m.group(1) == "math.GT/0309136"


def test_heat_ratio_averages_two_middle_values_and_empty_query_is_unmeasured(tmp_path: Path) -> None:
    ok = lambda c, a: {"ok": True, "count_24h": c, "avg_7d": a}
    assert pn.heat_ratio([ok(1, 2), ok(4, 2)]) == 1.25
    assert pn.heat_ratio([ok(1, 2), ok(4, 2), ok(3, 1)]) == 2.0
    fetch = FakeFetch({"hn.algolia": {"nbHits": 999}})
    svc = pn.PulseService(tmp_path, llm_router=None, fetch=fetch, bsky=pn.BskySession("", ""), clock=lambda: NOW)
    out = svc.score({"text": "this is really about you and me"})
    assert out["query"] == "" and out["heat"]["ratio"] is None
    assert all(not s["ok"] for s in out["heat"]["sources"]) and fetch.calls == []


def test_x_first_look_opens_that_persons_profile(tmp_path: Path) -> None:
    svc = pn.NameDropService(tmp_path, fetch=FakeFetch({}), bsky=pn.BskySession("", ""), clock=lambda: NOW)
    svc.put_first_look({"people": [{"platform": "x", "address": "@pal_one"}, {"platform": "x", "address": "pal2"}]})
    out = svc.send_first_look({"text": "hi"})
    assert [r["open_url"] for r in out["results"]] == ["https://x.com/pal_one", "https://x.com/pal2"]
    assert out["results"][0]["copy_text"] == "hi"



def test_citation_fetch_only_reaches_public_hosts() -> None:
    public = lambda host: ["93.184.216.34"]
    assert pn.is_public_http_url("https://news.example/a", resolve=public)
    for addr in ("127.0.0.1", "10.0.0.5", "192.168.1.2", "169.254.169.254", "::1", "fd00::1"):
        assert not pn.is_public_http_url("http://h.example/", resolve=lambda host, a=addr: [a])
    assert not pn.is_public_http_url("file:///etc/passwd", resolve=public)
    assert not pn.is_public_http_url("http://h.example/", resolve=lambda h: ["93.184.216.34", "127.0.0.1"])
    with pytest.raises(pn.FetchError):
        pn.fetch_public_page("http://127.0.0.1:8443/v1/namedrop/first-look")


def test_boosts_skipped_when_claim_analysis_fails(tmp_path: Path) -> None:
    class Flaky:
        calls = 0

        def complete(self, *a: Any, **k: Any) -> Any:
            Flaky.calls += 1
            if Flaky.calls == 1:
                raise RuntimeError("provider down")
            return SimpleNamespace(text=json.dumps({"boosts": [{"kind": "hook", "text": "John cheated", "why": "w"}]}),
                                   provider="p", model="m")

    svc = pn.PulseService(tmp_path, llm_router=Flaky(), fetch=FakeFetch({}), bsky=pn.BskySession("", ""),
                          clock=lambda: NOW)
    out = svc.score({"text": "John Doe cheated investors"})
    assert out["boosts"] == [] and out["boosts_via"].startswith("skipped")
    assert Flaky.calls == 1


def test_doi_link_decorations_and_non_latin_names(tmp_path: Path) -> None:
    seen: list[str] = []

    def work(url: str, _kw: Any) -> Any:
        seen.append(url)
        return {"title": "T", "authorships": [{"author": {"display_name": "张伟"}}, {"author": {"display_name": "王芳"}}]}

    fetch = FakeFetch({"api.openalex.org/works/doi:": work,
                       "searchActors": {"actors": [{"displayName": "李娜", "handle": "li.bsky.social"}]}})
    svc = pn.NameDropService(tmp_path, fetch=fetch, clock=lambda: NOW)
    out = svc.resolve({"url": "https://doi.org/10.1000/xyz123?utm_source=foo#top"})
    assert seen[0].endswith("doi:10.1000/xyz123")
    assert [p["name"] for p in out["people"]] == ["张伟", "王芳"]
    assert all(p["handles"]["bsky"]["status"] == "unknown" for p in out["people"])
    assert pn._norm_name("张伟") != pn._norm_name("王芳")


def test_resolve_requires_exact_link_hosts(tmp_path: Path) -> None:
    svc = pn.NameDropService(tmp_path, fetch=lambda url, **kw: (_ for _ in ()).throw(AssertionError(url)),
                             clock=lambda: NOW)
    for src in ("https://notx.com/alice/status/123", "https://x.com.evil.example/alice/status/123",
                "https://evil.example/x.com/alice/status/123"):
        out = svc.resolve({"url": src})
        assert out["kind"] == "text", src
        assert all(p["handles"]["x"]["handle"] != "@alice" for p in out["people"]), src
    x = svc.resolve({"url": "https://twitter.com/alice/status/123"})
    assert x["kind"] == "x_post"
    assert x["people"][0]["handles"]["x"]["status"] == "likely"
    assert x["taggable"]["x"] == 0


def test_bare_arxiv_link_still_resolves(tmp_path: Path) -> None:
    seen: list[str] = []

    def fetch(url: str, **kw: Any) -> Any:
        seen.append(url)
        raise pn.FetchError("offline")

    svc = pn.NameDropService(tmp_path, fetch=fetch, clock=lambda: NOW)
    out = svc.resolve({"text": "see arxiv.org/abs/2401.00001 for details"})
    assert out["kind"] == "paper"
    assert "2401.00001" in seen[0]
    assert svc.resolve({"text": "see notarxiv.org/abs/2401.00001"})["kind"] != "paper"


def test_orcid_in_bio_alone_is_only_likely(tmp_path: Path) -> None:
    def fetch(url: str, **kw: Any) -> Any:
        if "researcher-urls" in url:
            return {"researcher-url": []}
        if "searchActors" in url:
            return {"actors": [{"handle": "ada.bsky.social", "displayName": "Ada Lovelace",
                                "description": "orcid 0000-0001"}]}
        raise pn.FetchError("unexpected")

    svc = pn.NameDropService(tmp_path, fetch=fetch, clock=lambda: NOW)
    person = svc._person("Ada Lovelace", 0, 1, "0000-0001", [])
    assert person["handles"]["bsky"]["handle"] == "ada.bsky.social"
    assert person["handles"]["bsky"]["status"] == "likely"


def test_first_look_send_stops_starting_dms_at_the_deadline(tmp_path: Path, monkeypatch: Any) -> None:
    import threading as _th
    gate = _th.Event()
    started: list[str] = []
    monkeypatch.setattr(pn, "FIRST_LOOK_WORKERS", 1)
    monkeypatch.setattr(pn, "FIRST_LOOK_DEADLINE_SEC", 0.05)
    svc = pn.NameDropService(tmp_path, fetch=lambda url, **kw: {}, clock=lambda: NOW)

    def slow_dm(handle: str, message: str) -> dict[str, str]:
        started.append(handle)
        gate.wait(1)
        return {"status": "sent", "detail": "ok"}

    monkeypatch.setattr(svc, "_send_bsky_dm", slow_dm)
    svc.put_first_look({"people": [{"platform": "bsky", "address": "a.bsky.social"},
                                   {"platform": "bsky", "address": "b.bsky.social"}]})
    out = svc.send_first_look({"text": "hi"})
    gate.set()
    statuses = [r["status"] for r in out["results"]]
    assert statuses == ["sent", "failed"] and started == ["a.bsky.social"]
    assert "ran out of time" in out["results"][1]["detail"]
