# WatchMan Project Requirements

Updated: 2026-09-28

This document records durable product and engineering requirements that apply across corrective releases unless explicitly superseded.

## 1. Product model: active navigation

WatchMan is an active-navigation system. The selected routing mode must influence graph exploration and path construction itself.

Camera/sensor behavior is not satisfied by:
- generating ordinary routes first and scoring them afterward;
- picking among only a small set of Fastest alternatives;
- inserting a few shaping waypoints and calling the result camera-aware;
- counting devices after route selection without changing the search objective.

The selected objective must survive acquisition, graph search, route selection, route locking, live navigation, and legitimate rerouting.

## 2. Five-mode routing contract

### No Cams

Primary priority.

Actively search around known cameras/sensors and seek zero meaningful encounters when reasonably feasible.

Permitted consequences include:
- more turns;
- side streets;
- legal service roads;
- backroads;
- non-highway roads;
- longer but reasonable legal routes.

Exposure is the primary objective. ETA is secondary within explicit detour/interactivity limits.

User clarification, 2026-11-06: **No Cams is strict.** Route around every known meaningful camera/sensor, using longer legal routes and snaking through side streets when needed. Permit a nonzero encounter only when it is proven unavoidable. A timeout, provider limitation, exhausted search budget, or unsuccessful avoidance request does not prove unavoidability. A lower observed count from a weighted heuristic must not be presented as a qualifying strict No Cams route. Preserve all accepted traffic-device data and every listed camera/sensor family.

The suggested No Cams Scenic option is a separate future design idea. It does not relax this requirement or change the existing five-mode UI.

User clarification, 2026-11-07: retain devices within 180 m for awareness, while trying verified affected-road/direction coverage to determine rerouting/snaking encounters. A nearby device on a different road must not automatically block that road when source evidence establishes its affected roads. Unknown coverage remains conservative. Preserve the complete device population and accepted weights; label nearby-device counts separately from routed encounters. This is a requirement change to implement and validate, not evidence that source-road association already works.

### Full Cams / Most Cams

Actively search through as many meaningful known camera/sensor encounters as possible within explicit reasonable-detour limits.

Do not:
- merely count devices on already-generated normal routes;
- inflate encounter counts with loops or oscillation;
- violate legality to increase exposure.

### Scenic

Select the longest reasonable route inside explicit distance/time bounds.

### Shortest

Optimize distance only.

### Fastest

Optimize ETA only.

### Historical diagrams are non-authoritative

Older sketches, screenshots, diagrams, route-color legends, and illustrative route drawings may predate later corrections.

Do not infer routing semantics from a historical image when it conflicts with the written contract above.

A historical diagram may be retained privately as context, but:
- it must not be treated as executable acceptance criteria;
- its colors and legends must not be assumed current;
- it must not be copied into repository documentation unless it has been deliberately regenerated against the current routing contract;
- any replacement diagram committed to the repository must use synthetic, non-location-specific geometry and neutral labels.

The written routing contract is authoritative unless the user explicitly changes it.

## 3. Legal graph traversal

All routing modes must preserve:
- access permissions;
- destination/private access semantics;
- permission state through partial road segments;
- one-way rules;
- barriers;
- turn restrictions;
- exact endpoint handling;
- physical traversal order;
- endpoint grants without bypassing other legality constraints.

Endpoint camera/sensor encounters count.

## 4. Exact route locking and recovery

The exact selected route must persist across Activity lifecycle changes, foreground-service recovery, and navigation restoration.

Persist and restore, as applicable:
- selected route geometry;
- origin and destination identities;
- maneuvers;
- provider metadata;
- route identity/hash;
- selected routing mode;
- current maneuver/progress state.

A destination-only re-plan may exist only as migration fallback when no valid exact-route payload is available.

A live corrective reroute must preserve the currently selected mode.

## 5. Route alternative identity

Alternative detection must be geometry-aware. Routes must be compared by actual path shape/coverage rather than superficial differences such as point density or provider encoding.

## 6. Export workflow

Directions and analytics exports should be cache-first and non-blocking.

Required normal flow:
1. render and validate from available local/cache data;
2. invoke Android's direct save picker;
3. if the user cancels, preserve a validated recovery copy;
4. after a successful save, offer Open, Share, and Done;
5. schedule optional network enrichment after rendering instead of blocking the export.

Existing validation and recovery guarantees must not be weakened.

## 7. Camera/sensor taxonomy

Maintain a rich, extensible taxonomy across device classes and supported vendors.

The taxonomy must support consistent:
- acquisition/discovery;
- normalization;
- storage;
- routing weights/objectives;
- map rendering;
- export labels;
- precedence rules;
- filtering.

Known supported vendor/device families from prior work must remain represented without hard-coding the architecture to only those families.

Bare traffic-signal data must not be misclassified as a camera/sensor encounter unless the validated taxonomy says it is relevant.

## 8. Preloaded/offline data

Offline/preloaded data is in product scope.

Requirements:
- reproducible source-to-app data transformation;
- deterministic output where practical;
- provenance records;
- validation manifests;
- resumable generation for large datasets;
- stable source identities;
- normalized taxonomy;
- compact app-ready assets;
- no false setup-completion credit merely because preloaded data exists.

Preloaded visual map data and offline routing graph capability must be described separately. A visual fallback must not be represented as a full offline routing graph unless that capability has actually been implemented and validated.

## 9. Build and release discipline

Do not call a release complete because source compilation succeeds.

Release evidence must distinguish:
- source-contract tests;
- host/core behavior tests;
- Android compilation;
- package/DEX/resource validation;
- asset integrity;
- alignment/signature state;
- emulator/instrumentation validation;
- physical-device acceptance.

Final release packaging should include, as applicable:
- unsigned APK;
- complete source archive;
- SHA256SUMS;
- source inventory;
- build report;
- release notes;
- validation evidence;
- device checklist.

Verified host/emulator results must remain clearly separated from unverified physical-device acceptance.

## 10. UI/UX preservation

Preserve accepted UI/UX and existing functionality unless explicitly instructed otherwise.

Do not silently remove or replace:
- established routing modes;
- navigation behavior;
- map behavior;
- export actions;
- audio/recovery behavior;
- setup flows;
- device categories;
- acquisition paths;
- already accepted weighting behavior.

## 11. Evidence discipline

Distinguish:
- verified implementation;
- verified test result;
- planned work;
- incomplete work;
- historical claim not reproduced against current source.

A supporting component does not satisfy an end-to-end acceptance requirement by association.

Historical screenshots, PDFs, diagrams, and route exports are evidence of observed behavior, not automatically proof of intended behavior.

## 12. Test-location confidentiality

GitHub must not contain location information for routing tests.

Do not add exact or approximate:
- addresses;
- cities;
- states/regions;
- coordinates;
- named roads;
- landmarks;
- test facilities;
- named geographic corridors;
- route maps whose geometry can identify the test area;
- other test-location identifiers.

Use location-neutral case IDs and describe only route characteristics and expected behavior.

Examples:
- `TEST_ROUTE_A`
- `SHORT_ROUTE_CASE_1`
- `LONG_ROUTE_CASE_1`
- `CROSS_REGION_CASE_1`
- `ORIGIN_FIXTURE_1`
- `DESTINATION_FIXTURE_1`

When exact geography is necessary for private execution, keep it outside committed GitHub documentation and redact it from any evidence before committing.

See [Evidence Handling and Redaction](EVIDENCE_HANDLING.md).
