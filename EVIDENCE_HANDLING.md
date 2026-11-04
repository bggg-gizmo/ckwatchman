# WatchMan Evidence Handling and Redaction

Updated: 2026-09-28

This policy governs screenshots, PDFs, exported directions, route-comparison reports, maps, logs, fixture descriptions, validation records, and other evidence used while developing WatchMan.

## Core rule

Useful behavioral evidence may contain location-sensitive route information.

Do not upload unredacted route evidence to GitHub when it exposes or permits reconstruction of a test location.

This includes both explicit text and implicit geographic information.

## Information that must be removed from GitHub-facing evidence

Redact or replace:
- origin and destination names;
- street addresses;
- business names used as route endpoints;
- city, state, province, region, county, or similar geographic names used by the test;
- postal codes;
- coordinates;
- road names;
- interchange names;
- landmarks;
- facility names;
- route-specific maneuver text containing road/place names;
- map labels;
- scale/context that makes the test location identifiable;
- route geometry over a recognizable basemap;
- metadata or filenames that encode a test location.

Route geometry itself must be treated as location information when it could identify the tested area.

## PDFs

Original directions and route-comparison PDFs may be retained privately as evidence.

Do not commit the original PDF when it contains test-location information.

For GitHub-facing evidence, prefer a text-only sanitized record containing:
- neutral case ID;
- routing mode;
- route class such as short/long/cross-region;
- expected behavior;
- actual behavior;
- relevant non-location-specific timing;
- relevant non-location-specific device/exposure counts only when needed;
- pass/fail state;
- evidence provenance;
- whether the result came from host, emulator, or physical device.

If an image excerpt is absolutely necessary, produce a purpose-built redacted derivative. Black boxes over text are not enough when the remaining route geometry or basemap still reveals the area.

## Historical route sketch

The user-supplied route sketch is historical conceptual material and may require updating.

Do not use its old colors or legend as normative routing semantics.

The written routing contract in `PROJECT_REQUIREMENTS.md` is authoritative.

If a new diagram is created for the repository, it must:
- use synthetic geometry;
- contain no real map;
- contain no real location names;
- contain no coordinates;
- visually demonstrate the current written routing objectives only.

## Sanitized evidence presently learned from supplied exports

The supplied private export evidence supports these location-neutral observations:

- at least one directions export retained route geometry while reporting that the basemap was unavailable;
- at least one route-comparison export retained route geometry while reporting that the basemap was unavailable;
- camera/sensor exposure was presented alongside route alternatives;
- therefore export correctness must include both route-data correctness and map/readability validation rather than treating successful PDF generation alone as acceptance.

The original private PDFs are not to be added to the repository.

## Evidence naming

Use neutral names such as:
- `TEST_ROUTE_A`
- `NO_CAMS_LONG_CASE_1`
- `MOST_CAMS_SHORT_CASE_1`
- `EXPORT_BASEMAP_CASE_1`
- `ENDPOINT_ACCESS_CASE_1`

Do not embed geographic names in committed filenames.

## Result boundaries

Every evidence record should clearly state whether it is:
- source/static evidence;
- host/core-test evidence;
- emulator/instrumentation evidence;
- physical-device evidence.

Do not convert one evidence class into another by implication.
