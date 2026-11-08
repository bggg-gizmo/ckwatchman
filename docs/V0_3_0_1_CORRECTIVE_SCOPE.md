# WatchMan v0.3.0.1 Corrective Scope

Updated: 2026-09-28

This document carries forward the complete corrective scope for v0.3.0.1 in location-neutral form.

It supplements:
- `PROJECT_REQUIREMENTS.md`
- `docs/ACCEPTANCE_MATRIX.md`
- `docs/RELEASE_GATES.md`
- `EVIDENCE_HANDLING.md`

## 1. Preserve validated traffic/device behavior

Do not make camera-aware routing look better by deleting or broadly suppressing the existing validated traffic/device population.

Required:
- preserve accepted traffic-signal/device weighting;
- fix individual bad records only when specific evidence supports that correction;
- keep route optimization, turn-by-turn, exports, alerts, analytics and history semantically consistent;
- explicitly distinguish weighted route exposure from raw corridor-device counts when both are presented.

### User-locked accepted traffic-signal coverage (2026-11-08)

The existing SafeTOC-compatible Overpass `node["highway"="traffic_signals"]` feed and its accepted traffic-device population are accurate enough to keep, by explicit user instruction. This includes the existing `TRAFFIC` routing inclusion, unit weights and 180 m nearby awareness. Do not make `NoRoute` disappear by reclassifying, deleting, suppressing, exempting or downgrading accepted traffic-signal records, even when later OSM source metadata instead lists an all-way stop. Preserve all other camera/sensor families, including Flock/ALPR.

Record any disagreement between the current source tags and accepted cached records without treating road-control metadata as proof that a sensor must be excluded. Missing/mismatched affected-road evidence remains conservatively UNKNOWN. Keep current discovery queries, storage, source identities, previews, map, alerts, export labels and routing weighting unchanged until the user explicitly authorizes a behavior change. Resolve the backend graph/exclusion failure independently.

## 2. Whole-route camera-aware search

### No Cams

No Cams must optimize exposure over the entire route.

Required:
- remove any architecture that considers only the first few exposure clusters;
- do not ignore route endpoints through a generic exclusion radius;
- distinguish truly unavoidable endpoint access from avoidable departure/arrival exposure;
- search legal local streets, service roads, residential roads, backroads, arterials and other motor-vehicle-usable roads;
- choose zero exposure when a reasonable legal zero-exposure route exists;
- otherwise choose minimum achievable weighted exposure inside deterministic detour bounds;
- use ETA/travel cost only as a deterministic secondary objective after exposure.

Preferred path objective:

`(weighted exposure, travel cost)`

Exposure compares first.

Strictness clarification from the user on 2026-11-06: nonzero exposure requires proof that it is unavoidable. Exhausting a bounded/public-provider search is not such proof. Keep searching legal longer/snaking paths when needed; do not substitute an uncertified weighted candidate under No Cams. All accepted traffic devices and listed sensor families remain included. The separate No Cams Scenic suggestion has not been specified or added to the app.

### Most Cams / Full Cams

Required:
- deliberately seek greater meaningful exposure;
- remain within deterministic detour/time bounds;
- remain a reasonable driving path;
- do not introduce negative-cost or loop behavior that allows repeated devices to inflate exposure;
- use real graph diversity and no-loop constraints.

### Scenic

Required:
- use diverse legal path generation;
- select the longest valid route inside deterministic distance/duration bounds;
- remain geometrically distinct;
- do not silently fall back to Fastest because a small shaping search fails.

### Fastest and Shortest

Required pipeline:
1. resolve origin/destination;
2. obtain valid road-route candidates;
3. select by ETA for Fastest or road distance for Shortest;
4. publish usable route promptly;
5. asynchronously enrich camera/sensor context.

Fastest/Shortest must not synchronously wait for camera-network work that is irrelevant to their primary objective.

## 3. Routable PBF graph

The local graph must be generated from full raw geospatial road data rather than display-only polylines or arbitrary geometric offsets.

Retain enough information for legal routing:
- node topology;
- edge/way identity;
- road geometry;
- road class;
- one-way semantics;
- access/vehicle/motor-vehicle restrictions;
- service-road subtype;
- turn-restriction relations;
- barriers;
- speed/maxspeed/default-speed model;
- shard membership;
- cross-shard connector edges.

Include all legally usable motor-vehicle road classes needed by active navigation, not only major highways.

Do not assume every service/alley road is legal. Access tags, restrictions and graph connectivity control.

Online routing may remain available, but candidate points must be snapped to real legal road segments. A stronger implementation performs exposure-aware routing directly on the local graph.

## 4. Route diversity

Alternatives must be generated from actual road-graph diversity, such as:
- k-shortest/diverse paths;
- edge/segment penalties;
- corridor-disjoint candidates;
- exposure-aware candidates.

Geometry deduplication must reject materially identical corridors even when vertex density differs.

## 5. Startup workflow correction

Normal application entry must preserve the approved launch presentation.

Required behavior:
- show only `Isaiah 21:6` during the intro;
- use the approved breathing treatment;
- visible for approximately three seconds;
- transition to the preserved workflow-choice screen;
- present both setup and navigation choices on every normal launch;
- keep national acquisition/progress persistent independently;
- do not permanently suppress the launch workflow because of an earlier choice;
- do not replay onboarding over an already-active navigation session merely because of a transient lifecycle resume.

Launch presentation state is ephemeral. Acquisition/setup progress remains persistent.

## 6. TTS / voice correction

WatchMan must use a shared, explicit speech policy across Activity and foreground service.

### British-English voice

Target:
- locale `en-GB` / `Locale.UK`;
- enumerate installed voices;
- prefer exact British-English locale;
- prefer higher-quality installed/offline voice where practical;
- explicitly select the voice rather than only setting a generic locale;
- expose selected engine, voice name and locale in diagnostics;
- provide a Test Voice action;
- surface missing voice-data state rather than silently substituting another locale.

Do not promise identical voice quality across all devices unless a voice engine is bundled.

### Delivery acknowledgement

Do not consume a spoken alert because it merely became eligible.

Track delivery stages such as:
- eligible;
- reserved;
- queued;
- started;
- delivered;
- failed/retryable;
- cancelled by explicit policy.

Use unique utterance IDs and TTS progress callbacks.

A TTS-not-ready or failed-to-queue event must not permanently consume the alert.

### Speech arbitration

Navigation speech and camera/sensor speech require one explicit queue/priority policy.

If an utterance is displaced, it must be intentionally superseded or remain retryable. It must not disappear silently.

### Foreground/background ownership

Use one shared speech coordinator or a strict ownership handoff so Activity/service transitions cannot double-speak or lose speech state.

Add required Android TTS-service visibility integration and verify on supported Android versions.

## 7. PDF/export road-context correction

A successfully generated PDF is not accepted when the route is drawn without usable road context.

Preparation and save must remain separate.

### Preparation phase

Before opening the document picker:
1. compute route bounds and export zoom;
2. enumerate required map context;
3. reuse cached raster data;
4. perform bounded explicit preparation fetches only when policy/network permit;
5. prepare local vector-road fallback for the same bounds;
6. validate that road context is actually present;
7. freeze the complete prepared PDF state/bytes.

### Save phase

After the picker opens:
- use prepared/cache data only;
- do not introduce a new network dependency;
- preserve prepared state through cancellation/recreation;
- offer Open, Share and Done after a successful save.

### Offline fallback

If raster data is unavailable, render PBF-derived/vector road context.

Do not fall straight to an empty coordinate grid when local road data exists.

Validation must render the PDF map page and prove actual road/basemap detail is present in addition to route geometry.

## 8. Performance / lag corrections

Profile real behavior. Do not solve performance by deleting data.

### Location-driven device queries

Required:
- move large database/spatial queries off location/UI callbacks;
- cancel/supersede stale queries with generation IDs;
- coalesce location-driven requests;
- cache by useful spatial/route partitions;
- publish immutable snapshots;
- prevent unbounded stale-query backlogs.

Apply equivalent treatment in foreground-service navigation.

### Spatial indexing

Benchmark the SQLite query plan.

Use appropriate spatial indexing/cell structures so repeated proximity queries do not repeatedly reconstruct nearly identical large result populations.

Avoid reparsing unchanged source metadata on every update.

### Map rendering

Required:
- classify/sort/declutter when snapshots change, not every frame;
- spatially cull before drawing;
- minimize allocations in draw paths;
- render viewport-relevant markers;
- preserve full stored data even when display density is bounded.

### Coverage callback churn

Do not re-query an entire route after every small provider/tile completion. Coalesce or incrementally merge route coverage updates.

### Seed installation

National/preloaded seed installation must be:
- chunked;
- resumable;
- cancellable/yielding;
- foreground-priority aware;
- rate-limited during active route planning/navigation.

### Executor ownership

Audit worker pools globally.

Require:
- explicit ownership;
- cancellation;
- stale-callback rejection;
- no nested blocking waits;
- navigation/planning priority over background acquisition;
- deterministic shutdown.

## 9. SignalTrace / EOC Plus discovery

Retain the existing RF/electronic-signature taxonomy.

SignalTrace/EOC Plus must remain its own RF/electronic-signature sensor family rather than being collapsed into ALPR.

Additive live/public-source discovery should consider explicit evidence such as:
- SignalTrace/EOC Plus product/model/name evidence;
- Leonardo/ELSAG manufacturer context combined with RF/electronic-signature evidence;
- reliable model identity/provenance.

Do not classify arbitrary Bluetooth/Wi-Fi/RFID infrastructure as SignalTrace.

Do not make WatchMan reproduce RF surveillance by sniffing nearby device identifiers. The product requirement is infrastructure mapping/awareness.

Preserve provenance and confidence.

## 10. Device-count semantics

Do not delete validated records to make counts appear smaller.

Keep terminology explicit:
- weighted route exposure score;
- devices along route/corridor;
- per-category counts.

The optimizer, turn-by-turn, exports, alerts, analytics and history must not present distinct metrics under one ambiguous label.

## 11. Testing architecture

A corrective release of this scope requires real Android testing.

### Emulator/instrumentation

Maintain a real `app/src/androidTest` suite.

Cover:
- launch workflow and timing;
- setup/navigation choice behavior;
- setup-progress restoration;
- route-mode workflow;
- PDF creation and map rendering;
- TTS initialization and delivery acknowledgement;
- Activity/service handoff;
- route restoration;
- picker cancellation/recreation.

Use neutral synthetic/private route fixtures; committed evidence must contain no real test locations.

### Physical/cloud device testing

Cloud physical-device testing may reduce obvious Android-specific defects but does not replace final user physical-device acceptance.

### Performance benchmarks

Measure:
- cold launch;
- intro-to-choice transition;
- map frame time during movement;
- database query duration;
- route-planning phases;
- PDF preparation;
- seed-install contention;
- foreground/background transitions.

Record percentile metrics where appropriate.

### Degradation matrix

Exercise:
- normal network;
- slow network;
- routing backend unavailable;
- camera/device provider unavailable;
- live data unavailable with cache present;
- raster unavailable;
- partial cache;
- picker cancellation;
- Activity recreation;
- foreground/background transitions.

The system must fail deliberately and promptly rather than simply extending waits.

## 12. Behavioral test discipline

Structural/source tests are supplemental.

The following do not constitute release acceptance by themselves:
- class/method names exist;
- TTS symbols exist;
- route-via helpers exist;
- PDF renderer exists;
- generated file has a PDF header;
- fallback strings exist;
- launch text appears in source.

Behavioral tests must prove outcomes.

Do not preserve obsolete assertions merely because they currently pass.

## 13. Build identity

v0.3.0.1 must be consistent everywhere:
- `versionName = 0.3.0.1`
- `versionCode = 31`
- package `com.bggg.watchman`
- minSdk 26
- target/compile 36

Update Gradle/native builders, package scripts, validation assertions, docs and visible version strings together.

Use both maintained build paths described in `docs/RELEASE_GATES.md`.

Final production APK remains intentionally unsigned.

## 14. Full-PBF regeneration

When full raw road inputs are available:
1. inventory/hash every input;
2. regenerate source-derived data as required;
3. generate full routable graph shards including local/service roads;
4. preserve cross-shard connectors;
5. validate access/one-way/turn/barrier semantics;
6. generate deterministic manifests/counts/hashes;
7. package required graph assets;
8. run representative offline/legal route tests;
9. rerun permanent sanitized No Cams regression cases;
10. rerun PDF road-context fallback tests;
11. rerun all release gates.

Do not impose an artificial file-size ceiling by deleting required routing capability.

## 15. Diagnostics

Diagnostics must be bounded and local/debug-oriented.

### Route planning

Capture:
- version/build hash;
- mode;
- provider/source classes;
- candidate count;
- graph shard IDs;
- exposure score/count per candidate;
- affected edge/corridor identifiers;
- alternatives explored;
- detour limits;
- rejection reasons;
- geometry-diversity hash;
- per-phase timing;
- total timing;
- deadline state;
- cache/live coverage state.

Do not commit real origin/destination details.

### Speech

Capture:
- TTS engine;
- selected voice;
- locale;
- voice quality/network requirement when available;
- utterance ID;
- requested/queued/started/done/error timestamps;
- arbitration decision;
- current owner;
- device type/neutral device ID.

### Map/PDF

Capture:
- neutral route/test ID;
- export zoom;
- raster tiles required/cached/fetched;
- local road fallback use;
- basemap validation result;
- output page count/size.

### Performance

Keep bounded metrics. Do not create unbounded diagnostic logs.

## 16. Evidence and privacy

All committed evidence follows `EVIDENCE_HANDLING.md`.

Real route addresses, locations, roads, coordinates and recognizable route geometry remain outside GitHub.
