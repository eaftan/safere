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
