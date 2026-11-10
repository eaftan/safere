#!/usr/bin/env python3
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the BSD 3-Clause License.
# See LICENSE file in the project root for details.

"""Apply or restore the prototype in a detached checkout of the pinned library baseline."""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest() if path.exists() else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkout", type=Path)
    parser.add_argument("--restore", action="store_true")
    parser.add_argument("--control", choices=("dfa", "localSticky"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    manifest = json.loads((root / "prototype/manifest.json").read_text())
    checkout = args.checkout.resolve()
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=checkout, text=True).strip()
    attached = subprocess.run(["git", "symbolic-ref", "-q", "HEAD"], cwd=checkout, capture_output=True)
    if head != manifest["baseCommit"] or attached.returncode == 0:
        parser.error("Use a detached investigation checkout at the pinned baseline")
    if args.control:
        control = manifest["controls"][args.control]
        matcher_path = "safere/src/main/java/org/safere/Matcher.java"
        for path, hashes in manifest["trackedFiles"].items():
            expected = control["matcherSha256"] if args.restore and path == matcher_path else hashes["baseline"]
            if digest(checkout / path) != expected:
                parser.error("Unexpected minimal-control source: " + path)
        if any((checkout / path).exists() for path in manifest["addedFiles"]):
            parser.error("Restore the full prototype before applying a minimal control")
        patch = root / control["patch"]
        if digest(patch) != control["patchSha256"]:
            parser.error("Minimal-control patch differs from its manifest")
        command = ["git", "apply"] + (["--reverse"] if args.restore else [])
        subprocess.run(command + ["--check", str(patch)], cwd=checkout, check=True)
        subprocess.run(command + [str(patch)], cwd=checkout, check=True)
        expected = manifest["trackedFiles"][matcher_path]["baseline"] if args.restore else control["matcherSha256"]
        if digest(checkout / matcher_path) != expected:
            raise RuntimeError("Minimal-control source hash mismatch")
        print("Restored main" if args.restore else "Applied minimal " + args.control + " control")
        return
    old_state = "patched" if args.restore else "baseline"
    new_state = "baseline" if args.restore else "patched"
    for path, hashes in manifest["trackedFiles"].items():
        if digest(checkout / path) != hashes[old_state]:
            parser.error("Source differs from expected " + old_state + ": " + path)
    for target, source in manifest["addedFiles"].items():
        expected = manifest["prototypeSha256"][source] if args.restore else None
        if digest(checkout / target) != expected:
            parser.error("Refusing to overwrite unexpected source: " + target)
        if digest(root / source) != manifest["prototypeSha256"][source]:
            parser.error("Prototype source differs from its manifest: " + source)
    patch = root.parent / "policies/adaptive.patch"
    if digest(patch) != manifest["patchSha256"]:
        parser.error("Prototype patch differs from its manifest")
    command = ["git", "apply"] + (["--reverse"] if args.restore else [])
    subprocess.run(command + ["--check", str(patch)], cwd=checkout, check=True)
    subprocess.run(command + [str(patch)], cwd=checkout, check=True)
    for target, source in manifest["addedFiles"].items():
        if args.restore:
            (checkout / target).unlink()
        else:
            shutil.copyfile(root / source, checkout / target)
    for path, hashes in manifest["trackedFiles"].items():
        if digest(checkout / path) != hashes[new_state]:
            raise RuntimeError("Post-apply source mismatch: " + path)
    print("Restored unmodified main" if args.restore else "Applied investigation-only prototype")


if __name__ == "__main__":
    main()
