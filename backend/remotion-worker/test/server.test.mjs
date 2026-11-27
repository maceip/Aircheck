import test from "node:test";
import assert from "node:assert/strict";
import * as http from "node:http";
import * as net from "node:net";
import { createServer, resolveHost, resolvePort } from "../dist/server.js";

async function startEphemeralServer() {
  const server = createServer();
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const addr = server.address();
  assert.ok(addr && typeof addr === "object");
  return {
    server,
    baseUrl: `http://127.0.0.1:${addr.port}`,
    port: addr.port,
  };
}

test("resolvePort defaults to 8444 (never 8080) and resolveHost defaults to 127.0.0.1", () => {
  const prevPort = process.env.PORT;
  const prevRemotionPort = process.env.REMOTION_WORKER_PORT;
  const prevHost = process.env.HOST;
  const prevRemotionHost = process.env.REMOTION_WORKER_HOST;
  try {
    delete process.env.PORT;
    delete process.env.REMOTION_WORKER_PORT;
    delete process.env.HOST;
    delete process.env.REMOTION_WORKER_HOST;

    assert.equal(resolvePort(), 8444);
    assert.equal(resolveHost(), "127.0.0.1");

    process.env.PORT = "9001";
    assert.equal(resolvePort(), 9001);

    process.env.REMOTION_WORKER_PORT = "8449";
    assert.equal(resolvePort(), 8449);

    // Empty REMOTION_WORKER_PORT falls back to PORT
    process.env.REMOTION_WORKER_PORT = "";
    process.env.PORT = "8080";
    assert.equal(resolvePort(), 8080);

    process.env.REMOTION_WORKER_PORT = "   ";
    process.env.PORT = "8445";
    assert.equal(resolvePort(), 8445);

    process.env.HOST = "0.0.0.0";
    assert.equal(resolveHost(), "0.0.0.0");

    process.env.REMOTION_WORKER_HOST = "::1";
    assert.equal(resolveHost(), "::1");

    // Empty/whitespace REMOTION_WORKER_HOST falls back to HOST, and empty/whitespace HOST falls back to 127.0.0.1
    process.env.REMOTION_WORKER_HOST = "";
    process.env.HOST = "0.0.0.0";
    assert.equal(resolveHost(), "0.0.0.0");

    process.env.REMOTION_WORKER_HOST = "   ";
    process.env.HOST = "192.168.1.10";
    assert.equal(resolveHost(), "192.168.1.10");

    process.env.REMOTION_WORKER_HOST = "";
    process.env.HOST = "";
    assert.equal(resolveHost(), "127.0.0.1");

    process.env.REMOTION_WORKER_HOST = "  ";
    process.env.HOST = "   ";
    assert.equal(resolveHost(), "127.0.0.1");
  } finally {
    if (prevPort === undefined) delete process.env.PORT;
    else process.env.PORT = prevPort;
    if (prevRemotionPort === undefined) delete process.env.REMOTION_WORKER_PORT;
    else process.env.REMOTION_WORKER_PORT = prevRemotionPort;
    if (prevHost === undefined) delete process.env.HOST;
    else process.env.HOST = prevHost;
    if (prevRemotionHost === undefined) delete process.env.REMOTION_WORKER_HOST;
    else process.env.REMOTION_WORKER_HOST = prevRemotionHost;
  }
});

test("GET /healthz returns remotionVersion and bound port; unknown routes return JSON 404", async () => {
  const { server, baseUrl, port } = await startEphemeralServer();
  try {
    const healthRes = await fetch(`${baseUrl}/healthz?probe=1`);
    assert.equal(healthRes.status, 200);
    const healthJson = await healthRes.json();
    assert.equal(healthJson.ok, true);
    assert.equal(healthJson.port, port);
    assert.match(healthJson.remotionVersion, /^4\./);

    const notFoundRes = await fetch(`${baseUrl}/does-not-exist`);
    assert.equal(notFoundRes.status, 404);
    const notFoundJson = await notFoundRes.json();
    assert.equal(typeof notFoundJson.error, "string");
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test("POST /render enforces bearer auth when REMOTION_WORKER_TOKEN is set and rejects invalid payloads with JSON 400/413", async () => {
  const prevToken = process.env.REMOTION_WORKER_TOKEN;
  const { server, baseUrl } = await startEphemeralServer();
  try {
    process.env.REMOTION_WORKER_TOKEN = "secret-worker-token";

    const unauthRes = await fetch(`${baseUrl}/render`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}",
    });
    assert.equal(unauthRes.status, 401);
    const unauthJson = await unauthRes.json();
    assert.equal(unauthJson.error, "Unauthorized");

    delete process.env.REMOTION_WORKER_TOKEN;

    const validBase = {
      jobId: "job-123",
      outputLocalPath: "/tmp/aircheck-test-out.mp4",
      inputProps: {
        lipsyncUrl: "http://127.0.0.1:8443/v1/renders/lipsync.mp4",
        voiceUrl: "http://127.0.0.1:8443/v1/renders/voice.wav",
        words: [{ word: "Hello", startMs: 0, endMs: 250 }],
        beats: [],
        packaging: {
          chyronHeadline: "Test",
          chyronSubline: "",
          cornerBugText: "LIVE",
          showBrandBug: true,
        },
        templateId: "Newsroom916",
      },
    };

    const invalidCases = [
      { name: "invalid JSON", body: "{broken" },
      { name: "missing destination", body: JSON.stringify({ ...validBase, outputLocalPath: undefined }) },
      { name: "half-specified GCS", body: JSON.stringify({ ...validBase, outputLocalPath: undefined, bucketName: "bkt" }) },
      { name: "relative outputLocalPath", body: JSON.stringify({ ...validBase, outputLocalPath: "rel/out.mp4" }) },
      { name: "non-mp4 outputLocalPath", body: JSON.stringify({ ...validBase, outputLocalPath: "/tmp/out.mov" }) },
      { name: "empty jobId", body: JSON.stringify({ ...validBase, jobId: "!!!" }) },
      {
        name: "file:// lipsyncUrl",
        body: JSON.stringify({
          ...validBase,
          inputProps: { ...validBase.inputProps, lipsyncUrl: "file:///tmp/a.mp4" },
        }),
      },
      {
        name: "missing words and alignUrl",
        body: JSON.stringify({
          ...validBase,
          inputProps: { ...validBase.inputProps, words: undefined },
        }),
      },
      {
        name: "invalid templateId",
        body: JSON.stringify({
          ...validBase,
          inputProps: { ...validBase.inputProps, templateId: "Square11" },
        }),
      },
      {
        name: "invalid word timing",
        body: JSON.stringify({
          ...validBase,
          inputProps: { ...validBase.inputProps, words: [{ word: "Hi", startMs: 500, endMs: 100 }] },
        }),
      },
    ];

    for (const tc of invalidCases) {
      const res = await fetch(`${baseUrl}/render`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: tc.body,
      });
      assert.equal(res.status, 400, `Expected 400 for ${tc.name}, got ${res.status}`);
      const json = await res.json();
      assert.equal(typeof json.error, "string", `Expected error string for ${tc.name}`);
    }

    // > 5 MB body -> 413
    const hugeBody = "x".repeat(5 * 1024 * 1024 + 128);
    const largeRes = await fetch(`${baseUrl}/render`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: hugeBody,
    });
    assert.equal(largeRes.status, 413);
    const largeJson = await largeRes.json();
    assert.equal(typeof largeJson.error, "string");
  } finally {
    if (prevToken === undefined) delete process.env.REMOTION_WORKER_TOKEN;
    else process.env.REMOTION_WORKER_TOKEN = prevToken;
    await new Promise((resolve) => server.close(resolve));
  }
});

test("server handles malformed request targets (http://[bad, //evil) without crashing", async () => {
  const { server, baseUrl, port } = await startEphemeralServer();
  try {
    const sendRaw = (rawReq) =>
      new Promise((resolve, reject) => {
        const sock = net.connect(port, "127.0.0.1", () => {
          sock.write(rawReq);
        });
        let data = "";
        sock.setEncoding("utf8");
        sock.on("data", (chunk) => {
          data += chunk;
        });
        sock.on("end", () => resolve(data));
        sock.on("error", reject);
      });

    const badUrlRaw = await sendRaw("GET http://[bad HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");
    assert.match(badUrlRaw, /^HTTP\/1\.1 400 /);
    assert.match(badUrlRaw, /Invalid request URL/);

    const protoRelRaw = await sendRaw("GET //evil HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");
    assert.match(protoRelRaw, /^HTTP\/1\.1 404 /);

    // Server remains healthy and responsive
    const healthRes = await fetch(`${baseUrl}/healthz`);
    assert.equal(healthRes.status, 200);
    const healthJson = await healthRes.json();
    assert.equal(healthJson.ok, true);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test("POST /render rejects chunked >5 MB stream without Content-Length cleanly with HTTP 413 JSON", async () => {
  const { server, port } = await startEphemeralServer();
  try {
    const result = await new Promise((resolve, reject) => {
      const req = http.request(
        {
          hostname: "127.0.0.1",
          port,
          path: "/render",
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "Transfer-Encoding": "chunked",
          },
        },
        (res) => {
          let body = "";
          res.setEncoding("utf8");
          res.on("data", (c) => {
            body += c;
          });
          res.on("end", () => {
            resolve({ status: res.statusCode, body });
          });
        },
      );

      req.on("error", reject);

      const chunk = Buffer.alloc(512 * 1024, "a");
      for (let i = 0; i < 11; i++) {
        req.write(chunk);
      }
      req.end();
    });

    assert.equal(result.status, 413);
    const parsed = JSON.parse(result.body);
    assert.match(parsed.error, /exceeds .* byte limit/);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test("POST /render returns clean 500 JSON error when alignUrl returns null JSON", async () => {
  const alignServer = http.createServer((_req, res) => {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end("null");
  });
  await new Promise((resolve) => alignServer.listen(0, "127.0.0.1", resolve));
  const alignPort = alignServer.address().port;

  const { server, baseUrl } = await startEphemeralServer();
  try {
    const res = await fetch(`${baseUrl}/render`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        jobId: "align-null",
        outputLocalPath: "/tmp/aircheck-align-null.mp4",
        inputProps: {
          lipsyncUrl: `http://127.0.0.1:${alignPort}/lipsync.mp4`,
          voiceUrl: `http://127.0.0.1:${alignPort}/voice.wav`,
          alignUrl: `http://127.0.0.1:${alignPort}/align.json`,
          beats: [],
          templateId: "Newsroom916",
        },
      }),
    });
    assert.equal(res.status, 500);
    const json = await res.json();
    assert.match(json.error, /invalid JSON object payload/i);
  } finally {
    await new Promise((resolve) => server.close(resolve));
    await new Promise((resolve) => alignServer.close(resolve));
  }
});

test("POST /render Content-Length > 5 MB returns 413 cleanly across 20 fetch() iterations (keep-alive + Connection: close) and server stays healthy", async () => {
  const { server, baseUrl } = await startEphemeralServer();
  const hugeBody = "x".repeat(5 * 1024 * 1024 + 128);
  try {
    for (let i = 0; i < 10; i++) {
      const res = await fetch(`${baseUrl}/render`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: hugeBody,
      });
      assert.equal(res.status, 413, `keep-alive iter ${i} status=${res.status}`);
      const json = await res.json();
      assert.equal(typeof json.error, "string");
    }

    for (let i = 0; i < 10; i++) {
      const res = await fetch(`${baseUrl}/render`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Connection: "close",
        },
        body: hugeBody,
      });
      assert.equal(res.status, 413, `conn-close iter ${i} status=${res.status}`);
      const json = await res.json();
      assert.equal(typeof json.error, "string");
    }

    const healthRes = await fetch(`${baseUrl}/healthz`);
    assert.equal(healthRes.status, 200);
    const healthJson = await healthRes.json();
    assert.equal(healthJson.ok, true);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test("early HTTP rejections (401, 404, raw 400 bad URL) drain 5 MB + 128 B Connection: close bodies without ECONNRESET/EPIPE", async () => {
  const prevToken = process.env.REMOTION_WORKER_TOKEN;
  const { server, baseUrl, port } = await startEphemeralServer();
  const largePayloadBuf = Buffer.alloc(5 * 1024 * 1024 + 128, "x");
  const largePayloadStr = largePayloadBuf.toString("latin1");

  const sendRawTcp = (headerStr, bodyBuf) =>
    new Promise((resolve, reject) => {
      const sock = net.connect(port, "127.0.0.1", () => {
        sock.write(headerStr);
        sock.write(bodyBuf);
      });
      let data = "";
      sock.setEncoding("utf8");
      sock.on("data", (chunk) => {
        data += chunk;
      });
      sock.on("end", () => resolve(data));
      sock.on("error", reject);
    });

  try {
    process.env.REMOTION_WORKER_TOKEN = "secret-worker-token";
    for (let i = 0; i < 10; i++) {
      const res401 = await fetch(`${baseUrl}/render`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: "Bearer wrong-token",
          Connection: "close",
        },
        body: largePayloadBuf,
      });
      assert.equal(res401.status, 401, `401 iter ${i} status=${res401.status}`);
      const json401 = await res401.json();
      assert.equal(json401.error, "Unauthorized");
    }

    delete process.env.REMOTION_WORKER_TOKEN;

    for (let i = 0; i < 10; i++) {
      const res404 = await fetch(`${baseUrl}/unknown-route`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Connection: "close",
        },
        body: largePayloadStr,
      });
      assert.equal(res404.status, 404, `404 iter ${i} status=${res404.status}`);
      const json404 = await res404.json();
      assert.equal(json404.error, "Not found");
    }

    const rawHeader =
      `POST http://[bad HTTP/1.1\r\n` +
      `Host: 127.0.0.1\r\n` +
      `Content-Type: application/json\r\n` +
      `Content-Length: ${largePayloadBuf.length}\r\n` +
      `Connection: close\r\n\r\n`;
    for (let i = 0; i < 10; i++) {
      const rawResp = await sendRawTcp(rawHeader, largePayloadBuf);
      assert.match(rawResp, /^HTTP\/1\.1 400 /, `raw 400 iter ${i} got: ${rawResp.slice(0, 80)}`);
      assert.match(rawResp, /Invalid request URL/);
    }

    const healthRes = await fetch(`${baseUrl}/healthz`);
    assert.equal(healthRes.status, 200);
    const healthJson = await healthRes.json();
    assert.equal(healthJson.ok, true);
  } finally {
    if (prevToken === undefined) delete process.env.REMOTION_WORKER_TOKEN;
    else process.env.REMOTION_WORKER_TOKEN = prevToken;
    await new Promise((resolve) => server.close(resolve));
  }
});

test("POST /render rejects a single > 32 MiB chunk immediately with HTTP 413 JSON", async () => {
  const { server, port } = await startEphemeralServer();
  try {
    const startMs = Date.now();
    const result = await new Promise((resolve, reject) => {
      const req = http.request(
        {
          hostname: "127.0.0.1",
          port,
          path: "/render",
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "Transfer-Encoding": "chunked",
          },
        },
        (res) => {
          let body = "";
          res.setEncoding("utf8");
          res.on("data", (c) => {
            body += c;
          });
          res.on("end", () => {
            resolve({ status: res.statusCode, body });
          });
        },
      );

      req.on("error", reject);

      const oversizedChunk = Buffer.alloc(32 * 1024 * 1024 + 64, "z");
      req.end(oversizedChunk);
    });

    const elapsedMs = Date.now() - startMs;
    assert.equal(result.status, 413);
    assert.ok(elapsedMs < 1800, `Expected immediate 413 before 2000ms drain timeout, took ${elapsedMs}ms`);
    const parsed = JSON.parse(result.body);
    assert.match(parsed.error, /exceeds .* byte limit/);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test("POST /render validates packaging.accentHex and packaging.brollFraming strictly", async () => {
  const { server, port } = await startEphemeralServer();
  const baseUrl = `http://127.0.0.1:${port}`;
  try {
    const badAccentRes = await fetch(`${baseUrl}/render`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        jobId: "accent-test",
        outputLocalPath: "/tmp/out.mp4",
        inputProps: {
          lipsyncUrl: "http://127.0.0.1:9/a.mp4",
          voiceUrl: "http://127.0.0.1:9/a.wav",
          words: [{ word: "Live", startMs: 0, endMs: 400 }],
          packaging: { accentHex: "not-a-hex" },
        },
      }),
    });
    assert.equal(badAccentRes.status, 400);
    assert.match((await badAccentRes.json()).error, /accentHex/);

    const badFramingRes = await fetch(`${baseUrl}/render`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        jobId: "framing-test",
        outputLocalPath: "/tmp/out.mp4",
        inputProps: {
          lipsyncUrl: "http://127.0.0.1:9/a.mp4",
          voiceUrl: "http://127.0.0.1:9/a.wav",
          words: [{ word: "Live", startMs: 0, endMs: 400 }],
          packaging: { accentHex: "#3A86F2", brollFraming: "diagonal" },
        },
      }),
    });
    assert.equal(badFramingRes.status, 400);
    assert.match((await badFramingRes.json()).error, /brollFraming/);

    for (const field of ["showCaptions", "punchIn", "showLowerThird"]) {
      const badToggleRes = await fetch(`${baseUrl}/render`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          jobId: "toggle-test",
          outputLocalPath: "/tmp/out.mp4",
          inputProps: {
            lipsyncUrl: "http://127.0.0.1:9/a.mp4",
            voiceUrl: "http://127.0.0.1:9/a.wav",
            words: [{ word: "Live", startMs: 0, endMs: 400 }],
            packaging: { [field]: "yes" },
          },
        }),
      });
      assert.equal(badToggleRes.status, 400);
      assert.match((await badToggleRes.json()).error, new RegExp(field));
    }
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});
