# Reviewed intentional reproductions

These decoded cases are separate from passing Jazzer seeds. Each indexed
properties file pins the exact regex, flags, input, operation and both outcomes,
and links a documented rule and an independent SafeRE model regression.
`IntentionalReproductionTest` executes both engines and also verifies that the
exploratory comparator still reports the difference. A changed outcome fails
replay and requires reclassification; this registry is never consulted to
suppress fuzz findings. Java properties escaping preserves the decoded text.

Replay all entries with:

```bash
mvn -pl safere-fuzz -am -Dtest=IntentionalReproductionTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Select an exact entry with `-Dsafere.fuzz.reproduction=comment-quote-membership`.
Unknown identifiers fail. Add a newly reviewed case only with its rule, model
regression and independently checked outcomes. Similarity is not classification.
These curated semantic reproductions have no invented raw fuzz bytes; discoveries
must additionally retain the original Jazzer reproducer and run metadata.

For the comment/quoting proposal in PR #864, retain the explicit
`CommentQuotingModelTest` expectations and these exact exploratory reproductions.
The comment scanner is unnecessary for continuous targets and must not become a
new suppression path. This records the local policy reconciliation; it does not
update or close the external PR.
