#!/usr/bin/env node
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createInterface } from "node:readline";

const [mode, script, root] = process.argv.slice(2);
assert.ok(["bridge", "droidmcp"].includes(mode), "mode must be bridge or droidmcp");
assert.ok(script && root, "script and root paths are required");

const env = { ...process.env };
if (mode === "bridge") {
  env.BUDDY_BRIDGE_REA_ROOT = root;
  env.BUDDY_ANDROID_PACKAGE = "app.buddy.myai.gt.test225";
} else {
  env.DROIDMCP_ROOT = root;
}

const child = spawn(process.execPath, [script], { env, stdio: ["pipe", "pipe", "pipe"] });
let stderr = "";
child.stderr.on("data", chunk => { stderr = (stderr + chunk.toString()).slice(-8000); });
const lines = createInterface({ input: child.stdout });
const pending = new Map();
lines.on("line", line => {
  let message;
  try { message = JSON.parse(line); } catch { return; }
  if (message?.id !== undefined && pending.has(message.id)) {
    const item = pending.get(message.id);
    pending.delete(message.id);
    clearTimeout(item.timer);
    item.resolve(message);
  }
});
const exited = new Promise(resolve => child.once("exit", (code, signal) => resolve({ code, signal })));

function request(id, method, params = {}) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`Timed out waiting for ${method}; stderr=${stderr}`));
    }, 5000);
    pending.set(id, { resolve, reject, timer });
    child.stdin.write(JSON.stringify({ jsonrpc: "2.0", id, method, params }) + "\n");
  });
}
function notify(method) {
  child.stdin.write(JSON.stringify({ jsonrpc: "2.0", method }) + "\n");
}
function textJson(response) {
  assert.ok(response?.result?.content?.[0]?.text, "MCP tool returned no text content");
  return JSON.parse(response.result.content[0].text);
}

try {
  const initialized = await request(1, "initialize", {
    protocolVersion: "2025-11-25",
    capabilities: {},
    clientInfo: { name: "buddy-test225-smoke", version: "1.0.0" }
  });
  assert.equal(initialized.jsonrpc, "2.0");
  notify("notifications/initialized");
  const listed = await request(2, "tools/list");
  const tools = listed?.result?.tools ?? [];
  const names = tools.map(tool => tool.name);

  if (mode === "bridge") {
    for (const name of ["bridge_status", "project_git_status", "diagnose_environment", "read_project_file", "run_allowlisted_command", "launch_android_app"]) {
      assert.ok(names.includes(name), `Bridge tool missing: ${name}`);
    }
    const status = textJson(await request(3, "tools/call", { name: "bridge_status", arguments: {} }));
    assert.equal(status.bridge, "buddy-bridge");
    assert.equal(status.rootExists, true);
    const commandBlocked = textJson(await request(4, "tools/call", { name: "run_allowlisted_command", arguments: { command_id: "node-version" } }));
    assert.equal(commandBlocked.blocked, true, "allowlisted command bypassed approval gate");
    const launchBlocked = textJson(await request(5, "tools/call", { name: "launch_android_app", arguments: {} }));
    assert.equal(launchBlocked.blocked, true, "Android launch bypassed approval gate");
    console.log("Buddy Bridge MCP stdio smoke passed: 6 tools, root available, both action gates enforced.");
  } else {
    for (const name of ["list_directory", "read_file", "search_files", "directory_tree", "get_file_info"]) {
      assert.ok(names.includes(name), `DroidMCP tool missing: ${name}`);
    }
    assert.equal(names.some(name => /write|delete|move|copy/i.test(name)), false, "DroidMCP exposed a mutating tool");
    const listing = textJson(await request(3, "tools/call", { name: "list_directory", arguments: { path: "." } }));
    assert.ok(JSON.stringify(listing).includes("smoke.txt"), "DroidMCP did not list the fixture file");
    const file = textJson(await request(4, "tools/call", { name: "read_file", arguments: { path: "smoke.txt" } }));
    assert.ok(JSON.stringify(file).includes("buddy-smoke-ok"), "DroidMCP did not read fixture content");
    console.log("DroidMCP MCP stdio smoke passed: 5 read-only tools, directory listing and file read verified.");
  }
} catch (error) {
  child.kill("SIGKILL");
  throw new Error(`${error.message}\nserver stderr:\n${stderr}`);
} finally {
  if (child.exitCode === null && child.signalCode === null) {
    child.kill("SIGTERM");
    const result = await Promise.race([exited, new Promise(resolve => setTimeout(() => resolve(null), 1500))]);
    if (result === null) child.kill("SIGKILL");
  }
}
