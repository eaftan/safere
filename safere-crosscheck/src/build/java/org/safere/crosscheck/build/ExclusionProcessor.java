// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.crosscheck.build;

import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;

/** Collects top-level crosscheck exclusions during an explicit processing pass. */
// Run even when no exclusion annotations remain, so we write an empty manifest.
@SupportedAnnotationTypes("*")
public final class ExclusionProcessor extends AbstractProcessor {
  private final Set<String> excluded = new TreeSet<>();

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
    for (var element : round.getRootElements()) {
      if (!(element instanceof TypeElement type)
          || type.getNestingKind() != NestingKind.TOP_LEVEL) {
        continue;
      }
      for (var annotation : type.getAnnotationMirrors()) {
        var annotationType = (TypeElement) annotation.getAnnotationType().asElement();
        if (annotationType.getQualifiedName().contentEquals("org.safere.DisabledForCrosscheck")) {
          excluded.add(type.getSimpleName() + ".java");
        }
      }
    }
    if (round.processingOver() && !round.errorRaised()) {
      try (var writer =
          processingEnv
              .getFiler()
              .createResource(StandardLocation.CLASS_OUTPUT, "", "crosscheck-excludes.txt")
              .openWriter()) {
        for (String filename : excluded) {
          writer.write(filename + "\n");
        }
      } catch (IOException exception) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, exception.toString());
      }
    }
    return false;
  }
}
