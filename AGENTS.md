# WatchMan Engineering Agent Contract

Updated: 2026-09-28

This file governs engineering agents and contributors working in this repository.

## Authority and evidence

The user's explicit requirements and physical-device observations are the runtime authority.

Always distinguish:
- physical-device acceptance;
- emulator/instrumentation evidence;
- host/core-test evidence;
- source/static evidence;
- historical claims;
- planned or incomplete work.

A source symbol, passing build, generated PDF, or prior-session statement is not proof of end-to-end behavior.

Do not mark a requirement VERIFIED until the claimed behavior has evidence appropriate to that requirement.

## Current release target

The current corrective target is:
- versionName: `0.3.0.1`
- versionCode: `31`
- package: `com.bggg.watchman`
- minSdk: 26
- target/compile SDK: 36
- release APK policy: unsigned

Do not alter release identity without explicit user direction.

## Preserve the product

Do not remove or simplify existing functionality to make a corrective gate easier.

Preserve accepted:
- routing modes;
- GPS/navigation;
- route preview;
- rerouting;
- exact route lock/recovery;
- camera/sensor acquisition and population;
- alerts;
- TTS/audio/vibration;
- maps;
- exports;
- history;
- analytics;
- settings;
- providers;
- setup/acquisition workflows;
- offline/preloaded data behavior;
- validated traffic-signal weighting;
- accepted UI/UX.

Difficult requirements remain requirements.

## User-locked SafeTOC traffic-signal coverage (2026-11-08)

The user has explicitly confirmed that the existing OpenStreetMap/Overpass `node["highway"="traffic_signals"]` discovery and resulting SafeTOC-compatible traffic-device population are accurate enough to keep. This is **accepted, user-validated project scope**, not an invitation to reclassify these results as merely intersections or narrow them to independently camera-tagged nodes. Do not change this acquisition clause or its existing 700-result floor, `CameraType.TRAFFIC` routing inclusion, unit exposure weighting, 180 m awareness, records, source identities, or existing map/alert/export behavior. Preserve the other sensor families, including Flock/ALPR, without exception.

New road-control metadata such as `highway=stop` and `stop=all`, absence from a later Overpass snapshot, uncertainty about an affected road, provider limits, or backend `NoRoute` do **not** by themselves authorize removing, suppressing, downgrading, reclassifying, or exempting a previously accepted signal record. Preserve the accepted record and its provenance; represent unsupported road-specific attribution conservatively as UNKNOWN instead of manufacturing an exemption. Investigate source disagreement separately from the routing failure. No Cams still requires strict avoidance and fully checked candidates.

**CODEX and all contributors: do not change this accepted coverage or weaken its detection/weighting policy without a new explicit instruction from the user.** A source audit or failed route is not that instruction.

## Active-navigation routing contract

WatchMan is active navigation. The selected route mode must affect graph exploration and path construction.

### No Cams

**Name clarification (user instruction, 2026-11-08): `No Cams` is shorthand and the existing UI label for `No Cameras or Sensors`.** The mode must avoid *all* known meaningful camera **and** sensor encounters, including accepted traffic-device detections, whether or not a device photographs anything. In particular, it covers Flock/ALPR/LPR, speed/red-light cameras, the validated traffic-signal population, acoustic sensing, RF/electronic-signature systems (including SignalTrace/EOC Plus), surveillance, highway sensors, and every other supported device category. Neither device names nor `CameraType` enum naming may be used to narrow routing to photographic cameras only.

Keep the complete accepted device population in acquisition, storage, awareness, and downstream reporting. Determine routed encounters using the existing conservative, source-verified affected-road policy; a nearby awareness record on a different proven-unaffected road is not automatically a routed encounter. Do not silently omit a category, drop records to obtain a route, treat unknown coverage as proven clear, or relax the strict No Cams acceptance rule. This clarification does not rename the UI mode or change routing implementation.

Highest implementation priority.

Construct legal routes around meaningful known camera/sensor exposure and seek zero encounters when reasonably viable.

It is acceptable for a correct No Cams route to use:
- more turns;
- side streets;
- residential roads;
- legal service roads;
- backroads;
- non-highway roads;
- longer but reasonable paths.

Exposure is primary. ETA is secondary within defined detour/interactivity bounds.

Do not implement No Cams as post-hoc scoring of a few ordinary Fastest routes.

### Most Cams / Full Cams

Construct legal routes that deliberately increase meaningful known camera/sensor exposure within reasonable deterministic detour bounds.

Do not manufacture exposure with loops or oscillation.

### Scenic

Choose the longest reasonable geometrically distinct route within deterministic distance/time bounds.

### Shortest

Optimize road distance only.

### Fastest

Optimize ETA only.

### Legal traversal

Every mode must respect:
- one-way direction;
- road connectivity;
- motor-vehicle access;
- destination/private/customer semantics;
- barriers;
- turn restrictions;
- endpoint permissions;
- exact origin/destination binding;
- unresolved/uncertain restrictions without silently converting uncertainty into permission.

Endpoint exposure counts.

Historical route drawings and color legends are not authoritative. Use `PROJECT_REQUIREMENTS.md`.

## Exact route integrity

Persist and restore the selected route identity and exact navigation state. Do not silently substitute another candidate during lifecycle or service recovery.

Legitimate off-route rerouting must retain the selected routing mode.

## Export behavior

Preserve cache-first preparation and validation.

Normal successful flow:
1. prepare/validate;
2. open Android save picker;
3. save;
4. offer Open, Share, Done.

Cancellation/recreation must preserve validated prepared output and workflow origin.

A generated PDF is not sufficient acceptance. Map/readability and content must be validated.

## Test-location confidentiality

Never commit test-location information.

Do not commit:
- test addresses;
- cities/states/regions used for route tests;
- coordinates;
- named roads/intersections;
- test businesses or landmarks;
- recognizable route geometry;
- unredacted route PDFs/screenshots;
- GPX/KML traces for real test routes;
- filenames containing test locations.

Use neutral identifiers such as `NO_CAMS_LONG_CASE_1`.

See `EVIDENCE_HANDLING.md`.

## Source archive handling

Opaque source/history archives are inputs, not repository artifacts.

Before importing an archive:
1. hash it locally;
2. extract it outside the repository;
3. inventory contents;
4. compare against current Git state;
5. remove build outputs, caches, toolchains, private evidence and location-bearing test artifacts from the import set;
6. review stale documentation against current requirements;
7. commit reviewable source/text/assets in logical groups;
8. validate source completeness afterward.

Do not commit a large archive as a substitute for source history.

## Collaboration protocol

Before changing shared behavior:
- read `PROJECT_REQUIREMENTS.md`;
- read `EVIDENCE_HANDLING.md`;
- read the current release execution/status docs;
- inspect current source rather than relying on historical claims.

After meaningful work, record:
- what changed;
- why;
- files affected;
- verification performed;
- unresolved failures/uncertainty;
- next concrete action.

Never rewrite evidence to make a failure look like a pass.

## Definition of done

v0.3.0.1 is not complete because:
- the app compiles;
- source-contract tests pass;
- a route request returns;
- a camera count exists;
- a PDF exists;
- a corrective helper function exists.

Release completion requires the behavioral acceptance matrix plus the complete host/Android packaging gates, with physical-device acceptance reported separately.
