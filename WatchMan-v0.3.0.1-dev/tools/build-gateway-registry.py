#!/usr/bin/env python3
"""Create WatchMan's deterministic 50-state public-source registry from the verified seed manifest."""
from __future__ import annotations

import argparse
import importlib.util
import json
import pathlib

OVERPASS = "https://overpass-api.de/api/interpreter"
LEADS = {
    "alabama": ["Alabama traffic-counter data (endpoint/schema/license revalidation required)"],
    "california": ["Caltrans statewide CCTV ArcGIS/FeatureServer", "Caltrans CWWP2 district CCTV", "Caltrans CWWP2 RWIS"],
    "georgia": ["GDOT statewide CCTV GIS/ArcGIS (endpoint/schema revalidation required)"],
    "idaho": ["ITD public CCTV GIS", "ITD public RWIS GIS"],
    "louisiana": ["LADOTD regional camera GIS (regional scope; do not label statewide)"],
    "maryland": ["MD iMAP Traffic Cameras FeatureServer"],
    "minnesota": ["Minnesota traffic sensors (endpoint/schema/license revalidation required)"],
    "missouri": ["Missouri public cameras (endpoint/schema/license revalidation required)"],
    "nevada": ["NDOT ITS/ATM Existing Devices GIS"],
    "north-carolina": ["North Carolina ITS devices (endpoint/schema/license revalidation required)"],
    "north-dakota": ["North Dakota camera/RWIS files (usage restrictions require review)"],
    "utah": ["UDOT public camera KML", "UDOT public RWIS KML"],
}


def load_seed_module(tool_dir: pathlib.Path) -> object:
    path = tool_dir / "build-national-seed.py"
    spec = importlib.util.spec_from_file_location("watchman_seed_builder", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Unable to load {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def geofabrik_endpoint(region: str) -> str:
    return f"https://download.geofabrik.de/north-america/us/{region}-latest.osm.pbf"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seed-manifest", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--verified-date", required=True)
    args = parser.parse_args()

    seed = load_seed_module(pathlib.Path(__file__).resolve().parent)
    manifest = json.loads(args.seed_manifest.read_text(encoding="utf-8"))
    shards = {row["jurisdiction"]: row for row in manifest["shards"]}
    jurisdictions = []
    for region, (code, name) in sorted(seed.REGIONS.items(), key=lambda item: item[1][0]):
        shard = shards.get(region)
        if shard is None:
            raise SystemExit(f"Seed manifest is missing {region}")
        sources = [
            {
                "provider": "OpenStreetMap via Geofabrik",
                "endpoint": geofabrik_endpoint(region),
                "authentication": "none",
                "data_type": "raw OSM PBF",
                "geographic_coverage": "statewide/jurisdiction-wide extract",
                "update_style": "public rolling extract; build-time snapshot",
                "provenance": {
                    "input_file": shard["input_file"],
                    "input_sha256": shard["input_sha256"],
                    "source_date": shard["source_date"],
                    "shard_sha256": shard["shard_sha256"],
                },
                "redistribution_rights": "ODbL-1.0; attribution and database obligations apply",
                "runtime_or_bundle_policy": "filtered normalized derivative may be bundled; raw PBF is never bundled",
                "normalization_adapter": "tools/build-national-seed.py and tools/build-offline-road-map.py",
                "last_verification_date": args.verified_date,
                "health_status": "local input, hash, shard, and deterministic merge verified",
                "account_or_key_required": False,
            },
            {
                "provider": "OpenStreetMap Overpass / SafeTOC-compatible discovery",
                "endpoint": OVERPASS,
                "authentication": "none",
                "data_type": "live ALPR and traffic-device OSM objects; additive surveillance/enforcement/sensor/toll families",
                "geographic_coverage": "county/locale cells across the jurisdiction",
                "update_style": "runtime source-scoped snapshot refresh",
                "provenance": "OpenStreetMap object identity, tags, retrieval source, and timestamp retained",
                "redistribution_rights": "ODbL-1.0; attribution required",
                "runtime_or_bundle_policy": "runtime/cache only; independent source snapshots; failures retain prior success",
                "normalization_adapter": "NationalCameraBootstrapProvider",
                "last_verification_date": args.verified_date,
                "health_status": "source contract and host build verified; physical-device acceptance pending",
                "account_or_key_required": False,
            },
        ]
        official = [{
            "provider": lead,
            "endpoint": None,
            "authentication": "no-login lead; must be revalidated before production adapter activation",
            "data_type": "official camera/ITS/RWIS/sensor lead",
            "geographic_coverage": "as stated by provider; regional leads must remain regional",
            "update_style": "unknown until endpoint/schema revalidation",
            "provenance": "handoff research lead",
            "redistribution_rights": "not cleared for APK bundling",
            "runtime_or_bundle_policy": "documented lead only; no completion credit; do not bundle",
            "normalization_adapter": None,
            "last_verification_date": None,
            "health_status": "planned/incomplete research; not a production dependency",
            "account_or_key_required": False,
        } for lead in LEADS.get(region, [])]
        jurisdictions.append({
            "jurisdiction": name,
            "jurisdiction_code": code,
            "slug": region,
            "is_state": region in seed.STATE_REGIONS,
            "baseline_sources": sources,
            "official_public_gateway_leads": official,
            "official_gateway_research_status": "lead_needs_revalidation" if official else "no production official no-login adapter independently verified; OSM baseline and SafeTOC remain active",
        })

    states = [row for row in jurisdictions if row["is_state"]]
    if len(states) != 50:
        raise SystemExit(f"Expected 50 states, found {len(states)}")
    registry = {
        "schema_version": 1,
        "release": "v0.2.9",
        "last_verification_date": args.verified_date,
        "authoritative_scope": "50 states plus DC, Puerto Rico, and U.S. Virgin Islands",
        "state_count": len(states),
        "jurisdiction_count": len(jurisdictions),
        "mandatory_account_or_key_sources": 0,
        "seed_completion_credit": 0,
        "seed_manifest_sha256": manifest["seed_sha256"],
        "research_caveat": "OSM/PBF and SafeTOC provide complete national baseline coverage. Official state-specific leads remain additive and are never represented as production-ready until endpoint, schema, scope, licensing, and health are independently revalidated.",
        "jurisdictions": jurisdictions,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(registry, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps({"states": len(states), "jurisdictions": len(jurisdictions), "mandatory_keys": 0}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
