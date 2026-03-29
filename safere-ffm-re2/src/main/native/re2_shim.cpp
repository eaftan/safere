// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

#include "re2_shim.h"

#include <algorithm>
#include <cstring>
#include <string>
#include <vector>

#include "re2/re2.h"

struct re2_pattern {
  RE2* re2;
  std::string error_msg;
};

extern "C" {

re2_pattern_t* re2_compile(const char* pattern, int pattern_len) {
  auto* p = new re2_pattern();
  RE2::Options opts;
  opts.set_log_errors(false);
  p->re2 = new RE2(absl::string_view(pattern, pattern_len), opts);
  if (!p->re2->ok()) {
    p->error_msg = p->re2->error();
  }
  return p;
}

re2_pattern_t* re2_compile_case_insensitive(const char* pattern,
                                            int pattern_len) {
  auto* p = new re2_pattern();
  RE2::Options opts;
  opts.set_log_errors(false);
  opts.set_case_sensitive(false);
  p->re2 = new RE2(absl::string_view(pattern, pattern_len), opts);
  if (!p->re2->ok()) {
    p->error_msg = p->re2->error();
  }
  return p;
}

void re2_free(re2_pattern_t* p) {
  if (p) {
    delete p->re2;
    delete p;
  }
}

bool re2_ok(const re2_pattern_t* p) {
  return p && p->re2->ok();
}

const char* re2_error(const re2_pattern_t* p) {
  if (!p) return "null pattern";
  return p->error_msg.c_str();
}

int re2_num_capturing_groups(const re2_pattern_t* p) {
  if (!p || !p->re2->ok()) return -1;
  return p->re2->NumberOfCapturingGroups();
}

bool re2_full_match(const re2_pattern_t* p, const char* text, int text_len) {
  if (!p || !p->re2->ok()) return false;
  return p->re2->Match(absl::string_view(text, text_len),
                        0, text_len, RE2::ANCHOR_BOTH, nullptr, 0);
}

bool re2_find(const re2_pattern_t* p, const char* text, int text_len,
              int startpos, int32_t* matches_out, int nmatches) {
  if (!p || !p->re2->ok()) return false;

  int nsub = nmatches + 1;  // group 0 + nmatches capture groups
  std::vector<absl::string_view> submatch(nsub);

  bool found = p->re2->Match(absl::string_view(text, text_len),
                              startpos, text_len, RE2::UNANCHORED,
                              submatch.data(), nsub);
  if (!found) return false;

  for (int i = 0; i < nsub; i++) {
    if (submatch[i].data() == nullptr) {
      matches_out[2 * i] = -1;
      matches_out[2 * i + 1] = -1;
    } else {
      matches_out[2 * i] = static_cast<int32_t>(submatch[i].data() - text);
      matches_out[2 * i + 1] =
          static_cast<int32_t>(submatch[i].data() - text + submatch[i].size());
    }
  }
  return true;
}

int re2_replace_all(const re2_pattern_t* p,
                    const char* text, int text_len,
                    const char* rewrite, int rewrite_len,
                    char* out_buf, int out_cap, int* out_len) {
  if (!p || !p->re2->ok()) return -1;

  std::string str(text, text_len);
  int count = RE2::GlobalReplace(&str, *p->re2,
                                  absl::string_view(rewrite, rewrite_len));

  *out_len = static_cast<int>(str.size());
  if (static_cast<int>(str.size()) > out_cap) {
    return -1;  // buffer too small
  }
  std::memcpy(out_buf, str.data(), str.size());
  return count;
}

}  // extern "C"
