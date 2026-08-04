# One-time setup: create the virtualenv and install dependencies (Windows / PowerShell).

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $here
try {
    if (-not (Test-Path ".venv")) {
        Write-Host "Creating virtualenv..."
        python -m venv .venv
    }
    Write-Host "Installing dependencies..."
    & ".venv\Scripts\python.exe" -m pip install --upgrade pip
    & ".venv\Scripts\python.exe" -m pip install -r requirements.txt
    Write-Host "Done. Run the emulator with .\run.ps1 (and .\scripts\install_media_tools.ps1 for video)."
} finally {
    Pop-Location
}
