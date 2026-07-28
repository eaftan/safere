// Copyright (c) 2026 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.
//
// Rust regex benchmark harness. Runs the same patterns and inputs as the Java,
// C++, and Go benchmarks and outputs JSON lines for cross-language comparison.

use regex::{NoExpand, Regex};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
#[cfg(feature = "memory-tracking")]
use std::alloc::{GlobalAlloc, Layout, System};
use std::collections::HashMap;
use std::env;
use std::fs;
use std::hint::black_box;
use std::path::{Path, PathBuf};
use std::sync::OnceLock;
#[cfg(feature = "memory-tracking")]
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::time::{Duration, Instant};

#[cfg(feature = "memory-tracking")]
struct TrackingAllocator;

#[cfg(feature = "memory-tracking")]
static TRACK_ALLOCATIONS: AtomicBool = AtomicBool::new(false);
#[cfg(feature = "memory-tracking")]
static RETAINED_BYTES: AtomicUsize = AtomicUsize::new(0);
static PATTERN_PROFILE: OnceLock<HashMap<String, String>> = OnceLock::new();
static REPLACEMENT_PROFILE: OnceLock<HashMap<String, String>> = OnceLock::new();

// SAFETY: Every operation delegates to the system allocator with the original
// pointer and layout. The counters only observe successful allocations.
#[cfg(feature = "memory-tracking")]
unsafe impl GlobalAlloc for TrackingAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        let pointer = unsafe { System.alloc(layout) };
        if !pointer.is_null() && TRACK_ALLOCATIONS.load(Ordering::Relaxed) {
            RETAINED_BYTES.fetch_add(layout.size(), Ordering::Relaxed);
        }
        pointer
    }

    unsafe fn dealloc(&self, pointer: *mut u8, layout: Layout) {
        if TRACK_ALLOCATIONS.load(Ordering::Relaxed) {
            RETAINED_BYTES.fetch_sub(layout.size(), Ordering::Relaxed);
        }
        unsafe { System.dealloc(pointer, layout) };
    }

    unsafe fn realloc(&self, pointer: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        let new_pointer = unsafe { System.realloc(pointer, layout, new_size) };
        if !new_pointer.is_null() && TRACK_ALLOCATIONS.load(Ordering::Relaxed) {
            if new_size >= layout.size() {
                RETAINED_BYTES.fetch_add(new_size - layout.size(), Ordering::Relaxed);
            } else {
                RETAINED_BYTES.fetch_sub(layout.size() - new_size, Ordering::Relaxed);
            }
        }
        new_pointer
    }
}

#[cfg(feature = "memory-tracking")]
#[global_allocator]
static GLOBAL_ALLOCATOR: TrackingAllocator = TrackingAllocator;

struct Corpus {
    data: Value,
    input_directory: PathBuf,
    inputs: Value,
    pattern_profile: HashMap<String, String>,
    replacement_profile: HashMap<String, String>,
}

impl Corpus {
    fn load(manifest_path: &Path) -> Self {
        let manifest_text = fs::read_to_string(manifest_path).unwrap_or_else(|error| {
            panic!(
                "cannot open benchmark input manifest {}: {error}",
                manifest_path.display()
            )
        });
        let manifest: Value = serde_json::from_str(&manifest_text)
            .unwrap_or_else(|error| panic!("invalid benchmark input manifest: {error}"));
        assert_eq!(
            manifest["version"].as_u64(),
            Some(1),
            "unsupported benchmark input manifest version"
        );
        let data = manifest["benchmarkData"].clone();
        Self {
            pattern_profile: load_profile(&data, "patternProfiles", "rust-regex"),
            replacement_profile: load_profile(&data, "replacementProfiles", "rust-regex"),
            data,
            input_directory: manifest_path
                .parent()
                .unwrap_or(Path::new("."))
                .to_path_buf(),
            inputs: manifest["inputs"].clone(),
        }
    }

    fn input(&self, key: &str) -> String {
        let entry = self.inputs.get(key).unwrap_or_else(|| {
            panic!("unknown materialized benchmark input: {key}");
        });
        let file = required_string(entry, "file");
        let path = self.input_directory.join(file);
        let bytes = fs::read(&path).unwrap_or_else(|error| {
            panic!("cannot open materialized benchmark input {key}: {error}");
        });
        let expected_size = required_usize(entry, "utf8Bytes");
        assert_eq!(
            bytes.len(),
            expected_size,
            "materialized benchmark input {key} has wrong size"
        );
        let actual_hash = format!("{:x}", Sha256::digest(&bytes));
        assert_eq!(
            actual_hash,
            required_string(entry, "sha256"),
            "materialized benchmark input {key} has wrong SHA-256"
        );
        String::from_utf8(bytes).unwrap_or_else(|error| {
            panic!("materialized benchmark input {key} is not UTF-8: {error}")
        })
    }
}

fn load_profile(data: &Value, registry: &str, profile_id: &str) -> HashMap<String, String> {
    data.get(registry)
        .and_then(|profiles| profiles.get(profile_id))
        .map(|entries| {
            entries
                .as_array()
                .expect("benchmark pattern profile must be a list")
                .iter()
                .map(|entry| {
                    (
                        required_string(entry, "java"),
                        required_string(entry, "alternate"),
                    )
                })
                .collect()
        })
        .unwrap_or_default()
}

fn at_path<'a>(value: &'a Value, path: &str) -> &'a Value {
    path.split('.').fold(value, |current, part| {
        current
            .get(part)
            .unwrap_or_else(|| panic!("missing benchmark data path: {path}"))
    })
}

fn required_string(value: &Value, path: &str) -> String {
    at_path(value, path)
        .as_str()
        .unwrap_or_else(|| panic!("benchmark data path is not a string: {path}"))
        .to_owned()
}

fn required_usize(value: &Value, path: &str) -> usize {
    at_path(value, path)
        .as_u64()
        .unwrap_or_else(|| panic!("benchmark data path is not an integer: {path}"))
        .try_into()
        .expect("benchmark integer does not fit usize")
}

fn int_list(value: &Value, path: &str) -> Vec<usize> {
    at_path(value, path)
        .as_array()
        .unwrap_or_else(|| panic!("benchmark data path is not a list: {path}"))
        .iter()
        .map(|item| {
            item.as_u64()
                .expect("benchmark list item is not an integer")
                .try_into()
                .expect("benchmark integer does not fit usize")
        })
        .collect()
}

fn string_list(value: &Value, path: &str) -> Vec<String> {
    at_path(value, path)
        .as_array()
        .unwrap_or_else(|| panic!("benchmark data path is not a list: {path}"))
        .iter()
        .map(|item| {
            item.as_str()
                .expect("benchmark list item is not a string")
                .to_owned()
        })
        .collect()
}

fn selected_pattern(java_pattern: &str) -> String {
    PATTERN_PROFILE
        .get()
        .and_then(|profile| profile.get(java_pattern))
        .map_or_else(|| java_pattern.to_owned(), Clone::clone)
}

fn selected_replacement(java_replacement: &str) -> String {
    REPLACEMENT_PROFILE
        .get()
        .and_then(|profile| profile.get(java_replacement))
        .map_or_else(|| java_replacement.to_owned(), Clone::clone)
}

fn compile_rust_pattern(pattern: &str) -> Regex {
    Regex::new(pattern).unwrap_or_else(|error| panic!("cannot compile /{pattern}/: {error}"))
}

fn compile(pattern: &str) -> Regex {
    compile_rust_pattern(&selected_pattern(pattern))
}

fn compile_full(pattern: &str) -> Regex {
    compile_rust_pattern(&format!(r"\A(?:{})\z", selected_pattern(pattern)))
}

fn matches_filter(name: &str, filters: &[String]) -> bool {
    filters.is_empty() || filters.iter().any(|filter| name.contains(filter))
}

fn measure<F>(name: &str, mut operation: F, unit: &str, divisor: f64) -> Value
where
    F: FnMut(),
{
    for _ in 0..2 {
        let deadline = Instant::now() + Duration::from_secs(2);
        while Instant::now() < deadline {
            operation();
        }
    }

    let mut samples = Vec::with_capacity(10);
    for _ in 0..10 {
        let start = Instant::now();
        let deadline = start + Duration::from_secs(2);
        let mut operations = 0_u64;
        while Instant::now() < deadline {
            operation();
            operations += 1;
        }
        samples.push(start.elapsed().as_nanos() as f64 / operations as f64 / divisor);
    }

    let mean = samples.iter().sum::<f64>() / samples.len() as f64;
    let variance = samples
        .iter()
        .map(|sample| (sample - mean).powi(2))
        .sum::<f64>()
        / (samples.len() - 1) as f64;
    let error = 4.781 * variance.sqrt() / (samples.len() as f64).sqrt();
    json!({
        "engine": "rust_regex",
        "benchmark": name,
        "score": round_millis(mean),
        "error": round_millis(error),
        "unit": unit,
    })
}

fn round_millis(value: f64) -> f64 {
    (value * 1000.0).round() / 1000.0
}

fn run_ns<F: FnMut()>(name: &str, filters: &[String], operation: F) {
    if matches_filter(name, filters) {
        println!("{}", measure(name, operation, "ns/op", 1.0));
    }
}

fn run_us<F: FnMut()>(name: &str, filters: &[String], operation: F) {
    if matches_filter(name, filters) {
        println!("{}", measure(name, operation, "us/op", 1000.0));
    }
}

fn run_regex_benchmarks(data: &Value, filters: &[String]) {
    let section = &data["regex"];
    let hello = compile_full(&required_string(section, "literalMatch.pattern"));
    let alpha = compile_full(&required_string(section, "charClassMatch.pattern"));
    let alternate = compile(&required_string(section, "alternationFind.pattern"));
    let date = compile_full(&required_string(section, "captureGroups.pattern"));
    let find_ing = compile(&required_string(section, "findInText.pattern"));
    let email = compile(&required_string(section, "emailFind.pattern"));
    let hello_text = required_string(section, "literalMatch.text");
    let alpha_text = required_string(section, "charClassMatch.text");
    let alternate_text = required_string(section, "alternationFind.text");
    let date_text = required_string(section, "captureGroups.text");
    let prose = required_string(section, "findInText.text");
    let email_text = required_string(section, "emailFind.text");

    run_ns("RegexBenchmark.literalMatch", filters, || {
        black_box(hello.is_match(black_box(&hello_text)));
    });
    run_ns("RegexBenchmark.charClassMatch", filters, || {
        black_box(alpha.is_match(black_box(&alpha_text)));
    });
    run_ns("RegexBenchmark.alternationFind", filters, || {
        black_box(alternate.find_iter(black_box(&alternate_text)).count());
    });
    run_ns("RegexBenchmark.captureGroups", filters, || {
        black_box(captured_text(&date, black_box(&date_text), &[1, 2, 3]));
    });
    run_ns("RegexBenchmark.findInText", filters, || {
        black_box(find_ing.find_iter(black_box(&prose)).count());
    });
    run_ns("RegexBenchmark.emailFind", filters, || {
        black_box(email.is_match(black_box(&email_text)));
    });
}

struct ApplicationCase {
    name: String,
    operation: String,
    texts: Vec<String>,
    text: String,
    groups: Vec<usize>,
    replacement: String,
    expected: Value,
    regex: Regex,
    full_regex: Option<Regex>,
}

fn group_length_sum(captures: &regex::Captures<'_>, groups: &[usize]) -> usize {
    groups
        .iter()
        .filter_map(|group| captures.get(*group))
        .map(|capture| capture.end() - capture.start())
        .sum()
}

fn captured_text(regex: &Regex, text: &str, groups: &[usize]) -> String {
    let Some(captures) = regex.captures(text) else {
        return String::new();
    };
    let mut result = String::new();
    for group in groups {
        if let Some(capture) = captures.get(*group) {
            result.push_str(capture.as_str());
        }
    }
    result
}

fn application_int(case: &ApplicationCase) -> usize {
    match case.operation.as_str() {
        "matchesCorpus" => case
            .texts
            .iter()
            .filter(|text| case.full_regex.as_ref().unwrap().is_match(text))
            .count(),
        "matchesGroupLengthSum" => case
            .texts
            .iter()
            .filter_map(|text| case.full_regex.as_ref().unwrap().captures(text))
            .map(|captures| group_length_sum(&captures, &case.groups))
            .sum(),
        "findAllCount" => case.regex.find_iter(&case.text).count(),
        "findAllLengthSum" => case
            .regex
            .find_iter(&case.text)
            .map(|found| found.end() - found.start())
            .sum(),
        "findAllGroupLengthSum" => case
            .regex
            .captures_iter(&case.text)
            .map(|captures| group_length_sum(&captures, &case.groups))
            .sum(),
        operation => panic!("string op used as integer op: {operation}"),
    }
}

fn application_string(case: &ApplicationCase) -> String {
    case.regex
        .replace_all(&case.text, case.replacement.as_str())
        .into_owned()
}

fn run_application_benchmarks(data: &Value, filters: &[String]) {
    let raw_cases = data["application"]
        .as_array()
        .expect("application benchmark data must be a list");
    let cases: Vec<ApplicationCase> = raw_cases
        .iter()
        .map(|item| {
            let pattern = required_string(item, "pattern");
            let operation = required_string(item, "op");
            let replacement = item
                .get("replacement")
                .map(|_| selected_replacement(&required_string(item, "replacement")))
                .unwrap_or_default();
            ApplicationCase {
                name: required_string(item, "name"),
                texts: item
                    .get("texts")
                    .map(|_| string_list(item, "texts"))
                    .unwrap_or_default(),
                text: item
                    .get("text")
                    .map(|_| required_string(item, "text"))
                    .unwrap_or_default(),
                groups: item
                    .get("groups")
                    .map(|_| int_list(item, "groups"))
                    .unwrap_or_default(),
                replacement,
                expected: item["expected"].clone(),
                regex: compile(&pattern),
                full_regex: operation
                    .starts_with("matches")
                    .then(|| compile_full(&pattern)),
                operation,
            }
        })
        .collect();

    for case in &cases {
        if case.operation.starts_with("findAll") {
            assert!(
                case.regex.find("").is_none(),
                "empty-width find-all application pattern: {}",
                case.name
            );
        }
        if case.operation == "replaceAll" {
            assert_eq!(
                application_string(case),
                case.expected.as_str().unwrap(),
                "{} expected result mismatch",
                case.name
            );
        } else {
            assert_eq!(
                application_int(case) as u64,
                case.expected.as_u64().unwrap(),
                "{} expected result mismatch",
                case.name
            );
        }
    }

    for case in &cases {
        let name = format!("ApplicationBenchmark.{}", case.name);
        run_ns(&name, filters, || {
            if case.operation == "replaceAll" {
                black_box(application_string(black_box(case)));
            } else {
                black_box(application_int(black_box(case)));
            }
        });
    }
}

fn run_real_world_benchmarks(corpus: &Corpus, filters: &[String]) {
    let section = &corpus.data["realWorldRegex"];
    let sizes = int_list(section, "textSizes");
    for item in section["cases"]
        .as_array()
        .expect("realWorldRegex cases must be a list")
    {
        let case_name = required_string(item, "name");
        let operation = required_string(item, "op");
        let pattern = required_string(item, "pattern");
        let regex = compile(&pattern);
        let full_regex = (operation == "matches").then(|| compile_full(&pattern));
        for matches in [true, false] {
            let match_label = if matches { "match" } else { "noMatch" };
            for size in &sizes {
                let text =
                    corpus.input(&format!("realWorldRegex.{case_name}.{match_label}.{size}"));
                let name = format!(
                    "RealWorldRegexBenchmark.runBenchmark.{case_name}.{match_label}.{size}"
                );
                match operation.as_str() {
                    "find" => run_ns(&name, filters, || {
                        black_box(regex.is_match(black_box(&text)));
                    }),
                    "matches" => run_ns(&name, filters, || {
                        black_box(full_regex.as_ref().unwrap().is_match(black_box(&text)));
                    }),
                    "replaceAllEmpty" => run_ns(&name, filters, || {
                        black_box(regex.replace_all(black_box(&text), NoExpand("")));
                    }),
                    "replaceAllGroup1" => run_ns(&name, filters, || {
                        black_box(regex.replace_all(black_box(&text), "$1"));
                    }),
                    "replaceAllLiteral" => run_ns(&name, filters, || {
                        black_box(regex.replace_all(black_box(&text), NoExpand("xyz")));
                    }),
                    _ => panic!("invalid realWorldRegex op: {operation}"),
                }
            }
        }
    }
}

fn run_compile_benchmarks(data: &Value, filters: &[String]) {
    let section = &data["compile"];
    for (name, path) in [
        ("CompileBenchmark.compileSimple", "simple.pattern"),
        ("CompileBenchmark.compileMedium", "medium.pattern"),
        ("CompileBenchmark.compileComplex", "complex.pattern"),
        ("CompileBenchmark.compileAlternation", "alternation.pattern"),
    ] {
        let pattern = selected_pattern(&required_string(section, path));
        run_us(name, filters, || {
            black_box(compile_rust_pattern(black_box(&pattern)));
        });
    }
}

fn run_search_scaling_benchmarks(corpus: &Corpus, filters: &[String]) {
    let section = &corpus.data["searchScaling"];
    let easy = compile(&required_string(section, "patterns.easy"));
    let medium = compile(&required_string(section, "patterns.medium"));
    let hard = compile(&required_string(section, "patterns.hard"));
    let find_ing = compile(&required_string(section, "findIngPattern"));
    for size in int_list(section, "textSizes") {
        let random = corpus.input(&format!("searchScaling.random.{size}"));
        let success = corpus.input(&format!("searchScaling.success.{size}"));
        let prose = corpus.input(&format!("searchScaling.prose.{size}"));
        run_us(
            &format!("SearchScalingBenchmark.searchEasyFail.{size}"),
            filters,
            || {
                black_box(easy.is_match(black_box(&random)));
            },
        );
        run_us(
            &format!("SearchScalingBenchmark.searchEasySuccess.{size}"),
            filters,
            || {
                black_box(easy.is_match(black_box(&success)));
            },
        );
        run_us(
            &format!("SearchScalingBenchmark.searchMediumFail.{size}"),
            filters,
            || {
                black_box(medium.is_match(black_box(&random)));
            },
        );
        run_us(
            &format!("SearchScalingBenchmark.searchHardFail.{size}"),
            filters,
            || {
                black_box(hard.is_match(black_box(&random)));
            },
        );
        run_us(
            &format!("SearchScalingBenchmark.findIngScaled.{size}"),
            filters,
            || {
                black_box(find_ing.find_iter(black_box(&prose)).count());
            },
        );
    }
}

fn run_issue_481_benchmarks(corpus: &Corpus, filters: &[String]) {
    let section = &corpus.data["issue481Scaling"];
    let split_words = compile(&required_string(section, "splitW.pattern"));
    let block = compile(&required_string(section, "block.pattern"));
    let tag = compile(&required_string(section, "tag.pattern"));
    let scheme = compile(&required_string(section, "scheme.pattern"));
    for size in int_list(section, "textSizes") {
        let split_text = corpus.input(&format!("issue481Scaling.splitW.{size}"));
        let block_text = corpus.input(&format!("issue481Scaling.block.{size}"));
        let block_negative = corpus.input(&format!("issue481Scaling.blockNegative.{size}"));
        let tag_text = corpus.input(&format!("issue481Scaling.tag.{size}"));
        let tag_negative = corpus.input(&format!("issue481Scaling.tagNegative.{size}"));
        let scheme_text = corpus.input(&format!("issue481Scaling.scheme.{size}"));
        let scheme_negative = corpus.input(&format!("issue481Scaling.schemeNegative.{size}"));
        run_us(
            &format!("Issue481ScalingBenchmark.splitWords.{size}"),
            filters,
            || {
                let mut parts: Vec<&str> = split_words.split(&split_text).collect();
                while parts.last() == Some(&"") {
                    parts.pop();
                }
                black_box(parts.len() + parts.iter().map(|part| part.len()).sum::<usize>());
            },
        );
        for (name, regex, text) in [
            ("blockFind", &block, &block_text),
            ("blockFindNegative", &block, &block_negative),
            ("tagFind", &tag, &tag_text),
            ("tagFindNegative", &tag, &tag_negative),
            ("schemeFindNegative", &scheme, &scheme_negative),
        ] {
            run_us(
                &format!("Issue481ScalingBenchmark.{name}.{size}"),
                filters,
                || {
                    black_box(regex.is_match(black_box(text)));
                },
            );
        }
        run_us(
            &format!("Issue481ScalingBenchmark.schemeExtract.{size}"),
            filters,
            || {
                let sum: usize = scheme
                    .captures_iter(&scheme_text)
                    .map(|captures| {
                        [1, 2]
                            .iter()
                            .filter_map(|group| captures.get(*group))
                            .map(|capture| capture.end() - capture.start())
                            .sum::<usize>()
                    })
                    .sum();
                black_box(sum);
            },
        );
    }
}

fn run_capture_benchmarks(data: &Value, filters: &[String]) {
    let section = &data["captureScaling"];
    for (name, key, captures) in [
        ("CaptureScalingBenchmark.capture0", "capture0", false),
        ("CaptureScalingBenchmark.capture1", "capture1", true),
        ("CaptureScalingBenchmark.capture3", "capture3", true),
        ("CaptureScalingBenchmark.capture10", "capture10", true),
    ] {
        let pattern = required_string(section, &format!("{key}.pattern"));
        let regex = compile_full(&pattern);
        let text = required_string(section, &format!("{key}.text"));
        let groups: Vec<usize> = (1..regex.captures_len()).collect();
        run_ns(name, filters, || {
            if captures {
                black_box(captured_text(&regex, black_box(&text), &groups));
            } else {
                black_box(regex.is_match(black_box(&text)));
            }
        });
    }
}

fn run_http_benchmarks(data: &Value, filters: &[String]) {
    let section = &data["http"];
    let regex = compile(&required_string(section, "pattern"));
    let full = required_string(section, "fullRequest");
    let small = required_string(section, "smallRequest");
    run_ns("HttpBenchmark.httpFull", filters, || {
        black_box(
            regex
                .captures(black_box(&full))
                .and_then(|captures| captures.get(1))
                .is_some(),
        );
    });
    run_ns("HttpBenchmark.httpSmall", filters, || {
        black_box(
            regex
                .captures(black_box(&small))
                .and_then(|captures| captures.get(1))
                .is_some(),
        );
    });
    run_ns("HttpBenchmark.httpExtract", filters, || {
        black_box(
            regex
                .captures(black_box(&full))
                .and_then(|captures| captures.get(1))
                .map(|capture| capture.as_str().to_owned()),
        );
    });
}

fn run_replace_benchmarks(data: &Value, filters: &[String]) {
    let section = data["replace"]
        .as_object()
        .expect("replace benchmark data must be an object");
    for (key, entry) in section {
        let name = format!("ReplaceBenchmark.{key}");
        if !matches_filter(&name, filters) {
            continue;
        }
        let regex = compile(&required_string(entry, "pattern"));
        let text = required_string(entry, "text");
        let replacement = selected_replacement(&required_string(entry, "replacement"));
        match required_string(entry, "op").as_str() {
            "replaceFirst" => run_ns(&name, filters, || {
                black_box(regex.replace(black_box(&text), replacement.as_str()));
            }),
            "replaceAll" => run_ns(&name, filters, || {
                black_box(regex.replace_all(black_box(&text), replacement.as_str()));
            }),
            operation => panic!("invalid replacement operation: {operation}"),
        }
    }
}

fn run_pathological_benchmarks(corpus: &Corpus, filters: &[String]) {
    for n in int_list(&corpus.data, "pathological.nValues") {
        let regex = compile_full(&corpus.input(&format!("pathological.pattern.{n}")));
        let text = corpus.input(&format!("pathological.text.{n}"));
        let name = format!("PathologicalBenchmark.pathological.{n}");
        run_us(&name, filters, || {
            black_box(regex.is_match(black_box(&text)));
        });
    }
}

fn run_fanout_benchmarks(corpus: &Corpus, filters: &[String]) {
    let section = &corpus.data["fanout"];
    let fanout = compile(&required_string(section, "unicodeFanout.pattern"));
    let nested = compile(&required_string(section, "nestedQuantifier.pattern"));
    for size in int_list(section, "textSizes") {
        let unicode = corpus.input(&format!("fanout.unicode.{size}"));
        let ascii = corpus.input(&format!("fanout.ascii.{size}"));
        run_us(
            &format!("FanoutBenchmark.fanoutUnicode.{size}"),
            filters,
            || {
                black_box(fanout.is_match(black_box(&unicode)));
            },
        );
        run_us(
            &format!("FanoutBenchmark.nestedQuantifier.{size}"),
            filters,
            || {
                black_box(nested.is_match(black_box(&ascii)));
            },
        );
    }
}

#[cfg(feature = "memory-tracking")]
fn compiled_retained_bytes(pattern: &str) -> usize {
    let pattern = selected_pattern(pattern);
    black_box(compile_rust_pattern(&pattern));
    RETAINED_BYTES.store(0, Ordering::Relaxed);
    TRACK_ALLOCATIONS.store(true, Ordering::SeqCst);
    let regex = compile_rust_pattern(&pattern);
    TRACK_ALLOCATIONS.store(false, Ordering::SeqCst);
    let bytes = RETAINED_BYTES.load(Ordering::Relaxed);
    black_box(regex);
    bytes
}

#[cfg(feature = "memory-tracking")]
fn run_memory_benchmarks(data: &Value, filters: &[String]) {
    let compile_section = &data["compile"];
    let regex_section = &data["regex"];
    for (name, pattern) in [
        (
            "MemoryBenchmark.compileSimple",
            required_string(compile_section, "simple.pattern"),
        ),
        (
            "MemoryBenchmark.compileMedium",
            required_string(compile_section, "medium.pattern"),
        ),
        (
            "MemoryBenchmark.compileComplex",
            required_string(compile_section, "complex.pattern"),
        ),
        (
            "MemoryBenchmark.compileAlternation",
            required_string(compile_section, "alternation.pattern"),
        ),
        (
            "MemoryBenchmark.literalMatch",
            required_string(regex_section, "literalMatch.pattern"),
        ),
        (
            "MemoryBenchmark.charClassMatch",
            required_string(regex_section, "charClassMatch.pattern"),
        ),
        (
            "MemoryBenchmark.alternationFind",
            required_string(regex_section, "alternationFind.pattern"),
        ),
        (
            "MemoryBenchmark.captureGroups",
            required_string(regex_section, "captureGroups.pattern"),
        ),
        (
            "MemoryBenchmark.findInText",
            required_string(regex_section, "findInText.pattern"),
        ),
        (
            "MemoryBenchmark.emailFind",
            required_string(regex_section, "emailFind.pattern"),
        ),
    ] {
        let benchmark = format!("{name}.heapBytes");
        if matches_filter(name, filters) || matches_filter(&benchmark, filters) {
            println!(
                "{}",
                json!({
                    "engine": "rust_regex",
                    "benchmark": benchmark,
                    "score": compiled_retained_bytes(&pattern),
                    "error": 0,
                    "unit": "bytes",
                })
            );
        }
    }
}

fn main() {
    let mut manifest_path = PathBuf::from("../../target/benchmark-corpus/manifest.json");
    let mut filters = Vec::new();
    let mut arguments = env::args().skip(1);
    while let Some(argument) = arguments.next() {
        if argument == "--manifest" {
            manifest_path = PathBuf::from(
                arguments
                    .next()
                    .expect("--manifest requires a following path"),
            );
        } else {
            filters.push(argument);
        }
    }

    let corpus = Corpus::load(&manifest_path);
    PATTERN_PROFILE
        .set(corpus.pattern_profile.clone())
        .expect("Rust pattern profile must only be initialized once");
    REPLACEMENT_PROFILE
        .set(corpus.replacement_profile.clone())
        .expect("Rust replacement profile must only be initialized once");
    if !cfg!(feature = "memory-tracking") {
        run_regex_benchmarks(&corpus.data, &filters);
        run_application_benchmarks(&corpus.data, &filters);
        run_real_world_benchmarks(&corpus, &filters);
        run_compile_benchmarks(&corpus.data, &filters);
        run_search_scaling_benchmarks(&corpus, &filters);
        run_issue_481_benchmarks(&corpus, &filters);
        run_capture_benchmarks(&corpus.data, &filters);
        run_http_benchmarks(&corpus.data, &filters);
        run_replace_benchmarks(&corpus.data, &filters);
        run_pathological_benchmarks(&corpus, &filters);
        run_fanout_benchmarks(&corpus, &filters);
    }
    #[cfg(feature = "memory-tracking")]
    run_memory_benchmarks(&corpus.data, &filters);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn explicit_profile_alternate_is_selected_with_java_fallback() {
        let data = json!({
            "patternProfiles": {
                "rust-regex": [{
                    "java": "same",
                    "alternate": "pattern"
                }]
            },
            "replacementProfiles": {
                "rust-regex": [{
                    "java": "same",
                    "alternate": "replacement"
                }]
            }
        });
        let patterns = load_profile(&data, "patternProfiles", "rust-regex");
        let replacements = load_profile(&data, "replacementProfiles", "rust-regex");

        assert_eq!(patterns.get("same").unwrap(), "pattern");
        assert_eq!(replacements.get("same").unwrap(), "replacement");
        assert!(!patterns.contains_key("unchanged"));
        assert!(!replacements.contains_key("unchanged"));
    }

    #[test]
    fn full_match_is_not_weakened_by_multiline_mode() {
        let regex = compile_full("(?m)abc");

        assert!(regex.is_match("abc"));
        assert!(!regex.is_match("abc\nother"));
    }
}
