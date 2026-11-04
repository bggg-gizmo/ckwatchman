#!/usr/bin/env python3
"""Build deterministic WatchMan camera/sensor seed assets from raw OSM PBF extracts."""
from __future__ import annotations

import argparse
import collections
import datetime as dt
import gzip
import hashlib
import json
import os
import pathlib
import re
import sys
from typing import Any, Iterable

try:
    import osmium
except ImportError as exc:
    raise SystemExit("Install project-local dependency 'osmium' before running this builder") from exc

SCHEMA_VERSION = 1
GENERATOR_VERSION = "watchman-national-seed/1"
REGION_ROWS = """
alabama AL Alabama
alaska AK Alaska
arizona AZ Arizona
arkansas AR Arkansas
california CA California
colorado CO Colorado
connecticut CT Connecticut
delaware DE Delaware
florida FL Florida
georgia GA Georgia
hawaii HI Hawaii
idaho ID Idaho
illinois IL Illinois
indiana IN Indiana
iowa IA Iowa
kansas KS Kansas
kentucky KY Kentucky
louisiana LA Louisiana
maine ME Maine
maryland MD Maryland
massachusetts MA Massachusetts
michigan MI Michigan
minnesota MN Minnesota
mississippi MS Mississippi
missouri MO Missouri
montana MT Montana
nebraska NE Nebraska
nevada NV Nevada
new-hampshire NH New_Hampshire
new-jersey NJ New_Jersey
new-mexico NM New_Mexico
new-york NY New_York
north-carolina NC North_Carolina
north-dakota ND North_Dakota
ohio OH Ohio
oklahoma OK Oklahoma
oregon OR Oregon
pennsylvania PA Pennsylvania
rhode-island RI Rhode_Island
south-carolina SC South_Carolina
south-dakota SD South_Dakota
tennessee TN Tennessee
texas TX Texas
utah UT Utah
vermont VT Vermont
virginia VA Virginia
washington WA Washington
west-virginia WV West_Virginia
wisconsin WI Wisconsin
wyoming WY Wyoming
district-of-columbia DC District_of_Columbia
puerto-rico PR Puerto_Rico
us-virgin-islands VI U.S._Virgin_Islands
"""
REGIONS = {
    row.split()[0]: (row.split()[1], row.split(maxsplit=2)[2].replace("_", " "))
    for row in REGION_ROWS.strip().splitlines()
}
STATE_REGIONS = {name for name, (code, _) in REGIONS.items() if code not in {"DC", "PR", "VI"}}
PBF_NAME = re.compile(r"^(?P<region>[a-z0-9-]+?)(?:-\d{6}|-latest)?\.osm\.pbf$", re.I)
PLATE_VENDORS = (
    "flock safety", "leonardo", "elsag", "vigilant", "motorola solutions",
    "rekor", "openalpr", "genetec", "autovu", "platesmart",
    "adaptive recognition", "survision", "neology",
)
ACOUSTIC = ("raven", "shotspotter", "soundthinking", "gunshot", "acoustic sensor")
RF = ("signaltrace", "signal trace", "eoc plus", "electronic signature", "rf detection", "rf sensor")
CONTEXT = ("camera", "surveillance", "monitoring", "sensor", "detector", "traffic", "public safety")


def canonical(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def source_date(path: pathlib.Path) -> str:
    match = re.search(r"-(\d{2})(\d{2})(\d{2})\.osm\.pbf$", path.name, re.I)
    if match:
        year, month, day = (int(value) for value in match.groups())
        return dt.date(2000 + year, month, day).isoformat()
    try:
        reader = osmium.io.Reader(str(path))
        try:
            value = reader.header().get("osmosis_replication_timestamp")
            if value:
                return str(value)[:10]
        finally:
            reader.close()
    except Exception:
        pass
    return dt.datetime.fromtimestamp(path.stat().st_mtime, tz=dt.timezone.utc).date().isoformat()


def tags_dict(tags: Any) -> dict[str, str]:
    return {str(key): str(value) for key, value in tags if str(key).strip() and str(value).strip()}


def blob(tags: dict[str, str]) -> str:
    return " ".join(f"{key}={value}" for key, value in sorted(tags.items())).lower()


def is_candidate(tags: dict[str, str]) -> bool:
    highway = tags.get("highway", "").lower()
    if highway in {"traffic_signals", "speed_camera", "toll_gantry"}:
        return True
    if tags.get("barrier", "").lower() == "toll_booth":
        return True
    if tags.get("enforcement", "").strip():
        return True
    if tags.get("surveillance", "").strip() or tags.get("surveillance:type", "").strip():
        return True
    if tags.get("camera:type", "").strip():
        return True
    if tags.get("man_made", "").lower() in {"surveillance", "monitoring_station"}:
        return True
    if any(key.startswith("monitoring:traffic") for key in tags):
        return True
    text = blob(tags)
    if any(term in text for term in PLATE_VENDORS):
        return True
    return (
        any(term in text for term in ACOUSTIC + RF)
        and any(context in text for context in CONTEXT)
    )


def classify(tags: dict[str, str]) -> tuple[str, str, str, int]:
    text = blob(tags)
    enforcement = tags.get("enforcement", "").lower()
    highway = tags.get("highway", "").lower()
    surveillance = tags.get("surveillance", "").lower()
    surveillance_type = tags.get("surveillance:type", "").lower()
    if any(term in text for term in RF):
        return "RF_SENSOR", "RF_SIGNAL_SENSOR", "signaltrace_eoc_plus", 99
    if any(term in text for term in ACOUSTIC):
        return "ACOUSTIC", "ACOUSTIC_SENSOR", "acoustic_detection", 99
    explicit_plate = (
        surveillance_type in {"alpr", "anpr", "lpr"}
        or surveillance in {"alpr", "anpr", "lpr"}
        or any(term in text for term in ("plate reader", "license plate", "number plate"))
    )
    vendor_plate = any(term in text for term in PLATE_VENDORS) and any(
        term in text for term in ("alpr", "anpr", "lpr", "plate", "falcon", "mph")
    )
    if "flock safety" in text and (explicit_plate or "falcon" in text or "plate" in text):
        return "FLOCK", "ALPR", "flock_alpr", 98
    if explicit_plate or vendor_plate:
        return "ALPR", "ALPR", "plate_recognition", 97
    if enforcement in {"traffic_signals", "red_light", "redlight"}:
        return "RED_LIGHT", "RED_LIGHT", "red_light_enforcement", 96
    if highway == "speed_camera" or enforcement in {"maxspeed", "speed", "average_speed"}:
        return "SPEED", "SPEED", "speed_enforcement", 96
    if highway == "toll_gantry" or tags.get("barrier", "").lower() == "toll_booth" or "toll" in text:
        return "TOLL", "TOLL", "toll_equipment", 92
    if any(term in text for term in ("rwis", "weather", "meteorological")):
        return "SENSOR", "WEATHER_SENSOR", "rwis_weather", 90
    if (
        tags.get("man_made", "").lower() == "monitoring_station"
        or any(key.startswith("monitoring:traffic") for key in tags)
        or any(term in text for term in ("wavetronix", "vehicle detector", "traffic sensor"))
    ):
        return "SENSOR", "TRAFFIC_SENSOR", "traffic_monitoring", 91
    if highway == "traffic_signals":
        return "TRAFFIC", "TRAFFIC_SIGNAL", "traffic_signal", 78
    if surveillance or surveillance_type or tags.get("man_made", "").lower() == "surveillance":
        if "traffic" in text or tags.get("camera:type", "").lower() == "traffic":
            return "TRAFFIC", "TRAFFIC_CAMERA", "traffic_camera", 89
        return "SURVEILLANCE", "SURVEILLANCE", "surveillance_camera", 86
    if any(term in text for term in ("sensor", "detector", "monitoring")):
        return "SENSOR", "GENERIC_SENSOR", "generic_sensor", 76
    return "OTHER", "UNKNOWN_RELEVANT_DEVICE", "unknown_relevant", 65


def node_point(obj: Any) -> tuple[float, float] | None:
    return (float(obj.location.lat), float(obj.location.lon)) if obj.location.valid() else None


def way_point(obj: Any) -> tuple[float, float] | None:
    points = []
    for ref in obj.nodes:
        try:
            if ref.location.valid():
                points.append((float(ref.location.lat), float(ref.location.lon)))
        except osmium.InvalidLocationError:
            pass
    if not points:
        return None
    return sum(p[0] for p in points) / len(points), sum(p[1] for p in points) / len(points)


class Handler(osmium.SimpleHandler):
    def __init__(self, region: str, date: str, input_name: str, input_sha: str) -> None:
        super().__init__()
        self.region = region
        self.date = date
        self.input_name = input_name
        self.input_sha = input_sha
        self.records: dict[str, dict[str, Any]] = {}
        self.skipped_geometry = 0

    def node(self, obj: Any) -> None:
        self.handle("node", obj, node_point(obj))

    def way(self, obj: Any) -> None:
        self.handle("way", obj, way_point(obj))

    def relation(self, obj: Any) -> None:
        if is_candidate(tags_dict(obj.tags)):
            self.skipped_geometry += 1

    def handle(self, kind: str, obj: Any, point: tuple[float, float] | None) -> None:
        tags = tags_dict(obj.tags)
        if not is_candidate(tags):
            return
        if point is None:
            self.skipped_geometry += 1
            return
        lat, lon = point
        if not (-90 <= lat <= 90 and -180 <= lon <= 180):
            self.skipped_geometry += 1
            return
        camera_type, broad_type, subtype, confidence = classify(tags)
        upstream = f"{kind}/{int(obj.id)}"
        self.records[upstream] = {
            "schema_version": SCHEMA_VERSION,
            "id": "wm-osm-" + hashlib.sha256(upstream.encode()).hexdigest()[:24],
            "provider_id": "openstreetmap_pbf",
            "upstream_id": upstream,
            "jurisdiction": self.region,
            "jurisdiction_code": REGIONS[self.region][0],
            "latitude": round(lat, 7),
            "longitude": round(lon, 7),
            "camera_type": camera_type,
            "broad_type": broad_type,
            "subtype": subtype,
            "name": tags.get("name"),
            "road": tags.get("road") or tags.get("street") or tags.get("location"),
            "direction": tags.get("direction") or tags.get("camera:direction"),
            "manufacturer": tags.get("manufacturer") or tags.get("brand"),
            "model": tags.get("model") or tags.get("product"),
            "operator": tags.get("operator"),
            "source_date": self.date,
            "confidence": confidence,
            "source_url": f"https://www.openstreetmap.org/{upstream}",
            "provenance": {
                "provider": "OpenStreetMap",
                "license": "ODbL-1.0",
                "input_file": self.input_name,
                "input_sha256": self.input_sha,
            },
            "tags": dict(sorted(tags.items())),
        }


def gzip_lines(path: pathlib.Path, lines: Iterable[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + ".tmp")
    with temp.open("wb") as raw:
        with gzip.GzipFile(filename="", fileobj=raw, mode="wb", compresslevel=9, mtime=0) as zipped:
            for line in lines:
                zipped.write((line + "\n").encode("utf-8"))
    os.replace(temp, path)


def read_shard(path: pathlib.Path) -> Iterable[dict[str, Any]]:
    with gzip.open(path, "rt", encoding="utf-8") as stream:
        for number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            record = json.loads(line)
            if record.get("schema_version") != SCHEMA_VERSION:
                raise ValueError(f"{path}:{number}: invalid schema")
            if not record.get("jurisdiction") or not record.get("upstream_id"):
                raise ValueError(f"{path}:{number}: missing identity")
            yield record


def build_shard(path: pathlib.Path, region: str, shard_dir: pathlib.Path) -> dict[str, Any]:
    input_sha = sha256(path)
    date = source_date(path)
    handler = Handler(region, date, path.name, input_sha)
    native_filters = (
        osmium.filter.TagFilter(
            ("highway", "traffic_signals"), ("highway", "speed_camera"),
            ("highway", "toll_gantry"), ("barrier", "toll_booth"),
            ("man_made", "surveillance"), ("man_made", "monitoring_station"),
        ),
        osmium.filter.KeyFilter(
            "enforcement", "surveillance", "surveillance:type", "surveillance:zone",
            "camera:type", "monitoring:traffic", "manufacturer", "brand",
            "product", "model", "device", "sensor",
        ),
    )
    for native_filter in native_filters:
        processor = (
            osmium.FileProcessor(str(path))
            .with_locations("flex_mem")
            .with_filter(native_filter)
        )
        for obj in processor:
            kind = obj.type_str()
            if kind == "n":
                handler.handle("node", obj, node_point(obj))
            elif kind == "w":
                handler.handle("way", obj, way_point(obj))
            elif kind == "r":
                handler.relation(obj)
    rows = [handler.records[key] for key in sorted(handler.records)]
    shard = shard_dir / f"{region}.jsonl.gz"
    gzip_lines(shard, (canonical(row) for row in rows))
    return {
        "jurisdiction": region,
        "input_file": path.name,
        "input_size": path.stat().st_size,
        "input_sha256": input_sha,
        "source_date": date,
        "record_count": len(rows),
        "counts_by_broad_type": dict(sorted(collections.Counter(row["broad_type"] for row in rows).items())),
        "skipped_without_geometry": handler.skipped_geometry,
        "shard_file": shard.name,
        "shard_sha256": sha256(shard),
        "status": "ok",
    }



def inventory_shards(
    shard_dir: pathlib.Path,
    inputs: dict[str, pathlib.Path],
) -> list[dict[str, Any]]:
    inventory = []
    for shard in sorted(shard_dir.glob("*.jsonl.gz")):
        region = shard.name.removesuffix(".jsonl.gz")
        count = 0
        broad_types: collections.Counter[str] = collections.Counter()
        first: dict[str, Any] | None = None
        for record in read_shard(shard):
            if first is None:
                first = record
            count += 1
            broad_types[record.get("broad_type", "UNKNOWN_RELEVANT_DEVICE")] += 1
        input_path = inputs.get(region)
        provenance = (first or {}).get("provenance", {})
        inventory.append({
            "jurisdiction": region,
            "input_file": provenance.get("input_file") or (input_path.name if input_path else None),
            "input_size": input_path.stat().st_size if input_path else None,
            "input_sha256": provenance.get("input_sha256"),
            "source_date": (first or {}).get("source_date"),
            "record_count": count,
            "counts_by_broad_type": dict(sorted(broad_types.items())),
            "shard_file": shard.name,
            "shard_sha256": sha256(shard),
            "status": "ok",
        })
    return inventory

def merge(shard_dir: pathlib.Path, seed: pathlib.Path, selected: set[str] | None) -> dict[str, Any]:
    rows: dict[tuple[str, str], dict[str, Any]] = {}
    for shard in sorted(shard_dir.glob("*.jsonl.gz")):
        region = shard.name.removesuffix(".jsonl.gz")
        if selected and region not in selected:
            continue
        for row in read_shard(shard):
            key = row["jurisdiction"], row["upstream_id"]
            prior = rows.get(key)
            if prior is None or canonical(row) < canonical(prior):
                rows[key] = row
    ordered = [rows[key] for key in sorted(rows)]
    gzip_lines(seed, (canonical(row) for row in ordered))
    return {
        "record_count": len(ordered),
        "counts_by_jurisdiction": dict(sorted(collections.Counter(row["jurisdiction"] for row in ordered).items())),
        "counts_by_broad_type": dict(sorted(collections.Counter(row["broad_type"] for row in ordered).items())),
        "seed_sha256": sha256(seed),
        "seed_size": seed.stat().st_size,
    }


def discover(input_dir: pathlib.Path) -> dict[str, pathlib.Path]:
    found = {}
    for path in sorted(input_dir.glob("*.osm.pbf")):
        match = PBF_NAME.match(path.name)
        if match and match.group("region").lower() in REGIONS:
            region = match.group("region").lower()
            if region not in found or path.name > found[region].name:
                found[region] = path
    return found


def write_manifest(path: pathlib.Path, value: dict[str, Any]) -> None:
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
    parser.add_argument("--strict-50-states", action="store_true")
    args = parser.parse_args()

    output = args.output_dir.resolve()
    shard_dir = output / "shards"
    shard_dir.mkdir(parents=True, exist_ok=True)
    inputs = discover(args.input_dir.resolve())
    selected = set(args.region) if args.region else None
    missing = sorted(STATE_REGIONS - set(inputs))
    if args.strict_50_states and missing:
        print("Missing state PBF inputs: " + ", ".join(missing), file=sys.stderr)
        return 2

    results = []
    failures = []
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
                print(f"[{region}] {result['record_count']} records", flush=True)
            except Exception as exc:
                failures.append({"jurisdiction": region, "error": f"{type(exc).__name__}: {exc}"})
                print(f"[{region}] failed: {exc}", file=sys.stderr, flush=True)

    seed = output / "national_seed_v028.jsonl.gz"
    merged = merge(shard_dir, seed, selected)
    manifest = {
        "schema_version": SCHEMA_VERSION,
        "generator": GENERATOR_VERSION,
        "deterministic": True,
        "gzip_mtime": 0,
        "seed_file": seed.name,
        **merged,
        "jurisdictions_in_seed": sorted(merged["counts_by_jurisdiction"]),
        "shards": inventory_shards(shard_dir, inputs),
        "shards_processed_this_run": sorted(results, key=lambda row: row["jurisdiction"]),
        "failures": sorted(failures, key=lambda row: row["jurisdiction"]),
        "missing_50_state_inputs": missing,
        "attribution": "Contains information from OpenStreetMap, available under the ODbL.",
        "completion_credit": 0,
    }
    write_manifest(output / "national_seed_v028.manifest.json", manifest)
    print(canonical({"records": merged["record_count"], "sha256": merged["seed_sha256"], "failures": len(failures)}))
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
