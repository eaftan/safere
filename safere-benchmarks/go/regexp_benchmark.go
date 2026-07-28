// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.
//
// Go regexp benchmark harness. The materialized execution plan is the sole
// source of workload selection, engine syntax, arguments, and exclusions.
package main

import (
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"time"
)

const engineID = "go_regexp"

type materializedInput struct {
	File      string `json:"file"`
	UTF8Bytes int    `json:"utf8Bytes"`
	SHA256    string `json:"sha256"`
}

type measurement struct {
	Mode       string `json:"mode"`
	TimingUnit string `json:"timingUnit"`
}

type exclusion struct {
	Kind   string `json:"kind"`
	Reason string `json:"reason"`
}

type expectedResult struct {
	Type  string `json:"type"`
	Value any    `json:"value"`
}

type planEntry struct {
	EngineID    string          `json:"engineId"`
	WorkloadID  string          `json:"workloadId"`
	Operation   string          `json:"operation"`
	Status      string          `json:"status"`
	Patterns    []string        `json:"patterns"`
	Inputs      []string        `json:"inputs"`
	Arguments   map[string]any  `json:"arguments"`
	Options     []string        `json:"options"`
	Measurement measurement     `json:"measurement"`
	Expected    *expectedResult `json:"expected"`
	Exclusion   *exclusion      `json:"exclusion"`
}

type manifest struct {
	Version       int                          `json:"version"`
	Inputs        map[string]materializedInput `json:"inputs"`
	ExecutionPlan struct {
		Version int         `json:"version"`
		Entries []planEntry `json:"entries"`
	} `json:"executionPlan"`
}

type benchResult struct {
	Engine    string  `json:"engine"`
	Benchmark string  `json:"benchmark"`
	Score     float64 `json:"score"`
	Error     float64 `json:"error"`
	Unit      string  `json:"unit"`
}

type reportedExclusion struct {
	Engine    string `json:"engine"`
	Benchmark string `json:"benchmark"`
	Reason    string `json:"reason"`
}

var benchmarkInputDirectory string
var benchmarkInputs map[string]materializedInput
var sink any

func loadManifest(path string) manifest {
	data, err := os.ReadFile(path)
	if err != nil {
		panic(fmt.Sprintf("cannot open benchmark input manifest: %v", err))
	}
	var result manifest
	if err := json.Unmarshal(data, &result); err != nil {
		panic(fmt.Sprintf("invalid benchmark input manifest: %v", err))
	}
	if result.Version != 1 || result.ExecutionPlan.Version != 1 {
		panic("unsupported benchmark manifest or execution-plan version")
	}
	benchmarkInputDirectory = filepath.Dir(path)
	benchmarkInputs = result.Inputs
	return result
}

func loadBenchmarkInput(key string) string {
	entry, ok := benchmarkInputs[key]
	if !ok {
		panic("unknown materialized benchmark input: " + key)
	}
	data, err := os.ReadFile(filepath.Join(benchmarkInputDirectory, entry.File))
	if err != nil {
		panic(fmt.Sprintf("cannot open materialized benchmark input %s: %v", key, err))
	}
	if len(data) != entry.UTF8Bytes {
		panic(fmt.Sprintf("materialized benchmark input %s has wrong size", key))
	}
	if fmt.Sprintf("%x", sha256.Sum256(data)) != entry.SHA256 {
		panic(fmt.Sprintf("materialized benchmark input %s has wrong SHA-256", key))
	}
	return string(data)
}

func stringsArgument(arguments map[string]any, key string) []int {
	raw, _ := arguments[key].([]any)
	result := make([]int, len(raw))
	for i, value := range raw {
		result[i] = int(value.(float64))
	}
	return result
}

func intArgument(arguments map[string]any, key string) int {
	value, _ := arguments[key].(float64)
	return int(value)
}

func stringArgument(arguments map[string]any, key string) string {
	value, _ := arguments[key].(string)
	return value
}

func compileFull(pattern string) *regexp.Regexp {
	return regexp.MustCompile(`\A(?:` + pattern + `)\z`)
}

func groupLengthSum(indices []int, groups []int) int {
	total := 0
	for _, group := range groups {
		offset := group * 2
		if offset+1 < len(indices) && indices[offset] >= 0 {
			total += indices[offset+1] - indices[offset]
		}
	}
	return total
}

func capturedText(text string, indices []int, groups []int) string {
	var result strings.Builder
	for _, group := range groups {
		offset := group * 2
		if offset+1 < len(indices) && indices[offset] >= 0 {
			result.WriteString(text[indices[offset]:indices[offset+1]])
		}
	}
	return result.String()
}

func splitLengthSum(re *regexp.Regexp, text string, limit int) int {
	goLimit := limit
	if limit == 0 {
		goLimit = -1
	}
	parts := re.Split(text, goLimit)
	if limit == 0 {
		for len(parts) > 0 && parts[len(parts)-1] == "" {
			parts = parts[:len(parts)-1]
		}
	}
	total := len(parts)
	for _, part := range parts {
		total += len(part)
	}
	return total
}

func prepare(entry planEntry) func() any {
	if len(entry.Options) != 0 {
		panic(fmt.Sprintf("%s has unsupported materialized options: %v",
			entry.WorkloadID, entry.Options))
	}
	regexes := make([]*regexp.Regexp, len(entry.Patterns))
	for i, pattern := range entry.Patterns {
		regexes[i] = regexp.MustCompile(pattern)
	}
	texts := make([]string, len(entry.Inputs))
	for i, input := range entry.Inputs {
		texts[i] = loadBenchmarkInput(input)
	}
	var re *regexp.Regexp
	if len(regexes) > 0 {
		re = regexes[0]
	}
	text := ""
	if len(texts) > 0 {
		text = texts[0]
	}
	groups := stringsArgument(entry.Arguments, "groups")
	group := intArgument(entry.Arguments, "group")
	replacement := stringArgument(entry.Arguments, "replacement")
	limit := intArgument(entry.Arguments, "limit")
	var full *regexp.Regexp
	if len(entry.Patterns) > 0 {
		full = compileFull(entry.Patterns[0])
	}

	switch entry.Operation {
	case "compile":
		return func() any { return regexp.MustCompile(entry.Patterns[0]) }
	case "matches":
		return func() any { return full.MatchString(text) }
	case "find":
		return func() any { return re.MatchString(text) }
	case "findAllCount":
		return func() any { return len(re.FindAllStringIndex(text, -1)) }
	case "matchesCorpus":
		return func() any {
			count := 0
			for _, candidate := range texts {
				if full.MatchString(candidate) {
					count++
				}
			}
			return count
		}
	case "matchesGroupLengthSum":
		return func() any {
			total := 0
			for _, candidate := range texts {
				total += groupLengthSum(full.FindStringSubmatchIndex(candidate), groups)
			}
			return total
		}
	case "findAllLengthSum":
		return func() any {
			total := 0
			for _, match := range re.FindAllStringIndex(text, -1) {
				total += match[1] - match[0]
			}
			return total
		}
	case "findAllGroupLengthSum":
		return func() any {
			total := 0
			for _, match := range re.FindAllStringSubmatchIndex(text, -1) {
				total += groupLengthSum(match, groups)
			}
			return total
		}
	case "captureGroups":
		return func() any {
			return capturedText(text, full.FindStringSubmatchIndex(text), groups)
		}
	case "replaceFirst":
		return func() any {
			match := re.FindStringSubmatchIndex(text)
			if match == nil {
				return text
			}
			result := append([]byte{}, text[:match[0]]...)
			result = re.ExpandString(result, replacement, text, match)
			result = append(result, text[match[1]:]...)
			return string(result)
		}
	case "replaceAll":
		return func() any { return re.ReplaceAllString(text, replacement) }
	case "replaceAllLengthSum":
		return func() any {
			total := 0
			for _, item := range regexes {
				total += len(item.ReplaceAllString(text, replacement))
			}
			return total
		}
	case "splitLengthSum":
		return func() any { return splitLengthSum(re, text, limit) }
	case "findGroupPresent":
		return func() any {
			match := re.FindStringSubmatchIndex(text)
			offset := group * 2
			return offset+1 < len(match) && match[offset] >= 0
		}
	case "findGroup":
		return func() any {
			match := re.FindStringSubmatchIndex(text)
			offset := group * 2
			if offset+1 >= len(match) || match[offset] < 0 {
				return ""
			}
			return text[match[offset]:match[offset+1]]
		}
	default:
		panic("unsupported runnable operation: " + entry.Operation)
	}
}

func validate(entry planEntry, operation func() any) {
	if entry.Expected == nil {
		return
	}
	actual := operation()
	expected := entry.Expected.Value
	if number, ok := expected.(float64); ok {
		expected = int(number)
	}
	if fmt.Sprint(actual) != fmt.Sprint(expected) {
		panic(fmt.Sprintf("%s result mismatch: expected %v, got %v",
			entry.WorkloadID, expected, actual))
	}
}

func measure(entry planEntry, operation func() any, smoke bool) benchResult {
	unit := map[string]string{
		"nanoseconds":  "ns/op",
		"microseconds": "us/op",
		"milliseconds": "ms/op",
	}[entry.Measurement.TimingUnit]
	divisor := map[string]float64{"ns/op": 1, "us/op": 1000, "ms/op": 1_000_000}[unit]
	warmups, iterations := 2, 10
	duration := 2 * time.Second
	if smoke {
		warmups, iterations, duration = 0, 1, time.Nanosecond
	}
	for i := 0; i < warmups; i++ {
		deadline := time.Now().Add(duration)
		for time.Now().Before(deadline) {
			sink = operation()
		}
	}
	samples := make([]float64, iterations)
	for i := range samples {
		start := time.Now()
		deadline := start.Add(duration)
		operations := 0
		for operations == 0 || time.Now().Before(deadline) {
			sink = operation()
			operations++
		}
		samples[i] = float64(time.Since(start).Nanoseconds()) / float64(operations) / divisor
	}
	mean := samples[0]
	error := 0.0
	if len(samples) > 1 {
		mean = 0
		for _, sample := range samples {
			mean += sample
		}
		mean /= float64(len(samples))
		var variance float64
		for _, sample := range samples {
			variance += (sample - mean) * (sample - mean)
		}
		error = 4.781 * math.Sqrt(variance/float64(len(samples)-1)) /
			math.Sqrt(float64(len(samples)))
	}
	return benchResult{engineID, entry.WorkloadID, round(mean), round(error), unit}
}

func measureCompiledSize(pattern string) int64 {
	_ = regexp.MustCompile(pattern)
	runtime.GC()
	runtime.GC()
	var before runtime.MemStats
	runtime.ReadMemStats(&before)
	compiled := regexp.MustCompile(pattern)
	runtime.GC()
	runtime.GC()
	var after runtime.MemStats
	runtime.ReadMemStats(&after)
	sink = compiled
	return int64(after.TotalAlloc - before.TotalAlloc)
}

func round(value float64) float64 { return math.Round(value*1000) / 1000 }

func matchesFilter(name string, filters []string) bool {
	if len(filters) == 0 {
		return true
	}
	for _, filter := range filters {
		if strings.Contains(name, filter) {
			return true
		}
	}
	return false
}

func printJSON(value any) {
	data, _ := json.Marshal(value)
	fmt.Println(string(data))
}

func main() {
	manifestPath := "../../target/benchmark-corpus/manifest.json"
	smoke, list, listExclusions := false, false, false
	var filters []string
	args := os.Args[1:]
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--manifest":
			i++
			manifestPath = args[i]
		case "--smoke":
			smoke = true
		case "--list":
			list = true
		case "--list-exclusions":
			listExclusions = true
		default:
			filters = append(filters, args[i])
		}
	}

	plan := loadManifest(manifestPath)
	for _, entry := range plan.ExecutionPlan.Entries {
		if entry.EngineID != engineID || !matchesFilter(entry.WorkloadID, filters) {
			continue
		}
		if entry.Status == "excluded" {
			if listExclusions {
				printJSON(reportedExclusion{engineID, entry.WorkloadID, entry.Exclusion.Reason})
			}
			continue
		}
		if entry.Status != "runnable" {
			panic("unknown execution-plan status: " + entry.Status)
		}
		if list {
			fmt.Println(entry.WorkloadID)
			continue
		}
		if listExclusions {
			continue
		}
		if entry.Measurement.Mode == "retainedMemory" {
			printJSON(benchResult{
				engineID, entry.WorkloadID, float64(measureCompiledSize(entry.Patterns[0])),
				0, "bytes",
			})
			continue
		}
		operation := prepare(entry)
		validate(entry, operation)
		printJSON(measure(entry, operation, smoke))
	}
}
