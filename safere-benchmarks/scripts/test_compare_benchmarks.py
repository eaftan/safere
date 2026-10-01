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

    def test_keeps_default_and_vector_utf8_results_separate(self):
        results = self.parse(
            "Benchmark (crossEngineTrial) Mode Cnt Score Error Units\n"
            "CrossEngineBenchmark.run RegexBenchmark.emailFind@safere-utf8 "
            "avgt 5 12.3 ± 0.4 ns/op\n"
            "CrossEngineBenchmark.run RegexBenchmark.emailFind@safere-utf8-vector "
            "avgt 5 8.3 ± 0.2 ns/op\n"
        )
        self.assertEqual([r.engine for r in results], ["safere_utf8", "safere_utf8_vector"])
        tables = COMPARE.generate_tables(results, ["safere_utf8", "safere_utf8_vector"])
        self.assertIn("safere_utf8 (ns/op)", tables)
        self.assertIn("safere_utf8_vector (ns/op)", tables)
        normalized = [json.loads(line) for line in COMPARE.generate_jsonl(results).splitlines()]
        self.assertEqual([row["engine"] for row in normalized],
                         ["safere_utf8", "safere_utf8_vector"])
        with tempfile.NamedTemporaryFile("w", encoding="utf-8") as plan_file:
            json.dump({"trials": [{"workloadId": "RegexBenchmark.emailFind",
                       "executionVariant": "safere-utf8-vector",
                       "requestedScanProvider": "vector"}], "exclusions": []}, plan_file)
            plan_file.flush()
            statuses = COMPARE.load_declared_plan(plan_file.name)
        self.assertIn(("RegexBenchmark.emailFind", "safere_utf8_vector"), statuses)

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

    def test_cross_language_engine_aliases_are_normalized(self):
        with tempfile.NamedTemporaryFile("w", encoding="utf-8") as output:
            output.write(
                '{"engine":"rust_regex","benchmark":"RegexBenchmark.literalMatch",'
                '"score":3.1,"error":0.2,"unit":"ns/op"}\n'
            )
            output.flush()
            results = COMPARE.parse_jsonl(output.name)

        self.assertEqual(results[0].engine, "rust")

    def test_serializes_normalized_results_as_json_lines(self):
        output = COMPARE.generate_jsonl([
            COMPARE.Result(
                "safere",
                "RegexBenchmark.literalMatch",
                23.7,
                1.2,
                "ns/op",
            ),
            COMPARE.Result(
                "rust",
                "RegexBenchmark.literalMatch",
                39.2,
                0.8,
                "ns/op",
            ),
        ])

        self.assertEqual(
            [json.loads(line) for line in output.splitlines()],
            [
                {
                    "engine": "safere",
                    "benchmark": "RegexBenchmark.literalMatch",
                    "score": 23.7,
                    "error": 1.2,
                    "unit": "ns/op",
                },
                {
                    "engine": "rust",
                    "benchmark": "RegexBenchmark.literalMatch",
                    "score": 39.2,
                    "error": 0.8,
                    "unit": "ns/op",
                },
            ],
        )

    def test_generate_tables_with_speedup(self):
        results = [
            COMPARE.Result("baseline", "RegexBenchmark.literalMatch", 100.0, 5.0, "ns/op"),
            COMPARE.Result("current", "RegexBenchmark.literalMatch", 25.0, 1.0, "ns/op"),
        ]
        table = COMPARE.generate_tables(
            results,
            engines=["baseline", "current"],
            show_speedup=True,
        )
        self.assertIn("Speedup", table)
        self.assertIn("4.00x", table)

    def test_speedup_normalizes_compatible_units(self):
        baseline = COMPARE.Result("baseline", "Benchmark.run", 1000.0, 10.0, "ns/op")
        current = COMPARE.Result("current", "Benchmark.run", 1.0, 0.01, "us/op")

        self.assertEqual(COMPARE._format_speedup(baseline, current), "1.00x")

    def test_speedup_rejects_incompatible_units(self):
        latency = COMPARE.Result("baseline", "Benchmark.run", 100.0, 1.0, "ns/op")
        throughput = COMPARE.Result("current", "Benchmark.run", 100.0, 1.0, "ops/s")

        self.assertEqual(COMPARE._format_speedup(latency, throughput), "—")

    def test_speedup_preserves_very_small_nonzero_ratios(self):
        baseline = COMPARE.Result("baseline", "Benchmark.run", 100.0, 1.0, "ns/op")
        current = COMPARE.Result("current", "Benchmark.run", 100000.0, 1.0, "ns/op")

        self.assertEqual(COMPARE._format_speedup(baseline, current), "0.001x")

    def test_generate_tables_single_table(self):
        results = [
            COMPARE.Result(
                "baseline",
                "RealWorldRegexBenchmark.runBenchmark.jsonBlock.match.1000",
                100.0,
                5.0,
                "ns/op",
            ),
            COMPARE.Result(
                "current",
                "RealWorldRegexBenchmark.runBenchmark.jsonBlock.match.1000",
                25.0,
                1.0,
                "ns/op",
            ),
        ]
        table = COMPARE.generate_tables(
            results,
            engines=["baseline", "current"],
            single_table=True,
        )
        self.assertNotIn("### RealWorldRegexBenchmark", table)
        self.assertIn("jsonBlock.match.1000", table)
        self.assertNotIn("RealWorldRegexBenchmark.runBenchmark.", table)

    def test_generate_single_table_preserves_mixed_units(self):
        results = [
            COMPARE.Result("baseline", "Benchmark.latency", 100.0, 5.0, "ns/op"),
            COMPARE.Result("current", "Benchmark.latency", 50.0, 2.0, "ns/op"),
            COMPARE.Result("baseline", "Benchmark.throughput", 10.0, 0.5, "ops/s"),
            COMPARE.Result("current", "Benchmark.throughput", 20.0, 1.0, "ops/s"),
        ]

        table = COMPARE.generate_tables(
            results,
            engines=["baseline", "current"],
            show_speedup=True,
            single_table=True,
        )

        self.assertIn("100 ± 5.0 ns/op", table)
        self.assertIn("10 ± 0.50 ops/s", table)
        self.assertIn("2.00x", table)

    def test_single_table_preserves_class_identity(self):
        results = [
            COMPARE.Result("baseline", "FooBenchmark.match", 10.0, 1.0, "ns/op"),
            COMPARE.Result("baseline", "BarBenchmark.match", 20.0, 2.0, "ns/op"),
        ]

        table = COMPARE.generate_tables(results, single_table=True)

        self.assertIn("FooBenchmark.match", table)
        self.assertIn("BarBenchmark.match", table)

    def test_simplify_benchmark_name_dynamic(self):
        self.assertEqual(
            COMPARE._simplify_benchmark_name(
                "org.safere.benchmark.RealWorldRegexBenchmark.runBenchmark.caseInsensitiveKeywordFind.match.1000"
            ),
            "caseInsensitiveKeywordFind.match.1000",
        )
        self.assertEqual(
            COMPARE._simplify_benchmark_name("ApplicationBenchmark.uuidValidation"),
            "ApplicationBenchmark.uuidValidation",
        )
        self.assertEqual(
            COMPARE._simplify_benchmark_name("SingleCharClassBenchmark.findDigitAbsent.1048576"),
            "SingleCharClassBenchmark.findDigitAbsent.1048576",
        )
        self.assertEqual(
            COMPARE._simplify_benchmark_name("CustomClassBenchmark.run.specialTrial"),
            "specialTrial",
        )


if __name__ == "__main__":
    unittest.main()
