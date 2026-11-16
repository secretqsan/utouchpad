#requires -Version 5.1
<#
    Utochpad installer.

    Publishes the Windows service and the credential provider, builds the Tauri
    control panel, then registers the service, the COM credential provider and
    the firewall rule. Run from an elevated PowerShell; the script elevates
    itself when needed.

    Usage:
        .\install.ps1
        .\install.ps1 -ViiperExe "D:\tools\viiper.exe"
        .\install.ps1 -PanelExe "D:\build\Utochpad.exe"
        .\install.ps1 -SkipBuild
#>
[CmdletBinding()]
param(
    [string]$InstallDir = (Join-Path $env:ProgramFiles 'Utochpad'),
    [string]$ViiperExe = '',
    [string]$PanelExe = '',
    [switch]$SkipBuild,
    [switch]$NoStart
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$scriptRoot = $PSScriptRoot
$windowsRoot = Split-Path -Parent $scriptRoot
$repoRoot = Split-Path -Parent $windowsRoot
$panelProjectDir = Join-Path $windowsRoot 'panel'
$serviceName = 'UtochpadService'
$cpGuid = 'FDF19A17-3800-34D6-B49A-CAE0D53ACA8F'
$firewallRule = 'Utochpad Phone (TCP)'
$cpProviderKey = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\{$cpGuid}"
$cpClsidKey = "HKLM:\SOFTWARE\Classes\CLSID\{$cpGuid}"

function Test-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Write-Step([string]$Text) {
    Write-Host ''
    Write-Host "==> $Text" -ForegroundColor Cyan
}

function Resolve-PanelBinary([string]$ProjectDir) {
    $candidate = Join-Path $ProjectDir 'src-tauri\target\release\Utochpad.exe'
    if (Test-Path -LiteralPath $candidate) { return $candidate }
    return $null
}

if (-not (Test-Administrator)) {
    Write-Host '需要管理员权限，正在重新以管理员身份启动安装程序…' -ForegroundColor Yellow

    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"")
    foreach ($name in @('InstallDir', 'ViiperExe', 'PanelExe')) {
        if ($PSBoundParameters.ContainsKey($name)) {
            $arguments += "-$name"
            $arguments += "`"$($PSBoundParameters[$name])`""
        }
    }
    foreach ($name in @('SkipBuild', 'NoStart')) {
        if ($PSBoundParameters.ContainsKey($name) -and $PSBoundParameters[$name]) {
            $arguments += "-$name"
        }
    }

    Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') -Verb RunAs -ArgumentList $arguments
    exit
}

$serviceDir = Join-Path $InstallDir 'Service'
$panelDir = Join-Path $InstallDir 'Panel'
$cpDir = Join-Path $InstallDir 'CP'
$serviceExe = Join-Path $serviceDir 'Utochpad.Service.exe'
$panelTargetExe = Join-Path $panelDir 'Utochpad.exe'
$comhost = Join-Path $cpDir 'Utochpad.CredentialProvider.comhost.dll'

Write-Host 'Utochpad 安装程序' -ForegroundColor Green
Write-Host "安装目录：$InstallDir"

# ---------------------------------------------------------------------------
# Stop existing processes/services before replacing files.
# ---------------------------------------------------------------------------
Write-Step '停止旧的 Utochpad 组件'

foreach ($name in @('Utochpad', 'utochpad-settings', 'Utouchpad.Winapp')) {
    Get-Process -Name $name -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
}

if (Get-Service -Name $serviceName -ErrorAction SilentlyContinue) {
    Stop-Service -Name $serviceName -Force -ErrorAction SilentlyContinue

    & (Join-Path $env:SystemRoot 'System32\sc.exe') delete $serviceName | Out-Null

    for ($i = 0; $i -lt 20 -and (Get-Service -Name $serviceName -ErrorAction SilentlyContinue); $i++) {
        Start-Sleep -Milliseconds 500
    }

    if (Get-Service -Name $serviceName -ErrorAction SilentlyContinue) {
        throw '无法删除旧服务 UtochpadService。请重启电脑后重新运行安装程序。'
    }

    Write-Host '  旧服务已移除'
}

# ---------------------------------------------------------------------------
# Build and publish.
# ---------------------------------------------------------------------------
if (-not $SkipBuild) {
    Write-Step '检查 .NET SDK'

    $dotnet = Get-Command dotnet -ErrorAction SilentlyContinue
    if (-not $dotnet) {
        throw '找不到 dotnet。请安装 .NET 10 SDK 后重试。'
    }
    Write-Host "  $(& dotnet --version)"

    if (Test-Path $InstallDir) {
        Remove-Item -Path $InstallDir -Recurse -Force -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 500
        if (Test-Path $InstallDir) {
            try {
                Remove-Item -Path $InstallDir -Recurse -Force
            }
            catch {
                throw "无法删除旧安装目录 $InstallDir，可能有凭据提供程序仍被锁屏界面占用。请注销或重启后重新运行 install.ps1。`n$($_.Exception.Message)"
            }
        }
    }

    New-Item -ItemType Directory -Force -Path $serviceDir, $panelDir, $cpDir | Out-Null

    Write-Step '发布 Windows 服务'
    & dotnet publish (Join-Path $windowsRoot 'Service\Utochpad.Service\Utochpad.Service.csproj') `
        -c Release -r win-x64 --self-contained true -o $serviceDir --nologo
    if ($LASTEXITCODE -ne 0) { throw '发布 Windows 服务失败。' }

    Write-Step '发布凭据提供程序'
    # COM hosting cannot be self-contained: LogonUI loads the COM host through
    # the shared .NET runtime, so the CP must stay framework-dependent.
    & dotnet publish (Join-Path $windowsRoot 'Service\Utochpad.CredentialProvider\Utochpad.CredentialProvider.csproj') `
        -c Release -r win-x64 --self-contained false -o $cpDir --nologo
    if ($LASTEXITCODE -ne 0) { throw '发布凭据提供程序失败。' }

    Write-Step '准备控制面板'

    $panelSource = $null
    if ($PanelExe) {
        if (-not (Test-Path -LiteralPath $PanelExe)) { throw "找不到面板程序：$PanelExe" }
        $panelSource = (Resolve-Path -LiteralPath $PanelExe).Path
    }
    else {
        foreach ($tool in @('bun', 'cargo')) {
            if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
                throw "构建 Tauri 面板需要 $tool。请先安装 bun 和 Rust（MSVC toolchain），或用 -PanelExe 指定已构建的 Utochpad.exe。"
            }
        }

        Push-Location $panelProjectDir
        try {
            Write-Host '  bun install'
            & bun install --frozen-lockfile
            if ($LASTEXITCODE -ne 0) { throw 'bun install 失败。' }

            Write-Host '  bun run tauri build --no-bundle'
            & bun run tauri build --no-bundle
            if ($LASTEXITCODE -ne 0) { throw '构建 Tauri 面板失败。' }
        }
        finally {
            Pop-Location
        }

        $panelSource = Resolve-PanelBinary $panelProjectDir
        if (-not $panelSource) {
            throw "没有找到构建产物：$panelProjectDir\src-tauri\target\release\Utochpad.exe"
        }
    }

    Copy-Item -LiteralPath $panelSource -Destination $panelTargetExe -Force
    Write-Host "  $panelSource"
}

$runtimes = & dotnet --list-runtimes 2>$null
if (-not ($runtimes | Select-String -Pattern '^Microsoft\.NETCore\.App 10\.' -Quiet)) {
    throw @'
凭据提供程序以框架依赖模式运行（.NET 的 COM 托管不支持自包含），
但本机没有检测到 .NET 10 运行时。请先安装：

    winget install Microsoft.DotNet.Runtime.10

或从 https://dotnet.microsoft.com/download/dotnet/10.0 下载 .NET Runtime 10，
安装后重新运行 install.ps1。
'@
}

if (-not (Test-Path $serviceExe)) { throw "找不到服务程序：$serviceExe。请先执行不带 -SkipBuild 的安装。" }
if (-not (Test-Path $panelTargetExe)) { throw "找不到控制面板：$panelTargetExe。请先执行不带 -SkipBuild 的安装，或用 -PanelExe 指定。" }
if (-not (Test-Path $comhost)) { throw "找不到凭据提供程序 COM 宿主：$comhost。请确认 CP 项目启用了 EnableComHosting。" }

# ---------------------------------------------------------------------------
# VIIPER.
# ---------------------------------------------------------------------------
Write-Step '部署 VIIPER'

$viiperCandidates = @()
if ($ViiperExe) { $viiperCandidates += $ViiperExe }
# Built by Windows\installer\build-viiper.ps1 (pinned upstream + Windows\viiper).
$viiperCandidates += (Join-Path $repoRoot 'artifacts\viiper\viiper.exe')

$viiperSource = $null
foreach ($candidate in $viiperCandidates) {
    if ($candidate -and (Test-Path -LiteralPath $candidate)) {
        $viiperSource = (Resolve-Path -LiteralPath $candidate).Path
        break
    }
}

if ($viiperSource) {
    $viiperTarget = Join-Path $serviceDir 'viiper\viiper.exe'
    New-Item -ItemType Directory -Force -Path (Split-Path $viiperTarget) | Out-Null
    Copy-Item -LiteralPath $viiperSource -Destination $viiperTarget -Force
    Write-Host "  $viiperSource"
}
else {
    Write-Warning '未找到 viiper.exe。安装会继续，但触控板和 Radial 功能不可用。请用 -ViiperExe 指定路径后重新运行。'
}

# ---------------------------------------------------------------------------
# Windows service.
# ---------------------------------------------------------------------------
Write-Step '注册 Windows 服务'

New-Service -Name $serviceName `
    -BinaryPathName "`"$serviceExe`"" `
    -DisplayName 'Utochpad Service' `
    -StartupType Automatic | Out-Null

& (Join-Path $env:SystemRoot 'System32\sc.exe') description $serviceName `
    'Utochpad 手机解锁、触控板与 Surface Dial 桥接服务' | Out-Null
& (Join-Path $env:SystemRoot 'System32\sc.exe') failure $serviceName `
    reset= 86400 actions= restart/5000/restart/5000/restart/5000 | Out-Null

Start-Service -Name $serviceName
Write-Host '  服务已启动（LocalSystem，开机自启）'

# ---------------------------------------------------------------------------
# Credential provider (COM registration + provider key).
# ---------------------------------------------------------------------------
Write-Step '注册凭据提供程序'

$regsvr = Join-Path $env:SystemRoot 'System32\regsvr32.exe'
$regResult = Start-Process -FilePath $regsvr -ArgumentList @('/s', "`"$comhost`"") -Wait -PassThru

if (-not (Test-Path $cpClsidKey)) {
    throw "凭据提供程序 COM 注册失败（regsvr32 退出码 $($regResult.ExitCode)）。请检查 $comhost 及其依赖是否完整。"
}

New-Item -Path $cpProviderKey -Force | Out-Null
Set-ItemProperty -Path $cpProviderKey -Name '(default)' -Value 'Utochpad'
Write-Host "  CLSID {$cpGuid} 已注册"

# ---------------------------------------------------------------------------
# Firewall.
# ---------------------------------------------------------------------------
Write-Step '配置防火墙'

$port = 42800
$configPath = Join-Path $env:ProgramData 'Utochpad\config.json'
for ($i = 0; $i -lt 20 -and -not (Test-Path $configPath); $i++) {
    Start-Sleep -Milliseconds 250
}
if (Test-Path $configPath) {
    try {
        $port = [int](Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json).port
    }
    catch {
        $port = 42800
    }
}

Get-NetFirewallRule -DisplayName $firewallRule -ErrorAction SilentlyContinue |
    Remove-NetFirewallRule -ErrorAction SilentlyContinue

New-NetFirewallRule -DisplayName $firewallRule `
    -Description '允许同一局域网内的手机连接 Utochpad 服务' `
    -Direction Inbound -Action Allow -Protocol TCP -LocalPort $port `
    -Profile Private, Domain | Out-Null

Write-Host "  已允许 TCP $port（专用/域网络）"

# ---------------------------------------------------------------------------
# Start menu shortcut.
# ---------------------------------------------------------------------------
Write-Step '创建开始菜单快捷方式'

$startMenu = Join-Path ([Environment]::GetFolderPath('CommonStartMenu')) 'Programs'
$shortcutPath = Join-Path $startMenu 'Utochpad.lnk'
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $panelTargetExe
$shortcut.WorkingDirectory = $panelDir
$shortcut.IconLocation = $panelTargetExe
$shortcut.Description = 'Utochpad 手机触控板'
$shortcut.Save()
Write-Host "  $shortcutPath"

# ---------------------------------------------------------------------------
# Done.
# ---------------------------------------------------------------------------
Write-Step '安装完成'

Write-Host ''
Write-Host '接下来：' -ForegroundColor Green
Write-Host '  1. 打开 Utochpad（开始菜单），点击“开始配对”，用手机扫描二维码。'
Write-Host '  2. 配对后，在 Utochpad 中输入 Windows 账户密码并发送到手机；手机会要求验证指纹。'
Write-Host '  3. 锁屏后点击 Utochpad 凭据提供程序磁贴，手机前台 App 会弹出指纹提示。'
Write-Host ''
Write-Host '注意：' -ForegroundColor Yellow
Write-Host '  - 触控板和 Radial 需要预装 usbip-win2 驱动（https://github.com/vadimgrn/usbip-win2）。'
Write-Host '  - 手机 App 必须连接与电脑相同的局域网，并保持在前台。'
Write-Host '  - 从旧版本升级时，请重新配对并重新发送 Windows 密码。'
Write-Host '  - 如需卸载，请以管理员身份运行 uninstall.ps1。'
Write-Host ''

if (-not $NoStart) {
    Start-Process -FilePath $panelTargetExe
}
