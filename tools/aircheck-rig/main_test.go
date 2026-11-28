package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
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

func TestOtaServerArgsPassesMediaConfigOnlyWhenPresent(t *testing.T) {
	stateDir := t.TempDir()
	cfg := &Config{RepoRoot: "/repo", StateDir: stateDir, BundlesDir: "/b", PrefsFile: "/p", RendersDir: "/r", AvatarPath: "/a"}

	without := strings.Join(otaServerArgs(cfg), " ")
	if strings.Contains(without, "--media-config") {
		t.Fatalf("media config must not be passed when the file is absent: %s", without)
	}
	if !strings.Contains(without, "--renders-dir /r") || !strings.Contains(without, "--port 8443") {
		t.Fatalf("unexpected daemon args: %s", without)
	}

	mediaCfg := filepath.Join(stateDir, mediaConfigRel)
	if err := os.WriteFile(mediaCfg, []byte(`{"schema_version":1}`), 0o600); err != nil {
		t.Fatal(err)
	}
	with := otaServerArgs(cfg)
	if with[len(with)-2] != "--media-config" || with[len(with)-1] != mediaCfg {
		t.Fatalf("media config not passed: %v", with)
	}
}

func TestLaunchAgentPlistRunsSupervisorWithFlagsAndKeepAlive(t *testing.T) {
	plist := launchAgentPlist("/x/air & check/aircheck-rig", "/repo", "/repo/.aircheck-rig/logs/supervisor.log", []string{"--no-tunnel"})
	for _, want := range []string{
		"<string>/x/air &amp; check/aircheck-rig</string>",
		"<string>run</string>",
		"<string>--no-tunnel</string>",
		"<key>KeepAlive</key><true/>",
		"<key>RunAtLoad</key><true/>",
		"<key>" + detachedEnv + "</key><string>1</string>",
		"<string>" + launchdLabel + "</string>",
	} {
		if !strings.Contains(plist, want) {
			t.Fatalf("plist missing %q:\n%s", want, plist)
		}
	}
}

func TestRotateLogKeepsOnePreviousFile(t *testing.T) {
	dir := t.TempDir()
	logPath := filepath.Join(dir, "supervisor.log")
	if err := os.WriteFile(logPath, make([]byte, maxLogBytes+1), 0o644); err != nil {
		t.Fatal(err)
	}
	rotateLog(logPath)
	if _, err := os.Stat(logPath + ".1"); err != nil {
		t.Fatalf("oversized log was not rotated: %v", err)
	}
	if _, err := os.Stat(logPath); !os.IsNotExist(err) {
		t.Fatalf("current log should be gone after rotation")
	}
	small := filepath.Join(dir, "small.log")
	_ = os.WriteFile(small, []byte("x"), 0o644)
	rotateLog(small)
	if _, err := os.Stat(small); err != nil {
		t.Fatalf("small log must not be rotated")
	}
}

func TestLoadRigTokenGeneratesOnceAndHonoursEnv(t *testing.T) {
	root := t.TempDir()
	if err := os.MkdirAll(filepath.Join(root, ".aircheck-rig"), 0o755); err != nil {
		t.Fatal(err)
	}
	cfg := &Config{RepoRoot: root, TokenFile: filepath.Join(root, ".aircheck-rig", "rig_token")}
	t.Setenv("AIRCHECK_RIG_TOKEN", "")
	first, err := loadRigToken(cfg)
	if err != nil || len(first) < 32 {
		t.Fatalf("generated token = %q, %v", first, err)
	}
	again, _ := loadRigToken(cfg)
	if again != first {
		t.Fatalf("token changed between loads: %q vs %q", first, again)
	}
	if info, err := os.Stat(cfg.TokenFile); err != nil || info.Mode().Perm() != 0o600 {
		t.Fatalf("token file mode: %v %v", info, err)
	}
	if err := os.WriteFile(filepath.Join(root, ".aircheck-rig", "env"), []byte("AIRCHECK_RIG_TOKEN=abcdefghijklmnop-123\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if got, _ := loadRigToken(cfg); got != "abcdefghijklmnop-123" {
		t.Fatalf("env file token not used: %q", got)
	}
	t.Setenv("AIRCHECK_RIG_TOKEN", "x'; rm -rf /")
	os.Remove(filepath.Join(root, ".aircheck-rig", "env"))
	if _, err := loadRigToken(cfg); err == nil {
		t.Fatal("unsafe token accepted")
	}
}
