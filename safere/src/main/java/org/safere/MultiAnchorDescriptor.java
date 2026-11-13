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

  /**
   * Returns whether {@code find()} should run the start accelerator before {@code
   * findRejectPrefilter}.
   *
   * <p>Requires an exact-case literal-driven {@link StartPlan} ({@link StartPlan.Literal} with
   * {@code !foldCase} or {@link StartPlan.FixedOffset}) so slower case-folded or non-exact start
   * kernels never preempt an exact-case {@link RejectPrefilter}, and requires the start literal's
   * anchor rarity to be competitive with any {@link RejectPlan.RequiredLiteral}. O(1) end-anchored
   * boundary checks still run first when present.
   */
  boolean startPrecedesFindReject() {
    if (startAnchored || !(rejectPlan instanceof RejectPlan.RequiredLiteral rl)) {
      return false;
    }
    String startLiteral =
        switch (startPlan) {
          case StartPlan.Literal lit -> lit.foldCase() ? null : lit.prefix();
          case StartPlan.FixedOffset fo -> fo.fol().literal();
          default -> null;
        };
    return startLiteral != null
        && !startLiteral.isEmpty()
        && RarityOracle.startLiteralCompetitiveWithReject(startLiteral, rl.literal());
  }

  /**
   * Returns whether a failed start search already rejects the input, so that {@code find()} can
   * skip {@code findRejectPrefilter}. Holds for a leading expansion over a wide class whose inner
   * plan is an exact literal that contains every literal and some member of every class the reject
   * plan requires: the expansion's literal search then reports no candidate exactly when the reject
   * prefilter would reject, and running both scans the same text twice.
   */
  boolean startSubsumesFindReject() {
    if (startAnchored
        || !(startPlan instanceof StartPlan.LeadingExpansion le)
        || !MultiAnchorCompiler.isWideLeadingClass(le.leadingClass())
        || !(le.innerPlan() instanceof StartPlan.Literal lit)
        || lit.foldCase()) {
      return false;
    }
    return switch (rejectPlan) {
      case RejectPlan.Composite composite -> {
        for (RejectPlan plan : composite.plans()) {
          if (!impliedByLiteral(plan, lit.prefix())) {
            yield false;
          }
        }
        yield true;
      }
      default -> impliedByLiteral(rejectPlan, lit.prefix());
    };
  }

  private static boolean impliedByLiteral(RejectPlan plan, String literal) {
    return switch (plan) {
      case RejectPlan.RequiredLiteral rl -> literal.contains(rl.literal());
      case RejectPlan.RequiredCharClass rc ->
          literal.codePoints().anyMatch(rc.scanInfo()::contains);
      default -> false;
    };
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

    record FixedOffset(
        Pattern.FixedOffsetLiteral fol, CharClassScanInfo leadingClass, String leadingLiteral)
        implements StartPlan {
      FixedOffset(Pattern.FixedOffsetLiteral fol, CharClassScanInfo leadingClass) {
        this(fol, leadingClass, null);
      }

      public FixedOffset {
        Objects.requireNonNull(fol, "fol");
      }
    }

    /** A required ASCII class following an exact-width ASCII prefix. */
    record FixedOffsetClass(CharClassScanInfo scanInfo, int offset, CharClassScanInfo leadingClass)
        implements StartPlan {
      public FixedOffsetClass {
        Objects.requireNonNull(scanInfo, "scanInfo");
        Objects.requireNonNull(leadingClass, "leadingClass");
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
