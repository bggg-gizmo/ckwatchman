## Summary

Describe the actual behavior changed and why.

## Scope

Affected areas:

- [ ] No Cams
- [ ] Most Cams / Full Cams
- [ ] Scenic
- [ ] Shortest
- [ ] Fastest
- [ ] legal traversal/access
- [ ] route lock/recovery
- [ ] acquisition/cache
- [ ] map/preview/navigation
- [ ] export
- [ ] audio/TTS/vibration
- [ ] taxonomy
- [ ] offline/preloaded data
- [ ] build/release
- [ ] documentation only

## Preservation

- [ ] Existing unrelated functionality was not removed.
- [ ] Accepted UI/UX was preserved unless explicitly requested otherwise.
- [ ] Existing traffic-signal/device weighting was not weakened merely to improve routing counts.
- [ ] Exact selected-route integrity remains protected.

## Verification

Evidence class actually obtained:

- [ ] source/static
- [ ] host/core behavior
- [ ] emulator/instrumentation
- [ ] physical device

Tests/evidence performed:

Describe results, including failures or unresolved uncertainty.

## Routing acceptance

If routing is affected:

- [ ] graph/path construction behavior was tested, not only post-route scoring;
- [ ] one-way/access/barrier/turn legality remains enforced;
- [ ] endpoint behavior remains correct;
- [ ] mode-preserving rerouting remains correct;
- [ ] route-level diagnostics/evidence support the claimed result.

## Privacy / evidence hygiene

- [ ] No test address is included.
- [ ] No test city/state/region is included.
- [ ] No coordinates or named test roads/landmarks are included.
- [ ] No recognizable real test-route geometry is included.
- [ ] No unredacted route PDF/screenshot/GPX/KML is included.
- [ ] Any route evidence uses neutral case IDs.

## Release impact

- [ ] No release identity change.
- [ ] Release identity change explicitly requested and applied consistently.
- [ ] Applicable release gates have been rerun.
- [ ] Physical-device acceptance is not claimed from host/emulator evidence.
