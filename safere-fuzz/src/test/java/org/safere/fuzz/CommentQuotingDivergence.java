// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;

/** Recognizes the documented #858 quoting-in-comments family for differential fuzzing. */
final class CommentQuotingDivergence {
  private CommentQuotingDivergence() {}

  /**
   * Scans lexical context once, without compiling or matching a second pattern. A quote opener in
   * ignored comment text makes the JDK oracle unsuitable. Conservatively includes quotes closed
   * within the same comment, even when that particular pattern would agree.
   */
  static boolean contains(String regex, int flags) {
    if ((flags & Pattern.LITERAL) != 0) {
      return false;
    }
    Deque<Integer> groups = new ArrayDeque<>();
    // 0 = before optional negation, 1 = after negation, 2 = after the first atom.
    // A closing bracket is literal before the first atom.
    Deque<Integer> classStarts = new ArrayDeque<>();
    boolean quoted = false;
    boolean comment = false;
    boolean controlOperand = false;
    for (int i = 0; i < regex.length(); ) {
      int c = regex.codePointAt(i);
      i += Character.charCount(c);
      if (comment) {
        if (isCommentTerminator(c, flags)) {
          comment = false;
        } else {
          // JDK quote preprocessing pairs backslashes even inside comments.
          if (c == '\\' && i < regex.length()) {
            int next = regex.codePointAt(i);
            if (next == 'Q') {
              return true;
            }
            if (next == '\\') {
              i++;
            }
          }
          continue;
        }
      }
      if (quoted) {
        if (c == '\\' && i < regex.length() && regex.charAt(i) == 'E') {
          quoted = false;
          i++;
        } else {
          consumeClassAtom(classStarts);
        }
        continue;
      }
      if ((flags & Pattern.COMMENTS) != 0) {
        if (c == '#') {
          comment = true;
          continue;
        }
        if (c == ' ' || (c >= '\t' && c <= '\r')) {
          continue;
        }
      }
      // COMMENTS trivia can occur between a control escape and its operand.
      if (controlOperand) {
        controlOperand = false;
        consumeClassAtom(classStarts);
        continue;
      }
      if (c == '\\' && i < regex.length()) {
        int escaped = regex.codePointAt(i);
        i += Character.charCount(escaped);
        if (escaped == 'Q') {
          quoted = true;
        } else {
          controlOperand = escaped == 'c';
          consumeClassAtom(classStarts);
        }
      } else if (c == '[') {
        consumeClassAtom(classStarts);
        classStarts.push(0);
      } else if (!classStarts.isEmpty()) {
        if (c == ']' && classStarts.peek() == 2) {
          classStarts.pop();
        } else if (c == '^' && classStarts.peek() == 0) {
          classStarts.pop();
          classStarts.push(1);
        } else {
          consumeClassAtom(classStarts);
        }
      } else if (c == '(') {
        FlagGroup header = readFlagGroup(regex, i, flags);
        if (header == null) {
          groups.push(flags);
        } else {
          if (header.scoped()) {
            groups.push(flags);
          }
          flags = header.flags();
          i = header.end();
        }
      } else if (c == ')' && !groups.isEmpty()) {
        flags = groups.pop();
      }
    }
    return false;
  }

  private static void consumeClassAtom(Deque<Integer> classStarts) {
    if (!classStarts.isEmpty()) {
      classStarts.pop();
      classStarts.push(2);
    }
  }

  private static boolean isCommentTerminator(int c, int flags) {
    return c == 0
        || c == '\n'
        || ((flags & Pattern.UNIX_LINES) == 0
            && (c == '\r' || c == 0x85 || c == 0x2028 || c == 0x2029));
  }

  private static FlagGroup readFlagGroup(String regex, int start, int flags) {
    if (start == regex.length() || regex.charAt(start) != '?') {
      return null;
    }
    boolean disabled = false;
    for (int i = start + 1; i < regex.length(); i++) {
      char c = regex.charAt(i);
      if (c == ':' || c == ')') {
        return new FlagGroup(i + 1, flags, c == ':');
      }
      if (c == '-' && !disabled) {
        disabled = true;
      } else if ("idmsuxU".indexOf(c) >= 0) {
        int bit = c == 'x' ? Pattern.COMMENTS : c == 'd' ? Pattern.UNIX_LINES : 0;
        flags = disabled ? flags & ~bit : flags | bit;
      } else {
        return null;
      }
    }
    return null;
  }

  private record FlagGroup(int end, int flags, boolean scoped) {}
}
