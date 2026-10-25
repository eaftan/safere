// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.regex.PatternSyntaxException;

/**
 * Streaming source interpretation for {@link Parser}. Only this class reads the original source.
 * Tokens are borrowed until the next read; no object is allocated for an individual source rune.
 * The parser owns flag scope and updates this lexer before scanning beyond a group boundary.
 */
final class RegexLexer {
  enum Kind {
    END,
    LITERAL,
    DOT,
    CARET,
    DOLLAR,
    OPEN,
    CLOSE,
    ALTERNATION,
    CLASS_OPEN,
    CLASS_CLOSE,
    HYPHEN,
    AMPERSAND,
    INTERSECTION,
    PROPERTY,
    PREDEFINED,
    BOUNDARY,
    BEGIN,
    END_TEXT,
    FINAL_END,
    LINEBREAK,
    GRAPHEME,
    GRAPHEME_BOUNDARY,
    REPEAT,
    FLAGS,
    SCOPED_FLAGS,
    NONCAPTURE
  }

  enum RepetitionMode {
    GREEDY,
    RELUCTANT,
    POSSESSIVE
  }

  /** Mutable token storage owned by the lexer, including original UTF-16 source coordinates. */
  static final class Token {
    Kind kind;
    int start;
    int end;
    int rune;
    int min;
    int max;
    int flags;
    String text;
    boolean negated;
    boolean leadingTrivia;
    RepetitionMode mode;
  }

  private final String source;
  private int flags;
  private int sourcePosition;
  private boolean quoting;
  private boolean firstQuotedRune;
  private int quoteStart;
  private boolean escapedSourceRune;
  private boolean commentQuoting;
  // Bounded logical-character lookahead. Quoted punctuation contributes an escaped pair; a
  // leading quoted ASCII digit contributes a hexadecimal escape, preventing numeric ambiguity.
  private final int[] runes = new int[8];
  private final int[] starts = new int[8];
  private final int[] ends = new int[8];
  private int head;
  private int buffered;
  private final int[] pending = new int[4];
  private int pendingIndex;
  private int pendingCount;
  private int pendingStart;
  private int pendingEnd;
  private int lastEnd;
  private final Token token = new Token();
  private final Token classToken = new Token();
  private boolean classTokenReady;
  // At most one decoded Unicode escape can be held while deciding whether a high surrogate
  // forms a pair. This lets the ordinary component reader consume arbitrarily long trivia once.
  private boolean unicodeUnitReady;
  private int unicodeUnit;
  private int unicodeStart;
  private int unicodeEnd;

  RegexLexer(String source, int flags) {
    this.source = source;
    this.flags = flags;
  }

  void setFlags(int flags) {
    if (classTokenReady) {
      throw new IllegalStateException("lexical mode changed with an outstanding class token");
    }
    this.flags = flags;
  }

  boolean sawCommentQuoting() {
    return commentQuoting;
  }

  int length() {
    return source.length();
  }

  PatternSyntaxException error(String description, int index) {
    return new PatternSyntaxException(description, source, index);
  }

  PatternSyntaxException classError(String description, int index) {
    return new ClassSyntaxException(description, source, index);
  }

  static boolean isClassSyntaxError(PatternSyntaxException error) {
    return error instanceof ClassSyntaxException;
  }

  private static final class ClassSyntaxException extends PatternSyntaxException {
    private static final long serialVersionUID = 1L;

    ClassSyntaxException(String description, String pattern, int index) {
      super(description, pattern, index);
    }
  }

  Token next() {
    scan(token, false, false);
    return token;
  }

  Token peekClass() {
    if (!classTokenReady) {
      scan(classToken, true, false);
      classTokenReady = true;
    }
    return classToken;
  }

  /** Reads one scalar endpoint before recognizing any following class operator. */
  Token peekClassRangeEndpoint() {
    if (classTokenReady) {
      throw new IllegalStateException("range endpoint requested after expression lookahead");
    }
    scan(classToken, true, true);
    classTokenReady = true;
    return classToken;
  }

  Token takeClass() {
    Token result = peekClass();
    classTokenReady = false;
    return result;
  }

  private void scan(Token out, boolean inClass, boolean rangeEndpoint) {
    out.text = null;
    out.negated = false;
    out.mode = RepetitionMode.GREEDY;
    out.leadingTrivia = false;
    out.end = -1;
    if (unicodeUnitReady) {
      out.kind = Kind.LITERAL;
      out.start = unicodeStart;
      out.end = unicodeEnd;
      int unit = unicodeUnit;
      unicodeUnitReady = false;
      unicodeTail(out, unit);
      return;
    }
    if ((flags & ParseFlags.LITERAL) != 0) {
      out.start = sourcePosition;
      if (sourcePosition == source.length()) {
        out.kind = Kind.END;
      } else {
        out.kind = Kind.LITERAL;
        out.rune = source.codePointAt(sourcePosition);
        sourcePosition += Character.charCount(out.rune);
      }
      out.end = sourcePosition;
      return;
    }
    out.leadingTrivia = skipTrivia();
    int c = peekRaw();
    out.start = position();
    readRaw();
    out.rune = c;
    if (c < 0) {
      out.kind = Kind.END;
    } else if (c == '\\') {
      escape(out, inClass);
    } else if (inClass) {
      out.kind =
          switch (c) {
            case '[' -> Kind.CLASS_OPEN;
            case ']' -> Kind.CLASS_CLOSE;
            case '-' -> Kind.HYPHEN;
            case '^' -> Kind.CARET;
            default -> Kind.LITERAL;
          };
      if (c == '&' && !rangeEndpoint) {
        out.kind = Kind.AMPERSAND;
        skipTrivia();
        if (peekRaw() == '&') {
          readRaw();
          out.kind = Kind.INTERSECTION;
        }
      }
    } else {
      out.kind =
          switch (c) {
            case '(' -> Kind.OPEN;
            case ')' -> Kind.CLOSE;
            case '|' -> Kind.ALTERNATION;
            case '[' -> Kind.CLASS_OPEN;
            case '^' -> Kind.CARET;
            case '$' -> Kind.DOLLAR;
            case '.' -> Kind.DOT;
            case '*', '+', '?', '{' -> Kind.REPEAT;
            default -> Kind.LITERAL;
          };
      if (out.kind == Kind.REPEAT) repetition(out, c);
      if (out.kind == Kind.OPEN && (flags & ParseFlags.PERL_X) != 0) {
        skipTrivia();
        if (peekRaw() == '?') {
          readRaw();
          groupHeader(out);
        }
      }
    }
    if (out.end < 0) out.end = c < 0 ? source.length() : lastEnd;
  }

  private void repetition(Token out, int operator) {
    if (operator == '{') {
      out.min = decimal();
      out.max = out.min;
      skipTrivia();
      if (peekRaw() == ',') {
        readRaw();
        skipTrivia();
        out.max = peekRaw() == '}' ? -1 : decimal();
      }
      skipTrivia();
      if (out.min < 0 || out.max < -1 || readRaw() != '}') {
        throw error("Illegal repetition", out.start);
      }
    } else {
      out.min = operator == '+' ? 1 : 0;
      out.max = operator == '?' ? 1 : -1;
    }
    if ((flags & ParseFlags.PERL_X) != 0) {
      skipTrivia();
      if (peekRaw() == '?' || peekRaw() == '+') {
        out.mode = readRaw() == '?' ? RepetitionMode.RELUCTANT : RepetitionMode.POSSESSIVE;
      }
    }
  }

  private int decimal() {
    if (!Utils.isDigit(peekRaw())) return -2;
    int n = 0;
    while (Utils.isDigit(peekRaw())) {
      if (n >= 100_000_000) throw error("invalid repeat count", position());
      n = n * 10 + readRaw() - '0';
      skipTrivia();
    }
    return n;
  }

  private void escape(Token out, boolean inClass) {
    int c = readRaw();
    if (c < 0) throw error("trailing backslash", out.start);
    out.kind = Kind.LITERAL;
    switch (c) {
      case 'p', 'P' -> {
        if ((flags & ParseFlags.UNICODE_GROUPS) == 0) {
          throw error("invalid escape sequence", out.start);
        }
        out.kind = Kind.PROPERTY;
        out.negated = c == 'P';
        skipTrivia();
        int first = readRaw();
        if (first < 0) throw error("invalid Unicode group", out.start);
        if (first == '{') {
          skipTrivia();
          out.text = payload('}', "invalid Unicode group", out.start);
        } else {
          out.text = new String(Character.toChars(first));
        }
      }
      case 'd', 'D', 'h', 'H', 's', 'S', 'v', 'V', 'w', 'W' -> {
        if ((flags & ParseFlags.PERL_CLASSES) == 0) {
          throw error("invalid escape sequence", out.start);
        }
        out.kind = Kind.PREDEFINED;
        out.rune = Character.toLowerCase(c);
        out.negated = c >= 'A' && c <= 'Z';
      }
      case 'b', 'B' -> {
        if (inClass || (flags & ParseFlags.PERL_B) == 0) {
          throw error("invalid escape sequence", out.start);
        }
        out.kind = Kind.BOUNDARY;
        out.negated = c == 'B';
        if (c == 'b' && peekRaw() == '{' && peekRaw(1) == 'g' && peekRaw(2) == '}') {
          readRaw();
          readRaw();
          readRaw();
          out.kind = Kind.GRAPHEME_BOUNDARY;
        }
      }
      case 'A', 'z', 'Z', 'G' -> {
        if (inClass || (flags & ParseFlags.PERL_X) == 0 || c == 'G') {
          throw error(
              c == 'G' ? "\\G (end of previous match) is not supported" : "invalid escape sequence",
              out.start);
        }
        out.kind = c == 'A' ? Kind.BEGIN : c == 'z' ? Kind.END_TEXT : Kind.FINAL_END;
      }
      case 'R', 'X' -> {
        if (inClass) throw error("invalid escape sequence", out.start);
        out.kind = c == 'R' ? Kind.LINEBREAK : Kind.GRAPHEME;
      }
      case 'N' -> {
        if (readRaw() != '{') throw error("invalid escape sequence", out.start);
        String name = payload('}', "invalid escape sequence", out.start);
        try {
          out.rune = Character.codePointOf(name);
        } catch (IllegalArgumentException e) {
          PatternSyntaxException failure =
              error("unknown Unicode character name: " + name, out.start + 3);
          failure.initCause(e);
          throw failure;
        }
      }
      case '1', '2', '3', '4', '5', '6', '7', '8', '9' ->
          throw error("backreferences are not supported", out.start);
      case '0' -> {
        skipTrivia();
        if (peekRaw() < '0' || peekRaw() > '7')
          throw error("Illegal octal escape sequence", position());
        int code = 0;
        for (int i = 0; i < 3 && peekRaw() >= '0' && peekRaw() <= '7'; i++) {
          int next = code * 8 + peekRaw() - '0';
          if (next > 0377) break;
          readRaw();
          code = next;
          skipTrivia();
        }
        out.rune = code;
      }
      case 'x' -> {
        skipTrivia();
        if (peekRaw() == '{') {
          readRaw();
          int code = 0;
          int count = 0;
          skipTrivia();
          while (Utils.isHexDigit(peekRaw())) {
            code = code * 16 + Utils.unhex(readRaw());
            count++;
            if (code > Utils.MAX_RUNE) throw error("invalid escape sequence", position());
            skipTrivia();
          }
          if (count == 0 || readRaw() != '}') throw error("invalid escape sequence", position());
          out.rune = code;
        } else {
          out.rune = exactHex(2, out.start);
        }
      }
      case 'u' -> {
        int code = exactHex(4, out.start);
        out.end = lastEnd;
        unicodeTail(out, code);
      }
      case 'n' -> out.rune = '\n';
      case 'r' -> out.rune = '\r';
      case 't' -> out.rune = '\t';
      case 'a' -> out.rune = '\u0007';
      case 'e' -> out.rune = '\u001b';
      case 'f' -> out.rune = '\f';
      case 'c' -> {
        skipTrivia();
        int target = readRaw();
        if (target < 0) throw error("invalid escape sequence", out.start);
        out.rune = target ^ 0x40;
      }
      default -> {
        if (Utils.isAlpha(c)) throw error("invalid escape sequence", out.start);
        out.rune = c;
      }
    }
  }

  private void unicodeTail(Token out, int code) {
    out.rune = code;
    if (!Character.isHighSurrogate((char) code)) return;
    skipTrivia();
    if (peekRaw() != '\\' || peekRaw(1) != 'u') return;
    int start = position();
    readRaw();
    readRaw();
    int low = exactHex(4, start);
    if (Character.isLowSurrogate((char) low)) {
      out.rune = Character.toCodePoint((char) code, (char) low);
      out.end = lastEnd;
    } else {
      unicodeUnitReady = true;
      unicodeUnit = low;
      unicodeStart = start;
      unicodeEnd = lastEnd;
    }
  }

  private int exactHex(int digits, int start) {
    int code = 0;
    for (int i = 0; i < digits; i++) {
      skipTrivia();
      if (!Utils.isHexDigit(peekRaw())) throw error("invalid escape sequence", start);
      code = code * 16 + Utils.unhex(readRaw());
    }
    return code;
  }

  private String payload(int terminator, String message, int start) {
    StringBuilder result = new StringBuilder();
    while (peekRaw() >= 0 && peekRaw() != terminator) result.appendCodePoint(readRaw());
    if (readRaw() != terminator) throw error(message, start);
    return result.toString();
  }

  private void groupHeader(Token out) {
    // Recognize headers without scanning any of the following body. The parser commits/restores
    // flag scope before requesting that body's first token.
    skipTrivia();
    if (peekRaw() == '<' || peekRaw() == 'P') {
      boolean pythonName = readRaw() == 'P';
      if (pythonName && readRaw() != '<') throw error("invalid Perl operator", out.start);
      if (peekRaw() == '=' || peekRaw() == '!') throw error("invalid Perl operator", out.start);
      StringBuilder name = new StringBuilder();
      skipTrivia();
      while (peekRaw() >= 0 && peekRaw() != '>') {
        name.appendCodePoint(readRaw());
        skipTrivia();
      }
      if (readRaw() != '>') throw error("invalid named capture", out.start);
      out.text = name.toString();
      if (!validCaptureName(out.text, pythonName))
        throw error("invalid named capture: " + out.text, out.start);
      return;
    }
    if (peekRaw() == '=' || peekRaw() == '!' || peekRaw() == '>') {
      throw error("invalid Perl operator", out.start);
    }
    int nextFlags = flags;
    boolean negated = false;
    boolean sawFlags = false;
    while (true) {
      // A header's lexical mode changes as its flag letters are read, before its closing token.
      int previousFlags = flags;
      flags = nextFlags;
      skipTrivia();
      flags = previousFlags;
      int c = readRaw();
      int mask =
          switch (c) {
            case 'd' -> ParseFlags.UNIX_LINES;
            case 'i' -> ParseFlags.FOLD_CASE;
            case 'm' -> ParseFlags.ONE_LINE;
            case 's' -> ParseFlags.DOT_NL;
            case 'u' -> ParseFlags.UNICODE_CASE;
            case 'U' ->
                ParseFlags.UNICODE_CASE | ParseFlags.UNICODE_GROUPS | ParseFlags.UNICODE_CHAR_CLASS;
            case 'x' -> ParseFlags.COMMENTS;
            default -> 0;
          };
      if (mask != 0) {
        sawFlags = true;
        if (negated ^ (c == 'm')) nextFlags &= ~mask;
        else nextFlags |= mask;
      } else if (c == '-' && !negated) {
        negated = true;
        sawFlags = false;
      } else if ((c == ':' || c == ')') && (!negated || sawFlags)) {
        out.kind = c == ':' ? Kind.SCOPED_FLAGS : Kind.FLAGS;
        out.flags = nextFlags;
        return;
      } else {
        throw error("invalid Perl operator", out.start);
      }
    }
  }

  private static boolean validCaptureName(String name, boolean underscores) {
    if (name.isEmpty()) return false;
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (Utils.isAlpha(c) || (i > 0 && Utils.isDigit(c)) || (underscores && c == '_')) continue;
      return false;
    }
    return true;
  }

  private boolean skipTrivia() {
    if ((flags & ParseFlags.COMMENTS) == 0) return false;
    boolean skipped = false;
    while (true) {
      int c = peekRaw();
      if (c == ' ' || (c >= '\t' && c <= '\r')) {
        readRaw();
        skipped = true;
      } else if (c == '#') {
        // Comment text is discarded before quote interpretation (#858). Discard bounded
        // lookahead too, then scan the original comment once without interpreting its escapes.
        sourcePosition = starts[head] + 1;
        buffered = 0;
        pendingIndex = pendingCount = 0;
        quoting = false;
        escapedSourceRune = false;
        while (sourcePosition < source.length()) {
          if (WorkCounterConfig.ENABLED) WorkCounter.record();
          int cp = source.codePointAt(sourcePosition);
          if (cp == 0
              || cp == '\n'
              || ((flags & ParseFlags.UNIX_LINES) == 0 && Nfa.isLineTerminator(cp))) break;
          if (cp == '\\'
              && sourcePosition + 1 < source.length()
              && (source.charAt(sourcePosition + 1) == 'Q'
                  || source.charAt(sourcePosition + 1) == 'E')) {
            commentQuoting = true;
          }
          sourcePosition += Character.charCount(cp);
        }
        skipped = true;
      } else {
        return skipped;
      }
    }
  }

  private int position() {
    return buffered == 0 ? sourcePosition : starts[head];
  }

  private int peekRaw() {
    return peekRaw(0);
  }

  private int peekRaw(int offset) {
    while (buffered <= offset) {
      if (!fill()) return -1;
    }
    return runes[(head + offset) % runes.length];
  }

  private int readRaw() {
    int result = peekRaw();
    if (result >= 0) {
      lastEnd = ends[head];
      head = (head + 1) % runes.length;
      buffered--;
    }
    return result;
  }

  private boolean fill() {
    int value;
    int start;
    int end;
    while (true) {
      if (pendingIndex < pendingCount) {
        value = pending[pendingIndex++];
        start = pendingStart;
        end = pendingEnd;
        break;
      }
      if (sourcePosition == source.length()) return false;
      start = sourcePosition;
      if (WorkCounterConfig.ENABLED) WorkCounter.record();
      int cp = source.codePointAt(sourcePosition);
      sourcePosition += Character.charCount(cp);
      end = sourcePosition;
      if (quoting) {
        if (cp == '\\'
            && sourcePosition < source.length()
            && source.charAt(sourcePosition) == 'E') {
          sourcePosition++;
          quoting = false;
          continue;
        }
        boolean first = firstQuotedRune;
        firstQuotedRune = false;
        if (first) start = quoteStart;
        pendingStart = start;
        pendingEnd = end;
        pendingIndex = 0;
        if (first && cp >= '0' && cp <= '9') {
          pending[0] = '\\';
          pending[1] = 'x';
          pending[2] = '3';
          pending[3] = cp;
          pendingCount = 4;
        } else if (cp < 128 && !Utils.isAlpha(cp) && !Utils.isDigit(cp)) {
          pending[0] = '\\';
          pending[1] = cp;
          pendingCount = 2;
        } else {
          pendingCount = 0;
          value = cp;
          break;
        }
        continue;
      }
      if (escapedSourceRune) {
        escapedSourceRune = false;
      } else if (cp == '\\') {
        if ((flags & ParseFlags.PERL_X) != 0
            && sourcePosition < source.length()
            && source.charAt(sourcePosition) == 'Q') {
          sourcePosition++;
          quoting = true;
          firstQuotedRune = true;
          quoteStart = start;
          continue;
        }
        escapedSourceRune = true;
      }
      value = cp;
      break;
    }
    int tail = (head + buffered) % runes.length;
    runes[tail] = value;
    starts[tail] = start;
    ends[tail] = end;
    buffered++;
    return true;
  }
}
