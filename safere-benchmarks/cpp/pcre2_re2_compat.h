// Copyright (c) 2026 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.
//
// Small RE2-shaped adapter used to run the shared native benchmark harness
// against PCRE2's 8-bit JIT API.

#ifndef SAFERE_BENCHMARKS_CPP_PCRE2_RE2_COMPAT_H_
#define SAFERE_BENCHMARKS_CPP_PCRE2_RE2_COMPAT_H_

#define PCRE2_CODE_UNIT_WIDTH 8
#include <pcre2.h>

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace re2 {

class StringPiece {
 public:
  StringPiece() = default;
  StringPiece(const std::string& value)
      : data_(value.data()), size_(value.size()) {}
  StringPiece(const char* data, size_t size) : data_(data), size_(size) {}

  const char* data() const { return data_; }
  size_t size() const { return size_; }
  bool empty() const { return size_ == 0; }

  void remove_prefix(size_t count) {
    count = std::min(count, size_);
    data_ += count;
    size_ -= count;
  }

  void set(const char* data, size_t size) {
    data_ = data;
    size_ = size;
  }

 private:
  const char* data_ = nullptr;
  size_t size_ = 0;
};

}  // namespace re2

class RE2 {
 public:
  enum Anchor {
    UNANCHORED,
    ANCHOR_START,
    ANCHOR_BOTH,
  };

  explicit RE2(const std::string& pattern) : pattern_(pattern) {
    int error_code = 0;
    PCRE2_SIZE error_offset = 0;
    code_ = pcre2_compile(
        reinterpret_cast<PCRE2_SPTR>(pattern.data()), pattern.size(),
        PCRE2_UTF, &error_code, &error_offset, nullptr);
    if (code_ == nullptr) {
      fail_compile("compilation", error_code, error_offset);
    }
    int jit_result = pcre2_jit_compile(code_, PCRE2_JIT_COMPLETE);
    if (jit_result != 0) {
      fail_compile("JIT compilation", jit_result, 0);
    }
    PCRE2_SIZE jit_size = 0;
    if (pcre2_pattern_info(code_, PCRE2_INFO_JITSIZE, &jit_size) != 0 ||
        jit_size == 0) {
      fail_compile("JIT code generation", PCRE2_ERROR_JIT_UNSUPPORTED, 0);
    }
    jit_size_ = jit_size;
    match_data_ = pcre2_match_data_create_from_pattern(code_, nullptr);
    if (match_data_ == nullptr) {
      fail_compile("match-data allocation", PCRE2_ERROR_NOMEMORY, 0);
    }
  }

  ~RE2() {
    pcre2_match_data_free(end_anchored_match_data_);
    pcre2_code_free(end_anchored_code_);
    pcre2_match_data_free(match_data_);
    pcre2_code_free(code_);
  }

  RE2(const RE2&) = delete;
  RE2& operator=(const RE2&) = delete;
  RE2(RE2&&) = delete;
  RE2& operator=(RE2&&) = delete;

  bool ok() const { return code_ != nullptr && match_data_ != nullptr; }
  size_t jit_size() const { return jit_size_; }

  bool Match(const std::string& text, size_t start, size_t end,
             Anchor anchor, re2::StringPiece* matches, int match_count) const {
    if (!ok() || start > end || end > text.size()) {
      return false;
    }
    pcre2_code* code = code_;
    pcre2_match_data* match_data = match_data_;
    if (anchor == ANCHOR_BOTH) {
      ensure_end_anchored_code();
      code = end_anchored_code_;
      match_data = end_anchored_match_data_;
    }
    uint32_t options = anchor == UNANCHORED ? 0 : PCRE2_ANCHORED;
    int result = pcre2_jit_match(
        code, reinterpret_cast<PCRE2_SPTR>(text.data()), end, start,
        options, match_data, nullptr);
    if (result < 0) {
      return handle_match_failure(result);
    }
    PCRE2_SIZE* offsets = pcre2_get_ovector_pointer(match_data);
    if (anchor == ANCHOR_BOTH && offsets[1] != end) {
      return false;
    }
    fill_matches(text.data(), matches, match_count, result, offsets);
    return true;
  }

  static bool FullMatch(const std::string& text, const RE2& pattern) {
    return pattern.Match(text, 0, text.size(), ANCHOR_BOTH, nullptr, 0);
  }

  template <typename... Captures>
  static bool FullMatch(const std::string& text, const RE2& pattern,
                        Captures*... captures) {
    constexpr int capture_count = sizeof...(Captures);
    std::array<re2::StringPiece, capture_count + 1> matches;
    if (!pattern.Match(
            text, 0, text.size(), ANCHOR_BOTH, matches.data(), matches.size())) {
      return false;
    }
    assign_captures(matches, 1, captures...);
    return true;
  }

  static bool PartialMatch(const std::string& text, const RE2& pattern) {
    return pattern.Match(text, 0, text.size(), UNANCHORED, nullptr, 0);
  }

  template <typename... Captures>
  static bool PartialMatch(const std::string& text, const RE2& pattern,
                           Captures*... captures) {
    constexpr int capture_count = sizeof...(Captures);
    std::array<re2::StringPiece, capture_count + 1> matches;
    if (!pattern.Match(
            text, 0, text.size(), UNANCHORED, matches.data(), matches.size())) {
      return false;
    }
    assign_captures(matches, 1, captures...);
    return true;
  }

  static bool FindAndConsume(
      re2::StringPiece* input, const RE2& pattern, std::string* capture) {
    if (input == nullptr) {
      return false;
    }
    std::string_view subject(input->data(), input->size());
    int result = pattern.match(subject, 0, false);
    if (result < 0) {
      return handle_match_failure(result);
    }
    PCRE2_SIZE* offsets = pcre2_get_ovector_pointer(pattern.match_data_);
    int selected = result > 1 ? 1 : 0;
    if (capture != nullptr) {
      if (offsets[2 * selected] == PCRE2_UNSET) {
        capture->clear();
      } else {
        capture->assign(
            subject.data() + offsets[2 * selected],
            offsets[2 * selected + 1] - offsets[2 * selected]);
      }
    }
    input->remove_prefix(offsets[1]);
    return true;
  }

  static bool Replace(
      std::string* text, const RE2& pattern, const std::string& replacement) {
    return replace(text, pattern, replacement, false) > 0;
  }

  static int GlobalReplace(
      std::string* text, const RE2& pattern, const std::string& replacement) {
    return replace(text, pattern, replacement, true);
  }

 private:
  [[noreturn]] static void fail_compile(
      const char* stage, int error_code, PCRE2_SIZE error_offset) {
    PCRE2_UCHAR message[256];
    int message_result =
        pcre2_get_error_message(error_code, message, sizeof(message));
    if (message_result >= 0) {
      fprintf(
          stderr, "ERROR: PCRE2 %s failed at offset %zu: %s (%d)\n",
          stage, static_cast<size_t>(error_offset),
          reinterpret_cast<const char*>(message), error_code);
    } else {
      fprintf(
          stderr, "ERROR: PCRE2 %s failed at offset %zu: %d\n",
          stage, static_cast<size_t>(error_offset), error_code);
    }
    exit(1);
  }

  int match(std::string_view text, size_t start, bool anchored) const {
    if (!ok() || start > text.size()) {
      return PCRE2_ERROR_NOMATCH;
    }
    return pcre2_jit_match(
        code_, reinterpret_cast<PCRE2_SPTR>(text.data()), text.size(), start,
        anchored ? PCRE2_ANCHORED : 0, match_data_, nullptr);
  }

  void ensure_end_anchored_code() const {
    if (end_anchored_code_ != nullptr) {
      return;
    }
    int error_code = 0;
    PCRE2_SIZE error_offset = 0;
    end_anchored_code_ = pcre2_compile(
        reinterpret_cast<PCRE2_SPTR>(pattern_.data()), pattern_.size(),
        PCRE2_UTF | PCRE2_ENDANCHORED, &error_code, &error_offset, nullptr);
    if (end_anchored_code_ == nullptr) {
      fail_compile("end-anchored compilation", error_code, error_offset);
    }
    int jit_result =
        pcre2_jit_compile(end_anchored_code_, PCRE2_JIT_COMPLETE);
    if (jit_result != 0) {
      fail_compile("end-anchored JIT compilation", jit_result, 0);
    }
    PCRE2_SIZE jit_size = 0;
    if (pcre2_pattern_info(
            end_anchored_code_, PCRE2_INFO_JITSIZE, &jit_size) != 0 ||
        jit_size == 0) {
      fail_compile(
          "end-anchored JIT code generation",
          PCRE2_ERROR_JIT_UNSUPPORTED, 0);
    }
    end_anchored_match_data_ =
        pcre2_match_data_create_from_pattern(end_anchored_code_, nullptr);
    if (end_anchored_match_data_ == nullptr) {
      fail_compile(
          "end-anchored match-data allocation", PCRE2_ERROR_NOMEMORY, 0);
    }
  }

  static bool handle_match_failure(int result) {
    if (result == PCRE2_ERROR_NOMATCH) {
      return false;
    }
    PCRE2_UCHAR message[256];
    int message_result =
        pcre2_get_error_message(result, message, sizeof(message));
    if (message_result >= 0) {
      fprintf(
          stderr, "ERROR: PCRE2 JIT matching failed: %s (%d)\n",
          reinterpret_cast<const char*>(message), result);
    } else {
      fprintf(stderr, "ERROR: PCRE2 JIT matching failed: %d\n", result);
    }
    exit(1);
  }

  static void fill_matches(
      const char* text, re2::StringPiece* matches, int match_count,
      int result, const PCRE2_SIZE* offsets) {
    if (matches == nullptr) {
      return;
    }
    for (int index = 0; index < match_count; ++index) {
      if (index >= result || offsets[2 * index] == PCRE2_UNSET) {
        matches[index].set(nullptr, 0);
      } else {
        matches[index].set(
            text + offsets[2 * index],
            offsets[2 * index + 1] - offsets[2 * index]);
      }
    }
  }

  template <size_t Size>
  static void assign_captures(
      const std::array<re2::StringPiece, Size>&, size_t) {}

  template <size_t Size, typename... Remaining>
  static void assign_captures(
      const std::array<re2::StringPiece, Size>& matches, size_t index,
      std::string* capture, Remaining*... remaining) {
    if (capture != nullptr) {
      const re2::StringPiece& match = matches[index];
      if (match.data() == nullptr) {
        capture->clear();
      } else {
        capture->assign(match.data(), match.size());
      }
    }
    assign_captures(matches, index + 1, remaining...);
  }

  static int replace(
      std::string* text, const RE2& pattern, const std::string& replacement,
      bool global) {
    if (text == nullptr || !pattern.ok()) {
      return 0;
    }
    uint32_t options =
        PCRE2_SUBSTITUTE_OVERFLOW_LENGTH |
        (global ? PCRE2_SUBSTITUTE_GLOBAL : 0);
    PCRE2_SIZE output_length =
        text->size() * 2 + replacement.size() + 1;
    std::vector<PCRE2_UCHAR> output(output_length);
    int result = pcre2_substitute(
        pattern.code_, reinterpret_cast<PCRE2_SPTR>(text->data()),
        text->size(), 0, options, pattern.match_data_, nullptr,
        reinterpret_cast<PCRE2_SPTR>(replacement.data()), replacement.size(),
        output.data(), &output_length);
    if (result == PCRE2_ERROR_NOMEMORY) {
      output.resize(output_length);
      result = pcre2_substitute(
          pattern.code_, reinterpret_cast<PCRE2_SPTR>(text->data()),
          text->size(), 0, options, pattern.match_data_, nullptr,
          reinterpret_cast<PCRE2_SPTR>(replacement.data()), replacement.size(),
          output.data(), &output_length);
    }
    if (result < 0) {
      handle_match_failure(result);
    }
    text->assign(reinterpret_cast<const char*>(output.data()), output_length);
    return result;
  }

  pcre2_code* code_ = nullptr;
  pcre2_match_data* match_data_ = nullptr;
  std::string pattern_;
  mutable pcre2_code* end_anchored_code_ = nullptr;
  mutable pcre2_match_data* end_anchored_match_data_ = nullptr;
  size_t jit_size_ = 0;
};

#endif  // SAFERE_BENCHMARKS_CPP_PCRE2_RE2_COMPAT_H_
