#!/usr/bin/env python3
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the MIT License.
"""Emit OSS-Fuzz launchers and seed archives only for explicitly enrolled targets."""
import argparse
from pathlib import Path
import shlex
import zipfile
from targets import ROOT, read_targets


def export(output, root=ROOT):
    entries = [entry for entry in read_targets(root) if entry['oss_fuzz']]
    output.mkdir(parents=True, exist_ok=True)
    enrolled = {entry['class'].rsplit('.', 1)[-1] for entry in entries}
    stale = {path.name for path in output.glob('*Fuzzer')} - enrolled
    if stale:
        raise ValueError(f'stale or unenrolled launchers in output: {sorted(stale)}')
    launchers = []
    for entry in entries:
        name = entry['class'].rsplit('.', 1)[-1]
        launcher = output / name
        launcher.write_text('''#!/bin/bash
# LLVMFuzzerTestOneInput: marker required for OSS-Fuzz shell target discovery.
set -euo pipefail
this_dir="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="$this_dir/openjdk"
printf -v classpath '%s:' "$this_dir"/*.jar
export LD_LIBRARY_PATH="$this_dir/openjdk/lib/server:$this_dir${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
exec "$this_dir/jazzer_driver" \\
  --agent_path="$this_dir/jazzer_agent_deploy.jar" \\
  --cp="$classpath" \\
  --disabled_hooks=com.code_intelligence.jazzer.sanitizers.RegexInjection \\
  --jvm_args="-Xmx1900m:-Xss1024k:-Djdk.attach.allowAttachSelf=true:--enable-native-access=ALL-UNNAMED" \\
  --target_class=''' + shlex.quote(entry['class']) + ' "$@"\n')
        launcher.chmod(0o755)
        launchers.append(name)
        if entry['seeds']:
            seed_root = root / entry['seeds']
            with zipfile.ZipFile(output / (name + '_seed_corpus.zip'), 'w') as archive:
                for seed in sorted(seed_root.rglob('*')):
                    if seed.is_file():
                        archive.write(seed, seed.relative_to(seed_root))
    (output / 'safere-targets.txt').write_text(''.join(name + '\n' for name in launchers))
    return launchers


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    export(parser.parse_args().output)
