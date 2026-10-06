# Copyright (c) 2026 Eddie Aftandilian. Licensed under the MIT License.
"""Enrollment contract tests; run with python3 -m unittest discover -s safere-fuzz/scripts."""
import json
from pathlib import Path
import tempfile
import unittest
from targets import read_targets, ROOT


class TargetManifestTest(unittest.TestCase):
    def test_repository_inventory(self):
        targets = read_targets()
        self.assertTrue(any(t['oss_fuzz'] for t in targets))
        self.assertFalse(any(t['oss_fuzz'] and t['kind'] == 'broad' for t in targets))

    def fixture(self, entries):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        (root / 'targets.json').write_text(json.dumps(entries))
        for entry in entries:
            source = root / 'src/test/java' / (entry['class'].replace('.', '/') + '.java')
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text('public static void fuzzerTestOneInput(\n@FuzzTest(maxDuration = "30s")\nvoid fuzz(')
        return root

    def entry(self, **changes):
        return dict({'class': 'org.safere.fuzz.ExampleFuzzer', 'kind': 'strict',
                     'oss_fuzz': True, 'seeds': None, 'methods': ['fuzz']}, **changes)

    def test_broad_enrollment_rejected(self):
        root = self.fixture([self.entry(kind='broad')])
        with self.assertRaisesRegex(ValueError, 'cannot enroll'):
            read_targets(root)

    def test_duplicate_rejected(self):
        root = self.fixture([self.entry(), self.entry()])
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            read_targets(root)

    def test_missing_source_rejected(self):
        root = self.fixture([self.entry()])
        next(root.rglob('*.java')).unlink()
        with self.assertRaisesRegex(ValueError, 'missing target source'):
            read_targets(root)

    def test_uninventoried_target_rejected(self):
        root = self.fixture([self.entry()])
        next(root.rglob('*.java')).with_name('UnlistedFuzzer.java').write_text('')
        with self.assertRaisesRegex(ValueError, 'uninventoried'):
            read_targets(root)

    def test_missing_entry_point_rejected(self):
        root = self.fixture([self.entry()])
        next(root.rglob('*.java')).write_text('')
        with self.assertRaisesRegex(ValueError, 'missing static'):
            read_targets(root)

    def test_missing_method_rejected(self):
        root = self.fixture([self.entry(methods=[])])
        with self.assertRaisesRegex(ValueError, 'method inventory'):
            read_targets(root)

    def test_class_selection_includes_all_methods(self):
        import subprocess
        selected = subprocess.check_output(
            ['python3', str(ROOT / 'scripts/targets.py'), '--select', 'SplitFuzzer'], text=True)
        self.assertEqual(set(selected.splitlines()),
                         {'SplitFuzzer#split', 'SplitFuzzer#repeatedClassSplits', 'SplitFuzzer#unassignedControlSplits'})
        result = subprocess.run(
            ['python3', str(ROOT / 'scripts/targets.py'), '--select', 'SplitFuzzer#missing'],
            capture_output=True)
        self.assertNotEqual(result.returncode, 0)

    def test_invalid_seed_path_rejected(self):
        root = self.fixture([self.entry(seeds='../outside')])
        with self.assertRaisesRegex(ValueError, 'invalid seed'):
            read_targets(root)

class ExportTest(unittest.TestCase):
    def test_only_enrolled_launchers_are_emitted(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location('exporter', ROOT / 'scripts/export-oss-fuzz.py')
        exporter = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(exporter)
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            names = exporter.export(out)
            expected = {t['class'].rsplit('.', 1)[-1] for t in read_targets() if t['oss_fuzz']}
            self.assertEqual(set(names), expected)
            self.assertEqual({p.name for p in out.glob('*Fuzzer')}, expected)
            for name in names:
                target = next(t for t in read_targets() if t['class'].rsplit('.', 1)[-1] == name)
                self.assertIn('--target_class=' + target['class'], (out / name).read_text())
                # OSS-Fuzz identifies executable wrappers by this libFuzzer marker.
                import os
                self.assertTrue(os.access(out / name, os.X_OK))
                self.assertIn(b'LLVMFuzzerTestOneInput', (out / name).read_bytes())
                import subprocess
                subprocess.run(['bash', '-n', str(out / name)], check=True)
                import zipfile
                if not (out / (name + '_seed_corpus.zip')).exists():
                    continue
                with zipfile.ZipFile(out / (name + '_seed_corpus.zip')) as archive:
                    self.assertEqual(len(archive.namelist()), len(set(archive.namelist())))
                    self.assertTrue(all('/' in item for item in archive.namelist()))
            driver = out / 'jazzer_driver'
            driver.write_text('#!/bin/bash\nprintf "%s\\n" "$@"\n')
            driver.chmod(0o755)
            (out / 'safere.jar').touch()
            result = subprocess.check_output([str(out / names[0]), '-runs=1'], text=True)
            self.assertIn('--cp=' + str(out / 'safere.jar') + ':', result)
            self.assertIn('-runs=1', result)
            (out / 'CompileFuzzer').write_text('stale broad launcher')
            with self.assertRaisesRegex(ValueError, 'unenrolled'):
                exporter.export(out)


if __name__ == '__main__':
    unittest.main()
