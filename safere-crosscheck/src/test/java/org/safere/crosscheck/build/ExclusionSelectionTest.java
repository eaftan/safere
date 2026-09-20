// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.crosscheck.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the generation boundary with independently compiled source fixtures. */
class ExclusionSelectionTest {
  @TempDir Path temporary;

  @Test
  void excludesOnlyAnnotatedTopLevelTests() throws Exception {
    Path sources = temporary.resolve("sources");
    Files.createDirectories(sources);
    source(
        sources,
        "DisabledForCrosscheck",
        """
        package org.safere;
        import java.lang.annotation.*;
        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.TYPE, ElementType.METHOD})
        @interface DisabledForCrosscheck { String value(); }
        """);
    source(sources, "Internal", "package org.safere; class Internal {}");
    source(
        sources,
        "InternalTest",
        """
        package org.safere;
        @DisabledForCrosscheck("internal")
        class InternalTest { Internal value; }
        """);
    source(
        sources,
        "QualifiedTest",
        """
        package org.safere;
        @org.safere.DisabledForCrosscheck(value = "qualified")
        class QualifiedTest {}
        """);
    source(
        sources,
        "RetainedTest",
        """
        package org.safere;
        // @DisabledForCrosscheck("comment")
        class RetainedTest {
          String text = "@DisabledForCrosscheck";
          @DisabledForCrosscheck("method") void test() {}
          @DisabledForCrosscheck("nested") class Nested {}
        }
        """);
    source(
        sources,
        "PlainTest",
        """
        package org.safere;
        class PlainTest {
          static { if (true) throw new AssertionError("must not initialize tests"); }
        }
        """);
    source(
        sources,
        "TextBlockTest",
        "package org.safere; class TextBlockTest { String text = \"\"\"\n"
            + "@DisabledForCrosscheck(\"fake\") class Fake {}\n\"\"\"; }");
    Path output = select(sources);
    assertThat(Files.readAllLines(output))
        .containsExactly("InternalTest.java", "QualifiedTest.java");
    Files.delete(sources.resolve("InternalTest.java"));
    source(sources, "QualifiedTest", "package org.safere; class QualifiedTest {}");
    assertThat(Files.readString(select(sources))).isEmpty();
  }

  private static void source(Path directory, String name, String text) throws IOException {
    Files.writeString(directory.resolve(name + ".java"), text);
  }

  private Path select(Path sources) throws Exception {
    Path classes = temporary.resolve("classes");
    Files.createDirectories(classes);
    var compiler = ToolProvider.getSystemJavaCompiler();
    try (var manager = compiler.getStandardFileManager(null, null, null)) {
      List<Path> files;
      try (var paths = Files.list(sources)) {
        files = paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
      }
      var options = new ArrayList<>(List.of("-d", classes.toString()));
      options.add("-proc:only");
      var task =
          compiler.getTask(
              null, manager, null, options, null, manager.getJavaFileObjectsFromPaths(files));
      task.setProcessors(List.of(new ExclusionProcessor()));
      assertThat(task.call()).isTrue();
    }
    return classes.resolve("crosscheck-excludes.txt");
  }
}
