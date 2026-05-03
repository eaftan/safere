// Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.crosscheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.MatchResult;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

/** Policy checks for generated public API crosscheck coverage. */
class CrosscheckGenerationPolicyTest {
  private static final Path SAFERE_TEST_DIR =
      Path.of("..", "safere", "src", "test", "java", "org", "safere");
  private static final Path POM = Path.of("pom.xml");
  private static final java.util.regex.Pattern DISABLED_ANNOTATION =
      java.util.regex.Pattern.compile(
          "@DisabledForCrosscheck\\s*\\(((?:\\s*\"(?:\\\\.|[^\"\\\\])*\"\\s*\\+?)+)\\)",
          java.util.regex.Pattern.DOTALL);
  private static final java.util.regex.Pattern STRING_LITERAL =
      java.util.regex.Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");

  @Test
  @DisplayName("@DisabledForCrosscheck reasons are non-empty")
  void disabledForCrosscheckReasonsAreNonEmpty() throws IOException {
    Set<String> violations = new TreeSet<>();
    for (Path file : testSources()) {
      String source = Files.readString(file);
      java.util.regex.Matcher matcher = DISABLED_ANNOTATION.matcher(source);
      while (matcher.find()) {
        String reason = annotationReason(matcher.group(1));
        if (reason.isBlank()) {
          violations.add(file.getFileName() + " at offset " + matcher.start());
        }
      }
    }

    assertThat(violations).isEmpty();
  }

  @Test
  @DisplayName("structural crosscheck excludes carry class-level annotations")
  void structuralExcludesCarryClassLevelAnnotations() throws IOException {
    Set<String> violations = new TreeSet<>();
    for (String excluded : structuralExcludes()) {
      Path source = SAFERE_TEST_DIR.resolve(excluded);
      assertThat(Files.isRegularFile(source))
          .as("source file for structural exclude %s", excluded)
          .isTrue();
      if (!hasClassLevelDisabledAnnotation(Files.readString(source))) {
        violations.add(excluded);
      }
    }

    assertThat(violations).isEmpty();
  }

  private static List<Path> testSources() throws IOException {
    try (var files = Files.list(SAFERE_TEST_DIR)) {
      return files
          .filter(path -> path.getFileName().toString().endsWith("Test.java"))
          .sorted()
          .toList();
    }
  }

  private static Set<String> structuralExcludes() {
    Set<String> excludes = new HashSet<>();
    Element pom = pomDocument();
    var nodes = pom.getElementsByTagName("exclude");
    for (int i = 0; i < nodes.getLength(); i++) {
      Element exclude = (Element) nodes.item(i);
      String name = exclude.getAttribute("name");
      if (name.endsWith("Test.java")) {
        excludes.add(name);
      }
    }
    return excludes;
  }

  private static Element pomDocument() {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setExpandEntityReferences(false);
      return factory.newDocumentBuilder().parse(POM.toFile()).getDocumentElement();
    } catch (Exception e) {
      throw new AssertionError("Unable to parse " + POM, e);
    }
  }

  private static String annotationReason(String annotationArgument) {
    java.util.regex.Matcher literals = STRING_LITERAL.matcher(annotationArgument);
    return literals.results()
        .map(MatchResult::group)
        .map(literal -> literal.substring(1, literal.length() - 1))
        .reduce("", String::concat);
  }

  private static boolean hasClassLevelDisabledAnnotation(String source) {
    int classDeclaration = source.indexOf("class ");
    if (classDeclaration < 0) {
      return false;
    }
    int annotation = source.indexOf("@DisabledForCrosscheck");
    return annotation >= 0 && annotation < classDeclaration;
  }
}
