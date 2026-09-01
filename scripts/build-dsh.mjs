#!/usr/bin/env node
// build-dsh.mjs — 全平台 DSH 树构建（不含 node；运行时用系统 node 启动 dsh）。
//
// 用法：
//   node scripts/build-dsh.mjs [--dsh-version 0.1.1-rc.2] [--hanui-version 0.2.5]
//        [--output build/dsh] [--registry <npm>] [--cache <dir>]
//        [--bundle] [--force]
//
// 设计：单一 universal zip，覆盖 win-x64/win-arm64/macos-x64/macos-arm64/linux-x64/linux-arm64。
//   - npm install 不传 --os/--cpu，让 sharp/koffi/node-addon-require-builtin/node-pty 的所有平台
//     prebuilt 二进制都装上（每个都是多 arch 同 OS 的预编译包）。
//   - 运行时由 process.platform/arch 自动挑对应 native。
//   - node-pty prebuilds 全部保留（不裁剪）。
//   - 产物（--bundle）：build/dsh-universal.zip + 同名 .sha256 侧车；zip 根为 dsh/。
//
// 推荐在 ubuntu-22.04 runner 上构建：Linux 容器 npm 解析最稳、sharp/koffi 全平台
// 预编译都有、单 runner 出单一 universal zip 最简单。
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

const dshVersion = opt('dsh-version', '0.1.1-rc.2');
const hanuiVersion = opt('hanui-version', '0.2.5');
const output = opt('output', path.join(root, 'build', 'dsh'));
const registry = opt('registry', process.env.npm_config_registry || 'https://registry.npmmirror.com/');
const cacheDir = opt('cache', path.join(output, '.npm-cache'));
const force = flag('force');
const bundle = flag('bundle');

const dshDir = path.join(output, 'dsh');
const dshBin = path.join(dshDir, 'node_modules/@deepseek-ai/dsh/lib/bin.js');
const hanuiPkg = path.join(dshDir, 'node_modules/dsh-mobile-hanui/package.json');
const bundleZip = path.join(root, 'build', 'dsh-universal.zip');

function log(m) { console.log(`==> ${m}`); }
function sha256(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex').toUpperCase();
}
function sh(cmd, argsArr, opts = {}) {
  const r = spawnSync(cmd, argsArr, { stdio: 'inherit', ...opts });
  if (r.status !== 0) throw new Error(`command failed (exit ${r.status}): ${cmd} ${argsArr.join(' ')}`);
}

// 系统 node 仅用于本构建脚本，不会被打进产物。npm 由 PATH 提供（系统/容器/CI 的
// actions/setup-node 都把 npm 装到 PATH；不要硬编 node_modules/npm/bin/npm-cli.js，
// 那条路径在 GitHub Actions 上不存在）。
const nodeExe = process.execPath;
const npmCmd = process.platform === 'win32' ? 'npm.cmd' : 'npm';

async function main() {
  log(`DSH tree build (universal, no node): @deepseek-ai/dsh@${dshVersion} + dsh-mobile-hanui@${hanuiVersion} -> ${output}`);
  log('   target: all platforms (win-x64/win-arm64 + macos-x64/macos-arm64 + linux-x64/linux-arm64)');

  // ---- 1. Install dsh tree ----
  if (fs.existsSync(dshBin) && fs.existsSync(hanuiPkg) && !force) {
    log('dsh tree already present, skip install');
  } else {
    log(`Install dsh@${dshVersion} + hanui@${hanuiVersion} (universal)`);
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

    // 不传 --os/--cpu，让 npm 把 sharp/koffi/node-addon-require-builtin/node-pty 的
    // 所有平台变体都装上。--include=optional 确保 optionalDependencies 不会被跳过。
    // 不传 --include=optional 给 corepack/npm 新版；通过环境变量 NPM_CONFIG_INCLUDE=optional 设置。
    const env = { ...process.env, NPM_CONFIG_INCLUDE: 'optional' };
    const npmArgs = [
      'install',
      '--ignore-scripts', // 不跑 postinstall；sharp/koffi 取预编译二进制
      '--no-audit', '--no-fund',
      '--cache', cacheDir,
      '--registry', registry,
    ];
    sh(npmCmd, npmArgs, { cwd: dshDir, env });

    if (!fs.existsSync(dshBin)) throw new Error(`dsh bin missing after install: ${dshBin}`);
    if (!fs.existsSync(hanuiPkg)) throw new Error(`dsh-mobile-hanui missing after install: ${hanuiPkg}`);
  }

  // ---- 2. Verify ----
  log('Verify');
  const dshPkgDir = path.resolve(dshDir, 'node_modules/@deepseek-ai/dsh');
  sh(nodeExe, ['-e', `const p=require(process.argv[1]+'/package.json');console.log('   dsh '+p.version);`, dshPkgDir]);
  sh(nodeExe, ['-e', `const p=require(process.argv[1]);console.log('   hanui '+p.version);`, hanuiPkg]);

  // sharp 变体（应有 win/macos/linux × x64/arm64 = 6）
  const imgDir = path.join(dshDir, 'node_modules/@img');
  if (fs.existsSync(imgDir)) {
    const sharpVariants = fs.readdirSync(imgDir).filter((n) => n.startsWith('sharp-')).sort();
    log(`   sharp variants: ${sharpVariants.join(', ') || '(none)'}`);
    if (sharpVariants.length < 4) log('   WARN: sharp 变体偏少（<4），部分平台 image attachment 可能不可用');
  }
  // koffi 变体
  const koromixDir = path.join(dshDir, 'node_modules/@koromix');
  if (fs.existsSync(koromixDir)) {
    const koffiVariants = fs.readdirSync(koromixDir).filter((n) => n.startsWith('koffi-')).sort();
    log(`   koffi variants: ${koffiVariants.join(', ') || '(none)'}`);
  }
  // node-pty prebuilds（应有 win32-x64/arm64 + darwin-x64/arm64 + linux-x64/arm64 = 6）
  const ptyPrebuilds = path.join(dshDir, 'node_modules/node-pty/prebuilds');
  if (fs.existsSync(ptyPrebuilds)) {
    const ptyDirs = fs.readdirSync(ptyPrebuilds).sort();
    log(`   node-pty prebuilds: ${ptyDirs.join(', ') || '(none)'}`);
    if (ptyDirs.length < 4) log('   WARN: node-pty prebuilds 偏少，部分平台终端可能不可用');
  }
  // node-addon-require-builtin 变体
  const nrbDir = path.join(dshDir, 'node_modules');
  if (fs.existsSync(nrbDir)) {
    const nrbVariants = fs.readdirSync(nrbDir).filter((n) => n.startsWith('node-addon-require-builtin-')).sort();
    log(`   node-addon-require-builtin: ${nrbVariants.join(', ') || '(none)'}`);
  }

  // ---- 3. Bundle ----
  if (bundle) {
    log(`Bundle ${path.basename(bundleZip)}`);
    fs.rmSync(bundleZip, { force: true });
    // 仅打包 dsh/（不含 .npm-cache/）；根为 dsh/，与 DSH_BUNDLE_RESOURCE 解析路径一致。
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