#!/usr/bin/env node

import { cpSync, existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { spawn, spawnSync } from 'node:child_process';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const isWindows = process.platform === 'win32';
const args = parseArgs(process.argv.slice(2));

if (args.help) {
  printHelp();
  process.exit(0);
}

if (!args.idePath) fail('--ide-path is required.');
if (!args.jdkPath) fail('--jdk-path is required.');

const version = readVersion();
const archive = args.pluginArchive
  ? resolve(args.pluginArchive)
  : join(root, 'build', 'local', `git-diff-review-${version}.zip`);
const phases = args.skipFull ? ['native', 'source'] : ['native', 'source', 'full'];
const runner = findPowerShell();
const startSandbox = join(root, 'scripts', 'start-sandbox.ps1');
const buildLocal = join(root, 'scripts', 'build-local.ps1');

if (!runner) fail('No PowerShell runner was found. Set DIFFNAV_PWSH to pwsh or install PowerShell 7.');
if (!args.pluginArchive && !args.dryRun) {
  await run(runner, ['-NoProfile', '-File', buildLocal, '-IdePath', args.idePath, '-JdkPath', args.jdkPath], root);
}
if (!args.pluginArchive && !args.dryRun && !existsSync(archive)) fail(`Build archive was not created: ${archive}`);
if (args.pluginArchive && !existsSync(archive)) fail(`Plugin archive does not exist: ${archive}`);

const summary = [];
for (const phase of phases) {
  const sandboxName = `${args.sandboxPrefix}-${phase}-${Date.now()}`;
  const phaseArgs = [
    '-NoProfile', '-File', startSandbox,
    '-IdePath', args.idePath,
    '-JdkPath', args.jdkPath,
    '-SandboxName', sandboxName,
    '-PluginArchive', archive,
  ];
  if (phase === 'native') phaseArgs.push('-NativePopupOnly');
  if (phase === 'source') phaseArgs.push('-SourceOnly');

  const phaseConfig = args.testConfigPath ? cloneTestConfig(args.testConfigPath, phase) : null;
  if (phaseConfig) phaseArgs.push('-TestConfigPath', phaseConfig);

  if (args.dryRun) {
    console.log(`[dry-run] ${runner} ${phaseArgs.map(shellQuote).join(' ')}`);
    if (phaseConfig) removeTree(phaseConfig);
    continue;
  }

  try {
    const launcher = await runCapture(runner, phaseArgs, root);
    const reportMatch = launcher.stdout.match(/Report:\s*(.+smoke-report\.txt)/i);
    if (!reportMatch) throw new Error(`${phase}: sandbox launcher did not report its smoke file.\n${launcher.stdout}`);
    const report = resolve(reportMatch[1].trim());
    const result = await waitForReport(report, args.timeoutMs);
    const log = join(root, 'build', sandboxName, 'log', 'idea.log');
    const errors = existsSync(log) ? countPlatformErrors(log) : 0;
    const passed = result.text.match(/^PASS\s+\d+\s+.+$/m)?.[0] ?? null;
    const failed = result.text.match(/^FAIL\b.+$/m)?.[0] ?? null;
    const item = { phase, sandboxName, report, log, passed, failed, platformErrors: errors };
    summary.push(item);
    console.log(JSON.stringify(item));
    if (failed || !passed || errors > 0) {
      printSummary(summary);
      throw new Error(`${phase}: regression phase failed.`);
    }
  } finally {
    if (phaseConfig) removeTree(phaseConfig);
  }
}

if (args.dryRun) {
  console.log(`[dry-run] version=${version} archive=${archive}`);
} else {
  printSummary(summary);
}

function parseArgs(values) {
  const parsed = {
    idePath: '', jdkPath: '', pluginArchive: '', testConfigPath: '',
    sandboxPrefix: 'source-marker-regression', timeoutMs: 30 * 60 * 1000,
    skipFull: false, dryRun: false, help: false,
  };
  for (let i = 0; i < values.length; i++) {
    const value = values[i];
    if (value === '--help' || value === '-h') parsed.help = true;
    else if (value === '--skip-full') parsed.skipFull = true;
    else if (value === '--dry-run') parsed.dryRun = true;
    else if (value === '--ide-path') parsed.idePath = values[++i] ?? '';
    else if (value === '--jdk-path') parsed.jdkPath = values[++i] ?? '';
    else if (value === '--plugin-archive') parsed.pluginArchive = values[++i] ?? '';
    else if (value === '--test-config-path') parsed.testConfigPath = values[++i] ?? '';
    else if (value === '--sandbox-prefix') parsed.sandboxPrefix = values[++i] ?? parsed.sandboxPrefix;
    else if (value === '--timeout-ms') parsed.timeoutMs = Number(values[++i]);
    else fail(`Unknown argument: ${value}`);
  }
  if (!Number.isFinite(parsed.timeoutMs) || parsed.timeoutMs < 1000) fail('--timeout-ms must be at least 1000.');
  return parsed;
}

function readVersion() {
  const properties = readFileSync(join(root, 'gradle.properties'), 'utf8');
  const match = properties.match(/^pluginVersion\s*=\s*(\S+)\s*$/m);
  if (!match) fail('pluginVersion is missing from gradle.properties.');
  return match[1];
}

function findPowerShell() {
  const configured = process.env.DIFFNAV_PWSH;
  if (configured) return configured;
  const candidates = isWindows ? ['pwsh', 'powershell'] : ['pwsh'];
  for (const candidate of candidates) {
    const result = spawnSync(candidate, ['-NoProfile', '-Command', '$PSVersionTable.PSVersion.ToString()'], { stdio: 'ignore' });
    if (result.status === 0) return candidate;
  }
  return null;
}

function run(command, commandArgs, cwd) {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(command, commandArgs, { cwd, stdio: 'inherit', windowsHide: true });
    child.on('error', reject);
    child.on('exit', code => code === 0 ? resolvePromise() : reject(new Error(`${command} exited with ${code}`)));
  });
}

function runCapture(command, commandArgs, cwd) {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(command, commandArgs, { cwd, windowsHide: true });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', chunk => { stdout += chunk; process.stdout.write(chunk); });
    child.stderr.on('data', chunk => { stderr += chunk; process.stderr.write(chunk); });
    child.on('error', reject);
    child.on('exit', code => code === 0 ? resolvePromise({ stdout, stderr }) : reject(new Error(`${command} exited with ${code}`)));
  });
}

async function waitForReport(report, timeoutMs) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    if (existsSync(report)) {
      const text = readFileSync(report, 'utf8');
      if (/^PASS\s+\d+\s+.+$/m.test(text) || /^FAIL\b/m.test(text)) return { text };
    }
    await new Promise(resolvePromise => setTimeout(resolvePromise, 1000));
  }
  const text = existsSync(report) ? readFileSync(report, 'utf8') : '';
  throw new Error(`Timed out waiting for ${report}.\n${text.slice(-4000)}`);
}

function countPlatformErrors(log) {
  return readFileSync(log, 'utf8').split(/\r?\n/).filter(line => /\bSEVERE\b|\sERROR\s-/.test(line)).length;
}

function cloneTestConfig(source, phase) {
  const target = mkdtempSync(join(tmpdir(), `diffnav-regression-${phase}-`));
  try {
    cpSync(resolve(source), target, { recursive: true });
    return target;
  } catch (error) {
    removeTree(target);
    fail(`Could not clone test configuration for ${phase}: ${error.message}`);
  }
}

function removeTree(target) {
  try { rmSync(target, { recursive: true, force: true }); } catch (error) {
    console.warn(`Warning: could not remove temporary test configuration ${target}: ${error.message}`);
  }
}

function printSummary(items) {
  console.log(`Source marker regression summary: ${items.length} phase(s)`);
  for (const item of items) console.log(`${item.phase}: ${item.passed ?? item.failed ?? 'no result'}; platformErrors=${item.platformErrors}; report=${item.report}`);
}

function shellQuote(value) {
  return /[\s"]/.test(value) ? `"${value.replaceAll('"', '\\"')}"` : value;
}

function printHelp() {
  console.log(`Usage: node scripts/run-source-marker-regression.mjs --ide-path <path> --jdk-path <path> [options]

Runs native marker, source staging, and full smoke phases sequentially. The Node.js
entry point is cross-platform; the existing IDE adapter is selected through
DIFFNAV_PWSH (pwsh by default, powershell on Windows as a fallback).

Options:
  --plugin-archive <zip>   Reuse an existing archive instead of building.
  --test-config-path <dir> Reuse an isolated GoLand test configuration.
  --sandbox-prefix <name>  Prefix for generated sandbox directories.
  --skip-full              Run only native marker and source staging phases.
  --timeout-ms <number>    Per-phase timeout; default 1800000.
  --dry-run                Print commands without building or launching GoLand.
`);
}

function fail(message) {
  console.error(`ERROR: ${message}`);
  process.exit(2);
}
