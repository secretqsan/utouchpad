#requires -Version 5.1
<#
    Utochpad uninstaller.

    Removes the Windows service, the credential provider registration, the
    firewall rule, the start menu shortcut and the program files. Pairing data
    in %ProgramData%\Utochpad is kept unless -PurgeData is passed.

    Usage:
        .\uninstall.ps1
        .\uninstall.ps1 -PurgeData
#>
[CmdletBinding()]
param(
    [string]$InstallDir = (Join-Path $env:ProgramFiles 'Utochpad'),
    [switch]$PurgeData
)

$ErrorActionPreference = 'Stop'

$serviceName = 'UtochpadService'
$cpGuid = 'FDF19A17-3800-34D6-B49A-CAE0D53ACA8F'
$firewallRule = 'Utochpad Phone (TCP)'
$cpProviderKey = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\{$cpGuid}"
$cpClsidKey = "HKLM:\SOFTWARE\Classes\CLSID\{$cpGuid}"
$dataDir = Join-Path $env:ProgramData 'Utochpad'

function Test-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Write-Step([string]$Text) {
    Write-Host ''
    Write-Host "==> $Text" -ForegroundColor Cyan
}

if (-not (Test-Administrator)) {
    Write-Host '需要管理员权限，正在重新以管理员身份启动卸载程序…' -ForegroundColor Yellow

    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"")
    if ($PSBoundParameters.ContainsKey('InstallDir')) {
        $arguments += '-InstallDir'
        $arguments += "`"$($PSBoundParameters['InstallDir'])`""
    }
    if ($PurgeData) {
        $arguments += '-PurgeData'
    }

    Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') -Verb RunAs -ArgumentList $arguments
    exit
}

Write-Host 'Utochpad 卸载程序' -ForegroundColor Green

# ---------------------------------------------------------------------------
# Processes and service.
# ---------------------------------------------------------------------------
Write-Step '停止 Utochpad'

foreach ($name in @('Utochpad')) {
    Get-Process -Name $name -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
}
Get-Process -Name 'viiper' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue

if (Get-Service -Name $serviceName -ErrorAction SilentlyContinue) {
    Stop-Service -Name $serviceName -Force -ErrorAction SilentlyContinue

    & (Join-Path $env:SystemRoot 'System32\sc.exe') delete $serviceName | Out-Null

    for ($i = 0; $i -lt 20 -and (Get-Service -Name $serviceName -ErrorAction SilentlyContinue); $i++) {
        Start-Sleep -Milliseconds 500
    }

    Write-Host '  服务已移除'
}
else {
    Write-Host '  服务未安装'
}

# ---------------------------------------------------------------------------
# Credential provider.
# ---------------------------------------------------------------------------
Write-Step '注销凭据提供程序'

$comhost = Join-Path $InstallDir 'CP\Utochpad.CredentialProvider.comhost.dll'
if (Test-Path -LiteralPath $comhost) {
    Start-Process -FilePath (Join-Path $env:SystemRoot 'System32\regsvr32.exe') `
        -ArgumentList @('/u', '/s', "`"$comhost`"") -Wait -WindowStyle Hidden | Out-Null
    Write-Host '  COM 注册已删除'
}

if (Test-Path $cpProviderKey) {
    Remove-Item -Path $cpProviderKey -Recurse -Force
    Write-Host '  凭据提供程序注册表项已删除'
}

if (Test-Path $cpClsidKey) {
    Remove-Item -Path $cpClsidKey -Recurse -Force
    Write-Host '  CLSID 注册已删除'
}

# ---------------------------------------------------------------------------
# Firewall and shortcut.
# ---------------------------------------------------------------------------
Write-Step '清理防火墙与快捷方式'

Get-NetFirewallRule -DisplayName $firewallRule -ErrorAction SilentlyContinue |
    Remove-NetFirewallRule -ErrorAction SilentlyContinue

$shortcutPath = Join-Path ([Environment]::GetFolderPath('CommonStartMenu')) 'Programs\Utochpad.lnk'
if (Test-Path -LiteralPath $shortcutPath) {
    Remove-Item -LiteralPath $shortcutPath -Force
}

Write-Host '  已清理'

# ---------------------------------------------------------------------------
# Files.
# ---------------------------------------------------------------------------
Write-Step '删除程序文件'

if (Test-Path -LiteralPath $InstallDir) {
    Remove-Item -LiteralPath $InstallDir -Recurse -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 500
    if (Test-Path -LiteralPath $InstallDir) {
        try {
            Remove-Item -LiteralPath $InstallDir -Recurse -Force
        }
        catch {
            Write-Warning "部分文件无法删除（可能仍被锁屏界面占用）：$InstallDir。请注销或重启后手动删除。"
        }
    }
    Write-Host "  $InstallDir"
}
else {
    Write-Host '  目录不存在'
}

if ($PurgeData) {
    Write-Step '删除配对数据与日志'

    if (Test-Path -LiteralPath $dataDir) {
        Remove-Item -LiteralPath $dataDir -Recurse -Force -ErrorAction SilentlyContinue
        Write-Host "  $dataDir"
    }
}
else {
    Write-Host ''
    Write-Host "配对数据保留在 $dataDir（加 -PurgeData 可一并删除）。" -ForegroundColor Yellow
}

Write-Step '卸载完成'
Write-Host '建议注销或重启，以确保锁屏界面不再显示 Utochpad 磁贴。' -ForegroundColor Green
