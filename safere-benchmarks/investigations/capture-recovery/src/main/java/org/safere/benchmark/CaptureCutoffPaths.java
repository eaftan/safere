// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.safere.OperationDiagnostics;
import org.safere.Pattern;
import org.safere.SafeReMatchDiagnostics;

/** Separate-process path inspection; never installs diagnostics in a timing JVM. */
public final class CaptureCutoffPaths {
  private CaptureCutoffPaths() {}

  /** Writes observed paths for trial, operation, and profile lists supplied by the runner. */
  public static void main(String[] args) {
    if (args.length != 3) {
      throw new IllegalArgumentException("Expected comma-separated trials, operations, profiles");
    }
    List<Map<String, Object>> results = new ArrayList<>();
    JsonObject config = BenchmarkData.get().investigationConfiguration();
    int documents = config.get("documentsPerInvocation").getAsInt();
    for (String trialId : args[0].split(",")) {
      var trial = CrossEngineBenchmarkPlan.load().resolve(trialId);
      var workload = trial.workload();
      var inputs = trial.variant().prepareInputs(BenchmarkData.get(), workload.inputKeys());
      for (String operation : args[1].split(",")) {
        for (String profile : args[2].split(",")) {
          JsonObject schedule = config.getAsJsonObject("profiles").getAsJsonObject(profile);
          List<OperationDiagnostics> events = new ArrayList<>();
          try (var task =
              new CaptureCutoffTask(
                  trial.variant(),
                  workload.patterns().getFirst(),
                  inputs,
                  operation,
                  documents,
                  schedule.get("hitEvery").getAsInt(),
                  schedule.get("readEvery").getAsInt())) {
            Pattern.setDiagnostics(
                new SafeReMatchDiagnostics() {
                  @Override
                  public void onOperationCompleted(OperationDiagnostics event) {
                    events.add(event);
                  }
                });
            task.run();
          } finally {
            Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
          }
          if (events.size() != documents) {
            throw new IllegalStateException("Unexpected path-event count: " + trialId);
          }
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("trial", trialId);
          row.put("operation", operation);
          row.put("profile", profile);
          row.put(
              "paths",
              events.stream()
                  .map(
                      event -> {
                        Map<String, Object> path = new LinkedHashMap<>();
                        path.put("boundary", event.boundaryStrategy().name());
                        path.put("outcome", event.outcome().name());
                        path.put("forwardDfa", event.forwardDfaSearchCount());
                        path.put("reverseDfa", event.reverseDfaSearchCount());
                        return path;
                      })
                  .distinct()
                  .toList());
          results.add(row);
        }
      }
    }
    System.out.println(new Gson().toJson(results));
  }
}
