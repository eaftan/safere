# SafeRE Unicode Table Generator

This module generates SafeRE's checked-in Unicode tables from the
maintainer-selected JDK's `java.lang.Character` implementation.

The generated source is checked in at:

```text
safere/src/main/java/org/safere/UnicodeGeneratedTables.java
```

Regeneration is intentionally separate from the default Maven build lifecycle.
To regenerate and format the checked-in source, run from the repository root:

```bash
./safere-unicode/generate-unicode-tables.sh
```
