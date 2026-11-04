#!/usr/bin/env python3
"""Build a deterministic compact offline major-road basemap from local OSM PBF extracts."""
from __future__ import annotations

import argparse
import datetime as dt
import gzip
import hashlib
import heapq
import importlib.util
import json
import math
import os
import pathlib
import re
from typing import Iterable

import osmium

SCHEMA_VERSION = 1
GENERATOR = "watchman-offline-roads/1"
INDEX_ZOOM = 8
ROAD_RANK = {
    "motorway": 1,
    "motorway_link": 1,
    "trunk": 2,
    "trunk_link": 2,
    "primary": 3,
    "primary_link": 3,
    "secondary": 4,
    "secondary_link": 4,
}


def load_seed_module() -> object:
    path = pathlib.Path(__file__).with_name("build-national-seed.py")
    spec = importlib.util.spec_from_file_location("watchman_seed_builder", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Unable to load {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


SEED = load_seed_module()
REGIONS = SEED.REGIONS
STATE_REGIONS = SEED.STATE_REGIONS


def canonical(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def gzip_lines(path: pathlib.Path, lines: Iterable[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + ".tmp")
    with temp.open("wb") as raw:
        with gzip.GzipFile(filename="", fileobj=raw, mode="wb", compresslevel=9, mtime=0) as zipped:
            for line in lines:
                zipped.write((line + "\n").encode("utf-8"))
    os.replace(temp, path)


def web_tile(lat: float, lon: float, zoom: int = INDEX_ZOOM) -> tuple[int, int]:
    n = 1 << zoom
    x = int((lon + 180.0) / 360.0 * n)
    clamped = max(-85.05112878, min(85.05112878, lat))
    lat_rad = math.radians(clamped)
    y = int((1.0 - math.asinh(math.tan(lat_rad)) / math.pi) / 2.0 * n)
    return max(0, min(n - 1, x)), max(0, min(n - 1, y))


def distance_to_segment_m(point: tuple[float, float], a: tuple[float, float], b: tuple[float, float]) -> float:
    lat0 = math.radians((point[0] + a[0] + b[0]) / 3.0)
    scale_x = 111_320.0 * max(0.1, math.cos(lat0))
    px, py = point[1] * scale_x, point[0] * 110_574.0
    ax, ay = a[1] * scale_x, a[0] * 110_574.0
    bx, by = b[1] * scale_x, b[0] * 110_574.0
    dx, dy = bx - ax, by - ay
    if dx == 0.0 and dy == 0.0:
        return math.hypot(px - ax, py - ay)
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def simplify(points: list[tuple[float, float]], tolerance_m: float = 18.0) -> list[tuple[float, float]]:
    if len(points) <= 2:
        return points
    keep = {0, len(points) - 1}
    stack = [(0, len(points) - 1)]
    while stack:
        start, end = stack.pop()
        best_index = -1
        best_distance = tolerance_m
        for index in range(start + 1, end):
            distance = distance_to_segment_m(points[index], points[start], points[end])
            if distance > best_distance:
                best_distance = distance
                best_index = index
        if best_index >= 0:
            keep.add(best_index)
            stack.append((start, best_index))
            stack.append((best_index, end))
    return [points[index] for index in sorted(keep)]


def clean_label(value: str) -> str:
    return re.sub(r"[\t\r\n|;]+", " ", value).strip()[:96]


def encode_points(points: list[tuple[float, float]]) -> str:
    return ";".join(f"{round(lat * 1_000_000)},{round(lon * 1_000_000)}" for lat, lon in points)


def way_rows(obj: object, rank: int, label: str) -> list[str]:
    points: list[tuple[float, float]] = []
    for node in obj.nodes:
        try:
            lat, lon = float(node.lat), float(node.lon)
        except Exception:
            continue
        if -90.0 <= lat <= 90.0 and -180.0 <= lon <= 180.0:
            point = (lat, lon)
            if not points or point != points[-1]:
                points.append(point)
    points = simplify(points)
    if len(points) < 2:
        return []

    rows: list[str] = []
    current_tile: tuple[int, int] | None = None
    current_points: list[tuple[float, float]] = []

    def flush() -> None:
        nonlocal current_points
        if current_tile is None or len(current_points) < 2:
            current_points = []
            return
        x, y = current_tile
        key = f"{INDEX_ZOOM:03d}/{x:03d}/{y:03d}"
        rows.append(f"{key}\t{rank}\t{label}\t{encode_points(current_points)}")
        current_points = []

    for a, b in zip(points, points[1:]):
        tile = web_tile((a[0] + b[0]) / 2.0, (a[1] + b[1]) / 2.0)
        if tile != current_tile:
            flush()
            current_tile = tile
            current_points = [a, b]
        elif current_points[-1] == a:
            current_points.append(b)
        else:
            flush()
            current_points = [a, b]
    flush()
    return rows


def build_shard(path: pathlib.Path, region: str, shard_dir: pathlib.Path) -> dict[str, object]:
    rows: list[str] = []
    processor = (
        osmium.FileProcessor(str(path))
        .with_locations("flex_mem")
        .with_filter(osmium.filter.TagFilter(*[("highway", value) for value in ROAD_RANK]))
    )
    ways = 0
    for obj in processor:
        if obj.type_str() != "w":
            continue
        highway = str(obj.tags.get("highway", "")).lower()
        rank = ROAD_RANK.get(highway)
        if rank is None:
            continue
        label = clean_label(str(obj.tags.get("ref") or obj.tags.get("name") or highway.replace("_", " ")))
        encoded = way_rows(obj, rank, label)
        if encoded:
            ways += 1
            rows.extend(encoded)
    rows.sort()
    shard = shard_dir / f"{region}.wmr.gz"
    gzip_lines(shard, rows)
    return {
        "jurisdiction": region,
        "input_file": path.name,
        "input_size": path.stat().st_size,
        "input_sha256": sha256(path),
        "source_date": SEED.source_date(path),
        "road_way_count": ways,
        "encoded_polyline_count": len(rows),
        "shard_file": shard.name,
        "shard_sha256": sha256(shard),
        "status": "ok",
    }


def read_lines(path: pathlib.Path):
    with gzip.open(path, "rt", encoding="utf-8") as stream:
        yield from stream



def map_shard_inventory(
    shard_dir: pathlib.Path,
    seed_manifest: pathlib.Path | None,
) -> tuple[list[dict[str, object]], str | None]:
    seed_json = {}
    seed_rows = {}
    if seed_manifest is not None:
        seed_json = json.loads(seed_manifest.read_text(encoding="utf-8"))
        seed_rows = {row["jurisdiction"]: row for row in seed_json.get("shards", [])}
    inventory = []
    for shard in sorted(shard_dir.glob("*.wmr.gz")):
        region = shard.name.removesuffix(".wmr.gz")
        polylines = 0
        tile_keys = set()
        with gzip.open(shard, "rt", encoding="utf-8") as stream:
            for line in stream:
                if not line.strip():
                    continue
                polylines += 1
                tile_keys.add(line.split("\t", 1)[0])
        source = seed_rows.get(region, {})
        inventory.append({
            "jurisdiction": region,
            "input_file": source.get("input_file"),
            "input_size": source.get("input_size"),
            "input_sha256": source.get("input_sha256"),
            "source_date": source.get("source_date"),
            "encoded_polyline_count": polylines,
            "source_tile_count": len(tile_keys),
            "shard_file": shard.name,
            "shard_sha256": sha256(shard),
            "status": "ok" if source else "map shard valid; input provenance manifest not supplied",
        })
    return inventory, seed_json.get("seed_sha256")

def merge_tiles(shard_dir: pathlib.Path, tile_dir: pathlib.Path) -> tuple[list[dict[str, object]], int]:
    shards = sorted(shard_dir.glob("*.wmr.gz"))
    streams = [read_lines(path) for path in shards]
    merged = heapq.merge(*streams)
    tiles: list[dict[str, object]] = []
    current_key: str | None = None
    current_rows: list[str] = []
    previous: str | None = None
    total = 0

    def flush() -> None:
        nonlocal current_rows, total
        if current_key is None:
            return
        path = tile_dir / f"{current_key}.wmr.gz"
        gzip_lines(path, current_rows)
        tiles.append({
            "path": path.relative_to(tile_dir.parent).as_posix(),
            "sha256": sha256(path),
            "bytes": path.stat().st_size,
            "polyline_count": len(current_rows),
        })
        total += len(current_rows)
        current_rows = []

    for raw in merged:
        line = raw.rstrip("\n")
        if not line or line == previous:
            continue
        previous = line
        key, payload = line.split("\t", 1)
        if key != current_key:
            flush()
            current_key = key
        current_rows.append(payload)
    flush()
    return tiles, total


def write_json(path: pathlib.Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n"
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(encoded, encoding="utf-8", newline="\n")
    os.replace(temp, path)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input-dir", type=pathlib.Path, required=True)
    parser.add_argument("--output-dir", type=pathlib.Path, required=True)
    parser.add_argument("--region", action="append", choices=sorted(REGIONS))
    parser.add_argument("--merge-only", action="store_true")
    parser.add_argument("--seed-manifest", type=pathlib.Path)
    parser.add_argument("--strict-50-states", action="store_true")
    args = parser.parse_args()

    output = args.output_dir.resolve()
    shard_dir = output / "shards"
    tile_dir = output / "offline_roads"
    shard_dir.mkdir(parents=True, exist_ok=True)
    tile_dir.mkdir(parents=True, exist_ok=True)
    inputs = SEED.discover(args.input_dir.resolve())
    selected = set(args.region) if args.region else None
    missing = sorted(STATE_REGIONS - set(inputs))
    if args.strict_50_states and missing:
        print("Missing state PBF inputs: " + ", ".join(missing), flush=True)
        return 2

    results: list[dict[str, object]] = []
    failures: list[dict[str, str]] = []
    if not args.merge_only:
        for region in sorted(selected or inputs):
            path = inputs.get(region)
            if path is None:
                failures.append({"jurisdiction": region, "error": "PBF input missing"})
                continue
            print(f"[{region}] reading {path.name}", flush=True)
            try:
                result = build_shard(path, region, shard_dir)
                results.append(result)
                print(f"[{region}] {result['road_way_count']} ways / {result['encoded_polyline_count']} polylines", flush=True)
            except Exception as exc:
                failures.append({"jurisdiction": region, "error": f"{type(exc).__name__}: {exc}"})
                print(f"[{region}] failed: {exc}", flush=True)

    shard_inventory, seed_manifest_sha256 = map_shard_inventory(shard_dir, args.seed_manifest)
    tiles, polyline_count = merge_tiles(shard_dir, tile_dir)
    manifest = {
        "schema_version": SCHEMA_VERSION,
        "generator": GENERATOR,
        "generated_utc": "deterministic-from-input-manifests",
        "deterministic": True,
        "gzip_mtime": 0,
        "index_zoom": INDEX_ZOOM,
        "road_classes": ROAD_RANK,
        "jurisdictions_in_pack": [row["jurisdiction"] for row in shard_inventory],
        "shards": shard_inventory,
        "seed_manifest_sha256": seed_manifest_sha256,
        "jurisdictions_processed_this_run": results,
        "missing_50_state_inputs": missing,
        "failures": failures,
        "tile_count": len(tiles),
        "polyline_count": polyline_count,
        "tiles": tiles,
        "attribution": "Contains information from OpenStreetMap, available under the ODbL.",
        "public_raster_tiles_prefetched": False,
    }
    write_json(output / "offline_roads.manifest.json", manifest)
    print(canonical({
        "tiles": len(tiles),
        "polylines": polyline_count,
        "failures": len(failures),
    }))
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
