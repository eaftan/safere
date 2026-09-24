// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Objects;

/**
 * Immutable descriptor carrying the prefilter and start-acceleration plans extracted from a regular
 * expression AST: a {@link StartPlan} describing where a match may begin and a {@link RejectPlan}
 * describing what the input must contain for any match to be possible.
 */
final class MultiAnchorDescriptor {

  private final boolean startAnchored;
  private final StartPlan startPlan;
  private final RejectPlan rejectPlan;
  private final String anchoredPrefix;
  private final CharClassScanInfo anchoredCharClassPrefix;

  public static final MultiAnchorDescriptor NONE =
      new MultiAnchorDescriptor(false, StartPlan.None.INSTANCE, RejectPlan.None.INSTANCE);

  MultiAnchorDescriptor(boolean startAnchored, StartPlan startPlan, RejectPlan rejectPlan) {
    this(startAnchored, startPlan, rejectPlan, null, null);
  }

  MultiAnchorDescriptor(
      boolean startAnchored,
      StartPlan startPlan,
      RejectPlan rejectPlan,
      String anchoredPrefix,
      CharClassScanInfo anchoredCharClassPrefix) {
    this.startAnchored = startAnchored;
    this.startPlan = Objects.requireNonNull(startPlan, "startPlan");
    this.rejectPlan = Objects.requireNonNull(rejectPlan, "rejectPlan");
    this.anchoredPrefix = anchoredPrefix;
    this.anchoredCharClassPrefix = anchoredCharClassPrefix;
  }

  StartPlan startPlan() {
    return startPlan;
  }

  RejectPlan rejectPlan() {
    return rejectPlan;
  }

  String anchoredPrefix() {
    return anchoredPrefix;
  }

  CharClassScanInfo anchoredCharClassPrefix() {
    return anchoredCharClassPrefix;
  }

  String prefix() {
    if (startAnchored) {
      return null;
    }
    return startPlan instanceof StartPlan.Literal lit ? lit.prefix() : null;
  }

  boolean prefixFoldCase() {
    if (startAnchored) {
      return false;
    }
    return startPlan instanceof StartPlan.Literal lit && lit.foldCase();
  }

  CharClassScanInfo charClassPrefix() {
    if (startAnchored) {
      return null;
    }
    return startPlan instanceof StartPlan.CharClass cc ? cc.scanInfo() : null;
  }

  sealed interface StartPlan {
    record None() implements StartPlan {
      static final None INSTANCE = new None();
    }

    record Literal(String prefix, boolean foldCase) implements StartPlan {
      public Literal {
        Objects.requireNonNull(prefix, "prefix");
      }
    }

    record CharClass(CharClassScanInfo scanInfo) implements StartPlan {
      public CharClass {
        Objects.requireNonNull(scanInfo, "scanInfo");
      }
    }

    record FixedOffset(Pattern.FixedOffsetLiteral fol, CharClassScanInfo leadingClass)
        implements StartPlan {
      public FixedOffset {
        Objects.requireNonNull(fol, "fol");
      }
    }

    @SuppressWarnings("ArrayRecordComponent")
    record MultiLiteral(String[] literals, CharClassScanInfo fallbackClass) implements StartPlan {
      public MultiLiteral {
        Objects.requireNonNull(literals, "literals");
      }
    }

    record LeadingExpansion(
        CharClassScanInfo leadingClass,
        int minRepetition,
        int maxRepetition,
        boolean hasLeadingAssertions,
        StartPlan innerPlan)
        implements StartPlan {
      public LeadingExpansion {
        Objects.requireNonNull(leadingClass, "leadingClass");
        Objects.requireNonNull(innerPlan, "innerPlan");
      }
    }

    record LineAnchor(Pattern.StartAcceleration acceleration) implements StartPlan {
      public LineAnchor {
        Objects.requireNonNull(acceleration, "acceleration");
      }
    }
  }

  sealed interface RejectPlan {
    record None() implements RejectPlan {
      static final None INSTANCE = new None();
    }

    record RequiredLiteral(String literal) implements RejectPlan {
      public RequiredLiteral {
        Objects.requireNonNull(literal, "literal");
      }
    }

    record RequiredCharClass(CharClassScanInfo scanInfo) implements RejectPlan {
      public RequiredCharClass {
        Objects.requireNonNull(scanInfo, "scanInfo");
      }
    }

    @SuppressWarnings("ArrayRecordComponent")
    record DisjointLiterals(String[] literals) implements RejectPlan {
      public DisjointLiterals {
        Objects.requireNonNull(literals, "literals");
      }
    }

    record EndAnchoredSuffix(Pattern.SuffixInfo suffix) implements RejectPlan {
      public EndAnchoredSuffix {
        Objects.requireNonNull(suffix, "suffix");
      }
    }

    record EndAnchoredCharClass(Pattern.EndAnchoredCharClassInfo charClass) implements RejectPlan {
      public EndAnchoredCharClass {
        Objects.requireNonNull(charClass, "charClass");
      }
    }

    @SuppressWarnings("ArrayRecordComponent")
    record Composite(RejectPlan[] plans) implements RejectPlan {
      public Composite {
        Objects.requireNonNull(plans, "plans");
      }
    }
  }
}
