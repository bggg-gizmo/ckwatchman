#!/usr/bin/env python3
"""Validate WatchMan's packaged national seed and offline road assets end to end."""
from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import zipfile


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    args = parser.parse_args()

    with zipfile.ZipFile(args.apk) as apk:
        corrupt = apk.testzip()
        if corrupt is not None:
            raise SystemExit(f"ZIP CRC failure: {corrupt}")

        seed_manifest = json.loads(apk.read("assets/national_seed_v028.manifest.json"))
        seed_bytes = apk.read("assets/national_seed_v028.jsonl.gzipdata")
        if digest(seed_bytes) != seed_manifest["seed_sha256"]:
            raise SystemExit("Packaged national seed SHA-256 mismatch")
        seed_count = 0
        prior_key = None
        with gzip.GzipFile(fileobj=io.BytesIO(seed_bytes), mode="rb") as stream:
            for raw in stream:
                record = json.loads(raw)
                key = record["jurisdiction"], record["upstream_id"]
                if prior_key is not None and key <= prior_key:
                    raise SystemExit("National seed ordering/identity is not strict")
                prior_key = key
                seed_count += 1
        if seed_count != seed_manifest["record_count"]:
            raise SystemExit(f"National seed count mismatch: {seed_count}")

        roads_manifest = json.loads(apk.read("assets/offline_roads.manifest.json"))
        if roads_manifest["failures"] or roads_manifest["missing_50_state_inputs"]:
            raise SystemExit("Offline road manifest contains failures or missing states")
        if roads_manifest["public_raster_tiles_prefetched"]:
            raise SystemExit("Offline pack incorrectly reports public raster prefetch")
        tile_lines = 0
        for tile in roads_manifest["tiles"]:
            source_path = tile["path"]
            asset_path = "assets/" + source_path.removesuffix(".gz") + ".gzipdata"
            tile_bytes = apk.read(asset_path)
            if digest(tile_bytes) != tile["sha256"]:
                raise SystemExit(f"Offline tile SHA-256 mismatch: {asset_path}")
            count = 0
            with gzip.GzipFile(fileobj=io.BytesIO(tile_bytes), mode="rb") as stream:
                for raw in stream:
                    fields = raw.decode("utf-8").rstrip("\n").split("\t", 2)
                    if len(fields) != 3 or int(fields[0]) not in range(1, 5) or ";" not in fields[2]:
                        raise SystemExit(f"Invalid offline road row in {asset_path}")
                    count += 1
            if count != tile["polyline_count"]:
                raise SystemExit(f"Offline tile count mismatch: {asset_path}")
            tile_lines += count
        if tile_lines != roads_manifest["polyline_count"]:
            raise SystemExit(f"Offline map aggregate count mismatch: {tile_lines}")

    print(json.dumps({
        "apk_zip_crc": "pass",
        "seed_records": seed_count,
        "seed_sha256": seed_manifest["seed_sha256"],
        "offline_tiles": roads_manifest["tile_count"],
        "offline_polylines": tile_lines,
        "jurisdictions": len(roads_manifest["jurisdictions_in_pack"]),
    }, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
