#requires -Version 5.1
<#
.SYNOPSIS
    Builds viiper.exe from a pinned upstream VIIPER revision plus the
    utochpad device overlay.

.DESCRIPTION
    The upstream source is downloaded as a GitHub archive into
    .build\viiper\<sha> (never committed). The contents of Windows\viiper\
    are then copied over that checkout and the binary is built from it.

    Updating VIIPER means changing the SHA in Windows\viiper\VIIPER_VERSION.

.PARAMETER OutDir
    Output directory. Default: artifacts\viiper

.PARAMETER Version
    Version string embedded in the binary.
    Default: utochpad-<short sha>

.PARAMETER ForceFetch
    Re-download the upstream source even when the cache already exists.

.PARAMETER Test
    Run the ptp and radial device tests after building.

.EXAMPLE
    .\Windows\installer\build-viiper.ps1

.EXAMPLE
    .\Windows\installer\build-viiper.ps1 -Test -ForceFetch
#>
[CmdletBinding()]
param(
    [string]$OutDir,
    [string]$Version,
    [switch]$ForceFetch,
    [switch]$Test
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$scriptRoot = $PSScriptRoot
$windowsRoot = Split-Path -Parent $scriptRoot
$repoRoot = Split-Path -Parent $windowsRoot
if (-not $repoRoot) { $repoRoot = (Get-Location).Path }
$overlayDir = Join-Path $windowsRoot 'viiper'
$versionFile = Join-Path $overlayDir 'VIIPER_VERSION'

if (-not (Test-Path -LiteralPath $versionFile)) {
    throw "Missing $versionFile"
}

$sha = (Get-Content -LiteralPath $versionFile |
        Where-Object { $_ -match '\S' -and $_ -notmatch '^\s*#' } |
        Select-Object -First 1).Trim()
if ($sha -notmatch '^[0-9a-fA-F]{40}$') {
    throw "VIIPER_VERSION must contain a full 40-character commit SHA, got '$sha'"
}
$sha = $sha.ToLowerInvariant()
$shortSha = $sha.Substring(0, 7)
if (-not $Version) { $Version = "utochpad-$shortSha" }

if (-not $OutDir) { $OutDir = Join-Path $repoRoot 'artifacts\viiper' }
$OutDir = [System.IO.Path]::GetFullPath($OutDir)
$outExe = Join-Path $OutDir 'viiper.exe'

$cacheRoot = Join-Path $repoRoot '.build\viiper'
$srcDir = Join-Path $cacheRoot $sha
$stampFile = Join-Path $srcDir '.utochpad-source'

if ($ForceFetch -or -not (Test-Path -LiteralPath $stampFile)) {
    if (Test-Path -LiteralPath $srcDir) {
        Remove-Item -LiteralPath $srcDir -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $cacheRoot | Out-Null

    Write-Host "Fetching Alia5/VIIPER@$shortSha..."
    $url = "https://codeload.github.com/Alia5/VIIPER/zip/$sha"
    $tmpRoot = Join-Path $cacheRoot ("tmp-" + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $tmpRoot | Out-Null
    try {
        $archive = Join-Path $tmpRoot 'viiper.zip'
        Invoke-WebRequest -Uri $url -OutFile $archive -UseBasicParsing
        Expand-Archive -LiteralPath $archive -DestinationPath $tmpRoot -Force
        $extracted = Get-ChildItem -LiteralPath $tmpRoot -Directory | Select-Object -First 1
        if (-not $extracted) { throw "Unexpected archive layout from $url" }
        Move-Item -LiteralPath $extracted.FullName -Destination $srcDir
        Set-Content -LiteralPath $stampFile -Value $sha -Encoding ASCII
    }
    finally {
        if (Test-Path -LiteralPath $tmpRoot) {
            Remove-Item -LiteralPath $tmpRoot -Recurse -Force
        }
    }
}
else {
    Write-Host "Using cached upstream source: .build\viiper\$sha"
}

# These paths are owned by the overlay. Delete them first so files removed
# from Windows\viiper\ do not linger in a cached checkout.
foreach ($dir in @('device\ptp', 'device\radial')) {
    $dest = Join-Path $srcDir $dir
    if (Test-Path -LiteralPath $dest) {
        Remove-Item -LiteralPath $dest -Recurse -Force
    }
}

Write-Host 'Applying overlay...'
$robocopyArgs = @($overlayDir, $srcDir, '/E', '/NFL', '/NDL', '/NJH', '/NJS', '/NP', '/XF', 'VIIPER_VERSION')
& robocopy @robocopyArgs | Out-Null
if ($LASTEXITCODE -ge 8) {
    throw "robocopy failed with exit code $LASTEXITCODE"
}

foreach ($required in @('device\ptp\device.go', 'device\radial\device.go', 'internal\registry\zz_utochpad.go')) {
    $path = Join-Path $srcDir $required
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Overlay did not apply: $required is missing"
    }
}

$buildTime = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
$ldflags = @(
    "-X github.com/Alia5/VIIPER/meta.Version=$Version",
    "-X github.com/Alia5/VIIPER/meta.Commit=$shortSha",
    "-X github.com/Alia5/VIIPER/meta.Date=$buildTime",
    "-X github.com/Alia5/VIIPER/internal/codegen/common.Version=$Version"
) -join ' '

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$env:CGO_ENABLED = '0'

Push-Location $srcDir
try {
    # Upstream embeds internal/install/bundle/licenses.txt with //go:embed,
    # and `just build` generates it first via go-licenses. Do the same here;
    # the result is cached in the checkout and only generated once per SHA.
    $licensesFile = Join-Path $srcDir 'internal\install\bundle\licenses.txt'
    if (-not (Test-Path -LiteralPath $licensesFile)) {
        Write-Host 'Generating third-party license bundle (first build only)...'
        $templatePath = Join-Path $srcDir 'scripts\licenses.tpl'
        $template = (Get-Content -LiteralPath $templatePath -Raw).Replace('VERSION_PLACEHOLDER', $Version)
        $renderedTemplate = Join-Path $env:TEMP ('viiper-licenses-' + [Guid]::NewGuid().ToString('N') + '.tpl')
        [System.IO.File]::WriteAllText($renderedTemplate, $template, [System.Text.UTF8Encoding]::new($false))
        try {
            $env:GOOS = ''
            $env:GOARCH = ''
            $report = & go run github.com/google/go-licenses/v2@v2.0.1 report ./cmd/viiper --ignore github.com/Alia5/VIIPER,github.com/alecthomas/kong-yaml --template $renderedTemplate
            if ($LASTEXITCODE -ne 0) {
                throw "go-licenses failed with exit code $LASTEXITCODE"
            }
            [System.IO.File]::WriteAllText($licensesFile, (($report -join "`n") + "`n"), [System.Text.UTF8Encoding]::new($false))
        }
        finally {
            if (Test-Path -LiteralPath $renderedTemplate) {
                Remove-Item -LiteralPath $renderedTemplate -Force
            }
        }
    }

    Write-Host "Building $outExe ..."
    & go build -trimpath -ldflags $ldflags -o $outExe ./cmd/viiper
    if ($LASTEXITCODE -ne 0) {
        throw "go build failed with exit code $LASTEXITCODE"
    }

    if ($Test) {
        Write-Host 'Running ptp/radial device tests...'
        & go test -count=1 ./device/ptp ./device/radial
        if ($LASTEXITCODE -ne 0) {
            throw "device tests failed with exit code $LASTEXITCODE"
        }
    }
}
finally {
    Pop-Location
}

$item = Get-Item -LiteralPath $outExe
Write-Host ("Built {0} ({1:N1} MB, VIIPER {2}, version {3})" -f $item.FullName, ($item.Length / 1MB), $shortSha, $Version)
