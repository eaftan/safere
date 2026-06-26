// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for deterministic work accounting. */
class WorkCounterTest {
  @Test
  @DisplayName("countForTesting throws when work counters are disabled")
  void countForTestingThrowsWhenCountersAreDisabled() {
    assertThatThrownBy(() -> WorkCounter.countForTesting(() -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("WorkCounter is disabled");
  }

  @Test
  @DisplayName("production bytecode has no work counter method or field references when disabled")
  void productionBytecodeHasNoWorkCounterMethodOrFieldReferencesWhenDisabled()
      throws IOException, URISyntaxException {
    Path classesRoot =
        Path.of(WorkCounter.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    Path packageRoot = classesRoot.resolve("org/safere");
    List<String> references = new ArrayList<>();

    try (var classFiles = Files.walk(packageRoot)) {
      List<Path> compiledClasses =
          classFiles.filter(path -> path.toString().endsWith(".class")).toList();
      for (Path classFile : compiledClasses) {
        String classFileName = classFile.getFileName().toString();
        if (classFileName.startsWith("WorkCounter")) {
          continue;
        }
        references.addAll(workCounterMethodOrFieldReferences(classFile));
      }
    }

    assertThat(references).isEmpty();
  }

  private static List<String> workCounterMethodOrFieldReferences(Path classFile)
      throws IOException {
    try (InputStream input = Files.newInputStream(classFile);
        DataInputStream data = new DataInputStream(input)) {
      int magic = data.readInt();
      assertThat(magic).isEqualTo(0xCAFEBABE);
      data.readUnsignedShort();
      data.readUnsignedShort();

      ConstantPool constantPool = ConstantPool.read(data);
      List<String> references = new ArrayList<>();
      for (ConstantPool.Ref ref : constantPool.refs) {
        String owner = constantPool.className(ref.classIndex);
        if (!owner.equals("org/safere/WorkCounter")
            && !owner.equals("org/safere/WorkCounterConfig")) {
          continue;
        }
        references.add(
            classFile.getFileName() + " references " + owner + "." + constantPool.name(ref));
      }
      return references;
    }
  }

  private static final class ConstantPool {
    private final List<Ref> refs = new ArrayList<>();
    private final String[] utf8s;
    private final int[] classNameIndexes;
    private final int[] nameAndTypeNameIndexes;

    private ConstantPool(int size) {
      utf8s = new String[size];
      classNameIndexes = new int[size];
      nameAndTypeNameIndexes = new int[size];
    }

    private static ConstantPool read(DataInputStream data) throws IOException {
      int constantPoolCount = data.readUnsignedShort();
      ConstantPool constantPool = new ConstantPool(constantPoolCount);
      for (int index = 1; index < constantPoolCount; index++) {
        int tag = data.readUnsignedByte();
        switch (tag) {
          case 1 -> constantPool.utf8s[index] = data.readUTF();
          case 3, 4 -> data.skipBytes(4);
          case 5, 6 -> {
            data.skipBytes(8);
            index++;
          }
          case 7 -> constantPool.classNameIndexes[index] = data.readUnsignedShort();
          case 8, 16, 19, 20 -> data.skipBytes(2);
          case 9, 10, 11 -> {
            int classIndex = data.readUnsignedShort();
            int nameAndTypeIndex = data.readUnsignedShort();
            constantPool.refs.add(new Ref(classIndex, nameAndTypeIndex));
          }
          case 12 -> {
            constantPool.nameAndTypeNameIndexes[index] = data.readUnsignedShort();
            data.readUnsignedShort();
          }
          case 15 -> data.skipBytes(3);
          case 17, 18 -> data.skipBytes(4);
          default -> throw new IOException("Unsupported constant pool tag " + tag);
        }
      }
      return constantPool;
    }

    private String className(int classIndex) {
      return utf8s[classNameIndexes[classIndex]];
    }

    private String name(Ref ref) {
      return utf8s[nameAndTypeNameIndexes[ref.nameAndTypeIndex]];
    }

    private record Ref(int classIndex, int nameAndTypeIndex) {}
  }
}
