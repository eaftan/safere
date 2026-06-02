# Unicode Table Generation

SafeRE's checked-in Unicode tables are generated from a maintainer-selected
JDK rather than from Unicode text files directly. The initial generation target
is OpenJDK 26.0.1.

```bash
# Generate tables in-place.
./tools/unicode/generate-unicode-tables.sh \
  safere/src/main/resources/org/safere/unicode-tables.bin \
  <unicode-version>

# Verify checked-in tables against the maintainer JDK.
./tools/unicode/verify-unicode-tables.sh <unicode-version>
```

The generator uses the same `Character` APIs that SafeRE currently uses at
runtime: `getType`, `UnicodeScript.of`, `UnicodeBlock.of`, and the supported
binary-property predicates. Generated output is committed so SafeRE behavior is
fixed for a given SafeRE release and does not depend on the runtime JDK.

Do not wire this generator into the normal Maven lifecycle. It is a maintainer
workflow for intentional Unicode-version updates.
