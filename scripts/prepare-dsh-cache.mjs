#!/usr/bin/env node
// prepare-dsh-cache.mjs — 专给 CI 用的 dsh 基础树准备脚本。
//
// build-dsh.mjs 的 Stage 1 在 CI 上会 fallback 到 npm install（已知会卡），
// 且只检查本地路径（AppData/Roaming/JetBrains/...）—— GitHub Actions runner 上
// 这些都不存在。本脚本在 workflow 里 build-dsh.mjs 之前调用，把基础树装到
// build/dsh/dsh/ 下，让 build-dsh.mjs 复用。
//
// 配合 actions/cache 把 build/dsh 缓存起来，下次直接命中跳过 npm install。
//
// 用法：
//   node scripts/prepare-dsh-cache.mjs --registry <npm> --dsh-version <v> --hanui-version <v> [--output build/dsh]

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '..');

const args = process.argv.slice(2);
function opt(name, def) {
  const i = args.indexOf('--' + name);
  return i >= 0 && i + 1 < args.length ? args[i + 1] : def;
}

const dshVersion = opt('dsh-version', '0.1.5-rc.2');
const hanuiVersion = opt('hanui-version', '0.2.5');
const output = opt('output', path.join(root, 'build', 'dsh'));
const registry = opt('registry', 'https://registry.npmmirror.com/');

// dsh 树根（与 build-dsh.mjs 保持一致）
const dshDir = path.join(output, 'dsh');
const dshBin = path.join(dshDir, 'node_modules/@deepseek-ai/dsh/lib/bin.js');
const dshPkg = path.join(dshDir, 'node_modules/@deepseek-ai/dsh/package.json');
const hanuiPkg = path.join(dshDir, 'node_modules/dsh-mobile-hanui/package.json');
const piAiPkg = path.join(dshDir, 'node_modules/@earendil-works/pi-ai/package.json');
const anthropicPkg = path.join(dshDir, 'node_modules/@anthropic-ai/sdk/package.json');

function log(m) { console.log(`==> ${m}`); }
function ok(m) { console.log(`   ✓ ${m}`); }
function warn(m) { console.log(`   ! ${m}`); }

function npmRun(argsArr, cwd, envExtra = {}) {
  const cmd = os.platform() === 'win32' ? 'npm.cmd' : 'npm';
  const env = { ...process.env, ...envExtra };
  const r = spawnSync(cmd, argsArr, { cwd, env, stdio: 'inherit', shell: true });
  return { ok: r.status === 0, exit: r.status };
}

function readPackageVersion(file) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8')).version;
  } catch {
    return null;
  }
}

// 检查是否已经准备好（供 actions/cache 命中后直接跳过）。除了文件存在，还必须
// 校验版本和 anthropic provider 的硬依赖，避免把跨版本或历史坏缓存当成可用树。
const cachedDshVersion = readPackageVersion(dshPkg);
const cachedHanuiVersion = readPackageVersion(hanuiPkg);
const cachedPiAiVersion = readPackageVersion(piAiPkg);
const cachedAnthropicVersion = readPackageVersion(anthropicPkg);
if (
  fs.existsSync(dshBin) &&
  cachedDshVersion === dshVersion &&
  cachedHanuiVersion === hanuiVersion &&
  cachedPiAiVersion &&
  cachedAnthropicVersion
) {
  log(`dsh 基础树已存在: ${dshDir}`);
  ok(`跳过 npm install（dsh ${cachedDshVersion}, hanui ${cachedHanuiVersion}, pi-ai ${cachedPiAiVersion}, @anthropic-ai/sdk ${cachedAnthropicVersion}）`);
  process.exit(0);
}
if (fs.existsSync(dshDir)) {
  warn(`缓存树无效，将重建（dsh=${cachedDshVersion || 'missing'}, hanui=${cachedHanuiVersion || 'missing'}, pi-ai=${cachedPiAiVersion || 'missing'}, anthropic=${cachedAnthropicVersion || 'missing'}）`);
  fs.rmSync(dshDir, { recursive: true, force: true });
}

log(`准备 dsh 基础树: ${dshDir}`);
log(`   @deepseek-ai/dsh@${dshVersion} + dsh-mobile-hanui@${hanuiVersion}`);
log(`   registry: ${registry}`);

fs.mkdirSync(dshDir, { recursive: true });
const pkg = {
  name: 'dsh-runtime',
  private: true,
  dependencies: {
    '@deepseek-ai/dsh': dshVersion,
    'dsh-mobile-hanui': hanuiVersion,
  },
};
fs.writeFileSync(path.join(dshDir, 'package.json'), JSON.stringify(pkg, null, 2));

const t1 = Date.now();
const r = npmRun([
  'install',
  '--ignore-scripts',  // 跳过 postinstall 钩子（避免 sharp/koffi 跨平台下载）
  '--no-audit',
  '--no-fund',
  '--include=optional',  // 包含 optional deps（sharp/koffi 等 native 的 prebuilt）
  '--include=dev',       // 包含 devDeps：dsh 把 dsh-llm-pi-ai / dsh-llm-deepseek 等放在 devDeps，
                         // pi-ai 的 anthropic provider 又在 dsh-llm-pi-ai 间接依赖里。
                         // 缺了 dev 会让 anthropic 加载 ERR_MODULE_NOT_FOUND（build-dsh.mjs
                         // Stage 3 有 fail-fast 校验兜底，但显式 --include=dev 让意图可见）。
  '--registry', registry,
], dshDir, {
  NPM_CONFIG_FETCH_TIMEOUT: '120000',
  NPM_CONFIG_FETCH_RETRIES: '3',
  NPM_CONFIG_LOGLEVEL: 'warn',
});
const sec = Math.round((Date.now() - t1) / 1000);

if (!r.ok) {
  console.error(`npm install 失败 (exit ${r.exit})`);
  process.exit(r.exit || 1);
}
ok(`npm install 完成 (${sec}s)`);

// 验证
if (!fs.existsSync(dshBin)) {
  console.error(`缺失: ${dshBin}`);
  process.exit(1);
}
if (!fs.existsSync(hanuiPkg)) {
  console.error(`缺失: ${hanuiPkg}`);
  process.exit(1);
}
const installedDshVersion = readPackageVersion(dshPkg);
const installedHanuiVersion = readPackageVersion(hanuiPkg);
const installedPiAiVersion = readPackageVersion(piAiPkg);
const installedAnthropicVersion = readPackageVersion(anthropicPkg);
if (installedDshVersion !== dshVersion) {
  console.error(`dsh 版本不符: ${installedDshVersion || 'missing'} != ${dshVersion}`);
  process.exit(1);
}
if (installedHanuiVersion !== hanuiVersion) {
  console.error(`hanui 版本不符: ${installedHanuiVersion || 'missing'} != ${hanuiVersion}`);
  process.exit(1);
}
if (!installedPiAiVersion) {
  console.error(`缺失: ${piAiPkg}（dsh-llm-pi-ai 必需）`);
  process.exit(1);
}
if (!installedAnthropicVersion) {
  console.error(`缺失: ${anthropicPkg}（pi-ai anthropic provider 必需）`);
  process.exit(1);
}
ok(`dsh 基础树准备完成（dsh ${installedDshVersion}, hanui ${installedHanuiVersion}, pi-ai ${installedPiAiVersion}, @anthropic-ai/sdk ${installedAnthropicVersion}）`);
