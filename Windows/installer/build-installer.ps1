#requires -Version 5.1
<#
.SYNOPSIS
    Builds the Utochpad Windows installer (Utochpad-Setup-<version>.exe).

.DESCRIPTION
    Publishes the service and credential provider, builds the Tauri control
    panel and (when needed) viiper.exe, stages everything, and compiles the
    Inno Setup script. Prerequisite runtimes are not bundled: Windows normally
    provides WebView2, and the installer warns when the .NET 10 runtime needed
    by the credential provider is missing. The USBip driver is not bundled
    either; the installer detects it and tells the user where to get it.

.PARAMETER Version
    Installer version. Defaults to the version in
    Windows\panel\src-tauri\tauri.conf.json.

.PARAMETER PanelExe
    Use an already built Utochpad.exe instead of running the Tauri build.

.PARAMETER SkipPanel
    Use Windows\panel\src-tauri\target\release\Utochpad.exe if it exists.

.PARAMETER SkipViiper
    Reuse artifacts\viiper\viiper.exe instead of rebuilding it.

.PARAMETER ForceViiper
    Rebuild viiper.exe even when artifacts\viiper\viiper.exe exists.

.PARAMETER Iscc
    Path to ISCC.exe. By default the script searches the usual Inno Setup 6/7
    install locations.

.EXAMPLE
    .\Windows\installer\build-installer.ps1

.EXAMPLE
    .\Windows\installer\build-installer.ps1 -ForceViiper
#>
[CmdletBinding()]
param(
    [string]$Version,
    [string]$PanelExe,
    [switch]$SkipPanel,
    [switch]$SkipViiper,
    [switch]$ForceViiper,
    [string]$Iscc
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$scriptRoot = $PSScriptRoot
$windowsRoot = Split-Path -Parent $scriptRoot
$repoRoot = Split-Path -Parent $windowsRoot
$serviceProject = Join-Path $windowsRoot 'Service\Utochpad.Service\Utochpad.Service.csproj'
$cpProject = Join-Path $windowsRoot 'Service\Utochpad.CredentialProvider\Utochpad.CredentialProvider.csproj'
$lithnetProject = Join-Path $windowsRoot 'Service\Lithnet.CredentialProvider\src\Lithnet.CredentialProvider\Lithnet.CredentialProvider.csproj'
$panelDir = Join-Path $windowsRoot 'panel'
$artifactsDir = Join-Path $repoRoot 'artifacts\installer'
$stagingDir = Join-Path $artifactsDir 'staging'

function Write-Step([string]$Text) {
    Write-Host ''
    Write-Host "==> $Text" -ForegroundColor Cyan
}

function Assert-ExitCode([string]$What) {
    if ($LASTEXITCODE -ne 0) {
        throw "$What 失败（退出码 $LASTEXITCODE）。"
    }
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$ArgumentList,
        [string]$WorkingDirectory
    )

    Push-Location -LiteralPath (Get-Location).Path
    try {
        if ($WorkingDirectory) { Set-Location -LiteralPath $WorkingDirectory }
        & $FilePath @ArgumentList
        Assert-ExitCode $FilePath
    }
    finally {
        Pop-Location
    }
}

function Resolve-PanelBinary([string]$ReleaseDir) {
    $candidate = Join-Path $ReleaseDir 'Utochpad.exe'
    if (Test-Path -LiteralPath $candidate) { return $candidate }
    return $null
}

function Resolve-Iscc([string]$Explicit) {
    if ($Explicit) {
        if (-not (Test-Path -LiteralPath $Explicit)) { throw "找不到 ISCC.exe：$Explicit" }
        return (Resolve-Path -LiteralPath $Explicit).Path
    }

    $candidates = @(
        (Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 7\ISCC.exe'),
        (Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 6\ISCC.exe'),
        'C:\Program Files\Inno Setup 7\ISCC.exe',
        'C:\Program Files (x86)\Inno Setup 7\ISCC.exe',
        'C:\Program Files\Inno Setup 6\ISCC.exe',
        'C:\Program Files (x86)\Inno Setup 6\ISCC.exe'
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }

    $command = Get-Command iscc -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }

    throw @'
找不到 Inno Setup 编译器（ISCC.exe）。

请先安装 Inno Setup 7（免费版即可）：

    winget install --id JRSoftware.InnoSetup.7 -e

或者从 https://jrsoftware.org/isdl.php 下载安装，然后用 -Iscc 指定 ISCC.exe。
'@
}

# ---------------------------------------------------------------------------
# Version.
# ---------------------------------------------------------------------------
$tauriConfigPath = Join-Path $panelDir 'src-tauri\tauri.conf.json'
if (-not $Version) {
    if (-not (Test-Path -LiteralPath $tauriConfigPath)) {
        throw "找不到 $tauriConfigPath，无法确定版本号。"
    }
    $Version = (Get-Content -LiteralPath $tauriConfigPath -Raw | ConvertFrom-Json).version
}
if ($Version -notmatch '^\d+(\.\d+){1,3}$') {
    throw "版本号必须是 1.2.3 或 1.2.3.4 格式，实际为：$Version"
}
$versionParts = @($Version.Split('.'))
while ($versionParts.Count -lt 4) { $versionParts += '0' }
$versionQuad = ($versionParts[0..3] -join '.')

Write-Host "Utochpad 安装包构建" -ForegroundColor Green
Write-Host "版本：$Version"
Write-Host "输出：$artifactsDir"

# ---------------------------------------------------------------------------
# Toolchain and source checks.
# ---------------------------------------------------------------------------
Write-Step '检查构建环境'

if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
    throw '找不到 dotnet。请安装 .NET 10 SDK。'
}
if (-not (Test-Path -LiteralPath $lithnetProject)) {
    throw @'
凭据提供程序的子模块缺失：

    git submodule update --init --recursive
'@
}

# ---------------------------------------------------------------------------
# Staging.
# ---------------------------------------------------------------------------
Write-Step '准备暂存目录'

if (Test-Path -LiteralPath $stagingDir) {
    Remove-Item -LiteralPath $stagingDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path `
    (Join-Path $stagingDir 'Service'), `
    (Join-Path $stagingDir 'CP'), `
    (Join-Path $stagingDir 'Panel'), `
    (Join-Path $stagingDir 'Viiper') | Out-Null

# ---------------------------------------------------------------------------
# Publish .NET service and credential provider.
# ---------------------------------------------------------------------------
Write-Step '发布 Windows 服务（自包含）'

$serviceOut = Join-Path $stagingDir 'Service'
& dotnet publish $serviceProject -c Release -r win-x64 --self-contained true -o $serviceOut --nologo
Assert-ExitCode '发布 Windows 服务'

Write-Step '发布凭据提供程序（框架依赖）'

$cpOut = Join-Path $stagingDir 'CP'
& dotnet publish $cpProject -c Release -r win-x64 --self-contained false -o $cpOut --nologo
Assert-ExitCode '发布凭据提供程序'

if (-not (Test-Path -LiteralPath (Join-Path $serviceOut 'Utochpad.Service.exe'))) {
    throw "服务发布输出不完整：$serviceOut"
}
if (-not (Test-Path -LiteralPath (Join-Path $cpOut 'Utochpad.CredentialProvider.comhost.dll'))) {
    throw "凭据提供程序发布输出不完整：$cpOut"
}

# ---------------------------------------------------------------------------
# Control panel.
# ---------------------------------------------------------------------------
Write-Step '准备控制面板'

$panelOut = Join-Path $stagingDir 'Panel'
$releaseDir = Join-Path $panelDir 'src-tauri\target\release'
$panelSource = $null

if ($PanelExe) {
    if (-not (Test-Path -LiteralPath $PanelExe)) { throw "找不到面板程序：$PanelExe" }
    $panelSource = (Resolve-Path -LiteralPath $PanelExe).Path
    Copy-Item -LiteralPath $panelSource -Destination (Join-Path $panelOut 'Utochpad.exe') -Force
    Get-ChildItem -LiteralPath (Split-Path -Parent $panelSource) -Filter '*.dll' -File -ErrorAction SilentlyContinue |
        Copy-Item -Destination $panelOut -Force
}
else {
    if (-not $SkipPanel) {
        foreach ($tool in @('bun', 'cargo')) {
            if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
                throw "构建 Tauri 面板需要 $tool。请安装 bun 和 Rust（MSVC toolchain），或用 -PanelExe 指定已构建的 Utochpad.exe。"
            }
        }

        Invoke-Checked -FilePath 'bun' -ArgumentList @('install', '--frozen-lockfile') -WorkingDirectory $panelDir
        Invoke-Checked -FilePath 'bun' -ArgumentList @('run', 'tauri', 'build', '--no-bundle') -WorkingDirectory $panelDir
    }

    $panelSource = Resolve-PanelBinary $releaseDir
    if (-not $panelSource) {
        throw "没有找到面板构建产物：$releaseDir\Utochpad.exe"
    }

    Copy-Item -LiteralPath $panelSource -Destination (Join-Path $panelOut 'Utochpad.exe') -Force
    Get-ChildItem -LiteralPath $releaseDir -Filter '*.dll' -File -ErrorAction SilentlyContinue |
        Copy-Item -Destination $panelOut -Force
}

Write-Host "  $panelSource"

# ---------------------------------------------------------------------------
# VIIPER.
# ---------------------------------------------------------------------------
Write-Step '准备 VIIPER'

$viiperExe = Join-Path $repoRoot 'artifacts\viiper\viiper.exe'
if ($SkipViiper -and -not $ForceViiper) {
    if (-not (Test-Path -LiteralPath $viiperExe)) {
        throw "找不到 $viiperExe。去掉 -SkipViiper，或先运行 Windows\installer\build-viiper.ps1。"
    }
}
elseif ($ForceViiper -or -not (Test-Path -LiteralPath $viiperExe)) {
    & (Join-Path $scriptRoot 'build-viiper.ps1')
    Assert-ExitCode '构建 VIIPER'
}

if (-not (Test-Path -LiteralPath $viiperExe)) {
    throw "VIIPER 构建后仍未找到：$viiperExe"
}
Copy-Item -LiteralPath $viiperExe -Destination (Join-Path $stagingDir 'Viiper\viiper.exe') -Force
Write-Host "  $viiperExe"

# ---------------------------------------------------------------------------
# Third-party notices.
# ---------------------------------------------------------------------------
Copy-Item -LiteralPath (Join-Path $scriptRoot 'THIRD-PARTY-NOTICES.txt') `
    -Destination (Join-Path $stagingDir 'THIRD-PARTY-NOTICES.txt') -Force
Copy-Item -LiteralPath (Join-Path $scriptRoot 'GPL-3.0.txt') `
    -Destination (Join-Path $stagingDir 'GPL-3.0.txt') -Force

# ---------------------------------------------------------------------------
# Compile.
# ---------------------------------------------------------------------------
Write-Step '编译安装包'

$isccPath = Resolve-Iscc $Iscc
New-Item -ItemType Directory -Force -Path $artifactsDir | Out-Null

$isccArgs = @(
    "-DAppVersion=$Version",
    "-DAppVersionQuad=$versionQuad",
    "-DStagingDir=$stagingDir",
    "-DOutputDir=$artifactsDir",
    (Join-Path $scriptRoot 'Utochpad.iss')
)

& $isccPath @isccArgs
Assert-ExitCode '编译安装包'

$setupPath = Join-Path $artifactsDir "Utochpad-Setup-$Version.exe"
if (-not (Test-Path -LiteralPath $setupPath)) {
    throw "安装包未生成：$setupPath"
}

$item = Get-Item -LiteralPath $setupPath
$sha256 = (Get-FileHash -LiteralPath $setupPath -Algorithm SHA256).Hash
Write-Step '完成'
Write-Host ("{0}  ({1:N1} MB)" -f $item.FullName, ($item.Length / 1MB)) -ForegroundColor Green
Write-Host "SHA256: $sha256"
Write-Host ''
Write-Host '安装包会：'
Write-Host '  - 停止并替换旧服务与凭据提供程序；'
Write-Host '  - 检测 USBip 驱动，缺失时提示用户自行安装；'
Write-Host '  - 注册凭据提供程序、防火墙规则与开始菜单快捷方式；'
Write-Host '  - 在“应用和功能”中提供卸载入口，并可选择保留配对数据。'
