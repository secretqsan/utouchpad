#requires -Version 5.1
<#
.SYNOPSIS
    Builds the Utochpad Windows installer (Utochpad-Setup-<version>.exe).

.DESCRIPTION
    Publishes the service, credential provider, and framework-dependent WinUI
    control panel, builds viiper.exe when needed, stages everything, and
    compiles the Inno Setup script. The installer only detects the USBip
    driver; all other runtimes are neither checked nor installed.

    Inno Setup itself is a build-machine prerequisite that must be installed
    by the user. This script only looks for an existing ISCC.exe; it never
    downloads, extracts or otherwise ships the Inno Setup installer.

.PARAMETER Version
    Installer version. Defaults to the version in
    Windows\panel\Utochpad.Panel.csproj.

.PARAMETER PanelPublishDir
    Use a previously published framework-dependent WinUI panel directory.

.PARAMETER SkipPanel
    Reuse the framework-dependent WinUI publish output in .build\panel-publish.

.PARAMETER SkipViiper
    Reuse .build\viiper\viiper.exe instead of rebuilding it.

.PARAMETER ForceViiper
    Rebuild viiper.exe even when .build\viiper\viiper.exe exists.

.PARAMETER Iscc
    Path to ISCC.exe. By default the script looks on PATH and then in the usual
    Inno Setup 6/7 install locations.

.EXAMPLE
    .\Windows\installer\build-installer.ps1

.EXAMPLE
    .\Windows\installer\build-installer.ps1 -ForceViiper
#>
[CmdletBinding()]
param(
    [string]$Version,
    [string]$PanelPublishDir,
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
$panelProject = Join-Path $windowsRoot 'panel\Utochpad.Panel.csproj'
$artifactsDir = Join-Path $repoRoot '.build\installer'
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

function Assert-PanelPublish([string]$PublishDir) {
    foreach ($file in @('Utochpad.exe', 'Utochpad.dll', 'Utochpad.deps.json', 'Utochpad.runtimeconfig.json', 'Utochpad.pri')) {
        if (-not (Test-Path -LiteralPath (Join-Path $PublishDir $file))) {
            throw "WinUI 面板发布目录不完整，缺少 $file：$PublishDir"
        }
    }
}

function Get-NormalizedDirectory([string]$Path) {
    $trimmed = $Path.Trim().Trim('"')
    if (-not $trimmed) { return $null }
    try {
        return [System.IO.Path]::GetFullPath($trimmed).TrimEnd('\')
    }
    catch {
        return $null
    }
}

function Assert-IsccVersion([string]$Path) {
    $versionText = (Get-Item -LiteralPath $Path).VersionInfo.ProductVersion
    if ($versionText -notmatch '(\d+)\.(\d+)' -or $versionText -match '^0\.0(?:\.|$)') {
        $installDir = Get-NormalizedDirectory ([System.IO.Path]::GetDirectoryName($Path))
        $uninstallRoots = @(
            'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall',
            'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall'
        )

        foreach ($root in $uninstallRoots) {
            foreach ($key in (Get-ChildItem -LiteralPath $root -ErrorAction SilentlyContinue)) {
                $registration = Get-ItemProperty -LiteralPath $key.PSPath -ErrorAction SilentlyContinue
                if (-not $registration.InstallLocation) { continue }

                # Some uninstall entries wrap InstallLocation in quotes.
                $registeredDir = Get-NormalizedDirectory ([string]$registration.InstallLocation)
                if ($registeredDir -and $registeredDir -ieq $installDir -and $registration.DisplayVersion) {
                    $versionText = [string]$registration.DisplayVersion
                    break
                }
            }
            if ($versionText -match '(\d+)\.(\d+)' -and $versionText -notmatch '^0\.0(?:\.|$)') {
                break
            }
        }
    }

    if ($versionText -notmatch '(\d+)\.(\d+)' -or $versionText -match '^0\.0(?:\.|$)') {
        throw "无法读取 Inno Setup 版本：$Path"
    }

    $major = [int]$Matches[1]
    $minor = [int]$Matches[2]
    if (($major -lt 6) -or (($major -eq 6) -and ($minor -lt 1))) {
        throw "需要 Inno Setup 6.1 或更高版本：$Path"
    }
    return $Path
}

function Resolve-Iscc([string]$Explicit) {
    if ($Explicit) {
        if (-not (Test-Path -LiteralPath $Explicit)) { throw "找不到 ISCC.exe：$Explicit" }
        return Assert-IsccVersion (Resolve-Path -LiteralPath $Explicit).Path
    }

    $command = Get-Command iscc -ErrorAction SilentlyContinue
    if ($command) { return Assert-IsccVersion $command.Source }

    $installDirs = @()
    if ($env:LOCALAPPDATA) { $installDirs += Join-Path $env:LOCALAPPDATA 'Programs' }
    if ($env:ProgramFiles) { $installDirs += $env:ProgramFiles }
    if (${env:ProgramFiles(x86)}) { $installDirs += ${env:ProgramFiles(x86)} }

    foreach ($dir in $installDirs) {
        foreach ($version in @('7', '6')) {
            $candidate = Join-Path $dir "Inno Setup $version\ISCC.exe"
            if (Test-Path -LiteralPath $candidate) { return Assert-IsccVersion $candidate }
        }
    }

    throw @'
找不到 Inno Setup 编译器（ISCC.exe）。

Inno Setup 是构建机上的前置工具：本脚本只会查找已安装的 ISCC.exe，
不会下载、解压或随仓库分发 Inno Setup 的安装程序。请自行安装
Inno Setup 7（免费版即可）：

    winget install --id JRSoftware.InnoSetup.7 -e

也可以从 https://jrsoftware.org/isdl.php 自行下载安装；如果装在非默认位置，
用 -Iscc "…\ISCC.exe" 指定路径。
'@
}

# ---------------------------------------------------------------------------
# Version.
# ---------------------------------------------------------------------------
if (-not (Test-Path -LiteralPath $panelProject)) {
    throw "找不到 $panelProject，无法确定版本号。"
}
[xml]$projectXml = Get-Content -LiteralPath $panelProject -Raw
if (-not $Version) {
    $Version = [string]$projectXml.Project.PropertyGroup.Version
    if (-not $Version) {
        throw "项目中没有定义 Version：$panelProject"
    }
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
# WinUI control panel.
# ---------------------------------------------------------------------------
Write-Step '发布 WinUI 控制面板'

$panelOut = Join-Path $stagingDir 'Panel'
$publishDir = Join-Path $repoRoot '.build\panel-publish'

if ($PanelPublishDir) {
    if (-not (Test-Path -LiteralPath $PanelPublishDir -PathType Container)) {
        throw "找不到 WinUI 面板发布目录：$PanelPublishDir"
    }
    $publishDir = (Resolve-Path -LiteralPath $PanelPublishDir).Path
}
elseif (-not $SkipPanel) {
    if (-not (Test-Path -LiteralPath $panelProject)) {
        throw "找不到 WinUI 面板项目：$panelProject"
    }
    if (Test-Path -LiteralPath $publishDir) {
        Remove-Item -LiteralPath $publishDir -Recurse -Force
    }
    & dotnet publish $panelProject -c Release -r win-x64 --self-contained false `
        -p:WindowsAppSDKSelfContained=false -p:PublishTrimmed=false -p:PublishSingleFile=false `
        "-p:Version=$Version" -o $publishDir --nologo
    Assert-ExitCode '发布 WinUI 控制面板'
}

Assert-PanelPublish $publishDir
Copy-Item -Path (Join-Path $publishDir '*') -Destination $panelOut -Recurse -Force
$panelSize = (Get-ChildItem -LiteralPath $panelOut -File -Recurse | Measure-Object Length -Sum).Sum
Write-Host ("  {0} ({1:N1} MB)" -f $publishDir, ($panelSize / 1MB))

# ---------------------------------------------------------------------------
# VIIPER.
# ---------------------------------------------------------------------------
Write-Step '准备 VIIPER'

$viiperExe = Join-Path $repoRoot '.build\viiper\viiper.exe'
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
# License texts and third-party notices.
# ---------------------------------------------------------------------------
Copy-Item -LiteralPath (Join-Path $scriptRoot 'THIRD-PARTY-NOTICES.txt') `
    -Destination (Join-Path $stagingDir 'THIRD-PARTY-NOTICES.txt') -Force
Copy-Item -LiteralPath (Join-Path $scriptRoot 'GPL-3.0.txt') `
    -Destination (Join-Path $stagingDir 'GPL-3.0.txt') -Force
Copy-Item -LiteralPath (Join-Path $repoRoot 'LICENSE') `
    -Destination (Join-Path $stagingDir 'LICENSE-APACHE-2.0.txt') -Force

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
