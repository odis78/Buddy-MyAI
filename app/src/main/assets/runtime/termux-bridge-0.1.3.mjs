#!/usr/bin/env node
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';

const VERSION = '0.1.3';
const ROOT = path.resolve(process.env.TERMUX_BRIDGE_REA_ROOT || path.join(os.homedir(), 'rea'));
const ANDROID_PACKAGE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/.test(process.env.BUDDY_ANDROID_PACKAGE || '')
  ? process.env.BUDDY_ANDROID_PACKAGE : 'app.buddy.myai.gt';
const MAX_READ_BYTES = 64 * 1024;
const MAX_OUTPUT = 16 * 1024;

// Fixed executable/argument combinations only. No shell is ever invoked.
const COMMANDS = {
  'node-version': { bin: 'node', args: ['--version'], cwd: ROOT },
  'npm-version': { bin: 'npm', args: ['--version'], cwd: ROOT },
  'git-version': { bin: 'git', args: ['--version'], cwd: ROOT },
  'java-version': { bin: 'java', args: ['-version'], cwd: ROOT },
  'git-status': { bin: 'git', args: ['status', '--short', '--branch'], cwd: ROOT },
  'git-diff-stat': { bin: 'git', args: ['diff', '--stat'], cwd: ROOT }
};
const SENSITIVE = /(^|\/)(\.env([^/]*)?|id_rsa|id_ed25519|credentials?(\.json)?|secrets?)(\/|$)/i;

function isWithinRoot(candidate) {
  const rel = path.relative(ROOT, candidate);
  return rel === '' || (!rel.startsWith(`..${path.sep}`) && rel !== '..' && !path.isAbsolute(rel));
}
function resolveProjectFile(relPath) {
  if (typeof relPath !== 'string' || !relPath.trim() || path.isAbsolute(relPath)) throw new Error('Provide a relative file path inside the project root.');
  const normalized = path.normalize(relPath);
  if (normalized.split(path.sep).includes('..') || SENSITIVE.test(normalized)) throw new Error('Path is blocked by the safety policy.');
  const full = path.resolve(ROOT, normalized);
  if (!isWithinRoot(full)) throw new Error('Path escapes the configured project root.');
  const rootReal = fs.realpathSync(ROOT);
  const fullReal = fs.realpathSync(full);
  const realRel = path.relative(rootReal, fullReal);
  if (realRel === '..' || realRel.startsWith(`..${path.sep}`) || path.isAbsolute(realRel)) throw new Error('Resolved path escapes the configured project root.');
  const stat = fs.lstatSync(full);
  if (stat.isSymbolicLink() || !stat.isFile()) throw new Error('Only regular, non-symlink files can be read.');
  if (stat.size > MAX_READ_BYTES) throw new Error(`File exceeds ${MAX_READ_BYTES} byte read limit.`);
  return fullReal;
}
function runCommand(spec) {
  return new Promise((resolve) => {
    const child = spawn(spec.bin, spec.args, { cwd: spec.cwd, shell: false, env: { ...process.env, GIT_PAGER: 'cat', PAGER: 'cat' }, stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '', stderr = '', finished = false;
    const append = (current, chunk) => (current + chunk.toString()).slice(0, MAX_OUTPUT);
    child.stdout.on('data', c => { stdout = append(stdout, c); });
    child.stderr.on('data', c => { stderr = append(stderr, c); });
    const timer = setTimeout(() => { child.kill('SIGKILL'); }, 12000);
    child.on('error', err => { if (!finished) { finished = true; clearTimeout(timer); resolve({ ok: false, error: err.message, stdout, stderr }); } });
    child.on('close', code => { if (!finished) { finished = true; clearTimeout(timer); resolve({ ok: code === 0, exitCode: code, stdout, stderr }); } });
  });
}
function launchMyAi() {
  return new Promise((resolve) => {
    const bin = '/system/bin/am';
    const args = [
      'start',
      '-a', 'android.intent.action.MAIN',
      '-c', 'android.intent.category.LAUNCHER',
      '-p', ANDROID_PACKAGE
    ];
    let stdout = '', stderr = '', finished = false;
    let child;
    try {
      child = spawn(bin, args, {
        cwd: ROOT,
        shell: false,
        env: process.env,
        stdio: ['ignore', 'pipe', 'pipe']
      });
    } catch (err) {
      resolve({ ok: false, error: err.message, executable: bin });
      return;
    }
    const append = (current, chunk) => (current + chunk.toString()).slice(0, MAX_OUTPUT);
    child.stdout.on('data', c => { stdout = append(stdout, c); });
    child.stderr.on('data', c => { stderr = append(stderr, c); });
    const timer = setTimeout(() => {
      if (!finished) {
        child.kill('SIGKILL');
        finished = true;
        resolve({ ok: false, timedOut: true, error: 'Android activity launch command timed out.', stdout, stderr });
      }
    }, 15000);
    child.on('error', err => {
      if (!finished) {
        finished = true;
        clearTimeout(timer);
        resolve({ ok: false, error: err.message, executable: bin, stdout, stderr });
      }
    });
    child.on('close', code => {
      if (!finished) {
        finished = true;
        clearTimeout(timer);
        resolve({
          ok: code === 0,
          exitCode: code,
          package: ANDROID_PACKAGE,
          stdout,
          stderr,
          note: code === 0
            ? 'Android accepted the launch command; this does not independently verify that the app finished rendering.'
            : 'Android did not confirm the launch. Background activity restrictions or a missing app may be responsible.'
        });
      }
    });
  });
}
function textResult(value) { return { content: [{ type: 'text', text: JSON.stringify(value, null, 2) }] }; }
function tool(name, description, properties = {}, required = []) {
  return { name, description, inputSchema: { type: 'object', properties, required, additionalProperties: false } };
}
const TOOLS = [
  tool('bridge_status', 'Read-only status of this local bridge and configured project root.'),
  tool('project_git_status', 'Run read-only git status --short --branch in the configured project root.'),
  tool('diagnose_environment', 'Check availability and versions of common Termux development tools.'),
  tool('read_project_file', 'Read a small non-sensitive UTF-8 regular file under the configured project root.', { path: { type: 'string', description: 'Relative path such as package.json or README.md; secret files and symlinks are blocked.' } }, ['path']),
  tool('run_allowlisted_command', 'Run one fixed diagnostic command only. Ask the user to approve the exact command in conversation before calling with confirmed=true.', { command_id: { type: 'string', enum: Object.keys(COMMANDS) }, confirmed: { type: 'boolean', description: 'Must be true only after the user explicitly approves the exact command in conversation.' } }, ['command_id', 'confirmed']),
  tool('launch_android_app', 'Launch only the preconfigured Buddy GT Android app. No package name or command can be supplied by the caller.')
];

async function callTool(name, args = {}) {
  switch (name) {
    case 'bridge_status':
      return textResult({ bridge: 'termux-bridge', version: VERSION, platform: process.platform, arch: process.arch, node: process.version, configuredRoot: ROOT, rootExists: fs.existsSync(ROOT), rootIsDirectory: fs.existsSync(ROOT) ? fs.statSync(ROOT).isDirectory() : false, executionPolicy: 'fixed allowlist; shell disabled; one fixed Android app launch', note: 'Account plugin installation alone does not connect to this local process.' });
    case 'project_git_status': {
      if (!fs.existsSync(ROOT) || !fs.statSync(ROOT).isDirectory()) return textResult({ ok: false, error: 'Configured project root does not exist or is not a directory.', root: ROOT });
      return textResult(await runCommand({ bin: 'git', args: ['status', '--short', '--branch'], cwd: ROOT }));
    }
    case 'diagnose_environment': {
      const checks = {};
      for (const [id, spec] of Object.entries(COMMANDS).filter(([id]) => ['node-version','npm-version','git-version','java-version'].includes(id))) checks[id] = await runCommand(spec);
      return textResult({ platform: process.platform, arch: process.arch, nodeRuntime: process.version, root: ROOT, rootExists: fs.existsSync(ROOT), checks });
    }
    case 'read_project_file': {
      try { const full = resolveProjectFile(args.path); return textResult({ path: path.relative(ROOT, full), content: fs.readFileSync(full, 'utf8') }); }
      catch (e) { return textResult({ ok: false, error: e.message }); }
    }
    case 'run_allowlisted_command': {
      if (args.confirmed !== true) return textResult({ ok: false, blocked: true, error: 'Explicit user confirmation is required. Explain the exact command and ask the user first.' });
      if (typeof args.command_id !== 'string' || !Object.hasOwn(COMMANDS, args.command_id)) return textResult({ ok: false, blocked: true, error: 'Command is not allowlisted.' });
      if (!fs.existsSync(ROOT) || !fs.statSync(ROOT).isDirectory()) return textResult({ ok: false, error: 'Configured project root does not exist.' });
      return textResult({ commandId: args.command_id, ...await runCommand(COMMANDS[args.command_id]) });
    }
    case 'launch_android_app':
      return textResult(await launchMyAi());
    default: throw new Error(`Unknown tool: ${name}`);
  }
}
function send(message) { process.stdout.write(JSON.stringify(message) + '\n'); }
function handle(msg) {
  if (!msg || typeof msg !== 'object') return;
  if (msg.method === 'notifications/initialized' || msg.method?.startsWith('notifications/')) return;
  if (msg.method === 'initialize') return send({ jsonrpc: '2.0', id: msg.id, result: { protocolVersion: msg.params?.protocolVersion || '2024-11-05', capabilities: { tools: {} }, serverInfo: { name: 'termux-bridge', version: VERSION } } });
  if (msg.method === 'ping') return send({ jsonrpc: '2.0', id: msg.id, result: {} });
  if (msg.method === 'tools/list') return send({ jsonrpc: '2.0', id: msg.id, result: { tools: TOOLS } });
  if (msg.method === 'tools/call') {
    callTool(msg.params?.name, msg.params?.arguments || {}).then(result => send({ jsonrpc: '2.0', id: msg.id, result })).catch(e => send({ jsonrpc: '2.0', id: msg.id, error: { code: -32000, message: e.message } }));
    return;
  }
  if (msg.id !== undefined) send({ jsonrpc: '2.0', id: msg.id, error: { code: -32601, message: `Method not found: ${msg.method}` } });
}
let buffer = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', chunk => {
  buffer += chunk;
  let newline;
  while ((newline = buffer.indexOf('\n')) >= 0) {
    const line = buffer.slice(0, newline).trim(); buffer = buffer.slice(newline + 1);
    if (!line) continue;
    try { handle(JSON.parse(line)); } catch (e) { send({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Parse error' } }); }
  }
});
