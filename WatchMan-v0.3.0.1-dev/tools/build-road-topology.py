#!/usr/bin/env python3
"""Bounded, lossless road-topology staging from an OSM extract; NOT a router."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import importlib.metadata
import json
import math
from pathlib import Path
import platform
import re
import shutil
import sqlite3
import sys

try:
    import osmium
except ImportError as exc:
    raise SystemExit("Add the existing project toolchain/python-packages to PYTHONPATH (osmium required)") from exc

GENERATOR = "watchman-road-topology/1"
GIB = 1024 ** 3
BATCH = 400
SCHEMA = """
CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID;
CREATE TABLE relations(id INTEGER PRIMARY KEY, version INTEGER NOT NULL, stamp TEXT NOT NULL,
                       tags TEXT NOT NULL);
CREATE TABLE relation_members(relation_id INTEGER NOT NULL REFERENCES relations(id),
 ordinal INTEGER NOT NULL, member_type TEXT NOT NULL CHECK(member_type IN ('n','w','r')),
 member_id INTEGER NOT NULL, role TEXT NOT NULL, PRIMARY KEY(relation_id,ordinal)) WITHOUT ROWID;
CREATE INDEX member_lookup ON relation_members(member_type,member_id);
CREATE TABLE ways(id INTEGER PRIMARY KEY, version INTEGER NOT NULL, stamp TEXT NOT NULL,
 kind TEXT NOT NULL, tags TEXT NOT NULL);
CREATE TABLE needed_nodes(id INTEGER PRIMARY KEY);
CREATE TABLE nodes(id INTEGER PRIMARY KEY, lon_e7 INTEGER NOT NULL, lat_e7 INTEGER NOT NULL,
 version INTEGER NOT NULL, stamp TEXT NOT NULL, tags TEXT NOT NULL);
CREATE TABLE way_nodes(way_id INTEGER NOT NULL REFERENCES ways(id), ordinal INTEGER NOT NULL,
 node_id INTEGER NOT NULL, PRIMARY KEY(way_id,ordinal)) WITHOUT ROWID;
CREATE INDEX way_node_lookup ON way_nodes(node_id);
CREATE TABLE segments(way_id INTEGER NOT NULL REFERENCES ways(id), ordinal INTEGER NOT NULL,
 from_node INTEGER NOT NULL REFERENCES nodes(id), to_node INTEGER NOT NULL REFERENCES nodes(id),
 length_mm INTEGER NOT NULL CHECK(length_mm >= 0), PRIMARY KEY(way_id,ordinal)) WITHOUT ROWID;
CREATE INDEX segment_from ON segments(from_node);
CREATE INDEX segment_to ON segments(to_node);
"""


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def input_date(path):
    match = re.search(r"-(\d{2})(\d{2})(\d{2})\.osm\.pbf$", path.name, re.I)
    if match:
        return {"value": dt.date(2000 + int(match[1]), int(match[2]), int(match[3])).isoformat(),
                "basis": "extract filename; not a per-object verification date"}
    with osmium.io.Reader(str(path), osmium.osm.osm_entity_bits.NOTHING,
                          thread_pool=osmium.io.ThreadPool(1, 2)) as reader:
        stamp = reader.header().get("osmosis_replication_timestamp")
    return {"value": str(stamp) if stamp else None,
            "basis": "OSM replication header" if stamp else "unavailable"}


class Budget:
    def __init__(self, directory, reserve_bytes, max_bytes):
        self.directory = directory
        self.reserve_bytes = reserve_bytes
        self.max_bytes = max_bytes

    def check(self, additional_bytes=0):
        if shutil.disk_usage(self.directory).free - additional_bytes < self.reserve_bytes:
            raise RuntimeError("Disk reserve reached; topology build stopped")
        used = sum(p.stat().st_size for p in self.directory.iterdir() if p.is_file())
        if used + additional_bytes > self.max_bytes:
            raise RuntimeError("Topology staging byte budget exceeded; build stopped")


class Store:
    def __init__(self, db, budget):
        self.db = db
        self.budget = budget
        self.events = 0

    def tick(self, amount=1):
        self.events += amount
        if self.events >= 4000:
            self.db.commit()
            self.budget.check()
            self.events = 0

    def finish(self):
        self.db.commit()
        self.budget.check()


def tags_of(entity):
    return dict(entity.tags)


def is_constraint(tags):
    return (tags.get("type") == "restriction" or any(k.startswith("restriction") for k in tags)
            or tags.get("route") == "ferry" or "barrier" in tags)


class Relations(osmium.SimpleHandler):
    def __init__(self, store):
        super().__init__()
        self.store = store

    def relation(self, entity):
        self.store.tick()
        tags = tags_of(entity)
        if not is_constraint(tags):
            return
        db = self.store.db
        db.execute("INSERT INTO relations VALUES(?,?,?,?)",
                   (entity.id, entity.version, str(entity.timestamp), canonical(tags)))
        for ordinal, member in enumerate(entity.members):
            db.execute("INSERT INTO relation_members VALUES(?,?,?,?,?)",
                       (entity.id, ordinal, member.type, member.ref, member.role))
            if member.type == "n":
                db.execute("INSERT OR IGNORE INTO needed_nodes VALUES(?)", (member.ref,))
            self.store.tick()


class Ways(osmium.SimpleHandler):
    def __init__(self, store):
        super().__init__()
        self.store = store

    def way(self, entity):
        self.store.tick()
        tags = tags_of(entity)
        db = self.store.db
        kind = ("highway" if "highway" in tags else "ferry" if tags.get("route") == "ferry"
                else "barrier" if "barrier" in tags else None)
        if kind is None:
            if not db.execute("SELECT 1 FROM relation_members WHERE member_type='w' AND member_id=? LIMIT 1",
                              (entity.id,)).fetchone():
                return
            kind = "relation-member"
        db.execute("INSERT INTO ways VALUES(?,?,?,?,?)",
                   (entity.id, entity.version, str(entity.timestamp), kind, canonical(tags)))
        for ordinal, node in enumerate(entity.nodes):
            db.execute("INSERT INTO way_nodes VALUES(?,?,?)", (entity.id, ordinal, node.ref))
            db.execute("INSERT OR IGNORE INTO needed_nodes VALUES(?)", (node.ref,))
            self.store.tick()


class Nodes(osmium.SimpleHandler):
    def __init__(self, store):
        super().__init__()
        self.store = store
        self.pending = []

    def node(self, entity):
        # Copy ephemeral osmium values only within this callback. No national RAM location index.
        valid = entity.location.valid()
        self.pending.append((entity.id, entity.location.x if valid else None,
                             entity.location.y if valid else None, entity.version,
                             str(entity.timestamp), tags_of(entity)))
        if len(self.pending) >= BATCH:
            self.flush()

    def flush(self):
        if not self.pending:
            return
        db = self.store.db
        ids = [row[0] for row in self.pending]
        needed = {r[0] for r in db.execute("SELECT id FROM needed_nodes WHERE id IN (" +
                                          ",".join("?" for _ in ids) + ")", ids)}
        for node_id, lon, lat, version, stamp, tags in self.pending:
            if node_id not in needed and "barrier" not in tags:
                continue
            if lon is None or lat is None:
                raise ValueError(f"Selected node {node_id} has no valid location")
            db.execute("INSERT INTO nodes VALUES(?,?,?,?,?,?)",
                       (node_id, lon, lat, version, stamp, canonical(tags)))
        self.store.tick(len(self.pending))
        self.pending.clear()


def scan(path, handler):
    pool = osmium.io.ThreadPool(1, 2)
    with osmium.io.Reader(str(path), handler.enabled_for(), thread_pool=pool) as reader:
        osmium.apply(reader, handler)
    if isinstance(handler, Nodes):
        handler.flush()
    handler.store.finish()


def length_mm(a_lon, a_lat, b_lon, b_lat):
    lat1, lat2 = math.radians(a_lat / 1e7), math.radians(b_lat / 1e7)
    dlat = lat2 - lat1
    dlon = math.radians((b_lon - a_lon) / 1e7)
    h = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return int(math.floor(2 * 6371008.8 * math.asin(math.sqrt(min(1.0, max(0.0, h)))) * 1000 + 0.5))


def make_segments(store):
    db = store.db
    missing = db.execute("SELECT wn.way_id,wn.node_id FROM way_nodes wn LEFT JOIN nodes n ON n.id=wn.node_id "
                         "WHERE n.id IS NULL LIMIT 1").fetchone()
    if missing:
        raise ValueError(f"Missing geometry node {missing[1]} in selected way {missing[0]}")
    cursor = db.execute("SELECT wn.way_id,wn.ordinal,n.id,n.lon_e7,n.lat_e7 FROM way_nodes wn "
                        "JOIN nodes n ON n.id=wn.node_id ORDER BY wn.way_id,wn.ordinal")
    previous = None
    for row in cursor:
        if previous is not None and previous[0] == row[0] and previous[2] != row[2]:
            db.execute("INSERT INTO segments VALUES(?,?,?,?,?)",
                       (row[0], previous[1], previous[2], row[2], length_mm(*previous[3:], *row[3:])))
        previous = row
        store.tick()
    store.finish()


def describe(db):
    counts = {table: db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
              for table in ("nodes", "ways", "way_nodes", "relations", "relation_members", "segments")}
    external = {}
    for kind, table in (("n", "nodes"), ("w", "ways"), ("r", "relations")):
        external[kind] = db.execute(f"SELECT COUNT(*) FROM relation_members m LEFT JOIN {table} t "
                                   "ON t.id=m.member_id WHERE m.member_type=? AND t.id IS NULL", (kind,)).fetchone()[0]
    degenerate = db.execute("SELECT COUNT(*) FROM ways w WHERE NOT EXISTS "
                            "(SELECT 1 FROM segments s WHERE s.way_id=w.id)").fetchone()[0]
    bounds = db.execute("SELECT MIN(lon_e7),MIN(lat_e7),MAX(lon_e7),MAX(lat_e7) FROM nodes").fetchone()
    return {"counts": counts, "unresolved_relation_member_occurrences": external,
            "ways_without_segments": degenerate, "bounds_e7": list(bounds)}


def build(source, output, jurisdiction, reserve_bytes=2 * GIB, max_bytes=4 * GIB):
    source, output = Path(source).resolve(), Path(output).resolve()
    if not source.is_file() or not (source.name.endswith(".osm.pbf") or source.suffix == ".osm"):
        raise ValueError("Input must be an existing complete .osm.pbf or test .osm file")
    if not re.fullmatch(r"[A-Z]{2}", jurisdiction):
        raise ValueError("Jurisdiction must be its two-letter code")
    if reserve_bytes < 0 or max_bytes <= 0:
        raise ValueError("Invalid disk budget")
    # Fail on existing output before touching it. Every invocation has its own evidence directory.
    output.mkdir(parents=True, exist_ok=False)
    budget = Budget(output, reserve_bytes, max_bytes)
    db = None
    working = output / "topology.working.sqlite"
    try:
        budget.check()
        before = source.stat()
        provenance = {"schema": 1, "generator": GENERATOR, "jurisdiction": jurisdiction,
                      "input": {"file": source.name, "bytes": before.st_size, "sha256": sha256(source),
                                "source_date": input_date(source)},
                      "generator_sha256": sha256(__file__),
                      "runtime": {"python": platform.python_version(), "osmium": importlib.metadata.version("osmium"),
                                  "sqlite": sqlite3.sqlite_version},
                      "license": "OpenStreetMap contributors; ODbL-1.0; derived database obligations apply",
                      "attribution_url": "https://www.openstreetmap.org/copyright",
                      "routing_ready": False, "live_setup_completion_credit": 0,
                      "scope": "Raw topology and constraints; no legal arcs, routing costs or route search",
                      "geometry": "OSM global IDs; unsimplified E7; consecutive-node geodesic length in millimetres",
                      "resources": {"parser_workers": 1, "parser_queue": 2, "node_batch": BATCH,
                                    "sqlite_cache_kib": 32768}}
        db = sqlite3.connect(working)
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("PRAGMA cache_size=-32768")
        db.execute("PRAGMA temp_store=FILE")
        db.execute("PRAGMA journal_mode=DELETE")
        db.execute("PRAGMA synchronous=FULL")
        # SQLite also enforces the database byte ceiling before later budget checkpoints.
        page_size = db.execute("PRAGMA page_size").fetchone()[0]
        db.execute(f"PRAGMA max_page_count={max(1, max_bytes // page_size)}")
        db.executescript(SCHEMA)
        db.execute("INSERT INTO metadata VALUES('provenance',?)", (canonical(provenance),))
        store = Store(db, budget)
        for name, handler in (("relations", Relations(store)), ("ways", Ways(store)), ("nodes", Nodes(store))):
            print(f"Extracting {name}: {source.name}", flush=True)
            scan(source, handler)
        print("Validating geometry and constructing raw segments", flush=True)
        make_segments(store)
        report = describe(db)
        if not report["counts"]["ways"] or not report["counts"]["segments"]:
            raise ValueError("No usable topology segments in input")
        if db.execute("PRAGMA foreign_key_check").fetchone() is not None:
            raise ValueError("Foreign key validation failed")
        if db.execute("PRAGMA integrity_check").fetchall() != [("ok",)]:
            raise ValueError("SQLite integrity check failed")
        after = source.stat()
        if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns) or sha256(source) != provenance["input"]["sha256"]:
            raise ValueError("Input changed during extraction")
        db.execute("INSERT INTO metadata VALUES('report',?)", (canonical(report),))
        db.commit()
        db.close()
        db = None
        budget.check()
        final = output / "topology.sqlite"
        working.rename(final)
        manifest = {**provenance, **report, "database": {"file": final.name, "bytes": final.stat().st_size,
                                                       "sha256": sha256(final)}}
        manifest_text = canonical(manifest) + "\n"
        budget.check(len(manifest_text.encode("utf-8")))
        # Manifest is the final success marker; incomplete directories are never consumable.
        with (output / "manifest.working.json").open("x", encoding="utf-8", newline="\n") as stream:
            stream.write(manifest_text)
        (output / "manifest.working.json").rename(output / "manifest.json")
        print(canonical(report), flush=True)
        return manifest
    except Exception as exc:
        if db is not None:
            db.close()
        with (output / "failure.json").open("x", encoding="utf-8", newline="\n") as stream:
            stream.write(canonical({"routing_ready": False, "error": type(exc).__name__, "message": str(exc)}) + "\n")
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="New non-existing staging directory")
    parser.add_argument("--jurisdiction", required=True)
    parser.add_argument("--reserve-gib", type=float, default=2)
    parser.add_argument("--max-output-gib", type=float, default=4)
    args = parser.parse_args()
    build(args.input, args.output, args.jurisdiction, int(args.reserve_gib * GIB), int(args.max_output_gib * GIB))


if __name__ == "__main__":
    main()
