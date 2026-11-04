#!/usr/bin/env python3
"""Atomic, bounded-safe-point resumability for the existing raw topology extractor.

Does not adopt unmarked partials, build legal arcs or certify routing readiness.
"""
from __future__ import annotations

import argparse
import contextlib
import hashlib
import importlib.util
import itertools
import json
import math
import os
from pathlib import Path
import platform
import re
import sqlite3
import time

SPEC = importlib.util.spec_from_file_location("watchman_raw_topology", Path(__file__).with_name("build-road-topology.py"))
raw = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(raw)
AUDIT_SPEC = importlib.util.spec_from_file_location("watchman_raw_topology_audit", Path(__file__).with_name("validate-road-topology.py"))
audit = importlib.util.module_from_spec(AUDIT_SPEC)
AUDIT_SPEC.loader.exec_module(audit)
FORMAT = "watchman-road-topology-resume/1"
PHASES = ("relations", "ways", "nodes", "segments", "finalize", "audit", "publish")


class Paused(Exception):
    """Only raised after the last entire entity/batch/way was durably committed."""


def checkpoint(phase, ordinal=0, last_id=None):
    return {"format": FORMAT, "phase": phase, "ordinal": ordinal, "last_original_id": last_id}


def require_checkpoint(value):
    if (value.get("format") != FORMAT or value.get("phase") not in PHASES or
            type(value.get("ordinal")) is not int or not 0 <= value["ordinal"] <= 2**63 - 1 or
            (value.get("last_original_id") is not None and type(value["last_original_id"]) is not int) or
            ((value["ordinal"] == 0) != (value["last_original_id"] is None))):
        raise ValueError("Unsupported or invalid committed topology checkpoint")
    return value


@contextlib.contextmanager
def exclusive(directory):
    """OS-owned lock releases on process death; never infer that a PID/file is stale."""
    path = directory / "resume.lock"
    with path.open("a+b") as stream:
        if path.stat().st_size == 0:
            stream.write(b"\0"); stream.flush(); os.fsync(stream.fileno())
        stream.seek(0)
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            try:
                yield
            finally:
                stream.seek(0); msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
            try:
                yield
            finally:
                fcntl.flock(stream, fcntl.LOCK_UN)


class ResumeStore:
    # Unlike legacy Store.tick, this NEVER commits inside a source object.
    def __init__(self, db, budget, saved, deadline, stop_after):
        self.db, self.budget, self.saved = db, budget, saved
        self.deadline, self.stop_after = deadline, stop_after
        self.events = 0
        self.processed = 0

    def tick(self, amount=1):
        self.events += amount

    def finish(self):
        # Legacy handlers call finish only through raw.scan; this controller owns commits.
        raise RuntimeError("Only an atomic resumable safe point may commit")

    def commit(self, phase, ordinal=0, last_id=None):
        saved = checkpoint(phase, ordinal, last_id)
        self.db.execute("UPDATE metadata SET value=? WHERE key='resume_checkpoint'", (raw.canonical(saved),))
        self.db.commit()
        self.saved = saved
        self.events = 0
        try:
            self.budget.check()
        except RuntimeError as exc:
            # The complete entity and its checkpoint are already committed. A
            # storage pause is resumable after the caller provides enough space.
            raise Paused(str(exc)) from exc

    def expired(self):
        return time.monotonic() >= self.deadline or (self.stop_after is not None and self.processed >= self.stop_after)


def scan_phase(source, phase, store):
    cls = {"relations": raw.Relations, "ways": raw.Ways, "nodes": raw.Nodes}[phase]
    delegate = cls(store)
    committed = store.saved["ordinal"]
    last_id = store.saved["last_original_id"]
    state = {"ordinal": 0, "last_id": None}

    def consume(self, entity):
        state["ordinal"] += 1
        state["last_id"] = entity.id
        ordinal = state["ordinal"]
        if ordinal <= committed:
            if ordinal == committed and entity.id != last_id:
                raise ValueError("Committed source ordinal/original ID mismatch")
            if store.expired():
                raise Paused("Replay reached its runtime window; no new progress is implied")
            return
        getattr(delegate, phase[:-1])(entity)
        store.processed += 1
        safe = not isinstance(delegate, raw.Nodes) or not delegate.pending
        if safe and (store.events >= 4000 or store.expired()):
            store.commit(phase, ordinal, entity.id)
            if store.expired():
                raise Paused("Committed complete original entity/node batch")

    handler_type = type("Checkpoint" + phase.title(), (raw.osmium.SimpleHandler,), {phase[:-1]: consume})
    handler = handler_type()
    with raw.osmium.io.Reader(str(source), handler.enabled_for(), thread_pool=raw.osmium.io.ThreadPool(1, 2)) as reader:
        raw.osmium.apply(reader, handler)
    if state["ordinal"] < committed:
        raise ValueError("Committed callback ordinal exceeds the exact source phase")
    if isinstance(delegate, raw.Nodes):
        delegate.flush()
    # EOF is a durable phase boundary, even for an empty source phase.
    next_phase = PHASES[PHASES.index(phase) + 1]
    store.commit(next_phase)
    if store.expired():
        raise Paused("Completed source phase; next phase is durably recorded")


def segments_phase(store):
    db = store.db
    missing = db.execute("SELECT wn.way_id,wn.node_id FROM way_nodes wn LEFT JOIN nodes n ON n.id=wn.node_id "
                         "WHERE n.id IS NULL LIMIT 1").fetchone()
    if missing:
        raise ValueError(f"Missing geometry node {missing[1]} in selected way {missing[0]}")
    count, last = store.saved["ordinal"], store.saved["last_original_id"]
    # Ascending IDs are an explicit SQLite ORDER BY here, never an assumption about the PBF.
    query = "SELECT id FROM ways" + (" WHERE id>?" if last is not None else "") + " ORDER BY id"
    for (way_id,) in db.execute(query, (last,) if last is not None else ()):
        previous = None
        for row in db.execute("SELECT wn.ordinal,n.id,n.lon_e7,n.lat_e7 FROM way_nodes wn "
                              "JOIN nodes n ON n.id=wn.node_id WHERE wn.way_id=? ORDER BY wn.ordinal", (way_id,)):
            if previous is not None and previous[1] != row[1]:
                db.execute("INSERT INTO segments VALUES(?,?,?,?,?)",
                           (way_id, previous[0], previous[1], row[1], raw.length_mm(*previous[2:], *row[2:])))
            previous = row
            store.tick()
        count += 1
        store.processed += 1
        if store.events >= 4000 or store.expired():
            store.commit("segments", count, way_id)
            if store.expired():
                raise Paused("Committed all original segments of the completed source way")
    store.commit("finalize")
    if store.expired():
        raise Paused("Completed original segment phase")


def metadata(db, key):
    row = db.execute("SELECT value FROM metadata WHERE key=?", (key,)).fetchone()
    if row is None or len(row[0]) > 128 * 1024:
        raise ValueError("Missing/oversized resumability metadata: " + key)
    return json.loads(row[0])


def independent_rescan(db, source, binding):
    """Run the unchanged validator's raw-source comparisons BEFORE publication."""
    inspector = audit.Audit(db)
    with raw.osmium.io.Reader(str(source), inspector.enabled_for(),
                              thread_pool=raw.osmium.io.ThreadPool(1, 2)) as reader:
        raw.osmium.apply(reader, inspector)
    inspector.flush_nodes()
    counts = {}
    for table in ("nodes", "ways", "way_nodes", "relations", "relation_members", "segments"):
        counts[table] = db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
        audit.require(inspector.found[table] == counts[table], "Independent raw inventory mismatch: " + table)
    audit.require(raw.sha256(source) == binding["source"]["sha256"], "Input changed during independent rescan")
    audit.require(db.execute("PRAGMA integrity_check").fetchall() == [("ok",)], "Independent integrity failure")
    audit.require(db.execute("PRAGMA foreign_key_check").fetchall() == [], "Independent foreign key failure")
    return {"result": "PASS", "scope": "Raw source retention, tags, geometry identities and inventory; NOT legal routing acceptance",
            "counts": counts, "highway_way_counts": dict(sorted(inspector.highways.items())),
            "source_sha256": binding["source"]["sha256"], "validator_sha256": binding["validator_sha256"]}


def write_evidence(path, value):
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(raw.canonical(value) + "\n")
        stream.flush()
        os.fsync(stream.fileno())


def storage_safe_point(budget, additional_bytes=0):
    try:
        budget.check(additional_bytes)
    except RuntimeError as exc:
        raise Paused(str(exc)) from exc


def require_equal_content(source_db, image_db):
    """Stream all committed rows; never load state geometry into process RAM."""
    schema = "SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name"
    if source_db.execute(schema).fetchall() != image_db.execute(schema).fetchall():
        raise ValueError("Canonical image schema changed")
    ordered = {"metadata": "key", "relations": "id", "relation_members": "relation_id,ordinal",
               "ways": "id", "needed_nodes": "id", "nodes": "id",
               "way_nodes": "way_id,ordinal", "segments": "way_id,ordinal"}
    for table, key in ordered.items():
        query = f"SELECT * FROM {table} ORDER BY {key}"
        absent = object()
        for original, copied in itertools.zip_longest(source_db.execute(query), image_db.execute(query), fillvalue=absent):
            if original != copied:
                raise ValueError("Canonical image changed committed content: " + table)


def run(source, output, jurisdiction, *, resume=False, reserve_bytes=6 * raw.GIB,
        max_bytes=4 * raw.GIB, max_seconds=180.0, stop_after=None):
    """Advance only a newly marked build; pause never means extraction completion.

    max_seconds bounds parsing work between complete-object/batch safe points. Source
    hashes, open recovery, final audit/hash publication and one object can exceed it.
    stop_after is a deterministic tiny-test safe-point injection, not a dataset cap.
    """
    source, output = Path(source).resolve(), Path(output).resolve()
    if not source.is_file() or not (source.name.endswith(".osm.pbf") or source.suffix == ".osm"):
        raise ValueError("Input must be an existing complete .osm.pbf or test .osm file")
    if not re.fullmatch(r"[A-Z]{2}", jurisdiction):
        raise ValueError("Jurisdiction must be its two-letter code")
    if reserve_bytes < 0 or max_bytes <= 0 or not math.isfinite(max_seconds) or max_seconds <= 0:
        raise ValueError("Invalid disk/runtime budget")
    if stop_after is not None and (type(stop_after) is not int or stop_after <= 0):
        raise ValueError("Invalid deterministic pause injection")
    binding = {"format": FORMAT, "schema_sha256": hashlib.sha256(raw.SCHEMA.encode()).hexdigest(),
               "source": {"file": source.name, "bytes": source.stat().st_size, "sha256": raw.sha256(source)},
               "jurisdiction": jurisdiction, "generator_sha256": raw.sha256(raw.__file__),
               "controller_sha256": raw.sha256(__file__),
               "validator_sha256": raw.sha256(audit.__file__),
               "runtime": {"python": platform.python_version(), "osmium": raw.importlib.metadata.version("osmium"),
                           "sqlite": sqlite3.sqlite_version}}
    marker = output / "resume-format.json"
    if resume:
        # Reject legacy partials BEFORE creating a lock or opening SQLite writable.
        if not marker.is_file() or marker.stat().st_size > 128 * 1024:
            raise ValueError("Unmarked partial topology cannot be adopted/resumed")
        if json.loads(marker.read_text(encoding="utf-8")) != binding:
            raise ValueError("Exact source/generator/controller/schema/runtime binding mismatch")
        if (output / "manifest.json").exists() or (output / "failure.json").exists():
            raise ValueError("Completed or terminally failed extraction cannot be resumed")
    else:
        output.mkdir(parents=True, exist_ok=False)
    budget = raw.Budget(output, reserve_bytes, max_bytes)
    budget.check()
    with exclusive(output):
        working = output / "topology.working.sqlite"
        final = output / "topology.sqlite"
        if resume and working.exists() == final.exists():
            raise ValueError("A marked checkpoint must have exactly one working/publication database")
        path = final if resume and final.exists() else working
        if resume and not path.is_file():
            raise ValueError("Marked build has no committed database")
        if resume:
            # A marker alone never authorizes writable SQLite opening. Check the
            # embedded binding/checkpoint through a read-only connection first.
            with contextlib.closing(sqlite3.connect(path.as_uri() + "?mode=ro", uri=True, timeout=0)) as guard:
                guard.execute("PRAGMA query_only=ON")
                if metadata(guard, "resume_binding") != binding:
                    raise ValueError("Embedded exact resumability binding mismatch")
                require_checkpoint(metadata(guard, "resume_checkpoint"))
        db = sqlite3.connect(path, timeout=0)
        try:
            db.execute("PRAGMA foreign_keys=ON")
            db.execute("PRAGMA cache_size=-32768")
            db.execute("PRAGMA temp_store=FILE")
            db.execute("PRAGMA journal_mode=DELETE")
            db.execute("PRAGMA synchronous=FULL")
            page_size = db.execute("PRAGMA page_size").fetchone()[0]
            db.execute(f"PRAGMA max_page_count={max(1, max_bytes // page_size)}")
            if not resume:
                provenance = {"schema": 1, "generator": raw.GENERATOR, "jurisdiction": jurisdiction,
                    "input": {**binding["source"], "source_date": raw.input_date(source)},
                    "generator_sha256": binding["generator_sha256"], "runtime": binding["runtime"],
                    "license": "OpenStreetMap contributors; ODbL-1.0; derived database obligations apply",
                    "attribution_url": "https://www.openstreetmap.org/copyright", "routing_ready": False,
                    "live_setup_completion_credit": 0,
                    "scope": "Raw topology and constraints; no legal arcs, routing costs or route search",
                    "geometry": "OSM global IDs; unsimplified E7; consecutive-node geodesic length in millimetres",
                    "resources": {"parser_workers": 1, "parser_queue": 2, "node_batch": raw.BATCH,
                                  "sqlite_cache_kib": 32768},
                    "resumability": {"format": FORMAT, "controller_sha256": binding["controller_sha256"],
                                     "binding": binding, "publication": "Manifest last after unchanged independent raw-source rescan"}}
                db.executescript(raw.SCHEMA)
                for key, value in (("provenance", provenance), ("resume_binding", binding),
                                   ("resume_checkpoint", checkpoint("relations"))):
                    db.execute("INSERT INTO metadata VALUES(?,?)", (key, raw.canonical(value)))
                db.commit()
                write_evidence(marker, binding)
            else:
                if metadata(db, "resume_binding") != binding:
                    raise ValueError("Embedded exact resumability binding mismatch")
                if db.execute("PRAGMA quick_check").fetchall() != [("ok",)]:
                    raise ValueError("Checkpoint SQLite integrity failure")
                provenance = metadata(db, "provenance")
                if provenance.get("resumability", {}).get("binding") != binding:
                    raise ValueError("Checkpoint provenance/binding mismatch")
            saved = require_checkpoint(metadata(db, "resume_checkpoint"))
            if path == final and saved["phase"] != "publish":
                raise ValueError("Final filename without committed publication phase")
            store = ResumeStore(db, budget, saved, time.monotonic() + max_seconds, stop_after)
            try:
                fresh_audit = False
                while store.saved["phase"] in PHASES[:3]:
                    phase = store.saved["phase"]
                    print(f"Resume {phase}: committedOrdinal={store.saved['ordinal']}", flush=True)
                    scan_phase(source, phase, store)
                if store.saved["phase"] == "segments":
                    segments_phase(store)
                if store.saved["phase"] == "finalize":
                    report = raw.describe(db)
                    if not report["counts"]["ways"] or not report["counts"]["segments"]:
                        raise ValueError("No usable topology segments in input")
                    if db.execute("PRAGMA foreign_key_check").fetchall():
                        raise ValueError("Foreign key validation failed")
                    if db.execute("PRAGMA integrity_check").fetchall() != [("ok",)]:
                        raise ValueError("SQLite integrity check failed")
                    if raw.sha256(source) != binding["source"]["sha256"]:
                        raise ValueError("Input changed during resumable extraction")
                    db.execute("INSERT INTO metadata VALUES('report',?)", (raw.canonical(report),))
                    store.commit("audit")
                if store.saved["phase"] == "audit":
                    raw_audit = independent_rescan(db, source, binding)
                    fresh_audit = True
                    db.execute("INSERT INTO metadata VALUES('independent_raw_audit',?)", (raw.canonical(raw_audit),))
                    store.commit("publish")
                report = metadata(db, "report")
                raw_audit = metadata(db, "independent_raw_audit")
                if (raw_audit.get("result") != "PASS" or raw_audit.get("source_sha256") != binding["source"]["sha256"] or
                        raw_audit.get("validator_sha256") != binding["validator_sha256"] or raw_audit.get("counts") != report["counts"]):
                    raise ValueError("Publication lacks matching committed independent raw audit")
                # Publication can be recovered after rename/before manifest on the next run.
                if raw.sha256(source) != binding["source"]["sha256"]:
                    raise ValueError("Input changed before publication")
                if db.execute("PRAGMA integrity_check").fetchall() != [("ok",)] or db.execute("PRAGMA foreign_key_check").fetchall():
                    raise ValueError("Publication database integrity failure")
                if raw.describe(db) != report:
                    raise ValueError("Committed report differs from publication database")
                if not fresh_audit and independent_rescan(db, source, binding) != raw_audit:
                    raise ValueError("Resumed publication no longer matches independent raw audit")
                # A fresh VACUUM INTO image also resets SQLite's transaction
                # change counters: in-place VACUUM would retain pause-dependent
                # header counters even when all logical rows are identical.
                image_marker = output / "canonical-image.json"
                image_record = None
                if image_marker.exists():
                    if image_marker.stat().st_size > 128 * 1024:
                        raise ValueError("Oversized canonical image record")
                    image_record = json.loads(image_marker.read_text(encoding="utf-8"))
                    if (image_record.get("binding") != binding or type(image_record.get("bytes")) is not int or
                            image_record["bytes"] <= 0 or not re.fullmatch(r"[0-9a-f]{64}", image_record.get("sha256", ""))):
                        raise ValueError("Invalid exact canonical image binding")
                already_canonical = (image_record is not None and path.stat().st_size == image_record["bytes"] and
                                     raw.sha256(path) == image_record["sha256"])
                if path == final and not already_canonical:
                    raise ValueError("Renamed publication database lacks its exact canonical image hash")
                if path == working and not already_canonical:
                    storage_safe_point(budget, path.stat().st_size)
                    canonical = output / "topology.canonical.sqlite"
                    if not canonical.exists():
                        db.execute("VACUUM INTO ?", (str(canonical),))
                    with contextlib.closing(sqlite3.connect(canonical.as_uri() + "?mode=ro", uri=True)) as image:
                        image.execute("PRAGMA query_only=ON")
                        if (image.execute("PRAGMA integrity_check").fetchall() != [("ok",)] or
                                image.execute("PRAGMA foreign_key_check").fetchall() or
                                metadata(image, "resume_binding") != binding or
                                metadata(image, "resume_checkpoint") != checkpoint("publish") or
                                metadata(image, "report") != report or
                                metadata(image, "independent_raw_audit") != raw_audit):
                            raise ValueError("Canonical publication image failed complete independent audit")
                        require_equal_content(db, image)
                    if raw.sha256(source) != binding["source"]["sha256"]:
                        raise ValueError("Input changed during canonical publication audit")
                    candidate_record = {"binding": binding, "bytes": canonical.stat().st_size,
                                        "sha256": raw.sha256(canonical)}
                    if image_record is not None and image_record != candidate_record:
                        raise ValueError("Recovered canonical image differs from committed image record")
                    if image_record is None:
                        pending_image = output / "canonical-image.working.json"
                        if pending_image.exists():
                            if pending_image.stat().st_size > 128 * 1024 or json.loads(pending_image.read_text(encoding="utf-8")) != candidate_record:
                                raise ValueError("Interrupted canonical image record does not match audited image")
                        else:
                            write_evidence(pending_image, candidate_record)
                        pending_image.rename(image_marker)
                    db.close(); db = None
                    # Replace ONLY this controller's exact, identity-checked working
                    # database with its fully audited equivalent canonical image.
                    os.replace(canonical, path)
                else:
                    # Renamed final images were already canonicalized. Rebuilding
                    # them again would change schema/header history and invalidate
                    # a previously prepared manifest after a process interruption.
                    db.close(); db = None
                storage_safe_point(budget)
                if path == working:
                    working.rename(final)
                manifest = {**provenance, **report, "independent_raw_audit": raw_audit, "database": {"file": final.name,
                    "bytes": final.stat().st_size, "sha256": raw.sha256(final)}}
                text = raw.canonical(manifest) + "\n"
                storage_safe_point(budget, len(text.encode("utf-8")))
                pending = output / "manifest.working.json"
                if pending.exists():
                    if pending.read_text(encoding="utf-8") != text:
                        raise ValueError("Interrupted manifest bytes do not match validated publication")
                else:
                    with pending.open("x", encoding="utf-8", newline="\n") as stream:
                        stream.write(text); stream.flush(); os.fsync(stream.fileno())
                pending.rename(output / "manifest.json")
                return {"status": "complete", "manifest": manifest}
            except Paused as pause:
                # Uncommitted replay work never advances the embedded safe checkpoint.
                if db is not None:
                    db.rollback()
                return {"status": "paused", "checkpoint": store.saved, "reason": str(pause),
                        "routing_ready": False, "live_setup_completion_credit": 0}
            except Exception as exc:
                if db is not None:
                    db.rollback()
                if isinstance(exc, sqlite3.OperationalError) and getattr(exc, "sqlite_errorcode", None) == sqlite3.SQLITE_FULL:
                    return {"status": "paused", "checkpoint": store.saved,
                            "reason": "SQLite storage/page limit reached; uncommitted entity rolled back",
                            "routing_ready": False, "live_setup_completion_credit": 0}
                # Do not turn a malformed source/audit error into retry success.
                # Evidence-writing failure may mask nothing: retain the original
                # exception and report any secondary storage failure separately.
                try:
                    write_evidence(output / "failure.json", {"format": FORMAT, "binding": binding,
                        "checkpoint": store.saved, "routing_ready": False,
                        "live_setup_completion_credit": 0, "error": type(exc).__name__, "message": str(exc)})
                except OSError as evidence_error:
                    print("Could not save terminal failure evidence: " + str(evidence_error), flush=True)
                raise
        finally:
            if db is not None:
                db.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--jurisdiction", required=True)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--reserve-gib", type=float, default=6)
    parser.add_argument("--max-output-gib", type=float, default=4)
    parser.add_argument("--max-seconds", type=float, default=180)
    args = parser.parse_args()
    result = run(args.input, args.output, args.jurisdiction, resume=args.resume,
        reserve_bytes=int(args.reserve_gib * raw.GIB), max_bytes=int(args.max_output_gib * raw.GIB),
        max_seconds=args.max_seconds)
    print(raw.canonical(result), flush=True)


if __name__ == "__main__":
    main()
