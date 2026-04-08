// Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.crosscheck;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Records a trace of API calls made through the crosscheck facade. Each call is recorded as a
 * {@link TraceEntry} containing the method name, arguments, and results from both engines.
 *
 * <p>The trace is included in {@link CrosscheckException} on divergence and can also be retrieved
 * manually via {@link Matcher#getTrace()} for debugging.
 */
public final class TraceRecorder {

  private final List<TraceEntry> entries = new ArrayList<>();

  /** A single recorded API call with results from both engines. */
  public record TraceEntry(
      String method,
      String args,
      String safereResult,
      String jdkResult,
      boolean matched) {

    /** Creates a trace entry, converting results to strings. */
    public static TraceEntry of(
        String method, String args, Object safereResult, Object jdkResult) {
      String sr = Objects.toString(safereResult);
      String jr = Objects.toString(jdkResult);
      return new TraceEntry(method, args, sr, jr, Objects.equals(sr, jr));
    }
  }

  /** Records a call where both engines produced the same result. */
  void recordMatch(String method, String args, Object result) {
    String s = Objects.toString(result);
    entries.add(new TraceEntry(method, args, s, s, true));
  }

  /** Records a call where the engines produced different results. */
  void recordDivergence(String method, String args, Object safereResult, Object jdkResult) {
    entries.add(TraceEntry.of(method, args, safereResult, jdkResult));
  }

  /** Records a call with explicit results from both engines. */
  void record(String method, String args, Object safereResult, Object jdkResult) {
    entries.add(TraceEntry.of(method, args, safereResult, jdkResult));
  }

  /** Returns an unmodifiable view of all recorded entries. */
  public List<TraceEntry> getEntries() {
    return Collections.unmodifiableList(entries);
  }

  /** Clears all recorded entries. */
  public void clear() {
    entries.clear();
  }

  /** Formats the trace as a human-readable string for inclusion in error messages and bug reports. */
  public String format() {
    if (entries.isEmpty()) {
      return "(empty trace)";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("API call trace (").append(entries.size()).append(" calls):\n");
    for (int i = 0; i < entries.size(); i++) {
      TraceEntry e = entries.get(i);
      sb.append(String.format("  [%d] %s(%s)", i + 1, e.method(), e.args()));
      if (e.matched()) {
        sb.append(" → ").append(e.safereResult());
      } else {
        sb.append(" → DIVERGENCE\n");
        sb.append("        SafeRE: ").append(e.safereResult()).append('\n');
        sb.append("        JDK:    ").append(e.jdkResult());
      }
      sb.append('\n');
    }
    return sb.toString();
  }
}
