# WatchMan v0.3.0.1 Acceptance Matrix

Updated: 2026-09-28

All route cases committed to GitHub must use neutral identifiers and contain no test-location information.

## Routing

### No Cams

Required:
- evaluates meaningful exposure across the entire route;
- includes exposure near both endpoints;
- explores legal side streets/local roads/service roads when they can reduce exposure;
- selects a zero-exposure reasonable legal route when one exists;
- permits a nonzero encounter only with proof that it is unavoidable, and minimizes remaining exposure inside deterministic detour bounds;
- does not collapse to a tiny waypoint-shaping search;
- diagnostics show alternatives actually explored rather than only a scoring pass;
- preserves one-way, access, barrier and turn legality;
- preserves selected mode during rerouting.

Strict No Cams publication must reject a nonzero route unless unavoidable exposure is proven. Provider errors, resource limits, partial search and a best observed weighted score are not proof. Longer legal snaking routes remain eligible.

Pass evidence must report route-level behavior, not merely source presence.

### Most Cams / Full Cams

Required:
- deliberately increases meaningful weighted exposure when a reasonable legal higher-exposure route exists;
- searches the road graph rather than merely choosing the highest count from a few ordinary routes;
- prevents loops/oscillation used only to inflate counts;
- remains inside deterministic detour limits;
- obeys all legal traversal constraints;
- preserves mode during rerouting.

### Scenic

Required:
- produces the longest reasonable geometrically distinct route within its deterministic envelope;
- does not silently collapse to Fastest because a small shaping search failed;
- obeys road legality and route-integrity requirements.

### Fastest

Required:
- optimizes ETA;
- does not wait on camera-network work before publishing an otherwise valid route;
- camera counts do not alter the primary objective.

### Shortest

Required:
- optimizes road distance;
- does not wait on camera-network work before publishing an otherwise valid route;
- camera counts do not alter the primary objective.

## Route integrity and lifecycle

Required:
- selected route identity is preserved;
- exact geometry/maneuvers/mode/progress survive supported lifecycle and service recovery;
- no silent substitution of another route candidate;
- legitimate off-route rerouting is separate and mode-preserving;
- stale planning work cannot replace newer navigation state.

## Device knowledge consistency

Required:
- planning, preview, foreground navigation, background navigation, analytics and exports use semantically consistent camera/sensor knowledge;
- duplicate logical devices from multiple stores/sources do not become duplicate route encounters;
- route exposure terminology is consistent across optimizer, UI, exports and history;
- any distinct metrics are explicitly named rather than conflated.

## Maps and route visibility

Required:
- route is immediately visually identifiable;
- start and destination are identifiable without exposing private test data in committed evidence;
- full intended route is contained by overview framing;
- route remains visible above device context;
- marker/device density is bounded for readability;
- long-route and turn-by-turn views remain usable.

## Export

Required:
- prepared output survives recreation/cancellation;
- cancellation returns to the correct workflow origin;
- saved bytes validate;
- exported data matches the selected route;
- map is readable;
- road context is present;
- Open, Share and Done work after save;
- selected-route, alternatives/comparison, analytics and supported CSV/PDF capabilities remain intact.

Corrective regression:
- when raster data is unavailable, the approved local road-context fallback must produce a readable route map rather than route geometry on an empty grid.

## Startup/workflow

Required:
- normal application entry presents the approved launch sequence;
- launch presentation does not erase persistent acquisition/setup progress;
- both setup and navigation choices remain available as specified;
- active navigation is not unnecessarily interrupted by lifecycle resume behavior.

## Voice/TTS

Required:
- configured voice selection is actually applied;
- Test Voice works;
- initialization does not silently lose the first alert;
- failed speech requests are not treated as successful delivery;
- navigation speech does not silently erase a pending camera/sensor alert;
- Activity/service handoff avoids duplicate or lost speech;
- user disable/volume behavior remains correct.

## Proximity audio

Required:
- one consistent tone family is used across approach cadence states;
- cadence increases monotonically with approach;
- continuous state is the same tone held continuously, not a telephone-keypad/DTMF tone;
- explicit proximity, visual-range and FOV/fallback semantics remain distinct;
- passed-device state stops the tone;
- jitter/hysteresis does not immediately retrigger a passed device;
- foreground/background ownership is exclusive;
- settings validation preserves ordered distance thresholds.

## Acquisition/cache/offline

Required:
- route-relevant acquisition can preempt background national work safely;
- background acquisition resumes afterward;
- cached/national/regional/live data semantics remain coherent;
- seed data does not falsely earn live setup-completion credit;
- offline visual fallback is not misrepresented as a full offline routing graph;
- full routing graph validation remains separate.

## Network degradation

Exercise:
- normal connectivity;
- slow connectivity;
- routing-provider failure;
- device-provider timeout;
- live providers unavailable with cache present;
- raster unavailable;
- partial cache;
- picker cancellation;
- lifecycle recreation;
- foreground/background transitions.

Required:
- failures are bounded and deliberate;
- ordinary routing does not simply wait longer for unrelated camera work.

## Performance

Current target envelope:
- Fastest / Shortest: 10-second target;
- camera-aware routes: 20-second target;
- interactive ceiling: 25 seconds.

Collect phase timings. Performance success does not override semantic route correctness.

## Evidence classes

Report separately:
- source/static;
- host/core;
- emulator/instrumentation;
- physical-device.

A lower evidence class never implies a higher one.
