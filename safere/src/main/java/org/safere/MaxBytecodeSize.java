// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Bounds the size of a method's bytecode, for methods whose performance depends on the JIT
/// inlining them into their callers.
///
/// HotSpot decides whether to inline a call partly by the callee's bytecode size: C2 always
/// considers methods of at most `-XX:MaxInlineSize` (35) bytes, and larger methods only at hot call
/// sites and subject to further budgets. A small, unrelated edit can push a method across one of
/// these limits and silently cost a large fraction of its callers' throughput. `BytecodeBudgetTest`
/// checks every annotated method against its bounds.
///
/// Sizes are the length of the method's `Code` attribute, in bytes. `javap -c` lists the offset of
/// the last instruction, which is one to a few bytes less than that.
///
/// Annotate only methods where inlining has been shown to matter, and give the evidence in
/// [#reason()].
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
@interface MaxBytecodeSize {
  /// The largest allowed `Code` attribute length, in bytes.
  int value();

  /// The smallest allowed `Code` attribute length, in bytes. Set this to [#value()] for a method
  /// whose size was tuned and whose performance was measured to regress when it shrinks, too.
  int min() default 0;

  /// Why the method has to stay within its bounds, and what to measure before changing them.
  String reason();
}
