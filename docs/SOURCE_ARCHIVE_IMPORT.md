# Source Archive Import Policy

Updated: 2026-09-28

Two candidate inputs have been supplied for migration work:
- `WatchMan-v0.3.0.1-dev.7z`
- `CHANGELOG.7z`

They are source/history inputs, not GitHub artifacts.

Current verification state: **RECEIVED / NOT YET AUTHORITATIVE**. Their presence does not prove that the repository has imported or validated their contents.

Do not commit either opaque archive to the repository. Import the reviewed **contents** directly into normal Git history.

This import does not require GitHub Actions: extract and review the archives in the working environment, then create ordinary GitHub blobs/trees/commits (or contents-API commits) directly.

## Import procedure

Before any source import:

1. Compute and record the archive SHA-256 locally.
2. Extract outside the Git working tree.
3. Generate an inventory of every extracted path, file size and hash.
4. Identify the actual Android project root.
5. Compare extracted files against current Git `main`.
6. Classify every file as:
   - current source/config/resource;
   - current documentation;
   - test source/fixture;
   - generated build output;
   - toolchain/cache;
   - historical artifact;
   - private/location-sensitive evidence;
   - stale/superseded documentation.
7. Import only reviewed source/config/resources/tests and current sanitized documentation.
8. Keep historical artifacts non-overwriting when they are intentionally retained.
9. Exclude toolchains, build directories, Gradle caches, APKs, raw PBFs, private route evidence and other generated/local-only data unless a specific tracked asset is required by the application.
10. Run the repository location-leak audit before pushing.

## Documentation reconciliation

Do not copy old documentation merely because it is present in an archive.

Resolve against:
1. current explicit user requirements;
2. physical-device evidence;
3. current corrective handoff;
4. reproduced current-source behavior;
5. historical documentation.

When an older file conflicts with the current routing contract, preserve history separately if useful but do not let stale text redefine the product.

## Source-tree authority

The imported source becomes authoritative only after:
- complete path inventory;
- source completeness checks;
- build success;
- required behavioral regressions;
- comparison against preserved historical baseline paths;
- review for accidentally omitted features.

An archive filename containing `dev` is not itself proof that every described correction exists.

## Privacy

Before committing extracted docs/tests/evidence, remove:
- route addresses and endpoint names;
- test cities/states/regions;
- coordinates;
- named roads/landmarks;
- identifiable route geometry;
- private PDFs/screenshots;
- route playback traces.

Use neutral IDs in repository-visible tests and reports.

See `EVIDENCE_HANDLING.md`.
