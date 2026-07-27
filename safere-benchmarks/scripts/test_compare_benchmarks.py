#!/usr/bin/env python3

# Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.

"""Tests for cross-engine JMH result normalization."""

import importlib.util
import json
import pathlib
import tempfile
import unittest


SCRIPT = pathlib.Path(__file__).with_name("compare-benchmarks.py")
SPEC = importlib.util.spec_from_file_location("compare_benchmarks", SCRIPT)
COMPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COMPARE)


class CrossEngineResultParsingTest(unittest.TestCase):

    def parse(self, text):
        with tempfile.NamedTemporaryFile("w", encoding="utf-8") as output:
            output.write(text)
            output.flush()
            return COMPARE.parse_jmh(output.name)

    def test_normalizes_first_class_workload_and_variant_ids(self):
        results = self.parse(
            "Benchmark (crossEngineTrial) Mode Cnt Score Error Units\n"
            "org.safere.benchmark.CrossEngineBenchmark.run "
            "RegexBenchmark.emailFind@safere-utf8 avgt 5 "
            "12.3 ± 0.4 ns/op\n"
        )

        self.assertEqual(
            results,
            [
                COMPARE.Result(
                    engine="safere_utf8",
                    benchmark="RegexBenchmark.emailFind",
                    score=12.3,
                    error=0.4,
                    unit="ns/op",
                )
            ],
        )

    def test_normalizes_parameterized_scaling_workload_id(self):
        results = self.parse(
            "Benchmark (crossEngineScalingTrial) Mode Cnt Score Error Units\n"
            "org.safere.benchmark.CrossEngineScalingBenchmark.run "
            "SearchScalingBenchmark.searchEasyFail.1024@jdk-string "
            "avgt 5 1.2 ± 0.1 us/op\n"
        )

        self.assertEqual(
            results[0].benchmark,
            "SearchScalingBenchmark.searchEasyFail.1024",
        )
        self.assertEqual(results[0].engine, "jdk")

    def test_normalizes_no_fork_and_cold_start_trial_ids(self):
        results = self.parse(
            "Benchmark (crossEngineNoForkTrial) Mode Cnt Score Error Units\n"
            "org.safere.benchmark.CrossEngineNoForkBenchmark.run "
            "PathologicalBenchmark.pathological.25@re2j-string "
            "avgt 5 2.4 ± 0.2 us/op\n"
            "Benchmark (crossEngineColdStartTrial) Mode Score Error Units\n"
            "org.safere.benchmark.CrossEngineColdStartBenchmark.run "
            "UnicodeFirstCompileBenchmark.firstCompile.letter.0@jdk-string "
            "ss 7.0 ms/op\n"
        )

        self.assertEqual(
            [(result.benchmark, result.engine) for result in results],
            [
                ("PathologicalBenchmark.pathological.25", "re2j"),
                ("UnicodeFirstCompileBenchmark.firstCompile.letter.0", "jdk"),
            ],
        )

    def test_normalizes_specialized_trial_and_preserves_representation_label(self):
        results = self.parse(
            "Benchmark (specializedTrial) Mode Cnt Score Error Units\n"
            "org.safere.benchmark.SpecializedBenchmark.run "
            "Utf8MatchingBenchmark.captureFreeDecode.asciiEarly@safere-utf8 "
            "avgt 5 12.3 ± 0.4 ns/op\n"
        )

        self.assertEqual(results[0].engine, "safere_utf8")
        self.assertEqual(
            results[0].benchmark,
            "Utf8MatchingBenchmark.captureFreeDecode.asciiEarly",
        )

    def test_preserves_timed_string_conversion_variant_label(self):
        results = self.parse(
            "Benchmark (crossEngineTrial) Mode Cnt Score Error Units\n"
            "org.safere.benchmark.CrossEngineBenchmark.run "
            "RegexBenchmark.emailFind@re2-ffm-string-conversion "
            "avgt 5 12.3 ± 0.4 ns/op\n"
        )

        self.assertEqual(results[0].engine, "re2_ffm")

    def test_declared_plan_distinguishes_missing_from_excluded(self):
        plan = {
            "trials": [
                {
                    "workloadId": "RegexBenchmark.literalMatch",
                    "executionVariant": "safere-string",
                }
            ],
            "exclusions": [
                {
                    "workloadId": "RegexBenchmark.literalMatch",
                    "executionVariant": "safere-utf8",
                }
            ],
        }
        with tempfile.NamedTemporaryFile("w", encoding="utf-8") as plan_file:
            json.dump(plan, plan_file)
            plan_file.flush()
            statuses = COMPARE.load_declared_plan(plan_file.name)

        markdown = COMPARE.generate_tables(
            [],
            ["safere", "safere_utf8"],
            statuses,
        )

        self.assertIn("missing", markdown)
        self.assertIn("excluded", markdown)

    def test_cross_language_json_row_keeps_stable_identity(self):
        with tempfile.NamedTemporaryFile("w", encoding="utf-8") as output:
            output.write(
                '{"engine":"re2_cpp","benchmark":"RegexBenchmark.literalMatch",'
                '"score":4.2,"error":0.1,"unit":"ns/op"}\n'
            )
            output.flush()
            results = COMPARE.parse_jsonl(output.name)

        self.assertEqual(
            results[0],
            COMPARE.Result(
                "re2_cpp",
                "RegexBenchmark.literalMatch",
                4.2,
                0.1,
                "ns/op",
            ),
        )

if __name__ == "__main__":
    unittest.main()
