"""Make the ``mfd_emulator`` package importable when running pytest from ``emulator/``."""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
