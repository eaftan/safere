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

func getStringSlice(data any, path string) []string {
	v := get(data, path)
	arr, ok := v.([]any)
	if !ok {
		return nil
	}
	result := make([]string, len(arr))
	for i, item := range arr {
		if s, ok := item.(string); ok {
			result[i] = s
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

func repeatToSize(unit string, size int) string {
	var b strings.Builder
	b.Grow(size + len(unit))
	for b.Len() < size {
		b.WriteString(unit)
	}
	return b.String()[:size]
}

func surroundToSize(prefix string, unit string, suffix string, size int) string {
	bodySize := size - len(prefix) - len(suffix)
	if bodySize < 0 {
		bodySize = 0
	}
	return prefix + repeatToSize(unit, bodySize) + suffix
}

func suffixMatchToSize(prefixUnit string, match string, size int) string {
	prefixSize := size - len(match)
	if prefixSize < 0 {
		prefixSize = 0
	}
	return repeatToSize(prefixUnit, prefixSize) + match
}

func generatedRealWorldInput(unit string, size int, alphabet string, seed int) string {
	if len(unit) >= size {
		return unit[:size]
	}
	var b strings.Builder
	b.Grow(size + len(unit))
	delimiterIndex := seed
	for b.Len() < size {
		b.WriteString(unit)
		if b.Len() < size {
			b.WriteByte(alphabet[delimiterIndex%len(alphabet)])
			delimiterIndex++
		}
	}
	return b.String()[:size]
}

func generatedSparseRealWorldInput(
	matchUnit string, nonMatchUnit string, size int, seed int, nonMatchRepeats int,
	alphabet string,
) string {
	var b strings.Builder
	b.Grow(size + len(matchUnit) + len(nonMatchUnit))
	delimiterIndex := seed
	for b.Len() < size {
		for i := 0; i < nonMatchRepeats && b.Len() < size; i++ {
			b.WriteString(nonMatchUnit)
			if b.Len() < size {
				b.WriteByte(alphabet[delimiterIndex%len(alphabet)])
				delimiterIndex++
			}
		}
		if b.Len() < size {
			b.WriteByte(' ')
			b.WriteString(matchUnit)
			b.WriteByte(' ')
		}
	}
	return b.String()[:size]
}

func generateRealWorldInput(
	inputSpec map[string]any, matchUnit string, nonMatchUnit string, match bool, size int,
	alphabet string, seed int,
) string {
	unit := nonMatchUnit
	if match {
		unit = matchUnit
	}
	kind := "repeat"
	if rawKind, ok := inputSpec["kind"]; ok {
		kind = rawKind.(string)
	}
	switch kind {
	case "repeat":
		return generatedRealWorldInput(unit, size, alphabet, seed)
	case "prefixedRepeat":
		prefix := getString(inputSpec, "prefix")
		if len(prefix) >= size {
			return prefix[:size]
		}
		bodySize := size - len(prefix)
		return prefix + generatedRealWorldInput(unit, bodySize, alphabet, seed)
	case "sparseMatch":
		return generatedSparseRealWorldInput(
			matchUnit, nonMatchUnit, size, seed, getInt(inputSpec, "nonMatchRepeats"),
			getString(inputSpec, "delimiterAlphabet"))
	case "surroundWithSpaces":
		body := getString(inputSpec, "body")
		return generateSurroundWithSpacesInput(body, size)
	case "scaledSurroundWithSpaces":
		return generateScaledSurroundWithSpacesInput(
			getString(inputSpec, "bodyPrefix"),
			getString(inputSpec, "bodySuffix"),
			getString(inputSpec, "bodyFill"),
			getInt(inputSpec, "bodyScalePercent"),
			size)
	default:
		fmt.Fprintf(os.Stderr, "ERROR: invalid realWorldRegex input kind: %s\n", kind)
		os.Exit(1)
	}
	panic("unreachable")
}

func generateSurroundWithSpacesInput(body string, size int) string {
	if len(body) >= size {
		return body[:size]
	}
	totalPadding := size - len(body)
	leadingPadding := totalPadding / 2
	trailingPadding := totalPadding - leadingPadding
	return strings.Repeat(" ", leadingPadding) + body + strings.Repeat(" ", trailingPadding)
}

func generateScaledSurroundWithSpacesInput(
	bodyPrefix string, bodySuffix string, bodyFill string, bodyScalePercent int, size int,
) string {
	if bodyFill == "" {
		fmt.Fprintln(os.Stderr, "ERROR: scaledSurroundWithSpaces requires non-empty bodyFill")
		os.Exit(1)
	}
	fixedBodyLength := len(bodyPrefix) + len(bodySuffix)
	targetBodyLength := size * bodyScalePercent / 100
	if targetBodyLength < fixedBodyLength {
		targetBodyLength = fixedBodyLength
	}
	if targetBodyLength > size {
		targetBodyLength = size
	}
	fillLength := targetBodyLength - fixedBodyLength
	if fillLength < 0 {
		fillLength = 0
	}
	body := bodyPrefix + repeatToSize(bodyFill, fillLength) + bodySuffix
	return generateSurroundWithSpacesInput(body, size)
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

func runApplicationBenchmarks(data map[string]any, filters []string) {
	run := func(name string, fn func()) {
		if matchesFilter(name, filters) {
			printJSON(measureNs(name, fn))
		}
	}

	type appCase struct {
		name        string
		op          string
		pattern     string
		texts       []string
		text        string
		groups      []int
		replacement string
		expected    any
		re          *regexp.Regexp
		fullRe      *regexp.Regexp
	}

	rawCases, ok := data["application"].([]any)
	if !ok {
		fmt.Fprintln(os.Stderr, "ERROR: application benchmark data must be a list")
		os.Exit(1)
	}
	cases := make([]appCase, 0, len(rawCases))
	for _, raw := range rawCases {
		item, ok := raw.(map[string]any)
		if !ok {
			fmt.Fprintln(os.Stderr, "ERROR: invalid application benchmark case")
			os.Exit(1)
		}
		pattern := getString(item, "pattern")
		re, err := regexp.Compile(pattern)
		if err != nil {
			fmt.Fprintf(os.Stderr, "WARNING: skipping application benchmark %s: %v\n", getString(item, "name"), err)
			continue
		}
		var fullRe *regexp.Regexp
		if strings.HasPrefix(getString(item, "op"), "matches") {
			var err error
			fullRe, err = regexp.Compile("^(?:" + pattern + ")$")
			if err != nil {
				fmt.Fprintf(os.Stderr, "WARNING: skipping application benchmark %s (full match compile): %v\n", getString(item, "name"), err)
				continue
			}
		}
		c := appCase{
			name:        getString(item, "name"),
			op:          getString(item, "op"),
			pattern:     pattern,
			texts:       getStringSlice(item, "texts"),
			text:        getString(item, "text"),
			groups:      getIntSlice(item, "groups"),
			replacement: convertReplacement(getString(item, "replacement")),
			expected:    item["expected"],
			re:          re,
			fullRe:      fullRe,
		}
		cases = append(cases, c)
	}

	groupLengthSum := func(indexes []int, groups []int) int {
		sum := 0
		for _, group := range groups {
			start := indexes[2*group]
			end := indexes[2*group+1]
			if start >= 0 {
				sum += end - start
			}
		}
		return sum
	}
	runInt := func(c appCase) int {
		switch c.op {
		case "matchesCorpus":
			count := 0
			for _, text := range c.texts {
				if c.fullRe.MatchString(text) {
					count++
				}
			}
			return count
		case "matchesGroupLengthSum":
			count := 0
			for _, text := range c.texts {
				indexes := c.fullRe.FindStringSubmatchIndex(text)
				if indexes != nil {
					count += groupLengthSum(indexes, c.groups)
				}
			}
			return count
		case "findAllCount":
			return len(c.re.FindAllStringIndex(c.text, -1))
		case "findAllLengthSum":
			count := 0
			for _, match := range c.re.FindAllStringIndex(c.text, -1) {
				count += match[1] - match[0]
			}
			return count
		case "findAllGroupLengthSum":
			count := 0
			for _, indexes := range c.re.FindAllStringSubmatchIndex(c.text, -1) {
				count += groupLengthSum(indexes, c.groups)
			}
			return count
		default:
			fmt.Fprintf(os.Stderr, "ERROR: string op used as int op: %s\n", c.op)
			os.Exit(1)
		}
		return 0
	}
	runString := func(c appCase) string {
		return c.re.ReplaceAllString(c.text, c.replacement)
	}

	for _, c := range cases {
		if strings.HasPrefix(c.op, "findAll") && c.re.FindStringIndex("") != nil {
			fmt.Fprintf(os.Stderr, "ERROR: empty-width find-all application pattern: %s\n", c.name)
			os.Exit(1)
		}
		if c.op == "replaceAll" {
			actual := runString(c)
			if actual != c.expected.(string) {
				fmt.Fprintf(os.Stderr, "ERROR: %s expected result mismatch\n", c.name)
				os.Exit(1)
			}
		} else {
			actual := runInt(c)
			if actual != int(c.expected.(float64)) {
				fmt.Fprintf(os.Stderr, "ERROR: %s expected %d but was %d\n",
					c.name, int(c.expected.(float64)), actual)
				os.Exit(1)
			}
		}
	}

	for _, c := range cases {
		c := c
		run("ApplicationBenchmark."+c.name, func() {
			if c.op == "replaceAll" {
				sink = runString(c)
			} else {
				sink = runInt(c)
			}
		})
	}
}

func runRealWorldRegexBenchmarks(data map[string]any, filters []string) {
	sec, ok := data["realWorldRegex"].(map[string]any)
	if !ok {
		fmt.Fprintln(os.Stderr, "ERROR: realWorldRegex benchmark data must be an object")
		os.Exit(1)
	}
	sizes := getIntSlice(sec, "textSizes")
	alphabet := getString(sec, "safeDelimiterAlphabet")
	seed := getInt(sec, "seed")

	type realWorldCase struct {
		name          string
		op            string
		pattern       string
		match         string
		nonMatch      string
		matchInput    map[string]any
		nonMatchInput map[string]any
		re            *regexp.Regexp
		fullRe        *regexp.Regexp
	}

	rawCases, ok := sec["cases"].([]any)
	if !ok {
		fmt.Fprintln(os.Stderr, "ERROR: realWorldRegex cases must be a list")
		os.Exit(1)
	}
	cases := make([]realWorldCase, 0, len(rawCases))
	for _, raw := range rawCases {
		item, ok := raw.(map[string]any)
		if !ok {
			fmt.Fprintln(os.Stderr, "ERROR: invalid realWorldRegex benchmark case")
			os.Exit(1)
		}
		op := getString(item, "op")
		if op != "find" && op != "matches" && op != "replaceAllEmpty" && op != "replaceAllGroup1" && op != "replaceAllLiteral" {
			fmt.Fprintf(os.Stderr, "ERROR: invalid realWorldRegex op: %s\n", op)
			os.Exit(1)
		}
		pattern := getString(item, "pattern")
		re, err := regexp.Compile(pattern)
		if err != nil {
			fmt.Fprintf(os.Stderr, "WARNING: skipping realWorldRegex benchmark %s: %v\n", getString(item, "name"), err)
			continue
		}
		var fullRe *regexp.Regexp
		if op == "matches" {
			var err error
			fullRe, err = regexp.Compile("^(?:" + pattern + ")$")
			if err != nil {
				fmt.Fprintf(os.Stderr, "WARNING: skipping realWorldRegex benchmark %s (full match compile): %v\n", getString(item, "name"), err)
				continue
			}
		}
		matchInput, _ := item["matchInput"].(map[string]any)
		nonMatchInput, _ := item["nonMatchInput"].(map[string]any)
		c := realWorldCase{
			name:          getString(item, "name"),
			op:            op,
			pattern:       pattern,
			match:         getString(item, "match"),
			nonMatch:      getString(item, "nonMatch"),
			matchInput:    matchInput,
			nonMatchInput: nonMatchInput,
			re:            re,
			fullRe:        fullRe,
		}
		cases = append(cases, c)
	}

	for _, c := range cases {
		c := c
		for _, match := range []bool{true, false} {
			matchLabel := "noMatch"
			if match {
				matchLabel = "match"
			}
			for _, size := range sizes {
				inputSpec := c.nonMatchInput
				if match {
					inputSpec = c.matchInput
				}
				text := generateRealWorldInput(
					inputSpec, c.match, c.nonMatch, match, size, alphabet, seed)
				name := fmt.Sprintf(
					"RealWorldRegexBenchmark.runBenchmark.%s.%s.%d",
					c.name, matchLabel, size)
				if !matchesFilter(name, filters) {
					continue
				}
				if c.op == "find" {
					printJSON(measureNs(name, func() {
						sink = c.re.FindStringIndex(text) != nil
					}))
				} else if c.op == "matches" {
					printJSON(measureNs(name, func() {
						sink = c.fullRe.MatchString(text)
					}))
				} else if c.op == "replaceAllEmpty" {
					printJSON(measureNs(name, func() {
						sink = c.re.ReplaceAllString(text, "")
					}))
				} else if c.op == "replaceAllGroup1" {
					printJSON(measureNs(name, func() {
						sink = c.re.ReplaceAllString(text, "$1")
					}))
				} else if c.op == "replaceAllLiteral" {
					printJSON(measureNs(name, func() {
						sink = c.re.ReplaceAllString(text, "xyz")
					}))
				}
			}
		}
	}
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

func runIssue481ScalingBenchmarks(data map[string]any, filters []string) {
	sec := data["issue481Scaling"].(map[string]any)

	sizes := getIntSlice(sec, "textSizes")
	splitW := regexp.MustCompile(getString(sec, "splitW.pattern"))
	block := regexp.MustCompile(getString(sec, "block.pattern"))
	tag := regexp.MustCompile(getString(sec, "tag.pattern"))
	scheme := regexp.MustCompile(getString(sec, "scheme.pattern"))

	splitLengthSum := func(parts []string) int {
		for len(parts) > 0 && parts[len(parts)-1] == "" {
			parts = parts[:len(parts)-1]
		}
		sum := len(parts)
		for _, part := range parts {
			sum += len(part)
		}
		return sum
	}
	schemeExtract := func(text string, re *regexp.Regexp) int {
		sum := 0
		for _, match := range re.FindAllStringSubmatchIndex(text, -1) {
			sum += match[3] - match[2]
			sum += match[5] - match[4]
		}
		return sum
	}

	for _, size := range sizes {
		splitText := repeatToSize(getString(sec, "splitW.unit"), size)
		blockText := surroundToSize(
			getString(sec, "block.prefix"),
			getString(sec, "block.unit"),
			getString(sec, "block.suffix"),
			size)
		blockNegativeText := surroundToSize(
			getString(sec, "block.prefix"),
			getString(sec, "block.unit"),
			getString(sec, "block.negativeSuffix"),
			size)
		tagText := suffixMatchToSize(
			getString(sec, "tag.prefixUnit"),
			getString(sec, "tag.match"),
			size)
		tagNegativeText := suffixMatchToSize(
			getString(sec, "tag.prefixUnit"),
			getString(sec, "tag.negativeMatch"),
			size)
		schemeText := suffixMatchToSize(
			getString(sec, "scheme.prefixUnit"),
			getString(sec, "scheme.match"),
			size)
		schemeNegativeText := suffixMatchToSize(
			getString(sec, "scheme.prefixUnit"),
			getString(sec, "scheme.negativeMatch"),
			size)

		suffix := fmt.Sprintf(".%d", size)
		run := func(name string, fn func()) {
			fullName := name + suffix
			if matchesFilter(fullName, filters) {
				printJSON(measureUs(fullName, fn))
			}
		}

		run("Issue481ScalingBenchmark.splitWords", func() {
			sink = splitLengthSum(splitW.Split(splitText, -1))
		})
		run("Issue481ScalingBenchmark.blockFind", func() {
			sink = block.MatchString(blockText)
		})
		run("Issue481ScalingBenchmark.blockFindNegative", func() {
			sink = block.MatchString(blockNegativeText)
		})
		run("Issue481ScalingBenchmark.tagFind", func() {
			sink = tag.MatchString(tagText)
		})
		run("Issue481ScalingBenchmark.tagFindNegative", func() {
			sink = tag.MatchString(tagNegativeText)
		})
		run("Issue481ScalingBenchmark.schemeExtract", func() {
			sink = schemeExtract(schemeText, scheme)
		})
		run("Issue481ScalingBenchmark.schemeFindNegative", func() {
			sink = scheme.MatchString(schemeNegativeText)
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

		re, err := regexp.Compile(pattern)
		if err != nil {
			fmt.Fprintf(os.Stderr, "WARNING: skipping replace benchmark %s: %v\n", key, err)
			continue
		}
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
	runApplicationBenchmarks(data, filters)
	runRealWorldRegexBenchmarks(data, filters)
	runCompileBenchmarks(data, filters)
	runSearchScalingBenchmarks(data, filters)
	runIssue481ScalingBenchmarks(data, filters)
	runCaptureScalingBenchmarks(data, filters)
	runHTTPBenchmarks(data, filters)
	runReplaceBenchmarks(data, filters)
	runPathologicalBenchmarks(data, filters)
	runFanoutBenchmarks(data, filters)
	runMemoryBenchmarks(data, filters)
}
