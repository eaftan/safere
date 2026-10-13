# SafeRE Unicode Table Generator

This module generates SafeRE's checked-in Unicode property tables:

- General categories, scripts, blocks, and most binary properties come from
  the maintainer-selected JDK's public `java.lang.Character` API.
- `Grapheme_Cluster_Break`, `Indic_Conjunct_Break`, and `Extended_Pictographic`
  come from ICU4J (`com.ibm.icu.lang.UCharacter`, `UProperty`, `UnicodeSet`).
  No private JDK APIs, reflection, module-opening flags, or checked-in UCD text
  files are used.

The generated source is checked in at:

```text
safere/src/main/java/org/safere/UnicodeGeneratedTables.java
```

Regeneration is separate from the default Maven build. Normal library builds
use the checked-in Java source; `safere` has no runtime dependency on ICU4J.
Run from the repository root:

```bash
./safere-unicode/generate-unicode-tables.sh
mvn -pl safere-unicode test
mvn -pl safere -Dtest=GraphemeBreakConformanceTest,UnassignedGraphemeTest test
```

## Generator options

By default the generator writes `UnicodeGeneratedTables.java` and reads the
checked-in Unicode license notice, so the script and Maven need no arguments:

```text
UnicodeTableGenerator [options] [output-file]

--unicode-license=FILE            Unicode License V3 text
                                  (default: safere/src/main/resources/META-INF/LICENSE-Unicode.txt)
```

The generator records `UCharacter.getUnicodeVersion()` as
`UnicodeGeneratedTables.UNICODE_DATA_VERSION`. The generated source carries the
Unicode license notice, also included in the library JAR as
`META-INF/LICENSE-Unicode.txt`.

To upgrade Unicode, update the `com.ibm.icu:icu4j` version in
`safere-unicode/pom.xml` and `GraphemeBreakTest.txt`, select a JDK with the
same Unicode version for the remaining properties, regenerate, and review and
commit the output together. Run the generator and conformance tests; JDK
crosschecks supplement the Unicode fixture where versions agree.
