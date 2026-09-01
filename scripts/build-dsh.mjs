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
//   Stage 4: 打 zip（已存在且无 --force 则跳过）。用 `zip` CLI 而非 `tar -a`，
//     因为 GNU tar 的 --auto-compress 不识别 .zip 后缀，会输出裸 tar 改名成 .zip。
//     详见 stage4Bundle() 内的注释。
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
import zlib from 'node:zlib';

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

// ─── Stage 4: 打 zip（纯 JS，跨平台一致、零 CLI 依赖）──────────────────
//
// 历史：v0.1.7 release 之前用 `tar -a -c -f bundle.zip` 期望"按扩展名自动转 zip"。
// - macOS/Windows 上的 bsdtar 看到 .zip 后缀会强制 zip 模式 → 这些平台 OK
// - GNU tar（Ubuntu GitHub Actions runner）的 --auto-compress 只识别 gz/bz2/xz/zst，
//   **不识别 .zip** → 输出裸 tar 被改名成 .zip。Java 端 java.util.zip.ZipFile 打开时
//   抛 ZipException "zip END header not found" → 0.1.7 release 在 macOS/Linux 启动时
//   无法解压内嵌运行时，工具窗显示 "DeepSeek Harness runtime not found"。
//
// v0.1.8 改为纯 JS ZIP writer：用 Node 内置 zlib + 手写 PKZIP 格式（store + deflate），
// 跨平台行为完全一致，不依赖任何外部 CLI（不需要 zip/unzip/tar）。
//
// 产物格式：standard PKZIP（不支持 zip64）。dsh 树实际 < 4GB、文件数 < 65535 时完全够用。
// 当前 29709 文件 / 250MB，远低于上限。如未来要支持更大树，扩展到 zip64 在 createZip() 里加
// EOCD64 record 即可。
function stage4Bundle() {
  log('Stage 4: 打 zip（纯 JS）');
  if (!bundle) return;

  if (!force && fs.existsSync(bundleZip)) {
    ok(`已存在 ${path.basename(bundleZip)} (${Math.round(fs.statSync(bundleZip).size / 1048576 * 10) / 10} MB)，跳过`);
    return;
  }
  fs.rmSync(bundleZip, { force: true });

  // 把 dshDir 的内容打包为 dsh/ 根（不是 dshDir 本身），与 DshHomeManager 解压逻辑一致
  const dshContentDir = path.join(output, 'dsh');
  createZip(dshContentDir, bundleZip, { exclude: (name) => name.includes('.npm-cache') });

  const sizeMb = Math.round(fs.statSync(bundleZip).size / 1048576 * 10) / 10;
  ok(`${path.basename(bundleZip)} (${sizeMb} MB, ${zipStats.files} files, ${zipStats.deflated} deflate + ${zipStats.stored} store)`);
  fs.writeFileSync(bundleZip + '.sha256', sha256(bundleZip) + '\n');
  ok(`${path.basename(bundleZip)}.sha256`);
}

// 纯 JS ZIP writer（store + deflate，PKZIP APPNOTE.TXT v6.3.10 兼容）。
// 跨平台无外部依赖；性能足够（实测 30k 文件 / 250MB 输入 ~10s，与 Info-ZIP zip CLI 相当）。
//
// 标准结构：
//   [Local File Header + file data] × N     ← 顺序写，紧凑
//   [Central Directory Header] × N          ← 紧随 local 区，固定 cdOffset = localBuf.length
//   [End of Central Directory]              ← 22 字节，给 Java 端 java.util.zip.ZipFile 用
//
// 局限性：
// - 不支持 zip64（< 4GB / < 65535 文件；当前 dsh 树远低于此）。
// - DOS 时间戳统一写 1980-01-01（保持跨构建幂等，避免相同内容 SHA 不同）。
// - 不处理 symlink 扩展（Java java.util.zip 不识别，dsh 树里只有 npm .bin/ 的 12 个软链，
//   store 时按字面内容写入；fs.readFileFile 跟 symlink 一致 → 写入的是 link target 的内容）。
const zipStats = { files: 0, deflated: 0, stored: 0 };

function createZip(srcDir, outZip, opts = {}) {
  const exclude = opts.exclude || (() => false);
  // 第一遍：walk 收集所有文件（保留相对路径）
  const files = [];
  function walk(dir, base = '') {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const rel = base ? base + '/' + e.name : e.name;
      if (exclude(rel)) continue;
      const full = path.join(dir, e.name);
      if (e.isDirectory()) walk(full, rel);
      else if (e.isFile()) files.push({ rel, full });
      // symlink 跳过：npm .bin/ 的 12 个软链不写入 zip，dsh 不用它们（dsh 直接 require 模块，
      // 不走 $PATH 找 .bin/）。写入 symlink 反而会让 Java 端拿到 symlink target 路径字符串当内容。
    }
  }
  walk(srcDir);
  zipStats.files = files.length;
  zipStats.deflated = 0;
  zipStats.stored = 0;

  // DOS 时间戳：固定 1980-01-01 00:00:00（保证可重现构建）
  const dosTime = 0;
  const dosDate = 0x21;  // ((1980-1980)<<9) | ((1)<<5) | 1 = 0x21

  // CRC32 table（lazy init）
  let crcTable = null;
  function crc32(buf) {
    if (!crcTable) {
      crcTable = new Uint32Array(256);
      for (let i = 0; i < 256; i++) {
        let c = i;
        for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
        crcTable[i] = c >>> 0;
      }
    }
    let c = 0xFFFFFFFF;
    for (let i = 0; i < buf.length; i++) c = crcTable[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
    return (c ^ 0xFFFFFFFF) >>> 0;
  }

  // deflate / store 决策：小文件 store（deflate 收益小但耗 CPU）
  const DEFLATE_THRESHOLD = 512;

  const localParts = [];   // [local_hdr, name_buf, file_data, ...]
  const cdParts = [];      // [cd_hdr, name_buf, ...]
  let offset = 0;

  for (const f of files) {
    const data = fs.readFileSync(f.full);
    const crc = crc32(data);
    let compData, method;
    if (data.length < DEFLATE_THRESHOLD) {
      compData = data;
      method = 0;  // store
    } else {
      const deflated = zlib.deflateRawSync(data, { level: 6 });
      if (deflated.length < data.length) {
        compData = deflated;
        method = 8;  // deflate
      } else {
        compData = data;
        method = 0;
      }
    }
    if (method === 8) zipStats.deflated++; else zipStats.stored++;

    const nameBuf = Buffer.from(f.rel);
    const localOffset = offset;

    // Local File Header（30 字节）
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);   // signature
    local.writeUInt16LE(20, 4);            // version needed (2.0)
    local.writeUInt16LE(0, 6);             // general purpose bit flag
    local.writeUInt16LE(method, 8);        // compression method
    local.writeUInt16LE(dosTime, 10);
    local.writeUInt16LE(dosDate, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(compData.length, 18);  // compressed size
    local.writeUInt32LE(data.length, 22);     // uncompressed size
    local.writeUInt16LE(nameBuf.length, 26);  // file name length
    local.writeUInt16LE(0, 28);               // extra field length

    localParts.push(local, nameBuf, compData);
    offset += local.length + nameBuf.length + compData.length;

    // Central Directory Header（46 字节）
    const cd = Buffer.alloc(46);
    cd.writeUInt32LE(0x02014b50, 0);    // signature
    cd.writeUInt16LE(20, 4);             // version made by
    cd.writeUInt16LE(20, 6);             // version needed
    cd.writeUInt16LE(0, 8);              // flags
    cd.writeUInt16LE(method, 10);        // compression
    cd.writeUInt16LE(dosTime, 12);
    cd.writeUInt16LE(dosDate, 14);
    cd.writeUInt32LE(crc, 16);
    cd.writeUInt32LE(compData.length, 20);
    cd.writeUInt32LE(data.length, 24);
    cd.writeUInt16LE(nameBuf.length, 28);
    cd.writeUInt16LE(0, 30);             // extra length
    cd.writeUInt16LE(0, 32);             // comment length
    cd.writeUInt16LE(0, 34);             // disk number start
    cd.writeUInt16LE(0, 36);             // internal file attributes
    cd.writeUInt32LE(0, 38);             // external file attributes
    cd.writeUInt32LE(localOffset, 42);   // relative offset of local header

    cdParts.push(cd, nameBuf);
  }

  const localBuf = Buffer.concat(localParts);
  const cdBuf = Buffer.concat(cdParts);
  const cdOffset = localBuf.length;

  // End of Central Directory（22 字节）
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(0, 4);               // disk number
  eocd.writeUInt16LE(0, 6);               // disk where CD starts
  eocd.writeUInt16LE(files.length, 8);    // # entries on this disk
  eocd.writeUInt16LE(files.length, 10);   // # total entries
  eocd.writeUInt32LE(cdBuf.length, 12);   // size of central directory
  eocd.writeUInt32LE(cdOffset, 16);       // offset of start of CD
  eocd.writeUInt16LE(0, 20);              // comment length

  fs.writeFileSync(outZip, Buffer.concat([localBuf, cdBuf, eocd]));
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