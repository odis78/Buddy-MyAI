import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const bridgePath = process.argv[2];
const droidPath = process.argv[3];
if (!bridgePath || !droidPath) throw new Error("Bridge and DroidMCP entrypoint paths are required");
const root = fs.mkdtempSync(path.join(os.tmpdir(), "buddy-helper-probe-"));
fs.writeFileSync(path.join(root, "README.md"), "Buddy helper MCP probe file\n");
const baseEnv = Object.fromEntries(Object.entries(process.env).filter(([, value]) => typeof value === "string"));

async function inspect(name, scriptPath, envName, expectedTools, invoke) {
  const env = { ...baseEnv, [envName]: root };
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [scriptPath],
    cwd: root,
    env,
  });
  const client = new Client({ name: "buddy-" + name + "-probe", version: "1.0.0" });
  try {
    await client.connect(transport);
    const result = await client.listTools();
    const tools = result.tools ?? [];
    if (tools.length !== expectedTools) throw new Error(name + " expected " + expectedTools + " tools, got " + tools.length);
    const sample = await invoke(client);
    return { name, version: client.getServerVersion()?.version ?? null, tools: tools.length, catalogBytes: Buffer.byteLength(JSON.stringify(tools)), sampleOk: Boolean(sample) };
  } finally {
    await client.close();
  }
}
const bridge = await inspect("bridge", bridgePath, "TERMUX_BRIDGE_REA_ROOT", 6,
  async client => (await client.callTool({ name: "bridge_status", arguments: {} })).content?.length);
const droid = await inspect("droidmcp", droidPath, "DROIDMCP_ROOT", 5,
  async client => (await client.callTool({ name: "list_directory", arguments: { path: "." } })).content?.length);
if (bridge.version !== "0.1.3" || droid.version !== "0.4.0" || !bridge.sampleOk || !droid.sampleOk) {
  throw new Error("Helper MCP handshake or read-only smoke test failed");
}
console.log(JSON.stringify({ bridge, droid }));
