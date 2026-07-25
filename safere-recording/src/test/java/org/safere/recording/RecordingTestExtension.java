// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.recording;

import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Attributes facade events to the currently executing JUnit invocation. */
public final class RecordingTestExtension
    implements BeforeTestExecutionCallback, AfterTestExecutionCallback {

  @Override
  public void beforeTestExecution(ExtensionContext context) {
    RecordingRuntime.startTest(context.getUniqueId());
  }

  @Override
  public void afterTestExecution(ExtensionContext context) {
    RecordingRuntime.finishTest(context.getUniqueId());
  }
}
