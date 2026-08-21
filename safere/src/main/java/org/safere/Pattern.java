// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.io.Serializable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A compiled regular expression backed by a linear-time NFA engine. This class provides a drop-in
 * replacement for {@link java.util.regex.Pattern}.
 *
 * <p>Unlike {@code java.util.regex.Pattern}, this implementation guarantees linear-time matching
 * regardless of the pattern or input. Features that require exponential time (backreferences,
 * lookahead, lookbehind) are not supported and will be rejected at compile time.
 *
 * <p>Usage:
 *
 * <pre>
 *   Pattern p = Pattern.compile("(\\w+)@(\\w+)");
 *   Matcher m = p.matcher("user@host");
 *   if (m.matches()) {
 *     String user = m.group(1);
 *   }
 * </pre>
 */
public final class Pattern implements Serializable {

  private static final long serialVersionUID = 1L;
  private static final AtomicLong NEXT_PATTERN_ID = new AtomicLong(1);
  private static final MethodType DIAGNOSTICS_TYPE =
      MethodType.methodType(SafeReMatchDiagnostics.class);
  private static final MutableCallSite DIAGNOSTICS_SITE = new MutableCallSite(DIAGNOSTICS_TYPE);
  private static final MethodHandle DIAGNOSTICS_INVOKER = DIAGNOSTICS_SITE.dynamicInvoker();
  private static final int MAX_DISJOINT_REQUIRED_LITERALS = 4;

  static {
    setDiagnosticsTarget(SafeReMatchDiagnostics.NONE);
  }

  /**
   * Enables Unix lines mode. In this mode, only {@code '\n'} is recognized as a line terminator.
   */
  public static final int UNIX_LINES = java.util.regex.Pattern.UNIX_LINES; // 1

  /**
   * Enables case-insensitive matching. By default, case-insensitive matching assumes only US-ASCII
   * characters. Unicode-aware case folding can be enabled with {@link #UNICODE_CASE}.
   */
  public static final int CASE_INSENSITIVE = java.util.regex.Pattern.CASE_INSENSITIVE; // 2

  /**
   * Permits whitespace and comments in the pattern. Whitespace is ignored, and comments starting
   * with {@code #} run to end-of-line.
   */
  public static final int COMMENTS = java.util.regex.Pattern.COMMENTS; // 4

  /**
   * Enables multiline mode. In multiline mode, {@code ^} and {@code $} match at the start and end
   * of each line, not just the start and end of the entire input.
   */
  public static final int MULTILINE = java.util.regex.Pattern.MULTILINE; // 8

  /**
   * Enables literal parsing of the pattern. Metacharacters and escape sequences have no special
   * meaning.
   */
  public static final int LITERAL = java.util.regex.Pattern.LITERAL; // 16

  /**
   * Enables dotall mode. In dotall mode, {@code .} matches any character including line
   * terminators.
   */
  public static final int DOTALL = java.util.regex.Pattern.DOTALL; // 32

  /**
   * Enables Unicode-aware case folding. When used with {@link #CASE_INSENSITIVE}, matching is done
   * in a manner consistent with the Unicode Standard.
   */
  public static final int UNICODE_CASE = java.util.regex.Pattern.UNICODE_CASE; // 64

  /**
   * Enables Unicode-aware character classes. When enabled, predefined character classes such as
   * {@code \w}, {@code \d}, and {@code \s} match Unicode characters instead of only ASCII.
   */
  public static final int UNICODE_CHARACTER_CLASS =
      java.util.regex.Pattern.UNICODE_CHARACTER_CLASS; // 256

  private final String pattern;
  private final int flags;
  private final transient Prog prog;
  private final transient Prog flatProg;
  private final transient Prog flatDfaProg;
  private final transient Regexp ast;

  private final transient Map<String, Integer> namedGroups;
  private final transient String prefix;
  private final transient boolean prefixFoldCase;
  private final transient MatchDescriptor matchDescriptor;
  private final transient byte[] literalMatchUtf8;
  private final transient int[] literalMatchFailure;
  private final transient int[] literalMatchShifts;
  private final transient byte[] prefixUtf8;
  private final transient String anchoredPrefix;
  private final transient byte[] anchoredPrefixUtf8;
  private final transient boolean hasLazy;
  private final transient boolean hasAlternation;
  private final transient boolean hasNullableAlternation;
  private final transient boolean canMatchEmpty;
  private final transient boolean startsWithGraphemeClusterBoundary;
  private final transient boolean hasInternalGraphemeClusterBoundary;
  private final transient CharClassScanInfo charClassPrefix;
  private final transient CharClassScanInfo anchoredCharClassPrefix;
  private final transient FixedOffsetLiteral fixedOffsetLiteral;
  private final transient Utf8StartAccelerator utf8StartAccelerator;
  private final transient StringStartAccelerator stringStartAccelerator;
  private final transient EnginePathOptions enginePathOptions;
  private final transient Matcher.PreparedMatchRunner defaultPreparedMatchRunner;
  private final transient Matcher.PreparedMatchRunner regionPreparedMatchRunner;
  private final long patternId;
  private transient volatile PatternAnalysis patternAnalysis;
  private transient volatile PatternDescriptor patternDescriptor;

  /** Whole-input rejection AST metadata for Tier 0 acceleration. */
  private final transient RejectDescriptor rejectDescriptor;

  /**
   * Whole-input rejection filter for Tier 0 acceleration. Non-null when matching can quickly reject
   * by verifying that mandatory literal content or character classes appear anywhere in the input
   * before running automata.
   */
  private final transient RejectPrefilter rejectPrefilter;

  /**
   * Lazily computed OnePass analysis results. Holds the OnePass automaton (if eligible) and derived
   * flags ({@code canOnePassFind}, {@code canOnePassSubmatch}). Computed on first access to avoid
   * paying the OnePass BFS cost at compile time.
   */
  private transient volatile OnePassAnalysis onePassAnalysis;

  /**
   * Whether a matcher created from this pattern has requested an inner capture. This adaptive
   * signal lets later small-input match operations avoid a redundant DFA pass when capture
   * extraction is demonstrably part of the workload.
   */
  private transient volatile boolean innerCapturesObserved;

  /**
   * Lazily computed DFA equivalence-class setup for the forward program. Shared across all Matcher
   * instances. Computed on first access to avoid paying the boundary-scan cost at compile time.
   */
  private transient volatile Dfa.Setup forwardDfaSetup;

  /**
   * Reverse-compiled program for backward DFA matching. Lazily computed on first access to avoid
   * paying the compilation cost for patterns that never need it (e.g., anchored patterns, patterns
   * used only with {@code matches()} or {@code lookingAt()}).
   */
  private transient volatile Prog reverseProg;

  private transient volatile Prog flatReverseProg;
  private transient volatile Prog flatReverseDfaProg;

  /** Lazily computed DFA setup for the reverse program. Computed alongside {@link #reverseProg}. */
  private transient volatile Dfa.Setup reverseDfaSetup;

  /**
   * Thread-local cached BitState instance. Shared across all Matchers created from this Pattern
   * within the same thread, enabling reuse even with the common {@code pattern.matcher(t).find()}
   * idiom where each call creates a new Matcher.
   */
  // Per-Pattern ThreadLocals are intentional: each Pattern caches its own DFA/BitState per thread,
  // so the warm state cache persists across the common pattern.matcher(t).find() idiom.
  @SuppressWarnings("ThreadLocalUsage")
  private final transient ThreadLocal<BitState> cachedBitState = new ThreadLocal<>();

  @SuppressWarnings("ThreadLocalUsage")
  private final transient ThreadLocal<Nfa> cachedNfa = new ThreadLocal<>();

  /**
   * Thread-local cached forward DFA. Shared across all Matchers created from this Pattern within
   * the same thread, so the DFA state cache persists across the common {@code
   * pattern.matcher(t).find()} idiom. The DFA's state cache is text-independent (keyed by NFA
   * instruction sets and flags), so it remains valid for any input text.
   */
  // Per-Pattern ThreadLocals are intentional; see cachedBitState above.
  @SuppressWarnings("ThreadLocalUsage")
  private final transient ThreadLocal<Dfa> cachedForwardFirstMatchDfa = new ThreadLocal<>();

  @SuppressWarnings("ThreadLocalUsage")
  private final transient ThreadLocal<Dfa> cachedForwardLongestMatchDfa = new ThreadLocal<>();

  /**
   * Thread-local cached reverse DFA. Shared like the forward DFA, enabling the DFA sandwich to run
   * with a warm state cache across Matcher instances.
   */
  // Per-Pattern ThreadLocals are intentional; see cachedBitState above.
  @SuppressWarnings("ThreadLocalUsage")
  private final transient ThreadLocal<Dfa> cachedReverseDfa = new ThreadLocal<>();

  /** Holder for lazily computed OnePass analysis results. */
  private record OnePassAnalysis(
      OnePass onePass, boolean canPrimary, boolean canFind, boolean canSubmatch) {
    static final OnePassAnalysis DISABLED = new OnePassAnalysis(null, false, false, false);
  }

  /** Precomputed metadata for a small disjoint set of required literal substrings. */
  // The array is owned by the immutable compiled Pattern and is never exposed publicly.
  @SuppressWarnings("ArrayRecordComponent")
  record DisjointRequiredLiterals(String[] literals) {

    static DisjointRequiredLiterals create(String[] literals) {
      if (literals == null || literals.length == 0) {
        return null;
      }
      return new DisjointRequiredLiterals(literals);
    }
  }

  private Pattern(
      String pattern,
      int flags,
      Prog prog,
      Regexp ast,
      Map<String, Integer> namedGroups,
      StartDescriptor startDescriptor,
      MatchDescriptor matchDescriptor,
      boolean hasLazy,
      boolean hasAlternation,
      boolean hasNullableAlternation,
      boolean canMatchEmpty,
      boolean startsWithGraphemeClusterBoundary,
      boolean hasInternalGraphemeClusterBoundary,
      RejectDescriptor rejectDescriptor,
      EnginePathOptions enginePathOptions) {
    this.patternId = nextPatternId();
    this.pattern = pattern;
    this.flags = flags;
    this.prog = prog;
    if (enginePathOptions.dfa()) {
      this.flatProg = new Prog(prog);
      this.flatProg.flatten();
      this.flatProg.freeze();
      if (prog.numLoopRegs() > 0) {
        Prog dfaProg = Compiler.compileForDfa(ast);
        if (dfaProg != null) {
          this.flatDfaProg = new Prog(dfaProg);
          this.flatDfaProg.flatten();
          this.flatDfaProg.freeze();
        } else {
          this.flatDfaProg = this.flatProg;
        }
      } else {
        this.flatDfaProg = this.flatProg;
      }
    } else {
      this.flatProg = null;
      this.flatDfaProg = null;
    }

    this.ast = ast;
    this.namedGroups = namedGroups;
    this.prefix = startDescriptor.prefix();
    this.prefixFoldCase = startDescriptor.prefixFoldCase();
    this.prefixUtf8 =
        startDescriptor.prefix() == null || startDescriptor.prefix().isEmpty()
            ? null
            : startDescriptor.prefix().getBytes(StandardCharsets.UTF_8);
    this.anchoredPrefix = startDescriptor.anchoredPrefix();
    this.anchoredPrefixUtf8 =
        anchoredPrefix == null || anchoredPrefix.isEmpty()
            ? null
            : anchoredPrefix.getBytes(StandardCharsets.UTF_8);
    this.matchDescriptor = matchDescriptor != null ? matchDescriptor : MatchDescriptor.NONE;
    String literalMatch = this.matchDescriptor.literalMatch();
    this.literalMatchUtf8 =
        literalMatch == null ? null : literalMatch.getBytes(StandardCharsets.UTF_8);
    this.literalMatchFailure = literalMatchUtf8 == null ? null : literalFailure(literalMatchUtf8);
    this.literalMatchShifts = literalMatchUtf8 == null ? null : literalShifts(literalMatchUtf8);
    this.hasLazy = hasLazy;
    this.hasAlternation = hasAlternation;
    this.hasNullableAlternation = hasNullableAlternation;
    this.canMatchEmpty = canMatchEmpty;
    this.startsWithGraphemeClusterBoundary = startsWithGraphemeClusterBoundary;
    this.hasInternalGraphemeClusterBoundary = hasInternalGraphemeClusterBoundary;
    this.charClassPrefix = startDescriptor.charClassPrefix();
    this.anchoredCharClassPrefix = startDescriptor.anchoredCharClassPrefix();
    this.fixedOffsetLiteral = startDescriptor.fixedOffsetLiteral();
    this.utf8StartAccelerator =
        Utf8StartAccelerator.create(startDescriptor, prog.hasWordBoundary());
    this.stringStartAccelerator =
        StringStartAccelerator.create(startDescriptor, prog.hasWordBoundary());
    this.enginePathOptions = enginePathOptions;
    this.rejectDescriptor = rejectDescriptor != null ? rejectDescriptor : RejectDescriptor.NONE;
    this.rejectPrefilter = RejectPrefilter.create(this.rejectDescriptor);
    this.defaultPreparedMatchRunner = createPreparedRunner(false);
    this.regionPreparedMatchRunner = createPreparedRunner(true);

    // Eagerly compute analysis and setup to avoid latency spikes on first use.
    if (shouldEagerlyBuildOnePass()) {
      onePassAnalysis();
    }
    forwardDfaSetup();
    if (canUseReverseDfa()) {
      flatReverseDfaProg();
    }

    SafeReMatchDiagnostics listener = diagnostics();
    if (SafeReMatchDiagnostics.isEnabled(listener)) {
      listener.onPatternCompiled(new PatternCompiledEvent(descriptor()));
    }
  }

  private static MatchDescriptor extractMatchDescriptor(
      Regexp metadataAst, Regexp sourceAst, int flags) {
    LiteralResult literalMatchResult = extractLiteralMatch(metadataAst);
    String literalMatch = literalMatchResult.literal();
    boolean literalFoldCase = literalMatchResult.foldCase();
    CharClassScanInfo singleCharClass = extractSingleCharClass(metadataAst);
    KeywordAlternation keywordAlternation = extractKeywordAlternation(metadataAst, flags);
    CharClassMatchInfo ccMatch = extractCharClassMatch(metadataAst);
    int minMatchLength = extractMinMatchLength(sourceAst);
    if (literalMatch == null
        && singleCharClass == null
        && keywordAlternation == null
        && ccMatch == null
        && minMatchLength <= 0) {
      return MatchDescriptor.NONE;
    }
    return new MatchDescriptor(
        literalMatch,
        literalFoldCase,
        singleCharClass,
        keywordAlternation,
        ccMatch,
        minMatchLength);
  }

  private static long nextPatternId() {
    long id = NEXT_PATTERN_ID.getAndIncrement();
    if (id <= 0) {
      throw new IllegalStateException("pattern diagnostics identifier space exhausted");
    }
    return id;
  }

  /**
   * Installs the process-wide synchronous match diagnostics listener.
   *
   * <p>The listener is invoked on compiling and matching threads and remains installed until
   * replaced. Use {@link SafeReMatchDiagnostics#NONE} to disable diagnostics.
   *
   * @param listener the listener to install
   */
  public static void setDiagnostics(SafeReMatchDiagnostics listener) {
    setDiagnosticsTarget(Objects.requireNonNull(listener, "listener"));
    MutableCallSite.syncAll(new MutableCallSite[] {DIAGNOSTICS_SITE});
  }

  /**
   * Returns the currently installed process-wide match diagnostics listener.
   *
   * @return the installed listener
   */
  public static SafeReMatchDiagnostics diagnostics() {
    try {
      return (SafeReMatchDiagnostics) DIAGNOSTICS_INVOKER.invokeExact();
    } catch (Throwable impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static void setDiagnosticsTarget(SafeReMatchDiagnostics listener) {
    DIAGNOSTICS_SITE.setTarget(MethodHandles.constant(SafeReMatchDiagnostics.class, listener));
  }

  /**
   * Compiles the given regular expression into a pattern with default flags.
   *
   * @param regex the expression to be compiled
   * @return the compiled pattern
   * @throws PatternSyntaxException if the expression's syntax is invalid
   */
  public static Pattern compile(String regex) {
    return compile(regex, 0);
  }

  /**
   * Compiles the given regular expression into a pattern with the given flags.
   *
   * @param regex the expression to be compiled
   * @param flags match flags, a bit mask of {@link #CASE_INSENSITIVE}, {@link #MULTILINE}, {@link
   *     #DOTALL}, {@link #UNICODE_CHARACTER_CLASS}, {@link #LITERAL}, {@link #COMMENTS}, {@link
   *     #UNIX_LINES}, and {@link #UNICODE_CASE}
   * @return the compiled pattern
   * @throws PatternSyntaxException if the expression's syntax is invalid
   * @throws IllegalArgumentException if the flags contain unsupported bits (e.g., {@code CANON_EQ})
   */
  public static Pattern compile(String regex, int flags) {
    return compile(regex, flags, EnginePathOptions.allEnabled());
  }

  static Pattern compile(String regex, int flags, EnginePathOptions enginePathOptions) {
    validateFlags(flags);
    Objects.requireNonNull(enginePathOptions, "enginePathOptions");
    int effectiveFlags = effectiveFlags(flags);
    int parseFlags = toParseFlags(effectiveFlags);
    Regexp re = Parser.parse(regex, parseFlags);
    Prog compiled = Compiler.compile(re);
    if (compiled == null) {
      throw new PatternSyntaxException("compiled program too large", regex, -1);
    }
    compiled.setUnixLines((effectiveFlags & UNIX_LINES) != 0);
    // Language-shape accelerators should see through source-only grouping. Correctness guards
    // below still inspect the source AST because source quantifiers carry matching semantics that
    // simplification deliberately lowers away.
    Regexp metadataAst = Simplifier.simplify(re);
    if (metadataAst == null) {
      throw new PatternSyntaxException("pattern too large to simplify", regex, -1);
    }
    Map<String, Integer> named = extractNamedGroups(re);
    StartDescriptor startDescriptor = extractStartDescriptor(metadataAst);
    MatchDescriptor matchDescriptor = extractMatchDescriptor(metadataAst, re, flags);
    boolean hasLazy = hasLazyQuantifiers(re);
    boolean hasAlt = hasAlternation(re);
    boolean canMatchEmpty = canMatchEmpty(re);
    boolean hasNullableAlt = hasAlt && hasNullableAlternation(re);
    boolean startsWithGcb = startsWithGraphemeClusterBoundary(metadataAst);
    boolean hasInternalGcb = hasInternalExplicitGraphemeBoundary(re);
    RejectDescriptor rejectDescriptor =
        extractRejectDescriptor(
            metadataAst, effectiveFlags, startDescriptor, compiled.anchorStart());
    // OnePass analysis and DFA setup are deferred to first use (lazy initialization).
    return new Pattern(
        regex,
        effectiveFlags,
        compiled,
        re,
        named,
        startDescriptor,
        matchDescriptor,
        hasLazy,
        hasAlt,
        hasNullableAlt,
        canMatchEmpty,
        startsWithGcb,
        hasInternalGcb,
        rejectDescriptor,
        enginePathOptions);
  }

  private static StartDescriptor extractStartDescriptor(Regexp metadataAst) {
    PrefixResult prefixResult = extractPrefix(metadataAst);
    String prefix = prefixResult.prefix();
    boolean prefixFoldCase = prefixResult.foldCase();
    FixedOffsetLiteral fixedOffsetLiteral =
        prefix == null ? extractFixedOffsetLiteral(metadataAst) : null;
    CharClassScanInfo ccPrefix = (prefix == null) ? extractCharClassPrefix(metadataAst) : null;
    String[] altLiterals = prefix == null ? extractLiteralAlternation(metadataAst) : null;
    TeddyModel teddyModel = null;
    if (altLiterals != null && altLiterals.length >= 2 && altLiterals.length <= 32) {
      teddyModel = TeddyModel.compileForSelectedProvider(altLiterals);
    }
    StartAcceleration startAcceleration =
        (prefix == null && ccPrefix == null && fixedOffsetLiteral == null && teddyModel == null)
            ? extractStartAcceleration(metadataAst)
            : null;
    Regexp anchoredCandidate = firstPrefixCandidateAfterTextAnchor(metadataAst);
    PrefixResult anchoredPrefixResult = extractPrefixFromCandidate(anchoredCandidate);
    String anchoredPrefix =
        anchoredPrefixResult.prefix() != null && !anchoredPrefixResult.foldCase()
            ? anchoredPrefixResult.prefix()
            : null;
    CharClassScanInfo anchoredCharClassPrefix =
        anchoredPrefix == null && anchoredCandidate != null
            ? extractCharClassPrefix(anchoredCandidate)
            : null;
    if (prefix == null
        && fixedOffsetLiteral == null
        && ccPrefix == null
        && startAcceleration == null
        && anchoredPrefix == null
        && anchoredCharClassPrefix == null
        && teddyModel == null) {
      return StartDescriptor.NONE;
    }
    return new StartDescriptor(
        prefix,
        prefixFoldCase,
        fixedOffsetLiteral,
        ccPrefix,
        startAcceleration,
        anchoredPrefix,
        anchoredCharClassPrefix,
        teddyModel);
  }

  /**
   * Compiles the given regular expression and attempts to match the given input against it. This is
   * equivalent to {@code Pattern.compile(regex).matcher(input).matches()}.
   *
   * @param regex the expression to be compiled
   * @param input the character sequence to be matched
   * @return {@code true} if the entire input matches the pattern
   * @throws PatternSyntaxException if the expression's syntax is invalid
   */
  public static boolean matches(String regex, CharSequence input) {
    return compile(regex).matcher(input).matches();
  }

  /**
   * Materializes a {@link CharSequence} by reading through {@code charAt()}, so custom
   * implementations that do not override {@code toString()} are handled correctly.
   */
  private static String charSequenceToString(CharSequence cs) {
    if (cs instanceof String s) {
      return s;
    }
    int len = cs.length();
    char[] chars = new char[len];
    for (int i = 0; i < len; i++) {
      chars[i] = cs.charAt(i);
    }
    return new String(chars);
  }

  /**
   * Returns a literal pattern string for the specified string. Metacharacters and escape sequences
   * in the returned string will have no special meaning.
   *
   * @param s the string to be literalized
   * @return a literal pattern string
   */
  public static String quote(String s) {
    // Use \Q...\E quoting. If the string contains \E, split around it.
    if (!s.contains("\\E")) {
      return "\\Q" + s + "\\E";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("\\Q");
    int last = 0;
    int idx;
    while ((idx = s.indexOf("\\E", last)) != -1) {
      sb.append(s, last, idx);
      sb.append("\\E\\\\E\\Q");
      last = idx + 2;
    }
    sb.append(s, last, s.length());
    sb.append("\\E");
    return sb.toString();
  }

  /**
   * Creates a matcher that will match the given input against this pattern.
   *
   * @param input the character sequence to be matched
   * @return a new matcher for this pattern
   */
  public Matcher matcher(CharSequence input) {
    return new Matcher(this, input);
  }

  /**
   * Creates a matcher over UTF-8 input whose positions are relative byte offsets.
   *
   * @param input borrowed UTF-8 input retained for the lifetime of the matcher
   * @return a new, non-thread-safe UTF-8 matcher
   */
  public Utf8Matcher matcher(Utf8Input input) {
    return new Utf8Matcher(this, input);
  }

  /**
   * Returns whether this pattern occurs in the supplied UTF-8 input.
   *
   * @param input borrowed UTF-8 input retained only for this call
   * @return whether the pattern occurs
   */
  public boolean find(Utf8Input input) {
    ArrayUtf8Input arrayInput = (ArrayUtf8Input) Objects.requireNonNull(input, "input");
    Utf8InputScanner scanner = arrayInput.scanner();
    SafeReMatchDiagnostics listener = diagnostics();
    if (SafeReMatchDiagnostics.isEnabled(listener)) {
      return findWithDiagnostics(scanner);
    }
    return findWithoutDiagnostics(scanner);
  }

  boolean findWithDiagnostics(Utf8InputScanner scanner) {
    SafeReMatchDiagnostics listener = diagnostics();
    DiagnosticAccumulator accumulator = new DiagnosticAccumulator();
    boolean matched = findWithDiagnostics(scanner, accumulator);
    accumulator.matchCount(matched ? 1 : 0);
    MatchOutcome outcome = matched ? MatchOutcome.MATCH : MatchOutcome.NO_MATCH;
    OperationDiagnostics event =
        accumulator.toEvent(
            descriptor(), MatchOperation.FIND, outcome, CaptureMode.NONE, scanner.length());
    listener.onOperationCompleted(event);
    return matched;
  }

  boolean findWithoutDiagnostics(Utf8InputScanner scanner) {
    int length = scanner.length();
    if (matchDescriptor.minMatchLength() > 0 && length < matchDescriptor.minMatchLength()) {
      return false;
    }
    if (literalMatchUtf8 != null && !literalFoldCase()) {
      if (prog.anchorStart()) {
        return scanner.startsWith(literalMatchUtf8, 0);
      }
      return scanner.indexOf(literalMatchUtf8, literalMatchFailure, literalMatchShifts) >= 0;
    }
    if (enginePathOptions.keywordAlternationFastPath()
        && matchDescriptor.keywordAlternation() != null) {
      return matchDescriptor.keywordAlternation().find(scanner, 0) >= 0;
    }
    if (prog.anchorStart()) {
      if (anchoredPrefixUtf8 != null) {
        if (!scanner.startsWith(anchoredPrefixUtf8, 0)) {
          return false;
        }
      } else if (anchoredCharClassPrefix != null) {
        if (scanner.length() == 0) {
          return false;
        }
        int cp = scanner.codePointAt(0);
        if (!anchoredCharClassPrefix.contains(cp)) {
          return false;
        }
      }
    }
    if (rejectPrefilter != null && rejectPrefilter.canReject(scanner, 0, enginePathOptions)) {
      return false;
    }
    int searchStart = 0;
    if (enginePathOptions.startAcceleration()
        && utf8StartAccelerator != null
        && !prog.anchorStart()) {
      searchStart = Utf8StartAccelerator.findNextCandidate(utf8StartAccelerator, scanner, 0);
      if (searchStart < 0) {
        return false;
      }
    }
    if (!prog.anchorEnd() && !prog.hasGraphemeSemantics() && prog.numLoopRegs() == 0) {
      Dfa.SearchResult result = forwardFirstMatchDfa().doSearch(scanner, searchStart, false, false);
      if (result != null) {
        return result.matched();
      }
    }
    return Nfa.search(
            prog,
            scanner,
            searchStart,
            length,
            length,
            0,
            Nfa.Anchor.UNANCHORED,
            Nfa.MatchKind.FIRST_MATCH,
            0,
            null)
        != null;
  }

  private boolean findWithDiagnostics(Utf8InputScanner scanner, DiagnosticAccumulator diagnostics) {
    int length = scanner.length();
    if (literalMatchUtf8 != null && !literalFoldCase()) {
      boolean matched =
          prog.anchorStart()
              ? scanner.startsWith(literalMatchUtf8, 0)
              : scanner.indexOf(literalMatchUtf8, literalMatchFailure, literalMatchShifts) >= 0;
      diagnostics.boundary(MatchStrategy.LITERAL);
      return matched;
    }
    if (enginePathOptions.keywordAlternationFastPath()
        && matchDescriptor.keywordAlternation() != null) {
      boolean matched = matchDescriptor.keywordAlternation().find(scanner, 0) >= 0;
      diagnostics.boundary(MatchStrategy.KEYWORD);
      return matched;
    }
    if (prog.anchorStart()) {
      if (anchoredPrefixUtf8 != null) {
        if (!scanner.startsWith(anchoredPrefixUtf8, 0)) {
          diagnostics.participate(MatchStrategy.LITERAL, StrategyRole.REJECT_PREFILTER);
          diagnostics.boundary(MatchStrategy.LITERAL);
          return false;
        }
      } else if (anchoredCharClassPrefix != null) {
        if (scanner.length() == 0) {
          diagnostics.participate(MatchStrategy.CHARACTER_CLASS, StrategyRole.REJECT_PREFILTER);
          diagnostics.boundary(MatchStrategy.CHARACTER_CLASS);
          return false;
        }
        int cp = scanner.codePointAt(0);
        if (!anchoredCharClassPrefix.contains(cp)) {
          diagnostics.participate(MatchStrategy.CHARACTER_CLASS, StrategyRole.REJECT_PREFILTER);
          diagnostics.boundary(MatchStrategy.CHARACTER_CLASS);
          return false;
        }
      }
    }
    if (rejectPrefilter != null
        && rejectPrefilter.canRejectWithDiagnostics(scanner, 0, enginePathOptions, diagnostics)) {
      return false;
    }
    int searchStart = 0;
    if (enginePathOptions.startAcceleration()
        && utf8StartAccelerator != null
        && !prog.anchorStart()) {
      MatchStrategy strategy = utf8StartAccelerator.policy().strategy();
      if (strategy != null) {
        diagnostics.participate(strategy, StrategyRole.START_ACCELERATION);
      }
      searchStart = Utf8StartAccelerator.findNextCandidate(utf8StartAccelerator, scanner, 0);
      if (searchStart < 0) {
        if (strategy != null) {
          diagnostics.boundary(strategy);
        }
        return false;
      }
    }
    if (!prog.anchorEnd() && !prog.hasGraphemeSemantics() && prog.numLoopRegs() == 0) {
      diagnostics.participate(MatchStrategy.DFA, StrategyRole.REJECT_PREFILTER);
      diagnostics.incrementForwardDfaSearchCount();
      Dfa.SearchResult result = forwardFirstMatchDfa().doSearch(scanner, searchStart, false, false);
      if (result != null) {
        diagnostics.boundary(MatchStrategy.DFA);
        return result.matched();
      }
      diagnostics.decision(
          MatchStrategy.DFA, StrategyDisposition.FALLBACK, StrategyReason.DFA_BUDGET_EXCEEDED);
    }
    boolean matched =
        Nfa.search(
                prog,
                scanner,
                searchStart,
                length,
                length,
                0,
                Nfa.Anchor.UNANCHORED,
                Nfa.MatchKind.FIRST_MATCH,
                0,
                null)
            != null;
    diagnostics.boundary(MatchStrategy.NFA);
    return matched;
  }

  static int[] literalFailure(byte[] literal) {
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

  static int[] literalShifts(byte[] literal) {
    if (literal.length < 2) {
      return null;
    }
    int[] shifts = new int[256];
    Arrays.fill(shifts, literal.length);
    for (int index = 0; index < literal.length - 1; index++) {
      shifts[literal[index] & 0xFF] = literal.length - index - 1;
    }
    return shifts;
  }

  /**
   * Returns the match flags specified when this pattern was compiled.
   *
   * @return the match flags
   */
  public int flags() {
    return flags;
  }

  /**
   * Returns the regular expression from which this pattern was compiled.
   *
   * @return the source of this pattern
   */
  public String pattern() {
    return pattern;
  }

  /**
   * Splits the given input around matches of this pattern. Trailing empty strings are discarded.
   *
   * @param input the character sequence to be split
   * @return the array of strings computed by splitting the input around matches of this pattern
   */
  public String[] split(CharSequence input) {
    return split(input, 0);
  }

  /**
   * Splits the given input around matches of this pattern.
   *
   * <p>The {@code limit} parameter controls the number of times the pattern is applied:
   *
   * <ul>
   *   <li>If {@code limit > 0}, the pattern is applied at most {@code limit - 1} times, and the
   *       resulting array will have at most {@code limit} entries.
   *   <li>If {@code limit == 0}, the pattern is applied as many times as possible, and trailing
   *       empty strings are discarded.
   *   <li>If {@code limit < 0}, the pattern is applied as many times as possible, and trailing
   *       empty strings are retained.
   * </ul>
   *
   * @param input the character sequence to be split
   * @param limit the result threshold
   * @return the array of strings computed by splitting the input around matches of this pattern
   */
  public String[] split(CharSequence input, int limit) {
    String text = charSequenceToString(input);
    Matcher m = matcher(text);
    Matcher.SplitBuffer buffer = new Matcher.SplitBuffer();
    int matchesCount = m.findSplitPositions(limit, buffer);
    if (matchesCount == 0) {
      return new String[] {text};
    }
    int partsCount = (limit > 0) ? Math.min(limit, matchesCount + 1) : matchesCount + 1;
    String[] parts = new String[partsCount];
    int last = 0;
    int i = 0;
    while (i < partsCount - 1) {
      int start = buffer.array[2 * i];
      int end = buffer.array[2 * i + 1];
      parts[i] = text.substring(last, start);
      last = end;
      i++;
    }
    parts[i] = text.substring(last);

    if (limit == 0) {
      int end = partsCount;
      while (end > 0 && parts[end - 1].isEmpty()) {
        end--;
      }
      if (end < partsCount) {
        parts = Arrays.copyOf(parts, end);
      }
    }
    return parts;
  }

  /**
   * Splits the given input around matches of this pattern, returning both the substrings between
   * matches and the matching delimiters, interleaved. The resulting array alternates between
   * substrings and delimiters: {@code [substring, delimiter, substring, delimiter, ...,
   * substring]}.
   *
   * <p>This is equivalent to {@code splitWithDelimiters(input, 0)}.
   *
   * @param input the character sequence to be split
   * @return the array of strings computed by splitting the input around matches of this pattern,
   *     with the matching delimiters interleaved
   * @since 21
   */
  public String[] splitWithDelimiters(CharSequence input) {
    return splitWithDelimiters(input, 0);
  }

  /**
   * Splits the given input around matches of this pattern, returning both the substrings between
   * matches and the matching delimiters, interleaved.
   *
   * <p>The {@code limit} parameter controls the number of times the pattern is applied:
   *
   * <ul>
   *   <li>If {@code limit > 0}, the pattern is applied at most {@code limit - 1} times, and the
   *       resulting array will have at most {@code 2 * limit - 1} entries.
   *   <li>If {@code limit == 0}, the pattern is applied as many times as possible, and trailing
   *       empty strings are discarded.
   *   <li>If {@code limit < 0}, the pattern is applied as many times as possible, and trailing
   *       empty strings are retained.
   * </ul>
   *
   * @param input the character sequence to be split
   * @param limit the result threshold
   * @return the array of strings computed by splitting the input around matches of this pattern,
   *     with the matching delimiters interleaved
   * @since 21
   */
  public String[] splitWithDelimiters(CharSequence input, int limit) {
    String text = charSequenceToString(input);
    Matcher m = matcher(text);
    Matcher.SplitBuffer buffer = new Matcher.SplitBuffer();
    int matchesCount = m.findSplitPositions(limit, buffer);
    if (matchesCount == 0) {
      return new String[] {text};
    }
    int partsCount = 2 * matchesCount + 1;
    if (limit > 0) {
      partsCount = (int) Math.min(2L * limit - 1, partsCount);
    }
    String[] parts = new String[partsCount];
    int last = 0;
    int i = 0;
    int matchIdx = 0;
    while (i < partsCount - 1) {
      int start = buffer.array[2 * matchIdx];
      int end = buffer.array[2 * matchIdx + 1];
      parts[i++] = text.substring(last, start);
      parts[i++] = text.substring(start, end);
      last = end;
      matchIdx++;
    }
    parts[i] = text.substring(last);

    if (limit == 0) {
      int end = partsCount;
      while (end > 0 && parts[end - 1].isEmpty()) {
        end--;
      }
      if (end < partsCount) {
        parts = Arrays.copyOf(parts, end);
      }
    }
    return parts;
  }

  /**
   * Creates a stream of strings split from the given input sequence around matches of this pattern.
   * The stream contains the same strings that {@link #split(CharSequence)} would return, produced
   * lazily.
   *
   * @param input the character sequence to be split
   * @return a sequential stream of strings computed by splitting the input around matches of this
   *     pattern
   */
  public Stream<String> splitAsStream(CharSequence input) {
    return StreamSupport.stream(
        () -> Arrays.spliterator(split(input, 0)),
        Spliterator.ORDERED | Spliterator.NONNULL,
        false);
  }

  /**
   * Creates a predicate that tests if this pattern is found in a given input string. The predicate
   * behaves as if calling {@code matcher(input).find()}.
   *
   * @return a predicate for partial matching
   */
  public Predicate<String> asPredicate() {
    return input -> matcher(input).find();
  }

  /**
   * Creates a predicate that tests if this pattern matches a given input string in its entirety.
   * The predicate behaves as if calling {@code matcher(input).matches()}.
   *
   * @return a predicate for full matching
   */
  public Predicate<String> asMatchPredicate() {
    return input -> matcher(input).matches();
  }

  @Override
  public String toString() {
    return pattern;
  }

  // ---------------------------------------------------------------------------
  // Package-private accessors for Matcher
  // ---------------------------------------------------------------------------

  /** Returns the compiled program. */
  Prog prog() {
    return prog;
  }

  EnginePathOptions enginePathOptions() {
    return enginePathOptions;
  }

  Matcher.PreparedMatchRunner preparedMatchRunner(boolean regionActive) {
    return regionActive ? regionPreparedMatchRunner : defaultPreparedMatchRunner;
  }

  private Matcher.PreparedMatchRunner createPreparedRunner(boolean regionActive) {
    String literal = matchDescriptor.literalMatch();
    if (enginePathOptions.literalFastPaths() && literal != null && numGroups() == 0) {
      return new Matcher.LiteralPreparedRunner(
          literal,
          matchDescriptor.literalFoldCase(),
          literalMatchUtf8,
          literalMatchFailure,
          literalMatchShifts,
          prog.anchorStart(),
          matchDescriptor.literalFoldCase()
              ? createLiteralFallbackRunner(regionActive)
              : Matcher.FallbackPreparedRunner.INSTANCE);
    }

    CharClassScanInfo singleCharClass = matchDescriptor.singleCharClass();
    Pattern.CharClassMatchInfo charClassMatch = matchDescriptor.charClassMatch();
    if (enginePathOptions.charClassMatchFastPaths()
        && (singleCharClass != null || charClassMatch != null)) {
      return new Matcher.SingleCharClassPreparedRunner(
          singleCharClass, charClassMatch, prog.anchorStart());
    }

    if (regionActive) {
      return Matcher.FallbackPreparedRunner.INSTANCE;
    }

    Pattern.KeywordAlternation keywordAlternation = matchDescriptor.keywordAlternation();
    if (enginePathOptions.keywordAlternationFastPath() && keywordAlternation != null) {
      return new Matcher.KeywordAlternationPreparedRunner(
          keywordAlternation, prog.numCaptures(), prog.anchorStart());
    }

    if (enginePathOptions.onePass() && (canOnePassFind() || canOnePassPrimary())) {
      return new Matcher.OnePassAnchoredPreparedRunner(prog.numCaptures());
    }

    return Matcher.FallbackPreparedRunner.INSTANCE;
  }

  private Matcher.PreparedMatchRunner createLiteralFallbackRunner(boolean regionActive) {
    CharClassScanInfo singleCharClass = matchDescriptor.singleCharClass();
    Pattern.CharClassMatchInfo charClassMatch = matchDescriptor.charClassMatch();
    if (enginePathOptions.charClassMatchFastPaths()
        && (singleCharClass != null || charClassMatch != null)) {
      return new Matcher.SingleCharClassPreparedRunner(
          singleCharClass, charClassMatch, prog.anchorStart());
    }

    if (regionActive) {
      return Matcher.FallbackPreparedRunner.INSTANCE;
    }

    Pattern.KeywordAlternation keywordAlternation = matchDescriptor.keywordAlternation();
    if (enginePathOptions.keywordAlternationFastPath() && keywordAlternation != null) {
      return new Matcher.KeywordAlternationPreparedRunner(
          keywordAlternation, prog.numCaptures(), prog.anchorStart());
    }

    if (enginePathOptions.onePass()) {
      return new Matcher.OnePassAnchoredPreparedRunner(prog.numCaptures());
    }

    return Matcher.FallbackPreparedRunner.INSTANCE;
  }

  boolean innerCapturesObserved() {
    return innerCapturesObserved;
  }

  void recordInnerCaptureAccess() {
    if (!innerCapturesObserved) {
      innerCapturesObserved = true;
    }
  }

  /** Returns the thread-local cached BitState, or null if none has been cached yet. */
  BitState borrowBitState() {
    BitState bs = cachedBitState.get();
    cachedBitState.set(null); // take ownership
    return bs;
  }

  /** Returns a BitState to the thread-local cache for reuse by future Matchers. */
  void returnBitState(BitState bs) {
    bs.releaseInput();
    cachedBitState.set(bs);
  }

  Nfa borrowNfa() {
    Nfa nfa = cachedNfa.get();
    cachedNfa.set(null);
    return nfa;
  }

  void returnNfa(Nfa nfa) {
    nfa.releaseInputContext();
    cachedNfa.set(nfa);
  }

  /** Maximum number of DFA states before the DFA bails out. */
  static final int MAX_DFA_STATES = 10_000;

  /**
   * Returns the thread-local cached forward DFA, creating it on first access. The DFA state cache
   * persists across Matcher instances, so repeated {@code pattern.matcher(t).find()} calls benefit
   * from warm DFA transitions.
   */
  Prog flatProg() {
    return flatProg;
  }

  Prog flatDfaProg() {
    return flatDfaProg;
  }

  Dfa forwardFirstMatchDfa() {
    Dfa dfa = cachedForwardFirstMatchDfa.get();
    if (dfa == null) {
      dfa =
          new Dfa(
              flatDfaProg,
              MAX_DFA_STATES,
              forwardDfaSetup(),
              false,
              enginePathOptions.startAcceleration() ? utf8StartAccelerator : null,
              enginePathOptions.startAcceleration() ? stringStartAccelerator : null);
      cachedForwardFirstMatchDfa.set(dfa);
    }
    return dfa;
  }

  Dfa forwardLongestMatchDfa() {
    Dfa dfa = cachedForwardLongestMatchDfa.get();
    if (dfa == null) {
      dfa =
          new Dfa(
              flatDfaProg,
              MAX_DFA_STATES,
              forwardDfaSetup(),
              true,
              enginePathOptions.startAcceleration() ? utf8StartAccelerator : null,
              enginePathOptions.startAcceleration() ? stringStartAccelerator : null);
      cachedForwardLongestMatchDfa.set(dfa);
    }
    return dfa;
  }

  /**
   * Returns the thread-local cached reverse DFA, creating it on first access. Triggers lazy
   * compilation of the reverse program if needed.
   */
  Dfa reverseDfa() {
    Dfa dfa = cachedReverseDfa.get();
    if (dfa == null) {
      Prog rp = flatReverseDfaProg();
      if (rp != null) {
        dfa = new Dfa(rp, MAX_DFA_STATES, reverseDfaSetup(), true);
        cachedReverseDfa.set(dfa);
      }
    }
    return dfa;
  }

  /**
   * Returns the lazily computed OnePass analysis results. Thread-safe via volatile: benign data
   * race at worst computes twice, but the result is the same since all inputs are immutable.
   */
  private OnePassAnalysis onePassAnalysis() {
    OnePassAnalysis analysis = onePassAnalysis;
    if (analysis == null) {
      // Lazy quantifiers are excluded because OnePass returns leftmost-longest capture group
      // boundaries, which differs from leftmost-first semantics for lazy groups. When hasLazy is
      // true, neither canPrimary nor canSubmatch can use OnePass, so we can skip building OnePass.
      if (hasLazy || prog.numCaptures() > OnePass.MAX_CAPTURE_GROUPS) {
        analysis = OnePassAnalysis.DISABLED;
      } else {
        OnePass op = OnePass.build(prog);
        // OnePass can be used as the primary matching engine (bypassing DFA entirely) when the
        // pattern is non-nullable and has no lazy quantifiers. Nullable patterns (e.g., a*|c.)
        // must be excluded because OnePass returns leftmost-longest semantics, which disagrees
        // with JDK's leftmost-first (biased) semantics for nullable alternations.
        boolean canPrimary =
            op != null
                && op.search("", false, 0) == null
                && !hasNullableAlternation
                && !prog.hasGraphemeSemantics();
        // canFind is canPrimary restricted to anchored patterns (legacy flag).
        boolean canFind = canPrimary && prog.anchorStart();
        // OnePass can be used for the sandwich submatch extraction step (anchored, endMatch=true)
        // when captures need to be extracted from a known match range. Nullable patterns are safe
        // here because match bounds are already known.
        boolean canSubmatch = op != null;
        analysis = new OnePassAnalysis(op, canPrimary, canFind, canSubmatch);
      }
      onePassAnalysis = analysis;
    }
    return analysis;
  }

  /** Returns the one-pass automaton, or {@code null} if the pattern is not one-pass. */
  OnePass onePass() {
    return onePassAnalysis().onePass();
  }

  /**
   * Returns whether OnePass can be used as the primary matching engine, bypassing the DFA entirely.
   * This is true when the pattern is OnePass-eligible, non-nullable, and has no lazy quantifiers.
   * The non-nullable restriction prevents leftmost-first ambiguity bugs where a nullable
   * alternative (e.g., {@code a*} in {@code a*|c.}) would incorrectly lose to a longer alternative
   * under OnePass's longest-match semantics.
   */
  boolean canOnePassPrimary() {
    return onePassAnalysis().canPrimary();
  }

  /**
   * Returns whether OnePass can be used directly in {@code find()} for anchored patterns. This is
   * {@link #canOnePassPrimary()} restricted to patterns anchored at the start.
   */
  boolean canOnePassFind() {
    return onePassAnalysis().canFind();
  }

  /**
   * Returns whether OnePass can be used for submatch extraction in the sandwich path. This is true
   * when the pattern is OnePass-eligible and has no lazy quantifiers. Nullable patterns are safe
   * here because match bounds are already determined by the DFA.
   */
  boolean canOnePassSubmatch() {
    return onePassAnalysis().canSubmatch();
  }

  /**
   * Returns {@code true} when the DFA's leftmost-longest group(0) boundaries are guaranteed to
   * match RE2's leftmost-first semantics. The DFA uses POSIX leftmost-longest matching which can
   * disagree with Perl/RE2 leftmost-first semantics in three cases:
   *
   * <ol>
   *   <li>Lazy quantifiers: prefer shortest match, but the DFA gives longest.
   *   <li>Alternation: the DFA picks the longest branch, but RE2 picks the first matching branch.
   *   <li>Bounded repetitions ({@code a{3,4}}): nested inside quantifiers, the DFA may find a
   *       globally longer match by choosing fewer characters per iteration, while RE2 greedily
   *       maximizes each iteration.
   * </ol>
   *
   * <p>When this returns {@code false}, the DFA sandwich is skipped and the submatch engine
   * (BitState/NFA) determines the correct match boundaries.
   */
  boolean dfaGroupZeroReliable() {
    return prog.numLoopRegs() == 0;
  }

  /**
   * Returns {@code true} if the pattern contains alternation ({@code |}). Used by the Matcher to
   * skip OnePass primary for find() — OnePass always uses longest-match semantics, which can pick
   * the wrong alternative when a zero-width branch competes with a consuming branch.
   */
  boolean hasAlternation() {
    return hasAlternation;
  }

  /**
   * Returns {@code true} if the pattern contains an alternation where at least one branch can match
   * zero characters. This is the specific case where OnePass's longest-match semantics produce
   * incorrect results: a zero-width branch (assertion, nullable repetition) loses to a consuming
   * branch under longest-match, but should win under first-match (leftmost-first).
   *
   * <p>When this returns {@code false}, alternations are safe for OnePass because all branches must
   * consume at least one character, making longest-match and first-match equivalent.
   */
  boolean hasNullableAlternation() {
    return hasNullableAlternation;
  }

  /** Returns {@code true} if this pattern can match zero characters. */
  boolean canMatchEmpty() {
    return canMatchEmpty;
  }

  /** Returns whether this pattern contains any lazy quantifiers. */
  boolean hasLazyQuantifiers() {
    return hasLazy;
  }

  /**
   * Returns true if this pattern can participate in reverse DFA matching (e.g. unanchored find or
   * end-anchored reverse-first rejection).
   */
  boolean canUseReverseDfa() {
    return !prog.anchorStart() && !matchDescriptor.hasFindFastPath();
  }

  private boolean shouldEagerlyBuildOnePass() {
    return !hasLazy && !matchDescriptor.hasFindFastPath();
  }

  /**
   * Returns the literal prefix for this pattern, or {@code null} if the pattern has no fixed
   * literal prefix. Used for prefix acceleration in {@link Matcher#doFind()}.
   */
  String prefix() {
    return prefix;
  }

  /** Returns whether the prefix should be matched case-insensitively. */
  boolean prefixFoldCase() {
    return prefixFoldCase;
  }

  /**
   * Returns a {@link CharClassScanInfo} of the character-class prefix, or {@code null} if the
   * pattern has no character-class prefix. Used for prefix acceleration in {@link Matcher#doFind()}
   * when no literal prefix exists.
   */
  CharClassScanInfo charClassPrefix() {
    return charClassPrefix;
  }

  String anchoredPrefix() {
    return anchoredPrefix;
  }

  byte[] anchoredPrefixUtf8() {
    return anchoredPrefixUtf8;
  }

  CharClassScanInfo anchoredCharClassPrefix() {
    return anchoredCharClassPrefix;
  }

  /** Returns a mandatory ASCII literal at a fixed offset from the match start, or {@code null}. */
  FixedOffsetLiteral fixedOffsetLiteral() {
    return fixedOffsetLiteral;
  }

  /** Returns the compiled UTF-8 start-position accelerator strategy, or {@code null}. */
  Utf8StartAccelerator utf8StartAccelerator() {
    return utf8StartAccelerator;
  }

  /** Returns the compiled String start-position accelerator strategy, or {@code null}. */
  StringStartAccelerator stringStartAccelerator() {
    return stringStartAccelerator;
  }

  MatchDescriptor matchDescriptor() {
    return matchDescriptor;
  }

  /** Returns case-insensitive keyword-alternation fast-path data, or {@code null}. */
  KeywordAlternation keywordAlternation() {
    return matchDescriptor.keywordAlternation();
  }

  /** Returns AST metadata for whole-input rejection, or {@link RejectDescriptor#NONE}. */
  RejectDescriptor rejectDescriptor() {
    return rejectDescriptor;
  }

  /** Returns whole-input rejection prefilter, or {@code null}. */
  RejectPrefilter rejectPrefilter() {
    return rejectPrefilter;
  }

  DisjointRequiredLiterals disjointRequiredLiterals() {
    return rejectDescriptor.disjointRequiredLiterals();
  }

  String[] requiredDisjointLiterals() {
    return rejectDescriptor.disjointRequiredLiterals() != null
        ? rejectDescriptor.disjointRequiredLiterals().literals()
        : null;
  }

  /**
   * Returns the reverse-compiled program for backward DFA matching. The reverse program is compiled
   * lazily on first access, since many patterns never need it (anchored patterns, patterns used
   * only with {@code matches()} or {@code lookingAt()}, single-find workloads).
   *
   * <p>Thread-safe via volatile: benign data race at worst compiles twice, but {@link Prog} is
   * effectively immutable once constructed.
   */
  Prog reverseProg() {
    Prog rp = reverseProg;
    if (rp == null) {
      rp = Compiler.compile(ast, true);
      reverseProg = rp;
    }
    return rp;
  }

  Prog flatReverseProg() {
    Prog frp = flatReverseProg;
    if (frp == null) {
      Prog rp = reverseProg();
      if (rp != null) {
        frp = new Prog(rp);
        frp.flatten();
        frp.freeze();
        reverseDfaSetup = Dfa.buildSetup(frp);
        flatReverseProg = frp;
      }
    }
    return frp;
  }

  Prog flatReverseDfaProg() {
    Prog frp = flatReverseDfaProg;
    if (frp == null) {
      Prog rp = reverseProg();
      if (rp != null) {
        if (rp.numLoopRegs() > 0) {
          Prog dfaRp = Compiler.compileForDfa(ast, true);
          if (dfaRp != null) {
            frp = new Prog(dfaRp);
            frp.flatten();
            frp.freeze();
          } else {
            frp = flatReverseProg();
          }
        } else {
          frp = flatReverseProg();
        }
        reverseDfaSetup = Dfa.buildSetup(frp);
        flatReverseDfaProg = frp;
      }
    }
    return frp;
  }

  Dfa.Setup forwardDfaSetup() {
    Dfa.Setup setup = forwardDfaSetup;
    if (setup == null) {
      setup = Dfa.buildSetup(flatProg != null ? flatProg : prog);
      forwardDfaSetup = setup;
    }
    return setup;
  }

  Dfa.Setup reverseDfaSetup() {
    flatReverseDfaProg(); // ensure flat reverse dfa prog and its setup are computed
    return reverseDfaSetup;
  }

  /**
   * Returns the full literal string for patterns that are entirely literal (no metacharacters), or
   * {@code null} if the pattern is not fully literal. For case-insensitive patterns, returns the
   * lowercase version.
   */
  String literalMatch() {
    return matchDescriptor.literalMatch();
  }

  boolean literalFoldCase() {
    return matchDescriptor.literalFoldCase();
  }

  byte[] literalMatchUtf8() {
    return literalMatchUtf8;
  }

  int[] literalMatchFailure() {
    return literalMatchFailure;
  }

  int[] literalMatchShifts() {
    return literalMatchShifts;
  }

  byte[] prefixUtf8() {
    return prefixUtf8;
  }

  /** Returns {@code true} if this pattern is a simple literal with no metacharacters. */
  boolean isLiteral() {
    return literalMatch() != null;
  }

  boolean startsWithGraphemeClusterBoundary() {
    return startsWithGraphemeClusterBoundary;
  }

  boolean hasInternalGraphemeClusterBoundary() {
    return hasInternalGraphemeClusterBoundary;
  }

  /** Returns the parsed AST. */
  Regexp ast() {
    return ast;
  }

  /**
   * Returns an unmodifiable map of named capturing groups to their 1-based group indices.
   *
   * <p>If the pattern has no named capturing groups, an empty map is returned.
   *
   * @return an unmodifiable map from group names to group numbers
   * @since 20
   */
  public Map<String, Integer> namedGroups() {
    return namedGroups;
  }

  /**
   * Returns immutable static diagnostics for this pattern.
   *
   * <p>Capabilities describe strategies that can participate for at least one supported operation
   * and input; runtime input length, operation shape, and engine budgets can still affect
   * selection.
   */
  public PatternAnalysis analysis() {
    PatternAnalysis result = patternAnalysis;
    if (result == null) {
      synchronized (this) {
        result = patternAnalysis;
        if (result == null) {
          result = buildPatternAnalysis();
          patternAnalysis = result;
        }
      }
    }
    return result;
  }

  PatternDescriptor descriptor() {
    PatternDescriptor result = patternDescriptor;
    if (result == null) {
      synchronized (this) {
        result = patternDescriptor;
        if (result == null) {
          result = new PatternDescriptor(patternId, analysis());
          patternDescriptor = result;
        }
      }
    }
    return result;
  }

  private PatternAnalysis buildPatternAnalysis() {
    EnumSet<PatternFeature> features = EnumSet.noneOf(PatternFeature.class);
    EnumSet<PatternCapability> capabilities = EnumSet.noneOf(PatternCapability.class);
    EnumSet<PatternLimitation> limitations = EnumSet.noneOf(PatternLimitation.class);

    if (literalMatch() != null) {
      features.add(PatternFeature.LITERAL);
      capabilities.add(PatternCapability.LITERAL_MATCH);
    }
    if (prog.numCaptures() > 1) {
      features.add(PatternFeature.CAPTURES);
    }
    if (hasAlternation) {
      features.add(PatternFeature.ALTERNATION);
    }
    if (hasLazy) {
      features.add(PatternFeature.LAZY_QUANTIFIER);
      limitations.add(PatternLimitation.LAZY_SEMANTICS_LIMIT_ONE_PASS);
    }
    if (canMatchEmpty) {
      features.add(PatternFeature.NULLABLE);
    }
    if (hasNullableAlternation) {
      features.add(PatternFeature.NULLABLE_ALTERNATION);
      limitations.add(PatternLimitation.NULLABLE_ALTERNATION_LIMITS_ONE_PASS);
    }
    if (prog.anchorStart()) {
      features.add(PatternFeature.ANCHOR);
      features.add(PatternFeature.START_ANCHOR);
    }
    if (prog.anchorEnd()) {
      features.add(PatternFeature.ANCHOR);
      features.add(PatternFeature.END_ANCHOR);
    }
    if (prog.hasWordBoundary()) {
      features.add(PatternFeature.WORD_BOUNDARY);
    }
    if (prog.hasGraphemeSemantics()) {
      features.add(PatternFeature.GRAPHEME);
      limitations.add(PatternLimitation.GRAPHEME_REQUIRES_EXACT_ENGINE);
    }
    if ((flags & CASE_INSENSITIVE) != 0) {
      features.add(PatternFeature.CASE_INSENSITIVE);
    }
    if ((flags & UNICODE_CHARACTER_CLASS) != 0) {
      features.add(PatternFeature.UNICODE_CHARACTER_CLASS);
    }
    addAstAnalysisFeatures(features);

    if (matchDescriptor.charClassMatch() != null || matchDescriptor.singleCharClass() != null) {
      capabilities.add(PatternCapability.CHARACTER_CLASS_MATCH);
    }
    if (matchDescriptor.keywordAlternation() != null) {
      capabilities.add(PatternCapability.KEYWORD_MATCH);
    }
    if (canOnePassPrimary()) {
      capabilities.add(PatternCapability.ONE_PASS_PRIMARY);
    }
    if (canOnePassSubmatch()) {
      capabilities.add(PatternCapability.ONE_PASS_CAPTURE_EXTRACTION);
    }
    if (flatDfaProg != null && !prog.hasGraphemeSemantics()) {
      if (prog.numLoopRegs() == 0) {
        capabilities.add(PatternCapability.DFA_BOUNDARY_SEARCH);
      } else {
        capabilities.add(PatternCapability.DFA_REJECT_PREFILTER);
      }
    }
    int maxBitStateText = BitState.maxTextSize(prog);
    if (maxBitStateText >= 0) {
      capabilities.add(PatternCapability.BIT_STATE);
    } else {
      limitations.add(PatternLimitation.PROGRAM_TOO_LARGE_FOR_BIT_STATE);
    }
    capabilities.add(PatternCapability.NFA);

    if (prog.numLoopRegs() > 0) {
      features.add(PatternFeature.NESTED_NULLABLE_QUANTIFIER);
      features.add(PatternFeature.PROGRESS_CHECK);
      limitations.add(PatternLimitation.NULLABLE_LOOP_REQUIRES_EXACT_ENGINE);
    }
    if (features.contains(PatternFeature.CAPTURES)
        && (hasAlternation || hasLazy || prog.numLoopRegs() > 0)) {
      limitations.add(PatternLimitation.CAPTURE_PRIORITY_REQUIRES_EXACT_ENGINE);
    }

    return new PatternAnalysis(
        features, capabilities, limitations, prog.size(), prog.numCaptures() - 1);
  }

  private record AstAnalysisNode(Regexp regexp, boolean insideQuantifier) {}

  private void addAstAnalysisFeatures(EnumSet<PatternFeature> features) {
    Deque<AstAnalysisNode> stack = new ArrayDeque<>();
    stack.push(new AstAnalysisNode(ast, false));
    while (!stack.isEmpty()) {
      AstAnalysisNode current = stack.pop();
      Regexp node = current.regexp();
      boolean quantifier =
          node.op == RegexpOp.STAR
              || node.op == RegexpOp.PLUS
              || node.op == RegexpOp.QUEST
              || node.op == RegexpOp.REPEAT;
      if (node.op == RegexpOp.REPEAT) {
        features.add(PatternFeature.BOUNDED_REPEAT);
      }
      switch (node.op) {
        case LITERAL, LITERAL_STRING -> features.add(PatternFeature.LITERAL);
        case BEGIN_LINE, BEGIN_TEXT -> {
          features.add(PatternFeature.ANCHOR);
          features.add(PatternFeature.START_ANCHOR);
        }
        case END_LINE, END_TEXT -> {
          features.add(PatternFeature.ANCHOR);
          features.add(PatternFeature.END_ANCHOR);
        }
        case WORD_BOUNDARY, NO_WORD_BOUNDARY -> features.add(PatternFeature.WORD_BOUNDARY);
        case GRAPHEME_CLUSTER, GRAPHEME_CLUSTER_BOUNDARY -> features.add(PatternFeature.GRAPHEME);
        default -> {}
      }
      if (node.op == RegexpOp.CAPTURE && node.cap > 0 && current.insideQuantifier()) {
        features.add(PatternFeature.CAPTURES_IN_QUANTIFIER);
      }
      if (node.subs != null) {
        for (Regexp sub : node.subs) {
          stack.push(new AstAnalysisNode(sub, current.insideQuantifier() || quantifier));
        }
      }
    }
  }

  /**
   * Returns the number of capturing groups in this pattern, not counting the implicit group 0 for
   * the full match.
   */
  int numGroups() {
    return prog.numCaptures() - 1;
  }

  // ---------------------------------------------------------------------------
  // Flag mapping
  // ---------------------------------------------------------------------------

  /** The set of all flag bits we support. */
  private static final int SUPPORTED_FLAGS =
      UNIX_LINES
          | CASE_INSENSITIVE
          | COMMENTS
          | MULTILINE
          | LITERAL
          | DOTALL
          | UNICODE_CASE
          | UNICODE_CHARACTER_CLASS;

  /** Validates that no unsupported flag bits are set. */
  private static void validateFlags(int flags) {
    int unsupported = flags & ~SUPPORTED_FLAGS;
    if (unsupported != 0) {
      throw new IllegalArgumentException(
          "Unsupported flags: 0x"
              + Integer.toHexString(unsupported)
              + ". CANON_EQ is not supported by SafeRE.");
    }
  }

  /** Returns JDK-compatible effective flags after applying implied flags. */
  private static int effectiveFlags(int flags) {
    if ((flags & UNICODE_CHARACTER_CLASS) != 0) {
      flags |= UNICODE_CASE;
    }
    return flags;
  }

  /**
   * Converts {@code java.util.regex.Pattern} flags to internal {@link ParseFlags}.
   *
   * <p>The baseline is {@link ParseFlags#LIKE_PERL}, which includes {@code ONE_LINE} (single-line
   * mode where {@code ^} and {@code $} match only at the start/end of the entire input).
   */
  private static int toParseFlags(int flags) {
    // Start with LIKE_PERL as the baseline.
    int pf = ParseFlags.LIKE_PERL;

    if ((flags & CASE_INSENSITIVE) != 0) {
      pf |= ParseFlags.FOLD_CASE;
    }
    if ((flags & MULTILINE) != 0) {
      // Multiline mode: ^ and $ match at line boundaries.
      // Remove ONE_LINE so that ^ and $ are per-line.
      pf &= ~ParseFlags.ONE_LINE;
    }
    if ((flags & DOTALL) != 0) {
      pf |= ParseFlags.DOT_NL;
    }
    if ((flags & LITERAL) != 0) {
      pf |= ParseFlags.LITERAL;
    }
    if ((flags & COMMENTS) != 0) {
      pf |= ParseFlags.COMMENTS;
    }
    if ((flags & UNICODE_CASE) != 0) {
      pf |= ParseFlags.UNICODE_CASE;
    }
    if ((flags & UNICODE_CHARACTER_CLASS) != 0) {
      pf |= ParseFlags.UNICODE_GROUPS | ParseFlags.UNICODE_CHAR_CLASS;
    }
    if ((flags & UNIX_LINES) != 0) {
      pf |= ParseFlags.UNIX_LINES;
    }
    return pf;
  }

  // ---------------------------------------------------------------------------
  // Named group extraction
  // ---------------------------------------------------------------------------

  /** Walks the AST to extract named capture groups and their 1-based indices. */
  private static Map<String, Integer> extractNamedGroups(Regexp re) {
    Map<String, Integer> map = new HashMap<>();
    Deque<Regexp> stack = new ArrayDeque<>();
    stack.push(re);
    while (!stack.isEmpty()) {
      Regexp node = stack.pop();
      if (node.op == RegexpOp.CAPTURE && node.name != null) {
        map.put(node.name, node.cap);
      }
      if (node.subs != null) {
        for (Regexp sub : node.subs) {
          stack.push(sub);
        }
      }
    }
    return Collections.unmodifiableMap(map);
  }

  /**
   * Returns {@code true} if the AST contains any lazy (non-greedy) quantifiers ({@code +?}, {@code
   * *?}, {@code ??}, or {@code {n,m}?}). OnePass does not respect lazy vs greedy semantics for
   * overall match boundaries, so patterns with lazy quantifiers must use the DFA pipeline in {@code
   * find()}.
   */
  private static boolean hasLazyQuantifiers(Regexp re) {
    Deque<Regexp> stack = new ArrayDeque<>();
    stack.push(re);
    while (!stack.isEmpty()) {
      Regexp node = stack.pop();
      if (node.nonGreedy()) {
        return true;
      }
      if (node.subs != null) {
        for (Regexp sub : node.subs) {
          stack.push(sub);
        }
      }
    }
    return false;
  }

  /**
   * Returns {@code true} if the AST contains any explicit alternation ({@link RegexpOp#ALTERNATE}).
   * Patterns with alternation may have branches of different match lengths, causing the DFA's
   * leftmost-longest match to disagree with RE2's leftmost-first alternation priority. When this
   * flag is set, the submatch engine must resolve the correct group(0) boundaries.
   */
  private static boolean hasAlternation(Regexp re) {
    Deque<Regexp> stack = new ArrayDeque<>();
    stack.push(re);
    while (!stack.isEmpty()) {
      Regexp node = stack.pop();
      if (node.op == RegexpOp.ALTERNATE) {
        return true;
      }
      if (node.subs != null) {
        for (Regexp sub : node.subs) {
          stack.push(sub);
        }
      }
    }
    return false;
  }

  /**
   * Returns {@code true} if the AST contains an alternation where at least one branch can match
   * zero characters (is "nullable"). This detects the case where OnePass's longest-match semantics
   * differ from first-match: a zero-width branch (assertion, empty match, nullable repetition)
   * competing with a consuming branch. For alternations where all branches must consume at least
   * one character (e.g., {@code GET|POST}), OnePass gives correct results.
   */
  private static boolean hasNullableAlternation(Regexp re) {
    Deque<Regexp> stack = new ArrayDeque<>();
    stack.push(re);
    while (!stack.isEmpty()) {
      Regexp node = stack.pop();
      if (node.op == RegexpOp.ALTERNATE && node.subs != null) {
        for (Regexp branch : node.subs) {
          if (canMatchEmpty(branch)) {
            return true;
          }
        }
      }
      if (node.subs != null) {
        for (Regexp sub : node.subs) {
          stack.push(sub);
        }
      }
    }
    return false;
  }

  /**
   * Returns {@code true} if the given regexp can match the empty string. Used to detect nullable
   * alternation branches where OnePass's longest-match semantics may differ from first-match.
   */
  static boolean canMatchEmpty(Regexp re) {
    return new CanMatchEmptyWalker().walk(re, false);
  }

  private static final class CanMatchEmptyWalker extends Walker<Boolean> {

    @Override
    protected Boolean shortVisit(Regexp re, Boolean parentArg) {
      return false;
    }

    @Override
    protected Boolean postVisit(
        Regexp re, Boolean parentArg, Boolean preArg, List<Boolean> childArgs) {
      return switch (re.op) {
        case EMPTY_MATCH,
            BEGIN_LINE,
            END_LINE,
            BEGIN_TEXT,
            END_TEXT,
            WORD_BOUNDARY,
            NO_WORD_BOUNDARY,
            GRAPHEME_CLUSTER_BOUNDARY ->
            true;
        case STAR, QUEST -> true;
        case REPEAT -> re.min == 0 || (!childArgs.isEmpty() && childArgs.getFirst());
        case PLUS, NON_CAPTURE, CAPTURE -> !childArgs.isEmpty() && childArgs.getFirst();
        case CONCAT -> {
          for (boolean childCanMatchEmpty : childArgs) {
            if (!childCanMatchEmpty) {
              yield false;
            }
          }
          yield true;
        }
        case ALTERNATE -> {
          for (boolean childCanMatchEmpty : childArgs) {
            if (childCanMatchEmpty) {
              yield true;
            }
          }
          yield childArgs.isEmpty();
        }
        default -> false;
      };
    }
  }

  private static boolean startsWithGraphemeClusterBoundary(Regexp re) {
    Regexp first = firstMeaningfulNode(re);
    return first != null && first.op == RegexpOp.GRAPHEME_CLUSTER_BOUNDARY;
  }

  private static boolean hasInternalExplicitGraphemeBoundary(Regexp re) {
    return new GraphemeBoundaryContextWalker()
        .walk(re, GraphemeBoundaryContext.noMatch())
        .hasInternalExplicitBoundary();
  }

  private record GraphemeBoundaryContext(
      boolean canMatchEmpty,
      boolean canConsume,
      boolean startsWithExplicitBoundary,
      boolean endsWithExplicitBoundary,
      boolean hasInternalExplicitBoundary) {
    static GraphemeBoundaryContext noMatch() {
      return new GraphemeBoundaryContext(false, false, false, false, false);
    }

    static GraphemeBoundaryContext empty() {
      return new GraphemeBoundaryContext(true, false, false, false, false);
    }

    static GraphemeBoundaryContext consuming() {
      return new GraphemeBoundaryContext(false, true, false, false, false);
    }

    static GraphemeBoundaryContext explicitBoundary() {
      return new GraphemeBoundaryContext(true, false, true, true, false);
    }
  }

  private static final class GraphemeBoundaryContextWalker extends Walker<GraphemeBoundaryContext> {

    @Override
    protected GraphemeBoundaryContext shortVisit(Regexp re, GraphemeBoundaryContext parentArg) {
      return GraphemeBoundaryContext.noMatch();
    }

    @Override
    protected GraphemeBoundaryContext copy(GraphemeBoundaryContext arg) {
      return arg;
    }

    @Override
    protected GraphemeBoundaryContext postVisit(
        Regexp re,
        GraphemeBoundaryContext parentArg,
        GraphemeBoundaryContext preArg,
        List<GraphemeBoundaryContext> childArgs) {
      return switch (re.op) {
        case NO_MATCH -> GraphemeBoundaryContext.noMatch();
        case EMPTY_MATCH,
            BEGIN_LINE,
            END_LINE,
            BEGIN_TEXT,
            END_TEXT,
            WORD_BOUNDARY,
            NO_WORD_BOUNDARY,
            HAVE_MATCH ->
            GraphemeBoundaryContext.empty();
        case LITERAL, LITERAL_STRING, ANY_CHAR, ANY_BYTE, CHAR_CLASS, GRAPHEME_CLUSTER ->
            GraphemeBoundaryContext.consuming();
        case GRAPHEME_CLUSTER_BOUNDARY -> GraphemeBoundaryContext.explicitBoundary();
        case CONCAT -> concatBoundaryContext(childArgs);
        case ALTERNATE -> alternateBoundaryContext(childArgs);
        case STAR, QUEST -> optionalBoundaryContext(childArgs);
        case PLUS, NON_CAPTURE, CAPTURE -> childBoundaryContext(childArgs);
        case REPEAT -> repeatBoundaryContext(re, childArgs);
      };
    }

    private static GraphemeBoundaryContext concatBoundaryContext(
        List<GraphemeBoundaryContext> childArgs) {
      boolean canMatchEmpty = true;
      boolean canConsume = false;
      boolean startsWithExplicitBoundary = false;
      boolean canStillStartAtChild = true;
      boolean hasInternalExplicitBoundary = false;
      boolean hasConsumingPrefix = false;
      boolean[] consumingSuffix = new boolean[childArgs.size()];
      boolean hasConsumingSuffix = false;
      for (int i = childArgs.size() - 1; i >= 0; i--) {
        consumingSuffix[i] = hasConsumingSuffix;
        hasConsumingSuffix |= childArgs.get(i).canConsume();
      }
      for (int i = 0; i < childArgs.size(); i++) {
        GraphemeBoundaryContext child = childArgs.get(i);
        canMatchEmpty &= child.canMatchEmpty();
        canConsume |= child.canConsume();
        if (canStillStartAtChild && child.startsWithExplicitBoundary()) {
          startsWithExplicitBoundary = true;
        }
        canStillStartAtChild &= child.canMatchEmpty();
        hasInternalExplicitBoundary |= child.hasInternalExplicitBoundary();
        if ((child.startsWithExplicitBoundary() || child.endsWithExplicitBoundary())
            && hasConsumingPrefix
            && consumingSuffix[i]) {
          hasInternalExplicitBoundary = true;
        }
        hasConsumingPrefix |= child.canConsume();
      }

      boolean endsWithExplicitBoundary = false;
      boolean canStillEndAtChild = true;
      for (int i = childArgs.size() - 1; i >= 0; i--) {
        GraphemeBoundaryContext child = childArgs.get(i);
        if (canStillEndAtChild && child.endsWithExplicitBoundary()) {
          endsWithExplicitBoundary = true;
        }
        canStillEndAtChild &= child.canMatchEmpty();
      }
      return new GraphemeBoundaryContext(
          canMatchEmpty,
          canConsume,
          startsWithExplicitBoundary,
          endsWithExplicitBoundary,
          hasInternalExplicitBoundary);
    }

    private static GraphemeBoundaryContext alternateBoundaryContext(
        List<GraphemeBoundaryContext> childArgs) {
      boolean canMatchEmpty = childArgs.isEmpty();
      boolean canConsume = false;
      boolean startsWithExplicitBoundary = false;
      boolean endsWithExplicitBoundary = false;
      boolean hasInternalExplicitBoundary = false;
      for (GraphemeBoundaryContext child : childArgs) {
        canMatchEmpty |= child.canMatchEmpty();
        canConsume |= child.canConsume();
        startsWithExplicitBoundary |= child.startsWithExplicitBoundary();
        endsWithExplicitBoundary |= child.endsWithExplicitBoundary();
        hasInternalExplicitBoundary |= child.hasInternalExplicitBoundary();
      }
      return new GraphemeBoundaryContext(
          canMatchEmpty,
          canConsume,
          startsWithExplicitBoundary,
          endsWithExplicitBoundary,
          hasInternalExplicitBoundary);
    }

    private static GraphemeBoundaryContext optionalBoundaryContext(
        List<GraphemeBoundaryContext> childArgs) {
      GraphemeBoundaryContext child = childBoundaryContext(childArgs);
      return new GraphemeBoundaryContext(
          true,
          child.canConsume(),
          child.startsWithExplicitBoundary(),
          child.endsWithExplicitBoundary(),
          child.hasInternalExplicitBoundary());
    }

    private static GraphemeBoundaryContext repeatBoundaryContext(
        Regexp re, List<GraphemeBoundaryContext> childArgs) {
      GraphemeBoundaryContext child = childBoundaryContext(childArgs);
      boolean canMatchEmpty = re.min == 0 || child.canMatchEmpty();
      boolean canConsume = re.max != 0 && child.canConsume();
      return new GraphemeBoundaryContext(
          canMatchEmpty,
          canConsume,
          child.startsWithExplicitBoundary(),
          child.endsWithExplicitBoundary(),
          child.hasInternalExplicitBoundary());
    }

    private static GraphemeBoundaryContext childBoundaryContext(
        List<GraphemeBoundaryContext> childArgs) {
      return childArgs.isEmpty() ? GraphemeBoundaryContext.noMatch() : childArgs.getFirst();
    }
  }

  /** Result of prefix extraction: a literal string prefix and whether it is case-folded. */
  private record PrefixResult(String prefix, boolean foldCase) {}

  /**
   * Conservative start-position accelerator.
   *
   * <p>Every match must start at a multiline {@code ^} position, optionally with the first consumed
   * ASCII character in {@code asciiStart}. The accelerator only advances the initial search
   * position before handing off to the normal linear engine pipeline.
   */
  static final class StartAcceleration {
    final boolean requireLineStart;
    final boolean allowLineStart;
    final AsciiBitmap asciiStart;

    StartAcceleration(boolean requireLineStart, boolean allowLineStart, AsciiBitmap asciiStart) {
      this.requireLineStart = requireLineStart;
      this.allowLineStart = allowLineStart;
      this.asciiStart = asciiStart;
    }
  }

  static final class FixedOffsetLiteral {
    private final String literal;
    private final int minOffset;
    private final int maxOffset;
    private final int[] discreteOffsets;
    private final byte[] utf8;
    private final int[] failure;
    private final int[] shifts;

    FixedOffsetLiteral(String literal, int offset) {
      this(literal, offset, offset, new int[] {offset});
    }

    FixedOffsetLiteral(String literal, int minOffset, int maxOffset, int[] discreteOffsets) {
      this.literal = literal;
      this.minOffset = minOffset;
      this.maxOffset = maxOffset;
      this.discreteOffsets = discreteOffsets;
      this.utf8 = literal.getBytes(StandardCharsets.UTF_8);
      this.failure = literalFailure(utf8);
      this.shifts = literalShifts(utf8);
    }

    String literal() {
      return literal;
    }

    int offset() {
      return minOffset;
    }

    int minOffset() {
      return minOffset;
    }

    int maxOffset() {
      return maxOffset;
    }

    int[] discreteOffsets() {
      return discreteOffsets;
    }

    boolean isExactOffset() {
      return minOffset == maxOffset;
    }

    byte[] utf8() {
      return utf8;
    }

    int[] failure() {
      return failure;
    }

    int[] shifts() {
      return shifts;
    }
  }

  /** Fast-path data for {@code (?i)\b(keyword|...)\b} and its greedy whole-input form. */
  static final class KeywordAlternation {
    final String[] keywords;
    final AsciiBitmap firstAscii;
    final boolean[] firstAsciiTable;
    final int captureGroup;
    final boolean unicodeWordBoundary;
    final boolean greedyWholeInput;

    KeywordAlternation(
        String[] keywords,
        AsciiBitmap firstAscii,
        int captureGroup,
        boolean unicodeWordBoundary,
        boolean greedyWholeInput) {
      this.keywords = keywords;
      this.firstAscii = firstAscii;
      this.firstAsciiTable = firstAscii.toBooleanArray();
      this.captureGroup = captureGroup;
      this.unicodeWordBoundary = unicodeWordBoundary;
      this.greedyWholeInput = greedyWholeInput;
    }

    long find(InputScanner scanner, int startPos) {
      int matchStart = Math.max(0, startPos);
      if (greedyWholeInput) {
        for (int position = scanner.length() - 1; position >= matchStart; position--) {
          long match = matchAt(scanner, position);
          if (match >= 0) {
            return match;
          }
        }
        return -1;
      }
      int position = matchStart;
      while (position < scanner.length()) {
        long match = matchAt(scanner, position);
        if (match >= 0) {
          return match;
        }
        int ascii = scanner.asciiAt(position);
        position =
            ascii >= 0 ? position + 1 : InputScanner.position(scanner.decodeForward(position));
      }
      return -1;
    }

    long matchAt(InputScanner scanner, int position) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      int first = scanner.asciiAt(position);
      if (first < 0
          || !firstAsciiTable[Ascii.toLowerCase(first)]
          || !isWordBoundaryAt(scanner, position)) {
        return -1;
      }
      for (String keyword : keywords) {
        int end = position + keyword.length();
        if (end <= scanner.length()
            && matchesAsciiIgnoreCase(scanner, position, keyword)
            && isWordBoundaryAt(scanner, end)) {
          return ((long) position << 32) | (end & 0xFFFF_FFFFL);
        }
      }
      return -1;
    }

    private boolean isWordBoundaryAt(InputScanner scanner, int position) {
      boolean previousWord =
          position > 0
              && isBoundaryWordChar(scanner.codePointBefore(position), unicodeWordBoundary);
      boolean nextWord =
          position < scanner.length()
              && isBoundaryWordChar(scanner.codePointAt(position), unicodeWordBoundary);
      return previousWord != nextWord;
    }

    private static boolean matchesAsciiIgnoreCase(
        InputScanner scanner, int position, String keyword) {
      for (int index = 0; index < keyword.length(); index++) {
        int input = scanner.asciiAt(position + index);
        if (input < 0 || Ascii.toLowerCase(input) != keyword.charAt(index)) {
          return false;
        }
      }
      return true;
    }

    private static boolean isBoundaryWordChar(int codePoint, boolean unicodeWordBoundary) {
      return unicodeWordBoundary ? Nfa.isUnicodeWordChar(codePoint) : Nfa.isWordChar(codePoint);
    }

    static int matchStart(long match) {
      return (int) (match >>> 32);
    }

    static int matchEnd(long match) {
      return (int) match;
    }
  }

  /** Finds the longest case-sensitive ASCII literal after a bounded-width match prefix. */
  private static FixedOffsetLiteral extractFixedOffsetLiteral(Regexp re) {
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

  private static String extractExactAsciiLiteral(Regexp re) {
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

  /**
   * Extracts a literal prefix from the simplified AST for prefix acceleration. Returns a {@link
   * PrefixResult} containing the literal string that every match must start with (or {@code null}
   * if no fixed prefix exists) and whether the prefix is case-folded.
   *
   * <p>This looks for patterns that begin with literal characters (possibly inside a CONCAT or
   * CAPTURE). The prefix is used by {@link Matcher#doFind()} to skip ahead using {@link
   * String#indexOf} before running the full engine.
   */
  private static PrefixResult extractPrefix(Regexp re) {
    return extractPrefixFromCandidate(firstPrefixCandidate(re));
  }

  private static PrefixResult extractPrefixFromCandidate(Regexp node) {
    if (node == null) {
      return new PrefixResult(null, false);
    }

    // Check for literal or literal string.
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

  private static Regexp firstPrefixCandidate(Regexp re) {
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

  private static Regexp firstPrefixCandidateAfterTextAnchor(Regexp re) {
    Deque<Regexp> stack = new ArrayDeque<>();
    stack.push(re);
    boolean sawTextAnchor = false;
    while (!stack.isEmpty()) {
      Regexp node = unwrapCaptures(stack.pop());
      if (node == null) {
        continue;
      }
      if (node.op == RegexpOp.CONCAT) {
        for (int i = node.subs.size() - 1; i >= 0; i--) {
          stack.push(node.subs.get(i));
        }
        continue;
      }
      if (!sawTextAnchor) {
        if (isLeadingZeroWidth(node)) {
          continue;
        }
        if (node.op != RegexpOp.BEGIN_TEXT) {
          return null;
        }
        sawTextAnchor = true;
        continue;
      }
      if (!isLeadingZeroWidth(node)) {
        return node;
      }
    }
    return null;
  }

  private static boolean isLeadingZeroWidth(Regexp re) {
    return switch (re.op) {
      case EMPTY_MATCH, WORD_BOUNDARY, NO_WORD_BOUNDARY -> true;
      default -> false;
    };
  }

  /**
   * Extracts a character-class prefix bitmap for ASCII acceleration. Walks the AST (through CAPTURE
   * and CONCAT wrappers) to find a required character class at the start of the pattern. If found
   * and the class contains only ASCII code points, returns a {@code boolean[128]} bitmap where
   * Extracts a character-class prefix from the AST, supporting both ASCII and Unicode character
   * classes.
   *
   * @return a {@link CharClassScanInfo}, or {@code null} if no suitable prefix exists
   */
  private static CharClassScanInfo extractCharClassPrefix(Regexp re) {
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
          node = node.subs.getFirst();
          continue;
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
    // Selectivity check: If the character class matches more than 50% of the Unicode space,
    // scanning for it will cause high false-positive rates. Skip prefix acceleration.
    if (cc.numRunes() > 0x80000) {
      return null;
    }
    return CharClassScanInfo.fromCharClass(cc);
  }

  private static boolean addAsciiCharClass(CharClass cc, AsciiBitmap.Builder bitmap) {
    if (cc == null || cc.isEmpty()) {
      return false;
    }
    for (int i = 0; i < cc.numRanges(); i++) {
      if (cc.hi(i) >= 128) {
        return false;
      }
    }
    for (int i = 0; i < cc.numRanges(); i++) {
      bitmap.addRange(cc.lo(i), cc.hi(i));
    }
    return true;
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

  private static StartAcceleration extractStartAcceleration(Regexp re) {
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
        return new StartAcceleration(true, false, requiredStart);
      }
      return null;
    }

    if (node.op == RegexpOp.BEGIN_LINE) {
      return new StartAcceleration(true, false, null);
    }
    return null;
  }

  private static KeywordAlternation extractKeywordAlternation(Regexp re, int patternFlags) {
    if ((patternFlags & UNICODE_CASE) != 0) {
      return null;
    }

    Regexp node = unwrapImplicitCapture(re);
    if (node == null || node.op != RegexpOp.CONCAT) {
      return null;
    }
    boolean greedyWholeInput = false;
    int coreOffset = 0;
    if (node.nsub() == 5
        && isGreedyAnyCharStar(node.subs.getFirst())
        && isGreedyAnyCharStar(node.subs.getLast())) {
      greedyWholeInput = true;
      coreOffset = 1;
    } else if (node.nsub() != 3) {
      return null;
    }
    Regexp before = unwrapImplicitCapture(node.subs.get(coreOffset));
    Regexp middle = unwrapImplicitCapture(node.subs.get(coreOffset + 1));
    Regexp after = unwrapImplicitCapture(node.subs.get(coreOffset + 2));
    if (before == null
        || before.op != RegexpOp.WORD_BOUNDARY
        || after == null
        || after.op != RegexpOp.WORD_BOUNDARY) {
      return null;
    }

    int captureGroup = -1;
    if (middle != null && middle.op == RegexpOp.CAPTURE && middle.cap > 0) {
      captureGroup = middle.cap;
      middle = unwrapImplicitCapture(middle.sub());
    }
    if (middle == null
        || middle.op != RegexpOp.ALTERNATE
        || middle.subs == null
        || middle.subs.isEmpty()) {
      return null;
    }
    if (hasOtherUserCaptures(middle, captureGroup)) {
      return null;
    }

    String[] keywords = new String[middle.subs.size()];
    AsciiBitmap.Builder firstAscii = new AsciiBitmap.Builder();
    for (int i = 0; i < middle.subs.size(); i++) {
      String keyword = extractAsciiCaseInsensitiveLiteral(middle.subs.get(i));
      if (keyword == null || keyword.isEmpty()) {
        return null;
      }
      keywords[i] = keyword;
      firstAscii.add(keyword.charAt(0));
    }

    boolean beforeUnicodeWordBoundary = (before.flags & ParseFlags.UNICODE_CHAR_CLASS) != 0;
    boolean afterUnicodeWordBoundary = (after.flags & ParseFlags.UNICODE_CHAR_CLASS) != 0;
    if (beforeUnicodeWordBoundary != afterUnicodeWordBoundary) {
      return null;
    }
    return new KeywordAlternation(
        keywords, firstAscii.build(), captureGroup, beforeUnicodeWordBoundary, greedyWholeInput);
  }

  private static boolean isGreedyAnyCharStar(Regexp re) {
    Regexp node = unwrapImplicitCapture(re);
    return node != null
        && node.op == RegexpOp.STAR
        && !node.nonGreedy()
        && node.sub().op == RegexpOp.ANY_CHAR;
  }

  private static boolean hasOtherUserCaptures(Regexp re, int allowedCapture) {
    Deque<Regexp> stack = new ArrayDeque<>();
    stack.push(re);
    while (!stack.isEmpty()) {
      Regexp node = stack.pop();
      if (node.op == RegexpOp.CAPTURE && node.cap > 0 && node.cap != allowedCapture) {
        return true;
      }
      if (node.subs != null) {
        for (Regexp sub : node.subs) {
          stack.push(sub);
        }
      }
    }
    return false;
  }

  private static Regexp unwrapImplicitCapture(Regexp re) {
    Regexp node = re;
    while (node != null && node.op == RegexpOp.CAPTURE && node.cap == 0) {
      node = node.sub();
    }
    return node;
  }

  private static String extractAsciiCaseInsensitiveLiteral(Regexp re) {
    Regexp node = unwrapImplicitCapture(re);
    if (node == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    if (!appendAsciiCaseInsensitiveLiteral(node, sb)) {
      return null;
    }
    return sb.toString();
  }

  private static boolean appendAsciiCaseInsensitiveLiteral(Regexp node, StringBuilder sb) {
    node = unwrapImplicitCapture(node);
    if (node == null) {
      return false;
    }
    return switch (node.op) {
      case CONCAT -> {
        if (node.subs == null || node.subs.isEmpty()) {
          yield false;
        }
        for (Regexp sub : node.subs) {
          if (!appendAsciiCaseInsensitiveLiteral(sub, sb)) {
            yield false;
          }
        }
        yield true;
      }
      case LITERAL -> {
        int cp = node.rune;
        if (cp < 0 || cp >= 128 || !isAsciiLiteralKeywordChar(cp)) {
          yield false;
        }
        if ((node.flags & ParseFlags.FOLD_CASE) == 0) {
          yield false;
        }
        sb.append((char) Ascii.toLowerCase(cp));
        yield true;
      }
      case LITERAL_STRING -> {
        if (node.runes == null || node.runes.length == 0) {
          yield false;
        }
        if ((node.flags & ParseFlags.FOLD_CASE) == 0) {
          yield false;
        }
        for (int cp : node.runes) {
          if (cp < 0 || cp >= 128 || !isAsciiLiteralKeywordChar(cp)) {
            yield false;
          }
          sb.append((char) Ascii.toLowerCase(cp));
        }
        yield true;
      }
      case CHAR_CLASS -> {
        int cp = asciiFoldedLiteralChar(node.charClass);
        if (cp < 0) {
          yield false;
        }
        sb.append((char) cp);
        yield true;
      }
      default -> false;
    };
  }

  private static boolean isAsciiLiteralKeywordChar(int cp) {
    return Ascii.isWordChar(cp);
  }

  private static int asciiFoldedLiteralChar(CharClass cc) {
    if (cc == null || cc.numRunes() != 2) {
      return -1;
    }
    for (int cp = 'a'; cp <= 'z'; cp++) {
      if (cc.contains(cp) && cc.contains(Ascii.toUpperCase(cp))) {
        return cp;
      }
    }
    return -1;
  }

  private static Regexp firstMeaningfulNode(Regexp re) {
    Regexp node = unwrapCaptures(re);
    if (node == null) {
      return null;
    }
    if (node.op == RegexpOp.CONCAT && node.nsub() > 0) {
      return unwrapCaptures(node.subs.get(0));
    }
    return node;
  }

  private static Regexp unwrapCaptures(Regexp re) {
    Regexp node = re;
    while (node != null && (node.op == RegexpOp.CAPTURE || node.op == RegexpOp.NON_CAPTURE)) {
      node = node.sub();
    }
    return node;
  }

  private static AsciiBitmap requiredFirstAscii(Regexp re) {
    Regexp node = firstMeaningfulNode(re);
    if (node == null) {
      return null;
    }
    if (node.op == RegexpOp.PLUS || (node.op == RegexpOp.REPEAT && node.min >= 1)) {
      node = firstMeaningfulNode(node.sub());
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

  /** Holds precomputed data for the character-class-match fast path. */
  // TODO(#98): Replace int[] with Guava ImmutableIntArray to get proper value semantics.
  @SuppressWarnings("ArrayRecordComponent")
  record CharClassMatchInfo(int[] ranges, long bitmap0, long bitmap1, boolean allowEmpty) {}

  /**
   * Detects patterns that are structurally a single character class under a quantifier covering the
   * entire string — e.g., {@code [a-zA-Z]+}, {@code \d*}, {@code \w{1,}}, {@code [0-9]+}. When
   * detected, {@code matches()} can use a tight character-scanning loop with precomputed bitmaps
   * instead of the full engine cascade.
   *
   * <p>Sees through the implicit group-0 CAPTURE wrapper. Returns {@code null} if the pattern has
   * any user capture groups (the fast path only produces group 0).
   */
  private static CharClassMatchInfo extractCharClassMatch(Regexp re) {
    Regexp node = re;

    // Unwrap implicit group-0 capture.
    if (node.op == RegexpOp.CAPTURE && node.cap == 0) {
      node = node.sub();
    }

    // Must be a quantifier: PLUS (min=1), STAR (min=0), or REPEAT (min >= 0, max = -1).
    boolean allowEmpty;
    switch (node.op) {
      case PLUS -> allowEmpty = false;
      case STAR -> allowEmpty = true;
      case REPEAT -> {
        if (node.max != -1) {
          return null; // bounded repeat like {3,5} — not a "cover entire string" pattern
        }
        if (node.min > 1) {
          return null; // {2,} or higher — would need code point counting; not worth optimizing
        }
        allowEmpty = (node.min == 0);
      }
      default -> {
        return null;
      }
    }

    Regexp inner = node.sub();

    // Reject if the original pattern has user capture groups — the fast path only produces
    // group 0, so it can't provide group(1) etc.
    if (hasUserCaptures(re)) {
      return null;
    }

    CharClass cc;
    if (inner.op == RegexpOp.CHAR_CLASS && inner.charClass != null) {
      cc = inner.charClass;
    } else if (inner.op == RegexpOp.LITERAL) {
      cc = literalCharClass(inner.rune, inner.flags);
    } else {
      return null;
    }
    if (cc.isEmpty()) {
      return null;
    }

    // Build flat ranges array and precompute ASCII bitmaps.
    int numRanges = cc.numRanges();
    int[] ranges = new int[numRanges * 2];
    long b0 = 0;
    long b1 = 0;
    for (int i = 0; i < numRanges; i++) {
      int lo = cc.lo(i);
      int hi = cc.hi(i);
      ranges[i * 2] = lo;
      ranges[i * 2 + 1] = hi;
      int start = Math.max(lo, 0);
      int end = Math.min(hi, 127);
      for (int cp = start; cp <= end; cp++) {
        if (cp < 64) {
          b0 |= 1L << cp;
        } else {
          b1 |= 1L << (cp - 64);
        }
      }
    }
    return new CharClassMatchInfo(ranges, b0, b1, allowEmpty);
  }

  /**
   * Detects patterns that are exactly one character class, such as {@code [a-z]} or {@code
   * \p{javaLetter}}. The fast path only produces group 0, so patterns with user capture groups are
   * excluded.
   */
  private static CharClassScanInfo extractSingleCharClass(Regexp re) {
    Regexp node = re;
    if (node.op == RegexpOp.CAPTURE && node.cap == 0) {
      node = node.sub();
    }
    if (node.op != RegexpOp.CHAR_CLASS || node.charClass == null || hasUserCaptures(re)) {
      return null;
    }
    CharClass cc = node.charClass;
    if (cc.isEmpty()) {
      return null;
    }
    return CharClassScanInfo.fromCharClass(cc);
  }

  record SuffixInfo(String suffix, boolean wasDollar, boolean unixLines, boolean foldCase) {
    SuffixInfo(String suffix, boolean wasDollar) {
      this(suffix, wasDollar, false, false);
    }

    SuffixInfo(String suffix, boolean wasDollar, boolean unixLines) {
      this(suffix, wasDollar, unixLines, false);
    }
  }

  record EndAnchoredCharClassInfo(AsciiBitmap bitmap, boolean wasDollar, boolean unixLines) {
    EndAnchoredCharClassInfo(AsciiBitmap bitmap, boolean wasDollar) {
      this(bitmap, wasDollar, false);
    }
  }

  /** Extracts whole-input rejection metadata from the AST. */
  private static RejectDescriptor extractRejectDescriptor(
      Regexp metadataAst, int flags, StartDescriptor startDescriptor, boolean anchorStart) {
    SuffixInfo endAnchoredSuffix = extractEndAnchoredSuffix(metadataAst, flags);
    EndAnchoredCharClassInfo endAnchoredCharClass =
        endAnchoredSuffix == null ? extractEndAnchoredCharClass(metadataAst, flags) : null;
    String prefix = startDescriptor != null ? startDescriptor.prefix() : null;
    CharClassScanInfo ccPrefix = startDescriptor != null ? startDescriptor.charClassPrefix() : null;
    String requiredLiteral = prefix == null ? extractRequiredLiteral(metadataAst) : null;
    CharClassScanInfo requiredMatchClass = null;
    if (prefix == null && endAnchoredCharClass == null) {
      if (ccPrefix == null) {
        requiredMatchClass = extractRequiredMatchClass(metadataAst, true);
      } else {
        CharClassScanInfo candidate = extractRequiredMatchClass(metadataAst, false);
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
    DisjointRequiredLiterals disjointRequiredLiterals =
        (!anchorStart && prefix == null && requiredLiteral == null)
            ? DisjointRequiredLiterals.create(extractDisjointRequiredLiterals(metadataAst))
            : null;
    if (requiredLiteral == null
        && requiredMatchClass == null
        && disjointRequiredLiterals == null
        && endAnchoredSuffix == null
        && endAnchoredCharClass == null) {
      return null;
    }
    return new RejectDescriptor(
        requiredLiteral,
        requiredMatchClass,
        disjointRequiredLiterals,
        endAnchoredSuffix,
        endAnchoredCharClass);
  }

  /**
   * Extracts a literal suffix from an end-anchored pattern (e.g. {@code .*\\.json$} or {@code
   * (?i).*\\.json$}).
   *
   * <p>Returns {@code null} if the pattern is not end-anchored, if the pattern is compiled with
   * {@link #MULTILINE} and ends with {@code $}, or if no literal precedes the anchor.
   */
  private static SuffixInfo extractEndAnchoredSuffix(Regexp metadataAst, int flags) {
    Regexp node = unwrapCaptures(metadataAst);
    if (node == null || node.op != RegexpOp.CONCAT || node.nsub() < 2) {
      return null;
    }
    List<Regexp> subs = node.subs;
    Regexp last = unwrapCaptures(subs.get(subs.size() - 1));
    if (last == null || last.op != RegexpOp.END_TEXT) {
      return null;
    }
    if ((flags & MULTILINE) != 0 && (last.flags & ParseFlags.WAS_DOLLAR) != 0) {
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
    return new SuffixInfo(suffix.toString(), wasDollar, (flags & UNIX_LINES) != 0, foldCase);
  }

  private static boolean isAllAscii(int[] runes) {
    for (int r : runes) {
      if (r > 0x7F) {
        return false;
      }
    }
    return true;
  }

  /**
   * Extracts an ASCII character class from an end-anchored pattern (e.g. {@code .*[0-9]$}).
   *
   * <p>Returns {@code null} if the pattern is not end-anchored, if the pattern is compiled with
   * {@link #MULTILINE} and ends with {@code $}, or if the preceding node is not an ASCII character
   * class.
   */
  private static EndAnchoredCharClassInfo extractEndAnchoredCharClass(
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
    if ((flags & MULTILINE) != 0 && (last.flags & ParseFlags.WAS_DOLLAR) != 0) {
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
      boolean unixLines = (flags & UNIX_LINES) != 0;
      return new EndAnchoredCharClassInfo(builder.build(), wasDollar, unixLines);
    }
    return null;
  }

  /**
   * Detects a mandatory character class, such as the whitespace in {@code .*\\s+.*}. The resulting
   * class is only used to reject inputs that contain no matching code point; positive results still
   * go through the normal engine to preserve full regex semantics.
   *
   * <p>Alternation is inspected only when no start-character accelerator was found. For a leading
   * alternation, its union is already represented more precisely by that accelerator; constructing
   * the same union again would add compile-time work without improving searches.
   */
  private static CharClassScanInfo extractRequiredMatchClass(
      Regexp re, boolean inspectAlternation) {
    Regexp node = unwrapRequiredNode(re);
    if (node == null) {
      return null;
    }
    if (node.op != RegexpOp.CONCAT || node.subs == null) {
      CharClass required = requiredCharClass(node, inspectAlternation);
      return required != null ? CharClassScanInfo.fromCharClass(required) : null;
    }
    CharClass mostSelective = null;
    for (Regexp sub : node.subs) {
      CharClass required = requiredCharClass(sub, inspectAlternation);
      if (required != null
          && (mostSelective == null || required.numRunes() < mostSelective.numRunes())) {
        mostSelective = required;
      }
    }
    return mostSelective != null ? CharClassScanInfo.fromCharClass(mostSelective) : null;
  }

  private static CharClass requiredCharClass(Regexp re, boolean inspectAlternation) {
    Regexp node = unwrapRequiredNode(re);
    if (node == null) {
      return null;
    }
    if (node.op == RegexpOp.LITERAL) {
      return literalCharClass(node.rune, node.flags);
    }
    if (node.op == RegexpOp.LITERAL_STRING && node.runes != null && node.runes.length > 0) {
      return literalCharClass(node.runes[0], node.flags);
    }
    if (node.op == RegexpOp.CHAR_CLASS && node.charClass != null) {
      return node.charClass.isEmpty() ? null : node.charClass;
    }
    if (inspectAlternation
        && node.op == RegexpOp.ALTERNATE
        && node.subs != null
        && !node.subs.isEmpty()) {
      CharClassBuilder union = new CharClassBuilder();
      for (Regexp branch : node.subs) {
        CharClass branchClass = requiredAtomicCharClass(branch);
        if (branchClass == null) {
          return null;
        }
        union.addCharClass(branchClass);
      }
      CharClass result = union.build();
      return result.isEmpty() ? null : result;
    }
    return null;
  }

  private static CharClass requiredAtomicCharClass(Regexp re) {
    Regexp node = unwrapRequiredNode(re);
    if (node == null) {
      return null;
    }
    if (node.op == RegexpOp.LITERAL) {
      return literalCharClass(node.rune, node.flags);
    }
    if (node.op == RegexpOp.LITERAL_STRING && node.runes != null && node.runes.length > 0) {
      return literalCharClass(node.runes[0], node.flags);
    }
    if (node.op == RegexpOp.CHAR_CLASS && node.charClass != null && !node.charClass.isEmpty()) {
      return node.charClass;
    }
    return null;
  }

  private static Regexp unwrapRequiredNode(Regexp re) {
    Regexp node = re;
    while (true) {
      if (node.op == RegexpOp.CAPTURE
          || node.op == RegexpOp.NON_CAPTURE
          || node.op == RegexpOp.PLUS) {
        node = node.sub();
        continue;
      }
      if (node.op == RegexpOp.REPEAT) {
        if (node.min == 0) {
          return null;
        }
        node = node.sub();
        continue;
      }
      return node;
    }
  }

  /**
   * Finds the longest case-sensitive literal substring that every match must contain.
   *
   * <p>The worklist descends only through operators whose children are mandatory: concatenation,
   * transparent groups, and repetitions with a positive minimum. It deliberately stops at
   * alternation and optional repetition, so the result can only reject inputs that cannot match.
   */
  private static String extractRequiredLiteral(Regexp re) {
    String longest = null;
    int longestScore = 0;
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
            int candidateScore = RarityOracle.literalSelectivityScore(candidate);
            if (longest == null || candidateScore > longestScore) {
              longest = candidate;
              longestScore = candidateScore;
            }
          }
        }
        default -> {}
      }
    }
    return longest;
  }

  /**
   * Finds a small set of disjoint required literal substrings for alternation patterns where every
   * branch requires at least one literal substring (e.g., {@code (apple.*|banana.*|orange.*)}).
   *
   * <p>Returns {@code null} if any branch has no required literal, or if the number of distinct
   * literals is outside [2, 4].
   */
  private static String[] extractDisjointRequiredLiterals(Regexp re) {
    Regexp node = re;
    while (node != null
        && (node.op == RegexpOp.CAPTURE
            || node.op == RegexpOp.NON_CAPTURE
            || node.op == RegexpOp.PLUS)) {
      node = node.sub();
    }
    if (node == null) {
      return null;
    }
    if (node.op == RegexpOp.CONCAT && node.subs != null) {
      if (!node.subs.isEmpty()) {
        Regexp first = unwrapCaptures(node.subs.get(0));
        if (first != null && (first.op == RegexpOp.BEGIN_TEXT || first.op == RegexpOp.BEGIN_LINE)) {
          return null;
        }
      }
      for (Regexp sub : node.subs) {
        String[] disjoint = extractDisjointRequiredLiteralsFromAlternate(sub);
        if (disjoint != null) {
          return disjoint;
        }
      }
      return null;
    }
    return extractDisjointRequiredLiteralsFromAlternate(node);
  }

  private static String[] extractDisjointRequiredLiteralsFromAlternate(Regexp re) {
    Regexp node = re;
    while (node != null
        && (node.op == RegexpOp.CAPTURE
            || node.op == RegexpOp.NON_CAPTURE
            || node.op == RegexpOp.PLUS)) {
      node = node.sub();
    }
    if (node == null
        || node.op != RegexpOp.ALTERNATE
        || node.subs == null
        || node.subs.size() < 2) {
      return null;
    }
    Set<String> literalSet = new LinkedHashSet<>();
    for (Regexp branch : node.subs) {
      String req = extractRequiredLiteral(branch);
      if (req == null || req.length() < 2) {
        return null;
      }
      literalSet.add(req);
      if (literalSet.size() > MAX_DISJOINT_REQUIRED_LITERALS) {
        return null;
      }
    }
    // Substring subsumption / set minimization:
    // If literal A is a substring of literal B, any text containing B already contains A.
    // Therefore, searching for A is sufficient to cover branch B, and the longer literal B can
    // be pruned from the required search set.
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

  /**
   * Returns {@code true} if the AST contains any CAPTURE node with {@code cap > 0} (user capture
   * groups, not the implicit group 0).
   */
  private static boolean hasUserCaptures(Regexp re) {
    Deque<Regexp> stack = new ArrayDeque<>();
    stack.push(re);
    while (!stack.isEmpty()) {
      Regexp node = stack.pop();
      if (node.op == RegexpOp.CAPTURE && node.cap > 0) {
        return true;
      }
      if (node.subs != null) {
        for (Regexp sub : node.subs) {
          stack.push(sub);
        }
      }
    }
    return false;
  }

  private record LiteralResult(String literal, boolean foldCase) {
    static final LiteralResult NONE = new LiteralResult(null, false);
  }

  /**
   * Extracts the full literal string if this pattern is entirely literal (no metacharacters, no
   * quantifiers, no alternation). Transparent groups (e.g. non-capturing groups) are unwrapped.
   *
   * <p>Returns {@link LiteralResult#NONE} if the pattern is not fully literal. For case-insensitive
   * patterns, returns the lowercase version and foldCase=true.
   */
  private static LiteralResult extractLiteralMatch(Regexp re) {
    Regexp node = re;

    // Unwrap outer CAPTURE (group 0).
    while (node != null && node.op == RegexpOp.CAPTURE) {
      node = node.sub();
    }
    if (node == null) {
      return LiteralResult.NONE;
    }

    boolean foldCase = false;
    boolean foldCaseInitialized = false;
    StringBuilder sb = new StringBuilder();

    switch (node.op) {
      case LITERAL -> {
        foldCase = (node.flags & ParseFlags.FOLD_CASE) != 0;
        sb.appendCodePoint(node.rune);
      }
      case LITERAL_STRING -> {
        foldCase = (node.flags & ParseFlags.FOLD_CASE) != 0;
        if (node.runes != null) {
          for (int r : node.runes) {
            sb.appendCodePoint(r);
          }
        }
      }
      case CONCAT -> {
        for (Regexp child : node.subs) {
          // Each child must be LITERAL or LITERAL_STRING (not wrapped in CAPTURE etc.)
          Regexp c = child;
          while (c != null && (c.op == RegexpOp.CAPTURE || c.op == RegexpOp.NON_CAPTURE)) {
            c = c.sub();
          }
          if (c == null) {
            return LiteralResult.NONE;
          }
          if (c.op == RegexpOp.BEGIN_TEXT) {
            if (sb.length() > 0) {
              return LiteralResult.NONE;
            }
            continue;
          }
          boolean childFoldCase = (c.flags & ParseFlags.FOLD_CASE) != 0;
          if (!foldCaseInitialized) {
            foldCase = childFoldCase;
            foldCaseInitialized = true;
          } else if (childFoldCase != foldCase) {
            return LiteralResult.NONE;
          }
          if (c.op == RegexpOp.LITERAL) {
            sb.appendCodePoint(c.rune);
          } else if (c.op == RegexpOp.LITERAL_STRING && c.runes != null) {
            for (int r : c.runes) {
              sb.appendCodePoint(r);
            }
          } else {
            return LiteralResult.NONE;
          }
        }
      }
      case EMPTY_MATCH -> {
        // Empty pattern matches empty string.
        return new LiteralResult("", false);
      }
      default -> {
        return LiteralResult.NONE;
      }
    }

    if (sb.isEmpty()) {
      return new LiteralResult("", false);
    }
    return new LiteralResult(
        foldCase ? sb.toString().toLowerCase(Locale.ROOT) : sb.toString(), foldCase);
  }

  private static final class MinMatchLengthWalker extends Walker<Integer> {
    @Override
    protected Integer shortVisit(Regexp re, Integer parentArg) {
      return 0;
    }

    @Override
    protected Integer postVisit(
        Regexp re, Integer parentArg, Integer preArg, List<Integer> childArgs) {
      return switch (re.op) {
        case NO_MATCH -> Integer.MAX_VALUE / 2;
        case EMPTY_MATCH,
            BEGIN_LINE,
            END_LINE,
            BEGIN_TEXT,
            END_TEXT,
            WORD_BOUNDARY,
            NO_WORD_BOUNDARY,
            GRAPHEME_CLUSTER_BOUNDARY,
            HAVE_MATCH,
            QUEST,
            STAR ->
            0;
        case LITERAL -> Character.charCount(re.rune);
        case LITERAL_STRING -> {
          int count = 0;
          if (re.runes != null) {
            for (int r : re.runes) {
              count += Character.charCount(r);
            }
          }
          yield count;
        }
        case CHAR_CLASS, ANY_CHAR, ANY_BYTE, GRAPHEME_CLUSTER -> 1;
        case CAPTURE, NON_CAPTURE, PLUS -> childArgs.isEmpty() ? 0 : childArgs.getFirst();
        case REPEAT -> {
          int subMin = childArgs.isEmpty() ? 0 : childArgs.getFirst();
          yield (int) Math.min((long) subMin * Math.max(0, re.min), Integer.MAX_VALUE / 2);
        }
        case CONCAT -> {
          long sum = 0;
          for (int child : childArgs) {
            sum += child;
          }
          yield (int) Math.min(sum, Integer.MAX_VALUE / 2);
        }
        case ALTERNATE -> {
          int min = Integer.MAX_VALUE / 2;
          for (int child : childArgs) {
            min = Math.min(min, child);
          }
          yield min == Integer.MAX_VALUE / 2 ? 0 : min;
        }
      };
    }
  }

  private static int extractMinMatchLength(Regexp re) {
    if (re == null) {
      return 0;
    }
    return new MinMatchLengthWalker().walk(re, 0);
  }

  /** Deserialization: recompile the pattern from the stored string and flags. */
  private Object readResolve() {
    return compile(pattern, flags);
  }
}
