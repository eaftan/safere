// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

// Thin C wrapper around C++ RE2 for use with Java FFM.
// All functions use C linkage so FFM can call them without C++ name mangling.

#ifndef RE2_SHIM_H_
#define RE2_SHIM_H_

#include <stddef.h>
#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Opaque handle to a compiled RE2 pattern.
typedef struct re2_pattern re2_pattern_t;

// Compile a UTF-8 pattern. Returns an opaque handle.
// The caller must call re2_free() when done.
re2_pattern_t* re2_compile(const char* pattern, int pattern_len);

// Compile with case-insensitive matching.
re2_pattern_t* re2_compile_case_insensitive(const char* pattern,
                                            int pattern_len);

// Free a compiled pattern.
void re2_free(re2_pattern_t* p);

// Returns true if the pattern compiled successfully.
bool re2_ok(const re2_pattern_t* p);

// Returns the error message (empty string if ok). The returned pointer is valid
// until re2_free() is called.
const char* re2_error(const re2_pattern_t* p);

// Returns the number of capturing groups in the pattern.
int re2_num_capturing_groups(const re2_pattern_t* p);

// Full match: returns true if the entire text matches the pattern.
bool re2_full_match(const re2_pattern_t* p, const char* text, int text_len);

// Find the next match starting at startpos. On success, writes match byte
// offsets into matches_out as [start0, end0, start1, end1, ...].
// nmatches is the number of groups to capture (0 = group 0 only, i.e. the
// whole match). The matches_out array must have space for 2*(nmatches+1) ints.
// Returns true if a match was found.
bool re2_find(const re2_pattern_t* p, const char* text, int text_len,
              int startpos, int32_t* matches_out, int nmatches);

// Replace all occurrences of the pattern in text with rewrite.
// Writes the result into out_buf (up to out_cap bytes) and sets *out_len
// to the actual result length. Returns the number of replacements made,
// or -1 if out_buf was too small (in which case *out_len contains the
// required size).
int re2_replace_all(const re2_pattern_t* p,
                    const char* text, int text_len,
                    const char* rewrite, int rewrite_len,
                    char* out_buf, int out_cap, int* out_len);

#ifdef __cplusplus
}
#endif

#endif  // RE2_SHIM_H_
