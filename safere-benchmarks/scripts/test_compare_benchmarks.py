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

    def test_application_name_check_respects_utf8_operation_coverage(self):
        benchmark_data = {
            "application": [
                {
                    "id": "ApplicationBenchmark.find",
                    "op": "find",
                },
                {
                    "id": "ApplicationBenchmark.replace",
                    "op": "replaceAll",
                },
            ]
        }
        results = [
            COMPARE.Result("safere", "ApplicationBenchmark.find", 1.0, 0.0, "ns/op"),
            COMPARE.Result("safere", "ApplicationBenchmark.replace", 1.0, 0.0, "ns/op"),
            COMPARE.Result("safere_utf8", "ApplicationBenchmark.find", 1.0, 0.0, "ns/op"),
        ]

        with tempfile.NamedTemporaryFile("w", encoding="utf-8") as data_file:
            json.dump(benchmark_data, data_file)
            data_file.flush()
            COMPARE.verify_application_names(
                results,
                data_file.name,
                ["safere", "safere_utf8"],
            )


if __name__ == "__main__":
    unittest.main()
