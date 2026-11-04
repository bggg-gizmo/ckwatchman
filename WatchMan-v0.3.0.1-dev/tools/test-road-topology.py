#!/usr/bin/env python3
"""Executable raw-topology preservation tests; these do not validate a legal router."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest import mock
from xml.sax.saxutils import quoteattr

SPEC = importlib.util.spec_from_file_location("topology", Path(__file__).with_name("build-road-topology.py"))
topology = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(topology)
AUDIT_SPEC = importlib.util.spec_from_file_location("topology_audit", Path(__file__).with_name("validate-road-topology.py"))
audit_tool = importlib.util.module_from_spec(AUDIT_SPEC)
AUDIT_SPEC.loader.exec_module(audit_tool)


def tags(values):
    return "".join(f"<tag k={quoteattr(k)} v={quoteattr(v)}/>" for k, v in values.items())


def node(n, lon, lat, **attributes):
    return f'<node id="{n}" lon="{lon}" lat="{lat}" version="1">{tags(attributes)}</node>'


def way(n, refs, attributes):
    return f'<way id="{n}" version="2">' + "".join(f'<nd ref="{r}"/>' for r in refs) + tags(attributes) + '</way>'


def relation(n, members, attributes):
    return f'<relation id="{n}" version="3">' + "".join(
        f'<member type={quoteattr(t)} ref="{r}" role={quoteattr(role)}/>' for t, r, role in members
    ) + tags(attributes) + '</relation>'


def fixture(missing=False):
    nodes = [node(1, -77.1234567, 38.1234567), node(2, -77.122, 38.123, barrier="gate", access="private"),
             node(3, -77.121, 38.124), node(4, -77.120, 38.125),
             node(5, -77.122, 38.123), node(6, -77.119, 38.125),
             node(7, -77.118, 38.126), node(8, -77.117, 38.127),
             node(90, -77.116, 38.128, barrier="bollard"), node(91, -77.115, 38.129)]
    ways = [way(10, [1, 2, 3], {"highway": "residential", "name": "Full local street", "oneway": "-1",
                               "maxspeed": "25 mph", "motor_vehicle:conditional": "no @ (Mo-Fr 08:00-09:00)"}),
            way(11, [2, 4], {"highway": "service", "service": "alley", "access": "private", "vehicle": "destination"}),
            way(12, [5, 6], {"highway": "tertiary", "bridge": "yes", "layer": "1"}),
            way(13, [6, 7, 8], {"highway": "footway", "motor_vehicle": "no"}),
            way(14, [7, 8], {}), way(15, [1, 1, 5, 2], {"highway": "unclassified"}),
            way(16, [4, 8], {"barrier": "fence"}), way(17, [1, 8], {"landuse": "forest"})]
    if missing:
        ways.append(way(18, [1, 999999], {"highway": "residential"}))
    relations = [relation(100, [("way", 10, "from"), ("node", 2, "via"), ("way", 11, "to")],
                          {"type": "restriction", "restriction": "no_right_turn", "except": "bus;bicycle"}),
                 relation(101, [("way", 10, "from"), ("way", 11, "via"), ("way", 12, "via"), ("way", 13, "to")],
                          {"type": "restriction", "restriction:conditional": "only_straight_on @ (Mo-Fr)"}),
                 relation(102, [("way", 14, "")], {"type": "route", "route": "ferry", "motor_vehicle": "yes"}),
                 relation(103, [("way", 999, "to"), ("node", 888, "via"), ("relation", 777, "")],
                          {"type": "restriction", "restriction:motorcar": "no_entry"})]
    return '<?xml version="1.0" encoding="UTF-8"?><osm version="0.6">' + ''.join(nodes + ways + relations) + '</osm>'


class TopologyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="watchman-topology-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "fixture.osm"
        self.source.write_text(fixture(), encoding="utf-8")

    def build(self, name="out", **kwargs):
        with contextlib.redirect_stdout(io.StringIO()):
            return topology.build(self.source, self.root / name, "DC", reserve_bytes=0, **kwargs)

    def database(self, name="out"):
        db = sqlite3.connect(self.root / name / "topology.sqlite")
        self.addCleanup(db.close)
        return db

    def test_local_service_all_classes_and_constraint_tags(self):
        manifest = self.build()
        db = self.database()
        rows = {r[0]: (r[1], json.loads(r[2])) for r in db.execute("SELECT id,kind,tags FROM ways")}
        self.assertEqual(set(rows), set(range(10, 17)))
        self.assertEqual(rows[10][1]["oneway"], "-1")
        self.assertEqual(rows[10][1]["maxspeed"], "25 mph")
        self.assertEqual(rows[10][1]["motor_vehicle:conditional"], "no @ (Mo-Fr 08:00-09:00)")
        self.assertEqual(rows[11][1], {"highway": "service", "service": "alley", "access": "private", "vehicle": "destination"})
        self.assertEqual(rows[13][1]["motor_vehicle"], "no")
        self.assertEqual(rows[14][0], "relation-member")
        self.assertEqual(rows[16][0], "barrier")
        self.assertFalse(manifest["routing_ready"])
        self.assertEqual(manifest["live_setup_completion_credit"], 0)

    def test_exact_geometry_global_ids_and_no_coincident_merging(self):
        self.build()
        db = self.database()
        self.assertEqual(db.execute("SELECT lon_e7,lat_e7 FROM nodes WHERE id=1").fetchone(), (-771234567, 381234567))
        self.assertEqual(db.execute("SELECT node_id FROM way_nodes WHERE way_id=10 ORDER BY ordinal").fetchall(), [(1,), (2,), (3,)])
        self.assertEqual(db.execute("SELECT id FROM nodes WHERE lon_e7=-771220000 ORDER BY id").fetchall(), [(2,), (5,)])
        self.assertEqual(db.execute("SELECT from_node,to_node FROM segments WHERE way_id=12").fetchone(), (5, 6))
        self.assertEqual(db.execute("SELECT ordinal,from_node,to_node,length_mm FROM segments WHERE way_id=15 ORDER BY ordinal").fetchall()[-1], (2, 5, 2, 0))
        self.assertEqual(db.execute("SELECT COUNT(*) FROM segments WHERE from_node=to_node").fetchone()[0], 0)
        self.assertGreater(db.execute("SELECT length_mm FROM segments WHERE way_id=10 LIMIT 1").fetchone()[0], 0)

    def test_ordered_via_way_and_conditional_restrictions_preserved(self):
        self.build()
        db = self.database()
        self.assertEqual(db.execute("SELECT member_type,member_id,role FROM relation_members WHERE relation_id=101 ORDER BY ordinal").fetchall(),
                         [("w", 10, "from"), ("w", 11, "via"), ("w", 12, "via"), ("w", 13, "to")])
        self.assertEqual(json.loads(db.execute("SELECT tags FROM relations WHERE id=100").fetchone()[0])["except"], "bus;bicycle")
        self.assertIn("restriction:conditional", json.loads(db.execute("SELECT tags FROM relations WHERE id=101").fetchone()[0]))

    def test_barriers_and_external_members_not_silently_dropped(self):
        manifest = self.build()
        db = self.database()
        self.assertEqual(manifest["unresolved_relation_member_occurrences"], {"n": 1, "w": 1, "r": 1})
        self.assertEqual(json.loads(db.execute("SELECT tags FROM nodes WHERE id=2").fetchone()[0])["access"], "private")
        self.assertEqual(json.loads(db.execute("SELECT tags FROM nodes WHERE id=90").fetchone()[0])["barrier"], "bollard")
        self.assertIsNone(db.execute("SELECT id FROM nodes WHERE id=91").fetchone())
        self.assertEqual(db.execute("PRAGMA integrity_check").fetchall(), [("ok",)])
        self.assertEqual(db.execute("PRAGMA foreign_key_check").fetchall(), [])

    def test_same_input_is_byte_deterministic_and_provenance_checked(self):
        a = self.build("a")
        b = self.build("b")
        self.assertEqual(a, b)
        self.assertEqual((self.root / "a/manifest.json").read_bytes(), (self.root / "b/manifest.json").read_bytes())
        self.assertEqual(a["database"]["sha256"], topology.sha256(self.root / "a/topology.sqlite"))
        self.assertEqual(a["input"]["sha256"], topology.sha256(self.source))
        self.assertIsNone(a["input"]["source_date"]["value"])

    def test_missing_way_geometry_fails_without_success_marker(self):
        self.source.write_text(fixture(missing=True), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Missing geometry node 999999"):
            self.build()
        self.assertFalse((self.root / "out/manifest.json").exists())
        self.assertTrue((self.root / "out/failure.json").exists())
        self.assertTrue((self.root / "out/topology.working.sqlite").exists())

    def test_existing_output_is_never_modified(self):
        self.build()
        before = {p.name: p.read_bytes() for p in (self.root / "out").iterdir()}
        with self.assertRaises(FileExistsError):
            self.build()
        self.assertEqual(before, {p.name: p.read_bytes() for p in (self.root / "out").iterdir()})

    def test_disk_reserve_and_output_budget_fail_closed(self):
        usage = type("Usage", (), {"free": 0})()
        with mock.patch.object(topology.shutil, "disk_usage", return_value=usage):
            with self.assertRaisesRegex(RuntimeError, "Disk reserve"):
                topology.build(self.source, self.root / "reserve", "DC", reserve_bytes=1)
        with self.assertRaises((sqlite3.DatabaseError, RuntimeError)):
            self.build("size", max_bytes=8192)
        self.assertFalse((self.root / "reserve/manifest.json").exists())
        self.assertFalse((self.root / "size/manifest.json").exists())

    def test_shared_border_node_identity_across_extracts(self):
        self.build("dc")
        self.source.write_text('<?xml version="1.0"?><osm version="0.6">' +
                               node(1, -77.1234567, 38.1234567) + node(700, -77.124, 38.124) +
                               way(800, [700, 1], {"highway": "residential"}) + '</osm>', encoding="utf-8")
        self.build("neighbor")
        a, b = self.database("dc"), self.database("neighbor")
        self.assertEqual(a.execute("SELECT id,lon_e7,lat_e7 FROM nodes WHERE id=1").fetchone(),
                         b.execute("SELECT id,lon_e7,lat_e7 FROM nodes WHERE id=1").fetchone())
        # Preserved identity is necessary but not proof of integrated cross-state routing.

    def test_distance_handles_antimeridian_and_identical_coordinates(self):
        self.assertEqual(topology.length_mm(0, 0, 0, 0), 0)
        self.assertLess(topology.length_mm(1799999999, 0, -1799999999, 0), 100)

    def test_independent_raw_rescan_passes(self):
        manifest = self.build()
        result = audit_tool.validate(self.source, self.root / "out")
        self.assertEqual(result["result"], "PASS")
        self.assertEqual(result["counts"], manifest["counts"])
        self.assertEqual(result["highway_way_counts"]["service"], 1)
        self.assertEqual(result["highway_way_counts"]["residential"], 1)

    def test_independent_audit_across_node_batch_boundaries(self):
        parts = [node(n, -77.0 + n / 10000000, 38.0, **({"barrier": "gate"} if n == 900 else {}))
                 for n in range(1, 906)]
        parts.append(way(1000, [1, 400, 401, 800, 905], {"highway": "residential"}))
        parts.append(way(1001, [401, 402], {"highway": "service"}))
        self.source.write_text('<?xml version="1.0"?><osm version="0.6">' + ''.join(parts) + '</osm>', encoding="utf-8")
        manifest = self.build()
        result = audit_tool.validate(self.source, self.root / "out")
        self.assertEqual(result["counts"], manifest["counts"])
        self.assertEqual(result["counts"]["nodes"], 7)
        self.assertEqual(result["counts"]["segments"], 5)

    def test_independent_audit_rejects_changed_input(self):
        self.build()
        self.source.write_text(fixture().replace("Full local street", "Wrong local street"), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Input"):
            audit_tool.validate(self.source, self.root / "out")

    def test_rescan_detects_wrong_tags_even_with_recomputed_database_hash(self):
        manifest = self.build()
        path = self.root / "out/topology.sqlite"
        db = sqlite3.connect(path)
        try:
            db.execute("UPDATE ways SET tags=? WHERE id=11", ('{"highway":"motorway"}',))
            db.commit()
        finally:
            db.close()
        manifest["database"]["sha256"] = topology.sha256(path)
        manifest["database"]["bytes"] = path.stat().st_size
        (self.root / "out/manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Way tags mismatch 11"):
            audit_tool.validate(self.source, self.root / "out")


if __name__ == "__main__":
    unittest.main(verbosity=2)
