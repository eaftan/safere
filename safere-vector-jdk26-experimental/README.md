# SafeRE experimental Vector provider for JDK 26

This experimental optional artifact accelerates selected UTF-8 ASCII character-class scans with
the JDK 26 incubator Vector API. It is kept outside the normal Maven reactor so the core SafeRE
artifact continues to build and run on Java 21 without linking to an incubator module.

The provider SPI and this artifact may change incompatibly or be removed in any SafeRE release.
They are available for evaluation and benchmarking, not as supported third-party extension points.

Applications opt in by adding this artifact alongside SafeRE and starting Java with:

```text
--add-modules=jdk.incubator.vector -Dorg.safere.experimental.utf8ScanProvider=vector
```

Adding the artifact alone does not activate it. Core SafeRE discovers providers only when the
system property requests one. A different provider artifact can target a later incompatible
incubator or preview API without changing the core artifact.
