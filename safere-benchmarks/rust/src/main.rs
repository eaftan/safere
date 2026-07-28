// Copyright (c) 2026 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.
//
// Rust regex benchmark harness. The materialized execution plan is the sole
// source of workload selection, engine syntax, arguments, and exclusions.

use regex::Regex;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
#[cfg(feature = "memory-tracking")]
use std::alloc::{GlobalAlloc, Layout, System};
use std::env;
use std::fs;
use std::hint::black_box;
use std::path::{Path, PathBuf};
#[cfg(feature = "memory-tracking")]
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::time::{Duration, Instant};

const ENGINE_ID: &str = "rust_regex";

#[cfg(feature = "memory-tracking")]
struct TrackingAllocator;
#[cfg(feature = "memory-tracking")]
static TRACK_ALLOCATIONS: AtomicBool = AtomicBool::new(false);
#[cfg(feature = "memory-tracking")]
static RETAINED_BYTES: AtomicUsize = AtomicUsize::new(0);

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

    unsafe fn realloc(&self, pointer: *mut u8, layout: Layout, size: usize) -> *mut u8 {
        let result = unsafe { System.realloc(pointer, layout, size) };
        if !result.is_null() && TRACK_ALLOCATIONS.load(Ordering::Relaxed) {
            if size >= layout.size() {
                RETAINED_BYTES.fetch_add(size - layout.size(), Ordering::Relaxed);
            } else {
                RETAINED_BYTES.fetch_sub(layout.size() - size, Ordering::Relaxed);
            }
        }
        result
    }
}

#[cfg(feature = "memory-tracking")]
#[global_allocator]
static GLOBAL_ALLOCATOR: TrackingAllocator = TrackingAllocator;

struct Corpus {
    manifest: Value,
    input_directory: PathBuf,
}

impl Corpus {
    fn load(path: &Path) -> Self {
        let text = fs::read_to_string(path)
            .unwrap_or_else(|error| panic!("cannot open manifest {}: {error}", path.display()));
        let manifest: Value = serde_json::from_str(&text)
            .unwrap_or_else(|error| panic!("invalid benchmark manifest: {error}"));
        assert_eq!(manifest["version"].as_u64(), Some(1));
        assert_eq!(manifest["executionPlan"]["version"].as_u64(), Some(1));
        Self {
            manifest,
            input_directory: path.parent().unwrap_or(Path::new(".")).to_path_buf(),
        }
    }

    fn entries(&self) -> &[Value] {
        self.manifest["executionPlan"]["entries"]
            .as_array()
            .expect("executionPlan.entries must be an array")
    }

    fn input(&self, key: &str) -> String {
        let entry = &self.manifest["inputs"][key];
        assert!(
            !entry.is_null(),
            "unknown materialized benchmark input: {key}"
        );
        let path = self.input_directory.join(required_string(entry, "file"));
        let bytes = fs::read(path)
            .unwrap_or_else(|error| panic!("cannot read materialized input {key}: {error}"));
        assert_eq!(bytes.len(), required_usize(entry, "utf8Bytes"));
        assert_eq!(
            format!("{:x}", Sha256::digest(&bytes)),
            required_string(entry, "sha256")
        );
        String::from_utf8(bytes).expect("materialized input must be UTF-8")
    }
}

fn at_path<'a>(value: &'a Value, path: &str) -> &'a Value {
    path.split('.').fold(value, |current, part| {
        current
            .get(part)
            .unwrap_or_else(|| panic!("missing execution-plan path: {path}"))
    })
}

fn required_string(value: &Value, path: &str) -> String {
    at_path(value, path)
        .as_str()
        .unwrap_or_else(|| panic!("execution-plan path is not a string: {path}"))
        .to_owned()
}

fn optional_string(value: &Value, path: &str) -> String {
    value
        .get(path)
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_owned()
}

fn required_usize(value: &Value, path: &str) -> usize {
    at_path(value, path)
        .as_u64()
        .unwrap_or_else(|| panic!("execution-plan path is not an integer: {path}"))
        .try_into()
        .expect("execution-plan integer does not fit usize")
}

fn optional_usize(value: &Value, key: &str) -> usize {
    value
        .get(key)
        .and_then(Value::as_u64)
        .unwrap_or_default()
        .try_into()
        .expect("execution-plan integer does not fit usize")
}

fn string_list(value: &Value, path: &str) -> Vec<String> {
    at_path(value, path)
        .as_array()
        .unwrap_or_else(|| panic!("execution-plan path is not an array: {path}"))
        .iter()
        .map(|item| {
            item.as_str()
                .expect("array item must be a string")
                .to_owned()
        })
        .collect()
}

fn int_list(value: &Value, key: &str) -> Vec<usize> {
    value
        .get(key)
        .and_then(Value::as_array)
        .map(|items| {
            items
                .iter()
                .map(|item| {
                    item.as_u64()
                        .expect("array item must be an integer")
                        .try_into()
                        .expect("integer does not fit usize")
                })
                .collect()
        })
        .unwrap_or_default()
}

fn compile(pattern: &str) -> Regex {
    Regex::new(pattern).unwrap_or_else(|error| panic!("cannot compile /{pattern}/: {error}"))
}

fn compile_full(pattern: &str) -> Regex {
    compile(&format!(r"\A(?:{pattern})\z"))
}

fn group_length_sum(captures: &regex::Captures<'_>, groups: &[usize]) -> usize {
    groups
        .iter()
        .filter_map(|group| captures.get(*group))
        .map(|capture| capture.len())
        .sum()
}

fn captured_text(regex: &Regex, text: &str, groups: &[usize]) -> String {
    let Some(captures) = regex.captures(text) else {
        return String::new();
    };
    groups
        .iter()
        .filter_map(|group| captures.get(*group))
        .map(|capture| capture.as_str())
        .collect()
}

struct Prepared {
    entry: Value,
    operation: String,
    patterns: Vec<String>,
    regexes: Vec<Regex>,
    full_regex: Option<Regex>,
    inputs: Vec<String>,
    groups: Vec<usize>,
    group: usize,
    replacement: String,
    limit: usize,
}

fn prepare(entry: &Value, corpus: &Corpus) -> Prepared {
    let patterns = string_list(entry, "patterns");
    let arguments = &entry["arguments"];
    Prepared {
        operation: required_string(entry, "operation"),
        regexes: patterns.iter().map(|pattern| compile(pattern)).collect(),
        full_regex: patterns.first().map(|pattern| compile_full(pattern)),
        inputs: string_list(entry, "inputs")
            .iter()
            .map(|key| corpus.input(key))
            .collect(),
        groups: int_list(arguments, "groups"),
        group: optional_usize(arguments, "group"),
        replacement: optional_string(arguments, "replacement"),
        limit: optional_usize(arguments, "limit"),
        entry: entry.clone(),
        patterns,
    }
}

fn execute(prepared: &Prepared) -> Value {
    let patterns = &prepared.patterns;
    let inputs = &prepared.inputs;
    let regexes = &prepared.regexes;
    let regex = regexes.first();
    let text = inputs.first().map(String::as_str).unwrap_or_default();

    match prepared.operation.as_str() {
        "compile" => {
            black_box(compile(&patterns[0]));
            json!(true)
        }
        "matches" => json!(prepared.full_regex.as_ref().unwrap().is_match(text)),
        "find" => json!(regex.unwrap().is_match(text)),
        "findAllCount" => json!(regex.unwrap().find_iter(text).count()),
        "matchesCorpus" => {
            let full = prepared.full_regex.as_ref().unwrap();
            json!(inputs.iter().filter(|input| full.is_match(input)).count())
        }
        "matchesGroupLengthSum" => {
            let full = prepared.full_regex.as_ref().unwrap();
            json!(
                inputs
                    .iter()
                    .filter_map(|input| full.captures(input))
                    .map(|captures| group_length_sum(&captures, &prepared.groups))
                    .sum::<usize>()
            )
        }
        "findAllLengthSum" => {
            json!(
                regex
                    .unwrap()
                    .find_iter(text)
                    .map(|found| found.len())
                    .sum::<usize>()
            )
        }
        "findAllGroupLengthSum" => {
            json!(
                regex
                    .unwrap()
                    .captures_iter(text)
                    .map(|captures| group_length_sum(&captures, &prepared.groups))
                    .sum::<usize>()
            )
        }
        "captureGroups" => {
            json!(captured_text(
                prepared.full_regex.as_ref().unwrap(),
                text,
                &prepared.groups
            ))
        }
        "replaceFirst" => {
            json!(
                regex
                    .unwrap()
                    .replacen(text, 1, prepared.replacement.as_str())
            )
        }
        "replaceAll" => {
            json!(
                regex
                    .unwrap()
                    .replace_all(text, prepared.replacement.as_str())
            )
        }
        "replaceAllLengthSum" => {
            json!(
                regexes
                    .iter()
                    .map(|item| { item.replace_all(text, prepared.replacement.as_str()).len() })
                    .sum::<usize>()
            )
        }
        "splitLengthSum" => {
            let mut parts: Vec<&str> = if prepared.limit > 0 {
                regex.unwrap().splitn(text, prepared.limit).collect()
            } else {
                regex.unwrap().split(text).collect()
            };
            if prepared.limit == 0 {
                while parts.last() == Some(&"") {
                    parts.pop();
                }
            }
            json!(parts.len() + parts.iter().map(|part| part.len()).sum::<usize>())
        }
        "findGroupPresent" => {
            json!(
                regex
                    .unwrap()
                    .captures(text)
                    .and_then(|captures| captures.get(prepared.group))
                    .is_some()
            )
        }
        "findGroup" => {
            json!(
                regex
                    .unwrap()
                    .captures(text)
                    .and_then(|captures| {
                        captures
                            .get(prepared.group)
                            .map(|found| found.as_str().to_owned())
                    })
                    .unwrap_or_default()
            )
        }
        other => panic!("unsupported runnable operation: {other}"),
    }
}

fn validate(entry: &Value, actual: &Value) {
    if let Some(expected) = entry.get("expected") {
        assert_eq!(
            actual,
            &expected["value"],
            "{} result mismatch",
            required_string(entry, "workloadId")
        );
    }
}

fn measure(prepared: &Prepared, smoke: bool) -> Value {
    let entry = &prepared.entry;
    let (warmups, iterations, duration) = if smoke {
        (0, 1, Duration::ZERO)
    } else {
        (2, 10, Duration::from_secs(2))
    };
    for _ in 0..warmups {
        let deadline = Instant::now() + duration;
        while Instant::now() < deadline {
            black_box(execute(prepared));
        }
    }
    let mut samples = Vec::with_capacity(iterations);
    for _ in 0..iterations {
        let start = Instant::now();
        let deadline = start + duration;
        let mut operations = 0_u64;
        while operations == 0 || Instant::now() < deadline {
            black_box(execute(prepared));
            operations += 1;
        }
        samples.push(start.elapsed().as_nanos() as f64 / operations as f64);
    }
    let unit_name = required_string(entry, "measurement.timingUnit");
    let (unit, divisor) = match unit_name.as_str() {
        "nanoseconds" => ("ns/op", 1.0),
        "microseconds" => ("us/op", 1_000.0),
        "milliseconds" => ("ms/op", 1_000_000.0),
        other => panic!("unsupported timing unit: {other}"),
    };
    let mean = samples.iter().sum::<f64>() / samples.len() as f64 / divisor;
    let error = if samples.len() == 1 {
        0.0
    } else {
        let variance = samples
            .iter()
            .map(|sample| (sample / divisor - mean).powi(2))
            .sum::<f64>()
            / (samples.len() - 1) as f64;
        4.781 * variance.sqrt() / (samples.len() as f64).sqrt()
    };
    json!({
        "engine": ENGINE_ID,
        "benchmark": required_string(entry, "workloadId"),
        "score": round(mean),
        "error": round(error),
        "unit": unit,
    })
}

fn round(value: f64) -> f64 {
    (value * 1000.0).round() / 1000.0
}

#[cfg(feature = "memory-tracking")]
fn compiled_retained_bytes(pattern: &str) -> usize {
    let _ = compile(pattern);
    RETAINED_BYTES.store(0, Ordering::SeqCst);
    TRACK_ALLOCATIONS.store(true, Ordering::SeqCst);
    let regex = compile(pattern);
    TRACK_ALLOCATIONS.store(false, Ordering::SeqCst);
    let bytes = RETAINED_BYTES.load(Ordering::SeqCst);
    black_box(&regex);
    bytes
}

fn matches_filter(name: &str, filters: &[String]) -> bool {
    filters.is_empty() || filters.iter().any(|filter| name.contains(filter))
}

fn matches_build_mode(entry: &Value, memory_tracking: bool) -> bool {
    let memory = entry["measurement"]["mode"].as_str() == Some("retainedMemory");
    memory == memory_tracking
}

fn main() {
    let mut manifest_path = PathBuf::from("../../target/benchmark-corpus/manifest.json");
    let mut filters = Vec::new();
    let mut smoke = false;
    let mut list = false;
    let mut list_exclusions = false;
    let mut arguments = env::args().skip(1);
    while let Some(argument) = arguments.next() {
        match argument.as_str() {
            "--manifest" => {
                manifest_path = PathBuf::from(arguments.next().expect("--manifest needs a path"));
            }
            "--smoke" => smoke = true,
            "--list" => list = true,
            "--list-exclusions" => list_exclusions = true,
            _ => filters.push(argument),
        }
    }

    let corpus = Corpus::load(&manifest_path);
    for entry in corpus.entries() {
        if entry["engineId"].as_str() != Some(ENGINE_ID) {
            continue;
        }
        let id = required_string(entry, "workloadId");
        if !matches_filter(&id, &filters) {
            continue;
        }
        if !matches_build_mode(entry, cfg!(feature = "memory-tracking")) {
            continue;
        }
        if entry["status"].as_str() == Some("excluded") {
            if list_exclusions {
                println!(
                    "{}",
                    json!({
                        "engine": ENGINE_ID,
                        "benchmark": id,
                        "reason": required_string(entry, "exclusion.reason"),
                    })
                );
            }
            continue;
        }
        assert_eq!(entry["status"].as_str(), Some("runnable"));
        if list {
            println!("{id}");
        } else if !list_exclusions {
            #[cfg(feature = "memory-tracking")]
            if matches_build_mode(entry, true) {
                println!(
                    "{}",
                    json!({
                        "engine": ENGINE_ID,
                        "benchmark": id,
                        "score": compiled_retained_bytes(
                            &string_list(entry, "patterns")[0]
                        ),
                        "error": 0,
                        "unit": "bytes",
                    })
                );
                continue;
            }
            let prepared = prepare(entry, &corpus);
            let actual = execute(&prepared);
            validate(entry, &actual);
            println!("{}", measure(&prepared, smoke));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn full_match_is_not_weakened_by_multiline_mode() {
        let regex = compile_full("(?m)abc");
        assert!(regex.is_match("abc"));
        assert!(!regex.is_match("abc\nother"));
    }

    #[test]
    fn entries_are_partitioned_between_timing_and_memory_builds() {
        let timing = json!({"measurement": {"mode": "averageTime"}});
        let memory = json!({"measurement": {"mode": "retainedMemory"}});

        assert!(matches_build_mode(&timing, false));
        assert!(!matches_build_mode(&timing, true));
        assert!(!matches_build_mode(&memory, false));
        assert!(matches_build_mode(&memory, true));
    }
}
