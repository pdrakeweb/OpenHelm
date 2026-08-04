# Launch the simulated MFD emulator (Windows / PowerShell).
#
#   .\run.ps1                    # default config, video + fault console
#   .\run.ps1 --no-video         # skip RTSP (no MediaMTX/FFmpeg needed)
#   .\run.ps1 --interface 192.168.4.108
#
# First-time setup:  .\setup.ps1   (creates .venv and installs deps)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$py = Join-Path $here ".venv\Scripts\python.exe"
if (-not (Test-Path $py)) {
    Write-Error "No virtualenv found. Run .\setup.ps1 first."
    exit 1
}
Push-Location $here
try {
    & $py -m mfd_emulator @args
} finally {
    Pop-Location
}
