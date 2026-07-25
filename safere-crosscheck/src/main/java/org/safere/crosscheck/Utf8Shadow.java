// Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.crosscheck;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;

/** A UTF-8 matcher shadow for String crosscheck operations with equivalent UTF-8 APIs. */
final class Utf8Shadow {
  private final org.safere.Pattern pattern;
  private final byte[] bytes;
  private final Utf8Coordinates coordinates;
  private org.safere.Utf8Matcher matcher;

  private Utf8Shadow(org.safere.Pattern pattern, byte[] bytes, Utf8Coordinates coordinates) {
    this.pattern = pattern;
    this.bytes = bytes;
    this.coordinates = coordinates;
    matcher = newMatcher();
  }

  static Utf8Shadow create(Pattern pattern, CharSequence input) {
    if (!(input instanceof String text)) {
      return null;
    }
    Utf8Coordinates coordinates = Utf8Coordinates.create(text);
    if (coordinates == null) {
      return null;
    }
    byte[] bytes = text.getBytes(UTF_8);
    return new Utf8Shadow(pattern.saferePattern(), bytes, coordinates);
  }

  boolean find() {
    return matcher.find();
  }

  int groupCount() {
    return matcher.groupCount();
  }

  int start(int group) {
    return toUtf16(matcher.start(group));
  }

  int end(int group) {
    return toUtf16(matcher.end(group));
  }

  boolean canRepresent(org.safere.Matcher stringMatcher) {
    for (int group = 0; group <= stringMatcher.groupCount(); group++) {
      int start = stringMatcher.start(group);
      int end = stringMatcher.end(group);
      if (start >= 0
          && (!coordinates.isUtf16Boundary(start) || !coordinates.isUtf16Boundary(end))) {
        return false;
      }
    }
    return true;
  }

  String group(int group) {
    int start = matcher.start(group);
    if (start < 0) {
      return null;
    }
    int end = matcher.end(group);
    coordinates.toUtf16(start);
    coordinates.toUtf16(end);
    return new String(bytes, start, end - start, UTF_8);
  }

  boolean canEncode(String text) {
    return Utf8Coordinates.create(text) != null;
  }

  String appendReplacement(String replacement) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    matcher.appendReplacement(
        output::write, org.safere.Utf8Input.validated(replacement.getBytes(UTF_8)));
    return output.toString(UTF_8);
  }

  String appendTail() {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    matcher.appendTail(output::write);
    return output.toString(UTF_8);
  }

  String replaceFirst(String replacement) {
    org.safere.Utf8Matcher replacementMatcher = newMatcher();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    if (replacementMatcher.find()) {
      replacementMatcher.appendReplacement(
          output::write, org.safere.Utf8Input.validated(replacement.getBytes(UTF_8)));
    }
    replacementMatcher.appendTail(output::write);
    return output.toString(UTF_8);
  }

  String replaceAll(String replacement) {
    org.safere.Utf8Matcher replacementMatcher = newMatcher();
    org.safere.Utf8Input utf8Replacement =
        org.safere.Utf8Input.validated(replacement.getBytes(UTF_8));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    while (replacementMatcher.find()) {
      replacementMatcher.appendReplacement(output::write, utf8Replacement);
    }
    replacementMatcher.appendTail(output::write);
    return output.toString(UTF_8);
  }

  private org.safere.Utf8Matcher newMatcher() {
    return pattern.matcher(org.safere.Utf8Input.validated(bytes));
  }

  private int toUtf16(int utf8Offset) {
    return utf8Offset < 0 ? -1 : coordinates.toUtf16(utf8Offset);
  }
}
