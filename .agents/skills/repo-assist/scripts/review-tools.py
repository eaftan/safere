#!/usr/bin/env python3
# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see LICENSE file).

"""Run the loaded skill's reusable helpers without selecting another installed copy."""

from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from repo_assist.maintenance import main

if __name__ == "__main__":
  raise SystemExit(main())
