// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * Unicode character data tables for the SafeRE regular expression library.
 *
 * <p>This class contains Perl character classes, POSIX character classes, case folding data, and
 * provides access to Unicode script and category groups. Category and script tables are generated at
 * runtime from the JDK's {@link Character} implementation (see {@link UnicodeGroups}), so they
 * always match the JVM's Unicode version. Case folding data and ASCII Perl/POSIX classes are static
 * tables ported from RE2's {@code unicode_casefold.cc} and {@code perl_groups.cc}.
 *
 * <p>Each character class is represented as an {@code int[][]} where each element is a {@code {lo,
 * hi}} pair representing an inclusive range of Unicode code points.
 */
// This class is package-private and all arrays are generated Unicode data tables used for
// performance-critical character class lookups. They are not exposed to external callers.
@SuppressWarnings("MutablePublicArray")
final class UnicodeTables {
  private UnicodeTables() {}

  // Perl character classes

  public static final int[][] PERL_DIGIT = {{0x30, 0x39}};

  public static final int[][] PERL_SPACE = {
    {0x09, 0x0A}, {0x0C, 0x0D}, {0x20, 0x20},
  };

  public static final int[][] PERL_WORD = {
    {0x30, 0x39}, {0x41, 0x5A}, {0x5F, 0x5F}, {0x61, 0x7A},
  };

  // JDK horizontal whitespace (\h): [ \t\xA0\u1680\u180E\u2000-\u200A\u202F\u205F\u3000]
  public static final int[][] HORIZ_SPACE = {
    {0x09, 0x09}, {0x20, 0x20}, {0xA0, 0xA0}, {0x1680, 0x1680}, {0x180E, 0x180E},
    {0x2000, 0x200A}, {0x202F, 0x202F}, {0x205F, 0x205F}, {0x3000, 0x3000},
  };

  // JDK vertical whitespace (\v): [\n\x0B\f\r\x85\u2028\u2029]
  public static final int[][] VERT_SPACE = {
    {0x0A, 0x0D}, {0x85, 0x85}, {0x2028, 0x2029},
  };

  public static final Map<String, int[][]> PERL_GROUPS =
      Map.of(
          "\\d", PERL_DIGIT,
          "\\s", PERL_SPACE,
          "\\w", PERL_WORD,
          "\\h", HORIZ_SPACE,
          "\\v", VERT_SPACE);

  // POSIX character classes

  public static final int[][] POSIX_ALNUM = {{0x30, 0x39}, {0x41, 0x5A}, {0x61, 0x7A}};
  public static final int[][] POSIX_ALPHA = {{0x41, 0x5A}, {0x61, 0x7A}};
  public static final int[][] POSIX_ASCII = {{0x00, 0x7F}};
  public static final int[][] POSIX_BLANK = {{0x09, 0x09}, {0x20, 0x20}};
  public static final int[][] POSIX_CNTRL = {{0x00, 0x1F}, {0x7F, 0x7F}};
  public static final int[][] POSIX_DIGIT = {{0x30, 0x39}};
  public static final int[][] POSIX_GRAPH = {{0x21, 0x7E}};
  public static final int[][] POSIX_LOWER = {{0x61, 0x7A}};
  public static final int[][] POSIX_PRINT = {{0x20, 0x7E}};
  public static final int[][] POSIX_PUNCT = {{0x21, 0x2F}, {0x3A, 0x40}, {0x5B, 0x60}, {0x7B, 0x7E}};
  public static final int[][] POSIX_SPACE = {{0x09, 0x0D}, {0x20, 0x20}};
  public static final int[][] POSIX_UPPER = {{0x41, 0x5A}};
  public static final int[][] POSIX_WORD = {{0x30, 0x39}, {0x41, 0x5A}, {0x5F, 0x5F}, {0x61, 0x7A}};
  public static final int[][] POSIX_XDIGIT = {{0x30, 0x39}, {0x41, 0x46}, {0x61, 0x66}};

  public static final Map<String, int[][]> POSIX_GROUPS = Map.ofEntries(
      Map.entry("[:alnum:]", POSIX_ALNUM),
      Map.entry("[:alpha:]", POSIX_ALPHA),
      Map.entry("[:ascii:]", POSIX_ASCII),
      Map.entry("[:blank:]", POSIX_BLANK),
      Map.entry("[:cntrl:]", POSIX_CNTRL),
      Map.entry("[:digit:]", POSIX_DIGIT),
      Map.entry("[:graph:]", POSIX_GRAPH),
      Map.entry("[:lower:]", POSIX_LOWER),
      Map.entry("[:print:]", POSIX_PRINT),
      Map.entry("[:punct:]", POSIX_PUNCT),
      Map.entry("[:space:]", POSIX_SPACE),
      Map.entry("[:upper:]", POSIX_UPPER),
      Map.entry("[:word:]", POSIX_WORD),
      Map.entry("[:xdigit:]", POSIX_XDIGIT));

  /**
   * POSIX character class names for use with the {@code \p{...}} property syntax (e.g., {@code
   * \p{Lower}}). These are the 13 POSIX classes defined in the JDK's {@code java.util.regex.Pattern}
   * documentation. In the default (non-UNICODE_CHARACTER_CLASS) mode, they match ASCII-only ranges.
   */
  public static final Map<String, int[][]> POSIX_PROPERTY_GROUPS = Map.ofEntries(
      Map.entry("Lower", POSIX_LOWER),
      Map.entry("Upper", POSIX_UPPER),
      Map.entry("ASCII", POSIX_ASCII),
      Map.entry("Alpha", POSIX_ALPHA),
      Map.entry("Digit", POSIX_DIGIT),
      Map.entry("Alnum", POSIX_ALNUM),
      Map.entry("Punct", POSIX_PUNCT),
      Map.entry("Graph", POSIX_GRAPH),
      Map.entry("Print", POSIX_PRINT),
      Map.entry("Blank", POSIX_BLANK),
      Map.entry("Cntrl", POSIX_CNTRL),
      Map.entry("XDigit", POSIX_XDIGIT),
      Map.entry("Space", POSIX_SPACE));

  private static final class UnicodePosixHolder {
    static final int[][] UNICODE_ALPHA = UnicodeProperties.lookupBinaryProperty("Alphabetic");
    static final int[][] UNICODE_DIGIT = UnicodeProperties.lookupBinaryProperty("Digit");
    static final int[][] UNICODE_ALNUM = mergeRangeTables(UNICODE_ALPHA, UNICODE_DIGIT);
    static final int[][] UNICODE_PUNCT = UnicodeProperties.lookupBinaryProperty("Punctuation");
    static final int[][] UNICODE_CNTRL = UnicodeProperties.lookupBinaryProperty("Control");
    static final int[][] UNICODE_XDIGIT = UnicodeProperties.lookupBinaryProperty("Hex_Digit");
    static final int[][] UNICODE_GRAPH =
        buildRanges(
            cp ->
                Character.isDefined(cp)
                    && Character.getType(cp) != Character.CONTROL
                    && Character.getType(cp) != Character.SURROGATE
                    && !isUnicodeWhiteSpace(cp));
    static final int[][] UNICODE_PRINT = mergeRangeTables(UNICODE_GRAPH, HORIZ_SPACE);

    static final Map<String, int[][]> UNICODE_POSIX_PROPERTY_GROUPS =
        Map.ofEntries(
            Map.entry("Lower", UnicodeProperties.lookupBinaryProperty("Lowercase")),
            Map.entry("Upper", UnicodeProperties.lookupBinaryProperty("Uppercase")),
            Map.entry("ASCII", POSIX_ASCII),
            Map.entry("Alpha", UNICODE_ALPHA),
            Map.entry("Digit", UNICODE_DIGIT),
            Map.entry("Alnum", UNICODE_ALNUM),
            Map.entry("Punct", UNICODE_PUNCT),
            Map.entry("Graph", UNICODE_GRAPH),
            Map.entry("Print", UNICODE_PRINT),
            Map.entry("Blank", HORIZ_SPACE),
            Map.entry("Cntrl", UNICODE_CNTRL),
            Map.entry("XDigit", UNICODE_XDIGIT),
            Map.entry("Space", unicodeSpace()));

    static final Map<String, int[][]> UNICODE_POSIX_GROUPS =
        Map.ofEntries(
            Map.entry("[:alnum:]", UNICODE_ALNUM),
            Map.entry("[:alpha:]", UNICODE_ALPHA),
            Map.entry("[:ascii:]", POSIX_ASCII),
            Map.entry("[:blank:]", HORIZ_SPACE),
            Map.entry("[:cntrl:]", UNICODE_CNTRL),
            Map.entry("[:digit:]", UNICODE_DIGIT),
            Map.entry("[:graph:]", UNICODE_GRAPH),
            Map.entry("[:lower:]", UnicodeProperties.lookupBinaryProperty("Lowercase")),
            Map.entry("[:print:]", UNICODE_PRINT),
            Map.entry("[:punct:]", UNICODE_PUNCT),
            Map.entry("[:space:]", unicodeSpace()),
            Map.entry("[:upper:]", UnicodeProperties.lookupBinaryProperty("Uppercase")),
            Map.entry("[:word:]", unicodeWord()),
            Map.entry("[:xdigit:]", UNICODE_XDIGIT));
  }

  static Map<String, int[][]> unicodePosixPropertyGroups() {
    return UnicodePosixHolder.UNICODE_POSIX_PROPERTY_GROUPS;
  }

  static Map<String, int[][]> unicodePosixGroups() {
    return UnicodePosixHolder.UNICODE_POSIX_GROUPS;
  }

  private static boolean isUnicodeWhiteSpace(int cp) {
    return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
  }

  private static int[][] buildRanges(IntPredicate predicate) {
    List<int[]> ranges = new ArrayList<>();
    int lo = -1;
    for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
      if (predicate.test(cp)) {
        if (lo < 0) {
          lo = cp;
        }
      } else if (lo >= 0) {
        ranges.add(new int[] {lo, cp - 1});
        lo = -1;
      }
    }
    if (lo >= 0) {
      ranges.add(new int[] {lo, Character.MAX_CODE_POINT});
    }
    return ranges.toArray(new int[0][]);
  }

  // Case folding sentinel values
  public static final int EVEN_ODD = 1;
  public static final int ODD_EVEN = -1;
  public static final int EVEN_ODD_SKIP = 1 << 30;
  public static final int ODD_EVEN_SKIP = (1 << 30) + 1;

  // CASE_FOLD and TO_LOWER are static tables updated to Unicode 17.0 (CaseFolding.txt).
  //
  // Unlike UNICODE_GROUPS (which are generated at runtime from JDK APIs), these tables are safe
  // to keep ahead of the running JDK's Unicode version. If a fold entry references a code point
  // that the JDK doesn't yet recognize, the fold arithmetic still works (it's just integer
  // addition), but the code point will be classified as UNASSIGNED and will never appear as a
  // matched letter, so the entry is harmless dead code. Unicode's stability policy guarantees
  // that once a code point is assigned, its identity never changes, so a newer table can never
  // produce *wrong* results on an older JDK — only unused entries.
  //
  // A canary test (UnicodeJdkConsistencyTest.caseFoldCoversAllJdkPairs) detects when a newer
  // JDK adds case pairs that are not yet in this table.
  public static final int[][] CASE_FOLD = initCaseFold();

  private static int[][] initCaseFold() {
    return new int[][] {
      {65, 90, 32},
      {97, 106, -32},
      {107, 107, 8383},
      {108, 114, -32},
      {115, 115, 268},
      {116, 122, -32},
      {181, 181, 743},
      {192, 214, 32},
      {216, 222, 32},
      {223, 223, 7615},
      {224, 228, -32},
      {229, 229, 8262},
      {230, 246, -32},
      {248, 254, -32},
      {255, 255, 121},
      {256, 303, EVEN_ODD},
      {306, 311, EVEN_ODD},
      {313, 328, ODD_EVEN},
      {330, 375, EVEN_ODD},
      {376, 376, -121},
      {377, 382, ODD_EVEN},
      {383, 383, -300},
      {384, 384, 195},
      {385, 385, 210},
      {386, 389, EVEN_ODD},
      {390, 390, 206},
      {391, 392, ODD_EVEN},
      {393, 394, 205},
      {395, 396, ODD_EVEN},
      {398, 398, 79},
      {399, 399, 202},
      {400, 400, 203},
      {401, 402, ODD_EVEN},
      {403, 403, 205},
      {404, 404, 207},
      {405, 405, 97},
      {406, 406, 211},
      {407, 407, 209},
      {408, 409, EVEN_ODD},
      {410, 410, 163},
      {411, 411, 42561},
      {412, 412, 211},
      {413, 413, 213},
      {414, 414, 130},
      {415, 415, 214},
      {416, 421, EVEN_ODD},
      {422, 422, 218},
      {423, 424, ODD_EVEN},
      {425, 425, 218},
      {428, 429, EVEN_ODD},
      {430, 430, 218},
      {431, 432, ODD_EVEN},
      {433, 434, 217},
      {435, 438, ODD_EVEN},
      {439, 439, 219},
      {440, 441, EVEN_ODD},
      {444, 445, EVEN_ODD},
      {447, 447, 56},
      {452, 452, EVEN_ODD},
      {453, 453, ODD_EVEN},
      {454, 454, -2},
      {455, 455, ODD_EVEN},
      {456, 456, EVEN_ODD},
      {457, 457, -2},
      {458, 458, EVEN_ODD},
      {459, 459, ODD_EVEN},
      {460, 460, -2},
      {461, 476, ODD_EVEN},
      {477, 477, -79},
      {478, 495, EVEN_ODD},
      {497, 497, ODD_EVEN},
      {498, 498, EVEN_ODD},
      {499, 499, -2},
      {500, 501, EVEN_ODD},
      {502, 502, -97},
      {503, 503, -56},
      {504, 543, EVEN_ODD},
      {544, 544, -130},
      {546, 563, EVEN_ODD},
      {570, 570, 10795},
      {571, 572, ODD_EVEN},
      {573, 573, -163},
      {574, 574, 10792},
      {575, 576, 10815},
      {577, 578, ODD_EVEN},
      {579, 579, -195},
      {580, 580, 69},
      {581, 581, 71},
      {582, 591, EVEN_ODD},
      {592, 592, 10783},
      {593, 593, 10780},
      {594, 594, 10782},
      {595, 595, -210},
      {596, 596, -206},
      {598, 599, -205},
      {601, 601, -202},
      {603, 603, -203},
      {604, 604, 42319},
      {608, 608, -205},
      {609, 609, 42315},
      {611, 611, -207},
      {612, 612, 42343},
      {613, 613, 42280},
      {614, 614, 42308},
      {616, 616, -209},
      {617, 617, -211},
      {618, 618, 42308},
      {619, 619, 10743},
      {620, 620, 42305},
      {623, 623, -211},
      {625, 625, 10749},
      {626, 626, -213},
      {629, 629, -214},
      {637, 637, 10727},
      {640, 640, -218},
      {642, 642, 42307},
      {643, 643, -218},
      {647, 647, 42282},
      {648, 648, -218},
      {649, 649, -69},
      {650, 651, -217},
      {652, 652, -71},
      {658, 658, -219},
      {669, 669, 42261},
      {670, 670, 42258},
      {837, 837, 84},
      {880, 883, EVEN_ODD},
      {886, 887, EVEN_ODD},
      {891, 893, 130},
      {895, 895, 116},
      {902, 902, 38},
      {904, 906, 37},
      {908, 908, 64},
      {910, 911, 63},
      {912, 912, 7235},
      {913, 929, 32},
      {931, 931, 31},
      {932, 939, 32},
      {940, 940, -38},
      {941, 943, -37},
      {944, 944, 7219},
      {945, 945, -32},
      {946, 946, 30},
      {947, 948, -32},
      {949, 949, 64},
      {950, 951, -32},
      {952, 952, 25},
      {953, 953, 7173},
      {954, 954, 54},
      {955, 955, -32},
      {956, 956, -775},
      {957, 959, -32},
      {960, 960, 22},
      {961, 961, 48},
      {962, 962, EVEN_ODD},
      {963, 965, -32},
      {966, 966, 15},
      {967, 968, -32},
      {969, 969, 7517},
      {970, 971, -32},
      {972, 972, -64},
      {973, 974, -63},
      {975, 975, 8},
      {976, 976, -62},
      {977, 977, 35},
      {981, 981, -47},
      {982, 982, -54},
      {983, 983, -8},
      {984, 1007, EVEN_ODD},
      {1008, 1008, -86},
      {1009, 1009, -80},
      {1010, 1010, 7},
      {1011, 1011, -116},
      {1012, 1012, -92},
      {1013, 1013, -96},
      {1015, 1016, ODD_EVEN},
      {1017, 1017, -7},
      {1018, 1019, EVEN_ODD},
      {1021, 1023, -130},
      {1024, 1039, 80},
      {1040, 1071, 32},
      {1072, 1073, -32},
      {1074, 1074, 6222},
      {1075, 1075, -32},
      {1076, 1076, 6221},
      {1077, 1085, -32},
      {1086, 1086, 6212},
      {1087, 1088, -32},
      {1089, 1090, 6210},
      {1091, 1097, -32},
      {1098, 1098, 6204},
      {1099, 1103, -32},
      {1104, 1119, -80},
      {1120, 1122, EVEN_ODD},
      {1123, 1123, 6180},
      {1124, 1153, EVEN_ODD},
      {1162, 1215, EVEN_ODD},
      {1216, 1216, 15},
      {1217, 1230, ODD_EVEN},
      {1231, 1231, -15},
      {1232, 1327, EVEN_ODD},
      {1329, 1366, 48},
      {1377, 1414, -48},
      {4256, 4293, 7264},
      {4295, 4295, 7264},
      {4301, 4301, 7264},
      {4304, 4346, 3008},
      {4349, 4351, 3008},
      {5024, 5103, 38864},
      {5104, 5109, 8},
      {5112, 5117, -8},
      {7296, 7296, -6254},
      {7297, 7297, -6253},
      {7298, 7298, -6244},
      {7299, 7299, -6242},
      {7300, 7300, EVEN_ODD},
      {7301, 7301, -6243},
      {7302, 7302, -6236},
      {7303, 7303, -6181},
      {7304, 7304, 35266},
      {7305, 7306, ODD_EVEN},
      {7312, 7354, -3008},
      {7357, 7359, -3008},
      {7545, 7545, 35332},
      {7549, 7549, 3814},
      {7566, 7566, 35384},
      {7680, 7776, EVEN_ODD},
      {7777, 7777, 58},
      {7778, 7829, EVEN_ODD},
      {7835, 7835, -59},
      {7838, 7838, -7615},
      {7840, 7935, EVEN_ODD},
      {7936, 7943, 8},
      {7944, 7951, -8},
      {7952, 7957, 8},
      {7960, 7965, -8},
      {7968, 7975, 8},
      {7976, 7983, -8},
      {7984, 7991, 8},
      {7992, 7999, -8},
      {8000, 8005, 8},
      {8008, 8013, -8},
      {8017, 8017, 8},
      {8019, 8019, 8},
      {8021, 8021, 8},
      {8023, 8023, 8},
      {8025, 8025, -8},
      {8027, 8027, -8},
      {8029, 8029, -8},
      {8031, 8031, -8},
      {8032, 8039, 8},
      {8040, 8047, -8},
      {8048, 8049, 74},
      {8050, 8053, 86},
      {8054, 8055, 100},
      {8056, 8057, 128},
      {8058, 8059, 112},
      {8060, 8061, 126},
      {8064, 8071, 8},
      {8072, 8079, -8},
      {8080, 8087, 8},
      {8088, 8095, -8},
      {8096, 8103, 8},
      {8104, 8111, -8},
      {8112, 8113, 8},
      {8115, 8115, 9},
      {8120, 8121, -8},
      {8122, 8123, -74},
      {8124, 8124, -9},
      {8126, 8126, -7289},
      {8131, 8131, 9},
      {8136, 8139, -86},
      {8140, 8140, -9},
      {8144, 8145, 8},
      {8147, 8147, -7235},
      {8152, 8153, -8},
      {8154, 8155, -100},
      {8160, 8161, 8},
      {8163, 8163, -7219},
      {8165, 8165, 7},
      {8168, 8169, -8},
      {8170, 8171, -112},
      {8172, 8172, -7},
      {8179, 8179, 9},
      {8184, 8185, -128},
      {8186, 8187, -126},
      {8188, 8188, -9},
      {8486, 8486, -7549},
      {8490, 8490, -8415},
      {8491, 8491, -8294},
      {8498, 8498, 28},
      {8526, 8526, -28},
      {8544, 8559, 16},
      {8560, 8575, -16},
      {8579, 8580, ODD_EVEN},
      {9398, 9423, 26},
      {9424, 9449, -26},
      {11264, 11311, 48},
      {11312, 11359, -48},
      {11360, 11361, EVEN_ODD},
      {11362, 11362, -10743},
      {11363, 11363, -3814},
      {11364, 11364, -10727},
      {11365, 11365, -10795},
      {11366, 11366, -10792},
      {11367, 11372, ODD_EVEN},
      {11373, 11373, -10780},
      {11374, 11374, -10749},
      {11375, 11375, -10783},
      {11376, 11376, -10782},
      {11378, 11379, EVEN_ODD},
      {11381, 11382, ODD_EVEN},
      {11390, 11391, -10815},
      {11392, 11491, EVEN_ODD},
      {11499, 11502, ODD_EVEN},
      {11506, 11507, EVEN_ODD},
      {11520, 11557, -7264},
      {11559, 11559, -7264},
      {11565, 11565, -7264},
      {42560, 42570, EVEN_ODD},
      {42571, 42571, -35267},
      {42572, 42605, EVEN_ODD},
      {42624, 42651, EVEN_ODD},
      {42786, 42799, EVEN_ODD},
      {42802, 42863, EVEN_ODD},
      {42873, 42876, ODD_EVEN},
      {42877, 42877, -35332},
      {42878, 42887, EVEN_ODD},
      {42891, 42892, ODD_EVEN},
      {42893, 42893, -42280},
      {42896, 42899, EVEN_ODD},
      {42900, 42900, 48},
      {42902, 42921, EVEN_ODD},
      {42922, 42922, -42308},
      {42923, 42923, -42319},
      {42924, 42924, -42315},
      {42925, 42925, -42305},
      {42926, 42926, -42308},
      {42928, 42928, -42258},
      {42929, 42929, -42282},
      {42930, 42930, -42261},
      {42931, 42931, 928},
      {42932, 42947, EVEN_ODD},
      {42948, 42948, -48},
      {42949, 42949, -42307},
      {42950, 42950, -35384},
      {42951, 42954, ODD_EVEN},
      {42955, 42955, -42343},
      {42956, 42971, EVEN_ODD},
      {42972, 42972, -42561},
      {42997, 42998, ODD_EVEN},
      {43859, 43859, -928},
      {43888, 43967, -38864},
      {64261, 64262, ODD_EVEN},
      {65313, 65338, 32},
      {65345, 65370, -32},
      {66560, 66599, 40},
      {66600, 66639, -40},
      {66736, 66771, 40},
      {66776, 66811, -40},
      {66928, 66938, 39},
      {66940, 66954, 39},
      {66956, 66962, 39},
      {66964, 66965, 39},
      {66967, 66977, -39},
      {66979, 66993, -39},
      {66995, 67001, -39},
      {67003, 67004, -39},
      {68736, 68786, 64},
      {68800, 68850, -64},
      {68944, 68965, 32},
      {68976, 68997, -32},
      {71840, 71871, 32},
      {71872, 71903, -32},
      {93760, 93791, 32},
      {93792, 93823, -32},
      {93856, 93880, 27},
      {93883, 93907, -27},
      {125184, 125217, 34},
      {125218, 125251, -34},
    };
  }

  public static final int[][] TO_LOWER = initToLower();

  private static int[][] initToLower() {
    return new int[][] {
      {65, 90, 32},
      {181, 181, 775},
      {192, 214, 32},
      {216, 222, 32},
      {256, 302, EVEN_ODD_SKIP},
      {306, 310, EVEN_ODD_SKIP},
      {313, 327, ODD_EVEN_SKIP},
      {330, 374, EVEN_ODD_SKIP},
      {376, 376, -121},
      {377, 381, ODD_EVEN_SKIP},
      {383, 383, -268},
      {385, 385, 210},
      {386, 388, EVEN_ODD_SKIP},
      {390, 390, 206},
      {391, 391, ODD_EVEN},
      {393, 394, 205},
      {395, 395, ODD_EVEN},
      {398, 398, 79},
      {399, 399, 202},
      {400, 400, 203},
      {401, 401, ODD_EVEN},
      {403, 403, 205},
      {404, 404, 207},
      {406, 406, 211},
      {407, 407, 209},
      {408, 408, EVEN_ODD},
      {412, 412, 211},
      {413, 413, 213},
      {415, 415, 214},
      {416, 420, EVEN_ODD_SKIP},
      {422, 422, 218},
      {423, 423, ODD_EVEN},
      {425, 425, 218},
      {428, 428, EVEN_ODD},
      {430, 430, 218},
      {431, 431, ODD_EVEN},
      {433, 434, 217},
      {435, 437, ODD_EVEN_SKIP},
      {439, 439, 219},
      {440, 440, EVEN_ODD},
      {444, 444, EVEN_ODD},
      {452, 452, 2},
      {453, 453, ODD_EVEN},
      {455, 455, 2},
      {456, 456, EVEN_ODD},
      {458, 458, 2},
      {459, 475, ODD_EVEN_SKIP},
      {478, 494, EVEN_ODD_SKIP},
      {497, 497, 2},
      {498, 500, EVEN_ODD_SKIP},
      {502, 502, -97},
      {503, 503, -56},
      {504, 542, EVEN_ODD_SKIP},
      {544, 544, -130},
      {546, 562, EVEN_ODD_SKIP},
      {570, 570, 10795},
      {571, 571, ODD_EVEN},
      {573, 573, -163},
      {574, 574, 10792},
      {577, 577, ODD_EVEN},
      {579, 579, -195},
      {580, 580, 69},
      {581, 581, 71},
      {582, 590, EVEN_ODD_SKIP},
      {837, 837, 116},
      {880, 882, EVEN_ODD_SKIP},
      {886, 886, EVEN_ODD},
      {895, 895, 116},
      {902, 902, 38},
      {904, 906, 37},
      {908, 908, 64},
      {910, 911, 63},
      {913, 929, 32},
      {931, 939, 32},
      {962, 962, EVEN_ODD},
      {975, 975, 8},
      {976, 976, -30},
      {977, 977, -25},
      {981, 981, -15},
      {982, 982, -22},
      {984, 1006, EVEN_ODD_SKIP},
      {1008, 1008, -54},
      {1009, 1009, -48},
      {1012, 1012, -60},
      {1013, 1013, -64},
      {1015, 1015, ODD_EVEN},
      {1017, 1017, -7},
      {1018, 1018, EVEN_ODD},
      {1021, 1023, -130},
      {1024, 1039, 80},
      {1040, 1071, 32},
      {1120, 1152, EVEN_ODD_SKIP},
      {1162, 1214, EVEN_ODD_SKIP},
      {1216, 1216, 15},
      {1217, 1229, ODD_EVEN_SKIP},
      {1232, 1326, EVEN_ODD_SKIP},
      {1329, 1366, 48},
      {4256, 4293, 7264},
      {4295, 4295, 7264},
      {4301, 4301, 7264},
      {5112, 5117, -8},
      {7296, 7296, -6222},
      {7297, 7297, -6221},
      {7298, 7298, -6212},
      {7299, 7300, -6210},
      {7301, 7301, -6211},
      {7302, 7302, -6204},
      {7303, 7303, -6180},
      {7304, 7304, 35267},
      {7305, 7305, 1},
      {7312, 7354, -3008},
      {7357, 7359, -3008},
      {7680, 7828, EVEN_ODD_SKIP},
      {7835, 7835, -58},
      {7838, 7838, -7615},
      {7840, 7934, EVEN_ODD_SKIP},
      {7944, 7951, -8},
      {7960, 7965, -8},
      {7976, 7983, -8},
      {7992, 7999, -8},
      {8008, 8013, -8},
      {8025, 8025, -8},
      {8027, 8027, -8},
      {8029, 8029, -8},
      {8031, 8031, -8},
      {8040, 8047, -8},
      {8072, 8079, -8},
      {8088, 8095, -8},
      {8104, 8111, -8},
      {8120, 8121, -8},
      {8122, 8123, -74},
      {8124, 8124, -9},
      {8126, 8126, -7173},
      {8136, 8139, -86},
      {8140, 8140, -9},
      {8147, 8147, -7235},
      {8152, 8153, -8},
      {8154, 8155, -100},
      {8163, 8163, -7219},
      {8168, 8169, -8},
      {8170, 8171, -112},
      {8172, 8172, -7},
      {8184, 8185, -128},
      {8186, 8187, -126},
      {8188, 8188, -9},
      {8486, 8486, -7517},
      {8490, 8490, -8383},
      {8491, 8491, -8262},
      {8498, 8498, 28},
      {8544, 8559, 16},
      {8579, 8579, ODD_EVEN},
      {9398, 9423, 26},
      {11264, 11311, 48},
      {11360, 11360, EVEN_ODD},
      {11362, 11362, -10743},
      {11363, 11363, -3814},
      {11364, 11364, -10727},
      {11367, 11371, ODD_EVEN_SKIP},
      {11373, 11373, -10780},
      {11374, 11374, -10749},
      {11375, 11375, -10783},
      {11376, 11376, -10782},
      {11378, 11378, EVEN_ODD},
      {11381, 11381, ODD_EVEN},
      {11390, 11391, -10815},
      {11392, 11490, EVEN_ODD_SKIP},
      {11499, 11501, ODD_EVEN_SKIP},
      {11506, 11506, EVEN_ODD},
      {42560, 42604, EVEN_ODD_SKIP},
      {42624, 42650, EVEN_ODD_SKIP},
      {42786, 42798, EVEN_ODD_SKIP},
      {42802, 42862, EVEN_ODD_SKIP},
      {42873, 42875, ODD_EVEN_SKIP},
      {42877, 42877, -35332},
      {42878, 42886, EVEN_ODD_SKIP},
      {42891, 42891, ODD_EVEN},
      {42893, 42893, -42280},
      {42896, 42898, EVEN_ODD_SKIP},
      {42902, 42920, EVEN_ODD_SKIP},
      {42922, 42922, -42308},
      {42923, 42923, -42319},
      {42924, 42924, -42315},
      {42925, 42925, -42305},
      {42926, 42926, -42308},
      {42928, 42928, -42258},
      {42929, 42929, -42282},
      {42930, 42930, -42261},
      {42931, 42931, 928},
      {42932, 42946, EVEN_ODD_SKIP},
      {42948, 42948, -48},
      {42949, 42949, -42307},
      {42950, 42950, -35384},
      {42951, 42953, ODD_EVEN_SKIP},
      {42955, 42955, -42343},
      {42956, 42970, EVEN_ODD_SKIP},
      {42972, 42972, -42561},
      {42997, 42997, ODD_EVEN},
      {43888, 43967, -38864},
      {64261, 64261, ODD_EVEN},
      {65313, 65338, 32},
      {66560, 66599, 40},
      {66736, 66771, 40},
      {66928, 66938, 39},
      {66940, 66954, 39},
      {66956, 66962, 39},
      {66964, 66965, 39},
      {68736, 68786, 64},
      {68944, 68965, 32},
      {71840, 71871, 32},
      {93760, 93791, 32},
      {93856, 93880, 27},
      {125184, 125217, 34},
    };
  }

  // Unicode script and category groups — generated at runtime from the JDK's Character
  // implementation. See UnicodeGroups for details.


  public static final Map<String, int[][]> UNICODE_GROUPS = UnicodeGroups.groups();


  // ---------------------------------------------------------------------------
  // Unicode Perl character classes (for UNICODE_CHARACTER_CLASS flag)
  // ---------------------------------------------------------------------------
  //
  // These define Unicode-aware \d, \s, \w for use when the UNICODE_CHARACTER_CLASS
  // flag is enabled. They parallel PERL_GROUPS but use Unicode properties instead
  // of ASCII-only ranges.

  /**
   * Unicode {@code \d}: Unicode category Nd (Number, Decimal Digit). JDK maps {@code \d} under
   * UNICODE_CHARACTER_CLASS to {@code \p{IsDigit}} which is the Nd general category.
   */
  public static int[][] unicodeDigit() {
    return UNICODE_GROUPS.get("Nd");
  }

  /**
   * Unicode {@code \s}: Unicode White_Space property. This includes ASCII whitespace plus Unicode
   * space separators (Zs), line/paragraph separators (Zl, Zp), and certain control characters.
   * Matches JDK's {@code \s} under UNICODE_CHARACTER_CLASS.
   *
   * <p>White_Space is a derived Unicode property, not a general category, so it cannot be built
   * purely from {@link Character#getType(int)}. The specific control characters included (HT, LF,
   * VT, FF, CR, FS, GS, RS, US, NEL) are stable across Unicode versions. A canary test verifies
   * consistency with the JDK.
   */
  public static int[][] unicodeSpace() {
    // Unicode White_Space property (from PropList.txt):
    // 0009..000D, 0020, 0085, 00A0, 1680, 2000..200A, 2028, 2029, 202F, 205F, 3000
    return new int[][] {
      {0x09, 0x0D}, // \t \n \x0B \f \r
      {0x20, 0x20}, // space
      {0x85, 0x85}, // NEL (Next Line)
      {0xA0, 0xA0}, // NBSP
      {0x1680, 0x1680}, // Ogham space mark
      {0x2000, 0x200A}, // en quad through hair space
      {0x2028, 0x2029}, // line separator, paragraph separator
      {0x202F, 0x202F}, // narrow no-break space
      {0x205F, 0x205F}, // medium mathematical space
      {0x3000, 0x3000}, // ideographic space
    };
  }

  /**
   * Unicode {@code \w}: Alphabetic + Mark + Digit + Letter_Number + Connector_Punctuation (Pc) +
   * Join_Control. JDK defines {@code \w} under UNICODE_CHARACTER_CLASS as {@code
   * [\p{Alpha}\p{gc=Mn}\p{gc=Me}\p{gc=Mc}\p{Digit}\p{gc=Pc}\p{IsJoin_Control}]}.
   *
   * <p>Built by merging the runtime-generated L (Letter), M (Mark), Nd (Decimal Number), Nl
   * (Letter Number), Pc (Connector Punctuation) tables, plus the two Join_Control characters
   * (U+200C, U+200D).
   */
  public static int[][] unicodeWord() {
    return UnicodePerlHolder.UNICODE_WORD;
  }

  /** The two Join_Control characters: U+200C (ZWNJ) and U+200D (ZWJ). */
  private static final int[][] JOIN_CONTROL = {{0x200C, 0x200D}};

  // Lazy holder for unicode Perl groups — merging range tables is non-trivial, so cache the result.
  private static final class UnicodePerlHolder {
    static final int[][] UNICODE_WORD =
        mergeRangeTables(
            UNICODE_GROUPS.get("L"),
            UNICODE_GROUPS.get("M"),
            UNICODE_GROUPS.get("Nd"),
            UNICODE_GROUPS.get("Nl"),
            UNICODE_GROUPS.get("Pc"),
            JOIN_CONTROL);

    static final Map<String, int[][]> UNICODE_PERL_GROUPS =
        Map.of("\\d", unicodeDigit(), "\\s", unicodeSpace(), "\\w", UNICODE_WORD);
  }

  /**
   * Map of Unicode-aware Perl shorthand classes, paralleling {@link #PERL_GROUPS}. Used when {@link
   * ParseFlags#UNICODE_CHAR_CLASS} is active.
   */
  public static Map<String, int[][]> unicodePerlGroups() {
    return UnicodePerlHolder.UNICODE_PERL_GROUPS;
  }

  /**
   * Merges multiple sorted range tables into a single sorted, non-overlapping range table. Each
   * input table is an array of {@code {lo, hi}} pairs sorted by lo. The output merges adjacent and
   * overlapping ranges.
   */
  static int[][] mergeRangeTables(int[][]... tables) {
    // Count total ranges.
    int total = 0;
    for (int[][] t : tables) {
      total += t.length;
    }
    // Collect all ranges.
    int[][] all = new int[total][];
    int idx = 0;
    for (int[][] t : tables) {
      System.arraycopy(t, 0, all, idx, t.length);
      idx += t.length;
    }
    // Sort by lo, then by hi.
    java.util.Arrays.sort(all, (a, b) -> a[0] != b[0] ? a[0] - b[0] : a[1] - b[1]);
    // Merge overlapping/adjacent ranges.
    int[][] merged = new int[total][];
    int count = 0;
    for (int[] range : all) {
      if (count > 0 && range[0] <= merged[count - 1][1] + 1) {
        // Extend existing range.
        merged[count - 1][1] = Math.max(merged[count - 1][1], range[1]);
      } else {
        merged[count++] = new int[] {range[0], range[1]};
      }
    }
    return java.util.Arrays.copyOf(merged, count);
  }
}
