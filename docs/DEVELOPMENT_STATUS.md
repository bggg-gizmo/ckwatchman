# Development status — 2026-11-07

Current corrective target remains v0.3.0.1 / code 31. Source identity is still v0.3.0 / code 30 while acceptance gates remain open. No final corrective APK has been produced by this change.

Latest user rule: No Cams is strict. Longer legal snaking paths are required when needed; nonzero exposure needs proof of unavoidability. A weighted best-observed route is not a strict-mode pass. The suggested No Cams Scenic option remains a separate, unspecified future idea.

User clarified on November7:180m devices remain visible for awareness; try verified affected-road/direction coverage for rerouting/snaking, with unknown coverage conservative. The current Activity still uses the earlier conservative180m publication guard. The new source verifier below is **not yet connected to app route selection**, monitoring or recovery. Do not report this trial as an app correction already delivered.

## Source-road association trial

`RoadAwareExposure` and `RoadExposureObservationJson` implement bounded source verification and a separate awareness/encounter assessment. The trial currently supports only ordinary, graph-connected traffic-signal nodes with complete parent context, current coordinates/classification, exact full edge-walk attribution, source-way geometry and fresh receipt times. Other families, hybrid sensor evidence, missing data and ambiguity remain conservative. Current canonical classification protects SignalTrace/EOC Plus, acoustic, ALPR and other families from being mistaken for ordinary signals. All snapshot unit weights and device records remain.

Negative exemptions also require complete affected-parent geometry. Different road IDs cannot clear a device when corridors cross or coincide nearby, including grade ambiguity. Source geometry is verified in both directions, rejecting a shortcut across an intervening bend. No directional exemption or actual physical FOV is asserted. These records do not establish legal access, a vehicle entrance, an atomic planet snapshot or unavoidable exposure.

Host tests passed for the source model, decoder, conservative family/unknown/age/movement/shape/gap/overlap guards and bounded HTTP JSON transport. A private diagnostic executed the actual production verifier and app seed parser against observed source data:17nearby relevant devices remained retained, with nine source-road encounters, five conservative unknowns and three awareness-only devices. This is host source-association evidence, not a zero-exposure route or app acceptance. A prior six-device membership-only diagnostic lacked the later overlap guard and remains historical prototype evidence.

The actual source-observation provider/cache, No Cams planner/exclusion integration, final publication, consistent awareness/exposure labels, monitor suffix attribution and durable recovery still require implementation. The latest complete Android compile/lint and independent compile-only rechecks passed for these files. Compilation does not establish app integration or release acceptance.

## No Cams app connection

MainActivity now calls `NoCamsObstaclePlanner` and `ValhallaObstacleRoutingProvider` for No Cams, instead of `RouteShaper.aroundWaypointSets`. Other route modes retain their existing providers and workflows. This does not establish acceptance of those modes.

The online adapter passes camera exclusion areas and encountered-road exclusions into road-graph path construction. It accumulates all encountered relevant devices across successive candidate paths, rather than selecting a few shaping waypoints. It does not globally exclude camera-free highways. Existing traffic-device encounter classification and unit weights are preserved, including traffic devices, Flock, ALPR, SignalTrace/EOC Plus and the other accepted sensor families.

Each candidate is checked against bounded strict reads of the live, national and regional caches. Candidate queries now use at most16distance-partitioned windows covering every segment, shared cut vertices and separate original endpoint disks, avoiding the huge empty rectangle inside a bent long route. This is candidate validation, not full graph search-area evidence. Dateline envelopes conservatively include all longitudes rather than omit a seam. Cache failure, raw-row/metadata overflow, cancellation and deadline do not become an empty successful observation. Host coverage tests include24000independent segment/halo checks, endpoint disks, sparse/zero-length/seam/polar geometry and cancellation.

The planner checks original endpoint observations as well as road geometry, rechecks caches before accepting a route, and refuses an unchecked camera-crossing fallback. Endpoint overlap is retained in accounting but is **not** treated as an unavoidability certificate: a geocoded facility point is not a proven vehicle entrance. This online adapter has no complete access/unavoidability proof producer, so it currently returns only zero observed encounters and cannot grant a nonzero exception. Original endpoint context is persisted with the exact route and included in its identity and analysis. Limits are explicit:

- 25-second shared planning ceiling;
- up to eight road requests;
- up to 512 accumulated devices and 128 internal encountered-road points;
- at most 100 polygon vertices and 50 explicit road locations on the current demo wire request, with a conservative 9 km polygon-perimeter allowance;
- 180 m encounter policy, with circumscribed octagons and a 1 m numeric margin;
- at most 100 m endpoint road-snap displacement;
- detour distance at most the greater of three times baseline or baseline plus 10 km;
- detour time at most the greater of three times baseline or baseline plus 30 minutes.

Exceeding these bounds is a reported refusal, not proof that a zero-exposure or minimum-exposure path is impossible.

Overlapping exclusion outlines are combined without replacing them by convex hulls. Holes or ambiguous junctions retain original rings. Mixed encoding removes a road-point constraint only when it lies safely inside a retained polygon, preserving every accumulated blocker. Native server errors verified the 100-vertex and 50-location limits; the OSRM-format error had misleadingly labeled vertex overflow as `PerimeterExceeded`. A deployed perimeter limit was not established by those errors.

An Activity snapshot worker checks the remaining active No Cams path for newly observed cameras, at most once per 12 seconds, with a two-second allowance covering projection, cache query, index construction and encounter checks. It requests a mode-preserving diversion for a remaining encounter, including near the destination; proximity does not grant an automatic exception. Distant passed devices are excluded by remaining-route geometry. This code has host tests and complete Android compilation evidence. Android runtime/lifecycle acceptance remains open. Independent background-service diversion ownership remains open.

The shared route-proximity index now uses conservative latitude/longitude padding under the existing distance metric, per-segment world copies and exact checks for polar/oversized segments. It retains exact overflow checks after its 65,536-cell / 500,000-reference memory bounds instead of sampling away possible encounters. Injected cancellation/deadline checks reach construction, queries and route projection. A synthetic device just inside the accepted radius at a grid boundary reproduced a false-negative lookup before the correction. This repair does not change the accepted radius, unit weights or traffic/sensor taxonomy.

Diagnostics distinguish graph requests and accumulated exclusions from legacy shaped-route attempts. The route snapshot revision is recorded separately. Failed automatic No Cams diversions warn that the retained current guidance may encounter devices.

## Evidence obtained

- Corrective host suite: passed after the endpoint-proof and spatial-index corrections. Coverage includes synthetic directed-road Dijkstra detours, eight obstacles together, new detour cameras, parallel-road exposure, all sensor families, endpoint accounting, cache failure, ignored exclusions, cancellation/deadline/budgets and monitoring. Tests explicitly require rejection of unproven endpoint exceptions. New lookup checks cover grid boundaries, both poles, the dateline, repeated world copies, long segments, 600 independent exact-distance comparisons, construction/query/projection cancellation and memory-bounded exact overflow.
- Existing core smoke suite: passed.
- Adapter host suite: passed, including the latest small-payload road-blocker correction. Tests cover complete encounter-disk containment, independent union-membership grids, gap/hole preservation, mixed-constraint coverage, all wire budgets, cancellation, JSON HTTP transport and identifying header through a loopback server, backend failure, exact route origin/identity recovery with legacy compatibility, and existing OSRM-format geometry/maneuver/lane decoding. Small/empty polygon sets must retain independent outside or boundary road blockers; overflow is refused.
- Independent Android 36 resource compile/link, generated Java and complete application Kotlin compilation: passed with a bounded compiler heap. Compile-only mode deliberately did not run DEX, alignment, APK assembly or release packaging.
- Full source-contract/release audit: not run successfully. Its guard rejects the public checkout because the original private geographic assets and release evidence are absent. This is not reported as a pass.
- Offline Gradle `compileReleaseKotlin` and `lintVitalRelease`: passed after the latest code corrections (3m29s, 23 tasks: four executed, 19 up-to-date), followed by a successful independent Android36 resource/link/Rjavac/fullKotlin compile-only check. Build Tools 36 is explicit; validation used official SDK AAPT2 and an immutable read-only snapshot of existing lint metadata/artifacts. This was not `assembleRelease`, DEX or final packaging.
- Live private case using the existing app seed: actual graph detours returned, but the current Valhalla prototype has not established the required route before representability/request bounds. Result remains INCOMPLETE; no unchecked baseline is published.
- Approved private BRouter native-circle geometry probe: returned detours and reduced observed counts on one candidate, but successive hard avoidance expanded far beyond reasonable short-trip bounds without establishing zero additional exposure. This is not app integration, navigation/legality acceptance or proof of minimum exposure. No BRouter provider has been added to the app.
- A private positive-weight BRouter experiment found a bounded candidate with fewer observed encounters, but minimum exposure and unavoidability were not proven. It is explicitly rejected as strict No Cams acceptance and remains outside app source. It may inform a separately specified Scenic idea later.

Host results do not establish emulator, physical-device, provider legality or acquisition completeness.

## Remaining No Cams work

1. Resolve strict avoidance for dense real cases. Public-provider constraint exhaustion or excessive detours are not proof that encounters are unavoidable. Do not delete traffic/sensor categories to make this pass. Continue validating longer legal snaking paths, endpoint, long-distance and degraded-network behavior.
2. Implement proof-backed handling of unavoidable nonzero exposure using actual legal endpoint/access and complete search evidence. Endpoint proximity alone, including a geocoded building/POI centroid, does not establish that every legal departure or arrival is exposed. The online prototype currently grants no nonzero exception. Do not integrate the unproven weighted experiment as a strict-mode fallback.
3. Complete authoritative local/PBF graph producers, legal endpoint/entrance binding, timing/control contexts, ready graph catalogue and app integration. Existing `FullRoadNoCamsProvider` components are retained; their source-readiness flags have not been falsely enabled.
4. Validate coherent snapshots while acquisition mutates the three caches. Current rereads are separate bounded observations, not one atomic cross-database epoch.
5. Validate new-camera diversion, lifecycle/service ownership, exact selected-route persistence and priority-acquisition resumption on Android.
6. Resolve the public demo backend's exclusion/performance limits for end-user deployment. Backend deployment, reliability and operator coordination remain open.

## Release boundary

All requirements in `PROJECT_REQUIREMENTS.md`, `V0_3_0_1_CORRECTIVE_SCOPE.md`, `ACCEPTANCE_MATRIX.md` and `RELEASE_GATES.md` remain in force. Most Cams, Scenic, Fastest/Shortest semantics, exports/readable maps, acquisition, offline data, audio, UX, package validation and physical-device acceptance have not been declared complete by these routing changes.

No release identity bump or final APK/source ZIP/SHA256SUMS/release package until the required gates pass. Preserve private evidence outside this public source tree. No PDFs or real test locations belong in this repository.

## Reproduce host checks

Set `WATCHMAN_TOOLCHAIN_ROOT` to the existing toolchain directory or pass `-ToolchainRoot`:

```powershell
./scripts/test-core.ps1 -CompilerHeapMiB 256
./scripts/test-corrective.ps1 -CompilerHeapMiB 256
./scripts/test-obstacle-routing.ps1
./scripts/build-native-local.ps1 -CompilerHeapMiB 384 -CompileOnly -BuildDir build-android-compile-check
```

The adapter host suite uses the upstream `org.json:json:20260814` JAR in `host-test` under the toolchain directory. Its SHA-256 is pinned in the script. It is a host-only dependency; Android continues using its platform JSON implementation. Scripts perform no automatic dependency download. The loopback HTTP test and Gradle's local daemon IPC need permission to use local sockets.

Provider API reference: [Valhalla route API](https://github.com/valhalla/valhalla/blob/master/docs/docs/api/route/api-reference.md). Demo deployment guidance: [Valhalla introduction](https://github.com/valhalla/valhalla/blob/master/docs/docs/start/introduction.md).
