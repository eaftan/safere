// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.EnumMap;
import java.util.Map;

/**
 * Package-private engine-path controls for forced-path equivalence tests.
 *
 * <p>Production code uses {@link #allEnabled()}. Tests in this package can compile a pattern with
 * selected shortcuts disabled to compare observable API traces against the default engine cascade.
 */
public record EnginePathOptions(
    boolean literalFastPaths,
    boolean charClassMatchFastPaths,
    boolean charClassReplacementFastPath,
    boolean keywordAlternationFastPath,
    boolean startAcceleration,
    boolean onePass,
    boolean dfa,
    boolean reverseDfa,
    boolean bitState,
    boolean lazyCaptureExtraction,
    boolean semanticGuards) {

  private static final EnginePathOptions ALL_ENABLED = builder().build();
  private static final Map<EnginePath, OptionAccessor> ACCESSORS = buildAccessors();

  public static EnginePathOptions allEnabled() {
    return ALL_ENABLED;
  }

  public static Builder builder() {
    return new Builder();
  }

  static Map<EnginePath, OptionAccessor> accessors() {
    return ACCESSORS;
  }

  private static Map<EnginePath, OptionAccessor> buildAccessors() {
    EnumMap<EnginePath, OptionAccessor> accessors = new EnumMap<>(EnginePath.class);
    accessors.put(EnginePath.LITERAL_FAST_PATHS, EnginePathOptions::literalFastPaths);
    accessors.put(
        EnginePath.CHAR_CLASS_MATCH_FAST_PATHS, EnginePathOptions::charClassMatchFastPaths);
    accessors.put(
        EnginePath.CHAR_CLASS_REPLACEMENT_FAST_PATH,
        EnginePathOptions::charClassReplacementFastPath);
    accessors.put(
        EnginePath.KEYWORD_ALTERNATION_FAST_PATH, EnginePathOptions::keywordAlternationFastPath);
    accessors.put(EnginePath.START_ACCELERATION, EnginePathOptions::startAcceleration);
    accessors.put(EnginePath.ONE_PASS, EnginePathOptions::onePass);
    accessors.put(EnginePath.DFA, EnginePathOptions::dfa);
    accessors.put(EnginePath.REVERSE_DFA, EnginePathOptions::reverseDfa);
    accessors.put(EnginePath.BIT_STATE, EnginePathOptions::bitState);
    accessors.put(EnginePath.LAZY_CAPTURE_EXTRACTION, EnginePathOptions::lazyCaptureExtraction);
    return Map.copyOf(accessors);
  }

  interface OptionAccessor {
    boolean enabled(EnginePathOptions options);
  }

  public static final class Builder {
    public Builder() {}

    private boolean literalFastPaths = true;
    private boolean charClassMatchFastPaths = true;
    private boolean charClassReplacementFastPath = true;
    private boolean keywordAlternationFastPath = true;
    private boolean startAcceleration = true;
    private boolean onePass = true;
    private boolean dfa = true;
    private boolean reverseDfa = true;
    private boolean bitState = true;
    private boolean lazyCaptureExtraction = true;
    private boolean semanticGuards = true;

    public Builder literalFastPaths(boolean enabled) {
      literalFastPaths = enabled;
      return this;
    }

    public Builder charClassMatchFastPaths(boolean enabled) {
      charClassMatchFastPaths = enabled;
      return this;
    }

    public Builder charClassReplacementFastPath(boolean enabled) {
      charClassReplacementFastPath = enabled;
      return this;
    }

    public Builder keywordAlternationFastPath(boolean enabled) {
      keywordAlternationFastPath = enabled;
      return this;
    }

    public Builder startAcceleration(boolean enabled) {
      startAcceleration = enabled;
      return this;
    }

    public Builder onePass(boolean enabled) {
      onePass = enabled;
      return this;
    }

    public Builder dfa(boolean enabled) {
      dfa = enabled;
      return this;
    }

    public Builder reverseDfa(boolean enabled) {
      reverseDfa = enabled;
      return this;
    }

    public Builder bitState(boolean enabled) {
      bitState = enabled;
      return this;
    }

    public Builder lazyCaptureExtraction(boolean enabled) {
      lazyCaptureExtraction = enabled;
      return this;
    }

    public Builder semanticGuards(boolean enabled) {
      semanticGuards = enabled;
      return this;
    }

    public EnginePathOptions build() {
      return new EnginePathOptions(
          literalFastPaths,
          charClassMatchFastPaths,
          charClassReplacementFastPath,
          keywordAlternationFastPath,
          startAcceleration,
          onePass,
          dfa,
          reverseDfa,
          bitState,
          lazyCaptureExtraction,
          semanticGuards);
    }
  }
}
