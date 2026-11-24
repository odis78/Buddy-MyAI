#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";

const VERSION = "0.4.0";
const ROOT = path.resolve(process.env.DROIDMCP_ROOT || "/root/rea-source");
const MAX_READ_BYTES = 256 * 1024;
const MAX_SEARCH_FILE_BYTES = 512 * 1024;
const MAX_RESULTS = 100;
const MAX_TREE_ENTRIES = 500;
const SENSITIVE = /(^|\/)(\.env([^/]*)?|id_rsa|id_ed25519|credentials?(\.json)?|secrets?)(\/|$)/i;
const SKIP_DIRS = new Set([".git", "node_modules", ".cache", ".npm", ".ssh"]);

function withinRoot(candidate) {
  const rel = path.relative(ROOT, candidate);
  return rel === "" || (!rel.startsWith(".." + path.sep) && rel !== ".." && !path.isAbsolute(rel));
}
function resolvePath(relPath, allowRoot = true) {
  if (typeof relPath !== "string" || path.isAbsolute(relPath)) throw new Error("Use a relative path inside the allowed root.");
  const normalized = path.normalize(relPath || ".");
  if (normalized.split(path.sep).includes("..") || SENSITIVE.test(normalized)) throw new Error("Path is blocked by the read-only safety policy.");
  if (!allowRoot && (normalized === "." || normalized === "")) throw new Error("A file path is required.");
  const full = path.resolve(ROOT, normalized);
  if (!withinRoot(full)) throw new Error("Path escapes the configured read-only root.");
  const rootReal = fs.realpathSync(ROOT);
  const fullReal = fs.realpathSync(full);
  if (!withinRoot(fullReal) || !withinRoot(rootReal)) throw new Error("Resolved path escapes the configured read-only root.");
  const stat = fs.lstatSync(full);
  if (stat.isSymbolicLink()) throw new Error("Symbolic links are not followed.");
  return { full: fullReal, stat };
}
function textResult(value) {
  return { content: [{ type: "text", text: JSON.stringify(value, null, 2) }] };
}
function tool(name, description, properties = {}, required = []) {
  return { name, description, inputSchema: { type: "object", properties, required, additionalProperties: false } };
}
const TOOLS = [
  tool("list_directory", "List immediate entries in a directory under the configured read-only root.", {
    path: { type: "string", description: "Relative directory path; use . for the allowed root." }
  }, ["path"]),
  tool("read_file", "Read a small UTF-8 regular file under the configured read-only root. Symlinks and sensitive files are blocked.", {
    path: { type: "string", description: "Relative file path." }
  }, ["path"]),
  tool("search_files", "Search file names and bounded UTF-8 text contents recursively under the allowed root.", {
    query: { type: "string", description: "Case-insensitive text to find." },
    path: { type: "string", description: "Optional relative starting directory; defaults to ." },
    max_results: { type: "integer", minimum: 1, maximum: 100, description: "Maximum number of matches." }
  }, ["query"]),
  tool("directory_tree", "Return a bounded read-only directory tree; hidden secret files and symlinks are omitted.", {
    path: { type: "string", description: "Relative starting directory; defaults to ." },
    max_depth: { type: "integer", minimum: 1, maximum: 6, description: "Maximum recursion depth." }
  }),
  tool("get_file_info", "Return metadata for one non-sensitive regular file or directory under the allowed root.", {
    path: { type: "string", description: "Relative file or directory path." }
  }, ["path"])
];

function listDirectory(args) {
  const { full, stat } = resolvePath(args.path ?? ".");
  if (!stat.isDirectory()) throw new Error("Path is not a directory.");
  const entries = [];
  for (const entry of fs.readdirSync(full, { withFileTypes: true })) {
    if (SENSITIVE.test(entry.name) || entry.isSymbolicLink()) continue;
    const fullEntry = path.join(full, entry.name);
    const info = fs.lstatSync(fullEntry);
    entries.push({
      name: entry.name,
      type: info.isDirectory() ? "directory" : info.isFile() ? "file" : "other",
      size: info.isFile() ? info.size : null,
      modifiedAt: info.mtime.toISOString()
    });
  }
  entries.sort((a, b) => a.type.localeCompare(b.type) || a.name.localeCompare(b.name));
  return { root: ROOT, path: path.relative(ROOT, full) || ".", entries };
}
function readFile(args) {
  const { full, stat } = resolvePath(args.path, false);
  if (!stat.isFile()) throw new Error("Only regular files can be read.");
  if (stat.size > MAX_READ_BYTES) throw new Error("File exceeds " + MAX_READ_BYTES + " byte read limit.");
  return { path: path.relative(ROOT, full), bytes: stat.size, content: fs.readFileSync(full, "utf8") };
}
function directoryTree(args) {
  const { full, stat } = resolvePath(args.path ?? ".");
  if (!stat.isDirectory()) throw new Error("Path is not a directory.");
  const maxDepth = Math.min(6, Math.max(1, Number.isInteger(args.max_depth) ? args.max_depth : 4));
  const entries = [];
  function walk(dir, depth) {
    if (entries.length >= MAX_TREE_ENTRIES || depth > maxDepth) return;
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      if (entries.length >= MAX_TREE_ENTRIES || SENSITIVE.test(entry.name) || entry.isSymbolicLink()) continue;
      const fullEntry = path.join(dir, entry.name);
      const info = fs.lstatSync(fullEntry);
      const relative = path.relative(ROOT, fullEntry);
      entries.push({ path: relative, type: info.isDirectory() ? "directory" : info.isFile() ? "file" : "other", size: info.isFile() ? info.size : null });
      if (info.isDirectory() && !SKIP_DIRS.has(entry.name)) walk(fullEntry, depth + 1);
    }
  }
  walk(full, 1);
  return { root: ROOT, path: path.relative(ROOT, full) || ".", maxDepth, truncated: entries.length >= MAX_TREE_ENTRIES, entries };
}
function searchFiles(args) {
  const query = typeof args.query === "string" ? args.query.trim().toLowerCase() : "";
  if (!query) throw new Error("A non-empty search query is required.");
  const start = resolvePath(args.path ?? ".");
  if (!start.stat.isDirectory()) throw new Error("Search path is not a directory.");
  const limit = Math.min(MAX_RESULTS, Math.max(1, Number.isInteger(args.max_results) ? args.max_results : 50));
  const matches = [];
  function walk(dir, depth) {
    if (matches.length >= limit || depth > 8) return;
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      if (matches.length >= limit || SENSITIVE.test(entry.name) || entry.isSymbolicLink()) continue;
      if (entry.isDirectory()) {
        if (!SKIP_DIRS.has(entry.name)) walk(path.join(dir, entry.name), depth + 1);
        continue;
      }
      if (!entry.isFile()) continue;
      const full = path.join(dir, entry.name);
      const info = fs.lstatSync(full);
      const relative = path.relative(ROOT, full);
      if (entry.name.toLowerCase().includes(query)) {
        matches.push({ path: relative, match: "file_name" });
        continue;
      }
      if (info.size > MAX_SEARCH_FILE_BYTES) continue;
      try {
        const content = fs.readFileSync(full, "utf8");
        const index = content.toLowerCase().indexOf(query);
        if (index >= 0) matches.push({ path: relative, match: "file_content", preview: content.slice(Math.max(0, index - 80), Math.min(content.length, index + query.length + 160)) });
      } catch { /* Binary or unreadable files are skipped. */ }
    }
  }
  walk(start.full, 1);
  return { root: ROOT, query, matches, truncated: matches.length >= limit };
}
function fileInfo(args) {
  const { full, stat } = resolvePath(args.path, false);
  return {
    path: path.relative(ROOT, full),
    type: stat.isDirectory() ? "directory" : stat.isFile() ? "file" : "other",
    size: stat.isFile() ? stat.size : null,
    modifiedAt: stat.mtime.toISOString(),
    mode: (stat.mode & 0o777).toString(8)
  };
}
async function callTool(name, args = {}) {
  try {
    switch (name) {
      case "list_directory": return textResult(listDirectory(args));
      case "read_file": return textResult(readFile(args));
      case "search_files": return textResult(searchFiles(args));
      case "directory_tree": return textResult(directoryTree(args));
      case "get_file_info": return textResult(fileInfo(args));
      default: throw new Error("Unknown tool: " + name);
    }
  } catch (error) {
    return textResult({ ok: false, error: error.message });
  }
}
function send(message) { process.stdout.write(JSON.stringify(message) + "\n"); }
function handle(message) {
  if (!message || typeof message !== "object") return;
  if (message.method === "notifications/initialized" || message.method?.startsWith("notifications/")) return;
  if (message.method === "initialize") {
    return send({ jsonrpc: "2.0", id: message.id, result: {
      protocolVersion: message.params?.protocolVersion || "2024-11-05",
      capabilities: { tools: {} },
      serverInfo: { name: "droidmcp-filesystem", version: VERSION }
    }});
  }
  if (message.method === "ping") return send({ jsonrpc: "2.0", id: message.id, result: {} });
  if (message.method === "tools/list") return send({ jsonrpc: "2.0", id: message.id, result: { tools: TOOLS } });
  if (message.method === "tools/call") {
    callTool(message.params?.name, message.params?.arguments || {})
      .then(result => send({ jsonrpc: "2.0", id: message.id, result }))
      .catch(error => send({ jsonrpc: "2.0", id: message.id, error: { code: -32000, message: error.message } }));
    return;
  }
  if (message.id !== undefined) send({ jsonrpc: "2.0", id: message.id, error: { code: -32601, message: "Method not found: " + message.method } });
}
let buffer = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", chunk => {
  buffer += chunk;
  let newline;
  while ((newline = buffer.indexOf("\n")) >= 0) {
    const line = buffer.slice(0, newline).trim();
    buffer = buffer.slice(newline + 1);
    if (!line) continue;
    try { handle(JSON.parse(line)); }
    catch { send({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "Parse error" } }); }
  }
});
