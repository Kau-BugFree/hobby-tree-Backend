package ssmtest

import (
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

// Test the real document with Go's regexp parser: Python accepts {1,4094},
// but SSM rejected that counted repetition before creating the document.
func TestMainReleaseSSMPattern(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("..", "..", "deploy", "main-release", "HobbyTree-Main-Release.json"))
	if err != nil {
		t.Fatal(err)
	}
	var doc struct {
		Parameters map[string]struct {
			AllowedPattern string `json:"allowedPattern"`
			Interpolation  string `json:"interpolationType"`
		} `json:"parameters"`
	}
	if err := json.Unmarshal(raw, &doc); err != nil {
		t.Fatal(err)
	}
	parameter := doc.Parameters["ReleasePayload"]
	if parameter.Interpolation != "ENV_VAR" {
		t.Fatal("release payload must remain an environment variable")
	}
	pattern, err := regexp.Compile(parameter.AllowedPattern)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := regexp.Compile(`^[A-Za-z0-9+/]{1,4094}={0,2}$`); err == nil {
		t.Fatal("regression fixture no longer reproduces the SSM parser failure")
	}
	// Independent contract: 1..4094 base64 characters, then 0..2 '='.
	// Check every length across all five branches, including just over the limit.
	for length := 0; length <= 4100; length++ {
		for padding := 0; padding <= 3; padding++ {
			input := strings.Repeat("A", length) + strings.Repeat("=", padding)
			want := length >= 1 && length <= 4094 && padding <= 2
			if got := pattern.MatchString(input); got != want {
				t.Fatalf("length=%d padding=%d: got %v want %v", length, padding, got, want)
			}
		}
	}
	for _, input := range []string{
		"AaZz09+/", base64.StdEncoding.EncodeToString([]byte(`{"format":1,"request":"123-1"}`)),
	} {
		if !pattern.MatchString(input) {
			t.Fatalf("valid payload rejected: %q", input)
		}
	}
	for _, invalid := range []string{" ", "\n", "\r", "\t", "\x00", "-", "_", ";", "$", "'", "\"", "한", "😀", "="} {
		for _, input := range []string{invalid + "AA", "A" + invalid + "A", "AA" + invalid + "A"} {
			if pattern.MatchString(input) {
				t.Fatalf("invalid payload accepted: %q", input)
			}
		}
	}
	for _, input := range []string{"AA\n", "AA==\n", strings.Repeat("A", 10000)} {
		if pattern.MatchString(input) {
			t.Fatal("trailing newline or oversized payload accepted")
		}
	}
}
