#!/usr/bin/env node
// build-dsh.mjs — 跨平台 DSH 树构建（不含 node；运行时用系统 node 启动 dsh）。
//
// 用法：
//   node scripts/build-dsh.mjs [--os win32|darwin|linux] [--arch x64|arm64]
//        [--dsh-version 0.1.1-rc.2] [--hanui-version 0.2.5]
//        [--output build/dsh-<os>-<arch>] [--registry <npm>] [--cache <dir>]
//        [--bundle] [--force]
//
// 说明：
//   - 只安装 @deepseek-ai/dsh + dsh-mobile-hanui 到 <output>/dsh/，不下载/打包 node。
//     node 由宿主系统提供（系统 node 需 ≥ 20，sharp/koffi 用 NAPI v9）。
//   - `npm install` 用 `--ignore-scripts --os X --cpu Y`：跳过 postinstall（避免编译），
//     让 npm 按目标平台解析 sharp/koffi 等 optionalDependencies 的预编译二进制。
//   - 产出（--bundle）：`build/dsh-<os>-<arch>.zip`（根为 `dsh/`）+ 同名 `.sha256` 侧车。
//   - 推荐 CI 在各目标 OS runner 上构建最稳（见 .github/workflows/build-release.yml）；
//     `--os/--cpu` 标志位支持本地交叉构建，但 sharp/koffi 在跨平台 OS 上解析可能需 CI 实测。
import { spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
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

const osName = opt('os', process.platform);           // win32 | darwin | linux
const arch = opt('arch', process.arch);               // x64 | arm64
const dshVersion = opt('dsh-version', '0.1.1-rc.2');
const hanuiVersion = opt('hanui-version', '0.2.5');
const output = opt('output', path.join(root, 'build', `dsh-${osId(osName)}-${arch}`));
const registry = opt('registry', process.env.npm_config_registry || 'https://registry.npmmirror.com/');
const cacheDir = opt('cache', path.join(output, '.npm-cache'));
const force = flag('force');
const bundle = flag('bundle');

function osId(o) {
  const n = String(o).toLowerCase();
  if (n.includes('win')) return 'win';
  if (n.includes('darwin') || n.includes('mac')) return 'macos';
  if (n.includes('linux')) return 'linux';
  return n;
}

const targetKey = `${osId(osName)}-${arch}`;
const dshDir = path.join(output, 'dsh');
const dshBin = path.join(dshDir, 'node_modules/@deepseek-ai/dsh/lib/bin.js');
const hanuiPkg = path.join(dshDir, 'node_modules/dsh-mobile-hanui/package.json');
const bundleZip = path.join(root, 'build', `dsh-${targetKey}.zip`);

function log(m) { console.log(`==> ${m}`); }
function sha256(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex').toUpperCase();
}
function sh(cmd, argsArr, opts = {}) {
  const r = spawnSync(cmd, argsArr, { stdio: 'inherit', ...opts });
  if (r.status !== 0) throw new Error(`command failed (exit ${r.status}): ${cmd} ${argsArr.join(' ')}`);
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

/**
 * 裁剪 node-pty 的 prebuilds 到目标平台（单平台 zip 不需要其它 OS 的预编译二进制）。
 * node-pty 自带全部 6 个平台（darwin-arm64/x64、linux-arm64/x64、win32-arm64/x64）= ~20MB，
 * 但 node-pty 运行时按 process.platform/arch 选对应 prebuild，删其它安全。
 * 严格保留 `${osName}-${arch}`（如 win32-x64）—— 用户应装对应平台 zip，不为错误平台兜底。
 */
function trimNodePtyPrebuilds() {
  const prebuildsDir = path.join(dshDir, 'node_modules', 'node-pty', 'prebuilds');
  if (!fs.existsSync(prebuildsDir)) {
    log('   node-pty/prebuilds/ 不存在，跳过裁剪');
    return;
  }
  const keepKey = `${osName}-${arch}`; // e.g. win32-x64
  const all = fs.readdirSync(prebuildsDir);
  const drop = all.filter((n) => n !== keepKey);
  let savedBytes = 0;
  for (const d of drop) {
    const p = path.join(prebuildsDir, d);
    savedBytes += dirSize(p);
    fs.rmSync(p, { recursive: true, force: true });
  }
  const kept = fs.readdirSync(prebuildsDir);
  const savedMb = Math.round(savedBytes / 1048576 * 10) / 10;
  log(`   node-pty 裁剪: 保留 [${kept.join(', ') || '(none)'}], 删除 ${drop.length} 个平台目录 (省 ${savedMb} MB 解压体积)`);
}

// 系统 node 自带的 npm（系统 node 仅用于本构建脚本，不会被打进产物）。
const nodeExe = process.execPath;
const npmCli = path.join(path.dirname(nodeExe), 'node_modules', 'npm', 'bin', 'npm-cli.js');
if (!fs.existsSync(npmCli)) throw new Error(`system npm-cli.js not found: ${npmCli}（本脚本需系统 node 与其内置 npm）`);

async function main() {
  log(`DSH tree build (no node): @deepseek-ai/dsh@${dshVersion} + dsh-mobile-hanui@${hanuiVersion} -> ${output} [target ${targetKey}]`);

  // ---- 1. Install dsh tree ----
  if (fs.existsSync(dshBin) && fs.existsSync(hanuiPkg) && !force) {
    log('dsh tree already present, skip install');
  } else {
    log(`Install dsh@${dshVersion} + hanui@${hanuiVersion} for ${targetKey}`);
    fs.rmSync(dshDir, { recursive: true, force: true });
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

    const npmArgs = [
      npmCli, 'install',
      '--ignore-scripts', // 不跑 postinstall；sharp/koffi 取预编译二进制
      '--no-audit', '--no-fund',
      '--cache', cacheDir,
      '--registry', registry,
      '--os', osName,        // 让 npm 按目标平台解析 optionalDependencies
      '--cpu', arch,
    ];
    sh(nodeExe, npmArgs, { cwd: dshDir });

    if (!fs.existsSync(dshBin)) throw new Error(`dsh bin missing after install: ${dshBin}`);
    if (!fs.existsSync(hanuiPkg)) throw new Error(`dsh-mobile-hanui missing after install: ${hanuiPkg}`);
  }

  // 裁剪 node-pty prebuilds 到目标平台（单平台 zip 不需要其它 OS 的预编译）
  trimNodePtyPrebuilds();

  // ---- 2. Verify ----
  log('Verify');
  const dshPkgDir = path.resolve(dshDir, 'node_modules/@deepseek-ai/dsh');
  sh(nodeExe, ['-e', `const p=require(process.argv[1]+'/package.json');console.log('   dsh '+p.version);`, dshPkgDir]);
  sh(nodeExe, ['-e', `const p=require(process.argv[1]);console.log('   hanui '+p.version);`, hanuiPkg]);

  const imgDir = path.join(dshDir, 'node_modules/@img');
  if (fs.existsSync(imgDir)) {
    const sharpVariants = fs.readdirSync(imgDir).filter((n) => n.startsWith('sharp-'));
    log(`   sharp variants: ${sharpVariants.join(', ') || '(none)'}`);
    if (sharpVariants.length === 0) log('   WARN: no sharp variant installed — image attachment will break on this platform');
  }
  const koromixDir = path.join(dshDir, 'node_modules/@koromix');
  if (fs.existsSync(koromixDir)) {
    const koffiVariants = fs.readdirSync(koromixDir).filter((n) => n.startsWith('koffi-'));
    log(`   koffi variants: ${koffiVariants.join(', ') || '(none)'}`);
  }

  // ---- 3. Bundle ----
  if (bundle) {
    log(`Bundle dsh-${targetKey}.zip`);
    fs.rmSync(bundleZip, { force: true });
    // 仅打包 dsh/（不含 node/、不含 .npm-cache/）；根为 dsh/，与 runtime-bundle.zip 的"node + dsh"布局兼容。
    const tarArgs = [
      '-a', '-c', '-f', bundleZip,
      '--exclude', '.npm-cache',
      '-C', output, 'dsh',
    ];
    sh('tar', tarArgs);
    const sizeMb = Math.round(fs.statSync(bundleZip).size / 1048576 * 10) / 10;
    console.log(`   -> ${bundleZip} (${sizeMb} MB)`);
    fs.writeFileSync(bundleZip + '.sha256', sha256(bundleZip) + '\n');
    console.log(`   -> ${bundleZip}.sha256`);
  }

  log(`Done: ${output}`);
  console.log(`   dsh: ${dshBin}`);
}

main().catch((e) => { console.error(e.message || e); process.exit(1); });
