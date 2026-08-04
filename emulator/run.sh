#!/usr/bin/env bash
# Launch the simulated MFD emulator (POSIX / Git Bash).
#   ./run.sh --no-video
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
py="$here/.venv/Scripts/python.exe"
[ -x "$py" ] || py="$here/.venv/bin/python"
if [ ! -x "$py" ]; then
    echo "No virtualenv found. Create it: python -m venv .venv && .venv/Scripts/python -m pip install -r requirements.txt" >&2
    exit 1
fi
cd "$here"
exec "$py" -m mfd_emulator "$@"
