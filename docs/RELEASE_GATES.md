# WatchMan v0.3.0.1 Release Gates

Updated: 2026-09-28

Target identity:
- package: `com.bggg.watchman`
- versionName: `0.3.0.1`
- versionCode: `31`
- minSdk: 26
- target/compile SDK: 36
- final APK: unsigned

## Gate 1 — source completeness and requirements

Require:
- complete conventional Android source tree;
- current requirements/docs consistent with v0.3.0.1;
- no feature-stripped/tiny substitute build;
- no DEX/string patching or launcher/bootstrap crash-hiding;
- accepted UI/UX preservation audit;
- location-sensitive test evidence excluded from Git.

## Gate 2 — behavioral host/core tests

Require relevant executable tests for:
- five routing modes;
- legal traversal;
- endpoint/private access semantics;
- one-way/barrier/turn restrictions;
- route lock/recovery;
- geometry-aware alternative identity;
- taxonomy normalization;
- export workflow;
- navigation/audio state;
- acquisition/cache behavior.

Source-presence/grep tests remain supplemental, not behavioral acceptance.

## Gate 3 — conventional Gradle build

Run a clean conventional Gradle release build using the complete source.

Require successful:
- resource processing;
- manifest merge;
- Kotlin/Java compilation;
- DEX;
- lint/vital checks used by the project;
- APK packaging.

## Gate 4 — independent native build path

Run the maintained independent chain:

`AAPT2 → generated R/javac → Kotlin → official D8 → D8 structural re-feed → APK assembly → official 16 KiB zipalign`

Do not replace this gate with Gradle success alone.

## Gate 5 — package identity and structure

Independently verify:
- package name;
- versionName/code;
- minSdk/targetSdk;
- direct launcher Activity;
- required foreground location service declaration;
- ZIP integrity;
- 16 KiB zip alignment;
- intentionally unsigned state;
- no signing payload;
- expected assets/resources;
- source/APK asset parity where applicable.

## Gate 6 — packaged data integrity

Validate packaged:
- national seed data;
- manifests/provenance;
- offline/preloaded map assets;
- routable graph assets when included;
- deterministic hashes/counts required by their generators.

A visual road fallback must not be counted as a routable graph.

## Gate 7 — emulator/instrumentation

Require a real Android instrumentation/emulator pass for applicable workflows, including:
- application entry;
- route-mode workflow;
- route restoration;
- Activity/service handoff;
- export creation;
- cancellation/recreation;
- map rendering;
- bounded navigation state transitions.

Host success does not substitute for this gate.

## Gate 8 — route-behavior validation

Run location-private route cases outside committed Git evidence.

Commit only sanitized result records.

Require:
- No Cams whole-route avoidance behavior;
- Most Cams meaningful exposure increase;
- Scenic bounded-longest behavior;
- Fastest ETA-only behavior;
- Shortest distance-only behavior;
- legal road traversal;
- endpoint exposure;
- mode-preserving rerouting;
- route-level diagnostic evidence.

## Gate 9 — export readability

Require supported exports to contain:
- correct selected route/data;
- readable route geometry;
- usable road context/basemap or validated local fallback;
- correct route/device terminology;
- functional save/Open/Share/Done workflow.

Successful PDF byte generation alone is not a pass.

## Gate 10 — performance and degradation

Measure routing phases and verify bounded failure behavior under network/provider/tile degradation.

Retain current target envelope:
- Fastest / Shortest: 10 seconds;
- camera-aware routes: 20 seconds;
- interactive ceiling: 25 seconds.

## Gate 11 — final artifacts

Only after preceding required gates pass, produce the release set:
- unsigned APK;
- complete source archive;
- SHA256SUMS;
- source inventory;
- build report;
- release notes;
- validation evidence;
- physical-device checklist.

Use a new non-overwriting output location.

## Gate 12 — physical-device boundary

Clearly mark physical-device acceptance as pending until the user performs it.

Do not convert host, emulator, cloud-device, or static validation into a physical-device pass.
