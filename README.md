# WatchMan

WatchMan is an Android active-navigation project. Its routing modes deliberately use different objectives: avoid known device encounters, seek them within a reasonable detour, find the longest reasonable scenic route, minimize distance, or minimize ETA. See [Project Requirements](PROJECT_REQUIREMENTS.md) for the full behavioral contract.

## What is here

- The current Android development source is in `WatchMan-v0.3.0.1-dev/`.
- The public checkout includes application code, resources, build configuration, data-generation tools, and source tests using synthetic fixtures.
- It does not include PDFs, real test routes or locations, original geospatial payloads, device/emulator snapshots, personal data, development logs, signing material, or historical release packages. Original material is maintained separately by the owner.

## Development status

The corrective target is v0.3.0.1. The checked-in development version is still 0.3.0/code 30. Whole-road No Cams search components exist, but production data integration, actual trip acceptance, and the final corrective release gates are still open. This import is not a new APK or a claim of release/device acceptance.

Open `WatchMan-v0.3.0.1-dev` in Android Studio with JDK 17 and Android SDK 36. With a compatible Gradle installation, `gradle :app:assembleDebug` builds the development source. This checkout intentionally omits the original geographic data assets; offline/preloaded coverage, private geographic tests, and release-contract audits require a separate data-enabled workspace. Do not add private assets to this repository to make those checks run.

## Contributing

Read [AGENTS.md](AGENTS.md), [CONTRIBUTING.md](CONTRIBUTING.md), and [Evidence Handling](EVIDENCE_HANDLING.md). Use synthetic locations and neutral fixture IDs. Do not post PDFs, actual test addresses or coordinates, recognizable route geometry, personal identifiers, credentials, or original screenshots in commits, issues, or pull requests. App code that generates exports and handles user-supplied locations remains in scope; generated private files do not belong here.
