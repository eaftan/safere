// Copyright (c) 2026 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package main

import (
	"encoding/json"
	"os"
	"regexp"
	"testing"
)

func TestCheckedInRE2PatternProfileCompiles(t *testing.T) {
	contents, err := os.ReadFile("../benchmark-data.json")
	if err != nil {
		t.Fatal(err)
	}
	var data map[string]any
	if err := json.Unmarshal(contents, &data); err != nil {
		t.Fatal(err)
	}
	alternateCount := 0
	var visit func(any)
	visit = func(value any) {
		switch current := value.(type) {
		case map[string]any:
			if javaPattern, ok := current["java"].(string); ok {
				alternates := current["alternates"].(map[string]any)
				if re2, ok := alternates["re2"].(map[string]any); ok {
					alternate := re2["pattern"].(string)
					alternateCount++
					if _, err := regexp.Compile(alternate); err != nil {
						t.Errorf(
							"%q alternate for %q does not compile: %v",
							alternate,
							javaPattern,
							err,
						)
					}
				}
				return
			}
			for _, child := range current {
				visit(child)
			}
		case []any:
			for _, child := range current {
				visit(child)
			}
		}
	}
	visit(data)
	if alternateCount == 0 {
		t.Fatal("no inline re2 pattern alternates found")
	}
}

func TestReplacementProfileSelectsExactAlternateWithJavaFallback(t *testing.T) {
	data := map[string]any{
		"replacementProfiles": map[string]any{
			"go-regexp": []any{
				map[string]any{
					"java":      "$2$1ay",
					"alternate": "${2}${1}ay",
				},
			},
		},
	}

	benchmarkReplacementProfile = loadProfile(data, "replacementProfiles", "go-regexp")
	t.Cleanup(func() {
		benchmarkReplacementProfile = nil
	})

	if actual := selectedReplacement("$2$1ay"); actual != "${2}${1}ay" {
		t.Fatalf("selected replacement %q, want %q", actual, "${2}${1}ay")
	}
	if actual := selectedReplacement("$1=REDACTED"); actual != "$1=REDACTED" {
		t.Fatalf("fallback replacement %q, want unchanged Java value", actual)
	}
	re := regexp.MustCompile("(qu|[b-df-hj-np-tv-z]*)([a-z]+)")
	if actual := re.ReplaceAllString("the", selectedReplacement("$2$1ay")); actual != "ethay" {
		t.Fatalf("replacement result %q, want %q", actual, "ethay")
	}
}
