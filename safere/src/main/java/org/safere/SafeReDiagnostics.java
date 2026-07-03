// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Objects;

/**
 * Diagnostic export helpers for verifier fixtures and bytecode inspection.
 *
 * <p>This API is intended for tests and verification tooling. The JSON schema is versioned, but
 * this class is not part of SafeRE's regex-matching API.
 *
 * <p>In bytecode fixture results, each group record includes {@code matched}. Nonparticipating
 * captures are exported with {@code matched: false} and Java/SafeRE {@code -1} start/end offsets.
 */
public final class SafeReDiagnostics {

  /** Match mode for exported result metadata. */
  public enum BytecodeMatchMode {
    /** Run an NFA search anchored at the beginning of the input. */
    ANCHORED("anchored");

    private final String jsonName;

    BytecodeMatchMode(String jsonName) {
      this.jsonName = jsonName;
    }
  }

  /** Engine used to produce bytecode fixture result metadata. */
  public enum BytecodeResultEngine {
    /** SafeRE's Pike VM NFA engine. */
    NFA
  }

  private static final String SCHEMA = "safere-bytecode-v1";
  private static final Nfa.MatchKind BYTECODE_MATCH_KIND = Nfa.MatchKind.FIRST_MATCH;

  /**
   * Exports one verifier fixture JSONL record using SafeRE's NFA-facing compiled program.
   *
   * <p>The exported program is {@code Pattern.prog()}, not the DFA-oriented flattened program. The
   * result object is produced by directly invoking SafeRE's NFA on that same program.
   *
   * @param caseId stable fixture case identifier
   * @param regex source regular expression to record in the fixture
   * @param flags source flags value to record in the fixture
   * @param input input text to record and match
   * @param mode match mode for result metadata; currently only {@link BytecodeMatchMode#ANCHORED}
   * @param engine engine for result metadata; currently only {@link BytecodeResultEngine#NFA}
   * @param pattern compiled SafeRE pattern whose NFA program is exported
   * @return one complete JSON object followed by no trailing newline
   */
  public static String bytecodeCaseToJsonLine(
      String caseId,
      String regex,
      int flags,
      String input,
      BytecodeMatchMode mode,
      BytecodeResultEngine engine,
      Pattern pattern) {
    Objects.requireNonNull(caseId, "caseId");
    Objects.requireNonNull(regex, "regex");
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(engine, "engine");
    Objects.requireNonNull(pattern, "pattern");
    if (mode != BytecodeMatchMode.ANCHORED) {
      throw new IllegalArgumentException("Unsupported match mode: " + mode);
    }
    if (engine != BytecodeResultEngine.NFA) {
      throw new IllegalArgumentException("Unsupported diagnostic engine: " + engine);
    }

    Prog prog = pattern.prog();
    if (prog.didFlatten()) {
      throw new IllegalArgumentException("NFA bytecode export requires an unflattened program");
    }

    StringBuilder json = new StringBuilder();
    json.append('{');
    appendStringField(json, "schema", SCHEMA);
    json.append(',');
    appendProducer(json);
    json.append(',');
    appendCase(json, caseId, regex, flags, input, mode, engine, BYTECODE_MATCH_KIND);
    json.append(',');
    appendResult(json, prog, input, BYTECODE_MATCH_KIND);
    json.append(',');
    appendProgram(json, prog);
    json.append('}');
    return json.toString();
  }

  /**
   * Compiles a pattern and exports one verifier fixture JSONL record using SafeRE's NFA engine
   * result metadata.
   *
   * @param caseId stable fixture case identifier
   * @param regex source regular expression to compile and record in the fixture
   * @param flags source flags value to compile and record in the fixture
   * @param input input text to record and match
   * @param mode match mode for result metadata; currently only {@link BytecodeMatchMode#ANCHORED}
   * @return one complete JSON object followed by no trailing newline
   */
  public static String bytecodeCaseToJsonLine(
      String caseId, String regex, int flags, String input, BytecodeMatchMode mode) {
    return bytecodeCaseToJsonLine(caseId, regex, flags, input, mode, Pattern.compile(regex, flags));
  }

  /**
   * Exports one verifier fixture JSONL record using SafeRE's NFA engine result metadata.
   *
   * @param caseId stable fixture case identifier
   * @param regex source regular expression to record in the fixture
   * @param flags source flags value to record in the fixture
   * @param input input text to record and match
   * @param mode match mode for result metadata; currently only {@link BytecodeMatchMode#ANCHORED}
   * @param pattern compiled SafeRE pattern whose NFA program is exported
   * @return one complete JSON object followed by no trailing newline
   */
  public static String bytecodeCaseToJsonLine(
      String caseId,
      String regex,
      int flags,
      String input,
      BytecodeMatchMode mode,
      Pattern pattern) {
    return bytecodeCaseToJsonLine(
        caseId, regex, flags, input, mode, BytecodeResultEngine.NFA, pattern);
  }

  private static void appendProducer(StringBuilder json) {
    json.append("\"producer\":{");
    appendStringField(json, "name", "safere");
    json.append('}');
  }

  private static void appendCase(
      StringBuilder json,
      String caseId,
      String regex,
      int flags,
      String input,
      BytecodeMatchMode mode,
      BytecodeResultEngine engine,
      Nfa.MatchKind matchKind) {
    json.append("\"case\":{");
    appendStringField(json, "id", caseId);
    json.append(',');
    appendStringField(json, "pattern", regex);
    json.append(',');
    appendFlags(json, flags);
    json.append(',');
    appendNumberField(json, "flagsValue", flags);
    json.append(',');
    appendStringField(json, "input", input);
    json.append(',');
    appendStringField(json, "mode", mode.jsonName);
    json.append(',');
    appendStringField(json, "engine", engine.name());
    json.append(',');
    appendStringField(json, "matchKind", matchKind.name());
    json.append('}');
  }

  private static void appendFlags(StringBuilder json, int flags) {
    json.append("\"flags\":[");
    boolean needsComma = false;
    needsComma = appendFlag(json, needsComma, flags, Pattern.UNIX_LINES, "UNIX_LINES");
    needsComma = appendFlag(json, needsComma, flags, Pattern.CASE_INSENSITIVE, "CASE_INSENSITIVE");
    needsComma = appendFlag(json, needsComma, flags, Pattern.COMMENTS, "COMMENTS");
    needsComma = appendFlag(json, needsComma, flags, Pattern.MULTILINE, "MULTILINE");
    needsComma = appendFlag(json, needsComma, flags, Pattern.LITERAL, "LITERAL");
    needsComma = appendFlag(json, needsComma, flags, Pattern.DOTALL, "DOTALL");
    needsComma = appendFlag(json, needsComma, flags, Pattern.UNICODE_CASE, "UNICODE_CASE");
    appendFlag(json, needsComma, flags, Pattern.UNICODE_CHARACTER_CLASS, "UNICODE_CHARACTER_CLASS");
    json.append(']');
  }

  private static boolean appendFlag(
      StringBuilder json, boolean needsComma, int flags, int flag, String name) {
    if ((flags & flag) == 0) {
      return needsComma;
    }
    if (needsComma) {
      json.append(',');
    }
    appendString(json, name);
    return true;
  }

  private static void appendResult(
      StringBuilder json, Prog prog, String input, Nfa.MatchKind matchKind) {
    int[] groups =
        Nfa.search(
            prog,
            input,
            0,
            input.length(),
            input.length(),
            0,
            Nfa.Anchor.ANCHORED,
            matchKind,
            prog.numCaptures(),
            null);
    json.append("\"result\":{");
    appendBooleanField(json, "matched", groups != null);
    json.append(',');
    json.append("\"groups\":[");
    if (groups != null) {
      for (int group = 0; group < prog.numCaptures(); group++) {
        if (group > 0) {
          json.append(',');
        }
        json.append('{');
        appendNumberField(json, "group", group);
        json.append(',');
        appendBooleanField(json, "matched", groups[2 * group] >= 0);
        json.append(',');
        appendNumberField(json, "start", groups[2 * group]);
        json.append(',');
        appendNumberField(json, "end", groups[2 * group + 1]);
        json.append('}');
      }
    }
    json.append("]}");
  }

  private static void appendProgram(StringBuilder json, Prog prog) {
    json.append("\"program\":{");
    appendStringField(json, "shape", "nfa-prog");
    json.append(',');
    appendBooleanField(json, "didFlatten", prog.didFlatten());
    json.append(',');
    appendNumberField(json, "size", prog.size());
    json.append(',');
    appendNumberField(json, "start", prog.start());
    json.append(',');
    appendNumberField(json, "startUnanchored", prog.startUnanchored());
    json.append(',');
    appendNumberField(json, "numCaptures", prog.numCaptures());
    json.append(',');
    appendNumberField(json, "numCaptureSlots", 2 * Math.max(prog.numCaptures(), 1));
    json.append(',');
    appendNumberField(json, "numLoopRegs", prog.numLoopRegs());
    json.append(',');
    appendBooleanField(json, "anchorStart", prog.anchorStart());
    json.append(',');
    appendBooleanField(json, "anchorEnd", prog.anchorEnd());
    json.append(',');
    appendBooleanField(json, "dollarAnchorEnd", prog.dollarAnchorEnd());
    json.append(',');
    appendBooleanField(json, "reversed", prog.reversed());
    json.append(',');
    appendBooleanField(json, "unixLines", prog.unixLines());
    json.append(',');
    appendBooleanField(
        json, "requiresPikeNfaCaptureSemantics", prog.requiresPikeNfaCaptureSemantics());
    json.append(',');
    appendBooleanField(json, "hasGraphemeSemantics", prog.hasGraphemeSemantics());
    json.append(',');
    appendBooleanField(json, "hasGraphemeClusterInstruction", prog.hasGraphemeClusterInstruction());
    json.append(',');
    appendInstructions(json, prog);
    json.append('}');
  }

  private static void appendInstructions(StringBuilder json, Prog prog) {
    json.append("\"instructions\":[");
    for (int id = 0; id < prog.size(); id++) {
      if (id > 0) {
        json.append(',');
      }
      Inst inst = prog.inst(id);
      json.append('{');
      appendNumberField(json, "id", id);
      json.append(',');
      appendStringField(json, "op", inst.op.name());
      appendInstructionFields(json, inst);
      json.append('}');
    }
    json.append(']');
  }

  private static void appendInstructionFields(StringBuilder json, Inst inst) {
    switch (inst.op) {
      case FAIL -> {}
      case MATCH -> {
        json.append(',');
        appendNumberField(json, "arg", inst.arg);
      }
      case NOP, CAPTURE, EMPTY_WIDTH, GRAPHEME_CLUSTER -> appendArgOutFields(json, inst);
      case ALT, ALT_MATCH -> appendOutOut1Fields(json, inst);
      case CHAR_RANGE -> appendCharRangeFields(json, inst);
      case CHAR_CLASS -> appendCharClassFields(json, inst);
      case PROGRESS_CHECK -> appendProgressCheckFields(json, inst);
    }
  }

  private static void appendArgOutFields(StringBuilder json, Inst inst) {
    if (inst.op == InstOp.CAPTURE || inst.op == InstOp.EMPTY_WIDTH) {
      json.append(',');
      appendNumberField(json, "arg", inst.arg);
    }
    json.append(',');
    appendNumberField(json, "out", inst.out);
  }

  private static void appendOutOut1Fields(StringBuilder json, Inst inst) {
    json.append(',');
    appendNumberField(json, "out", inst.out);
    json.append(',');
    appendNumberField(json, "out1", inst.out1);
  }

  private static void appendCharRangeFields(StringBuilder json, Inst inst) {
    json.append(',');
    appendNumberField(json, "out", inst.out);
    json.append(',');
    appendNumberField(json, "lo", inst.lo);
    json.append(',');
    appendNumberField(json, "hi", inst.hi);
    json.append(',');
    appendBooleanField(json, "foldCase", inst.foldCase);
  }

  private static void appendCharClassFields(StringBuilder json, Inst inst) {
    json.append(',');
    appendNumberField(json, "out", inst.out);
    json.append(',');
    json.append("\"ranges\":[");
    for (int i = 0; i < inst.ranges.length; i += 2) {
      if (i > 0) {
        json.append(',');
      }
      json.append('[').append(inst.ranges[i]).append(',').append(inst.ranges[i + 1]).append(']');
    }
    json.append(']');
  }

  private static void appendProgressCheckFields(StringBuilder json, Inst inst) {
    json.append(',');
    appendNumberField(json, "arg", inst.arg);
    json.append(',');
    appendNumberField(json, "out", inst.out);
    json.append(',');
    appendNumberField(json, "out1", inst.out1);
    json.append(',');
    appendBooleanField(json, "nonGreedy", inst.foldCase);
  }

  private static void appendStringField(StringBuilder json, String name, String value) {
    appendString(json, name);
    json.append(':');
    appendString(json, value);
  }

  private static void appendNumberField(StringBuilder json, String name, int value) {
    appendString(json, name);
    json.append(':').append(value);
  }

  private static void appendBooleanField(StringBuilder json, String name, boolean value) {
    appendString(json, name);
    json.append(':').append(value);
  }

  private static void appendString(StringBuilder json, String value) {
    json.append('"');
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      switch (ch) {
        case '"' -> json.append("\\\"");
        case '\\' -> json.append("\\\\");
        case '\b' -> json.append("\\b");
        case '\f' -> json.append("\\f");
        case '\n' -> json.append("\\n");
        case '\r' -> json.append("\\r");
        case '\t' -> json.append("\\t");
        default -> appendJsonChar(json, ch);
      }
    }
    json.append('"');
  }

  private static void appendJsonChar(StringBuilder json, char ch) {
    if (ch < 0x20 || Character.isSurrogate(ch)) {
      appendUnicodeEscape(json, ch);
    } else {
      json.append(ch);
    }
  }

  private static void appendUnicodeEscape(StringBuilder json, char ch) {
    json.append("\\u");
    String hex = Integer.toHexString(ch);
    for (int i = hex.length(); i < 4; i++) {
      json.append('0');
    }
    json.append(hex);
  }

  private SafeReDiagnostics() {}
}
