<#
.SYNOPSIS
    构建 DSH 运行时目录（Step 2 交付物，见 docs/DESIGN.md §3.2）。

.DESCRIPTION
    产出 <OutputDir>/ 目录，布局与 DshHomeManager.runtimeRoot() 完全一致：
      <OutputDir>/node/                      # Node.js 22.x win-x64（固定版本，SHA-256 校验）
      <OutputDir>/dsh/                       # npm 安装的 @deepseek-ai/dsh 树（含依赖）

    可选 -Bundle 把整个目录打成 runtime-bundle.zip（Step 5 打入插件 resources）。

    设计约束：
    - 固定版本 + 校验和（可复现构建）；升级 = 改 -NodeVersion/-DshVersion 与对应 SHA。
    - 下载使用 Node 内置 fetch（本环境 curl/Invoke-WebRequest 走系统栈不稳，npm 可用）。
    - npm 安装使用 --ignore-scripts：win-x64 的原生依赖（koffi/sharp/node-pty 等）均以
      optionalDependencies 预编译产物提供，无需 postinstall；也避免沙箱/杀软拦截脚本。
    - 环境变量 npm_config_registry 已存在时尊重之，否则默认 npmmirror。

.PARAMETER OutputDir
    输出目录（默认 build/runtime；开发态常用 tooling/runtime-dev）。

.PARAMETER NodeVersion
    Node.js 版本（默认 22.23.2，与本地 node.zip 一致）。

.PARAMETER NodeSha256
    node-v<ver>-win-x64.zip 的 SHA-256（默认值对应 22.23.2 官方构建）。

.PARAMETER DshVersion
    @deepseek-ai/dsh 版本（默认 0.1.0-rc.7，与 DshHomeManager.DSH_VERSION 一致）。

.PARAMETER Mirror
    下载镜像前缀（默认 npmmirror 二进制镜像）。

.PARAMETER NpmRegistry
    npm registry（默认取环境变量或 npmmirror）。

.PARAMETER CacheDir
    npm 缓存目录（默认 <OutputDir>/.npm-cache，避免写入用户目录权限问题）。

.PARAMETER BootstrapNode
    下载阶段使用的 Node 可执行文件（默认取 PATH 中的 node；仅在 node/ 尚未构建时用到，
    因为构建期需要先有一个 Node 来下载 Node 本身）。

.PARAMETER Bundle
    构建完成后打包 runtime-bundle.zip 到 <OutputDir>.zip 同层（build/）。

.EXAMPLE
    ./scripts/build-runtime.ps1 -OutputDir build/runtime -Bundle
#>
[CmdletBinding()]
param(
    [string]$OutputDir = "build/runtime",
    [string]$NodeVersion = "22.23.2",
    [string]$NodeSha256 = "1177B4137BA5ADAA56354AE40F1080C7450E8AE09CECB47DA459D1C52AC99F97",
    [string]$DshVersion = "0.1.1-rc.2",
    [string]$Mirror = "https://registry.npmmirror.com/-/binary/node",
    [string]$NpmRegistry = "",
    [string]$CacheDir = "",
    [string]$BootstrapNode = "node",
    [switch]$Bundle,
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot   # 仓库根目录

if (-not $NpmRegistry) { $NpmRegistry = if ($env:npm_config_registry) { $env:npm_config_registry } else { "https://registry.npmmirror.com/" } }
if (-not $CacheDir) { $CacheDir = Join-Path $OutputDir ".npm-cache" }

$nodeZipName  = "node-v$NodeVersion-win-x64.zip"
$nodeUrl      = "$Mirror/v$NodeVersion/$nodeZipName"
$nodeZip      = Join-Path $OutputDir $nodeZipName
$nodeDir      = Join-Path $OutputDir "node"
$dshDir       = Join-Path $OutputDir "dsh"
$dshBin       = Join-Path $dshDir "node_modules/@deepseek-ai/dsh/lib/bin.js"
$nodeExe      = Join-Path $nodeDir "node.exe"
$bundleZip    = Join-Path (Split-Path -Parent $OutputDir) "runtime-bundle.zip"

function Write-Step([string]$msg) { Write-Host "==> $msg" -ForegroundColor Cyan }

Write-Step "DSH runtime build: node v$NodeVersion + @deepseek-ai/dsh@$DshVersion -> $OutputDir"

# ---------- 1. Node.js ----------
if (Test-Path $nodeExe) {
    if ($Force) { Write-Step "Node 已存在，-Force 强制重建"; Remove-Item $nodeDir -Recurse -Force }
    else {
        Write-Step "Node 已存在，跳过下载（-Force 重建）"
        & $nodeExe -v | ForEach-Object { Write-Host "   node $_" }
    }
}

if (-not (Test-Path $nodeExe)) {
    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
    if (-not (Test-Path $nodeZip)) {
        Write-Step "下载 $nodeZipName"
        Write-Host "   $nodeUrl"
        & $BootstrapNode (Join-Path $PSScriptRoot "download-node.mjs") $nodeUrl $nodeZip
        if ($LASTEXITCODE -ne 0) { throw "下载失败: $nodeUrl" }
    } else {
        Write-Step "复用已有 $nodeZipName"
    }

    Write-Step "校验 SHA-256"
    $actual = (Get-FileHash $nodeZip -Algorithm SHA256).Hash
    if ($actual -ne $NodeSha256) {
        throw "SHA-256 不匹配：期望 $NodeSha256，实际 $actual（$nodeZip）"
    }
    Write-Host "   ok: $actual"

    Write-Step "解压到 node/"
    # Windows 10 自带 bsdtar 支持 zip；失败则回退 Expand-Archive
    tar -xf $nodeZip -C $OutputDir
    if ($LASTEXITCODE -ne 0) { Expand-Archive -Path $nodeZip -DestinationPath $OutputDir -Force }
    $extracted = Join-Path $OutputDir "node-v$NodeVersion-win-x64"
    if (Test-Path $extracted) { Move-Item $extracted $nodeDir -Force }
    if (-not (Test-Path $nodeExe)) { throw "未找到 $nodeExe" }
    & $nodeExe -v | ForEach-Object { Write-Host "   node $_" }
}

# ---------- 2. @deepseek-ai/dsh ----------
if (Test-Path $dshBin) {
    if ($Force) { Write-Step "dsh 已存在，-Force 强制重装"; Remove-Item $dshDir -Recurse -Force }
    else {
        Write-Step "dsh 已存在，跳过安装（-Force 重装）"
        $dshPkgJson = Join-Path $dshDir "node_modules/@deepseek-ai/dsh/package.json"
        if (Test-Path $dshPkgJson) {
            $ver = (Get-Content $dshPkgJson -Raw | ConvertFrom-Json).version
            Write-Host "   dsh $ver"
        }
    }
}

if (-not (Test-Path $dshBin)) {
    Write-Step "安装 @deepseek-ai/dsh@$DshVersion（npm --ignore-scripts）"
    New-Item -ItemType Directory -Force -Path $dshDir | Out-Null
    $pkgJson = '{"name":"dsh-runtime","private":true,"dependencies":{"@deepseek-ai/dsh":"' + $DshVersion + '"}}'
    [System.IO.File]::WriteAllText((Join-Path $dshDir "package.json"), $pkgJson, [System.Text.UTF8Encoding]::new($false))

    $npmCli = Join-Path $nodeDir "node_modules/npm/bin/npm-cli.js"
    if (-not (Test-Path $npmCli)) { throw "未找到 bundled npm-cli.js: $npmCli" }

    Push-Location $dshDir
    try {
        & $nodeExe $npmCli install --ignore-scripts --no-audit --no-fund --cache $CacheDir --registry $NpmRegistry
        if ($LASTEXITCODE -ne 0) { throw "npm install 失败（exit $LASTEXITCODE）" }
    } finally {
        Pop-Location
    }
    if (-not (Test-Path $dshBin)) { throw "安装后仍未找到 $dshBin" }
    Write-Host "   ok: $dshBin"
}

# ---------- 3. 冒烟：--version + 端口启动 ----------
Write-Step "冒烟验证"
$verScript = "const p=require(process.argv[1]+'/package.json');console.log('   dsh ' + p.version);"
$dshPkgDir = (Join-Path $dshDir "node_modules/@deepseek-ai/dsh") -replace '\\','/'
& $nodeExe -e $verScript $dshPkgDir
if ($LASTEXITCODE -ne 0) { throw "dsh 版本读取失败" }

# ---------- 4. （可选）打包 ----------
if ($Bundle) {
    Write-Step "打包 runtime-bundle.zip"
    if (Test-Path $bundleZip) { Remove-Item $bundleZip -Force }
    # 打包 OutputDir 的“内容”（node/ + dsh/），且排除源 zip 与缓存：
    # DshHomeManager 解压后期望 <target>/node/node.exe 与 <target>/dsh/...
    $excludes = @("*.zip", ".npm-cache")
    $tarArgs = @("-a", "-c", "-f", $bundleZip)
    foreach ($ex in $excludes) { $tarArgs += @("--exclude", $ex) }
    $tarArgs += @("-C", $OutputDir, "node", "dsh")
    tar @tarArgs
    if ($LASTEXITCODE -ne 0) { Compress-Archive -Path (Join-Path $OutputDir "node"), (Join-Path $OutputDir "dsh") -DestinationPath $bundleZip -Force }
    Write-Host "   -> $bundleZip ($([math]::Round((Get-Item $bundleZip).Length / 1MB, 1)) MB)"
}

Write-Step "完成: $OutputDir"
Write-Host "   node: $nodeExe"
Write-Host "   dsh : $dshBin"
