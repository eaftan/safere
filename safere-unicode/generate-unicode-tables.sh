#!/usr/bin/env bash
# This file is part of a Java port of RE2 (https://github.com/google/re2).
# Original RE2 code is Copyright (c) 2009 The RE2 Authors.
# Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see LICENSE file).

set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

mvn -pl safere-unicode compile exec:java \
  -Dexec.mainClass=org.safere.tools.unicode.UnicodeTableGenerator
mvn -pl safere spotless:apply
