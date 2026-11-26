package main

import (
	"bufio"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	defaultOtaPort      = 8443
	defaultRemotionPort = 8444
	defaultProxyPort    = 8445
	defaultVideoPort    = 8451
	defaultVoicePort    = 8452
	defaultMlxPort      = 8080
	defaultLambdaTarget = "ubuntu@68.209.107.223"
	androidAppId        = "com.aistudio.aircheck.vqxkrm"
	androidMainActivity = "com.aistudio.aircheck.vqxkrm/com.example.MainActivity"
)

// rigEnvFile reads optional KEY=VALUE lines from the gitignored .aircheck-rig/env file so secrets
// (e.g. RYBBIT_API_KEY) reach supervised processes without being committed or put on the command
// line. Blank lines and #comments are ignored; a missing file is fine.
func rigEnvFile(repoRoot string) []string {
	data, err := os.ReadFile(filepath.Join(repoRoot, ".aircheck-rig", "env"))
	if err != nil {
		return nil
	}
	var out []string
	for _, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") || !strings.Contains(line, "=") {
			continue
		}
		out = append(out, line)
	}
	return out
}

var tryCloudflareRe = regexp.MustCompile(`https://[a-zA-Z0-9-]+\.trycloudflare\.com`)

type RigState struct {
	PID           int       `json:"pid"`
	StartedAt     time.Time `json:"started_at"`
	UpdatedAt     time.Time `json:"updated_at"`
	RepoRoot      string    `json:"repo_root"`
	LanURL        string    `json:"lan_url"`
	PublicURL     string    `json:"public_url,omitempty"`
	OtaHealthy    bool      `json:"ota_healthy"`
	RemotionOK    bool      `json:"remotion_healthy"`
	MlxHealthy    bool      `json:"mlx_healthy"`
	LambdaVideoOK bool      `json:"lambda_video_healthy"`
	LambdaVoiceOK bool      `json:"lambda_voice_healthy"`
	SyncedDevice  string    `json:"synced_device,omitempty"`
}

type Config struct {
	RepoRoot     string
	StateDir     string
	LogFile      string
	StateFile    string
	PidFile      string
	SecretFile   string
	PythonBin    string
	NodeBin      string
	Cloudflared  string
	AdbBin       string
	LambdaTarget string
	LambdaKey    string
	BundlesDir   string
	PrefsFile    string
	RendersDir   string
	AvatarPath   string
	EnableTunnel bool
	EnableLambda bool
	SyncPhone    bool
}

func main() {
	cmd := "run"
	args := os.Args[1:]
	if len(args) > 0 && !strings.HasPrefix(args[0], "-") {
		cmd = args[0]
		args = args[1:]
	}

	fs := flag.NewFlagSet("aircheck-rig", flag.ExitOnError)
	noTunnel := fs.Bool("no-tunnel", false, "Disable outbound 5G HTTPS cloudflared tunnel")
	noLambda := fs.Bool("no-lambda", false, "Disable Lambda GPU SSH tunnel (:8451/:8452)")
	noPhoneSync := fs.Bool("no-phone-sync", false, "Skip automatic ADB phone URL sync")
	lambdaHost := fs.String("lambda", envOr("AIRCHECK_LAMBDA_HOST", defaultLambdaTarget), "Lambda SSH user@host")
	_ = fs.Parse(args)

	cfg, err := buildConfig(!*noTunnel, !*noLambda, !*noPhoneSync, *lambdaHost)
	if err != nil {
		fatalf("config error: %v", err)
	}

	switch cmd {
	case "run", "up":
		if err := runSupervisor(cfg); err != nil {
			fatalf("%v", err)
		}
	case "start":
		if err := startDetached(cfg, os.Args[1:]); err != nil {
			fatalf("%v", err)
		}
	case "stop", "down":
		if err := stopSupervisor(cfg); err != nil {
			fatalf("%v", err)
		}
	case "status":
		os.Exit(printStatus(cfg))
	case "sync-phone":
		if err := syncPhoneNow(cfg); err != nil {
			fatalf("%v", err)
		}
	case "logs":
		if err := tailLogs(cfg); err != nil {
			fatalf("%v", err)
		}
	default:
		fmt.Fprintf(os.Stderr, "Usage: aircheck-rig [run|start|stop|status|sync-phone|logs] [--no-tunnel] [--no-lambda]\n")
		os.Exit(2)
	}
}

func buildConfig(enableTunnel, enableLambda, syncPhone bool, lambdaTarget string) (*Config, error) {
	root, err := discoverRepoRoot()
	if err != nil {
		return nil, err
	}
	home, _ := os.UserHomeDir()
	loadDotEnvFile(filepath.Join(home, ".zshenv"))
	loadDotEnvFile(filepath.Join(root, ".env"))

	stateDir := filepath.Join(root, ".aircheck-rig")
	if err := os.MkdirAll(filepath.Join(stateDir, "logs"), 0o755); err != nil {
		return nil, err
	}

	pythonBin := firstExecutable(
		os.Getenv("AIRCHECK_PYTHON"),
		filepath.Join(home, ".venv/bin/python"),
		"/opt/homebrew/bin/python3",
		"python3",
	)
	nodeBin := firstExecutable("/opt/homebrew/bin/node", "/usr/local/bin/node", "node")
	cfBin := firstExecutable("/opt/homebrew/bin/cloudflared", "/usr/local/bin/cloudflared", "cloudflared")
	adbBin := firstExecutable(
		"/opt/homebrew/share/android-commandlinetools/platform-tools/adb",
		filepath.Join(home, "Library/Android/sdk/platform-tools/adb"),
		"/opt/homebrew/bin/adb",
		"adb",
	)

	modelsBase := filepath.Join(home, "Downloads/voicestudio-models")
	bundlesDir := envOr("AIRCHECK_BUNDLES_DIR", filepath.Join(modelsBase, "bundles"))
	prefsFile := envOr("AIRCHECK_PREFS_FILE", filepath.Join(modelsBase, "preferences.jsonl"))
	rendersDir := envOr("AIRCHECK_RENDERS_DIR", filepath.Join(modelsBase, "renders"))
	avatarPath := envOr("AIRCHECK_AVATAR_PATH", filepath.Join(home, "Downloads/clone/images/avatar_ref.jpg"))
	_ = os.MkdirAll(bundlesDir, 0o755)
	_ = os.MkdirAll(rendersDir, 0o755)

	return &Config{
		RepoRoot:     root,
		StateDir:     stateDir,
		LogFile:      filepath.Join(stateDir, "logs", "supervisor.log"),
		StateFile:    filepath.Join(stateDir, "state.json"),
		PidFile:      filepath.Join(stateDir, "rig.pid"),
		SecretFile:   filepath.Join(stateDir, "tunnel_secret"),
		PythonBin:    pythonBin,
		NodeBin:      nodeBin,
		Cloudflared:  cfBin,
		AdbBin:       adbBin,
		LambdaTarget: lambdaTarget,
		LambdaKey:    envOr("AIRCHECK_LAMBDA_SSH_KEY", filepath.Join(home, ".ssh/nimbus_lambda")),
		BundlesDir:   bundlesDir,
		PrefsFile:    prefsFile,
		RendersDir:   rendersDir,
		AvatarPath:   avatarPath,
		EnableTunnel: enableTunnel,
		EnableLambda: enableLambda,
		SyncPhone:    syncPhone,
	}, nil
}

func discoverRepoRoot() (string, error) {
	if env := strings.TrimSpace(os.Getenv("AIRCHECK_ROOT")); env != "" {
		if isRepoRoot(env) {
			return filepath.Clean(env), nil
		}
	}
	if exe, err := os.Executable(); err == nil {
		if resolved, err := filepath.EvalSymlinks(exe); err == nil {
			exe = resolved
		}
		for dir := filepath.Dir(exe); dir != "/" && dir != "."; dir = filepath.Dir(dir) {
			if isRepoRoot(dir) {
				return dir, nil
			}
		}
	}
	if cwd, err := os.Getwd(); err == nil {
		for dir := cwd; dir != "/" && dir != "."; dir = filepath.Dir(dir) {
			if isRepoRoot(dir) {
				return dir, nil
			}
		}
	}
	return "", errors.New("could not locate Aircheck repository root (sync-trainer/home-daemon/ota_server.py)")
}

func isRepoRoot(dir string) bool {
	st, err := os.Stat(filepath.Join(dir, "sync-trainer", "home-daemon", "ota_server.py"))
	return err == nil && !st.IsDir()
}

func loadDotEnvFile(path string) {
	f, err := os.Open(path)
	if err != nil {
		return
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		line = strings.TrimSpace(strings.TrimPrefix(line, "export "))
		eq := strings.IndexByte(line, '=')
		if eq <= 0 {
			continue
		}
		key := strings.TrimSpace(line[:eq])
		val := strings.TrimSpace(line[eq+1:])
		if len(val) >= 2 && ((val[0] == '"' && val[len(val)-1] == '"') || (val[0] == '\'' && val[len(val)-1] == '\'')) {
			val = val[1 : len(val)-1]
		}
		if key != "" && os.Getenv(key) == "" && !strings.Contains(val, "$(") {
			_ = os.Setenv(key, val)
		}
	}
}

func loadOrCreateTunnelSecret(path string) (string, error) {
	if raw, err := os.ReadFile(path); err == nil {
		s := strings.TrimSpace(string(raw))
		if len(s) >= 32 {
			return s, nil
		}
	}
	buf := make([]byte, 16)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	secret := hex.EncodeToString(buf)
	if err := os.WriteFile(path, []byte(secret+"\n"), 0o600); err != nil {
		return "", err
	}
	return secret, nil
}

func newCapabilityProxyHandler(target *url.URL, secret string) http.Handler {
	proxy := httputil.NewSingleHostReverseProxy(target)
	origDirector := proxy.Director
	prefix := "/t/" + secret

	proxy.Director = func(req *http.Request) {
		origDirector(req)
		stripped := strings.TrimPrefix(req.URL.Path, prefix)
		if stripped == "" {
			stripped = "/"
		}
		req.URL.Path = stripped
		if req.URL.RawPath != "" {
			rawStripped := strings.TrimPrefix(req.URL.RawPath, prefix)
			if rawStripped == "" {
				rawStripped = "/"
			}
			req.URL.RawPath = rawStripped
		}
	}
	proxy.FlushInterval = 50 * time.Millisecond

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p := r.URL.Path
		if len(p) < len(prefix) {
			http.Error(w, `{"error":"forbidden"}`, http.StatusForbidden)
			return
		}
		candidate := p[:len(prefix)]
		if subtle.ConstantTimeCompare([]byte(candidate), []byte(prefix)) != 1 {
			http.Error(w, `{"error":"forbidden"}`, http.StatusForbidden)
			return
		}
		if len(p) > len(prefix) && p[len(prefix)] != '/' {
			http.Error(w, `{"error":"forbidden"}`, http.StatusForbidden)
			return
		}
		proxy.ServeHTTP(w, r)
	})
}

func startDetached(cfg *Config, origArgs []string) error {
	if pid, alive := readRunningPID(cfg.PidFile); alive {
		fmt.Printf("aircheck-rig is already running (pid %d)\n", pid)
		_ = printStatus(cfg)
		return nil
	}
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	childArgs := []string{"run"}
	for _, a := range origArgs {
		if a == "start" {
			continue
		}
		childArgs = append(childArgs, a)
	}
	logFh, err := os.OpenFile(cfg.LogFile, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return err
	}
	defer logFh.Close()

	cmd := exec.Command(exe, childArgs...)
	cmd.Dir = cfg.RepoRoot
	cmd.Stdout = logFh
	cmd.Stderr = logFh
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	if err := cmd.Start(); err != nil {
		return err
	}
	fmt.Printf("Started aircheck-rig supervisor (pid %d). Waiting for services", cmd.Process.Pid)

	deadline := time.Now().Add(25 * time.Second)
	for time.Now().Before(deadline) {
		time.Sleep(700 * time.Millisecond)
		fmt.Print(".")
		st, err := readState(cfg.StateFile)
		if err != nil {
			continue
		}
		tunnelReady := !cfg.EnableTunnel || st.PublicURL != "" || cfg.Cloudflared == ""
		if st.OtaHealthy && st.RemotionOK && tunnelReady {
			fmt.Println(" ready!")
			_ = printStatus(cfg)
			return nil
		}
	}
	fmt.Println()
	_ = printStatus(cfg)
	return nil
}

func stopSupervisor(cfg *Config) error {
	pid, alive := readRunningPID(cfg.PidFile)
	if !alive {
		cleanupPortListeners()
		fmt.Println("aircheck-rig stopped.")
		return nil
	}
	_ = syscall.Kill(pid, syscall.SIGTERM)
	deadline := time.Now().Add(6 * time.Second)
	for time.Now().Before(deadline) {
		if !pidAlive(pid) {
			break
		}
		time.Sleep(150 * time.Millisecond)
	}
	if pidAlive(pid) {
		_ = syscall.Kill(pid, syscall.SIGKILL)
	}
	_ = os.Remove(cfg.PidFile)
	fmt.Printf("Stopped aircheck-rig (pid %d).\n", pid)
	return nil
}

func runSupervisor(cfg *Config) error {
	if pid, alive := readRunningPID(cfg.PidFile); alive && pid != os.Getpid() {
		return fmt.Errorf("aircheck-rig already running (pid %d); run './aircheck-rig stop' first", pid)
	}
	cleanupPortListeners()

	if err := os.WriteFile(cfg.PidFile, []byte(strconv.Itoa(os.Getpid())+"\n"), 0o644); err != nil {
		return err
	}
	defer os.Remove(cfg.PidFile)

	secret, err := loadOrCreateTunnelSecret(cfg.SecretFile)
	if err != nil {
		return fmt.Errorf("tunnel secret: %w", err)
	}

	remotionServerJs := filepath.Join(cfg.RepoRoot, "backend", "remotion-worker", "dist", "server.js")
	if _, err := os.Stat(remotionServerJs); err != nil {
		logf("Building remotion-worker TypeScript bundle...")
		buildCmd := exec.Command("npm", "run", "build")
		buildCmd.Dir = filepath.Join(cfg.RepoRoot, "backend", "remotion-worker")
		buildCmd.Stdout = os.Stdout
		buildCmd.Stderr = os.Stderr
		if err := buildCmd.Run(); err != nil {
			return fmt.Errorf("remotion-worker build failed: %w", err)
		}
	}

	targetURL, _ := url.Parse(fmt.Sprintf("http://127.0.0.1:%d", defaultOtaPort))
	proxyLn, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", defaultProxyPort))
	if err != nil {
		return fmt.Errorf("capability proxy listen :%d: %w", defaultProxyPort, err)
	}
	proxySrv := &http.Server{
		Handler:           newCapabilityProxyHandler(targetURL, secret),
		ReadHeaderTimeout: 15 * time.Second,
	}
	go func() { _ = proxySrv.Serve(proxyLn) }()
	defer proxySrv.Close()

	stopCh := make(chan struct{})
	var stopOnce sync.Once
	closeStop := func() { stopOnce.Do(func() { close(stopCh) }) }

	sigCh := make(chan os.Signal, 2)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigCh
		logf("Shutting down aircheck-rig...")
		closeStop()
	}()

	var stateMu sync.Mutex
	lanIP := detectLanIPv4()
	state := RigState{
		PID:       os.Getpid(),
		StartedAt: time.Now(),
		UpdatedAt: time.Now(),
		RepoRoot:  cfg.RepoRoot,
		LanURL:    fmt.Sprintf("http://%s:%d", lanIP, defaultOtaPort),
	}
	saveState := func() {
		stateMu.Lock()
		state.UpdatedAt = time.Now()
		raw, _ := json.MarshalIndent(state, "", "  ")
		stateMu.Unlock()
		tmp := cfg.StateFile + ".tmp"
		if err := os.WriteFile(tmp, raw, 0o644); err == nil {
			_ = os.Rename(tmp, cfg.StateFile)
		}
	}
	saveState()

	var wg sync.WaitGroup

	// 1. Keep Mac awake while supervisor runs (`caffeinate -dimsu -w <pid>`).
	if caff := firstExecutable("/usr/bin/caffeinate", "caffeinate"); caff != "" {
		wg.Add(1)
		go superviseProcess(&wg, stopCh, "caffeinate", func() *exec.Cmd {
			return exec.Command(caff, "-dimsu", "-w", strconv.Itoa(os.Getpid()))
		}, nil)
	}

	// 2. Remotion worker (:8444).
	wg.Add(1)
	go superviseProcess(&wg, stopCh, "remotion-worker", func() *exec.Cmd {
		c := exec.Command(cfg.NodeBin, "dist/server.js")
		c.Dir = filepath.Join(cfg.RepoRoot, "backend", "remotion-worker")
		c.Env = append(os.Environ(),
			fmt.Sprintf("PORT=%d", defaultRemotionPort),
			"HOST=127.0.0.1",
		)
		return c
	}, nil)

	// 3. Lambda GPU SSH tunnel (:8451 video + :8452 voice).
	if cfg.EnableLambda {
		if _, err := os.Stat(cfg.LambdaKey); err == nil {
			wg.Add(1)
			go superviseProcess(&wg, stopCh, "lambda-tunnel", func() *exec.Cmd {
				return exec.Command(
					"ssh",
					"-o", "StrictHostKeyChecking=no",
					"-o", "ExitOnForwardFailure=yes",
					"-o", "ConnectTimeout=10",
					"-o", "ServerAliveInterval=15",
					"-o", "ServerAliveCountMax=3",
					"-i", cfg.LambdaKey,
					"-N",
					fmt.Sprintf("-L127.0.0.1:%d:127.0.0.1:%d", defaultVideoPort, defaultVideoPort),
					fmt.Sprintf("-L127.0.0.1:%d:127.0.0.1:%d", defaultVoicePort, defaultVoicePort),
					cfg.LambdaTarget,
				)
			}, nil)
		} else {
			logf("Lambda SSH key %s not found; skipping Lambda GPU tunnel", cfg.LambdaKey)
		}
	}

	// 4. Aircheck OTA & Studio Server (:8443).
	wg.Add(1)
	go superviseProcess(&wg, stopCh, "ota-server", func() *exec.Cmd {
		c := exec.Command(
			cfg.PythonBin,
			filepath.Join(cfg.RepoRoot, "sync-trainer", "home-daemon", "ota_server.py"),
			"--host", "0.0.0.0",
			"--port", strconv.Itoa(defaultOtaPort),
			"--bundles-dir", cfg.BundlesDir,
			"--preferences-file", cfg.PrefsFile,
			"--renders-dir", cfg.RendersDir,
			"--default-avatar", cfg.AvatarPath,
		)
		c.Dir = cfg.RepoRoot
		c.Env = append(append(os.Environ(), rigEnvFile(cfg.RepoRoot)...),
			fmt.Sprintf("REMOTION_WORKER_URL=http://127.0.0.1:%d", defaultRemotionPort),
			fmt.Sprintf("AIRCHECK_VIDEO_ENDPOINT=http://127.0.0.1:%d", defaultVideoPort),
			fmt.Sprintf("AIRCHECK_VOICE_ENDPOINT=http://127.0.0.1:%d", defaultVoicePort),
		)
		return c
	}, nil)

	// 5. Outbound 5G HTTPS tunnel (`cloudflared` -> capability proxy :8445 -> :8443).
	if cfg.EnableTunnel && cfg.Cloudflared != "" {
		wg.Add(1)
		go superviseProcess(&wg, stopCh, "cloudflared", func() *exec.Cmd {
			return exec.Command(
				cfg.Cloudflared,
				"tunnel",
				"--no-autoupdate",
				"--url", fmt.Sprintf("http://127.0.0.1:%d", defaultProxyPort),
			)
		}, func(line string) {
			if m := tryCloudflareRe.FindString(line); m != "" {
				full := m + "/t/" + secret
				stateMu.Lock()
				changed := state.PublicURL != full
				state.PublicURL = full
				stateMu.Unlock()
				if changed {
					logf("5G HTTPS Rig URL live: %s", full)
					saveState()
					if cfg.SyncPhone {
						if dev, err := pushRigEndpointToPhone(cfg.AdbBin, full); err == nil && dev != "" {
							stateMu.Lock()
							state.SyncedDevice = dev
							stateMu.Unlock()
							saveState()
							logf("Synced 5G HTTPS URL to phone (%s)", dev)
						}
					}
				}
			}
		})
	}

	// Health polling loop.
	wg.Add(1)
	go func() {
		defer wg.Done()
		ticker := time.NewTicker(2 * time.Second)
		defer ticker.Stop()
		lastPhoneSync := ""
		for {
			otaOK := checkHTTP(fmt.Sprintf("http://127.0.0.1:%d/health", defaultOtaPort))
			remOK := checkHTTP(fmt.Sprintf("http://127.0.0.1:%d/healthz", defaultRemotionPort))
			mlxOK := checkHTTP(fmt.Sprintf("http://127.0.0.1:%d/v1/models", defaultMlxPort))
			vidOK := checkHTTP(fmt.Sprintf("http://127.0.0.1:%d/health", defaultVideoPort))
			vocOK := checkHTTP(fmt.Sprintf("http://127.0.0.1:%d/health", defaultVoicePort))

			stateMu.Lock()
			state.LanURL = fmt.Sprintf("http://%s:%d", detectLanIPv4(), defaultOtaPort)
			state.OtaHealthy = otaOK
			state.RemotionOK = remOK
			state.MlxHealthy = mlxOK
			state.LambdaVideoOK = vidOK
			state.LambdaVoiceOK = vocOK
			pub := state.PublicURL
			lan := state.LanURL
			stateMu.Unlock()
			saveState()

			// Re-push the current rig URL whenever a phone (re)connects or the URL changes, so a phone
			// that was unplugged while the tunnel URL rotated never keeps a stale address.
			if cfg.SyncPhone && otaOK {
				target := pub
				if target == "" && !cfg.EnableTunnel {
					target = lan
				}
				if serial := firstOnlineDevice(cfg.AdbBin); serial != "" && target != "" && serial+"|"+target != lastPhoneSync {
					if dev, err := pushRigEndpointToPhone(cfg.AdbBin, target); err == nil && dev != "" {
						lastPhoneSync = serial + "|" + target
						stateMu.Lock()
						state.SyncedDevice = dev
						stateMu.Unlock()
						saveState()
						logf("Synced rig URL (%s) to phone (%s)", target, dev)
					}
				}
			}

			select {
			case <-stopCh:
				return
			case <-ticker.C:
			}
		}
	}()

	logf("Aircheck Rig running — LAN: %s (Ctrl-C to stop)", state.LanURL)
	<-stopCh
	wg.Wait()
	return nil
}

func superviseProcess(
	wg *sync.WaitGroup,
	stopCh <-chan struct{},
	name string,
	factory func() *exec.Cmd,
	onLine func(string),
) {
	defer wg.Done()
	backoff := time.Second
	for {
		select {
		case <-stopCh:
			return
		default:
		}

		cmd := factory()
		cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
		pr, pw := io.Pipe()
		cmd.Stdout = pw
		cmd.Stderr = pw

		started := time.Now()
		if err := cmd.Start(); err != nil {
			_ = pw.Close()
			_ = pr.Close()
			logf("[%s] start failed: %v", name, err)
			select {
			case <-stopCh:
				return
			case <-time.After(backoff):
				backoff = minDuration(backoff*2, 15*time.Second)
				continue
			}
		}

		var readerWg sync.WaitGroup
		readerWg.Add(1)
		go func() {
			defer readerWg.Done()
			sc := bufio.NewScanner(pr)
			sc.Buffer(make([]byte, 0, 64*1024), 512*1024)
			for sc.Scan() {
				line := sc.Text()
				if onLine != nil {
					onLine(line)
				}
				fmt.Fprintf(os.Stdout, "[%s] %s\n", name, line)
			}
		}()

		doneCh := make(chan error, 1)
		go func() {
			doneCh <- cmd.Wait()
			_ = pw.Close()
		}()

		select {
		case <-stopCh:
			killProcessGroup(cmd.Process.Pid)
			<-doneCh
			readerWg.Wait()
			_ = pr.Close()
			return
		case err := <-doneCh:
			readerWg.Wait()
			_ = pr.Close()
			if time.Since(started) > 20*time.Second {
				backoff = time.Second
			}
			logf("[%s] exited (%v); restarting in %s", name, err, backoff)
			select {
			case <-stopCh:
				return
			case <-time.After(backoff):
				backoff = minDuration(backoff*2, 15*time.Second)
			}
		}
	}
}

func killProcessGroup(pid int) {
	if pid <= 0 {
		return
	}
	_ = syscall.Kill(-pid, syscall.SIGTERM)
	deadline := time.Now().Add(2500 * time.Millisecond)
	for time.Now().Before(deadline) {
		if !pidAlive(pid) {
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
	_ = syscall.Kill(-pid, syscall.SIGKILL)
}

func cleanupPortListeners() {
	patterns := []string{
		"sync-trainer/home-daemon/ota_server.py",
		"remotion-worker.*dist/server.js",
		"127.0.0.1:8451:127.0.0.1:8451",
		"cloudflared tunnel.*127.0.0.1:8445",
	}
	for _, pat := range patterns {
		_ = exec.Command("pkill", "-f", pat).Run()
	}
	for _, port := range []int{defaultOtaPort, defaultRemotionPort, defaultProxyPort} {
		out, err := exec.Command("lsof", "-ti", fmt.Sprintf("tcp:%d", port), "-sTCP:LISTEN").Output()
		if err != nil {
			continue
		}
		for _, field := range strings.Fields(string(out)) {
			if p, err := strconv.Atoi(field); err == nil && p > 0 && p != os.Getpid() {
				_ = syscall.Kill(p, syscall.SIGTERM)
			}
		}
	}
	time.Sleep(300 * time.Millisecond)
}

func syncPhoneNow(cfg *Config) error {
	st, err := readState(cfg.StateFile)
	targetURL := ""
	if err == nil {
		if st.PublicURL != "" {
			targetURL = st.PublicURL
		} else {
			targetURL = st.LanURL
		}
	}
	if targetURL == "" {
		targetURL = fmt.Sprintf("http://%s:%d", detectLanIPv4(), defaultOtaPort)
	}
	dev, err := pushRigEndpointToPhone(cfg.AdbBin, targetURL)
	if err != nil {
		return err
	}
	fmt.Printf("Synced %s -> %s\n", targetURL, dev)
	return nil
}

func pushRigEndpointToPhone(adbBin, rigURL string) (string, error) {
	if adbBin == "" {
		return "", errors.New("adb not found")
	}
	serial := findOrConnectAndroidDevice(adbBin)
	if serial == "" {
		return "", errors.New("no connected Android phone found via USB or Wireless ADB")
	}
	_ = exec.Command(adbBin, "-s", serial, "reverse", fmt.Sprintf("tcp:%d", defaultOtaPort), fmt.Sprintf("tcp:%d", defaultOtaPort)).Run()
	remoteDir := fmt.Sprintf("/sdcard/Android/data/%s/files", androidAppId)
	escaped := strings.ReplaceAll(rigURL, "'", "")
	shellCmd := fmt.Sprintf(
		"mkdir -p %s && printf '%%s' '%s' > %s/rig_endpoint.txt && am start -n %s >/dev/null 2>&1 || true",
		remoteDir, escaped, remoteDir, androidMainActivity,
	)
	if out, err := exec.Command(adbBin, "-s", serial, "shell", shellCmd).CombinedOutput(); err != nil {
		return "", fmt.Errorf("adb shell failed: %v (%s)", err, strings.TrimSpace(string(out)))
	}
	return serial, nil
}

func findOrConnectAndroidDevice(adbBin string) string {
	if s := firstOnlineDevice(adbBin); s != "" {
		return s
	}
	out, err := exec.Command(adbBin, "mdns", "services").Output()
	if err == nil {
		for _, line := range strings.Split(string(out), "\n") {
			if strings.Contains(line, "_adb-tls-connect._tcp") {
				fields := strings.Fields(line)
				if len(fields) >= 3 {
					addr := fields[len(fields)-1]
					_ = exec.Command(adbBin, "connect", addr).Run()
				}
			}
		}
	}
	return firstOnlineDevice(adbBin)
}

func firstOnlineDevice(adbBin string) string {
	out, err := exec.Command(adbBin, "devices").Output()
	if err != nil {
		return ""
	}
	var fallback string
	for _, line := range strings.Split(string(out), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "List of devices") {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) >= 2 && fields[1] == "device" {
			if !strings.HasPrefix(fields[0], "emulator-") {
				return fields[0]
			}
			if fallback == "" {
				fallback = fields[0]
			}
		}
	}
	return fallback
}

func printStatus(cfg *Config) int {
	pid, alive := readRunningPID(cfg.PidFile)
	st, _ := readState(cfg.StateFile)
	otaOK := checkHTTP(fmt.Sprintf("http://127.0.0.1:%d/health", defaultOtaPort))
	remOK := checkHTTP(fmt.Sprintf("http://127.0.0.1:%d/healthz", defaultRemotionPort))
	mlxOK := checkHTTP(fmt.Sprintf("http://127.0.0.1:%d/v1/models", defaultMlxPort))
	vidOK := checkHTTP(fmt.Sprintf("http://127.0.0.1:%d/health", defaultVideoPort))
	vocOK := checkHTTP(fmt.Sprintf("http://127.0.0.1:%d/health", defaultVoicePort))

	lanURL := fmt.Sprintf("http://%s:%d", detectLanIPv4(), defaultOtaPort)
	pubURL := ""
	synced := ""
	if st != nil {
		if st.LanURL != "" {
			lanURL = st.LanURL
		}
		pubURL = st.PublicURL
		synced = st.SyncedDevice
	}

	supState := "stopped"
	if alive {
		supState = fmt.Sprintf("running (pid %d)", pid)
	}
	fmt.Printf("Supervisor:     %s\n", supState)
	fmt.Printf("OTA Server:     %s (%s)\n", okLabel(otaOK), lanURL)
	fmt.Printf("Remotion:       %s (http://127.0.0.1:%d)\n", okLabel(remOK), defaultRemotionPort)
	fmt.Printf("Local MLX:      %s (http://127.0.0.1:%d)\n", okLabel(mlxOK), defaultMlxPort)
	fmt.Printf("Lambda Video:   %s (http://127.0.0.1:%d)\n", okLabel(vidOK), defaultVideoPort)
	fmt.Printf("Lambda Voice:   %s (http://127.0.0.1:%d)\n", okLabel(vocOK), defaultVoicePort)
	if alive && pubURL != "" {
		fmt.Printf("5G Outside URL: %s\n", pubURL)
	} else {
		fmt.Printf("5G Outside URL: (not active)\n")
	}
	if synced != "" {
		fmt.Printf("Phone Synced:   %s\n", synced)
	}
	if otaOK && remOK {
		return 0
	}
	return 1
}

func tailLogs(cfg *Config) error {
	c := exec.Command("tail", "-n", "80", "-f", cfg.LogFile)
	c.Stdout = os.Stdout
	c.Stderr = os.Stderr
	return c.Run()
}

func checkHTTP(rawURL string) bool {
	client := &http.Client{
		Timeout:   1500 * time.Millisecond,
		Transport: &http.Transport{DisableKeepAlives: true},
	}
	resp, err := client.Get(rawURL)
	if err != nil {
		return false
	}
	_, _ = io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	return resp.StatusCode >= 200 && resp.StatusCode < 300
}

func detectLanIPv4() string {
	for _, ifName := range []string{"en0", "en1"} {
		out, err := exec.Command("ipconfig", "getifaddr", ifName).Output()
		if err == nil {
			if ip := strings.TrimSpace(string(out)); ip != "" {
				return ip
			}
		}
	}
	conn, err := net.Dial("udp", "8.8.8.8:80")
	if err == nil {
		defer conn.Close()
		if addr, ok := conn.LocalAddr().(*net.UDPAddr); ok {
			return addr.IP.String()
		}
	}
	return "127.0.0.1"
}

func readRunningPID(path string) (int, bool) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return 0, false
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(raw)))
	if err != nil || pid <= 0 {
		return 0, false
	}
	return pid, pidAlive(pid)
}

func pidAlive(pid int) bool {
	if pid <= 0 {
		return false
	}
	return syscall.Kill(pid, 0) == nil
}

func readState(path string) (*RigState, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var st RigState
	if err := json.Unmarshal(raw, &st); err != nil {
		return nil, err
	}
	return &st, nil
}

func firstExecutable(candidates ...string) string {
	for _, c := range candidates {
		c = strings.TrimSpace(c)
		if c == "" {
			continue
		}
		if strings.Contains(c, "/") {
			if st, err := os.Stat(c); err == nil && !st.IsDir() && st.Mode()&0o111 != 0 {
				return c
			}
			continue
		}
		if p, err := exec.LookPath(c); err == nil {
			return p
		}
	}
	return ""
}

func envOr(key, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return fallback
}

func okLabel(ok bool) string {
	if ok {
		return "ONLINE"
	}
	return "OFFLINE"
}

func minDuration(a, b time.Duration) time.Duration {
	if a < b {
		return a
	}
	return b
}

func logf(format string, args ...any) {
	fmt.Fprintf(os.Stdout, "%s [aircheck-rig] %s\n", time.Now().Format("15:04:05"), fmt.Sprintf(format, args...))
}

func fatalf(format string, args ...any) {
	fmt.Fprintf(os.Stderr, "aircheck-rig: "+format+"\n", args...)
	os.Exit(1)
}
