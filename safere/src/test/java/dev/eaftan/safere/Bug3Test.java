// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Bug 3: ^.*$ with MULTILINE on trailing newline")
class Bug3Test {

  @Test
  @DisplayName("^.*$ with MULTILINE on '\\na\\n' should match [0,0), [1,2) but not [3,3)")
  void testMultilineTrailingNewline() {
    String text = "\na\n"; // Position 0: \n, 1: a, 2: \n (length 3)
    String pattern = "^.*$";
    int flags = Pattern.MULTILINE;
    
    System.out.println("Testing: pattern='" + pattern + "' on text='\\na\\n' with MULTILINE");
    System.out.println("Expected SafeRE matches: [0,0), [1,2)");
    System.out.println("Expected JDK matches:    [0,0), [1,2)");
    
    // SafeRE
    Pattern safeP = Pattern.compile(pattern, flags);
    dev.eaftan.safere.Matcher safeMatcher = safeP.matcher(text);
    
    List<String> safeMatches = new ArrayList<>();
    while (safeMatcher.find()) {
      int start = safeMatcher.start();
      int end = safeMatcher.end();
      safeMatches.add("[" + start + "," + end + ")");
    }
    
    // JDK
    java.util.regex.Pattern jdkP = java.util.regex.Pattern.compile(pattern, flags);
    java.util.regex.Matcher jdkMatcher = jdkP.matcher(text);
    
    List<String> jdkMatches = new ArrayList<>();
    while (jdkMatcher.find()) {
      int start = jdkMatcher.start();
      int end = jdkMatcher.end();
      jdkMatches.add("[" + start + "," + end + ")");
    }
    
    System.out.println("SafeRE matches: " + safeMatches);
    System.out.println("JDK matches: " + jdkMatches);
    
    assertThat(safeMatches).as("SafeRE should match JDK behavior")
        .isEqualTo(jdkMatches)
        .isEqualTo(List.of("[0,0)", "[1,2)"));
  }
}
