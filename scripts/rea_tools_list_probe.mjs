import { Client } from "@modelcontextprotocol/client";
import { StdioClientTransport } from "@modelcontextprotocol/client/stdio";

const serverScript = process.argv[2];
if (!serverScript) throw new Error("REA MCP entrypoint path is required");
const env = Object.fromEntries(Object.entries(process.env).filter(([, value]) => typeof value === "string"));
Object.assign(env, {
  REA_MCP_INPUT_SCHEMA_PROFILE: "compact",
  REA_MCP_OUTPUT_SCHEMA_PROFILE: "off",
  REA_MCP_MAX_RESPONSE_BYTES: "10485760",
});
const transport = new StdioClientTransport({
  command: process.execPath,
  args: [serverScript, "mcp"],
  cwd: process.cwd(),
  env,
});
const client = new Client({ name: "buddy-rea-catalog-probe", version: "1.0.0" });
try {
  await client.connect(transport);
  const result = await client.listTools();
  const tools = result.tools ?? [];
  const bytes = Buffer.byteLength(JSON.stringify(tools), "utf8");
  const largestToolBytes = tools.reduce((largest, tool) =>
    Math.max(largest, Buffer.byteLength(JSON.stringify(tool), "utf8")), 0);
  if (tools.length !== 207) throw new Error(`Expected 207 public REA 6.5.0 tools, got ${tools.length}`);
  if (bytes > 2 * 1024 * 1024) throw new Error(`Compact REA catalog too large: ${bytes} bytes`);
  console.log(JSON.stringify({ tools: tools.length, catalogBytes: bytes, largestToolBytes }));
} finally {
  await client.close();
}
