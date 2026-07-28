// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.
//
// Native C++ regex benchmark harness. Runs the same patterns and inputs as the
// Java JMH benchmarks and outputs JSON lines for cross-language comparison.
// Patterns and inputs are loaded from a shared JSON data file.
//
// Build:
//   cd safere-benchmarks/cpp && mkdir -p build && cd build
//   cmake .. && cmake --build . --parallel
//
// Run:
//   ./build/re2_benchmark [--manifest path/to/manifest.json] [filter...]
//   ./build/pcre2_jit_benchmark [--manifest path/to/manifest.json] [filter...]
//
// Each filter is a substring match against benchmark names. If no filters
// are given, all benchmarks are run.

#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <functional>
#include <memory>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

#ifdef SAFERE_HAVE_MALLINFO2
#include <malloc.h>
#endif

#include <nlohmann/json.hpp>
#ifdef SAFERE_PCRE2_JIT
#include "pcre2_re2_compat.h"
#else
#include "re2/re2.h"
#endif

using json = nlohmann::json;

#ifdef SAFERE_PCRE2_JIT
constexpr const char* kEngineId = "pcre2_jit";
#else
constexpr const char* kEngineId = "re2_cpp";
#endif

json benchmark_input_manifest;
json benchmark_execution_plan;
std::filesystem::path benchmark_input_directory;

std::string sha256_hex(std::string_view input) {
  static constexpr std::array<uint32_t, 64> kRoundConstants = {
      0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b,
      0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01,
      0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7,
      0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
      0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152,
      0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
      0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc,
      0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
      0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819,
      0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08,
      0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f,
      0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
      0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};
  std::array<uint32_t, 8> hash = {
      0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
      0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};

  std::vector<uint8_t> message(input.begin(), input.end());
  uint64_t bit_length = static_cast<uint64_t>(message.size()) * 8;
  message.push_back(0x80);
  while (message.size() % 64 != 56) {
    message.push_back(0);
  }
  for (int shift = 56; shift >= 0; shift -= 8) {
    message.push_back(static_cast<uint8_t>(bit_length >> shift));
  }

  auto rotate_right = [](uint32_t value, int count) {
    return (value >> count) | (value << (32 - count));
  };
  for (size_t offset = 0; offset < message.size(); offset += 64) {
    std::array<uint32_t, 64> words{};
    for (size_t index = 0; index < 16; index++) {
      size_t word_offset = offset + index * 4;
      words[index] =
          (static_cast<uint32_t>(message[word_offset]) << 24) |
          (static_cast<uint32_t>(message[word_offset + 1]) << 16) |
          (static_cast<uint32_t>(message[word_offset + 2]) << 8) |
          static_cast<uint32_t>(message[word_offset + 3]);
    }
    for (size_t index = 16; index < words.size(); index++) {
      uint32_t s0 = rotate_right(words[index - 15], 7) ^
                    rotate_right(words[index - 15], 18) ^
                    (words[index - 15] >> 3);
      uint32_t s1 = rotate_right(words[index - 2], 17) ^
                    rotate_right(words[index - 2], 19) ^
                    (words[index - 2] >> 10);
      words[index] =
          words[index - 16] + s0 + words[index - 7] + s1;
    }

    uint32_t a = hash[0];
    uint32_t b = hash[1];
    uint32_t c = hash[2];
    uint32_t d = hash[3];
    uint32_t e = hash[4];
    uint32_t f = hash[5];
    uint32_t g = hash[6];
    uint32_t h = hash[7];
    for (size_t index = 0; index < words.size(); index++) {
      uint32_t sum1 = rotate_right(e, 6) ^ rotate_right(e, 11) ^
                      rotate_right(e, 25);
      uint32_t choose = (e & f) ^ (~e & g);
      uint32_t temporary1 =
          h + sum1 + choose + kRoundConstants[index] + words[index];
      uint32_t sum0 = rotate_right(a, 2) ^ rotate_right(a, 13) ^
                      rotate_right(a, 22);
      uint32_t majority = (a & b) ^ (a & c) ^ (b & c);
      uint32_t temporary2 = sum0 + majority;
      h = g;
      g = f;
      f = e;
      e = d + temporary1;
      d = c;
      c = b;
      b = a;
      a = temporary1 + temporary2;
    }
    hash[0] += a;
    hash[1] += b;
    hash[2] += c;
    hash[3] += d;
    hash[4] += e;
    hash[5] += f;
    hash[6] += g;
    hash[7] += h;
  }

  std::string result;
  result.reserve(64);
  char word[9];
  for (uint32_t value : hash) {
    snprintf(word, sizeof(word), "%08x", value);
    result.append(word);
  }
  return result;
}

bool run_sha256_self_test() {
  struct TestCase {
    std::string_view input;
    std::string_view expected;
  };
  static constexpr std::array<TestCase, 3> kTestCases = {{
      {"", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"},
      {"abc", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"},
      {"The quick brown fox jumps over the lazy dog",
       "d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592"},
  }};
  for (const TestCase& test_case : kTestCases) {
    std::string actual = sha256_hex(test_case.input);
    if (actual != test_case.expected) {
      fprintf(stderr, "SHA-256 self-test failed: expected %s, found %s\n",
              std::string(test_case.expected).c_str(), actual.c_str());
      return false;
    }
  }
  return true;
}

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

struct BenchResult {
  std::string name;
  double ns_per_op;
  double error;  // 99.9% CI half-width (like JMH ±)
  std::string unit;
};

// Measure a function: warmup_iters warmup rounds, then measure_iters rounds
// of measure_time_sec seconds each.
BenchResult measure(const std::string& name,
                    const std::function<void()>& fn,
                    int warmup_iters = 2, double warmup_time_sec = 2.0,
                    int measure_iters = 10, double measure_time_sec = 2.0,
                    const std::string& unit = "ns/op",
                    double unit_divisor = 1.0) {
  // Warmup.
  for (int w = 0; w < warmup_iters; ++w) {
    auto end = std::chrono::high_resolution_clock::now() +
               std::chrono::duration<double>(warmup_time_sec);
    while (std::chrono::high_resolution_clock::now() < end) {
      fn();
    }
  }

  // Measurement.
  std::vector<double> samples;
  for (int i = 0; i < measure_iters; ++i) {
    long ops = 0;
    auto start = std::chrono::high_resolution_clock::now();
    auto deadline = start + std::chrono::duration<double>(measure_time_sec);
    while (std::chrono::high_resolution_clock::now() < deadline) {
      fn();
      ++ops;
    }
    auto elapsed = std::chrono::high_resolution_clock::now() - start;
    double ns = std::chrono::duration<double, std::nano>(elapsed).count();
    samples.push_back((ns / ops) / unit_divisor);
  }

  // Stats.
  double sum = 0;
  for (double s : samples) sum += s;
  double mean = sum / samples.size();
  double error = 0;
  if (samples.size() > 1) {
    double var = 0;
    for (double s : samples) var += (s - mean) * (s - mean);
    double stddev = std::sqrt(var / (samples.size() - 1));
    // t-value for 99.9% CI with 9 df ≈ 4.781
    error = 4.781 * stddev / std::sqrt(samples.size());
  }

  return {name, mean, error, unit};
}

void print_json(const BenchResult& r) {
  printf("{\"engine\":\"%s\",\"benchmark\":\"%s\","
         "\"score\":%.3f,\"error\":%.3f,\"unit\":\"%s\"}\n",
         kEngineId, r.name.c_str(), r.ns_per_op, r.error, r.unit.c_str());
  fflush(stdout);
}

// Print a memory measurement result as JSON.
void print_memory_json(const std::string& name, long bytes,
                       const std::string& unit = "bytes") {
  printf("{\"engine\":\"%s\",\"benchmark\":\"%s\","
         "\"score\":%ld,\"error\":0,\"unit\":\"%s\"}\n",
         kEngineId, name.c_str(), bytes, unit.c_str());
  fflush(stdout);
}

bool matches_filter(const std::string& name,
                    const std::vector<std::string>& filters) {
  if (filters.empty()) return true;
  for (const auto& f : filters) {
    if (name.find(f) != std::string::npos) return true;
  }
  return false;
}

// Prevent compiler from optimizing away a value.
template <typename T>
void do_not_optimize(const T& val) {
  asm volatile("" : : "r,m"(val) : "memory");
}

// ---------------------------------------------------------------------------
// JSON loading
// ---------------------------------------------------------------------------

json load_benchmark_manifest(const std::string& manifest_path_string) {
  std::filesystem::path manifest_path = manifest_path_string;
  benchmark_input_directory = manifest_path.parent_path();
  std::ifstream input(manifest_path);
  if (!input.is_open()) {
    fprintf(stderr, "ERROR: cannot open materialized benchmark manifest: %s\n",
            manifest_path.string().c_str());
    exit(1);
  }
  json manifest = json::parse(input);
  if (manifest.at("version").get<int>() != 1) {
    fprintf(stderr, "ERROR: unsupported benchmark input manifest version\n");
    exit(1);
  }
  benchmark_input_manifest = manifest.at("inputs");
  benchmark_execution_plan = manifest.at("executionPlan");
  if (benchmark_execution_plan.at("version").get<int>() != 1) {
    fprintf(stderr, "ERROR: unsupported benchmark execution-plan version\n");
    exit(1);
  }
  return benchmark_execution_plan;
}

std::string load_benchmark_input(const std::string& key) {
  auto entry = benchmark_input_manifest.find(key);
  if (entry == benchmark_input_manifest.end()) {
    fprintf(stderr, "ERROR: unknown materialized benchmark input: %s\n",
            key.c_str());
    exit(1);
  }
  std::filesystem::path path =
      benchmark_input_directory / entry->at("file").get<std::string>();
  std::ifstream input(path, std::ios::binary);
  if (!input.is_open()) {
    fprintf(stderr, "ERROR: cannot open materialized benchmark input: %s\n",
            path.string().c_str());
    exit(1);
  }
  std::string text((std::istreambuf_iterator<char>(input)),
                   std::istreambuf_iterator<char>());
  size_t expected_size = entry->at("utf8Bytes").get<size_t>();
  if (text.size() != expected_size) {
    fprintf(stderr,
            "ERROR: materialized benchmark input has wrong size: %s "
            "(expected %zu, found %zu)\n",
            key.c_str(), expected_size, text.size());
    exit(1);
  }
  std::string expected_sha256 = entry->at("sha256").get<std::string>();
  std::string actual_sha256 = sha256_hex(text);
  if (actual_sha256 != expected_sha256) {
    fprintf(stderr,
            "ERROR: materialized benchmark input has wrong SHA-256: %s "
            "(expected %s, found %s)\n",
            key.c_str(), expected_sha256.c_str(), actual_sha256.c_str());
    exit(1);
  }
  return text;
}

// ---------------------------------------------------------------------------
// Benchmark implementations
// ---------------------------------------------------------------------------


size_t advance_utf8(const std::string& text, size_t position) {
  if (position >= text.size()) return position + 1;
  position++;
  while (position < text.size() &&
         (static_cast<unsigned char>(text[position]) & 0xc0) == 0x80) {
    position++;
  }
  return position;
}

std::vector<re2::StringPiece> next_match(
    const RE2& regex, const std::string& text, size_t start,
    int capture_count, RE2::Anchor anchor = RE2::UNANCHORED) {
  std::vector<re2::StringPiece> matches(capture_count);
  if (!regex.Match(
          text, start, text.size(), anchor, matches.data(), capture_count)) {
    matches.clear();
  }
  return matches;
}

size_t piece_start(
    const std::string& text, const re2::StringPiece& piece) {
  return static_cast<size_t>(piece.data() - text.data());
}

json execute_workload(
    const json& entry, const std::vector<std::unique_ptr<RE2>>& regexes,
    const std::vector<std::string>& texts) {
  const std::string operation = entry.at("operation");
  const json& arguments = entry.at("arguments");
  static const std::string empty_text;
  const std::string& text = texts.empty() ? empty_text : texts.front();
  const RE2& regex = *regexes.front();
  std::vector<int> groups =
      arguments.contains("groups")
          ? arguments.at("groups").get<std::vector<int>>()
          : std::vector<int>();
  int group = arguments.value("group", 0);
  int capture_count = 1;
  for (int selected : groups) capture_count = std::max(capture_count, selected + 1);
  capture_count = std::max(capture_count, group + 1);

  if (operation == "compile") {
    auto compiled =
        std::make_unique<RE2>(entry.at("patterns").at(0).get<std::string>());
    return compiled->ok();
  }
  if (operation == "matches") {
    return regex.Match(
        text, 0, text.size(), RE2::ANCHOR_BOTH, nullptr, 0);
  }
  if (operation == "find") {
    return regex.Match(
        text, 0, text.size(), RE2::UNANCHORED, nullptr, 0);
  }
  if (operation == "matchesCorpus") {
    int count = 0;
    for (const auto& candidate : texts) {
      count += regex.Match(
          candidate, 0, candidate.size(), RE2::ANCHOR_BOTH, nullptr, 0);
    }
    return count;
  }
  if (operation == "matchesGroupLengthSum") {
    int total = 0;
    for (const auto& candidate : texts) {
      auto matches = next_match(
          regex, candidate, 0, capture_count, RE2::ANCHOR_BOTH);
      for (int selected : groups) {
        if (!matches.empty() && matches[selected].data() != nullptr) {
          total += matches[selected].size();
        }
      }
    }
    return total;
  }
  if (operation == "captureGroups") {
    std::string result;
    auto matches =
        next_match(regex, text, 0, capture_count, RE2::ANCHOR_BOTH);
    for (int selected : groups) {
      if (!matches.empty() && matches[selected].data() != nullptr) {
        result.append(matches[selected].data(), matches[selected].size());
      }
    }
    return result;
  }
  if (operation == "findGroupPresent" || operation == "findGroup") {
    auto matches = next_match(regex, text, 0, capture_count);
    bool present =
        !matches.empty() && matches[group].data() != nullptr;
    if (operation == "findGroupPresent") return present;
    return present
               ? std::string(matches[group].data(), matches[group].size())
               : std::string();
  }
  if (operation == "replaceFirst" || operation == "replaceAll") {
    std::string result = text;
    std::string replacement = arguments.value("replacement", "");
    if (operation == "replaceFirst") {
      RE2::Replace(&result, regex, replacement);
    } else {
      RE2::GlobalReplace(&result, regex, replacement);
    }
    return result;
  }
  if (operation == "replaceAllLengthSum") {
    int total = 0;
    std::string replacement = arguments.value("replacement", "");
    for (const auto& item : regexes) {
      std::string result = text;
      RE2::GlobalReplace(&result, *item, replacement);
      total += result.size();
    }
    return total;
  }

  int count = 0;
  int total = 0;
  size_t start = 0;
  size_t previous = 0;
  std::vector<std::string> split_parts;
  int limit = arguments.value("limit", 0);
  while (start <= text.size()) {
    auto matches = next_match(regex, text, start, capture_count);
    if (matches.empty()) break;
    size_t match_start = piece_start(text, matches[0]);
    size_t match_end = match_start + matches[0].size();
    if (operation == "findAllCount") count++;
    if (operation == "findAllLengthSum") total += matches[0].size();
    if (operation == "findAllGroupLengthSum") {
      for (int selected : groups) {
        if (matches[selected].data() != nullptr) {
          total += matches[selected].size();
        }
      }
    }
    if (operation == "splitLengthSum") {
      if (limit > 0 && static_cast<int>(split_parts.size()) == limit - 1) {
        break;
      }
      split_parts.push_back(text.substr(previous, match_start - previous));
      previous = match_end;
    }
    start = match_end > start ? match_end : advance_utf8(text, start);
  }
  if (operation == "findAllCount") return count;
  if (operation == "findAllLengthSum" ||
      operation == "findAllGroupLengthSum") {
    return total;
  }
  if (operation == "splitLengthSum") {
    split_parts.push_back(text.substr(previous));
    if (limit == 0) {
      while (!split_parts.empty() && split_parts.back().empty()) {
        split_parts.pop_back();
      }
    }
    size_t length_sum = split_parts.size();
    for (const auto& part : split_parts) length_sum += part.size();
    return length_sum;
  }
  fprintf(
      stderr, "ERROR: unsupported runnable operation: %s\n",
      operation.c_str());
  exit(1);
}

void run_execution_plan(
    const std::vector<std::string>& filters, bool smoke, bool list,
    bool list_exclusions) {
  for (const auto& entry : benchmark_execution_plan.at("entries")) {
    if (entry.at("engineId") != kEngineId) continue;
    std::string id = entry.at("workloadId");
    if (!matches_filter(id, filters)) continue;
    std::string status = entry.at("status");
    if (status == "excluded") {
      if (list_exclusions) {
        json output = {
            {"engine", kEngineId},
            {"benchmark", id},
            {"reason", entry.at("exclusion").at("reason")}};
        printf("%s\n", output.dump().c_str());
      }
      continue;
    }
    if (status != "runnable") {
      fprintf(stderr, "ERROR: unknown execution-plan status: %s\n",
              status.c_str());
      exit(1);
    }
    if (list) {
      printf("%s\n", id.c_str());
      continue;
    }
    if (list_exclusions) continue;

    std::vector<std::unique_ptr<RE2>> regexes;
    for (const auto& pattern : entry.at("patterns")) {
      regexes.push_back(
          std::make_unique<RE2>(pattern.get<std::string>()));
      if (!regexes.back()->ok()) {
        fprintf(stderr, "ERROR: planned pattern failed to compile: %s\n",
                id.c_str());
        exit(1);
      }
    }
    std::vector<std::string> texts;
    for (const auto& input : entry.at("inputs")) {
      texts.push_back(load_benchmark_input(input.get<std::string>()));
    }
    json expected = entry.value("expected", json());
    if (!expected.is_null()) {
      json actual = execute_workload(entry, regexes, texts);
      if (actual != expected.at("value")) {
        fprintf(
            stderr, "ERROR: result mismatch for %s: expected %s, got %s\n",
            id.c_str(), expected.at("value").dump().c_str(),
            actual.dump().c_str());
        exit(1);
      }
    }
    auto operation = [&]() {
      do_not_optimize(execute_workload(entry, regexes, texts));
    };
    std::string timing_unit = entry.at("measurement").at("timingUnit");
    std::string unit;
    double divisor;
    if (timing_unit == "nanoseconds") {
      unit = "ns/op";
      divisor = 1;
    } else if (timing_unit == "microseconds") {
      unit = "us/op";
      divisor = 1000;
    } else if (timing_unit == "milliseconds") {
      unit = "ms/op";
      divisor = 1000000;
    } else {
      fprintf(stderr, "ERROR: unsupported timing unit: %s\n",
              timing_unit.c_str());
      exit(1);
    }
    BenchResult result =
        smoke ? measure(id, operation, 0, 0, 1, 0.001, unit, divisor)
              : measure(id, operation, 2, 2, 10, 2, unit, divisor);
    print_json(result);
  }
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

int main(int argc, char* argv[]) {
  if (argc == 2 && std::string_view(argv[1]) == "--sha256-self-test") {
    return run_sha256_self_test() ? 0 : 1;
  }
  if (argc == 2 && std::string_view(argv[1]) == "--engine-self-test") {
    RE2 pattern("(a+)-(b+)");
    if (!pattern.ok()) {
      fprintf(stderr, "%s self-test failed to compile\n", kEngineId);
      return 1;
    }
#ifdef SAFERE_PCRE2_JIT
    if (pattern.jit_size() == 0) {
      fprintf(stderr, "PCRE2 JIT self-test did not generate JIT code\n");
      return 1;
    }
#endif
    std::string first;
    std::string second;
    if (!RE2::FullMatch("aaa-bb", pattern, &first, &second) ||
        first != "aaa" || second != "bb") {
      fprintf(stderr, "%s self-test failed to match captures\n", kEngineId);
      return 1;
    }
    RE2 alternative("a|ab");
    if (!RE2::FullMatch("ab", alternative)) {
      fprintf(stderr, "%s self-test failed to end-anchor alternatives\n",
              kEngineId);
      return 1;
    }
    std::string replaced = "aaa-bb a-b";
#ifdef SAFERE_PCRE2_JIT
    const std::string replacement = "$2:$1";
#else
    const std::string replacement = "\\2:\\1";
#endif
    if (RE2::GlobalReplace(&replaced, pattern, replacement) != 2 ||
        replaced != "bb:aaa b:a") {
      fprintf(stderr, "%s self-test failed to replace captures\n", kEngineId);
      return 1;
    }
    return 0;
  }

  std::string manifest_path = "../../target/benchmark-corpus/manifest.json";
  std::vector<std::string> filters;
  bool smoke = false;
  bool list = false;
  bool list_exclusions = false;

  for (int i = 1; i < argc; ++i) {
    std::string arg = argv[i];
    if (arg == "--manifest" && i + 1 < argc) {
      manifest_path = argv[++i];
    } else if (arg == "--smoke") {
      smoke = true;
    } else if (arg == "--list") {
      list = true;
    } else if (arg == "--list-exclusions") {
      list_exclusions = true;
    } else {
      filters.push_back(arg);
    }
  }

  load_benchmark_manifest(manifest_path);
  run_execution_plan(filters, smoke, list, list_exclusions);

  return 0;
}
