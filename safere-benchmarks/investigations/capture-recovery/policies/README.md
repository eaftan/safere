# Character-loop fusion prototype

`character-loop-fusion.patch` preserves the candidate implementation and regression
coverage evaluated by this investigation. The benchmark layer does not apply it.
Its library changes apply to the measured baseline
`4ead777e87f9209c2d9dc06fb958a23bd01349be` and to the refreshed stack base; runtime
adoption is proposed in the following layer.

Use the capture-time source archives in `../results/` for exact historical
reproduction, including their benchmark-source overlays. For a new local comparison
using this benchmark layer, create isolated checkouts with identical harnesses and
apply the patch only to the candidate checkout:

```bash
git apply /path/to/capture-recovery/policies/character-loop-fusion.patch
```

For the cutoff counterfactuals, change only `Matcher.CAPTURE_DEMAND_TEXT_LIMIT` to
0 or 2048 in separate candidate snapshots; preserve all other eligibility guards.
There is no production runtime policy switch. The current limit remains 512.
