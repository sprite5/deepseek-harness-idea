#!/usr/bin/env node
// build-dsh.mjs — 单一 universal dsh 树构建（不含 node；运行时用系统 node 启动 dsh）。
//
// 产物：build/dsh/dsh/node_modules/ + build/dsh-universal.zip（约 70 MB，跨所有 OS/arch）
//
// 用法：
//   node scripts/build-dsh.mjs [--dsh-version 0.1.1-rc.2] [--hanui-version 0.2.5]
//        [--output build/dsh] [--registry <npm>] [--cache <dir>]
//        [--bundle] [--force]
//
// 设计：幂等的 4 阶段构建，每次跑都检查当前状态、缺啥补啥；任意阶段失败都不致命，
// 下一阶段仍可继续。npm install 全图解析在本机/CI 都可能卡死，所以分两步：
//   Stage 1: dsh 基础树（~190 JS 包）—— 复用 IDEA runtime tree / 全局 npm tree / build/runtime 残留 / npm install 兜底
//   Stage 2: 15 个 native prebuild（sharp/koffi/node-addon-require-builtin 各 6 变体）—— 逐包 `npm pack` + `tar -xzf`，独立 install，绝不触发 npm 全图解析
//   Stage 3: 验证（文件存在 + 树结构；不 require native，因跨平台会抛错）
//   Stage 4: 打 zip（已存在且无 --force 则跳过）
//
// 全部 npm pack 都带 --pack-destination（写到指定目录），避免 stdout 截断 tarball。
// 装 native prebuild 用 --force 绕开 notsup（在不同主机上装跨平台包会被 npm 默认拒）。
//
// 推荐在 ubuntu-22.04 runner 上构建（npm 在 Linux 容器解析最稳）；本机复用现成
// 树也能秒级完成。绝对不要在 macos runner 上 universal install（npm 在 mac runner 上
// 对全平台 optional 解析偶发卡死）。

import { spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '..');

// ---- CLI ----
const args = process.argv.slice(2);
function opt(name, def) {
  const i = args.indexOf('--' + name);
  return i >= 0 && i + 1 < args.length ? args[i + 1] : def;
}
function flag(name) { return args.includes('--' + name); }

const dshVersion = opt('dsh-version', '0.1.1-rc.2');
const hanuiVersion = opt('hanui-version', '0.2.5');
const output = opt('output', path.join(root, 'build', 'dsh'));
const registry = opt('registry', process.env.npm_config_registry || 'https://registry.npmmirror.com/');
const cacheDir = opt('cache', path.join(output, '.npm-cache'));
const force = flag('force');
const bundle = flag('bundle');

// dsh 树根（含 package.json + node_modules/）。脚本默认 output/build/dsh/dsh/；
// 产物 zip 是 bundleZip = output 目录的上层 + /dsh-universal.zip。
const dshDir = path.join(output, 'dsh');
const dshBin = path.join(dshDir, 'node_modules/@deepseek-ai/dsh/lib/bin.js');
const hanuiPkg = path.join(dshDir, 'node_modules/dsh-mobile-hanui/package.json');
const bundleZip = path.join(root, 'build', 'dsh-universal.zip');

function log(m) { console.log(`==> ${m}`); }
function ok(m) { console.log(`   ✓ ${m}`); }
function warn(m) { console.log(`   ! ${m}`); }
function err(m) { console.log(`   ✗ ${m}`); }
function sha256(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex').toUpperCase();
}

// 用 shell:true 调 npm，让 spawnSync 解析 npm/npm.cmd；NPM_CONFIG_FETCH_TIMEOUT 防单包 fetch 卡死。
function npmRun(argsArr, cwd, envExtra = {}) {
  const cmd = os.platform() === 'win32' ? 'npm.cmd' : 'npm';
  const env = { ...process.env, ...envExtra };
  const r = spawnSync(cmd, argsArr, { cwd, env, stdio: 'inherit', shell: true });
  return { ok: r.status === 0, exit: r.status };
}

function dirSize(p) {
  if (!fs.existsSync(p)) return 0;
  if (!fs.statSync(p).isDirectory()) return fs.statSync(p).size;
  let s = 0;
  const walk = (x) => {
    for (const e of fs.readdirSync(x, { withFileTypes: true })) {
      const f = path.join(x, e.name);
      if (e.isDirectory()) walk(f);
      else s += fs.statSync(f).size;
    }
  };
  walk(p);
  return s;
}

// ─── Stage 1: dsh 基础树 ──────────────────────────────────────────────
// 复用候选（按优先级）：本机 build/runtime/dsh/（上次构建） > IDEA runtime > 全局 npm
// 都没有则 fallback 到 npm install（可能卡，仅作最后兜底）
function stage1BaseTree() {
  log('Stage 1: dsh 基础树 (JS 包，不含 native prebuild)');
  if (!force && fs.existsSync(dshBin) && fs.existsSync(hanuiPkg)) {
    ok(`已存在 tree，跳过 (${Math.round(dirSize(dshDir) / 1048576)} MB, ${fs.readdirSync(path.join(dshDir, 'node_modules')).length} top-level 包)`);
    return true;
  }

  // 候选源路径（任一可用即复用，不联网）
  const userHome = os.homedir();
  const candidates = [
    path.join(root, 'build', 'runtime', 'dsh', 'node_modules', '@deepseek-ai', 'dsh'),  // 上次构建产物
    path.join(userHome, 'AppData', 'Roaming', 'JetBrains', 'IntelliJIdea2025.3', 'dsh-idea', 'runtime', dshVersion, 'dsh', 'node_modules', '@deepseek-ai', 'dsh'),  // IDEA 2025.3
    path.join(userHome, 'AppData', 'Roaming', 'JetBrains', 'IntelliJIdea2024.3', 'dsh-idea', 'runtime', dshVersion, 'dsh', 'node_modules', '@deepseek-ai', 'dsh'),  // IDEA 2024.3
    path.join(userHome, 'AppData', 'Roaming', 'npm', 'node_modules', '@deepseek-ai', 'dsh'),  // 全局 npm
  ];

  for (const c of candidates) {
    if (!fs.existsSync(c)) continue;
    const pkg = JSON.parse(fs.readFileSync(path.join(c, 'package.json'), 'utf8'));
    if (pkg.version !== dshVersion) continue;
    // c = .../node_modules/@deepseek-ai/dsh；源 node_modules = path.resolve(c, '..', '..')
    const srcTree = path.resolve(c, '..', '..');
    log(`   复用现成 tree: ${srcTree}`);
    rm(dshDir);
    fs.mkdirSync(dshDir, { recursive: true });
    // node_modules 内容 → dshDir/node_modules/
    copyTree(srcTree, path.join(dshDir, 'node_modules'));
    // 顶层 package.json 也拷过来（stage 1 verify 不严格需要，但保持完整性）
    const srcPkg = path.join(path.dirname(srcTree), 'package.json');
    if (fs.existsSync(srcPkg)) fs.copyFileSync(srcPkg, path.join(dshDir, 'package.json'));
    ok(`复用成功 (${Math.round(dirSize(dshDir) / 1048576)} MB, ${fs.readdirSync(path.join(dshDir, 'node_modules')).length} 包)`);
    return true;
  }

  // 没有现成的 → npm install 兜底（已知会卡，仅留作 fallback）
  warn('无现成 tree，fallback 到 npm install（已知会卡，按 Ctrl-C 中断）');
  rm(dshDir);
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
  const r = npmRun(['install', '--ignore-scripts', '--no-audit', '--no-fund', '--include=optional',
   '--cache', cacheDir, '--registry', registry], dshDir);
  if (!r.ok) throw new Error(`npm install 兜底失败 (exit ${r.exit})`);
  return true;
}

// ─── Stage 2: native prebuild 补齐 ────────────────────────────────────
// 用 `npm pack <pkg>` + `tar -xzf` 装指定 native 包，**完全绕开 npm install 解析**。
// npm pack 只下载该包的 tarball，不解析依赖图；tar -xzf 直接展开到 node_modules。
// --force 允许跨平台装（默认 npm 会 notsup 拒）。
function stage2NativePrebuilds() {
  log('Stage 2: native prebuild 补齐（15 个包，每个独立装）');

  const packages = [
    // sharp @img
    ['@img/sharp-win32-x64', '@img'],
    ['@img/sharp-win32-arm64', '@img'],
    ['@img/sharp-darwin-x64', '@img'],
    ['@img/sharp-darwin-arm64', '@img'],
    ['@img/sharp-linux-x64', '@img'],
    ['@img/sharp-linux-arm64', '@img'],
    // koffi @koromix
    ['@koromix/koffi-win32-x64', '@koromix'],
    ['@koromix/koffi-win32-arm64', '@koromix'],
    ['@koromix/koffi-darwin-x64', '@koromix'],
    ['@koromix/koffi-darwin-arm64', '@koromix'],
    ['@koromix/koffi-linux-x64', '@koromix'],
    ['@koromix/koffi-linux-arm64', '@koromix'],
    // node-addon-require-builtin (注意: win 加 -msvc, linux 加 -gnu)
    ['node-addon-require-builtin-win32-x64-msvc', null],
    ['node-addon-require-builtin-win32-arm64-msvc', null],
    ['node-addon-require-builtin-darwin-x64', null],
    ['node-addon-require-builtin-darwin-arm64', null],
    ['node-addon-require-builtin-linux-x64-gnu', null],
    ['node-addon-require-builtin-linux-arm64-gnu', null],
  ];

  const nm = path.join(dshDir, 'node_modules');
  const cache = path.join(root, 'build', 'pack-cache');
  fs.mkdirSync(cache, { recursive: true });

  let installed = 0, skipped = 0, failed = 0;

  for (const [pkg, scope] of packages) {
    // 目标 dir
    const dir = scope ? path.join(nm, scope) : nm;
    const name = scope ? pkg.slice(scope.length + 1) : pkg;
    const dest = path.join(dir, name);

    if (!force && fs.existsSync(path.join(dest, 'package.json'))) {
      skipped++;
      continue;
    }

    // npm pack 到本地 cache
    const r = npmRun(['pack', '--pack-destination', cache, '--registry', registry, pkg], root);
    if (!r.ok) { failed++; err(`pack ${pkg} 失败`); continue; }

    // 找下载的 tgz：npm pack 对 @img/sharp-x 产出 img-sharp-x-1.0.0.tgz（前缀是 scope名加 -）。
    // 对非 scoped 包：sharp-x-1.0.0.tgz。
    // 用 pack 后立即 find，时间窗口内 cache 里可能有多个相同 prefix，精确按 name 字符串匹配。
    const tgzPrefix = scope ? scope.slice(1) + '-' + name.replace('/', '-') : name;
    // 例: scope='@img', name='sharp-darwin-x64' → 'img-sharp-darwin-x64'
    // 之前可能留下同前缀旧 tgz，按 mtime 排序取最新的
    const allTgzs = fs.readdirSync(cache).filter((f) => f.startsWith(tgzPrefix) && f.endsWith('.tgz'));
    allTgzs.sort((a, b) => fs.statSync(path.join(cache, b)).mtimeMs - fs.statSync(path.join(cache, a)).mtimeMs);
    const tgzs = allTgzs.slice(0, 1);  // 只取最新的
    if (tgzs.length === 0) { failed++; err(`${pkg}: 未找到 tgz`); continue; }
    const tgz = path.join(cache, tgzs[0]);

    // 解压到 dest
    fs.mkdirSync(dest, { recursive: true });
    const tar = spawnSync('tar', ['-xzf', tgz, '-C', dest, '--strip-components=1'], { stdio: 'inherit' });
    fs.unlinkSync(tgz);
    if (tar.status !== 0 || !fs.existsSync(path.join(dest, 'package.json'))) {
      failed++; err(`${pkg}: 解压失败`);
      continue;
    }
    installed++;
    ok(`${pkg} → ${path.relative(dshDir, dest)}`);
  }

  log(`Stage 2 完成: ${installed} 装上, ${skipped} 已存在, ${failed} 失败`);
  if (failed > 0) warn(`${failed} 个 native prebuild 装失败，universal zip 可能缺该平台二进制`);
}

// ─── Stage 3: 验证（仅树检查，不 require native）────────────────────
function stage3Verify() {
  log('Stage 3: 验证');

  // 必须存在
  for (const [label, p] of [['dsh bin', dshBin], ['hanui', hanuiPkg]]) {
    if (!fs.existsSync(p)) throw new Error(`缺失: ${label} (${p})`);
    ok(`${label} 存在`);
  }

  // 版本对
  const dshPkg = JSON.parse(fs.readFileSync(path.join(dshDir, 'node_modules/@deepseek-ai/dsh/package.json'), 'utf8'));
  const hanuiPkgJson = JSON.parse(fs.readFileSync(hanuiPkg, 'utf8'));
  if (dshPkg.version !== dshVersion) throw new Error(`dsh 版本不符: ${dshPkg.version} != ${dshVersion}`);
  if (hanuiPkgJson.version !== hanuiVersion) throw new Error(`hanui 版本不符: ${hanuiPkgJson.version} != ${hanuiVersion}`);
  ok(`dsh ${dshPkg.version}, hanui ${hanuiPkgJson.version}`);

  // native prebuild 变体数
  const imgDir = path.join(dshDir, 'node_modules/@img');
  const sharpVars = fs.existsSync(imgDir) ? fs.readdirSync(imgDir).filter((n) => n.startsWith('sharp-')).sort() : [];
  log(`   sharp: ${sharpVars.join(', ') || '(none)'}`);
  if (sharpVars.length < 6) warn(`sharp 变体 <6 (${sharpVars.length})，部分平台 image 可能挂`);

  const koromixDir = path.join(dshDir, 'node_modules/@koromix');
  const koffiVars = fs.existsSync(koromixDir) ? fs.readdirSync(koromixDir).filter((n) => n.startsWith('koffi-')).sort() : [];
  log(`   koffi: ${koffiVars.join(', ') || '(none)'}`);
  if (koffiVars.length < 6) warn(`koffi 变体 <6 (${koffiVars.length})`);

  const ptyDir = path.join(dshDir, 'node_modules/node-pty/prebuilds');
  const ptyVars = fs.existsSync(ptyDir) ? fs.readdirSync(ptyDir).sort() : [];
  log(`   node-pty: ${ptyVars.join(', ') || '(none)'}`);
  if (ptyVars.length < 6) warn(`node-pty prebuilds <6 (${ptyVars.length})`);

  const nrbVars = fs.readdirSync(path.join(dshDir, 'node_modules'))
   .filter((n) => n.startsWith('node-addon-require-builtin-')).sort();
  log(`   node-addon-require-builtin: ${nrbVars.join(', ') || '(none)'}`);
  if (nrbVars.length < 6) warn(`nrb 变体 <6 (${nrbVars.length})`);
}

// ─── Stage 4: 打 zip ────────────────────────────────────────────────
function stage4Bundle() {
  log('Stage 4: 打 zip');
  if (!bundle) return;

  if (!force && fs.existsSync(bundleZip)) {
    ok(`已存在 ${path.basename(bundleZip)} (${Math.round(fs.statSync(bundleZip).size / 1048576 * 10) / 10} MB)，跳过`);
    return;
  }
  fs.rmSync(bundleZip, { force: true });
  // 把 dshDir 的内容打包为 dsh/ 根（不是 dshDir 本身），与 DshHomeManager 解压逻辑一致
  const tar = spawnSync('tar', [
    '-a', '-c', '-f', bundleZip,
    '--exclude', '.npm-cache',
    '-C', output, 'dsh',
  ], { stdio: 'inherit' });
  if (tar.status !== 0) throw new Error(`tar 失败 (exit ${tar.status})`);
  const sizeMb = Math.round(fs.statSync(bundleZip).size / 1048576 * 10) / 10;
  ok(`${path.basename(bundleZip)} (${sizeMb} MB)`);
  fs.writeFileSync(bundleZip + '.sha256', sha256(bundleZip) + '\n');
  ok(`${path.basename(bundleZip)}.sha256`);
}

// ─── 文件操作 helpers ───────────────────────────────────────────────────
function rm(p) {
  if (fs.existsSync(p)) fs.rmSync(p, { recursive: true, force: true });
}

// 递归复制 src → dst，保留目录结构
function copyTree(src, dst) {
  if (!fs.existsSync(src)) return;
  fs.mkdirSync(dst, { recursive: true });
  for (const e of fs.readdirSync(src, { withFileTypes: true })) {
    const s = path.join(src, e.name);
    const d = path.join(dst, e.name);
    if (e.isDirectory()) copyTree(s, d);
    else {
      // 用 copyFileSync 而不是 read+write，处理权限保留
      fs.copyFileSync(s, d);
    }
  }
}

// ─── main ─────────────────────────────────────────────────────────────
async function main() {
  log(`DSH universal build: @deepseek-ai/dsh@${dshVersion} + dsh-mobile-hanui@${hanuiVersion} -> ${output}`);
  log('   target: all OS/arch (win-x64/arm64, darwin-x64/arm64, linux-x64/arm64)');

  const t0 = Date.now();

  stage1BaseTree();
  stage2NativePrebuilds();
  stage3Verify();
  stage4Bundle();

  const sec = Math.round((Date.now() - t0) / 1000);
  log(`Done in ${sec}s: ${output}`);
  if (bundle) console.log(`   dsh-bundle: ${bundleZip}`);
}

main().catch((e) => { console.error(e.message || e); process.exit(1); });