# Install the RTSP video dependencies (MediaMTX + FFmpeg) for the MFD emulator.
#
# The emulator does not download or run these itself — you run this script once. FFmpeg comes
# via winget; MediaMTX is fetched from its official GitHub release into emulator\bin\.
#
#   .\scripts\install_media_tools.ps1
#
# After this, `.\run.ps1` will find both on PATH / in emulator\bin and serve real H.264 video.

$ErrorActionPreference = "Stop"
$here    = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)  # emulator\
$binDir  = Join-Path $here "bin"
New-Item -ItemType Directory -Force -Path $binDir | Out-Null

# --- FFmpeg (via winget) ---
if (Get-Command ffmpeg -ErrorAction SilentlyContinue) {
    Write-Host "[ffmpeg]   already on PATH."
} elseif (Get-Command winget -ErrorAction SilentlyContinue) {
    Write-Host "[ffmpeg]   installing via winget (Gyan.FFmpeg)..."
    winget install --id Gyan.FFmpeg -e --accept-source-agreements --accept-package-agreements
    Write-Host "[ffmpeg]   installed. You may need to open a new shell for PATH to update."
} else {
    Write-Warning "[ffmpeg]   winget not found. Install FFmpeg manually from https://ffmpeg.org/download.html and put ffmpeg.exe on PATH or in $binDir."
}

# --- MediaMTX (from GitHub releases) ---
$mtxExe = Join-Path $binDir "mediamtx.exe"
if (Test-Path $mtxExe) {
    Write-Host "[mediamtx] already present at $mtxExe."
} else {
    Write-Host "[mediamtx] fetching latest windows_amd64 release..."
    $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/bluenviron/mediamtx/releases/latest" -Headers @{ "User-Agent" = "mfd-emulator" }
    $asset = $rel.assets | Where-Object { $_.name -match "windows_amd64\.zip$" } | Select-Object -First 1
    if (-not $asset) { Write-Error "Could not find a windows_amd64 MediaMTX asset."; exit 1 }
    $zip = Join-Path $env:TEMP $asset.name
    Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $zip -Headers @{ "User-Agent" = "mfd-emulator" }
    Expand-Archive -Path $zip -DestinationPath $binDir -Force
    Remove-Item $zip -Force
    if (Test-Path $mtxExe) {
        Write-Host "[mediamtx] installed to $mtxExe ($($rel.tag_name))."
    } else {
        Write-Warning "[mediamtx] extracted but mediamtx.exe not found in $binDir — check the archive layout."
    }
}

Write-Host "`nDone. Start the emulator with video:  .\run.ps1"
