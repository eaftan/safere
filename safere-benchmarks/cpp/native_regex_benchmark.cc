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
constexpr const char* kPatternProfileId = "pcre2";
constexpr const char* kReplacementProfileId = "pcre2";
constexpr bool kSupportsLinearTimeWorkloads = false;
constexpr bool kSupportsMeasuredReplacementWorkloads = false;
#else
constexpr const char* kEngineId = "re2_cpp";
constexpr const char* kPatternProfileId = "re2";
constexpr const char* kReplacementProfileId = "re2-cpp";
constexpr bool kSupportsLinearTimeWorkloads = true;
constexpr bool kSupportsMeasuredReplacementWorkloads = true;
#endif

json benchmark_input_manifest;
std::filesystem::path benchmark_input_directory;
std::unordered_map<std::string, std::string> benchmark_pattern_profile;
std::unordered_map<std::string, std::string> benchmark_replacement_profile;

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
  double var = 0;
  for (double s : samples) var += (s - mean) * (s - mean);
  double stddev = std::sqrt(var / (samples.size() - 1));
  // t-value for 99.9% CI with 9 df ≈ 4.781
  double error = 4.781 * stddev / std::sqrt(samples.size());

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
  json benchmark_data = manifest.at("benchmarkData");
  if (benchmark_data.contains("patternProfiles")) {
    const auto& profiles = benchmark_data.at("patternProfiles");
    if (profiles.contains(kPatternProfileId)) {
      for (const auto& entry : profiles.at(kPatternProfileId)) {
        benchmark_pattern_profile.emplace(
            entry.at("java").get<std::string>(),
            entry.at("alternate").get<std::string>());
      }
    }
  }
  if (benchmark_data.contains("replacementProfiles")) {
    const auto& profiles = benchmark_data.at("replacementProfiles");
    if (profiles.contains(kReplacementProfileId)) {
      for (const auto& entry : profiles.at(kReplacementProfileId)) {
        benchmark_replacement_profile.emplace(
            entry.at("java").get<std::string>(),
            entry.at("alternate").get<std::string>());
      }
    }
  }
  return benchmark_data;
}

std::string select_pattern(const std::string& java_pattern) {
  auto alternate = benchmark_pattern_profile.find(java_pattern);
  return alternate == benchmark_pattern_profile.end()
             ? java_pattern
             : alternate->second;
}

std::string select_replacement(const std::string& java_replacement) {
  auto alternate = benchmark_replacement_profile.find(java_replacement);
  return alternate == benchmark_replacement_profile.end()
             ? java_replacement
             : alternate->second;
}

void validate_pattern_profile() {
  for (const auto& [java_pattern, alternate] : benchmark_pattern_profile) {
    RE2 pattern(alternate);
    if (!pattern.ok()) {
      fprintf(
          stderr, "ERROR: %s pattern alternate for %s does not compile\n",
          kPatternProfileId, java_pattern.c_str());
      exit(1);
    }
  }
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

void run_regex_benchmarks(const json& data,
                          const std::vector<std::string>& filters) {
  const auto& sec = data["regex"];

  RE2 hello(select_pattern(sec["literalMatch"]["pattern"].get<std::string>()));
  RE2 alpha(select_pattern(sec["charClassMatch"]["pattern"].get<std::string>()));
  RE2 alt(select_pattern(sec["alternationFind"]["pattern"].get<std::string>()));
  RE2 date(select_pattern(sec["captureGroups"]["pattern"].get<std::string>()));
  RE2 find_ing(select_pattern(sec["findInText"]["pattern"].get<std::string>()));
  RE2 email(select_pattern(sec["emailFind"]["pattern"].get<std::string>()));

  std::string hello_text = sec["literalMatch"]["text"];
  std::string alpha_text = sec["charClassMatch"]["text"];
  std::string alt_text = sec["alternationFind"]["text"];
  std::string date_text = sec["captureGroups"]["text"];
  std::string prose = sec["findInText"]["text"];
  std::string email_text = sec["emailFind"]["text"];
  int capture_groups = sec["captureGroups"]["groups"];

  auto run = [&](const std::string& name, const std::function<void()>& fn) {
    if (matches_filter(name, filters)) {
      print_json(measure(name, fn));
    }
  };

  run("RegexBenchmark.literalMatch", [&]() {
    do_not_optimize(RE2::FullMatch(hello_text, hello));
  });
  run("RegexBenchmark.charClassMatch", [&]() {
    do_not_optimize(RE2::FullMatch(alpha_text, alpha));
  });
  run("RegexBenchmark.alternationFind", [&]() {
    re2::StringPiece input(alt_text);
    int count = 0;
    std::string match;
    while (RE2::FindAndConsume(&input, alt, &match)) { ++count; }
    do_not_optimize(count);
  });
  run("RegexBenchmark.captureGroups", [&]() {
    if (capture_groups == 3) {
      std::string g1, g2, g3;
      RE2::FullMatch(date_text, date, &g1, &g2, &g3);
      do_not_optimize(g1);
    } else {
      do_not_optimize(RE2::FullMatch(date_text, date));
    }
  });
  run("RegexBenchmark.findInText", [&]() {
    re2::StringPiece input(prose);
    int count = 0;
    std::string match;
    while (RE2::FindAndConsume(&input, find_ing, &match)) { ++count; }
    do_not_optimize(count);
  });
  run("RegexBenchmark.emailFind", [&]() {
    do_not_optimize(RE2::PartialMatch(email_text, email));
  });
}

void run_application_benchmarks(const json& data,
                                const std::vector<std::string>& filters) {
  auto run = [&](const std::string& name, const std::function<void()>& fn) {
    if (matches_filter(name, filters)) {
      print_json(measure(name, fn));
    }
  };

  struct AppCase {
    std::string name;
    std::string op;
    std::string pattern;
    std::vector<std::string> texts;
    std::string text;
    std::vector<int> groups;
    std::string replacement;
    json expected;
    RE2 re;

    explicit AppCase(const json& item)
        : name(item.at("name").get<std::string>()),
          op(item.at("op").get<std::string>()),
          pattern(select_pattern(item.at("pattern").get<std::string>())),
          texts(item.contains("texts")
                    ? item.at("texts").get<std::vector<std::string>>()
                    : std::vector<std::string>()),
          text(item.value("text", "")),
          groups(item.contains("groups") ? item.at("groups").get<std::vector<int>>()
                                         : std::vector<int>()),
          replacement(item.contains("replacement")
                          ? select_replacement(item.at("replacement").get<std::string>())
                          : ""),
          expected(item.at("expected")),
          re(pattern) {}
  };

  auto max_group = [](const std::vector<int>& groups) {
    int max = 0;
    for (int group : groups) {
      if (group > max) max = group;
    }
    return max;
  };

  auto group_length_sum = [](const std::vector<re2::StringPiece>& matches,
                             const std::vector<int>& groups) {
    int sum = 0;
    for (int group : groups) {
      if (group < static_cast<int>(matches.size()) && matches[group].data() != nullptr) {
        sum += matches[group].size();
      }
    }
    return sum;
  };

  auto matches_groups =
      [&](const AppCase& app_case, const std::string& text,
          std::vector<re2::StringPiece>* matches) {
        int match_count = std::max(1, max_group(app_case.groups) + 1);
        matches->assign(match_count, re2::StringPiece());
        return app_case.re.Match(
            text, 0, text.size(), RE2::ANCHOR_BOTH, matches->data(), match_count);
      };

  auto run_int = [&](const AppCase& app_case) {
    if (app_case.op == "matchesCorpus") {
      int count = 0;
      for (const auto& text : app_case.texts) {
        if (RE2::FullMatch(text, app_case.re)) ++count;
      }
      return count;
    }
    if (app_case.op == "matchesGroupLengthSum") {
      int count = 0;
      std::vector<re2::StringPiece> matches;
      for (const auto& text : app_case.texts) {
        if (matches_groups(app_case, text, &matches)) {
          count += group_length_sum(matches, app_case.groups);
        }
      }
      return count;
    }
    if (app_case.op == "findAllCount") {
      int count = 0;
      int start = 0;
      std::vector<re2::StringPiece> matches(1);
      while (start <= static_cast<int>(app_case.text.size()) &&
             app_case.re.Match(app_case.text, start, app_case.text.size(),
                               RE2::UNANCHORED, matches.data(), matches.size())) {
        ++count;
        int end = matches[0].data() - app_case.text.data() + matches[0].size();
        start = matches[0].empty() ? end + 1 : end;
      }
      return count;
    }
    if (app_case.op == "findAllLengthSum" ||
        app_case.op == "findAllGroupLengthSum") {
      int count = 0;
      int start = 0;
      int match_count = std::max(1, max_group(app_case.groups) + 1);
      std::vector<re2::StringPiece> matches(match_count);
      while (start <= static_cast<int>(app_case.text.size()) &&
             app_case.re.Match(app_case.text, start, app_case.text.size(),
                               RE2::UNANCHORED, matches.data(), matches.size())) {
        if (app_case.op == "findAllLengthSum") {
          count += matches[0].size();
        } else {
          count += group_length_sum(matches, app_case.groups);
        }
        int end = matches[0].data() - app_case.text.data() + matches[0].size();
        start = matches[0].empty() ? end + 1 : end;
      }
      return count;
    }
    fprintf(stderr, "ERROR: string op used as int op: %s\n", app_case.op.c_str());
    exit(1);
  };

  auto run_string = [](const AppCase& app_case) {
    std::string s = app_case.text;
    RE2::GlobalReplace(&s, app_case.re, app_case.replacement);
    return s;
  };

  std::vector<std::unique_ptr<AppCase>> cases;
  for (const auto& item : data["application"]) {
    cases.push_back(std::make_unique<AppCase>(item));
  }

  for (const auto& app_case_ptr : cases) {
    const AppCase& app_case = *app_case_ptr;
    if (!app_case.re.ok()) {
      fprintf(stderr, "ERROR: invalid application pattern: %s\n",
              app_case.name.c_str());
      exit(1);
    }
    if (app_case.op == "replaceAll" &&
        !kSupportsMeasuredReplacementWorkloads) {
      continue;
    }
    if (app_case.op.rfind("findAll", 0) == 0 &&
        RE2::PartialMatch("", app_case.re)) {
      fprintf(stderr, "ERROR: empty-width find-all application pattern: %s\n",
              app_case.name.c_str());
      exit(1);
    }
    if (app_case.op == "replaceAll") {
      std::string actual = run_string(app_case);
      if (actual != app_case.expected.get<std::string>()) {
        fprintf(stderr, "ERROR: %s expected result mismatch\n", app_case.name.c_str());
        exit(1);
      }
    } else {
      int actual = run_int(app_case);
      if (actual != app_case.expected.get<int>()) {
        fprintf(stderr, "ERROR: %s expected %d but was %d\n",
                app_case.name.c_str(), app_case.expected.get<int>(), actual);
        exit(1);
      }
    }
  }

  for (const auto& app_case_ptr : cases) {
    const AppCase& app_case = *app_case_ptr;
    if (app_case.op == "replaceAll" &&
        !kSupportsMeasuredReplacementWorkloads) {
      continue;
    }
    run("ApplicationBenchmark." + app_case.name, [&]() {
      if (app_case.op == "replaceAll") {
        do_not_optimize(run_string(app_case));
      } else {
        do_not_optimize(run_int(app_case));
      }
    });
  }
}

void run_real_world_regex_benchmarks(
    const json& data, const std::vector<std::string>& filters) {
  const auto& sec = data["realWorldRegex"];
  std::vector<int> sizes = sec["textSizes"].get<std::vector<int>>();

  struct RealWorldCase {
    std::string name;
    std::string op;
    std::string pattern;
    std::string replacement;
    RE2 re;

    explicit RealWorldCase(const json& item)
        : name(item.at("name").get<std::string>()),
          op(item.at("op").get<std::string>()),
          pattern(select_pattern(item.at("pattern").get<std::string>())),
          replacement(item.contains("replacement")
                          ? select_replacement(
                                item.at("replacement").get<std::string>())
                          : ""),
          re(pattern) {}
  };

  std::vector<std::unique_ptr<RealWorldCase>> cases;
  for (const auto& item : sec["cases"]) {
    cases.push_back(std::make_unique<RealWorldCase>(item));
  }

  for (const auto& case_ptr : cases) {
    const RealWorldCase& c = *case_ptr;
    if (!c.re.ok()) {
      fprintf(stderr, "ERROR: invalid real-world regex pattern: %s\n",
              c.name.c_str());
      exit(1);
    }
    if (c.op != "find" && c.op != "matches" && c.op != "replaceAllEmpty" && c.op != "replaceAllGroup1" && c.op != "replaceAllLiteral") {
      fprintf(stderr, "ERROR: invalid real-world regex op: %s\n",
              c.op.c_str());
      exit(1);
    }
  }

  for (const auto& case_ptr : cases) {
    const RealWorldCase& c = *case_ptr;
    if (c.op.rfind("replaceAll", 0) == 0 &&
        !kSupportsMeasuredReplacementWorkloads) {
      continue;
    }
    for (bool match : {true, false}) {
      std::string match_label = match ? "match" : "noMatch";
      for (int size : sizes) {
        std::string text = load_benchmark_input(
            "realWorldRegex." + c.name + "." + match_label + "." +
            std::to_string(size));
        std::string name = "RealWorldRegexBenchmark.runBenchmark." + c.name +
                           "." + match_label + "." + std::to_string(size);
        if (!matches_filter(name, filters)) {
          continue;
        }
        if (c.op == "find") {
          print_json(measure(name, [&]() {
            do_not_optimize(RE2::PartialMatch(text, c.re));
          }));
        } else if (c.op == "matches") {
          print_json(measure(name, [&]() {
            do_not_optimize(RE2::FullMatch(text, c.re));
          }));
        } else if (c.op == "replaceAllEmpty") {
          print_json(measure(name, [&]() {
            std::string replaced = text;
            RE2::GlobalReplace(&replaced, c.re, c.replacement);
            do_not_optimize(replaced);
          }));
        } else if (c.op == "replaceAllGroup1") {
          print_json(measure(name, [&]() {
            std::string replaced = text;
            RE2::GlobalReplace(&replaced, c.re, c.replacement);
            do_not_optimize(replaced);
          }));
        } else if (c.op == "replaceAllLiteral") {
          print_json(measure(name, [&]() {
            std::string replaced = text;
            RE2::GlobalReplace(&replaced, c.re, c.replacement);
            do_not_optimize(replaced);
          }));
        }
      }
    }
  }
}

void run_compile_benchmarks(const json& data,
                            const std::vector<std::string>& filters) {
  const auto& sec = data["compile"];

  std::string simple =
      select_pattern(sec["simple"]["pattern"].get<std::string>());
  std::string medium =
      select_pattern(sec["medium"]["pattern"].get<std::string>());
  std::string complex_pat =
      select_pattern(sec["complex"]["pattern"].get<std::string>());
  std::string alternation =
      select_pattern(sec["alternation"]["pattern"].get<std::string>());

  auto run = [&](const std::string& name, const std::string& pattern) {
    if (matches_filter(name, filters)) {
      print_json(measure(name, [&]() {
        RE2 re(pattern);
        do_not_optimize(re.ok());
      }, 2, 2.0, 10, 2.0, "us/op", 1000.0));
    }
  };

  run("CompileBenchmark.compileSimple", simple);
  run("CompileBenchmark.compileMedium", medium);
  run("CompileBenchmark.compileComplex", complex_pat);
  run("CompileBenchmark.compileAlternation", alternation);
}

void run_search_scaling_benchmarks(const json& data,
                                   const std::vector<std::string>& filters) {
  const auto& sec = data["searchScaling"];
  std::vector<int> sizes = sec["textSizes"].get<std::vector<int>>();

  RE2 easy(select_pattern(sec["patterns"]["easy"].get<std::string>()));
  RE2 medium(select_pattern(sec["patterns"]["medium"].get<std::string>()));
  RE2 hard(select_pattern(sec["patterns"]["hard"].get<std::string>()));
  RE2 find_ing(select_pattern(sec["findIngPattern"].get<std::string>()));

  for (int size : sizes) {
    std::string size_string = std::to_string(size);
    std::string random_text =
        load_benchmark_input("searchScaling.random." + size_string);
    std::string text_with_match =
        load_benchmark_input("searchScaling.success." + size_string);
    std::string prose =
        load_benchmark_input("searchScaling.prose." + size_string);

    std::string suffix = "." + std::to_string(size);

    auto run = [&](const std::string& name, const std::function<void()>& fn) {
      std::string full_name = name + suffix;
      if (matches_filter(full_name, filters)) {
        print_json(measure(full_name, fn, 2, 2.0, 10, 2.0, "us/op", 1000.0));
      }
    };

    run("SearchScalingBenchmark.searchEasyFail", [&]() {
      do_not_optimize(RE2::PartialMatch(random_text, easy));
    });
    run("SearchScalingBenchmark.searchEasySuccess", [&]() {
      do_not_optimize(RE2::PartialMatch(text_with_match, easy));
    });
    run("SearchScalingBenchmark.searchMediumFail", [&]() {
      do_not_optimize(RE2::PartialMatch(random_text, medium));
    });
    run("SearchScalingBenchmark.searchHardFail", [&]() {
      do_not_optimize(RE2::PartialMatch(random_text, hard));
    });
    run("SearchScalingBenchmark.findIngScaled", [&]() {
      re2::StringPiece input(prose);
      int count = 0;
      std::string match;
      while (RE2::FindAndConsume(&input, find_ing, &match)) { ++count; }
      do_not_optimize(count);
    });
  }
}

void run_issue481_scaling_benchmarks(const json& data,
                                     const std::vector<std::string>& filters) {
  const auto& sec = data["issue481Scaling"];
  std::vector<int> sizes = sec["textSizes"].get<std::vector<int>>();

  RE2 split_w(select_pattern(sec["splitW"]["pattern"].get<std::string>()));
  RE2 block(select_pattern(sec["block"]["pattern"].get<std::string>()));
  RE2 tag(select_pattern(sec["tag"]["pattern"].get<std::string>()));
  RE2 scheme(select_pattern(sec["scheme"]["pattern"].get<std::string>()));

  auto split_length_sum = [](const std::string& text, RE2& re) {
    int sum = 0;
    int count = 0;
    int start = 0;
    int last = 0;
    std::vector<re2::StringPiece> matches(1);
    while (start <= static_cast<int>(text.size()) &&
           re.Match(text, start, text.size(), RE2::UNANCHORED,
                    matches.data(), matches.size())) {
      int match_start = matches[0].data() - text.data();
      int match_end = match_start + matches[0].size();
      ++count;
      sum += match_start - last;
      last = match_end;
      start = matches[0].empty() ? match_end + 1 : match_end;
    }
    if (last == 0) {
      return static_cast<int>(text.size()) + 1;
    }
    int trailing_length = text.size() - last;
    if (trailing_length > 0) {
      ++count;
      sum += trailing_length;
    }
    return sum + count;
  };

  auto find = [](const std::string& text, RE2& re) {
    return RE2::PartialMatch(text, re);
  };

  auto scheme_extract = [](const std::string& text, RE2& re) {
    int sum = 0;
    int start = 0;
    std::vector<re2::StringPiece> matches(3);
    while (start <= static_cast<int>(text.size()) &&
           re.Match(text, start, text.size(), RE2::UNANCHORED,
                    matches.data(), matches.size())) {
      sum += matches[1].size();
      sum += matches[2].size();
      int end = matches[0].data() - text.data() + matches[0].size();
      start = matches[0].empty() ? end + 1 : end;
    }
    return sum;
  };

  for (int size : sizes) {
    std::string size_string = std::to_string(size);
    std::string split_text =
        load_benchmark_input("issue481Scaling.splitW." + size_string);
    std::string block_text =
        load_benchmark_input("issue481Scaling.block." + size_string);
    std::string block_negative_text =
        load_benchmark_input("issue481Scaling.blockNegative." + size_string);
    std::string tag_text =
        load_benchmark_input("issue481Scaling.tag." + size_string);
    std::string tag_negative_text =
        load_benchmark_input("issue481Scaling.tagNegative." + size_string);
    std::string scheme_text =
        load_benchmark_input("issue481Scaling.scheme." + size_string);
    std::string scheme_negative_text =
        load_benchmark_input("issue481Scaling.schemeNegative." + size_string);

    std::string suffix = "." + std::to_string(size);
    auto run = [&](const std::string& name, const std::function<void()>& fn) {
      std::string full_name = name + suffix;
      if (matches_filter(full_name, filters)) {
        print_json(measure(full_name, fn, 2, 2.0, 10, 2.0, "us/op", 1000.0));
      }
    };

    run("Issue481ScalingBenchmark.splitWords", [&]() {
      do_not_optimize(split_length_sum(split_text, split_w));
    });
    run("Issue481ScalingBenchmark.blockFind", [&]() {
      do_not_optimize(find(block_text, block));
    });
    run("Issue481ScalingBenchmark.blockFindNegative", [&]() {
      do_not_optimize(find(block_negative_text, block));
    });
    run("Issue481ScalingBenchmark.tagFind", [&]() {
      do_not_optimize(find(tag_text, tag));
    });
    run("Issue481ScalingBenchmark.tagFindNegative", [&]() {
      do_not_optimize(find(tag_negative_text, tag));
    });
    run("Issue481ScalingBenchmark.schemeExtract", [&]() {
      do_not_optimize(scheme_extract(scheme_text, scheme));
    });
    run("Issue481ScalingBenchmark.schemeFindNegative", [&]() {
      do_not_optimize(find(scheme_negative_text, scheme));
    });
  }
}

void run_capture_scaling_benchmarks(const json& data,
                                    const std::vector<std::string>& filters) {
  const auto& sec = data["captureScaling"];

  RE2 pat0(select_pattern(sec["capture0"]["pattern"].get<std::string>()));
  RE2 pat1(select_pattern(sec["capture1"]["pattern"].get<std::string>()));
  RE2 pat3(select_pattern(sec["capture3"]["pattern"].get<std::string>()));
  RE2 pat10(select_pattern(sec["capture10"]["pattern"].get<std::string>()));

  std::string text0 = sec["capture0"]["text"];
  std::string text1 = sec["capture1"]["text"];
  std::string text3 = sec["capture3"]["text"];
  std::string text10 = sec["capture10"]["text"];

  int groups0 = sec["capture0"]["groups"];
  int groups1 = sec["capture1"]["groups"];
  int groups3 = sec["capture3"]["groups"];
  int groups10 = sec["capture10"]["groups"];

  auto run = [&](const std::string& name, const std::function<void()>& fn) {
    if (matches_filter(name, filters)) {
      print_json(measure(name, fn));
    }
  };

  run("CaptureScalingBenchmark.capture0", [&]() {
    do_not_optimize(RE2::FullMatch(text0, pat0));
  });
  run("CaptureScalingBenchmark.capture1", [&]() {
    std::string g1;
    RE2::FullMatch(text1, pat1, &g1);
    do_not_optimize(g1);
  });
  run("CaptureScalingBenchmark.capture3", [&]() {
    std::string g1, g2, g3;
    RE2::FullMatch(text3, pat3, &g1, &g2, &g3);
    do_not_optimize(g1);
  });
  run("CaptureScalingBenchmark.capture10", [&]() {
    std::string g1, g2, g3, g4, g5, g6, g7, g8, g9, g10;
    RE2::FullMatch(text10, pat10, &g1, &g2, &g3, &g4, &g5,
                   &g6, &g7, &g8, &g9, &g10);
    do_not_optimize(g1);
  });

  // Suppress unused variable warnings for groups (used to validate JSON).
  do_not_optimize(groups0);
  do_not_optimize(groups1);
  do_not_optimize(groups3);
  do_not_optimize(groups10);
}

void run_http_benchmarks(const json& data,
                         const std::vector<std::string>& filters) {
  const auto& sec = data["http"];

  RE2 http(select_pattern(sec["pattern"].get<std::string>()));
  std::string full = sec["fullRequest"];
  std::string small = sec["smallRequest"];

  auto run = [&](const std::string& name, const std::function<void()>& fn) {
    if (matches_filter(name, filters)) {
      print_json(measure(name, fn));
    }
  };

  run("HttpBenchmark.httpFull", [&]() {
    std::string path;
    RE2::PartialMatch(full, http, &path);
    do_not_optimize(path);
  });
  run("HttpBenchmark.httpSmall", [&]() {
    std::string path;
    RE2::PartialMatch(small, http, &path);
    do_not_optimize(path);
  });
  run("HttpBenchmark.httpExtract", [&]() {
    std::string path;
    RE2::PartialMatch(full, http, &path);
    do_not_optimize(path);
  });
}

void run_replace_benchmarks(const json& data,
                            const std::vector<std::string>& filters) {
  const auto& sec = data["replace"];

  struct ReplaceCase {
    std::string name;
    std::string pattern;
    std::string text;
    std::string replacement;
    std::string op;
  };

  std::vector<ReplaceCase> cases;
  for (auto& [key, val] : sec.items()) {
    cases.push_back({
        key,
        select_pattern(val["pattern"].get<std::string>()),
        val["text"].get<std::string>(),
        select_replacement(val["replacement"].get<std::string>()),
        val["op"].get<std::string>()
    });
  }

  for (const auto& c : cases) {
    std::string bench_name = "ReplaceBenchmark." + c.name;
    if (!matches_filter(bench_name, filters)) continue;

    RE2 re(c.pattern);
    if (c.op == "replaceFirst") {
      print_json(measure(bench_name, [&]() {
        std::string s = c.text;
        RE2::Replace(&s, re, c.replacement);
        do_not_optimize(s);
      }));
    } else {
      print_json(measure(bench_name, [&]() {
        std::string s = c.text;
        RE2::GlobalReplace(&s, re, c.replacement);
        do_not_optimize(s);
      }));
    }
  }
}

void run_pathological_benchmarks(const json& data,
                                 const std::vector<std::string>& filters) {
  std::vector<int> ns =
      data["pathological"]["nValues"].get<std::vector<int>>();

  for (int n : ns) {
    std::string n_string = std::to_string(n);
    std::string regex =
        load_benchmark_input("pathological.pattern." + n_string);
    std::string text =
        load_benchmark_input("pathological.text." + n_string);

    std::string name =
        "PathologicalBenchmark.pathological." + std::to_string(n);
    if (matches_filter(name, filters)) {
      RE2 re(select_pattern(regex));
      print_json(measure(name, [&]() {
        do_not_optimize(RE2::FullMatch(text, re));
      }, 2, 2.0, 10, 2.0, "us/op", 1000.0));
    }
  }
}

void run_fanout_benchmarks(const json& data,
                           const std::vector<std::string>& filters) {
  const auto& sec = data["fanout"];
  std::vector<int> sizes = sec["textSizes"].get<std::vector<int>>();

  RE2 fanout(select_pattern(sec["unicodeFanout"]["pattern"].get<std::string>()));
  RE2 nested(select_pattern(sec["nestedQuantifier"]["pattern"].get<std::string>()));

  for (int size : sizes) {
    std::string size_string = std::to_string(size);
    std::string unicode_text =
        load_benchmark_input("fanout.unicode." + size_string);
    std::string ascii_text =
        load_benchmark_input("fanout.ascii." + size_string);

    std::string suffix = "." + std::to_string(size);

    auto run = [&](const std::string& name, const std::function<void()>& fn) {
      std::string full_name = name + suffix;
      if (matches_filter(full_name, filters)) {
        print_json(measure(full_name, fn, 2, 2.0, 10, 2.0, "us/op", 1000.0));
      }
    };

    run("FanoutBenchmark.fanoutUnicode", [&]() {
      do_not_optimize(RE2::PartialMatch(unicode_text, fanout));
    });
    run("FanoutBenchmark.nestedQuantifier", [&]() {
      do_not_optimize(RE2::PartialMatch(ascii_text, nested));
    });
  }
}

// ---------------------------------------------------------------------------
// Memory benchmarks
// ---------------------------------------------------------------------------

// Measure the heap allocation for compiling a single RE2 pattern when
// mallinfo2() is available. Also reports RE2's ProgramSize() (number of
// compiled bytecode instructions) on every platform.
void run_memory_benchmarks(const json& data,
                           const std::vector<std::string>& filters) {
  const auto& compile_sec = data["compile"];
  const auto& regex_sec = data["regex"];

  struct PatternInfo {
    std::string name;
    std::string pattern;
  };

  std::vector<PatternInfo> patterns = {
      {"MemoryBenchmark.compileSimple",
       compile_sec["simple"]["pattern"].get<std::string>()},
      {"MemoryBenchmark.compileMedium",
       compile_sec["medium"]["pattern"].get<std::string>()},
      {"MemoryBenchmark.compileComplex",
       compile_sec["complex"]["pattern"].get<std::string>()},
      {"MemoryBenchmark.compileAlternation",
       compile_sec["alternation"]["pattern"].get<std::string>()},
      {"MemoryBenchmark.literalMatch",
       regex_sec["literalMatch"]["pattern"].get<std::string>()},
      {"MemoryBenchmark.charClassMatch",
       regex_sec["charClassMatch"]["pattern"].get<std::string>()},
      {"MemoryBenchmark.alternationFind",
       regex_sec["alternationFind"]["pattern"].get<std::string>()},
      {"MemoryBenchmark.captureGroups",
       regex_sec["captureGroups"]["pattern"].get<std::string>()},
      {"MemoryBenchmark.findInText",
       regex_sec["findInText"]["pattern"].get<std::string>()},
      {"MemoryBenchmark.emailFind",
       regex_sec["emailFind"]["pattern"].get<std::string>()},
  };

  for (const auto& pi : patterns) {
    if (!matches_filter(pi.name, filters)) continue;
    std::string pattern = select_pattern(pi.pattern);

#ifdef SAFERE_HAVE_MALLINFO2
    // Measure heap delta around RE2 compilation using mallinfo2().
    struct mallinfo2 before = mallinfo2();
#endif
    auto re = std::make_unique<RE2>(pattern);
#ifdef SAFERE_HAVE_MALLINFO2
    struct mallinfo2 after = mallinfo2();

    long heap_delta = static_cast<long>(after.uordblks) -
                      static_cast<long>(before.uordblks);
    print_memory_json(pi.name + ".heapBytes", heap_delta);
#endif

#ifndef SAFERE_PCRE2_JIT
    // Report RE2's program size (number of bytecode instructions).
    if (re->ok()) {
      print_memory_json(pi.name + ".programSize",
                        re->ProgramSize(), "instructions");
      print_memory_json(pi.name + ".reverseProgramSize",
                        re->ReverseProgramSize(), "instructions");
    }
#endif
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

  for (int i = 1; i < argc; ++i) {
    std::string arg = argv[i];
    if (arg == "--manifest" && i + 1 < argc) {
      manifest_path = argv[++i];
    } else {
      filters.push_back(arg);
    }
  }

  json data = load_benchmark_manifest(manifest_path);
  validate_pattern_profile();

  run_regex_benchmarks(data, filters);
  run_application_benchmarks(data, filters);
  run_real_world_regex_benchmarks(data, filters);
  run_compile_benchmarks(data, filters);
  run_search_scaling_benchmarks(data, filters);
  run_issue481_scaling_benchmarks(data, filters);
  run_capture_scaling_benchmarks(data, filters);
  run_http_benchmarks(data, filters);
  if (kSupportsMeasuredReplacementWorkloads) {
    run_replace_benchmarks(data, filters);
  }
  if (kSupportsLinearTimeWorkloads) {
    run_pathological_benchmarks(data, filters);
  }
  run_fanout_benchmarks(data, filters);
  run_memory_benchmarks(data, filters);

  return 0;
}
