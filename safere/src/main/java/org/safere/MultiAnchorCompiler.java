// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compiles AST representations of regular expressions into {@link MultiAnchorDescriptor}, {@link
 * StartDescriptor}, and {@link RejectDescriptor} instances in a single bottom-up traversal.
 */
final class MultiAnchorCompiler {

  record PrefixResult(String prefix, boolean foldCase) {
    static final PrefixResult NO_PREFIX = new PrefixResult(null, false);
  }

  @SuppressWarnings("ArrayRecordComponent")
  record FixedOffsetLiteral(String literal, int minOffset, int maxOffset, int[] discreteOffsets) {}

  private static final class AsciiWidthRange {
    static final AsciiWidthRange INVALID = new AsciiWidthRange(-1, -1, null);
    static final AsciiWidthRange ZERO = new AsciiWidthRange(0, 0, new int[] {0});
    static final AsciiWidthRange ONE = new AsciiWidthRange(1, 1, new int[] {1});
    static final AsciiWidthRange NON_DISCRETE_ONE = new AsciiWidthRange(1, 1, null);

    final int minWidth;
    final int maxWidth;
    final int[] discreteWidths;

    AsciiWidthRange(int minWidth, int maxWidth, int[] discreteWidths) {
      this.minWidth = minWidth;
      this.maxWidth = maxWidth;
      this.discreteWidths = discreteWidths;
    }

    static AsciiWidthRange exact(int width) {
      return new AsciiWidthRange(width, width, new int[] {width});
    }

    boolean isValid() {
      return minWidth >= 0;
    }

    boolean isExact() {
      return minWidth >= 0 && minWidth == maxWidth;
    }
  }

  @SuppressWarnings("ArrayRecordComponent")
  record StartFacets(
      PrefixResult prefix,
      CharClassScanInfo charClassPrefix,
      FixedOffsetLiteral fixedOffsetLiteral,
      Pattern.StartAcceleration startAcceleration,
      PrefixResult anchoredPrefix,
      CharClassScanInfo anchoredCharClassPrefix,
      String[] literalAlternation) {

    static final StartFacets EMPTY =
        new StartFacets(
            PrefixResult.NO_PREFIX, null, null, null, PrefixResult.NO_PREFIX, null, null);
  }

  @SuppressWarnings("ArrayRecordComponent")
  record RejectFacets(
      String bestRequiredLiteral,
      int bestRequiredLiteralScore,
      CharClass bestRequiredClass,
      String[] disjointRequiredLiterals,
      Pattern.SuffixInfo endAnchoredSuffix,
      Pattern.EndAnchoredCharClassInfo endAnchoredCharClass) {

    static final RejectFacets EMPTY = new RejectFacets(null, 0, null, null, null, null);
  }

  record NodeAnalysis(
      AsciiWidthRange width,
      StartFacets start,
      RejectFacets reject,
      CharClass atomicRequiredClass) {

    static final NodeAnalysis EMPTY =
        new NodeAnalysis(AsciiWidthRange.ZERO, StartFacets.EMPTY, RejectFacets.EMPTY, null);
  }

  private MultiAnchorCompiler() {}

  static NodeAnalysis analyze(Regexp re) {
    return analyze(re, 0);
  }

  private static NodeAnalysis analyze(Regexp re, int flags) {
    if (re == null) {
      return NodeAnalysis.EMPTY;
    }
    return new MultiAnchorWalker(re, flags).walk(re, NodeAnalysis.EMPTY);
  }

  static MultiAnchorDescriptor compile(Regexp re, int flags) {
    if (re == null) {
      return null;
    }
    Regexp node = unwrapCaptures(re);
    if (node == null) {
      return null;
    }

    boolean anchorStart = false;
    boolean anchorEnd = false;
    if (node.op == RegexpOp.CONCAT && node.subs != null && !node.subs.isEmpty()) {
      int n = node.subs.size();
      if (node.subs.get(0).op == RegexpOp.BEGIN_TEXT) {
        anchorStart = true;
      }
      if (n > 0 && node.subs.get(n - 1).op == RegexpOp.END_TEXT) {
        anchorEnd = true;
      }
    }

    MultiAnchorDescriptor base = extractBaseDescriptor(node, flags, anchorStart, anchorEnd);
    MultiAnchorDescriptor.Chain chain =
        base != null
            ? base.chain()
            : new MultiAnchorDescriptor.Chain(
                new MultiAnchorDescriptor.Segment[0],
                MultiAnchorDescriptor.Gap.EMPTY,
                new int[0],
                0,
                anchorStart,
                anchorEnd);

    NodeAnalysis analysis = analyze(re, flags);
    MultiAnchorDescriptor.StartPlan startPlan = extractStartPlan(re, true, analysis);
    MultiAnchorDescriptor.RejectPlan rejectPlan =
        extractRejectPlan(re, startPlan, anchorStart, analysis);

    PrefixResult anchoredPrefix = analysis.start().anchoredPrefix();
    String anchoredLiteral = anchoredPrefix.foldCase() ? null : anchoredPrefix.prefix();

    return new MultiAnchorDescriptor(
        chain, startPlan, rejectPlan, anchoredLiteral, analysis.start().anchoredCharClassPrefix());
  }

  private static MultiAnchorDescriptor extractBaseDescriptor(
      Regexp node, int flags, boolean anchorStart, boolean anchorEnd) {
    // 1. Multi-anchor sequence or anchored chain
    MultiAnchorDescriptor multiChain = extractMultiAnchorChain(node, flags, anchorStart, anchorEnd);
    if (multiChain != null) {
      return multiChain;
    }

    // 2. Direct anchor on single node
    MultiAnchorDescriptor.Anchor directAnchor = extractLiteralAnchor(node, flags);
    if (directAnchor != null) {
      return new MultiAnchorDescriptor(
          new MultiAnchorDescriptor.Segment[] {
            new MultiAnchorDescriptor.Segment(MultiAnchorDescriptor.Gap.EMPTY, directAnchor)
          },
          MultiAnchorDescriptor.Gap.EMPTY,
          new int[] {0},
          directAnchor.minLength(),
          anchorStart,
          anchorEnd);
    }

    return null;
  }

  static MultiAnchorDescriptor.StartPlan extractStartPlan(Regexp metadataAst) {
    return extractStartPlan(metadataAst, true);
  }

  static MultiAnchorDescriptor.StartPlan extractStartPlan(
      Regexp metadataAst, boolean allowLeadingExpansion) {
    if (metadataAst == null) {
      return MultiAnchorDescriptor.StartPlan.None.INSTANCE;
    }
    return extractStartPlan(metadataAst, allowLeadingExpansion, analyze(metadataAst));
  }

  private static MultiAnchorDescriptor.StartPlan extractStartPlan(
      Regexp metadataAst, boolean allowLeadingExpansion, NodeAnalysis analysis) {
    StartFacets start = analysis.start();

    if (start.startAcceleration() != null) {
      return new MultiAnchorDescriptor.StartPlan.LineAnchor(start.startAcceleration());
    }

    String prefix = start.prefix().prefix();
    boolean prefixFoldCase = start.prefix().foldCase();
    if (prefix != null) {
      ClassHashChain classHashChain =
          prefixFoldCase ? ClassHashChain.compileCaseInsensitive(prefix) : null;
      return new MultiAnchorDescriptor.StartPlan.Literal(prefix, prefixFoldCase, classHashChain);
    }

    if (start.fixedOffsetLiteral() != null) {
      FixedOffsetLiteral fol = start.fixedOffsetLiteral();
      return new MultiAnchorDescriptor.StartPlan.FixedOffset(
          new Pattern.FixedOffsetLiteral(
              fol.literal(), fol.minOffset(), fol.maxOffset(), fol.discreteOffsets()),
          start.charClassPrefix());
    }

    String[] altLiterals = start.literalAlternation();
    if (altLiterals != null && altLiterals.length >= 2) {
      return new MultiAnchorDescriptor.StartPlan.MultiLiteral(altLiterals, start.charClassPrefix());
    }

    if (allowLeadingExpansion) {
      MultiAnchorDescriptor.StartPlan.LeadingExpansion leadingExpansion =
          extractStartLeadingExpansion(metadataAst);
      if (leadingExpansion != null) {
        return leadingExpansion;
      }
    }

    if (start.charClassPrefix() != null) {
      return new MultiAnchorDescriptor.StartPlan.CharClass(start.charClassPrefix());
    }

    return MultiAnchorDescriptor.StartPlan.None.INSTANCE;
  }

  static MultiAnchorDescriptor.RejectPlan extractRejectPlan(
      Regexp metadataAst,
      int flags,
      MultiAnchorDescriptor.StartPlan startPlan,
      boolean anchorStart,
      MultiAnchorDescriptor.Chain chain) {
    if (metadataAst == null) {
      return MultiAnchorDescriptor.RejectPlan.None.INSTANCE;
    }
    return extractRejectPlan(metadataAst, startPlan, anchorStart, analyze(metadataAst, flags));
  }

  private static MultiAnchorDescriptor.RejectPlan extractRejectPlan(
      Regexp metadataAst,
      MultiAnchorDescriptor.StartPlan startPlan,
      boolean anchorStart,
      NodeAnalysis analysis) {
    RejectFacets reject = analysis.reject();

    List<MultiAnchorDescriptor.RejectPlan> plans = new ArrayList<>();

    Pattern.SuffixInfo endAnchoredSuffix = reject.endAnchoredSuffix();
    if (endAnchoredSuffix != null) {
      plans.add(new MultiAnchorDescriptor.RejectPlan.EndAnchoredSuffix(endAnchoredSuffix));
    }

    Pattern.EndAnchoredCharClassInfo endAnchoredCharClass =
        endAnchoredSuffix == null ? reject.endAnchoredCharClass() : null;
    if (endAnchoredCharClass != null) {
      plans.add(new MultiAnchorDescriptor.RejectPlan.EndAnchoredCharClass(endAnchoredCharClass));
    }

    String prefix =
        startPlan instanceof MultiAnchorDescriptor.StartPlan.Literal lit ? lit.prefix() : null;
    CharClassScanInfo ccPrefix =
        startPlan instanceof MultiAnchorDescriptor.StartPlan.CharClass cc ? cc.scanInfo() : null;
    boolean hasLeadingExpansion =
        startPlan instanceof MultiAnchorDescriptor.StartPlan.LeadingExpansion;
    String suffixStr = endAnchoredSuffix != null ? endAnchoredSuffix.suffix() : null;

    String requiredLiteral =
        !anchorStart && !hasLeadingExpansion
            ? extractRequiredLiteral(metadataAst, prefix, suffixStr)
            : null;
    if (requiredLiteral != null) {
      plans.add(new MultiAnchorDescriptor.RejectPlan.RequiredLiteral(requiredLiteral));
    }

    CharClassScanInfo requiredMatchClass = null;
    if (!anchorStart && prefix == null && endAnchoredCharClass == null) {
      CharClass reqClass = reject.bestRequiredClass();
      if (reqClass != null) {
        if (ccPrefix == null) {
          requiredMatchClass = CharClassScanInfo.fromCharClass(reqClass);
        } else {
          CharClassScanInfo candidate = CharClassScanInfo.fromCharClass(reqClass);
          if (candidate != null && candidate.ranges() != null) {
            int candidateRunes = 0;
            for (int i = 0; i < candidate.ranges().length; i += 2) {
              candidateRunes += (candidate.ranges()[i + 1] - candidate.ranges()[i] + 1);
            }
            int prefixRunes = 0;
            for (int i = 0; i < ccPrefix.ranges().length; i += 2) {
              prefixRunes += (ccPrefix.ranges()[i + 1] - ccPrefix.ranges()[i] + 1);
            }
            if (candidateRunes < prefixRunes) {
              requiredMatchClass = candidate;
            }
          }
        }
      }
    }
    if (requiredMatchClass != null) {
      plans.add(new MultiAnchorDescriptor.RejectPlan.RequiredCharClass(requiredMatchClass));
    }

    String[] disjointLiterals =
        (!anchorStart && prefix == null && requiredLiteral == null)
            ? reject.disjointRequiredLiterals()
            : null;
    if (disjointLiterals != null && disjointLiterals.length > 1) {
      plans.add(new MultiAnchorDescriptor.RejectPlan.DisjointLiterals(disjointLiterals));
    }

    if (plans.isEmpty()) {
      return MultiAnchorDescriptor.RejectPlan.None.INSTANCE;
    }
    if (plans.size() == 1) {
      return plans.get(0);
    }
    return new MultiAnchorDescriptor.RejectPlan.Composite(
        plans.toArray(MultiAnchorDescriptor.RejectPlan[]::new));
  }

  // --- Bottom-up MultiAnchorWalker ---

  private static final class MultiAnchorWalker extends Walker<NodeAnalysis> {

    private final int flags;
    private final Regexp fullAnalysisRoot;

    MultiAnchorWalker(Regexp root, int flags) {
      this.flags = flags;
      fullAnalysisRoot = unwrapCaptures(root);
    }

    @Override
    protected NodeAnalysis postVisit(
        Regexp node, NodeAnalysis parentArg, NodeAnalysis preArg, List<NodeAnalysis> childArgs) {
      NodeAnalysis analysis =
          switch (node.op) {
            case CAPTURE, NON_CAPTURE ->
                childArgs.isEmpty() ? NodeAnalysis.EMPTY : childArgs.getFirst();
            case EMPTY_MATCH, WORD_BOUNDARY, NO_WORD_BOUNDARY, BEGIN_TEXT, END_TEXT, END_LINE ->
                NodeAnalysis.EMPTY;
            case BEGIN_LINE ->
                new NodeAnalysis(
                    AsciiWidthRange.ZERO,
                    new StartFacets(
                        PrefixResult.NO_PREFIX,
                        null,
                        null,
                        new Pattern.StartAcceleration(true, false, null),
                        PrefixResult.NO_PREFIX,
                        null,
                        null),
                    RejectFacets.EMPTY,
                    null);
            case LITERAL -> visitLiteral(node);
            case LITERAL_STRING -> visitLiteralString(node);
            case CHAR_CLASS -> visitCharClass(node);
            case CONCAT -> visitConcat(node, childArgs, node == fullAnalysisRoot);
            case ALTERNATE -> visitAlternate(node, childArgs);
            case QUEST -> visitQuest(childArgs);
            case STAR -> visitStar();
            case PLUS -> visitPlus(childArgs);
            case REPEAT -> visitRepeat(node, childArgs);
            default -> NodeAnalysis.EMPTY;
          };
      return node == fullAnalysisRoot ? withRootCharClassPrefix(node, analysis) : analysis;
    }

    @Override
    protected NodeAnalysis shortVisit(Regexp re, NodeAnalysis parentArg) {
      return NodeAnalysis.EMPTY;
    }

    private static NodeAnalysis visitLiteral(Regexp node) {
      boolean foldCase = (node.flags & ParseFlags.FOLD_CASE) != 0;
      String lit = Character.toString(node.rune);
      AsciiWidthRange width =
          node.rune >= 0 && node.rune < 128 && !foldCase
              ? AsciiWidthRange.ONE
              : AsciiWidthRange.INVALID;
      PrefixResult prefix =
          foldCase
              ? new PrefixResult(lit.toLowerCase(Locale.ROOT), true)
              : new PrefixResult(lit, false);
      CharClassScanInfo ccPrefix =
          CharClassScanInfo.fromCharClass(literalCharClass(node.rune, node.flags));
      String reqLit = !foldCase ? lit : null;
      CharClass reqClass = literalCharClass(node.rune, node.flags);
      return new NodeAnalysis(
          width,
          new StartFacets(prefix, ccPrefix, null, null, prefix, ccPrefix, null),
          new RejectFacets(
              reqLit,
              reqLit != null ? RarityOracle.literalSelectivityScore(reqLit) : 0,
              reqClass,
              null,
              null,
              null),
          reqClass);
    }

    private static NodeAnalysis visitLiteralString(Regexp node) {
      boolean foldCase = (node.flags & ParseFlags.FOLD_CASE) != 0;
      String lit = node.runes != null ? new String(node.runes, 0, node.runes.length) : "";
      AsciiWidthRange width = AsciiWidthRangeWalker.literalStringWidth(node);
      PrefixResult prefix =
          foldCase
              ? new PrefixResult(lit.toLowerCase(Locale.ROOT), true)
              : new PrefixResult(lit, false);
      CharClassScanInfo ccPrefix =
          (node.runes != null && node.runes.length > 0)
              ? CharClassScanInfo.fromCharClass(literalCharClass(node.runes[0], node.flags))
              : null;
      String reqLit = (!foldCase && node.runes != null && node.runes.length >= 1) ? lit : null;
      CharClass reqClass =
          (node.runes != null && node.runes.length > 0)
              ? literalCharClass(node.runes[0], node.flags)
              : null;
      return new NodeAnalysis(
          width,
          new StartFacets(prefix, ccPrefix, null, null, prefix, ccPrefix, null),
          new RejectFacets(
              reqLit,
              reqLit != null ? RarityOracle.literalSelectivityScore(reqLit) : 0,
              reqClass,
              null,
              null,
              null),
          reqClass);
    }

    private static NodeAnalysis visitCharClass(Regexp node) {
      AsciiWidthRange width = AsciiWidthRangeWalker.characterClassWidth(node);
      CharClass cc = node.charClass;
      CharClassScanInfo ccPrefix =
          (cc != null && !cc.isEmpty() && cc.numRunes() <= 0x80000)
              ? CharClassScanInfo.fromCharClass(cc)
              : null;
      int rep = simpleFoldClassRepresentative(cc);
      PrefixResult prefix =
          rep >= 0
              ? new PrefixResult(Character.toString(rep).toLowerCase(Locale.ROOT), true)
              : PrefixResult.NO_PREFIX;
      return new NodeAnalysis(
          width,
          new StartFacets(prefix, ccPrefix, null, null, prefix, ccPrefix, null),
          new RejectFacets(null, 0, (cc != null && !cc.isEmpty()) ? cc : null, null, null, null),
          cc != null && !cc.isEmpty() ? cc : null);
    }

    private NodeAnalysis visitConcat(
        Regexp node, List<NodeAnalysis> children, boolean fullAnalysis) {
      if (children.isEmpty()) {
        return NodeAnalysis.EMPTY;
      }
      List<AsciiWidthRange> widths = new ArrayList<>(children.size());
      for (NodeAnalysis c : children) {
        widths.add(c.width());
      }
      AsciiWidthRange width = concatenateWidths(widths);

      if (!fullAnalysis) {
        return visitNestedConcat(node, children, width);
      }

      // Prefix
      PrefixResult prefix = extractPrefix(node);

      // Fixed-offset literal
      FixedOffsetLiteral fol = extractFixedOffsetLiteral(node);

      // Best required literal across concat
      String exactLit = extractExactAsciiLiteral(node);
      String bestReqLit = exactLit != null && exactLit.length() >= 2 ? exactLit : null;
      int bestReqScore = bestReqLit != null ? RarityOracle.literalSelectivityScore(bestReqLit) : 0;
      for (NodeAnalysis c : children) {
        String childReq = c.reject().bestRequiredLiteral();
        if (childReq != null && childReq.length() >= 2) {
          int score = c.reject().bestRequiredLiteralScore();
          if (bestReqLit == null || score > bestReqScore) {
            bestReqLit = childReq;
            bestReqScore = score;
          }
        }
      }

      // Best required class
      CharClass bestReqClass = null;
      for (NodeAnalysis c : children) {
        CharClass childReqClass = c.reject().bestRequiredClass();
        if (childReqClass != null) {
          if (bestReqClass == null || childReqClass.numRunes() < bestReqClass.numRunes()) {
            bestReqClass = childReqClass;
          }
        }
      }

      // Disjoint required literals
      String[] disjoint = null;
      if (node.subs != null && !node.subs.isEmpty()) {
        Regexp first = unwrapCaptures(node.subs.get(0));
        if (first == null || (first.op != RegexpOp.BEGIN_TEXT && first.op != RegexpOp.BEGIN_LINE)) {
          for (NodeAnalysis c : children) {
            if (c.reject().disjointRequiredLiterals() != null) {
              disjoint = c.reject().disjointRequiredLiterals();
              break;
            }
          }
        }
      }

      // End-anchored suffix & char class
      Pattern.SuffixInfo endSuffix = extractEndAnchoredSuffix(node, flags);
      Pattern.EndAnchoredCharClassInfo endClass = extractEndAnchoredCharClass(node, flags);

      // Start acceleration
      Pattern.StartAcceleration startAcc = extractStartAcceleration(node);

      // Anchored prefix
      Regexp anchoredCandidate = firstPrefixCandidateAfterTextAnchor(node);
      PrefixResult anchoredPrefix =
          anchoredCandidate != null
              ? extractPrefixFromCandidate(anchoredCandidate)
              : PrefixResult.NO_PREFIX;
      CharClassScanInfo anchoredCc =
          anchoredCandidate != null ? extractCharClassPrefix(anchoredCandidate) : null;

      CharClassScanInfo ccPrefix = extractCharClassPrefix(node);
      StartFacets start =
          new StartFacets(prefix, ccPrefix, fol, startAcc, anchoredPrefix, anchoredCc, null);
      RejectFacets reject =
          new RejectFacets(bestReqLit, bestReqScore, bestReqClass, disjoint, endSuffix, endClass);

      return new NodeAnalysis(width, start, reject, null);
    }

    private static NodeAnalysis visitNestedConcat(
        Regexp node, List<NodeAnalysis> children, AsciiWidthRange width) {
      StartFacets leading = StartFacets.EMPTY;
      for (int index = 0; index < children.size(); index++) {
        if (isLeadingZeroWidth(node.subs.get(index))) {
          continue;
        }
        leading = children.get(index).start();
        break;
      }

      String bestRequiredLiteral = null;
      int bestRequiredScore = 0;
      CharClass bestRequiredClass = null;
      String[] disjointRequiredLiterals = null;
      for (NodeAnalysis child : children) {
        String candidateLiteral = child.reject().bestRequiredLiteral();
        if (candidateLiteral != null && candidateLiteral.length() >= 2) {
          int candidateScore = child.reject().bestRequiredLiteralScore();
          if (bestRequiredLiteral == null || candidateScore > bestRequiredScore) {
            bestRequiredLiteral = candidateLiteral;
            bestRequiredScore = candidateScore;
          }
        }
        CharClass candidateClass = child.reject().bestRequiredClass();
        if (candidateClass != null
            && (bestRequiredClass == null
                || candidateClass.numRunes() < bestRequiredClass.numRunes())) {
          bestRequiredClass = candidateClass;
        }
        if (disjointRequiredLiterals == null && child.reject().disjointRequiredLiterals() != null) {
          disjointRequiredLiterals = child.reject().disjointRequiredLiterals();
        }
      }

      StartFacets start =
          new StartFacets(
              leading.prefix(),
              leading.charClassPrefix(),
              null,
              leading.startAcceleration(),
              leading.anchoredPrefix(),
              leading.anchoredCharClassPrefix(),
              null);
      RejectFacets reject =
          new RejectFacets(
              bestRequiredLiteral,
              bestRequiredScore,
              bestRequiredClass,
              disjointRequiredLiterals,
              null,
              null);
      return new NodeAnalysis(width, start, reject, null);
    }

    private static NodeAnalysis visitAlternate(Regexp node, List<NodeAnalysis> children) {
      if (children.isEmpty()) {
        return NodeAnalysis.EMPTY;
      }
      List<AsciiWidthRange> widths = new ArrayList<>(children.size());
      for (NodeAnalysis c : children) {
        widths.add(c.width());
      }
      AsciiWidthRange width = AsciiWidthRangeWalker.alternateWidth(widths);

      // Literal alternation
      String[] litAlt = extractLiteralAlternation(node);

      // Required match class across branches
      CharClass reqClass = unionRequiredCharClasses(children);

      // Disjoint required literals from branches
      String[] disjoint = combineDisjointRequiredLiterals(children);

      StartFacets start =
          new StartFacets(
              PrefixResult.NO_PREFIX, null, null, null, PrefixResult.NO_PREFIX, null, litAlt);
      RejectFacets reject = new RejectFacets(null, 0, reqClass, disjoint, null, null);

      return new NodeAnalysis(width, start, reject, null);
    }

    private static NodeAnalysis withRootCharClassPrefix(Regexp node, NodeAnalysis analysis) {
      StartFacets start = analysis.start();
      StartFacets completed =
          new StartFacets(
              start.prefix(),
              extractCharClassPrefix(node),
              start.fixedOffsetLiteral(),
              start.startAcceleration(),
              start.anchoredPrefix(),
              start.anchoredCharClassPrefix(),
              start.literalAlternation());
      return new NodeAnalysis(
          analysis.width(), completed, analysis.reject(), analysis.atomicRequiredClass());
    }

    private static CharClass unionRequiredCharClasses(List<NodeAnalysis> children) {
      CharClassBuilder union = new CharClassBuilder();
      for (NodeAnalysis child : children) {
        CharClass required = child.atomicRequiredClass();
        if (required == null) {
          return null;
        }
        union.addCharClass(required);
      }
      CharClass result = union.build();
      return result.isEmpty() ? null : result;
    }

    private static NodeAnalysis visitQuest(List<NodeAnalysis> children) {
      if (children.isEmpty()) {
        return NodeAnalysis.EMPTY;
      }
      AsciiWidthRange width =
          AsciiWidthRangeWalker.optionalWidth(List.of(children.getFirst().width()));
      return new NodeAnalysis(width, StartFacets.EMPTY, RejectFacets.EMPTY, null);
    }

    private static NodeAnalysis visitStar() {
      return new NodeAnalysis(AsciiWidthRange.INVALID, StartFacets.EMPTY, RejectFacets.EMPTY, null);
    }

    private static NodeAnalysis visitPlus(List<NodeAnalysis> children) {
      if (children.isEmpty()) {
        return NodeAnalysis.EMPTY;
      }
      NodeAnalysis child = children.getFirst();
      AsciiWidthRange width = AsciiWidthRange.INVALID;
      StartFacets start =
          new StartFacets(
              child.start().prefix(),
              child.start().charClassPrefix(),
              null,
              null,
              child.start().anchoredPrefix(),
              child.start().anchoredCharClassPrefix(),
              null);
      RejectFacets reject =
          new RejectFacets(
              child.reject().bestRequiredLiteral(),
              child.reject().bestRequiredLiteralScore(),
              child.reject().bestRequiredClass(),
              null,
              null,
              null);
      return new NodeAnalysis(width, start, reject, child.atomicRequiredClass());
    }

    private static NodeAnalysis visitRepeat(Regexp node, List<NodeAnalysis> children) {
      if (children.isEmpty()) {
        return NodeAnalysis.EMPTY;
      }
      NodeAnalysis child = children.getFirst();
      AsciiWidthRange width = AsciiWidthRangeWalker.repeatWidth(node, List.of(child.width()));
      StartFacets start =
          node.min > 0
              ? new StartFacets(
                  child.start().prefix(),
                  child.start().charClassPrefix(),
                  null,
                  null,
                  child.start().anchoredPrefix(),
                  child.start().anchoredCharClassPrefix(),
                  null)
              : StartFacets.EMPTY;
      RejectFacets reject =
          node.min > 0
              ? new RejectFacets(
                  child.reject().bestRequiredLiteral(),
                  child.reject().bestRequiredLiteralScore(),
                  child.reject().bestRequiredClass(),
                  null,
                  null,
                  null)
              : RejectFacets.EMPTY;
      return new NodeAnalysis(
          width, start, reject, node.min > 0 ? child.atomicRequiredClass() : null);
    }
  }

  // --- Helper methods for chain, gap, and anchor extraction ---

  private record ConsumedAnchor(MultiAnchorDescriptor.Anchor anchor, int consumedCount) {}

  private static ConsumedAnchor extractConsecutiveLiteralAnchor(
      List<Regexp> subs, int startIdx, int flags) {
    if (startIdx >= subs.size()) {
      return null;
    }
    Regexp first = unwrapCaptures(subs.get(startIdx));
    if (first == null) {
      return null;
    }

    if (first.op == RegexpOp.ALTERNATE) {
      MultiAnchorDescriptor.Anchor.Alternation alt = extractAlternationAnchor(first, flags);
      if (alt != null) {
        return new ConsumedAnchor(alt, 1);
      }
      return null;
    }

    boolean globalFold =
        (flags & Pattern.CASE_INSENSITIVE) != 0 || (first.flags & ParseFlags.FOLD_CASE) != 0;
    StringBuilder lit = new StringBuilder();
    int count = 0;
    for (int i = startIdx; i < subs.size(); i++) {
      Regexp sub = unwrapCaptures(subs.get(i));
      if (sub == null) {
        break;
      }
      boolean subFold =
          (flags & Pattern.CASE_INSENSITIVE) != 0 || (sub.flags & ParseFlags.FOLD_CASE) != 0;
      if (subFold != globalFold) {
        break;
      }
      if (sub.op == RegexpOp.LITERAL) {
        lit.append(Character.toChars(sub.rune));
        count++;
      } else if (sub.op == RegexpOp.LITERAL_STRING && sub.runes != null && sub.runes.length >= 1) {
        for (int r : sub.runes) {
          lit.append(Character.toChars(r));
        }
        count++;
      } else if (sub.op == RegexpOp.CONCAT && sub.subs != null) {
        String exact = extractExactAsciiLiteralIgnoringCase(sub);
        if (exact != null) {
          lit.append(exact);
          count++;
        } else {
          break;
        }
      } else {
        break;
      }
    }

    if (count == 0 || lit.isEmpty()) {
      return null;
    }
    return new ConsumedAnchor(
        MultiAnchorDescriptor.Anchor.Single.create(lit.toString(), globalFold), count);
  }

  private static MultiAnchorDescriptor extractMultiAnchorChain(
      Regexp node, int flags, boolean anchorStart, boolean anchorEnd) {
    if (node == null || node.op != RegexpOp.CONCAT || node.subs == null) {
      return null;
    }
    List<MultiAnchorDescriptor.Anchor> anchors = new ArrayList<>();
    List<MultiAnchorDescriptor.Gap> gaps = new ArrayList<>();

    int idx = 0;
    int n = node.subs.size();

    MultiAnchorDescriptor.Gap leadingGap = MultiAnchorDescriptor.Gap.EMPTY;
    while (idx < n) {
      Regexp sub = node.subs.get(idx);
      if (isLeadingZeroWidth(sub)) {
        MultiAnchorDescriptor.Gap zwGap = classifyGap(sub, flags);
        if (zwGap != null && leadingGap.kind() == MultiAnchorDescriptor.GapKind.EMPTY) {
          leadingGap = zwGap;
        }
        idx++;
        continue;
      }
      if (extractConsecutiveLiteralAnchor(node.subs, idx, flags) != null) {
        break;
      }
      MultiAnchorDescriptor.Gap gapCandidate = classifyGap(sub, flags);
      if (gapCandidate == null) {
        break;
      }
      MultiAnchorDescriptor.Gap merged = coalesceGaps(leadingGap, gapCandidate);
      if (merged == null) {
        break;
      }
      leadingGap = merged;
      idx++;
    }

    if (idx < n) {
      ConsumedAnchor firstConsumed = extractConsecutiveLiteralAnchor(node.subs, idx, flags);
      if (firstConsumed != null) {
        anchors.add(firstConsumed.anchor());
        gaps.add(leadingGap);
        idx += firstConsumed.consumedCount();

        while (idx < n) {
          Regexp gapSub = node.subs.get(idx);
          if (idx == n - 1 && gapSub.op == RegexpOp.END_TEXT) {
            idx++;
            break;
          }

          MultiAnchorDescriptor.Gap gap = classifyGap(gapSub, flags);
          if (gap == null) {
            ConsumedAnchor nextConsumed = extractConsecutiveLiteralAnchor(node.subs, idx, flags);
            if (nextConsumed != null) {
              gaps.add(MultiAnchorDescriptor.Gap.EMPTY);
              anchors.add(nextConsumed.anchor());
              idx += nextConsumed.consumedCount();
              continue;
            }
            break;
          }
          idx++;

          while (idx < n && extractConsecutiveLiteralAnchor(node.subs, idx, flags) == null) {
            MultiAnchorDescriptor.Gap nextGap = classifyGap(node.subs.get(idx), flags);
            if (nextGap == null) {
              break;
            }
            MultiAnchorDescriptor.Gap merged = coalesceGaps(gap, nextGap);
            if (merged == null) {
              break;
            }
            gap = merged;
            idx++;
          }

          if (idx >= n) {
            gaps.add(gap);
            break;
          }

          ConsumedAnchor nextConsumed = extractConsecutiveLiteralAnchor(node.subs, idx, flags);
          if (nextConsumed == null) {
            MultiAnchorDescriptor.Gap trailing = gap;
            boolean validTrailing = true;
            while (idx < n) {
              Regexp rem = node.subs.get(idx);
              if (idx == n - 1 && rem.op == RegexpOp.END_TEXT) {
                idx++;
                break;
              }
              validTrailing = false;
              break;
            }
            if (validTrailing) {
              gaps.add(trailing);
              break;
            }
            break;
          }
          gaps.add(gap);
          anchors.add(nextConsumed.anchor());
          idx += nextConsumed.consumedCount();
        }
      }
    }

    if (idx < n) {
      return null;
    }

    if (gaps.size() == anchors.size()) {
      gaps.add(MultiAnchorDescriptor.Gap.EMPTY);
    }

    if (anchors.isEmpty() || gaps.size() != anchors.size() + 1) {
      return null;
    }

    int maxAnchorLen = 0;
    int minTotalLength = 0;
    for (MultiAnchorDescriptor.Anchor a : anchors) {
      int len = a.minLength();
      minTotalLength += len;
      if (len > maxAnchorLen) {
        maxAnchorLen = len;
      }
    }
    for (MultiAnchorDescriptor.Gap g : gaps) {
      minTotalLength += g.minLength();
    }

    if (maxAnchorLen < 2) {
      return null;
    }
    if (maxAnchorLen < 3 && anchors.size() < 3 && anchors.size() > 1) {
      return null;
    }

    int numAnchors = anchors.size();
    Integer[] orderBoxed = new Integer[numAnchors];
    for (int i = 0; i < numAnchors; i++) {
      orderBoxed[i] = i;
    }
    Arrays.sort(
        orderBoxed,
        (a, b) ->
            Integer.compare(anchors.get(b).selectivityScore(), anchors.get(a).selectivityScore()));

    int[] checkOrder = new int[numAnchors];
    for (int i = 0; i < numAnchors; i++) {
      checkOrder[i] = orderBoxed[i];
    }

    MultiAnchorDescriptor.Segment[] segments = new MultiAnchorDescriptor.Segment[numAnchors];
    for (int i = 0; i < numAnchors; i++) {
      segments[i] = new MultiAnchorDescriptor.Segment(gaps.get(i), anchors.get(i));
    }

    return new MultiAnchorDescriptor(
        segments, gaps.get(numAnchors), checkOrder, minTotalLength, anchorStart, anchorEnd);
  }

  private static MultiAnchorDescriptor.Gap coalesceGaps(
      MultiAnchorDescriptor.Gap first, MultiAnchorDescriptor.Gap second) {
    if (first == null || first.kind() == MultiAnchorDescriptor.GapKind.EMPTY) {
      return second;
    }
    if (second == null || second.kind() == MultiAnchorDescriptor.GapKind.EMPTY) {
      return first;
    }
    if (first.kind() == MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT
        && second.kind() == MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT
        && Objects.equals(first.charClass(), second.charClass())
        && Objects.equals(first.scanInfo(), second.scanInfo())) {
      int min = first.minLength() + second.minLength();
      int max =
          (first.maxLength() == Integer.MAX_VALUE || second.maxLength() == Integer.MAX_VALUE)
              ? Integer.MAX_VALUE
              : first.maxLength() + second.maxLength();
      return new MultiAnchorDescriptor.Gap(
          MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT,
          min,
          max,
          first.charClass(),
          first.scanInfo(),
          first.isGreedy());
    }
    if (first.kind() == second.kind()
        && (first.kind() == MultiAnchorDescriptor.GapKind.ANY_STAR
            || first.kind() == MultiAnchorDescriptor.GapKind.SINGLE_LINE_ANY_STAR)) {
      int min = first.minLength() + second.minLength();
      int max =
          (first.maxLength() == Integer.MAX_VALUE || second.maxLength() == Integer.MAX_VALUE)
              ? Integer.MAX_VALUE
              : first.maxLength() + second.maxLength();
      return new MultiAnchorDescriptor.Gap(first.kind(), min, max, null, first.isGreedy());
    }
    return null;
  }

  private static MultiAnchorDescriptor.StartPlan.LeadingExpansion extractStartLeadingExpansion(
      Regexp re) {
    if (re == null) {
      return null;
    }
    re = unwrapCaptures(re);
    if (re == null || re.op != RegexpOp.CONCAT || re.nsub() < 2) {
      return null;
    }

    int idx = 0;
    while (idx < re.nsub() && isLeadingZeroWidth(re.subs.get(idx))) {
      idx++;
    }
    if (idx >= re.nsub() - 1) {
      return null;
    }

    Regexp first = unwrapCaptures(re.subs.get(idx));
    if (first == null) {
      return null;
    }

    int minRepetition;
    int maxRepetition;
    Regexp repeated;
    if (first.op == RegexpOp.STAR) {
      minRepetition = 0;
      maxRepetition = Integer.MAX_VALUE;
      repeated = unwrapCaptures(first.sub());
    } else if (first.op == RegexpOp.PLUS) {
      minRepetition = 1;
      maxRepetition = Integer.MAX_VALUE;
      repeated = unwrapCaptures(first.sub());
    } else if (first.op == RegexpOp.REPEAT) {
      minRepetition = first.min;
      maxRepetition = first.max == -1 ? Integer.MAX_VALUE : first.max;
      repeated = unwrapCaptures(first.sub());
    } else {
      return null;
    }

    if (repeated == null || repeated.op == RegexpOp.ANY_CHAR || repeated.op == RegexpOp.ANY_BYTE) {
      return null;
    }

    CharClassScanInfo leadingClass;
    if (repeated.op == RegexpOp.CHAR_CLASS
        && repeated.charClass != null
        && !repeated.charClass.isEmpty()) {
      leadingClass = CharClassScanInfo.fromCharClass(repeated.charClass);
    } else if (repeated.op == RegexpOp.LITERAL) {
      leadingClass =
          CharClassScanInfo.fromCharClass(literalCharClass(repeated.rune, repeated.flags));
    } else {
      return null;
    }

    if (leadingClass == null) {
      return null;
    }

    int numRunes = 0;
    int[] ranges = leadingClass.ranges();
    if (ranges != null) {
      for (int i = 0; i < ranges.length; i += 2) {
        numRunes += (ranges[i + 1] - ranges[i] + 1);
      }
    }
    if (numRunes > 150_000) {
      return null;
    }

    List<Regexp> tailSubs = re.subs.subList(idx + 1, re.nsub());
    Regexp tail = tailSubs.size() == 1 ? tailSubs.getFirst() : Regexp.concat(tailSubs, 0);

    MultiAnchorDescriptor.StartPlan inner = extractStartPlan(tail, false);
    if (inner == null
        || inner instanceof MultiAnchorDescriptor.StartPlan.None
        || inner instanceof MultiAnchorDescriptor.StartPlan.LeadingExpansion) {
      return null;
    }

    return new MultiAnchorDescriptor.StartPlan.LeadingExpansion(
        leadingClass, minRepetition, maxRepetition, inner);
  }

  private static AsciiBitmap buildAsciiBitmapFromCharClass(CharClass cc) {
    if (cc == null || cc.isEmpty()) {
      return null;
    }
    for (int i = 0; i < cc.numRanges(); i++) {
      if (cc.hi(i) >= 128) {
        return null;
      }
    }
    AsciiBitmap.Builder builder = new AsciiBitmap.Builder();
    for (int i = 0; i < cc.numRanges(); i++) {
      builder.addRange(cc.lo(i), cc.hi(i));
    }
    return builder.build();
  }

  static MultiAnchorDescriptor.Anchor extractLiteralAnchor(Regexp re, int flags) {
    if (re == null) {
      return null;
    }
    Regexp unwrapped = unwrapCaptures(re);
    if (unwrapped == null) {
      return null;
    }
    boolean foldCase =
        (flags & Pattern.CASE_INSENSITIVE) != 0 || (unwrapped.flags & ParseFlags.FOLD_CASE) != 0;

    if (unwrapped.op == RegexpOp.LITERAL_STRING
        && unwrapped.runes != null
        && unwrapped.runes.length >= 1) {
      String lit = new String(unwrapped.runes, 0, unwrapped.runes.length);
      return MultiAnchorDescriptor.Anchor.Single.create(lit, foldCase);
    }
    if (unwrapped.op == RegexpOp.LITERAL) {
      String lit = new String(Character.toChars(unwrapped.rune));
      return MultiAnchorDescriptor.Anchor.Single.create(lit, foldCase);
    }
    if (unwrapped.op == RegexpOp.CONCAT && unwrapped.subs != null) {
      String lit = extractExactAsciiLiteralIgnoringCase(unwrapped);
      if (lit != null) {
        return MultiAnchorDescriptor.Anchor.Single.create(lit, foldCase);
      }
    }
    if (unwrapped.op == RegexpOp.ALTERNATE) {
      MultiAnchorDescriptor.Anchor.Alternation alt = extractAlternationAnchor(unwrapped, flags);
      if (alt != null) {
        return alt;
      }
      CharClassScanInfo scanInfo = extractCharClassPrefix(unwrapped);
      if (scanInfo != null) {
        return MultiAnchorDescriptor.Anchor.CharClass.create(scanInfo);
      }
    }
    if (unwrapped.op == RegexpOp.CHAR_CLASS && unwrapped.charClass != null) {
      AsciiBitmap bm = buildAsciiBitmapFromCharClass(unwrapped.charClass);
      if (bm != null && !bm.isEmpty() && bm.cardinality() <= 32) {
        CharClassScanInfo scanInfo = CharClassScanInfo.fromCharClass(unwrapped.charClass);
        if (scanInfo != null) {
          return MultiAnchorDescriptor.Anchor.CharClass.create(scanInfo);
        }
      }
    }
    return null;
  }

  static MultiAnchorDescriptor.Anchor.Alternation extractAlternationAnchor(Regexp re, int flags) {
    if (re == null) {
      return null;
    }
    re = unwrapCaptures(re);
    if (re == null || re.op != RegexpOp.ALTERNATE || re.nsub() < 2) {
      return null;
    }
    boolean globalFold =
        (flags & Pattern.CASE_INSENSITIVE) != 0 || (re.flags & ParseFlags.FOLD_CASE) != 0;
    String[] literals = new String[re.nsub()];
    for (int i = 0; i < re.nsub(); i++) {
      Regexp branch = unwrapCaptures(re.subs.get(i));
      if (branch == null) {
        return null;
      }
      boolean branchFold = globalFold || (branch.flags & ParseFlags.FOLD_CASE) != 0;
      if (branchFold != globalFold) {
        return null;
      }
      String lit = extractExactAsciiLiteralIgnoringCase(branch);
      if (lit == null || lit.isEmpty()) {
        return null;
      }
      literals[i] = lit;
    }
    return MultiAnchorDescriptor.Anchor.Alternation.create(literals, globalFold);
  }

  static PrefixResult extractPrefix(Regexp re) {
    PrefixResult direct = extractPrefixFromCandidate(firstPrefixCandidate(re));
    return direct.prefix() != null ? direct : extractUnicodeFoldedPrefix(re);
  }

  static PrefixResult extractUnicodeFoldedPrefix(Regexp re) {
    Deque<Regexp> work = new ArrayDeque<>();
    work.add(re);
    StringBuilder prefix = new StringBuilder();
    boolean sawFoldClass = false;

    while (!work.isEmpty()) {
      Regexp node = unwrapCaptures(work.removeFirst());
      if (node == null) {
        continue;
      }
      if (node.op == RegexpOp.CONCAT) {
        for (int i = node.subs.size() - 1; i >= 0; i--) {
          work.addFirst(node.subs.get(i));
        }
        continue;
      }
      if (isLeadingZeroWidth(node)) {
        continue;
      }

      if (node.op == RegexpOp.CHAR_CLASS) {
        int representative = simpleFoldClassRepresentative(node.charClass);
        if (representative < 0) {
          break;
        }
        prefix.appendCodePoint(representative);
        sawFoldClass = true;
        continue;
      }

      if (node.op == RegexpOp.LITERAL) {
        if (!appendFoldCompatibleLiteral(prefix, node.rune, node.flags)) {
          break;
        }
        continue;
      }
      if (node.op == RegexpOp.LITERAL_STRING && node.runes != null) {
        boolean compatible = true;
        for (int rune : node.runes) {
          if (!appendFoldCompatibleLiteral(prefix, rune, node.flags)) {
            compatible = false;
            break;
          }
        }
        if (compatible) {
          continue;
        }
      }
      break;
    }

    return sawFoldClass && !prefix.isEmpty()
        ? new PrefixResult(prefix.toString().toLowerCase(Locale.ROOT), true)
        : new PrefixResult(null, false);
  }

  private static boolean appendFoldCompatibleLiteral(StringBuilder prefix, int rune, int flags) {
    if ((flags & ParseFlags.FOLD_CASE) == 0 && Inst.simpleFold(rune) != rune) {
      return false;
    }
    prefix.appendCodePoint(rune);
    return true;
  }

  private static int simpleFoldClassRepresentative(CharClass charClass) {
    if (charClass == null || charClass.isEmpty()) {
      return -1;
    }
    int representative = charClass.lo(0);
    CharClass expected =
        literalCharClass(representative, ParseFlags.FOLD_CASE | ParseFlags.UNICODE_CASE);
    if (expected.numRanges() != charClass.numRanges()) {
      return -1;
    }
    for (int i = 0; i < expected.numRanges(); i++) {
      if (expected.lo(i) != charClass.lo(i) || expected.hi(i) != charClass.hi(i)) {
        return -1;
      }
    }
    int utf8Width = utf8Width(representative);
    int folded = Inst.simpleFold(representative);
    while (folded != representative) {
      if (utf8Width(folded) != utf8Width) {
        return -1;
      }
      folded = Inst.simpleFold(folded);
    }
    return representative;
  }

  private static int utf8Width(int codePoint) {
    if (codePoint <= 0x7F) {
      return 1;
    }
    if (codePoint <= 0x7FF) {
      return 2;
    }
    return codePoint <= 0xFFFF ? 3 : 4;
  }

  private static PrefixResult extractPrefixFromCandidate(Regexp node) {
    if (node == null) {
      return new PrefixResult(null, false);
    }

    boolean foldCase = (node.flags & ParseFlags.FOLD_CASE) != 0;
    StringBuilder sb = new StringBuilder();
    if (node.op == RegexpOp.LITERAL) {
      sb.appendCodePoint(node.rune);
    } else if (node.op == RegexpOp.LITERAL_STRING && node.runes != null) {
      for (int r : node.runes) {
        sb.appendCodePoint(r);
      }
    } else {
      return new PrefixResult(null, false);
    }

    if (sb.isEmpty()) {
      return new PrefixResult(null, false);
    }

    String prefix = foldCase ? sb.toString().toLowerCase(Locale.ROOT) : sb.toString();
    return new PrefixResult(prefix, foldCase);
  }

  static Regexp firstPrefixCandidate(Regexp re) {
    Deque<Regexp> stack = new ArrayDeque<>();
    stack.push(re);
    while (!stack.isEmpty()) {
      Regexp node = unwrapCaptures(stack.pop());
      if (node == null || isLeadingZeroWidth(node)) {
        continue;
      }
      if (node.op == RegexpOp.CONCAT) {
        for (int i = node.subs.size() - 1; i >= 0; i--) {
          stack.push(node.subs.get(i));
        }
      } else {
        return node;
      }
    }
    return null;
  }

  static Regexp firstPrefixCandidateAfterTextAnchor(Regexp re) {
    Deque<Regexp> stack = new ArrayDeque<>();
    stack.push(re);
    boolean sawTextAnchor = false;
    while (!stack.isEmpty()) {
      Regexp node = unwrapCaptures(stack.pop());
      if (node == null) {
        continue;
      }
      if (node.op == RegexpOp.CONCAT && node.subs != null) {
        for (int i = node.subs.size() - 1; i >= 0; i--) {
          stack.push(node.subs.get(i));
        }
        continue;
      }
      if (!sawTextAnchor) {
        if (node.op == RegexpOp.BEGIN_TEXT) {
          sawTextAnchor = true;
          continue;
        }
        if (isLeadingZeroWidth(node)) {
          continue;
        }
        return null;
      }
      if (!isLeadingZeroWidth(node)) {
        return node;
      }
    }
    return null;
  }

  static String extractExactAsciiLiteral(Regexp re) {
    if (re == null) {
      return null;
    }
    StringBuilder literal = new StringBuilder();
    Deque<Regexp> pending = new ArrayDeque<>();
    pending.push(re);
    while (!pending.isEmpty()) {
      Regexp node = unwrapCaptures(pending.pop());
      if (node == null || (node.flags & ParseFlags.FOLD_CASE) != 0) {
        return null;
      }
      if (node.op == RegexpOp.LITERAL && node.rune >= 0 && node.rune < 128) {
        literal.append((char) node.rune);
        continue;
      }
      if (node.op == RegexpOp.LITERAL_STRING && node.runes != null && node.runes.length > 0) {
        for (int rune : node.runes) {
          if (rune < 0 || rune >= 128) {
            return null;
          }
          literal.append((char) rune);
        }
        continue;
      }
      if (node.op == RegexpOp.CONCAT && node.subs != null) {
        for (int index = node.subs.size() - 1; index >= 0; index--) {
          pending.push(node.subs.get(index));
        }
        continue;
      }
      return null;
    }
    return literal.isEmpty() ? null : literal.toString();
  }

  static String extractExactAsciiLiteralIgnoringCase(Regexp re) {
    if (re == null) {
      return null;
    }
    StringBuilder literal = new StringBuilder();
    Deque<Regexp> pending = new ArrayDeque<>();
    pending.push(re);
    while (!pending.isEmpty()) {
      Regexp node = unwrapCaptures(pending.pop());
      if (node == null) {
        return null;
      }
      if (node.op == RegexpOp.LITERAL && node.rune >= 0 && node.rune < 128) {
        literal.append((char) node.rune);
        continue;
      }
      if (node.op == RegexpOp.LITERAL_STRING && node.runes != null && node.runes.length > 0) {
        for (int rune : node.runes) {
          if (rune < 0 || rune >= 128) {
            return null;
          }
          literal.append((char) rune);
        }
        continue;
      }
      if (node.op == RegexpOp.CONCAT && node.subs != null) {
        for (int index = node.subs.size() - 1; index >= 0; index--) {
          pending.push(node.subs.get(index));
        }
        continue;
      }
      return null;
    }
    return literal.isEmpty() ? null : literal.toString();
  }

  static CharClassScanInfo extractCharClassPrefix(Regexp re) {
    CharClassBuilder builder = new CharClassBuilder();
    Deque<Regexp> work = new ArrayDeque<>();
    work.add(re);

    while (!work.isEmpty()) {
      Regexp node = work.removeLast();

      for (; ; ) {
        node = unwrapCaptures(node);
        if (node == null) {
          return null;
        }
        if (node.op == RegexpOp.CONCAT && node.nsub() > 0) {
          int i = 0;
          while (i < node.nsub() && isLeadingZeroWidth(node.subs.get(i))) {
            i++;
          }
          if (i < node.nsub()) {
            node = node.subs.get(i);
            continue;
          }
          return null;
        }
        if (node.op == RegexpOp.PLUS || (node.op == RegexpOp.REPEAT && node.min >= 1)) {
          node = node.sub();
          continue;
        }
        break;
      }

      switch (node.op) {
        case LITERAL -> {
          builder.addCharClass(literalCharClass(node.rune, node.flags));
        }
        case LITERAL_STRING -> {
          if (node.runes == null || node.runes.length == 0) {
            return null;
          }
          builder.addCharClass(literalCharClass(node.runes[0], node.flags));
        }
        case CHAR_CLASS -> {
          if (node.charClass == null || node.charClass.isEmpty()) {
            return null;
          }
          builder.addCharClass(node.charClass);
        }
        case ALTERNATE -> {
          if (node.nsub() == 0) {
            return null;
          }
          for (Regexp sub : node.subs) {
            work.add(sub);
          }
        }
        default -> {
          return null;
        }
      }
    }

    CharClass cc = builder.build();
    if (cc.isEmpty()) {
      return null;
    }
    if (cc.numRunes() > 0x80000) {
      return null;
    }
    return CharClassScanInfo.fromCharClass(cc);
  }

  private static boolean isDotCharClass(CharClass cc) {
    if (cc == null) {
      return false;
    }
    return !cc.contains('\n')
        && cc.contains('a')
        && cc.contains(' ')
        && cc.contains('0')
        && cc.numRanges() <= 6
        && cc.numRunes() > 1000;
  }

  static MultiAnchorDescriptor.Gap classifyGap(Regexp re, int flags) {
    if (re == null || AstAnalysis.analyze(re).hasUserCaptures()) {
      return null;
    }
    re = unwrapCaptures(re);
    if (re == null) {
      return null;
    }
    if (re.op == RegexpOp.BEGIN_TEXT) {
      return MultiAnchorDescriptor.Gap.TEXT_START;
    }
    if (re.op == RegexpOp.END_TEXT) {
      return MultiAnchorDescriptor.Gap.TEXT_END;
    }
    if (re.op == RegexpOp.WORD_BOUNDARY) {
      return MultiAnchorDescriptor.Gap.WORD_BOUNDARY;
    }
    if (re.op == RegexpOp.NO_WORD_BOUNDARY) {
      return MultiAnchorDescriptor.Gap.NO_WORD_BOUNDARY;
    }
    if (re.op == RegexpOp.BEGIN_LINE) {
      return MultiAnchorDescriptor.Gap.LINE_START;
    }
    if (re.op == RegexpOp.END_LINE) {
      return MultiAnchorDescriptor.Gap.LINE_END;
    }
    boolean greedy = (re.flags & ParseFlags.NON_GREEDY) == 0;
    if (re.op == RegexpOp.STAR) {
      Regexp sub = unwrapCaptures(re.sub());
      if (sub != null && sub.op == RegexpOp.ANY_CHAR) {
        boolean dotAll =
            (flags & Pattern.DOTALL) != 0
                || (re.flags & (ParseFlags.DOT_NL | ParseFlags.MATCH_NL)) != 0
                || (sub.flags & (ParseFlags.DOT_NL | ParseFlags.MATCH_NL)) != 0;
        return dotAll
            ? (greedy
                ? MultiAnchorDescriptor.Gap.ANY_STAR_GREEDY
                : MultiAnchorDescriptor.Gap.ANY_STAR_LAZY)
            : (greedy
                ? MultiAnchorDescriptor.Gap.SINGLE_LINE_ANY_STAR_GREEDY
                : MultiAnchorDescriptor.Gap.SINGLE_LINE_ANY_STAR_LAZY);
      }
      if (sub != null && sub.op == RegexpOp.CHAR_CLASS) {
        if (isDotCharClass(sub.charClass)) {
          return greedy
              ? MultiAnchorDescriptor.Gap.SINGLE_LINE_ANY_STAR_GREEDY
              : MultiAnchorDescriptor.Gap.SINGLE_LINE_ANY_STAR_LAZY;
        }
        AsciiBitmap bitmap = buildAsciiBitmapFromCharClass(sub.charClass);
        CharClassScanInfo scanInfo = CharClassScanInfo.fromCharClass(sub.charClass);
        if (bitmap == null && scanInfo == null) {
          return null;
        }
        return new MultiAnchorDescriptor.Gap(
            MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT,
            0,
            Integer.MAX_VALUE,
            bitmap,
            scanInfo,
            greedy);
      }
    } else if (re.op == RegexpOp.PLUS) {
      Regexp sub = unwrapCaptures(re.sub());
      if (sub != null && sub.op == RegexpOp.ANY_CHAR) {
        boolean dotAll =
            (flags & Pattern.DOTALL) != 0
                || (re.flags & (ParseFlags.DOT_NL | ParseFlags.MATCH_NL)) != 0
                || (sub.flags & (ParseFlags.DOT_NL | ParseFlags.MATCH_NL)) != 0;
        return dotAll
            ? new MultiAnchorDescriptor.Gap(
                MultiAnchorDescriptor.GapKind.ANY_STAR, 1, Integer.MAX_VALUE, null, greedy)
            : new MultiAnchorDescriptor.Gap(
                MultiAnchorDescriptor.GapKind.SINGLE_LINE_ANY_STAR,
                1,
                Integer.MAX_VALUE,
                null,
                greedy);
      }
      if (sub != null && sub.op == RegexpOp.CHAR_CLASS) {
        if (isDotCharClass(sub.charClass)) {
          return new MultiAnchorDescriptor.Gap(
              MultiAnchorDescriptor.GapKind.SINGLE_LINE_ANY_STAR,
              1,
              Integer.MAX_VALUE,
              null,
              greedy);
        }
        AsciiBitmap bitmap = buildAsciiBitmapFromCharClass(sub.charClass);
        CharClassScanInfo scanInfo = CharClassScanInfo.fromCharClass(sub.charClass);
        if (bitmap == null && scanInfo == null) {
          return null;
        }
        return new MultiAnchorDescriptor.Gap(
            MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT,
            1,
            Integer.MAX_VALUE,
            bitmap,
            scanInfo,
            greedy);
      }
    } else if (re.op == RegexpOp.REPEAT) {
      Regexp sub = unwrapCaptures(re.sub());
      int max = re.max == -1 ? Integer.MAX_VALUE : re.max;
      if (sub != null && sub.op == RegexpOp.ANY_CHAR) {
        boolean dotAll =
            (flags & Pattern.DOTALL) != 0
                || (re.flags & (ParseFlags.DOT_NL | ParseFlags.MATCH_NL)) != 0
                || (sub.flags & (ParseFlags.DOT_NL | ParseFlags.MATCH_NL)) != 0;
        return dotAll
            ? new MultiAnchorDescriptor.Gap(
                MultiAnchorDescriptor.GapKind.ANY_STAR, re.min, max, null, greedy)
            : new MultiAnchorDescriptor.Gap(
                MultiAnchorDescriptor.GapKind.SINGLE_LINE_ANY_STAR, re.min, max, null, greedy);
      }
      if (sub != null && sub.op == RegexpOp.CHAR_CLASS) {
        if (isDotCharClass(sub.charClass)) {
          return new MultiAnchorDescriptor.Gap(
              MultiAnchorDescriptor.GapKind.SINGLE_LINE_ANY_STAR, re.min, max, null, greedy);
        }
        AsciiBitmap bitmap = buildAsciiBitmapFromCharClass(sub.charClass);
        CharClassScanInfo scanInfo = CharClassScanInfo.fromCharClass(sub.charClass);
        if (bitmap == null && scanInfo == null) {
          return null;
        }
        return new MultiAnchorDescriptor.Gap(
            MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT,
            re.min,
            max,
            bitmap,
            scanInfo,
            greedy);
      }
    } else if (re.op == RegexpOp.QUEST) {
      Regexp sub = unwrapCaptures(re.sub());
      if (sub != null && sub.op == RegexpOp.ANY_CHAR) {
        boolean dotAll =
            (flags & Pattern.DOTALL) != 0
                || (re.flags & (ParseFlags.DOT_NL | ParseFlags.MATCH_NL)) != 0
                || (sub.flags & (ParseFlags.DOT_NL | ParseFlags.MATCH_NL)) != 0;
        return dotAll
            ? new MultiAnchorDescriptor.Gap(
                MultiAnchorDescriptor.GapKind.ANY_STAR, 0, 1, null, greedy)
            : new MultiAnchorDescriptor.Gap(
                MultiAnchorDescriptor.GapKind.SINGLE_LINE_ANY_STAR, 0, 1, null, greedy);
      }
      if (sub != null && sub.op == RegexpOp.CHAR_CLASS) {
        if (isDotCharClass(sub.charClass)) {
          return new MultiAnchorDescriptor.Gap(
              MultiAnchorDescriptor.GapKind.SINGLE_LINE_ANY_STAR, 0, 1, null, greedy);
        }
        AsciiBitmap bitmap = buildAsciiBitmapFromCharClass(sub.charClass);
        CharClassScanInfo scanInfo = CharClassScanInfo.fromCharClass(sub.charClass);
        if (bitmap == null && scanInfo == null) {
          return null;
        }
        return new MultiAnchorDescriptor.Gap(
            MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT, 0, 1, bitmap, scanInfo, greedy);
      }
    } else if (re.op == RegexpOp.CHAR_CLASS) {
      AsciiBitmap bitmap = buildAsciiBitmapFromCharClass(re.charClass);
      CharClassScanInfo scanInfo = CharClassScanInfo.fromCharClass(re.charClass);
      if (bitmap == null && scanInfo == null) {
        return null;
      }
      return new MultiAnchorDescriptor.Gap(
          MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT, 1, 1, bitmap, scanInfo, true);
    }
    if (re.op == RegexpOp.ANY_CHAR) {
      boolean dotAll =
          (flags & Pattern.DOTALL) != 0
              || (re.flags & (ParseFlags.DOT_NL | ParseFlags.MATCH_NL)) != 0;
      return new MultiAnchorDescriptor.Gap(
          dotAll
              ? MultiAnchorDescriptor.GapKind.ANY_STAR
              : MultiAnchorDescriptor.GapKind.SINGLE_LINE_ANY_STAR,
          1,
          1,
          null,
          true);
    }
    AsciiWidthRange width = computeAsciiWidthRange(re);
    if (width.isValid() && width.maxWidth < Integer.MAX_VALUE) {
      return new MultiAnchorDescriptor.Gap(
          MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT,
          width.minWidth,
          width.maxWidth,
          width.discreteWidths,
          null,
          null,
          greedy);
    }
    return null;
  }

  static FixedOffsetLiteral extractFixedOffsetLiteral(Regexp re) {
    Regexp node = unwrapCaptures(re);
    if (node == null || node.op != RegexpOp.CONCAT || node.subs == null) {
      return null;
    }
    FixedOffsetLiteral best = null;
    int bestScore = 0;
    AsciiWidthRange prefixWidth = AsciiWidthRange.ZERO;

    for (int index = 0; index < node.subs.size(); ) {
      String literalPart = extractExactAsciiLiteral(node.subs.get(index));
      if (literalPart != null) {
        StringBuilder literal = new StringBuilder(literalPart);
        int next = index + 1;
        while (next < node.subs.size()) {
          String nextPart = extractExactAsciiLiteral(node.subs.get(next));
          if (nextPart == null) {
            break;
          }
          literal.append(nextPart);
          next++;
        }
        if (index > 0 && (prefixWidth.minWidth > 0 || prefixWidth.maxWidth > 0)) {
          int minimumLiteralLength = prefixWidth.discreteWidths != null ? 1 : 2;
          if (literal.length() >= minimumLiteralLength) {
            int candidateScore = RarityOracle.literalSelectivityScore(literal);
            if (best == null || candidateScore > bestScore) {
              best =
                  new FixedOffsetLiteral(
                      literal.toString(),
                      prefixWidth.minWidth,
                      prefixWidth.maxWidth,
                      prefixWidth.discreteWidths);
              bestScore = candidateScore;
            }
          }
        }
        prefixWidth = concatenateWidths(prefixWidth, AsciiWidthRange.exact(literal.length()));
        if (!prefixWidth.isValid()) {
          break;
        }
        index = next;
        continue;
      }

      prefixWidth = concatenateWidths(prefixWidth, computeAsciiWidthRange(node.subs.get(index)));
      if (!prefixWidth.isValid()) {
        break;
      }
      index++;
    }
    return best;
  }

  private static AsciiWidthRange computeAsciiWidthRange(Regexp re) {
    return new AsciiWidthRangeWalker().walk(re, AsciiWidthRange.INVALID);
  }

  private static final class AsciiWidthRangeWalker extends Walker<AsciiWidthRange> {
    @Override
    protected AsciiWidthRange postVisit(
        Regexp node,
        AsciiWidthRange parentArg,
        AsciiWidthRange preArg,
        List<AsciiWidthRange> childArgs) {
      return switch (node.op) {
        case CAPTURE, NON_CAPTURE ->
            childArgs.isEmpty() ? AsciiWidthRange.INVALID : childArgs.getFirst();
        case EMPTY_MATCH,
            BEGIN_LINE,
            END_LINE,
            BEGIN_TEXT,
            END_TEXT,
            WORD_BOUNDARY,
            NO_WORD_BOUNDARY ->
            AsciiWidthRange.ZERO;
        case LITERAL ->
            node.rune >= 0 && node.rune < 128 && (node.flags & ParseFlags.FOLD_CASE) == 0
                ? AsciiWidthRange.ONE
                : AsciiWidthRange.INVALID;
        case LITERAL_STRING -> literalStringWidth(node);
        case CHAR_CLASS -> characterClassWidth(node);
        case REPEAT -> repeatWidth(node, childArgs);
        case QUEST -> optionalWidth(childArgs);
        case ALTERNATE -> alternateWidth(childArgs);
        case CONCAT -> concatenateWidths(childArgs);
        default -> AsciiWidthRange.INVALID;
      };
    }

    @Override
    protected AsciiWidthRange shortVisit(Regexp re, AsciiWidthRange parentArg) {
      return AsciiWidthRange.INVALID;
    }

    private static AsciiWidthRange literalStringWidth(Regexp node) {
      if ((node.flags & ParseFlags.FOLD_CASE) != 0 || node.runes == null) {
        return AsciiWidthRange.INVALID;
      }
      for (int rune : node.runes) {
        if (rune < 0 || rune >= 128) {
          return AsciiWidthRange.INVALID;
        }
      }
      return AsciiWidthRange.exact(node.runes.length);
    }

    private static AsciiWidthRange characterClassWidth(Regexp node) {
      if (node.charClass == null || node.charClass.isEmpty()) {
        return AsciiWidthRange.INVALID;
      }
      return node.charClass.hi(node.charClass.numRanges() - 1) < 128
          ? AsciiWidthRange.ONE
          : AsciiWidthRange.NON_DISCRETE_ONE;
    }

    private static AsciiWidthRange repeatWidth(Regexp node, List<AsciiWidthRange> childArgs) {
      if (node.min < 0 || node.max < 0 || childArgs.isEmpty()) {
        return AsciiWidthRange.INVALID;
      }
      AsciiWidthRange child = childArgs.getFirst();
      if (!child.isValid()) {
        return AsciiWidthRange.INVALID;
      }
      int minWidth = multiplyWidth(child.minWidth, node.min);
      int maxWidth = multiplyWidth(child.maxWidth, node.max);
      if (minWidth < 0 || maxWidth < 0) {
        return AsciiWidthRange.INVALID;
      }
      if (child.discreteWidths != null && child.isExact() && node.max - node.min <= 8) {
        int[] discrete = new int[node.max - node.min + 1];
        for (int index = 0; index < discrete.length; index++) {
          int width = multiplyWidth(child.minWidth, node.min + index);
          if (width < 0) {
            return AsciiWidthRange.INVALID;
          }
          discrete[index] = width;
        }
        return new AsciiWidthRange(minWidth, maxWidth, discrete);
      }
      return new AsciiWidthRange(minWidth, maxWidth, null);
    }

    private static AsciiWidthRange optionalWidth(List<AsciiWidthRange> childArgs) {
      if (childArgs.isEmpty() || !childArgs.getFirst().isValid()) {
        return AsciiWidthRange.INVALID;
      }
      AsciiWidthRange child = childArgs.getFirst();
      if (child.discreteWidths == null) {
        return new AsciiWidthRange(0, child.maxWidth, null);
      }
      TreeSet<Integer> discrete = new TreeSet<>();
      discrete.add(0);
      for (int width : child.discreteWidths) {
        discrete.add(width);
      }
      return new AsciiWidthRange(
          0,
          child.maxWidth,
          discrete.size() <= 16 ? discrete.stream().mapToInt(Integer::intValue).toArray() : null);
    }

    private static AsciiWidthRange alternateWidth(List<AsciiWidthRange> childArgs) {
      if (childArgs.isEmpty()) {
        return AsciiWidthRange.INVALID;
      }
      int minWidth = Integer.MAX_VALUE;
      int maxWidth = Integer.MIN_VALUE;
      TreeSet<Integer> discrete = new TreeSet<>();
      boolean allDiscrete = true;
      for (AsciiWidthRange child : childArgs) {
        if (!child.isValid()) {
          return AsciiWidthRange.INVALID;
        }
        minWidth = Math.min(minWidth, child.minWidth);
        maxWidth = Math.max(maxWidth, child.maxWidth);
        if (allDiscrete && child.discreteWidths != null) {
          for (int width : child.discreteWidths) {
            discrete.add(width);
          }
        } else {
          allDiscrete = false;
        }
      }
      return new AsciiWidthRange(
          minWidth,
          maxWidth,
          allDiscrete && discrete.size() <= 8
              ? discrete.stream().mapToInt(Integer::intValue).toArray()
              : null);
    }
  }

  private static AsciiWidthRange concatenateWidths(List<AsciiWidthRange> widths) {
    AsciiWidthRange result = AsciiWidthRange.ZERO;
    for (AsciiWidthRange width : widths) {
      result = concatenateWidths(result, width);
      if (!result.isValid()) {
        return result;
      }
    }
    return result;
  }

  private static AsciiWidthRange concatenateWidths(AsciiWidthRange left, AsciiWidthRange right) {
    if (!left.isValid() || !right.isValid()) {
      return AsciiWidthRange.INVALID;
    }
    int minWidth = addWidth(left.minWidth, right.minWidth);
    int maxWidth = addWidth(left.maxWidth, right.maxWidth);
    if (minWidth < 0 || maxWidth < 0) {
      return AsciiWidthRange.INVALID;
    }
    int[] discrete = null;
    if (left.discreteWidths != null
        && right.discreteWidths != null
        && left.discreteWidths.length * right.discreteWidths.length <= 16) {
      TreeSet<Integer> combined = new TreeSet<>();
      for (int leftWidth : left.discreteWidths) {
        for (int rightWidth : right.discreteWidths) {
          int width = addWidth(leftWidth, rightWidth);
          if (width < 0) {
            return AsciiWidthRange.INVALID;
          }
          combined.add(width);
        }
      }
      discrete = combined.stream().mapToInt(Integer::intValue).toArray();
    }
    return new AsciiWidthRange(minWidth, maxWidth, discrete);
  }

  private static int addWidth(int left, int right) {
    return left > Integer.MAX_VALUE - right ? -1 : left + right;
  }

  private static int multiplyWidth(int width, int count) {
    return width != 0 && count > Integer.MAX_VALUE / width ? -1 : width * count;
  }

  private static boolean isLeadingZeroWidth(Regexp re) {
    return switch (re.op) {
      case EMPTY_MATCH, WORD_BOUNDARY, NO_WORD_BOUNDARY, BEGIN_LINE, BEGIN_TEXT -> true;
      default -> false;
    };
  }

  private static String[] extractLiteralAlternation(Regexp re) {
    if (re == null) {
      return null;
    }
    re = unwrapCaptures(re);
    if (re == null || re.op != RegexpOp.ALTERNATE || re.nsub() < 2) {
      return null;
    }
    String[] literals = new String[re.nsub()];
    for (int i = 0; i < re.nsub(); i++) {
      String lit = extractExactAsciiLiteral(re.subs.get(i));
      if (lit == null || lit.isEmpty()) {
        return null;
      }
      literals[i] = lit;
    }
    return literals;
  }

  private static Pattern.StartAcceleration extractStartAcceleration(Regexp re) {
    Regexp node = unwrapCaptures(re);
    if (node == null) {
      return null;
    }

    if (node.op == RegexpOp.CONCAT && node.nsub() > 0) {
      Regexp first = unwrapCaptures(node.subs.get(0));
      if (first != null && first.op == RegexpOp.BEGIN_LINE) {
        AsciiBitmap requiredStart = null;
        if (node.nsub() > 1) {
          requiredStart = requiredFirstAscii(node.subs.get(1));
        }
        return new Pattern.StartAcceleration(true, false, requiredStart);
      }
      return null;
    }

    if (node.op == RegexpOp.BEGIN_LINE) {
      return new Pattern.StartAcceleration(true, false, null);
    }
    return null;
  }

  private static AsciiBitmap requiredFirstAscii(Regexp re) {
    Regexp node = unwrapCaptures(re);
    if (node == null) {
      return null;
    }
    if (node.op == RegexpOp.CONCAT && node.nsub() > 0) {
      node = unwrapCaptures(node.subs.get(0));
    }
    if (node == null) {
      return null;
    }
    if (node.op == RegexpOp.PLUS || (node.op == RegexpOp.REPEAT && node.min >= 1)) {
      node = unwrapCaptures(node.sub());
    }
    if (node == null) {
      return null;
    }

    if (node.op == RegexpOp.LITERAL) {
      if ((node.flags & ParseFlags.FOLD_CASE) != 0 || node.rune >= 128) {
        return null;
      }
      return AsciiBitmap.of(node.rune);
    }
    if (node.op == RegexpOp.LITERAL_STRING && node.runes != null && node.runes.length > 0) {
      if ((node.flags & ParseFlags.FOLD_CASE) != 0 || node.runes[0] >= 128) {
        return null;
      }
      return AsciiBitmap.of(node.runes[0]);
    }
    if (node.op == RegexpOp.CHAR_CLASS && node.charClass != null) {
      CharClass cc = node.charClass;
      if (cc.isEmpty()) {
        return null;
      }
      for (int i = 0; i < cc.numRanges(); i++) {
        if (cc.hi(i) >= 128) {
          return null;
        }
      }
      AsciiBitmap.Builder builder = new AsciiBitmap.Builder();
      for (int i = 0; i < cc.numRanges(); i++) {
        builder.addRange(cc.lo(i), cc.hi(i));
      }
      return builder.build();
    }
    return null;
  }

  private static boolean addAsciiCharClass(CharClass cc, AsciiBitmap.Builder builder) {
    if (cc == null || cc.isEmpty()) {
      return false;
    }
    for (int i = 0; i < cc.numRanges(); i++) {
      if (cc.hi(i) >= 128) {
        return false;
      }
    }
    for (int i = 0; i < cc.numRanges(); i++) {
      builder.addRange(cc.lo(i), cc.hi(i));
    }
    return true;
  }

  private static Pattern.SuffixInfo extractEndAnchoredSuffix(Regexp metadataAst, int flags) {
    Regexp node = unwrapCaptures(metadataAst);
    if (node == null || node.op != RegexpOp.CONCAT || node.nsub() < 2) {
      return null;
    }
    List<Regexp> subs = node.subs;
    Regexp last = unwrapCaptures(subs.get(subs.size() - 1));
    if (last == null || last.op != RegexpOp.END_TEXT) {
      return null;
    }
    if ((flags & Pattern.MULTILINE) != 0 && (last.flags & ParseFlags.WAS_DOLLAR) != 0) {
      return null;
    }
    boolean wasDollar = (last.flags & ParseFlags.WAS_DOLLAR) != 0;
    boolean foldCase = false;

    Deque<String> suffixParts = new ArrayDeque<>();
    int suffixLength = 0;
    for (int i = subs.size() - 2; i >= 0; i--) {
      Regexp sub = unwrapCaptures(subs.get(i));
      if (sub == null) {
        break;
      }
      boolean subFold = (sub.flags & ParseFlags.FOLD_CASE) != 0;
      if (sub.op == RegexpOp.LITERAL) {
        if (subFold && sub.rune > 0x7F) {
          break;
        }
        String part = Character.toString(sub.rune);
        suffixParts.addFirst(part);
        suffixLength += part.length();
        foldCase |= subFold;
      } else if (sub.op == RegexpOp.LITERAL_STRING && sub.runes != null) {
        if (subFold && !isAllAscii(sub.runes)) {
          break;
        }
        String part = new String(sub.runes, 0, sub.runes.length);
        suffixParts.addFirst(part);
        suffixLength += part.length();
        foldCase |= subFold;
      } else {
        break;
      }
    }
    if (suffixParts.isEmpty()) {
      return null;
    }
    StringBuilder suffix = new StringBuilder(suffixLength);
    suffixParts.forEach(suffix::append);
    return new Pattern.SuffixInfo(
        suffix.toString(), wasDollar, (flags & Pattern.UNIX_LINES) != 0, foldCase);
  }

  private static boolean isAllAscii(int[] runes) {
    for (int r : runes) {
      if (r > 0x7F) {
        return false;
      }
    }
    return true;
  }

  private static Pattern.EndAnchoredCharClassInfo extractEndAnchoredCharClass(
      Regexp metadataAst, int flags) {
    Regexp node = unwrapCaptures(metadataAst);
    if (node == null || node.op != RegexpOp.CONCAT || node.nsub() < 2) {
      return null;
    }
    List<Regexp> subs = node.subs;
    Regexp last = unwrapCaptures(subs.get(subs.size() - 1));
    if (last == null || last.op != RegexpOp.END_TEXT) {
      return null;
    }
    if ((flags & Pattern.MULTILINE) != 0 && (last.flags & ParseFlags.WAS_DOLLAR) != 0) {
      return null;
    }
    boolean wasDollar = (last.flags & ParseFlags.WAS_DOLLAR) != 0;

    Regexp sub = unwrapCaptures(subs.get(subs.size() - 2));
    if (sub == null) {
      return null;
    }
    while (sub.op == RegexpOp.PLUS || (sub.op == RegexpOp.REPEAT && sub.min >= 1)) {
      sub = unwrapCaptures(sub.sub());
      if (sub == null) {
        return null;
      }
    }
    AsciiBitmap.Builder builder = new AsciiBitmap.Builder();
    if (sub.op == RegexpOp.CHAR_CLASS && addAsciiCharClass(sub.charClass, builder)) {
      boolean unixLines = (flags & Pattern.UNIX_LINES) != 0;
      return new Pattern.EndAnchoredCharClassInfo(builder.build(), wasDollar, unixLines);
    }
    return null;
  }

  private static String extractRequiredLiteral(
      Regexp re, String excludePrefix, String excludeSuffix) {
    String best = null;
    int bestScore = 0;
    Deque<Regexp> pending = new ArrayDeque<>();
    pending.addLast(re);
    while (!pending.isEmpty()) {
      Regexp node = pending.removeLast();
      switch (node.op) {
        case CAPTURE, NON_CAPTURE, PLUS -> pending.addLast(node.sub());
        case REPEAT -> {
          if (node.min > 0) {
            pending.addLast(node.sub());
          }
        }
        case CONCAT -> {
          if (node.subs != null) {
            String exactAscii = extractExactAsciiLiteral(node);
            if (exactAscii != null && exactAscii.length() >= 2) {
              if ((excludePrefix == null || !exactAscii.equals(excludePrefix))
                  && (excludeSuffix == null || !exactAscii.equals(excludeSuffix))) {
                int score = RarityOracle.literalSelectivityScore(exactAscii);
                if (best == null || score > bestScore) {
                  best = exactAscii;
                  bestScore = score;
                }
              }
            }
            for (Regexp sub : node.subs) {
              pending.addLast(sub);
            }
          }
        }
        case LITERAL_STRING -> {
          if ((node.flags & ParseFlags.FOLD_CASE) == 0
              && node.runes != null
              && node.runes.length >= 2) {
            String candidate = new String(node.runes, 0, node.runes.length);
            if ((excludePrefix == null || !candidate.equals(excludePrefix))
                && (excludeSuffix == null || !candidate.equals(excludeSuffix))) {
              int candidateScore = RarityOracle.literalSelectivityScore(candidate);
              if (best == null || candidateScore > bestScore) {
                best = candidate;
                bestScore = candidateScore;
              }
            }
          }
        }
        default -> {}
      }
    }
    return best;
  }

  private static String[] combineDisjointRequiredLiterals(List<NodeAnalysis> children) {
    if (children == null || children.size() < 2) {
      return null;
    }
    Set<String> literalSet = new LinkedHashSet<>();
    for (NodeAnalysis branch : children) {
      String req = branch.reject().bestRequiredLiteral();
      if (req == null || req.length() < 2) {
        return null;
      }
      literalSet.add(req);
      if (literalSet.size() > 4) {
        return null;
      }
    }
    List<String> rawList = new ArrayList<>(literalSet);
    List<int[]> rawCodePoints = new ArrayList<>(rawList.size());
    List<int[]> rawFailures = new ArrayList<>(rawList.size());
    for (String literal : rawList) {
      int[] codePoints = literal.codePoints().toArray();
      rawCodePoints.add(codePoints);
      rawFailures.add(literalFailure(codePoints));
    }
    Set<String> pruned = new LinkedHashSet<>();
    for (int i = 0; i < rawList.size(); i++) {
      String s1 = rawList.get(i);
      boolean subsumed = false;
      for (int j = 0; j < rawList.size(); j++) {
        if (i != j) {
          String s2 = rawList.get(j);
          if (containsCodePointSequence(
                  rawCodePoints.get(i), rawCodePoints.get(j), rawFailures.get(j))
              && (s1.length() > s2.length() || (s1.length() == s2.length() && j < i))) {
            subsumed = true;
            break;
          }
        }
      }
      if (!subsumed) {
        pruned.add(s1);
      }
    }
    if (pruned.size() < 2) {
      return null;
    }
    return pruned.toArray(new String[0]);
  }

  private static int[] literalFailure(int[] literal) {
    int[] failure = new int[literal.length];
    int matched = 0;
    for (int index = 1; index < literal.length; index++) {
      while (matched > 0 && literal[index] != literal[matched]) {
        matched = failure[matched - 1];
      }
      if (literal[index] == literal[matched]) {
        matched++;
      }
      failure[index] = matched;
    }
    return failure;
  }

  private static boolean containsCodePointSequence(int[] value, int[] candidate, int[] failure) {
    int matched = 0;
    for (int codePoint : value) {
      while (matched > 0 && codePoint != candidate[matched]) {
        matched = failure[matched - 1];
      }
      if (codePoint == candidate[matched]) {
        matched++;
        if (matched == candidate.length) {
          return true;
        }
      }
    }
    return false;
  }

  private static CharClass literalCharClass(int cp, int flags) {
    CharClassBuilder ccb = new CharClassBuilder();
    if ((flags & ParseFlags.FOLD_CASE) == 0) {
      ccb.addRange(cp, cp);
    } else if ((flags & ParseFlags.UNICODE_CASE) == 0) {
      UnicodeCaseFolding.addAsciiFoldedRange(ccb, cp, cp);
    } else {
      UnicodeCaseFolding.addUnicodeFoldedRange(ccb, cp, cp);
    }
    return ccb.build();
  }

  private static Regexp unwrapCaptures(Regexp re) {
    Regexp node = re;
    while (node != null && (node.op == RegexpOp.CAPTURE || node.op == RegexpOp.NON_CAPTURE)) {
      node = node.sub();
    }
    return node;
  }
}
