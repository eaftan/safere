# Unicode 17.0.0 grapheme inputs

These files are unmodified copies of the Unicode Character Database:

| File | Source | Used properties |
|---|---|---|
| `GraphemeBreakProperty.txt` | https://www.unicode.org/Public/17.0.0/ucd/auxiliary/GraphemeBreakProperty.txt | Grapheme_Cluster_Break |
| `DerivedCoreProperties.txt` | https://www.unicode.org/Public/17.0.0/ucd/DerivedCoreProperties.txt | Indic_Conjunct_Break (InCB) |
| `emoji-data.txt` | https://www.unicode.org/Public/17.0.0/ucd/emoji/emoji-data.txt | Extended_Pictographic |

`LICENSE.txt` is the Unicode License V3 notice from
https://www.unicode.org/license.txt. `SHA256SUMS` records the exact checked-in
bytes. The generator reads these local inputs without network access and does
not consult the runtime JDK's Unicode version.

The conformance resource at
`safere/src/test/resources/org/safere/GraphemeBreakTest.txt` in the repository comes from
https://www.unicode.org/Public/17.0.0/ucd/auxiliary/GraphemeBreakTest.txt and is
covered by the same license. Its header retains Unicode's attribution.
