package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"testing"
)

func TestCapabilityProxyEnforcesConstantTimeSecretAndStripsPrefix(t *testing.T) {
	var seenPath string
	var seenQuery string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seenPath = r.URL.Path
		seenQuery = r.URL.RawQuery
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"status":"ok"}`))
	}))
	defer upstream.Close()

	u, err := url.Parse(upstream.URL)
	if err != nil {
		t.Fatal(err)
	}
	secret := "0123456789abcdef0123456789abcdef"
	proxy := httptest.NewServer(newCapabilityProxyHandler(u, secret))
	defer proxy.Close()

	// 1. Unauthenticated bare /health -> 403 Forbidden
	respBare, err := http.Get(proxy.URL + "/health")
	if err != nil {
		t.Fatal(err)
	}
	respBare.Body.Close()
	if respBare.StatusCode != http.StatusForbidden {
		t.Fatalf("expected 403 on bare /health, got %d", respBare.StatusCode)
	}

	// 2. Wrong secret -> 403 Forbidden
	respWrong, err := http.Get(proxy.URL + "/t/ffffffffffffffffffffffffffffffff/health")
	if err != nil {
		t.Fatal(err)
	}
	respWrong.Body.Close()
	if respWrong.StatusCode != http.StatusForbidden {
		t.Fatalf("expected 403 on wrong secret, got %d", respWrong.StatusCode)
	}

	// 3. Partial prefix collision (/t/<secret>extra) -> 403 Forbidden
	respCollision, err := http.Get(proxy.URL + "/t/" + secret + "extra/health")
	if err != nil {
		t.Fatal(err)
	}
	respCollision.Body.Close()
	if respCollision.StatusCode != http.StatusForbidden {
		t.Fatalf("expected 403 on prefix collision, got %d", respCollision.StatusCode)
	}

	// 4. Valid capability URL -> 200 OK and strips /t/<secret>
	respOK, err := http.Get(proxy.URL + "/t/" + secret + "/v1/renders/shotprev_abc.jpg?async=1")
	if err != nil {
		t.Fatal(err)
	}
	defer respOK.Body.Close()
	if respOK.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", respOK.StatusCode)
	}
	body, _ := io.ReadAll(respOK.Body)
	if string(body) != `{"status":"ok"}` {
		t.Fatalf("unexpected body: %s", string(body))
	}
	if seenPath != "/v1/renders/shotprev_abc.jpg" {
		t.Fatalf("expected stripped path /v1/renders/shotprev_abc.jpg, got %s", seenPath)
	}
	if seenQuery != "async=1" {
		t.Fatalf("expected query async=1, got %s", seenQuery)
	}
}

func TestLoadOrCreateTunnelSecretPersistsAcrossReads(t *testing.T) {
	dir := t.TempDir()
	secFile := filepath.Join(dir, "tunnel_secret")
	s1, err := loadOrCreateTunnelSecret(secFile)
	if err != nil {
		t.Fatal(err)
	}
	if len(s1) != 32 {
		t.Fatalf("expected 32-char hex secret, got %d (%s)", len(s1), s1)
	}
	s2, err := loadOrCreateTunnelSecret(secFile)
	if err != nil {
		t.Fatal(err)
	}
	if s1 != s2 {
		t.Fatalf("expected persisted secret %s == %s", s1, s2)
	}
	st, err := os.Stat(secFile)
	if err != nil {
		t.Fatal(err)
	}
	if st.Mode().Perm() != 0o600 {
		t.Fatalf("expected 0600 perms, got %o", st.Mode().Perm())
	}
}
