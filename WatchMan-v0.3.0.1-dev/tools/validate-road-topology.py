#!/usr/bin/env python3
"""Independently rescan OSM input against topology staging, not routing acceptance."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import sqlite3
import sys

import osmium


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            result.update(block)
    return result.hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


class Audit(osmium.SimpleHandler):
    def __init__(self, db):
        super().__init__()
        self.db = db
        self.found = Counter()
        self.highways = Counter()
        self.pending_nodes = []
        self.scanned_nodes = 0
        self.next_progress = 100000

    def node(self, entity):
        valid = entity.location.valid()
        self.pending_nodes.append((entity.id, entity.location.x if valid else None,
                                   entity.location.y if valid else None, entity.version,
                                   str(entity.timestamp), dict(entity.tags)))
        if len(self.pending_nodes) >= 400:
            self.flush_nodes()

    def flush_nodes(self):
        if not self.pending_nodes:
            return
        ids = [row[0] for row in self.pending_nodes]
        marks = ",".join("?" for _ in ids)
        actual = {r[0]: r[1:] for r in self.db.execute(
            f"SELECT id,lon_e7,lat_e7,version,stamp,tags FROM nodes WHERE id IN ({marks})", ids)}
        referenced = {r[0] for r in self.db.execute(
            f"SELECT node_id FROM way_nodes WHERE node_id IN ({marks}) UNION "
            f"SELECT member_id FROM relation_members WHERE member_type='n' AND member_id IN ({marks})", ids + ids)}
        for node_id, lon, lat, version, stamp, raw in self.pending_nodes:
            row = actual.get(node_id)
            require((row is not None) == (node_id in referenced or "barrier" in raw), f"Node retention mismatch {node_id}")
            if row is None:
                continue
            require(lon is not None and lat is not None, f"Invalid selected source node {node_id}")
            require(row[:4] == (lon, lat, version, stamp), f"Node geometry/version mismatch {node_id}")
            require(json.loads(row[4]) == raw, f"Node tags mismatch {node_id}")
            self.found["nodes"] += 1
        self.scanned_nodes += len(self.pending_nodes)
        self.pending_nodes.clear()
        if self.scanned_nodes >= self.next_progress:
            print(f"Audited {self.scanned_nodes} source nodes", file=sys.stderr, flush=True)
            self.next_progress += 100000

    def way(self, entity):
        self.flush_nodes()
        row = self.db.execute("SELECT version,stamp,kind,tags FROM ways WHERE id=?", (entity.id,)).fetchone()
        raw = dict(entity.tags)
        kind = ("highway" if "highway" in raw else "ferry" if raw.get("route") == "ferry"
                else "barrier" if "barrier" in raw else "relation-member")
        related = self.db.execute("SELECT 1 FROM relation_members WHERE member_type='w' AND member_id=? LIMIT 1",
                                  (entity.id,)).fetchone() is not None
        selected = kind != "relation-member" or related
        require((row is not None) == selected, f"Way retention mismatch {entity.id}")
        if row is None:
            return
        require(row[:3] == (entity.version, str(entity.timestamp), kind), f"Way metadata mismatch {entity.id}")
        require(json.loads(row[3]) == raw, f"Way tags mismatch {entity.id}")
        expected = [(ordinal, ref.ref) for ordinal, ref in enumerate(entity.nodes)]
        actual = self.db.execute("SELECT ordinal,node_id FROM way_nodes WHERE way_id=? ORDER BY ordinal", (entity.id,)).fetchall()
        require(actual == expected, f"Way node order mismatch {entity.id}")
        expected_segments = [(a[0], a[1], b[1]) for a, b in zip(expected, expected[1:]) if a[1] != b[1]]
        actual_segments = self.db.execute("SELECT ordinal,from_node,to_node FROM segments WHERE way_id=? ORDER BY ordinal", (entity.id,)).fetchall()
        require(actual_segments == expected_segments, f"Raw segment identity mismatch {entity.id}")
        self.found["ways"] += 1
        self.found["way_nodes"] += len(expected)
        self.found["segments"] += len(expected_segments)
        if "highway" in raw:
            self.highways[raw["highway"]] += 1

    def relation(self, entity):
        self.flush_nodes()
        row = self.db.execute("SELECT version,stamp,tags FROM relations WHERE id=?", (entity.id,)).fetchone()
        raw = dict(entity.tags)
        selected = (raw.get("type") == "restriction" or any(key.startswith("restriction") for key in raw)
                    or raw.get("route") == "ferry" or "barrier" in raw)
        require((row is not None) == selected, f"Relation retention mismatch {entity.id}")
        if row is None:
            return
        require(row[:2] == (entity.version, str(entity.timestamp)), f"Relation metadata mismatch {entity.id}")
        require(json.loads(row[2]) == raw, f"Relation tags mismatch {entity.id}")
        expected = [(i, m.type, m.ref, m.role) for i, m in enumerate(entity.members)]
        actual = self.db.execute("SELECT ordinal,member_type,member_id,role FROM relation_members WHERE relation_id=? ORDER BY ordinal",
                                 (entity.id,)).fetchall()
        require(actual == expected, f"Relation member order mismatch {entity.id}")
        self.found["relations"] += 1
        self.found["relation_members"] += len(expected)


def validate(source, directory):
    source, directory = Path(source).resolve(), Path(directory).resolve()
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    require(not (directory / "failure.json").exists(), "Failed extraction cannot be audited as successful")
    require(manifest["schema"] == 1 and manifest["routing_ready"] is False and
            manifest["live_setup_completion_credit"] == 0, "Staging scope/credit mismatch")
    require(source.name == manifest["input"]["file"] and source.stat().st_size == manifest["input"]["bytes"], "Input identity mismatch")
    require(digest(source) == manifest["input"]["sha256"], "Input hash mismatch")
    require(manifest["database"]["file"] == "topology.sqlite", "Unexpected database filename")
    database = directory / "topology.sqlite"
    require(database.stat().st_size == manifest["database"]["bytes"] and
            digest(database) == manifest["database"]["sha256"], "Database hash/size mismatch")
    db = sqlite3.connect(database.as_uri() + "?mode=ro", uri=True)
    try:
        db.execute("PRAGMA query_only=ON")
        db.execute("PRAGMA cache_size=-32768")
        db.execute("PRAGMA temp_store=FILE")
        # One read-only snapshot avoids reopening/rechecking the database for every entity.
        db.execute("BEGIN")
        require(db.execute("PRAGMA integrity_check").fetchall() == [("ok",)], "Database integrity failure")
        require(db.execute("PRAGMA foreign_key_check").fetchall() == [], "Foreign key failure")
        for key in ("provenance", "report"):
            stored = json.loads(db.execute("SELECT value FROM metadata WHERE key=?", (key,)).fetchone()[0])
            require(all(manifest.get(k) == v for k, v in stored.items()), "Embedded metadata/manifest mismatch")
        audit = Audit(db)
        pool = osmium.io.ThreadPool(1, 2)
        with osmium.io.Reader(str(source), audit.enabled_for(), thread_pool=pool) as reader:
            osmium.apply(reader, audit)
        audit.flush_nodes()
        counts = {}
        for table in ("nodes", "ways", "way_nodes", "relations", "relation_members", "segments"):
            counts[table] = db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
            require(audit.found[table] == counts[table] == manifest["counts"][table], f"Inventory mismatch {table}")
        require(db.execute("SELECT COUNT(*) FROM way_nodes wn LEFT JOIN nodes n ON wn.node_id=n.id WHERE n.id IS NULL").fetchone()[0] == 0,
                "Missing geometry node")
        for kind, table in (("n", "nodes"), ("w", "ways"), ("r", "relations")):
            count = db.execute(f"SELECT COUNT(*) FROM relation_members m LEFT JOIN {table} t ON t.id=m.member_id "
                               "WHERE m.member_type=? AND t.id IS NULL", (kind,)).fetchone()[0]
            require(count == manifest["unresolved_relation_member_occurrences"][kind], "External reference count mismatch")
        require(digest(source) == manifest["input"]["sha256"] and digest(database) == manifest["database"]["sha256"],
                "Input or database changed during independent audit")
        return {"result": "PASS", "scope": "Raw source-to-topology rescan; NOT legal routing acceptance",
                "counts": counts, "highway_way_counts": dict(sorted(audit.highways.items())),
                "database_sha256": manifest["database"]["sha256"]}
    finally:
        db.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(validate(args.input, args.directory), sort_keys=True, indent=2))
