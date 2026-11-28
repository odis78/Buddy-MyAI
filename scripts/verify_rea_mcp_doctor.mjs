import { readFileSync } from "node:fs";

const path = process.argv[2];
if (!path) throw new Error("doctor report path is required");
const report = JSON.parse(readFileSync(path, "utf8"));
if (report.healthy !== true) throw new Error("REA MCP doctor reported unhealthy");
if (report.inventory?.tools?.expected !== report.inventory?.tools?.observed) {
  throw new Error("REA tool inventory mismatch");
}
if (report.inventory?.prompts?.expected !== report.inventory?.prompts?.observed) {
  throw new Error("REA prompt inventory mismatch");
}
console.log(JSON.stringify({
  healthy: report.healthy,
  tools: report.inventory.tools,
  prompts: report.inventory.prompts,
  server: report.server,
}));
