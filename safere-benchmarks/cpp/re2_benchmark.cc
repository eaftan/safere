// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.
//
// C++ RE2 benchmark harness. Runs the same patterns and inputs as the Java
// JMH benchmarks and outputs JSON lines for cross-language comparison.
// Patterns and inputs are loaded from a shared JSON data file.
//
// Build:
//   cd safere-benchmarks/cpp && mkdir -p build && cd build
//   cmake .. && cmake --build . -j$(nproc)
//
// Run:
//   ./build/re2_benchmark [--data path/to/benchmark-data.json] [filter...]
//
// Each filter is a substring match against benchmark names. If no filters
// are given, all benchmarks are run.

#include <cctype>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <functional>
#include <malloc.h>
#include <memory>
#include <random>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>
#include "re2/re2.h"

using json = nlohmann::json;

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
  printf("{\"engine\":\"re2_cpp\",\"benchmark\":\"%s\","
         "\"score\":%.3f,\"error\":%.3f,\"unit\":\"%s\"}\n",
         r.name.c_str(), r.ns_per_op, r.error, r.unit.c_str());
  fflush(stdout);
}

// Print a memory measurement result as JSON.
void print_memory_json(const std::string& name, long bytes,
                       const std::string& unit = "bytes") {
  printf("{\"engine\":\"re2_cpp\",\"benchmark\":\"%s\","
         "\"score\":%ld,\"error\":0,\"unit\":\"%s\"}\n",
         name.c_str(), bytes, unit.c_str());
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

json load_benchmark_data(const std::string& path) {
  std::ifstream ifs(path);
  if (!ifs.is_open()) {
    fprintf(stderr, "ERROR: cannot open benchmark data file: %s\n",
            path.c_str());
    exit(1);
  }
  return json::parse(ifs);
}

// Convert Java-style replacement references ($N) to RE2 C++ style (\\N).
std::string convert_replacement(const std::string& repl) {
  std::string result;
  for (size_t i = 0; i < repl.size(); ++i) {
    if (repl[i] == '$' && i + 1 < repl.size() &&
        std::isdigit(static_cast<unsigned char>(repl[i + 1]))) {
      result += '\\';
      ++i;
      while (i < repl.size() &&
             std::isdigit(static_cast<unsigned char>(repl[i]))) {
        result += repl[i];
        ++i;
      }
      --i;
    } else {
      result += repl[i];
    }
  }
  return result;
}

// ---------------------------------------------------------------------------
// Text generators (parameterized from JSON)
// ---------------------------------------------------------------------------

std::string make_random_text(int size, const std::string& alphabet,
                             unsigned seed) {
  std::mt19937 rng(seed);
  std::string text(size, ' ');
  for (int i = 0; i < size; ++i) {
    text[i] = alphabet[rng() % alphabet.size()];
  }
  return text;
}

std::string make_prose(int size, const std::string& unit) {
  std::string text;
  text.reserve(size + unit.size());
  while (static_cast<int>(text.size()) < size) {
    text += unit;
  }
  return text;
}

// Encode a Unicode code point as UTF-8 and append to the string.
void append_utf8(std::string& s, int cp) {
  if (cp < 0x80) {
    s += static_cast<char>(cp);
  } else if (cp < 0x800) {
    s += static_cast<char>(0xC0 | (cp >> 6));
    s += static_cast<char>(0x80 | (cp & 0x3F));
  } else if (cp < 0x10000) {
    s += static_cast<char>(0xE0 | (cp >> 12));
    s += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
    s += static_cast<char>(0x80 | (cp & 0x3F));
  } else {
    s += static_cast<char>(0xF0 | (cp >> 18));
    s += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
    s += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
    s += static_cast<char>(0x80 | (cp & 0x3F));
  }
}

std::string make_unicode_text(int size, const std::vector<int>& codepoints,
                              unsigned seed) {
  std::mt19937 rng(seed);
  std::string text;
  while (static_cast<int>(text.size()) < size) {
    int cp = codepoints[rng() % codepoints.size()];
    append_utf8(text, cp);
  }
  return text;
}

// ---------------------------------------------------------------------------
// Benchmark implementations
// ---------------------------------------------------------------------------

void run_regex_benchmarks(const json& data,
                          const std::vector<std::string>& filters) {
  const auto& sec = data["regex"];

  RE2 hello(sec["literalMatch"]["pattern"].get<std::string>());
  RE2 alpha(sec["charClassMatch"]["pattern"].get<std::string>());
  RE2 alt(sec["alternationFind"]["pattern"].get<std::string>());
  RE2 date(sec["captureGroups"]["pattern"].get<std::string>());
  RE2 find_ing(sec["findInText"]["pattern"].get<std::string>());
  RE2 email(sec["emailFind"]["pattern"].get<std::string>());

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
          pattern(item.at("pattern").get<std::string>()),
          texts(item.contains("texts")
                    ? item.at("texts").get<std::vector<std::string>>()
                    : std::vector<std::string>()),
          text(item.value("text", "")),
          groups(item.contains("groups") ? item.at("groups").get<std::vector<int>>()
                                         : std::vector<int>()),
          replacement(item.contains("replacement")
                          ? convert_replacement(item.at("replacement").get<std::string>())
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
    run("ApplicationBenchmark." + app_case.name, [&]() {
      if (app_case.op == "replaceAll") {
        do_not_optimize(run_string(app_case));
      } else {
        do_not_optimize(run_int(app_case));
      }
    });
  }
}

void run_compile_benchmarks(const json& data,
                            const std::vector<std::string>& filters) {
  const auto& sec = data["compile"];

  std::string simple = sec["simple"]["pattern"];
  std::string medium = sec["medium"]["pattern"];
  std::string complex_pat = sec["complex"]["pattern"];
  std::string alternation = sec["alternation"]["pattern"];

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
  std::string match_suffix = sec["matchSuffix"];
  std::string alphabet = sec["randomText"]["alphabet"];
  unsigned seed = sec["randomText"]["seed"];
  std::string prose_unit = sec["proseUnit"];

  RE2 easy(sec["patterns"]["easy"].get<std::string>());
  RE2 medium(sec["patterns"]["medium"].get<std::string>());
  RE2 hard(sec["patterns"]["hard"].get<std::string>());
  RE2 find_ing(sec["findIngPattern"].get<std::string>());

  for (int size : sizes) {
    std::string random_text = make_random_text(size, alphabet, seed);
    std::string text_with_match = random_text + match_suffix;
    std::string prose = make_prose(size, prose_unit);

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

void run_capture_scaling_benchmarks(const json& data,
                                    const std::vector<std::string>& filters) {
  const auto& sec = data["captureScaling"];

  RE2 pat0(sec["capture0"]["pattern"].get<std::string>());
  RE2 pat1(sec["capture1"]["pattern"].get<std::string>());
  RE2 pat3(sec["capture3"]["pattern"].get<std::string>());
  RE2 pat10(sec["capture10"]["pattern"].get<std::string>());

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

  RE2 http(sec["pattern"].get<std::string>());
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
        val["pattern"].get<std::string>(),
        val["text"].get<std::string>(),
        convert_replacement(val["replacement"].get<std::string>()),
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
    std::string regex;
    for (int i = 0; i < n; ++i) regex += "a?";
    for (int i = 0; i < n; ++i) regex += "a";
    std::string text(n, 'a');

    std::string name =
        "PathologicalBenchmark.pathological." + std::to_string(n);
    if (matches_filter(name, filters)) {
      RE2 re(regex);
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

  RE2 fanout(sec["unicodeFanout"]["pattern"].get<std::string>());
  RE2 nested(sec["nestedQuantifier"]["pattern"].get<std::string>());

  std::vector<int> codepoints =
      sec["unicodeFanout"]["codePoints"].get<std::vector<int>>();
  unsigned unicode_seed = sec["unicodeFanout"]["seed"];

  std::string nested_alphabet = sec["nestedQuantifier"]["alphabet"];
  unsigned nested_seed = sec["nestedQuantifier"]["seed"];

  for (int size : sizes) {
    std::string unicode_text =
        make_unicode_text(size, codepoints, unicode_seed);
    std::string ascii_text =
        make_random_text(size, nested_alphabet, nested_seed);

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

// Measure the heap allocation for compiling a single RE2 pattern, using
// mallinfo2() heap delta.  Also reports RE2's ProgramSize() (number of
// compiled bytecode instructions).
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

    // Measure heap delta around RE2 compilation using mallinfo2().
    struct mallinfo2 before = mallinfo2();
    auto re = std::make_unique<RE2>(pi.pattern);
    struct mallinfo2 after = mallinfo2();

    long heap_delta = static_cast<long>(after.uordblks) -
                      static_cast<long>(before.uordblks);
    print_memory_json(pi.name + ".heapBytes", heap_delta);

    // Report RE2's program size (number of bytecode instructions).
    if (re->ok()) {
      print_memory_json(pi.name + ".programSize",
                        re->ProgramSize(), "instructions");
      print_memory_json(pi.name + ".reverseProgramSize",
                        re->ReverseProgramSize(), "instructions");
    }
  }
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

int main(int argc, char* argv[]) {
  std::string data_path = "../../benchmark-data.json";
  std::vector<std::string> filters;

  for (int i = 1; i < argc; ++i) {
    std::string arg = argv[i];
    if (arg == "--data" && i + 1 < argc) {
      data_path = argv[++i];
    } else {
      filters.push_back(arg);
    }
  }

  json data = load_benchmark_data(data_path);

  run_regex_benchmarks(data, filters);
  run_application_benchmarks(data, filters);
  run_compile_benchmarks(data, filters);
  run_search_scaling_benchmarks(data, filters);
  run_capture_scaling_benchmarks(data, filters);
  run_http_benchmarks(data, filters);
  run_replace_benchmarks(data, filters);
  run_pathological_benchmarks(data, filters);
  run_fanout_benchmarks(data, filters);
  run_memory_benchmarks(data, filters);

  return 0;
}
