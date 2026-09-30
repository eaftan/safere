// Copyright (c) 2026 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package main

import (
	"regexp"
	"slices"
	"testing"
)

func TestMaterializedReplacementIsUsedExactly(t *testing.T) {
	re := regexp.MustCompile("(qu|[b-df-hj-np-tv-z]*)([a-z]+)")
	if actual := re.ReplaceAllString("the", "${2}${1}ay"); actual != "ethay" {
		t.Fatalf("replacement result %q, want %q", actual, "ethay")
	}
}

func TestFullMatchIsNotWeakenedByMultilineMode(t *testing.T) {
	re := compileFull("(?m)abc")
	if !re.MatchString("abc") || re.MatchString("abc\nother") {
		t.Fatal("full match did not anchor the complete input")
	}
}

func TestJavaZeroSplitLimitMeansUnlimitedAndDropsTrailingEmptyParts(t *testing.T) {
	re := regexp.MustCompile(",")
	if actual := splitLengthSum(re, "a,b,", 0); actual != 4 {
		t.Fatalf("split length sum %d, want %d", actual, 4)
	}
}

func TestCaptureBoundsAdvanceAcrossMultibyteEmptyMatches(t *testing.T) {
	re := regexp.MustCompile("")
	var bounds []int
	if actual := captureBounds(re, "a有", []int{0}, false, &bounds); actual != 5 {
		t.Fatalf("start sum %d, want 5", actual)
	}
	want := []int{0, 0, 1, 1, 4, 4}
	if !slices.Equal(bounds, want) {
		t.Fatalf("bounds %v, want %v", bounds, want)
	}
}

func TestEveryRunnablePatternCompiles(t *testing.T) {
	plan := loadManifest("../target/benchmark-corpus/manifest.json")
	checked := 0
	for _, entry := range plan.ExecutionPlan.Entries {
		if entry.EngineID != engineID || entry.Status != "runnable" {
			continue
		}
		for _, pattern := range entry.Patterns {
			checked++
			if _, err := regexp.Compile(pattern); err != nil {
				t.Errorf("%s pattern %q: %v", entry.WorkloadID, pattern, err)
			}
		}
	}
	if checked == 0 {
		t.Fatal("no runnable Go patterns in the materialized plan")
	}
}
