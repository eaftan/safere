# Migrating from java.util.regex

For supported patterns and APIs, start by changing the imports:

```java
// Before
import java.util.regex.Pattern;
import java.util.regex.Matcher;

// After
import org.safere.Pattern;
import org.safere.Matcher;
```

## Validating with safere-crosscheck

To compare SafeRE behavior with `java.util.regex` in your
application, use the [safere-crosscheck](../safere-crosscheck/) module. It
provides `Pattern` and `Matcher` classes that compare covered operations on
both engines and throw an exception if results diverge:

```java
// Crosscheck mode — just change the import
import org.safere.crosscheck.Pattern;
import org.safere.crosscheck.Matcher;
```

Matcher operations are recorded in a trace for bug reports. Coverage has
exceptions, including methods that delegate without comparison. See
[safere-crosscheck/README.md](../safere-crosscheck/README.md) for details.

## Compatibility checklist

- Check patterns against the [supported syntax](SYNTAX.md). Backreferences,
  lookaround, atomic groups, and consuming possessive quantifiers are rejected.
- Check for `CANON_EQ`, `hitEnd()`, and `requireEnd()` use; these are unsupported.
- Review the [intentional divergences](../INTENTIONAL_DIVERGENCES.md), including
  matcher state, captures, regions, and Unicode edge cases.
- Run the application's tests after changing imports. SafeRE types are separate
  Java types: an API accepting `java.util.regex.Pattern` still needs an adapter
  or a change to that API. `String.matches`, `String.replaceAll`, and similar
  JDK convenience methods continue to use the JDK engine.

Common operations include `compile`, `matches`, `quote`, `lookingAt`, `find`,
groups and bounds, splitting, predicates, and replacement. Replacement templates
support numbered and named references (`$1`, `${name}`) and backslash escaping.

The crosscheck facade runs the JDK engine too, so use it for validation on
controlled inputs. It does not provide SafeRE's linear-time guarantee for the
combined operation. A reported difference may be an intentional divergence;
include the trace when reporting an unexpected difference.
