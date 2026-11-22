import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createServer } from "node:http";
import { createInterface } from "node:readline";
import { once } from "node:events";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const bridgePath = join(here, "bridge.mjs");

test("the default local Buddy endpoint matches McpServer.PORT", async () => {
  const source = await readFile(bridgePath, "utf8");
  assert.match(source, /const BASE = new URL\\(process\\.env\\.BUDDY_URL \\|\\| "http:\\/\\/127\\.0\\.0\\.1:8766"\\)/);
});

test("stdio bridge forwards MCP requests with the configured Bearer token", async (t) => {
  const seen = [];
  const server = createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    seen.push({ path: req.url, method: body.method, authorization: req.headers.authorization });
    const result = body.method === "tools/list"
      ? { tools: [{ name: "read_screen", inputSchema: { type: "object", properties: {} } }] }
      : { protocolVersion: "2025-06-18", capabilities: { tools: {} }, serverInfo: { name: "buddy-test", version: "1" }, instructions: "test" };
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ jsonrpc: "2.0", id: 1, result }));
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  t.after(() => server.close());

  const address = server.address();
  const child = spawn(process.execPath, [bridgePath], {
    env: {
      ...process.env,
      BUDDY_URL: `http://127.0.0.1:${address.port}`,
      BUDDY_TOKEN: "test-token",
      XDG_CONFIG_HOME: join(here, ".test-config"),
    },
    stdio: ["pipe", "pipe", "pipe"],
  });
  t.after(() => { if (!child.killed) child.kill("SIGTERM"); });

  const lines = createInterface({ input: child.stdout });
  const iterator = lines[Symbol.asyncIterator]();
  const nextMessage = async () => {
    const timeout = new Promise((_, reject) => setTimeout(() => reject(new Error("bridge response timed out")), 3000));
    const line = await Promise.race([iterator.next(), timeout]);
    assert.equal(line.done, false, "bridge exited before responding");
    return JSON.parse(line.value);
  };

  child.stdin.write(JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2025-06-18" } }) + "\\n");
  const initialized = await nextMessage();
  assert.equal(initialized.id, 1);
  assert.equal(initialized.result.serverInfo.name, "buddy-phone-bridge");

  child.stdin.write(JSON.stringify({ jsonrpc: "2.0", id: 2, method: "tools/list", params: {} }) + "\\n");
  const listed = await nextMessage();
  assert.equal(listed.id, 2);
  assert.equal(listed.result.tools[0].name, "read_screen");

  assert.deepEqual(seen.map(x => x.method), ["initialize", "tools/list"]);
  assert.ok(seen.every(x => x.path === "/mcp" && x.authorization === "Bearer test-token"));
  child.stdin.end();
});
