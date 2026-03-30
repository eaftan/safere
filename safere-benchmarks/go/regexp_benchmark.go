// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.
//
// Go regexp benchmark harness. Runs the same patterns and inputs as the Java
// JMH benchmarks and outputs JSON lines for cross-language comparison.
// Patterns and inputs are loaded from a shared JSON data file.
//
// Build:
//
//	cd safere-benchmarks/go && go build -o regexp_benchmark .
//
// Run:
//
//	./regexp_benchmark [--data path/to/benchmark-data.json] [filter...]
//
// Each filter is a substring match against benchmark names. If no filters
// are given, all benchmarks are run.
package main

import (
	"encoding/json"
	"fmt"
	"math"
	"math/rand"
	"os"
	"regexp"
	"runtime"
	"strings"
	"time"
	"unicode/utf8"
)

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

type benchResult struct {
	Engine    string  `json:"engine"`
	Benchmark string  `json:"benchmark"`
	Score     float64 `json:"score"`
	Error     float64 `json:"error"`
	Unit      string  `json:"unit"`
}

// measure runs fn in a warmup phase then a measurement phase, returning
// the mean time per operation and 99.9% CI half-width.
func measure(name string, fn func(), warmupIters int, warmupTime time.Duration,
	measureIters int, measureTime time.Duration, unit string, unitDivisor float64) benchResult {

	// Warmup.
	for w := 0; w < warmupIters; w++ {
		deadline := time.Now().Add(warmupTime)
		for time.Now().Before(deadline) {
			fn()
		}
	}

	// Measurement.
	samples := make([]float64, 0, measureIters)
	for i := 0; i < measureIters; i++ {
		var ops int64
		start := time.Now()
		deadline := start.Add(measureTime)
		for time.Now().Before(deadline) {
			fn()
			ops++
		}
		elapsed := time.Since(start).Nanoseconds()
		samples = append(samples, float64(elapsed)/float64(ops)/unitDivisor)
	}

	// Stats.
	var sum float64
	for _, s := range samples {
		sum += s
	}
	mean := sum / float64(len(samples))
	var variance float64
	for _, s := range samples {
		d := s - mean
		variance += d * d
	}
	stddev := math.Sqrt(variance / float64(len(samples)-1))
	// t-value for 99.9% CI with 9 df ≈ 4.781
	err := 4.781 * stddev / math.Sqrt(float64(len(samples)))

	return benchResult{
		Engine:    "go_regexp",
		Benchmark: name,
		Score:     math.Round(mean*1000) / 1000,
		Error:     math.Round(err*1000) / 1000,
		Unit:      unit,
	}
}

// measureNs is a convenience wrapper for ns/op measurements.
func measureNs(name string, fn func()) benchResult {
	return measure(name, fn, 2, 2*time.Second, 10, 2*time.Second, "ns/op", 1.0)
}

// measureUs is a convenience wrapper for µs/op measurements.
func measureUs(name string, fn func()) benchResult {
	return measure(name, fn, 2, 2*time.Second, 10, 2*time.Second, "us/op", 1000.0)
}

func printJSON(r benchResult) {
	b, _ := json.Marshal(r)
	fmt.Println(string(b))
}

func matchesFilter(name string, filters []string) bool {
	if len(filters) == 0 {
		return true
	}
	for _, f := range filters {
		if strings.Contains(name, f) {
			return true
		}
	}
	return false
}

// sink prevents the compiler from optimizing away results.
var sink any

// ---------------------------------------------------------------------------
// JSON loading
// ---------------------------------------------------------------------------

func loadBenchmarkData(path string) map[string]any {
	data, err := os.ReadFile(path)
	if err != nil {
		fmt.Fprintf(os.Stderr, "ERROR: cannot open benchmark data file: %s\n", path)
		os.Exit(1)
	}
	var result map[string]any
	if err := json.Unmarshal(data, &result); err != nil {
		fmt.Fprintf(os.Stderr, "ERROR: invalid JSON in %s: %v\n", path, err)
		os.Exit(1)
	}
	return result
}

// get navigates a dot-separated path in the JSON structure.
func get(data any, path string) any {
	parts := strings.Split(path, ".")
	current := data
	for _, part := range parts {
		m, ok := current.(map[string]any)
		if !ok {
			return nil
		}
		current = m[part]
	}
	return current
}

func getString(data any, path string) string {
	v := get(data, path)
	if s, ok := v.(string); ok {
		return s
	}
	return ""
}

func getInt(data any, path string) int {
	v := get(data, path)
	if f, ok := v.(float64); ok {
		return int(f)
	}
	return 0
}

func getIntSlice(data any, path string) []int {
	v := get(data, path)
	arr, ok := v.([]any)
	if !ok {
		return nil
	}
	result := make([]int, len(arr))
	for i, item := range arr {
		if f, ok := item.(float64); ok {
			result[i] = int(f)
		}
	}
	return result
}

// ---------------------------------------------------------------------------
// Text generators
// ---------------------------------------------------------------------------

func makeRandomText(size int, alphabet string, seed int64) string {
	rng := rand.New(rand.NewSource(seed))
	buf := make([]byte, size)
	for i := range buf {
		buf[i] = alphabet[rng.Intn(len(alphabet))]
	}
	return string(buf)
}

func makeProse(size int, unit string) string {
	var b strings.Builder
	b.Grow(size + len(unit))
	for b.Len() < size {
		b.WriteString(unit)
	}
	return b.String()
}

func appendUTF8(b *strings.Builder, cp int) {
	var buf [4]byte
	n := utf8.EncodeRune(buf[:], rune(cp))
	b.Write(buf[:n])
}

func makeUnicodeText(size int, codepoints []int, seed int64) string {
	rng := rand.New(rand.NewSource(seed))
	var b strings.Builder
	for b.Len() < size {
		cp := codepoints[rng.Intn(len(codepoints))]
		appendUTF8(&b, cp)
	}
	return b.String()
}

// ---------------------------------------------------------------------------
// Benchmark implementations
// ---------------------------------------------------------------------------

func runRegexBenchmarks(data map[string]any, filters []string) {
	sec := data["regex"]

	hello := regexp.MustCompile(getString(sec, "literalMatch.pattern"))
	alpha := regexp.MustCompile(getString(sec, "charClassMatch.pattern"))
	alt := regexp.MustCompile(getString(sec, "alternationFind.pattern"))
	date := regexp.MustCompile(getString(sec, "captureGroups.pattern"))
	findIng := regexp.MustCompile(getString(sec, "findInText.pattern"))
	email := regexp.MustCompile(getString(sec, "emailFind.pattern"))

	helloText := getString(sec, "literalMatch.text")
	alphaText := getString(sec, "charClassMatch.text")
	altText := getString(sec, "alternationFind.text")
	dateText := getString(sec, "captureGroups.text")
	prose := getString(sec, "findInText.text")
	emailText := getString(sec, "emailFind.text")

	run := func(name string, fn func()) {
		if matchesFilter(name, filters) {
			printJSON(measureNs(name, fn))
		}
	}

	run("RegexBenchmark.literalMatch", func() {
		sink = hello.MatchString(helloText)
	})
	run("RegexBenchmark.charClassMatch", func() {
		sink = alpha.MatchString(alphaText)
	})
	run("RegexBenchmark.alternationFind", func() {
		sink = alt.FindAllString(altText, -1)
	})
	run("RegexBenchmark.captureGroups", func() {
		sink = date.FindStringSubmatch(dateText)
	})
	run("RegexBenchmark.findInText", func() {
		sink = findIng.FindAllString(prose, -1)
	})
	run("RegexBenchmark.emailFind", func() {
		sink = email.FindString(emailText)
	})
}

func runCompileBenchmarks(data map[string]any, filters []string) {
	sec := data["compile"]

	simple := getString(sec, "simple.pattern")
	medium := getString(sec, "medium.pattern")
	complex := getString(sec, "complex.pattern")
	alternation := getString(sec, "alternation.pattern")

	run := func(name string, pattern string) {
		if matchesFilter(name, filters) {
			printJSON(measureUs(name, func() {
				sink = regexp.MustCompile(pattern)
			}))
		}
	}

	run("CompileBenchmark.compileSimple", simple)
	run("CompileBenchmark.compileMedium", medium)
	run("CompileBenchmark.compileComplex", complex)
	run("CompileBenchmark.compileAlternation", alternation)
}

func runSearchScalingBenchmarks(data map[string]any, filters []string) {
	sec := data["searchScaling"].(map[string]any)

	sizes := getIntSlice(sec, "textSizes")
	matchSuffix := getString(sec, "matchSuffix")
	alphabet := getString(sec, "randomText.alphabet")
	// Interpret \n in alphabet
	alphabet = strings.ReplaceAll(alphabet, `\n`, "\n")
	seed := int64(getInt(sec, "randomText.seed"))
	proseUnit := getString(sec, "proseUnit")

	easy := regexp.MustCompile(getString(sec, "patterns.easy"))
	medium := regexp.MustCompile(getString(sec, "patterns.medium"))
	hard := regexp.MustCompile(getString(sec, "patterns.hard"))
	findIng := regexp.MustCompile(getString(sec, "findIngPattern"))

	for _, size := range sizes {
		randomText := makeRandomText(size, alphabet, seed)
		textWithMatch := randomText + matchSuffix
		proseText := makeProse(size, proseUnit)

		suffix := fmt.Sprintf(".%d", size)

		run := func(name string, fn func()) {
			fullName := name + suffix
			if matchesFilter(fullName, filters) {
				printJSON(measureUs(fullName, fn))
			}
		}

		run("SearchScalingBenchmark.searchEasyFail", func() {
			sink = easy.MatchString(randomText)
		})
		run("SearchScalingBenchmark.searchEasySuccess", func() {
			sink = easy.MatchString(textWithMatch)
		})
		run("SearchScalingBenchmark.searchMediumFail", func() {
			sink = medium.MatchString(randomText)
		})
		run("SearchScalingBenchmark.searchHardFail", func() {
			sink = hard.MatchString(randomText)
		})
		run("SearchScalingBenchmark.findIngScaled", func() {
			sink = findIng.FindAllString(proseText, -1)
		})
	}
}

func runCaptureScalingBenchmarks(data map[string]any, filters []string) {
	sec := data["captureScaling"]

	pat0 := regexp.MustCompile(getString(sec, "capture0.pattern"))
	pat1 := regexp.MustCompile(getString(sec, "capture1.pattern"))
	pat3 := regexp.MustCompile(getString(sec, "capture3.pattern"))
	pat10 := regexp.MustCompile(getString(sec, "capture10.pattern"))

	text0 := getString(sec, "capture0.text")
	text1 := getString(sec, "capture1.text")
	text3 := getString(sec, "capture3.text")
	text10 := getString(sec, "capture10.text")

	run := func(name string, fn func()) {
		if matchesFilter(name, filters) {
			printJSON(measureNs(name, fn))
		}
	}

	run("CaptureScalingBenchmark.capture0", func() {
		sink = pat0.MatchString(text0)
	})
	run("CaptureScalingBenchmark.capture1", func() {
		sink = pat1.FindStringSubmatch(text1)
	})
	run("CaptureScalingBenchmark.capture3", func() {
		sink = pat3.FindStringSubmatch(text3)
	})
	run("CaptureScalingBenchmark.capture10", func() {
		sink = pat10.FindStringSubmatch(text10)
	})
}

func runHTTPBenchmarks(data map[string]any, filters []string) {
	sec := data["http"]

	http := regexp.MustCompile(getString(sec, "pattern"))
	full := getString(sec, "fullRequest")
	small := getString(sec, "smallRequest")

	run := func(name string, fn func()) {
		if matchesFilter(name, filters) {
			printJSON(measureNs(name, fn))
		}
	}

	run("HttpBenchmark.httpFull", func() {
		sink = http.FindStringSubmatch(full)
	})
	run("HttpBenchmark.httpSmall", func() {
		sink = http.FindStringSubmatch(small)
	})
	run("HttpBenchmark.httpExtract", func() {
		sink = http.FindStringSubmatch(full)
	})
}

func runReplaceBenchmarks(data map[string]any, filters []string) {
	sec := data["replace"].(map[string]any)

	for key, val := range sec {
		entry := val.(map[string]any)
		pattern := entry["pattern"].(string)
		text := entry["text"].(string)
		replacement := entry["replacement"].(string)
		op := entry["op"].(string)

		// Convert Java-style $N backreferences to Go-style ${N}
		goReplacement := convertReplacement(replacement)

		re := regexp.MustCompile(pattern)
		benchName := "ReplaceBenchmark." + key

		if !matchesFilter(benchName, filters) {
			continue
		}

		if op == "replaceFirst" {
			printJSON(measureNs(benchName, func() {
				// Go has no replaceFirst; use ReplaceAllStringFunc with a once flag.
				replaced := false
				sink = re.ReplaceAllStringFunc(text, func(match string) string {
					if !replaced {
						replaced = true
						return re.ReplaceAllString(match, goReplacement)
					}
					return match
				})
			}))
		} else {
			printJSON(measureNs(benchName, func() {
				sink = re.ReplaceAllString(text, goReplacement)
			}))
		}
	}
}

// convertReplacement translates Java-style $N to Go-style ${N}.
func convertReplacement(repl string) string {
	var b strings.Builder
	for i := 0; i < len(repl); i++ {
		if repl[i] == '$' && i+1 < len(repl) && repl[i+1] >= '0' && repl[i+1] <= '9' {
			b.WriteString("${")
			i++
			for i < len(repl) && repl[i] >= '0' && repl[i] <= '9' {
				b.WriteByte(repl[i])
				i++
			}
			b.WriteByte('}')
			i--
		} else {
			b.WriteByte(repl[i])
		}
	}
	return b.String()
}

func runPathologicalBenchmarks(data map[string]any, filters []string) {
	ns := getIntSlice(data, "pathological.nValues")

	for _, n := range ns {
		pattern := strings.Repeat("a?", n) + strings.Repeat("a", n)
		text := strings.Repeat("a", n)

		name := fmt.Sprintf("PathologicalBenchmark.pathological.%d", n)
		if matchesFilter(name, filters) {
			re := regexp.MustCompile(pattern)
			printJSON(measureUs(name, func() {
				sink = re.MatchString(text)
			}))
		}
	}
}

func runFanoutBenchmarks(data map[string]any, filters []string) {
	sec := data["fanout"].(map[string]any)
	sizes := getIntSlice(sec, "textSizes")

	fanout := regexp.MustCompile(getString(sec, "unicodeFanout.pattern"))
	nested := regexp.MustCompile(getString(sec, "nestedQuantifier.pattern"))

	codepoints := getIntSlice(sec, "unicodeFanout.codePoints")
	unicodeSeed := int64(getInt(sec, "unicodeFanout.seed"))

	nestedAlphabet := getString(sec, "nestedQuantifier.alphabet")
	nestedSeed := int64(getInt(sec, "nestedQuantifier.seed"))

	for _, size := range sizes {
		unicodeText := makeUnicodeText(size, codepoints, unicodeSeed)
		asciiText := makeRandomText(size, nestedAlphabet, nestedSeed)

		suffix := fmt.Sprintf(".%d", size)

		run := func(name string, fn func()) {
			fullName := name + suffix
			if matchesFilter(fullName, filters) {
				printJSON(measureUs(fullName, fn))
			}
		}

		run("FanoutBenchmark.fanoutUnicode", func() {
			sink = fanout.MatchString(unicodeText)
		})
		run("FanoutBenchmark.nestedQuantifier", func() {
			sink = nested.MatchString(asciiText)
		})
	}
}

// ---------------------------------------------------------------------------
// Memory benchmarks
// ---------------------------------------------------------------------------

type memoryResult struct {
	Engine    string `json:"engine"`
	Benchmark string `json:"benchmark"`
	Score     int64  `json:"score"`
	Error     int    `json:"error"`
	Unit      string `json:"unit"`
}

func printMemoryJSON(r memoryResult) {
	b, _ := json.Marshal(r)
	fmt.Println(string(b))
}

// measureCompiledSize measures heap bytes allocated by compiling a regexp
// pattern, using runtime.MemStats before/after with forced GC.
func measureCompiledSize(pattern string) int64 {
	// Warm up.
	_ = regexp.MustCompile(pattern)

	runtime.GC()
	runtime.GC()
	var before runtime.MemStats
	runtime.ReadMemStats(&before)

	re := regexp.MustCompile(pattern)

	runtime.GC()
	runtime.GC()
	var after runtime.MemStats
	runtime.ReadMemStats(&after)

	// Keep re alive past the measurement.
	sink = re

	delta := int64(after.TotalAlloc - before.TotalAlloc)
	if delta < 0 {
		delta = 0
	}
	return delta
}

func runMemoryBenchmarks(data map[string]any, filters []string) {
	compileSec := data["compile"]
	regexSec := data["regex"]

	type patternInfo struct {
		name    string
		pattern string
	}

	patterns := []patternInfo{
		{"MemoryBenchmark.compileSimple", getString(compileSec, "simple.pattern")},
		{"MemoryBenchmark.compileMedium", getString(compileSec, "medium.pattern")},
		{"MemoryBenchmark.compileComplex", getString(compileSec, "complex.pattern")},
		{"MemoryBenchmark.compileAlternation", getString(compileSec, "alternation.pattern")},
		{"MemoryBenchmark.literalMatch", getString(regexSec, "literalMatch.pattern")},
		{"MemoryBenchmark.charClassMatch", getString(regexSec, "charClassMatch.pattern")},
		{"MemoryBenchmark.alternationFind", getString(regexSec, "alternationFind.pattern")},
		{"MemoryBenchmark.captureGroups", getString(regexSec, "captureGroups.pattern")},
		{"MemoryBenchmark.findInText", getString(regexSec, "findInText.pattern")},
		{"MemoryBenchmark.emailFind", getString(regexSec, "emailFind.pattern")},
	}

	for _, pi := range patterns {
		if !matchesFilter(pi.name, filters) {
			continue
		}
		heapBytes := measureCompiledSize(pi.pattern)
		printMemoryJSON(memoryResult{
			Engine:    "go_regexp",
			Benchmark: pi.name + ".heapBytes",
			Score:     heapBytes,
			Unit:      "bytes",
		})
	}
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

func main() {
	dataPath := "../../benchmark-data.json"
	var filters []string

	args := os.Args[1:]
	for i := 0; i < len(args); i++ {
		if args[i] == "--data" && i+1 < len(args) {
			dataPath = args[i+1]
			i++
		} else {
			filters = append(filters, args[i])
		}
	}

	data := loadBenchmarkData(dataPath)

	runRegexBenchmarks(data, filters)
	runCompileBenchmarks(data, filters)
	runSearchScalingBenchmarks(data, filters)
	runCaptureScalingBenchmarks(data, filters)
	runHTTPBenchmarks(data, filters)
	runReplaceBenchmarks(data, filters)
	runPathologicalBenchmarks(data, filters)
	runFanoutBenchmarks(data, filters)
	runMemoryBenchmarks(data, filters)
}
