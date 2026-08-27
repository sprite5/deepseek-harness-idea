[CmdletBinding()]
param(
    [string]$OutputDir = "build/runtime",
    [string]$NodeVersion = "22.23.2",
    [string]$NodeSha256 = "1177B4137BA5ADAA56354AE40F1080C7450E8AE09CECB47DA459D1C52AC99F97",
    [string]$DshVersion = "0.1.1-rc.2",
    # Mobile-shell bundle (dsh-mobile-hanui): turns the sidebar into a drawer on
    # narrow viewports so the IDE tool-window menu no longer eats the editing
    # space. Kept in sync with DshHomeManager.MOBILE_SHELL_BUNDLE (Kotlin).
    [string]$HanuiVersion = "0.2.5",
    [string]$Mirror = "https://registry.npmmirror.com/-/binary/node",
    [string]$NpmRegistry = "",
    [string]$CacheDir = "",
    [string]$BootstrapNode = "node",
    [switch]$Bundle,
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

# Normalize to absolute paths: the script Push-Location's into $dshDir, which
# would break the relative $nodeExe / $npmCli references once the cwd changes.
if (-not [System.IO.Path]::IsPathRooted($OutputDir)) {
    $OutputDir = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $OutputDir))
}

if (-not $NpmRegistry) {
    $NpmRegistry = if ($env:npm_config_registry) { $env:npm_config_registry } else { "https://registry.npmmirror.com/" }
}
if (-not $CacheDir) {
    $CacheDir = Join-Path $OutputDir ".npm-cache"
}

$nodeZipName  = "node-v$NodeVersion-win-x64.zip"
$nodeUrl      = "$Mirror/v$NodeVersion/$nodeZipName"
$nodeZip      = Join-Path $OutputDir $nodeZipName
$nodeDir      = Join-Path $OutputDir "node"
$dshDir       = Join-Path $OutputDir "dsh"
$dshBin       = Join-Path $dshDir "node_modules/@deepseek-ai/dsh/lib/bin.js"
$nodeExe      = Join-Path $nodeDir "node.exe"
$bundleZip    = Join-Path (Split-Path -Parent $OutputDir) "runtime-bundle.zip"

function Write-Step([string]$msg) {
    Write-Host "==> $msg" -ForegroundColor Cyan
}

Write-Step "DSH runtime build: node v$NodeVersion + @deepseek-ai/dsh@$DshVersion -> $OutputDir"

# ---------- 1. Node.js ----------
if (Test-Path $nodeExe) {
    if ($Force) {
        Write-Step "Node exists, -Force rebuild"
        Remove-Item $nodeDir -Recurse -Force
    } else {
        Write-Step "Node exists, skip download"
        & $nodeExe -v | ForEach-Object { Write-Host "   node $_" }
    }
}

if (-not (Test-Path $nodeExe)) {
    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
    if (-not (Test-Path $nodeZip)) {
        Write-Step "Download $nodeZipName"
        Write-Host "   $nodeUrl"
        & $BootstrapNode (Join-Path $PSScriptRoot "download-node.mjs") $nodeUrl $nodeZip
        if ($LASTEXITCODE -ne 0) { throw "download failed: $nodeUrl" }
    } else {
        Write-Step "Reuse existing $nodeZipName"
    }

    Write-Step "Verify SHA-256"
    $actual = (& $BootstrapNode (Join-Path $PSScriptRoot "sha256.mjs") $nodeZip)
    $actual = "$actual".Trim()
    if ($actual -ne $NodeSha256) {
        throw "SHA-256 mismatch: expected $NodeSha256, got $actual ($nodeZip)"
    }
    Write-Host "   ok: $actual"

    Write-Step "Extract to node/"
    tar -xf $nodeZip -C $OutputDir
    if ($LASTEXITCODE -ne 0) {
        Expand-Archive -Path $nodeZip -DestinationPath $OutputDir -Force
    }
    $extracted = Join-Path $OutputDir "node-v$NodeVersion-win-x64"
    if (Test-Path $extracted) {
        Move-Item $extracted $nodeDir -Force
    }
    if (-not (Test-Path $nodeExe)) { throw "node.exe not found: $nodeExe" }
    & $nodeExe -v | ForEach-Object { Write-Host "   node $_" }
}

# ---------- 2. @deepseek-ai/dsh ----------
# The runtime dsh tree must also contain dsh-mobile-hanui (the web profile's
# profiles/node_modules junction points here; dsh resolves the bundle by name).
# Guaranteed on both the fresh-install and the "existing dsh + top-up" paths.
$hanuiPkgDir = Join-Path $dshDir "node_modules/dsh-mobile-hanui"

if (Test-Path $dshBin) {
    if ($Force) {
        Write-Step "dsh exists, -Force reinstall"
        Remove-Item $dshDir -Recurse -Force
    } else {
        Write-Step "dsh exists, skip install"
        $dshPkgJson = Join-Path $dshDir "node_modules/@deepseek-ai/dsh/package.json"
        if (Test-Path $dshPkgJson) {
            $ver = (Get-Content $dshPkgJson -Raw | ConvertFrom-Json).version
            Write-Host "   dsh $ver"
        }
        # Top-up path: dsh tree exists but dsh-mobile-hanui is missing (runtime upgrade)
        if (-not (Test-Path (Join-Path $hanuiPkgDir "package.json"))) {
            Write-Step "Install dsh-mobile-hanui@$HanuiVersion into existing dsh tree"
            $npmCli = Join-Path $nodeDir "node_modules/npm/bin/npm-cli.js"
            if (-not (Test-Path $npmCli)) { throw "bundled npm-cli.js missing: $npmCli" }
            Push-Location $dshDir
            try {
                & $nodeExe $npmCli install "dsh-mobile-hanui@$HanuiVersion" --no-audit --no-fund --cache $CacheDir --registry $NpmRegistry
                if ($LASTEXITCODE -ne 0) { throw "npm install dsh-mobile-hanui failed with exit $LASTEXITCODE" }
            } finally {
                Pop-Location
            }
            if (-not (Test-Path (Join-Path $hanuiPkgDir "package.json"))) { throw "dsh-mobile-hanui missing after install" }
        }
    }
}

if (-not (Test-Path $dshBin)) {
    Write-Step "Install @deepseek-ai/dsh@$DshVersion + dsh-mobile-hanui@$HanuiVersion (npm --ignore-scripts)"
    New-Item -ItemType Directory -Force -Path $dshDir | Out-Null
    $pkgJson = '{"name":"dsh-runtime","private":true,"dependencies":{"@deepseek-ai/dsh":"' + $DshVersion + '","dsh-mobile-hanui":"' + $HanuiVersion + '"}}'
    [System.IO.File]::WriteAllText((Join-Path $dshDir "package.json"), $pkgJson, [System.Text.UTF8Encoding]::new($false))

    $npmCli = Join-Path $nodeDir "node_modules/npm/bin/npm-cli.js"
    if (-not (Test-Path $npmCli)) { throw "bundled npm-cli.js missing: $npmCli" }

    Push-Location $dshDir
    try {
        & $nodeExe $npmCli install --ignore-scripts --no-audit --no-fund --cache $CacheDir --registry $NpmRegistry
        if ($LASTEXITCODE -ne 0) { throw "npm install failed with exit $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
    if (-not (Test-Path $dshBin)) { throw "dsh bin missing after install: $dshBin" }
    if (-not (Test-Path (Join-Path $hanuiPkgDir "package.json"))) { throw "dsh-mobile-hanui missing after install: $hanuiPkgDir" }
    Write-Host "   ok: $dshBin"
}

# ---------- 3. Smoke verify ----------
Write-Step "Smoke verification"
$resolvedDshDir = if ([System.IO.Path]::IsPathRooted($dshDir)) { $dshDir } else { Join-Path $root $dshDir }
$dshPkgDir = (Join-Path $resolvedDshDir "node_modules/@deepseek-ai/dsh") -replace '\\','/'
& $nodeExe -e "const p=require(process.argv[1] + '/package.json');console.log('   dsh ' + p.version);" $dshPkgDir
if ($LASTEXITCODE -ne 0) { throw "dsh version check failed" }
$hanuiPkg = (Join-Path $resolvedDshDir "node_modules/dsh-mobile-hanui/package.json") -replace '\\','/'
& $nodeExe -e "const p=require(process.argv[1]);console.log('   dsh-mobile-hanui ' + p.version);" $hanuiPkg
if ($LASTEXITCODE -ne 0) { throw "dsh-mobile-hanui check failed" }

# ---------- 4. Bundle ----------
if ($Bundle) {
    Write-Step "Bundle runtime-bundle.zip"
    if (Test-Path $bundleZip) { Remove-Item $bundleZip -Force }
    $excludes = @("*.zip", ".npm-cache")
    $tarArgs = @("-a", "-c", "-f", $bundleZip)
    foreach ($ex in $excludes) { $tarArgs += @("--exclude", $ex) }
    $tarArgs += @("-C", $OutputDir, "node", "dsh")
    tar @tarArgs
    if ($LASTEXITCODE -ne 0) {
        Compress-Archive -Path (Join-Path $OutputDir "node"), (Join-Path $OutputDir "dsh") -DestinationPath $bundleZip -Force
    }
    $sizeMb = [math]::Round(((Get-Item $bundleZip).Length / 1MB), 1)
    Write-Host "   -> $bundleZip ($sizeMb MB)"
}

Write-Step "Done: $OutputDir"
Write-Host "   node: $nodeExe"
Write-Host "   dsh : $dshBin"
