// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Checks every {@link MaxBytecodeSize} method in SafeRE's compiled classes against its bounds.
 *
 * <p>Inlining depends on bytecode size, so these methods can lose much of their callers' throughput
 * to an edit that looks harmless in the source. Benchmarks catch that only if they exercise the
 * affected caller; this catches the size change itself.
 */
@DisabledForCrosscheck("Reads SafeRE's own class files")
class BytecodeBudgetTest {

  private static final String ANNOTATION = Type.getDescriptor(MaxBytecodeSize.class);

  /**
   * Guards against the test passing because it found no annotations, for example because the
   * annotation's retention changed or the classes were read from the wrong place.
   */
  private static final int MIN_ANNOTATED_METHODS = 3;

  private record Budget(String method, int max, int min, String reason, int codeLength) {}

  @Test
  void annotatedMethodsStayWithinTheirBytecodeBudgets() throws IOException, URISyntaxException {
    assumeFalse(
        WorkCounterConfig.ENABLED,
        "-Pwork-counters compiles WorkCounter calls into the methods, which enlarges them");
    List<Budget> budgets = new ArrayList<>();
    forEachClassFile(bytes -> budgets.addAll(budgets(new ClassReader(bytes))));

    assertThat(budgets).hasSizeGreaterThanOrEqualTo(MIN_ANNOTATED_METHODS);
    List<String> failures = new ArrayList<>();
    for (Budget budget : budgets) {
      if (budget.codeLength() > budget.max() || budget.codeLength() < budget.min()) {
        failures.add(
            String.format(
                "%s has %d bytes of bytecode, outside its budget [%d, %d]: %s",
                budget.method(), budget.codeLength(), budget.min(), budget.max(), budget.reason()));
      }
    }
    assertThat(failures).isEmpty();
  }

  private interface ClassFileConsumer {
    void accept(byte[] bytes) throws IOException;
  }

  /** Visits the class files of package {@code org.safere}, from a directory or a jar. */
  private static void forEachClassFile(ClassFileConsumer consumer)
      throws IOException, URISyntaxException {
    Path location =
        Path.of(Pattern.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    if (Files.isDirectory(location)) {
      forEachClassFile(location.resolve("org/safere"), consumer);
    } else {
      try (FileSystem jar = FileSystems.newFileSystem(location)) {
        forEachClassFile(jar.getPath("org/safere"), consumer);
      }
    }
  }

  private static void forEachClassFile(Path packageDirectory, ClassFileConsumer consumer)
      throws IOException {
    List<Path> classFiles;
    try (Stream<Path> files = Files.list(packageDirectory)) {
      classFiles = files.filter(f -> f.toString().endsWith(".class")).toList();
    }
    for (Path classFile : classFiles) {
      consumer.accept(Files.readAllBytes(classFile));
    }
  }

  /** Returns the budgets of the annotated methods in one class. */
  private static List<Budget> budgets(ClassReader reader) {
    Map<String, Integer> codeLengths = codeLengths(reader);
    List<Budget> budgets = new ArrayList<>();
    reader.accept(
        new ClassVisitor(Opcodes.ASM9) {
          @Override
          public MethodVisitor visitMethod(
              int access, String name, String descriptor, String signature, String[] exceptions) {
            String method = reader.getClassName().replace('/', '.') + "." + name + descriptor;
            return new MethodVisitor(Opcodes.ASM9) {
              @Override
              public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                if (!annotation.equals(ANNOTATION)) {
                  return null;
                }
                return new AnnotationVisitor(Opcodes.ASM9) {
                  int max = -1;
                  int min = 0;
                  String reason = "";

                  @Override
                  public void visit(String element, Object value) {
                    switch (element) {
                      case "value" -> max = (Integer) value;
                      case "min" -> min = (Integer) value;
                      case "reason" -> reason = (String) value;
                      default -> throw new AssertionError(element);
                    }
                  }

                  @Override
                  public void visitEnd() {
                    budgets.add(
                        new Budget(method, max, min, reason, codeLengths.get(name + descriptor)));
                  }
                };
              }
            };
          }
        },
        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    return budgets;
  }

  /**
   * Returns the length of each method's {@code Code} attribute, keyed by name and descriptor.
   *
   * <p>ASM's visitor API doesn't report this length, so this walks the {@code methods} table of the
   * class file (JVMS 4.1, 4.6, 4.7.3) with ASM's constant-pool-aware readers.
   */
  private static Map<String, Integer> codeLengths(ClassReader reader) {
    char[] buffer = new char[reader.getMaxStringLength()];
    // Skip access_flags, this_class, super_class and the interfaces.
    int offset = reader.header + 6;
    offset += 2 + 2 * reader.readUnsignedShort(offset);
    int fields = reader.readUnsignedShort(offset);
    offset += 2;
    for (int i = 0; i < fields; i++) {
      offset = skipAttributes(reader, offset + 6);
    }
    Map<String, Integer> codeLengths = new HashMap<>();
    int methods = reader.readUnsignedShort(offset);
    offset += 2;
    for (int i = 0; i < methods; i++) {
      String key = reader.readUTF8(offset + 2, buffer) + reader.readUTF8(offset + 4, buffer);
      int attributes = reader.readUnsignedShort(offset + 6);
      offset += 8;
      for (int j = 0; j < attributes; j++) {
        if (reader.readUTF8(offset, buffer).equals("Code")) {
          // attribute_name_index, attribute_length, max_stack, max_locals, then code_length.
          codeLengths.put(key, reader.readInt(offset + 10));
        }
        offset += 6 + reader.readInt(offset + 2);
      }
    }
    return codeLengths;
  }

  /** Skips an {@code attributes_count} and the attributes that follow it. */
  private static int skipAttributes(ClassReader reader, int offset) {
    int attributes = reader.readUnsignedShort(offset);
    offset += 2;
    for (int i = 0; i < attributes; i++) {
      offset += 6 + reader.readInt(offset + 2);
    }
    return offset;
  }
}
