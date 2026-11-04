#!/usr/bin/env python3
"""Compact indexed topology for the legal graph adapter; candidate arcs are NOT permissions."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3

FORMAT = "watchman-compact-road-graph/1"
GIB = 1024 ** 3
SCHEMA = """
CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL) WITHOUT ROWID;
CREATE TABLE tags(id INTEGER PRIMARY KEY,raw TEXT NOT NULL UNIQUE);
CREATE TABLE stamps(id INTEGER PRIMARY KEY,raw TEXT NOT NULL UNIQUE);
CREATE TABLE nodes(nid INTEGER PRIMARY KEY,osm_id INTEGER NOT NULL UNIQUE CHECK(osm_id>0),
 lon_e7 INTEGER NOT NULL,lat_e7 INTEGER NOT NULL,version INTEGER NOT NULL,
 stamp_id INTEGER NOT NULL REFERENCES stamps(id),tag_id INTEGER NOT NULL REFERENCES tags(id));
CREATE TABLE ways(wid INTEGER PRIMARY KEY,osm_id INTEGER NOT NULL UNIQUE CHECK(osm_id>0),
 version INTEGER NOT NULL,stamp_id INTEGER NOT NULL REFERENCES stamps(id),
 kind TEXT NOT NULL,tag_id INTEGER NOT NULL REFERENCES tags(id),
 node_count INTEGER NOT NULL,node_refs BLOB NOT NULL);
CREATE TABLE segments(sid INTEGER PRIMARY KEY,wid INTEGER NOT NULL REFERENCES ways(wid),
 ordinal INTEGER NOT NULL,from_nid INTEGER NOT NULL REFERENCES nodes(nid),
 to_nid INTEGER NOT NULL REFERENCES nodes(nid),length_mm INTEGER NOT NULL CHECK(length_mm>=0),
 UNIQUE(wid,ordinal),CHECK(from_nid<>to_nid));
CREATE INDEX outgoing_forward ON segments(from_nid);
CREATE INDEX outgoing_backward ON segments(to_nid);
CREATE TABLE relations(id INTEGER PRIMARY KEY,version INTEGER NOT NULL,
 stamp_id INTEGER NOT NULL REFERENCES stamps(id),tag_id INTEGER NOT NULL REFERENCES tags(id));
CREATE TABLE members(relation_id INTEGER NOT NULL REFERENCES relations(id),ordinal INTEGER NOT NULL,
 member_type TEXT NOT NULL CHECK(member_type IN ('n','w','r')),member_id INTEGER NOT NULL,
 role TEXT NOT NULL,PRIMARY KEY(relation_id,ordinal)) WITHOUT ROWID;
CREATE TABLE relation_scope(nid INTEGER NOT NULL REFERENCES nodes(nid),
 relation_id INTEGER NOT NULL REFERENCES relations(id),PRIMARY KEY(nid,relation_id)) WITHOUT ROWID;
CREATE INDEX scope_relation ON relation_scope(relation_id);
CREATE TABLE unresolved_members(relation_id INTEGER NOT NULL REFERENCES relations(id),ordinal INTEGER NOT NULL,
 member_type TEXT NOT NULL,member_id INTEGER NOT NULL,PRIMARY KEY(relation_id,ordinal)) WITHOUT ROWID;
CREATE TABLE unscoped_relations(relation_id INTEGER PRIMARY KEY REFERENCES relations(id));
"""


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def digest(path):
    value = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def packed_refs(ids):
    """Delta + ZigZag + unsigned base128; preserves repeats and reverse/non-monotonic IDs."""
    result = bytearray()
    last = 0
    count = 0
    for value in ids:
        if not 0 < value <= 2 ** 31 - 1:
            raise ValueError("Local node identity outside format bound")
        count += 1
        if count > 100_000:
            raise ValueError("Way node count exceeds bounded format")
        delta = value - last
        encoded = 2 * delta if delta >= 0 else -2 * delta - 1
        while encoded >= 128:
            result.append((encoded & 127) | 128)
            encoded >>= 7
        result.append(encoded)
        last = value
    return count, bytes(result)


def build(topology, output, expected, reserve_bytes=2 * GIB, max_bytes=GIB):
    topology = Path(topology).resolve(strict=True)
    output = Path(output).resolve()
    if reserve_bytes < 0 or max_bytes < 4096:
        raise ValueError("Invalid storage budget")
    if digest(topology) != expected.lower():
        raise ValueError("Input is not the expected independently audited topology")
    output.mkdir(parents=True, exist_ok=False)
    db = None

    def budget():
        if shutil.disk_usage(output).free < reserve_bytes:
            raise RuntimeError("Free-space reserve reached")
        if sum(p.stat().st_size for p in output.iterdir() if p.is_file()) > max_bytes:
            raise RuntimeError("Compact graph output budget exceeded")

    try:
        budget()
        db = sqlite3.connect(output / "graph.working.sqlite", uri=True)
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("PRAGMA cache_size=-16384")
        db.execute("PRAGMA temp_store=FILE")
        db.execute("PRAGMA synchronous=FULL")
        db.execute("PRAGMA journal_mode=DELETE")
        db.execute(f"PRAGMA max_page_count={max_bytes // 4096}")
        db.execute("PRAGMA user_version=1")
        db.execute("ATTACH DATABASE ? AS source", (topology.as_uri() + "?mode=ro",))
        db.execute("PRAGMA source.cache_size=-8192")
        if db.execute("PRAGMA source.integrity_check").fetchall() != [("ok",)]:
            raise ValueError("Input SQLite integrity check failed")
        provenance = json.loads(db.execute("SELECT value FROM source.metadata WHERE key='provenance'").fetchone()[0])
        db.executescript(SCHEMA)
        db.execute("BEGIN")
        operations = 0

        def progress():
            nonlocal operations
            operations += 1
            if operations % 100 == 0:
                try:
                    budget()
                except RuntimeError:
                    return 1
            return 0

        db.set_progress_handler(progress, 10_000)
        print("Interning source tags and timestamps", flush=True)
        db.execute("INSERT INTO tags(raw) SELECT tags FROM source.nodes UNION SELECT tags FROM source.ways "
                   "UNION SELECT tags FROM source.relations ORDER BY 1")
        for raw, in db.execute("SELECT raw FROM tags ORDER BY id"):
            if len(raw.encode('utf-8')) > 1024 ** 2:
                raise ValueError("Source tag set exceeds1MiB")
            parsed = json.loads(raw)
            if not isinstance(parsed, dict) or not all(isinstance(k, str) and isinstance(v, str) for k, v in parsed.items()):
                raise ValueError("Source tags must be a string map")
        db.execute("INSERT INTO stamps(raw) SELECT stamp FROM source.nodes UNION SELECT stamp FROM source.ways "
                   "UNION SELECT stamp FROM source.relations ORDER BY 1")
        db.execute("INSERT INTO nodes(osm_id,lon_e7,lat_e7,version,stamp_id,tag_id) "
                   "SELECT n.id,n.lon_e7,n.lat_e7,n.version,s.id,t.id FROM source.nodes n "
                   "JOIN stamps s ON s.raw=n.stamp JOIN tags t ON t.raw=n.tags ORDER BY n.id")
        if db.execute("SELECT COUNT(*) FROM nodes").fetchone()[0] > 2 ** 31 - 1:
            raise ValueError("Node count exceeds local-ID format")
        print("Packing unsimplified original-node geometry", flush=True)
        for osm_id, version, stamp_id, kind, tag_id in db.execute(
            "SELECT w.id,w.version,s.id,w.kind,t.id FROM source.ways w JOIN stamps s ON s.raw=w.stamp "
            "JOIN tags t ON t.raw=w.tags ORDER BY w.id"):
            rows = db.execute("SELECT wn.ordinal,n.nid FROM source.way_nodes wn LEFT JOIN nodes n ON n.osm_id=wn.node_id "
                              "WHERE wn.way_id=? ORDER BY wn.ordinal", (osm_id,))

            def refs():
                for ordinal, (actual, nid) in enumerate(rows):
                    if actual != ordinal or nid is None:
                        raise ValueError(f"Incomplete/invalid node geometry for way{osm_id}")
                    yield nid

            count, blob = packed_refs(refs())
            db.execute("INSERT INTO ways(osm_id,version,stamp_id,kind,tag_id,node_count,node_refs) VALUES(?,?,?,?,?,?,?)",
                       (osm_id, version, stamp_id, kind, tag_id, count, blob))
        print("Indexing candidate arcs and full relation coverage", flush=True)
        db.execute("INSERT INTO segments(wid,ordinal,from_nid,to_nid,length_mm) "
                   "SELECT w.wid,s.ordinal,a.nid,b.nid,s.length_mm FROM source.segments s "
                   "JOIN ways w ON w.osm_id=s.way_id JOIN nodes a ON a.osm_id=s.from_node "
                   "JOIN nodes b ON b.osm_id=s.to_node ORDER BY s.way_id,s.ordinal")
        db.execute("INSERT INTO relations SELECT r.id,r.version,s.id,t.id FROM source.relations r "
                   "JOIN stamps s ON s.raw=r.stamp JOIN tags t ON t.raw=r.tags ORDER BY r.id")
        db.execute("INSERT INTO members SELECT * FROM source.relation_members ORDER BY relation_id,ordinal")
        # ALL members, not only successfully resolved restrictions. Scope includes other departures
        # at a via-way junction, which are not necessarily themselves member arcs.
        db.execute("INSERT INTO relation_scope SELECT n.nid,m.relation_id FROM source.relation_members m "
                   "JOIN nodes n ON m.member_type='n' AND n.osm_id=m.member_id UNION "
                   "SELECT n.nid,m.relation_id FROM source.relation_members m "
                   "JOIN source.way_nodes wn ON m.member_type='w' AND wn.way_id=m.member_id "
                   "JOIN nodes n ON n.osm_id=wn.node_id ORDER BY 1,2")
        for kind, table, column in (("n", "nodes", "osm_id"), ("w", "ways", "osm_id"), ("r", "relations", "id")):
            db.execute(f"INSERT INTO unresolved_members SELECT m.relation_id,m.ordinal,m.member_type,m.member_id "
                       f"FROM members m LEFT JOIN {table} t ON t.{column}=m.member_id "
                       "WHERE m.member_type=? AND t." + column + " IS NULL ORDER BY m.relation_id,m.ordinal", (kind,))
        db.execute("INSERT INTO unscoped_relations SELECT id FROM relations r WHERE NOT EXISTS "
                   "(SELECT 1 FROM relation_scope s WHERE s.relation_id=r.id) ORDER BY id")
        counts = {table: db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] for table in
                  ("nodes", "ways", "segments", "relations", "members", "tags", "stamps", "relation_scope", "unresolved_members", "unscoped_relations")}
        for target, source in (("nodes", "nodes"), ("ways", "ways"), ("segments", "segments"),
                               ("relations", "relations"), ("members", "relation_members")):
            if counts[target] != db.execute(f"SELECT COUNT(*) FROM source.{source}").fetchone()[0]:
                raise ValueError(f"Incomplete source retention: {target}")
        if not counts['segments'] or counts['segments'] > (2 ** 63 - 1) // 2:
            raise ValueError("Missing/excessive candidate arcs")
        report = {"format": FORMAT, "routing_ready": False, "live_setup_completion_credit": 0,
                  "source_topology_sha256": expected.lower(), "source_provenance": provenance,
                  "generator_sha256": digest(__file__), "sqlite_version": sqlite3.sqlite_version,
                  "counts": counts, "candidate_directed_arcs": counts['segments'] * 2,
                  "arc_identity": "forward=2*sid-1,backward=2*sid; shard/revision-local, not cross-shard IDs",
                  "geometry": "original global identities/E7; local-ID delta ZigZag base128 node refs",
                  "permissions": "none inferred; all candidates require legal policy and unresolved-constraint checks",
                  "cache_kib": {"output": 16384, "input": 8192},
                  "unscoped_policy": "must remain a global unresolved guard until scoped/resolved; never ignore"}
        db.execute("INSERT INTO metadata VALUES('manifest',?)", (canonical(report),))
        if db.execute("PRAGMA foreign_key_check").fetchone() is not None:
            raise ValueError("Compact graph foreign-key validation failed")
        if db.execute("PRAGMA integrity_check").fetchall() != [("ok",)]:
            raise ValueError("Compact graph integrity check failed")
        db.commit()
        db.close()
        db = None
        if digest(topology) != expected.lower():
            raise ValueError("Input changed during compaction")
        budget()
        final = output / "graph.sqlite"
        (output / "graph.working.sqlite").rename(final)
        report['database'] = {"file": final.name, "bytes": final.stat().st_size, "sha256": digest(final)}
        with (output / "manifest.working.json").open('x', encoding='utf-8', newline='\n') as stream:
            stream.write(canonical(report) + '\n')
        budget()
        (output / "manifest.working.json").rename(output / "manifest.json")
        print(canonical({"counts": counts, "bytes": report['database']['bytes']}), flush=True)
        return report
    except Exception as failure:
        if db is not None:
            db.close()
        with (output / 'failure.json').open('x', encoding='utf-8') as stream:
            stream.write(canonical({"routing_ready": False, "error": type(failure).__name__, "message": str(failure)}) + '\n')
        raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--topology', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--expected-sha256', required=True)
    parser.add_argument('--reserve-gib', type=float, default=2)
    parser.add_argument('--max-output-gib', type=float, default=1)
    args = parser.parse_args()
    build(args.topology, args.output, args.expected_sha256, int(args.reserve_gib * GIB), int(args.max_output_gib * GIB))
