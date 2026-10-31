# Research

Reproducible investigations that inform SafeRE's design and compatibility
decisions live here. Each study has a README describing its question,
prerequisites, commands, and limitations, alongside its scripts and compact
recorded results.

Studies run independently of the normal Maven build. Generated raw results stay
outside the checkout; each study's README gives suitable output locations.

The repetition-policy observations are pinned to JDK 26.0.2. Its probes run
manually, outside CI, and recorded results are not kept in sync with later
SafeRE implementation changes. Updating the recorded observations requires an
explicit rerun against the selected JDK.

- [Repetition policy](repetition-policy/README.md): compare repetition,
  match-selection, and capture rules against observed JDK behavior.
