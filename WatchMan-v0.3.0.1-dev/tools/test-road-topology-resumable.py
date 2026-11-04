#!/usr/bin/env python3
"""Tiny atomic-resume regressions; no national data or Android acceptance."""
from __future__ import annotations

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest import mock


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


controller = load("resumable_topology_tests_controller", "build-road-topology-resumable.py")
original = load("resumable_topology_tests_original", "test-road-topology.py")
TABLE_KEYS = {"relations": "id", "relation_members": "relation_id,ordinal", "ways": "id",
              "needed_nodes": "id", "nodes": "id", "way_nodes": "way_id,ordinal", "segments": "way_id,ordinal"}


class ResumabilityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="watchman-topology-resume-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "fixture.osm"
        self.source.write_text(original.fixture(), encoding="utf-8")

    def run_once(self, name="out", resume=False, **kwargs):
        with contextlib.redirect_stdout(io.StringIO()):
            return controller.run(self.source, self.root / name, "DC", resume=resume,
                                  reserve_bytes=0, max_seconds=30, **kwargs)

    def finish(self, name="out", stop_after=None):
        result = self.run_once(name, stop_after=stop_after)
        history = [result]
        for _ in range(64):
            if result["status"] == "complete":
                return result["manifest"], history
            self.assertFalse((self.root / name / "manifest.json").exists())
            self.assertFalse((self.root / name / "failure.json").exists())
            self.assertFalse(result["routing_ready"])
            self.assertEqual(result["live_setup_completion_credit"], 0)
            result = self.run_once(name, resume=True, stop_after=stop_after)
            history.append(result)
        self.fail("Tiny fixture did not complete inside its explicitly bounded test loop")

    def connection(self, name="out", final=False):
        path = self.root / name / ("topology.sqlite" if final else "topology.working.sqlite")
        return contextlib.closing(sqlite3.connect(path.as_uri() + "?mode=ro", uri=True))

    def pause_at_phase_entry(self, phase, name="out"):
        # stop_after counts callbacks across phases. An empty earlier phase does
        # not consume that allowance, so it cannot establish an empty next phase.
        # Inject only after the real phase-entry transaction has committed.
        normal_commit = controller.ResumeStore.commit

        def pause_committed_entry(store, next_phase, ordinal=0, last_id=None):
            normal_commit(store, next_phase, ordinal, last_id)
            if next_phase == phase and ordinal == 0:
                raise controller.Paused("Fixture stopped at committed phase entry")

        with mock.patch.object(controller.ResumeStore, "commit", pause_committed_entry):
            result = self.run_once(name)
        self.assertEqual(result["status"], "paused")
        self.assertEqual(result["checkpoint"], controller.checkpoint(phase))
        return result

    def test_original_content_and_unchanged_independent_validator(self):
        manifest, _ = self.finish()
        with contextlib.redirect_stdout(io.StringIO()):
            baseline = original.topology.build(self.source, self.root / "legacy", "DC", reserve_bytes=0)
        for key, value in baseline.items():
            if key != "database":
                self.assertEqual(manifest[key], value, key)
        with self.connection(final=True) as current, contextlib.closing(sqlite3.connect(self.root / "legacy/topology.sqlite")) as old:
            for table, keys in TABLE_KEYS.items():
                query = f"SELECT * FROM {table} ORDER BY {keys}"
                self.assertEqual(current.execute(query).fetchall(), old.execute(query).fetchall(), table)
        audit = original.audit_tool.validate(self.source, self.root / "out")
        self.assertEqual(audit["result"], "PASS")
        self.assertEqual(manifest["independent_raw_audit"]["counts"], audit["counts"])
        self.assertEqual(manifest["independent_raw_audit"]["validator_sha256"], controller.raw.sha256(controller.audit.__file__))

    def test_deterministic_bytes_across_pause_schedules(self):
        uninterrupted, _ = self.finish("a")
        resumed, history = self.finish("b", stop_after=1)
        repeated, _ = self.finish("c", stop_after=3)
        self.assertGreater(len(history), 5)
        self.assertEqual(uninterrupted, resumed)
        self.assertEqual(resumed, repeated)
        for name in ("b", "c"):
            self.assertEqual((self.root / "a/topology.sqlite").read_bytes(), (self.root / name / "topology.sqlite").read_bytes())
            self.assertEqual((self.root / "a/manifest.json").read_bytes(), (self.root / name / "manifest.json").read_bytes())

    def test_callback_ordinals_do_not_assume_sorted_original_ids(self):
        content = ('<?xml version="1.0"?><osm version="0.6">' +
                   original.node(7, -77, 38) + original.node(2, -77.001, 38.001) +
                   original.way(900, [7, 2], {"highway": "residential"}) +
                   original.way(10, [2, 7], {"highway": "service", "service": "alley"}) +
                   original.relation(800, [("way", 900, "")], {"barrier": "gate"}) +
                   original.relation(1, [("way", 10, "")], {"barrier": "gate"}) + '</osm>')
        self.source.write_text(content, encoding="utf-8")
        first = self.run_once(stop_after=1)
        self.assertEqual(first["checkpoint"]["ordinal"], 1)
        self.assertEqual(first["checkpoint"]["last_original_id"], 800)
        second = self.run_once(resume=True, stop_after=1)
        self.assertEqual(second["checkpoint"]["ordinal"], 2)
        self.assertEqual(second["checkpoint"]["last_original_id"], 1)
        result = self.run_once(resume=True)
        self.assertEqual(result["status"], "complete")
        self.assertEqual(original.audit_tool.validate(self.source, self.root / "out")["counts"]["ways"], 2)

    def test_large_entity_commits_only_after_every_original_member(self):
        members = [("node", 1, "") for _ in range(5005)]
        refs = [1, 2] * 2501
        self.source.write_text('<?xml version="1.0"?><osm version="0.6">' + original.node(1, -77, 38) +
            original.node(2, -77.001, 38.001) + original.way(700, refs, {"highway": "service"}) +
            original.relation(999, members, {"barrier": "gate"}) + '</osm>', encoding="utf-8")
        result = self.run_once(stop_after=1)
        with self.connection() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM relation_members WHERE relation_id=999").fetchone()[0], 5005)
            self.assertEqual(controller.metadata(db, "resume_checkpoint"), result["checkpoint"])
        result = self.run_once(resume=True, stop_after=1)
        self.assertEqual(result["checkpoint"]["phase"], "ways")
        result = self.run_once(resume=True, stop_after=1)
        with self.connection() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM way_nodes WHERE way_id=700").fetchone()[0], len(refs))
            self.assertEqual(controller.metadata(db, "resume_checkpoint"), result["checkpoint"])
        result = self.run_once(resume=True)
        self.assertEqual(result["status"], "complete")

    def test_node_batch_is_atomic_and_last_partial_batch_is_retained(self):
        parts = [original.node(n, -77 + n / 1e7, 38) for n in range(1, 906)]
        parts.append(original.way(1000, list(range(1, 906)), {"highway": "residential"}))
        self.source.write_text('<?xml version="1.0"?><osm version="0.6">' + ''.join(parts) + '</osm>', encoding="utf-8")
        result = self.pause_at_phase_entry("nodes")
        self.assertEqual(result["checkpoint"]["ordinal"], 0)
        result = self.run_once(resume=True, stop_after=1)
        self.assertEqual(result["checkpoint"]["ordinal"], 400)
        with self.connection() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM nodes").fetchone()[0], 400)
        result = self.run_once(resume=True, stop_after=1)
        self.assertEqual(result["checkpoint"]["ordinal"], 800)
        result = self.run_once(resume=True)
        self.assertEqual(result["status"], "complete")
        self.assertEqual(result["manifest"]["counts"]["nodes"], 905)
        self.assertEqual(original.audit_tool.validate(self.source, self.root / "out")["counts"]["nodes"], 905)

    def test_process_interruption_rolls_back_whole_uncommitted_way(self):
        result = self.pause_at_phase_entry("ways")
        saved = result["checkpoint"]
        normal = controller.raw.Ways.way

        def interrupt_after_insert(handler, entity):
            normal(handler, entity)
            raise KeyboardInterrupt("Simulated process termination after SQL before entity checkpoint")

        with mock.patch.object(controller.raw.Ways, "way", interrupt_after_insert):
            with self.assertRaises(KeyboardInterrupt):
                self.run_once(resume=True)
        with self.connection() as db:
            self.assertEqual(controller.metadata(db, "resume_checkpoint"), saved)
            self.assertEqual(db.execute("SELECT COUNT(*) FROM ways").fetchone()[0], 0)
            self.assertEqual(db.execute("SELECT COUNT(*) FROM way_nodes").fetchone()[0], 0)
        self.assertFalse((self.root / "out/failure.json").exists())
        self.assertEqual(self.run_once(resume=True)["status"], "complete")

    def test_uncommitted_node_batch_rolls_back_without_partial_checkpoint(self):
        parts = [original.node(n, -77 + n / 1e7, 38) for n in range(1, 906)]
        parts.append(original.way(1000, list(range(1, 906)), {"highway": "service"}))
        self.source.write_text('<?xml version="1.0"?><osm version="0.6">' + ''.join(parts) + '</osm>', encoding="utf-8")
        result = self.pause_at_phase_entry("nodes")
        normal = controller.raw.Nodes.flush

        def interrupt_after_batch(handler):
            normal(handler)
            raise KeyboardInterrupt("Simulated node batch process termination before checkpoint")

        with mock.patch.object(controller.raw.Nodes, "flush", interrupt_after_batch):
            with self.assertRaises(KeyboardInterrupt):
                self.run_once(resume=True)
        with self.connection() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM nodes").fetchone()[0], 0)
            self.assertEqual(controller.metadata(db, "resume_checkpoint")["ordinal"], 0)
        self.assertEqual(self.run_once(resume=True)["manifest"]["counts"]["nodes"], 905)

    def test_expired_replay_reports_no_new_progress(self):
        initial = self.run_once(stop_after=1)
        with mock.patch.object(controller.time, "monotonic", side_effect=[0.0, 31.0]):
            result = self.run_once(resume=True)
        self.assertEqual(result["status"], "paused")
        self.assertEqual(result["checkpoint"], initial["checkpoint"])
        self.assertIn("no new progress", result["reason"])

    def test_old_unmarked_partials_and_completed_outputs_are_never_modified(self):
        old = self.root / "old"
        old.mkdir()
        (old / "topology.working.sqlite").write_bytes(b"Historical incomplete evidence")
        before = {p.name: p.read_bytes() for p in old.iterdir()}
        with self.assertRaisesRegex(ValueError, "Unmarked partial"):
            self.run_once("old", resume=True)
        self.assertEqual(before, {p.name: p.read_bytes() for p in old.iterdir()})
        self.finish()
        before = {p.name: p.read_bytes() for p in (self.root / "out").iterdir()}
        with self.assertRaises(ValueError):
            self.run_once(resume=True)
        with self.assertRaises(FileExistsError):
            self.run_once()
        self.assertEqual(before, {p.name: p.read_bytes() for p in (self.root / "out").iterdir()})

    def test_exact_binding_and_checkpoint_tamper_are_refused(self):
        self.run_once(stop_after=1)
        directory = self.root / "out"
        before = {p.name: p.read_bytes() for p in directory.iterdir()}
        self.source.write_text(original.fixture().replace("Full local street", "Other local street"), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "binding mismatch"):
            self.run_once(resume=True)
        self.assertEqual(before, {p.name: p.read_bytes() for p in directory.iterdir()})
        self.source.write_text(original.fixture(), encoding="utf-8")
        with contextlib.closing(sqlite3.connect(directory / "topology.working.sqlite")) as db:
            value = controller.metadata(db, "resume_checkpoint")
            value["ordinal"] = -1
            db.execute("UPDATE metadata SET value=? WHERE key='resume_checkpoint'", (json.dumps(value),))
            db.commit()
        before = (directory / "topology.working.sqlite").read_bytes()
        with self.assertRaisesRegex(ValueError, "invalid committed"):
            self.run_once(resume=True)
        self.assertEqual(before, (directory / "topology.working.sqlite").read_bytes())

    def test_bad_geometry_is_terminal_and_independent_audit_is_mandatory(self):
        self.source.write_text(original.fixture(missing=True), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Missing geometry"):
            self.run_once()
        self.assertTrue((self.root / "out/failure.json").exists())
        self.assertFalse((self.root / "out/manifest.json").exists())
        with self.assertRaisesRegex(ValueError, "terminally failed"):
            self.run_once(resume=True)
        self.source.write_text(original.fixture(), encoding="utf-8")
        with mock.patch.object(controller.audit.Audit, "way", side_effect=ValueError("Independent rejection")):
            with self.assertRaisesRegex(ValueError, "Independent rejection"):
                self.run_once("audit-fail")
        self.assertFalse((self.root / "audit-fail/manifest.json").exists())
        self.assertTrue((self.root / "audit-fail/failure.json").exists())

    def test_manifest_last_recovery_after_database_rename(self):
        normal = Path.rename

        def interrupt_manifest(path, destination):
            if path.name == "manifest.working.json":
                raise KeyboardInterrupt("Simulated termination immediately before success marker")
            return normal(path, destination)

        with mock.patch.object(Path, "rename", interrupt_manifest):
            with self.assertRaises(KeyboardInterrupt):
                self.run_once()
        directory = self.root / "out"
        self.assertTrue((directory / "topology.sqlite").exists())
        self.assertTrue((directory / "manifest.working.json").exists())
        self.assertFalse((directory / "manifest.json").exists())
        self.assertFalse((directory / "failure.json").exists())
        self.assertEqual(self.run_once(resume=True)["status"], "complete")
        self.assertEqual(original.audit_tool.validate(self.source, directory)["result"], "PASS")

    def test_canonical_replace_before_rename_is_recoverable_without_byte_drift(self):
        expected, _ = self.finish("reference")
        normal = Path.rename

        def interrupt_database_rename(path, destination):
            if path.name == "topology.working.sqlite":
                raise KeyboardInterrupt("Simulated termination after canonical replace, before final rename")
            return normal(path, destination)

        with mock.patch.object(Path, "rename", interrupt_database_rename):
            with self.assertRaises(KeyboardInterrupt):
                self.run_once()
        directory = self.root / "out"
        self.assertTrue((directory / "canonical-image.json").exists())
        self.assertFalse((directory / "manifest.json").exists())
        resumed = self.run_once(resume=True)
        self.assertEqual(resumed["status"], "complete")
        self.assertEqual(resumed["manifest"], expected)
        self.assertEqual((directory / "topology.sqlite").read_bytes(), (self.root / "reference/topology.sqlite").read_bytes())

    def test_storage_pause_is_committed_and_does_not_publish(self):
        original_check = controller.raw.Budget.check
        count = 0

        def pause_after_first_commit(budget, additional_bytes=0):
            nonlocal count
            count += 1
            if count == 2:
                raise RuntimeError("Disk reserve reached; simulated safe boundary")
            return original_check(budget, additional_bytes)

        with mock.patch.object(controller.raw.Budget, "check", pause_after_first_commit):
            result = self.run_once(stop_after=1)
        self.assertEqual(result["status"], "paused")
        with self.connection() as db:
            self.assertEqual(controller.metadata(db, "resume_checkpoint"), result["checkpoint"])
            self.assertEqual(db.execute("SELECT COUNT(*) FROM relations").fetchone()[0], 1)
        self.assertFalse((self.root / "out/manifest.json").exists())
        self.assertFalse((self.root / "out/failure.json").exists())
        self.assertEqual(self.run_once(resume=True)["status"], "complete")


if __name__ == "__main__":
    unittest.main(verbosity=2)
