// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Helper to traverse the {@link Regexp} AST at compile time and extract candidate literal strings
 * for Aho-Corasick pre-filtering.
 */
final class LiteralExtractor {

  /** Holds the results of a literal extraction pass. */
  public static class Result {
    /** The extracted literal search terms. */
    public final List<String> literals;

    /** Whether matching should be case-insensitive (case-folded). */
    public final boolean isCaseInsensitive;

    /** Whether the pattern consists entirely of an alternation of literal strings. */
    public final boolean isPureLiteralAlternation;

    public Result(
        List<String> literals, boolean isCaseInsensitive, boolean isPureLiteralAlternation) {
      this.literals = List.copyOf(literals);
      this.isCaseInsensitive = isCaseInsensitive;
      this.isPureLiteralAlternation = isPureLiteralAlternation;
    }
  }

  /**
   * Inspects the given AST root and returns its extracted literals if eligible for pre-filtering,
   * or null otherwise.
   */
  public static Result extract(Regexp re) {
    if (re == null) {
      return null;
    }
    // Unwrap root capturing/non-capturing parens
    while (re.op == RegexpOp.CAPTURE || re.op == RegexpOp.NON_CAPTURE) {
      re = re.sub();
    }
    boolean caseFold = (re.flags & ParseFlags.FOLD_CASE) != 0;
    boolean[] caseFoldOut = {caseFold};

    // Check if the root is an alternation of prefix patterns
    if (re.op == RegexpOp.ALTERNATE) {
      List<String> list = new ArrayList<>();
      for (Regexp sub : re.subs) {
        String s = tryExtractPrefix(sub, caseFold, caseFoldOut);
        if (s != null && s.length() >= 2) {
          list.add(s);
        } else {
          return null; // Reject pre-filtering if any branch prefix is not length >= 2
        }
      }
      return new Result(list, caseFoldOut[0], true);
    }

    // Check if the root is a concatenation starting with an alternation
    if (re.op == RegexpOp.CONCAT && re.subs != null && !re.subs.isEmpty()) {
      Regexp first = re.subs.get(0);
      while (first.op == RegexpOp.CAPTURE || first.op == RegexpOp.NON_CAPTURE) {
        first = first.sub();
      }
      if (first.op == RegexpOp.ALTERNATE) {
        List<String> list = new ArrayList<>();
        for (Regexp sub : first.subs) {
          String s = tryExtractPrefix(sub, caseFold, caseFoldOut);
          if (s != null && s.length() >= 2) {
            list.add(s);
          } else {
            list = null;
            break;
          }
        }
        if (list != null) {
          return new Result(list, caseFoldOut[0], true);
        }
      }
    }

    // Try to extract a single literal prefix
    String s = tryExtractPrefix(re, caseFold, caseFoldOut);
    if (s != null && s.length() >= 2) {
      return new Result(List.of(s), caseFoldOut[0], true);
    }

    // Traverse AST to extract any required substring literal
    List<String> substrings = new ArrayList<>();
    findRequiredSubstrings(re, substrings, caseFold, caseFoldOut);
    if (!substrings.isEmpty()) {
      return new Result(substrings, caseFoldOut[0], false);
    }

    return null;
  }

  private static String tryExtractLiteral(
      Regexp re, boolean parentCaseFold, boolean[] caseFoldOut) {
    if (re == null) {
      return null;
    }
    boolean currentCaseFold = parentCaseFold || (re.flags & ParseFlags.FOLD_CASE) != 0;
    if (currentCaseFold) {
      caseFoldOut[0] = true;
    }

    if (re.op == RegexpOp.LITERAL) {
      String s = new String(Character.toChars(re.rune));
      return currentCaseFold ? s.toLowerCase(Locale.ROOT) : s;
    } else if (re.op == RegexpOp.LITERAL_STRING) {
      StringBuilder sb = new StringBuilder();
      for (int r : re.runes) {
        sb.append(Character.toChars(r));
      }
      String s = sb.toString();
      return currentCaseFold ? s.toLowerCase(Locale.ROOT) : s;
    } else if (re.op == RegexpOp.CONCAT) {
      StringBuilder sb = new StringBuilder();
      for (Regexp sub : re.subs) {
        String s = tryExtractLiteral(sub, currentCaseFold, caseFoldOut);
        if (s == null) {
          return null; // Contains non-literal sub-expression
        }
        sb.append(s);
      }
      return sb.toString();
    } else if (re.op == RegexpOp.CAPTURE || re.op == RegexpOp.NON_CAPTURE) {
      return tryExtractLiteral(re.sub(), currentCaseFold, caseFoldOut);
    }
    return null;
  }

  private static String tryExtractPrefix(Regexp re, boolean parentCaseFold, boolean[] caseFoldOut) {
    if (re == null) {
      return null;
    }
    boolean currentCaseFold = parentCaseFold || (re.flags & ParseFlags.FOLD_CASE) != 0;
    if (currentCaseFold) {
      caseFoldOut[0] = true;
    }

    if (re.op == RegexpOp.LITERAL) {
      String s = new String(Character.toChars(re.rune));
      return currentCaseFold ? s.toLowerCase(Locale.ROOT) : s;
    } else if (re.op == RegexpOp.LITERAL_STRING) {
      StringBuilder sb = new StringBuilder();
      for (int r : re.runes) {
        sb.append(Character.toChars(r));
      }
      String s = sb.toString();
      return currentCaseFold ? s.toLowerCase(Locale.ROOT) : s;
    } else if (re.op == RegexpOp.CONCAT) {
      StringBuilder sb = new StringBuilder();
      for (Regexp sub : re.subs) {
        String s = tryExtractLiteral(sub, currentCaseFold, caseFoldOut);
        if (s != null) {
          sb.append(s);
        } else {
          String prefix = tryExtractPrefix(sub, currentCaseFold, caseFoldOut);
          if (prefix != null) {
            sb.append(prefix);
          }
          break; // Stop at first non-pure-literal child
        }
      }
      return sb.length() > 0 ? sb.toString() : null;
    } else if (re.op == RegexpOp.CAPTURE || re.op == RegexpOp.NON_CAPTURE) {
      return tryExtractPrefix(re.sub(), currentCaseFold, caseFoldOut);
    }
    return null;
  }

  private static void findRequiredSubstrings(
      Regexp re, List<String> acc, boolean parentCaseFold, boolean[] caseFoldOut) {
    if (re == null) {
      return;
    }
    boolean currentCaseFold = parentCaseFold || (re.flags & ParseFlags.FOLD_CASE) != 0;
    if (currentCaseFold) {
      caseFoldOut[0] = true;
    }
    String s = tryExtractPrefix(re, currentCaseFold, caseFoldOut);
    if (s != null && s.length() >= 2) {
      acc.add(s);
      return;
    }

    // We do not traverse ALTERNATE children for *required* substrings since branches are optional.
    if (re.op == RegexpOp.ALTERNATE) {
      return;
    }

    // Restrict traversal to operators that guarantee execution: CONCAT, CAPTURE, NON_CAPTURE.
    if (re.op == RegexpOp.CONCAT) {
      for (Regexp sub : re.subs) {
        findRequiredSubstrings(sub, acc, currentCaseFold, caseFoldOut);
      }
    } else if (re.op == RegexpOp.CAPTURE || re.op == RegexpOp.NON_CAPTURE) {
      findRequiredSubstrings(re.sub(), acc, currentCaseFold, caseFoldOut);
    }
  }

  private LiteralExtractor() {}
}
