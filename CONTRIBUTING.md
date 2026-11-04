# Contributing to WatchMan

Read these first:
- `AGENTS.md`
- `PROJECT_REQUIREMENTS.md`
- `EVIDENCE_HANDLING.md`
- `docs/ACCEPTANCE_MATRIX.md`
- `docs/RELEASE_GATES.md`

## Working principles

Preserve accepted functionality and UI/UX unless the user explicitly requests a change.

Do not make a difficult corrective gate pass by:
- deleting camera/sensor data;
- weakening traffic/device weighting;
- reducing the road search space;
- silently falling back to Fastest;
- dropping route alternatives;
- removing export/audio/navigation behavior;
- changing tests to encode an easier behavior.

Fix the execution path.

## Evidence

Every change should identify its evidence class:
- source/static;
- host/core;
- emulator/instrumentation;
- physical-device.

Do not imply a stronger class than was actually tested.

For behavioral work, source-presence tests are supplemental only.

## Routing changes

State which mode(s) are affected:
- No Cams;
- Most Cams / Full Cams;
- Scenic;
- Shortest;
- Fastest.

For camera-aware changes, describe how the change affects graph exploration/path construction rather than only post-route scoring.

For legal traversal changes, explicitly cover relevant:
- access;
- destination/private semantics;
- one-way direction;
- barriers;
- turn restrictions;
- endpoint behavior;
- unresolved/unknown constraints.

## Privacy

Never put real routing-test location information in GitHub.

Do not include test:
- addresses;
- endpoint business names;
- cities/states/regions;
- coordinates;
- named roads;
- landmarks;
- recognizable route geometry;
- unredacted route PDFs/screenshots;
- GPX/KML traces.

Use neutral case IDs.

## Source imports

Do not commit opaque development archives.

Extract, inventory, compare and review first. See `docs/SOURCE_ARCHIVE_IMPORT.md`.

## Release work

Do not call a release complete until all applicable gates in `docs/RELEASE_GATES.md` pass.

The final production APK is intentionally unsigned. Physical-device acceptance remains separate from host/emulator validation.
