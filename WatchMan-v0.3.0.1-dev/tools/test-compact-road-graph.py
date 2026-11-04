#!/usr/bin/env python3
"""Compact store source-equivalence/tamper tests. No route-mode or Android acceptance implied."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest import mock


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(file))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


builder = load('compact_builder', 'build-compact-road-graph.py')
audit = load('compact_audit', 'validate-compact-road-graph.py')
fixtures = load('topology_fixtures', 'test-road-topology.py')


class CompactTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='watchman-compact-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        raw = self.root / 'fixture.osm'
        raw.write_text(fixtures.fixture(), encoding='utf-8')
        with contextlib.redirect_stdout(io.StringIO()):
            fixtures.topology.build(raw, self.root / 'topology', 'DC', reserve_bytes=0)
        self.topology = self.root / 'topology/topology.sqlite'
        self.expected = builder.digest(self.topology)

    def build(self, name='compact', **kwargs):
        with contextlib.redirect_stdout(io.StringIO()):
            return builder.build(self.topology, self.root / name, self.expected, reserve_bytes=0, **kwargs)

    def validate(self, name='compact'):
        return audit.validate(self.topology, self.root / name, self.expected)

    def test_all_source_facts_and_scoped_and_unscoped_constraints(self):
        result = self.build()
        checked = self.validate()
        self.assertFalse(result['routing_ready'])
        self.assertEqual(result['live_setup_completion_credit'], 0)
        self.assertEqual(checked['counts']['unresolved_members'], 3)
        self.assertEqual(checked['counts']['unscoped_relations'], 1)
        with contextlib.closing(sqlite3.connect(self.root / 'compact/graph.sqlite')) as db, db:
            # Node4 is an intermediate via-way junction; another outgoing road must see relation101.
            self.assertIsNotNone(db.execute('SELECT 1 FROM relation_scope s JOIN nodes n ON n.nid=s.nid '
                                           'WHERE n.osm_id=4 AND s.relation_id=101').fetchone())
            self.assertEqual(db.execute('SELECT relation_id FROM unscoped_relations').fetchall(), [(103,)])
            self.assertEqual(db.execute('SELECT COUNT(*) FROM segments WHERE length_mm=0').fetchone()[0], 1)
        self.assertEqual(builder.digest(self.topology), self.expected)

    def test_byte_determinism(self):
        first, second = self.build('one'), self.build('two')
        self.assertEqual(first, second)
        self.assertEqual((self.root/'one/graph.sqlite').read_bytes(), (self.root/'two/graph.sqlite').read_bytes())
        self.assertEqual((self.root/'one/manifest.json').read_bytes(), (self.root/'two/manifest.json').read_bytes())

    def test_delta_codec_boundaries_and_repeated_geometry(self):
        for values in ([], [1], [1,2,1,1,130], [2**31-1,1,2**31-1], list(range(1,1001))):
            count, blob = builder.packed_refs(values)
            self.assertEqual(list(audit.unpack(blob, count)), values)
        self.assertEqual(builder.packed_refs([1,2,1,1,130])[1], bytes([2,2,1,0,130,2]))
        for values in ([0], [-1], [2**31], [1]*100001):
            with self.assertRaises(ValueError): builder.packed_refs(values)
        for blob, count in ((b'\x80',1), (b'\x82\x00',1), (b'\xff'*5,1), (b'\x00',1), (b'\x02\x02',1), (b'\x02',0)):
            with self.assertRaises(ValueError): list(audit.unpack(blob,count))

    def rehash(self):
        directory = self.root/'compact'
        manifest = json.loads((directory/'manifest.json').read_text(encoding='utf-8'))
        with contextlib.closing(sqlite3.connect(directory/'graph.sqlite')) as db, db:
            # Keep internal/external inventory consistent so source comparison, not a stale hash,
            # has to expose tampering.
            for table in manifest['counts']:
                manifest['counts'][table] = db.execute(f'SELECT COUNT(*) FROM {table}').fetchone()[0]
            embedded = {k:v for k,v in manifest.items() if k != 'database'}
            db.execute("UPDATE metadata SET value=? WHERE key='manifest'", (json.dumps(embedded),))
        manifest['database']['bytes'] = (directory/'graph.sqlite').stat().st_size
        manifest['database']['sha256'] = builder.digest(directory/'graph.sqlite')
        (directory/'manifest.json').write_text(json.dumps(manifest), encoding='utf-8')

    def test_forged_hash_cannot_hide_changed_road_tag(self):
        self.build()
        with contextlib.closing(sqlite3.connect(self.root/'compact/graph.sqlite')) as db, db:
            db.execute("UPDATE ways SET tag_id=(SELECT id FROM tags WHERE raw='{}') WHERE osm_id=11")
        self.rehash()
        with self.assertRaisesRegex(ValueError, 'Ways differs'): self.validate()

    def test_forged_hash_cannot_hide_geometry_change(self):
        self.build()
        with contextlib.closing(sqlite3.connect(self.root/'compact/graph.sqlite')) as db, db:
            blob = builder.packed_refs([1,3,2])[1]
            db.execute('UPDATE ways SET node_refs=? WHERE osm_id=10', (blob,))
        self.rehash()
        with self.assertRaisesRegex(ValueError, 'geometry mismatch'): self.validate()

    def test_forged_hash_cannot_hide_missing_relation_scope(self):
        self.build()
        with contextlib.closing(sqlite3.connect(self.root/'compact/graph.sqlite')) as db, db:
            db.execute('DELETE FROM relation_scope WHERE relation_id=101')
        self.rehash()
        with self.assertRaisesRegex(ValueError, 'Relation scope differs'): self.validate()

    def test_forged_hash_cannot_hide_unresolved_member_drop(self):
        self.build()
        with contextlib.closing(sqlite3.connect(self.root/'compact/graph.sqlite')) as db, db:
            db.execute('DELETE FROM unresolved_members WHERE member_type="r"')
        self.rehash()
        with self.assertRaisesRegex(ValueError, 'Unresolved members differs'): self.validate()

    def test_existing_output_and_wrong_source_hash_do_not_overwrite(self):
        self.build()
        before = builder.digest(self.root/'compact/graph.sqlite')
        with self.assertRaises(FileExistsError): self.build()
        self.assertEqual(builder.digest(self.root/'compact/graph.sqlite'), before)
        with self.assertRaisesRegex(ValueError, 'expected independently audited'):
            builder.build(self.topology, self.root/'bad-hash', '0'*64, reserve_bytes=0)
        self.assertFalse((self.root/'bad-hash').exists())

    def test_budget_failure_never_publishes_success(self):
        with self.assertRaises(Exception): self.build(max_bytes=4096)
        self.assertTrue((self.root/'compact/failure.json').exists())
        self.assertFalse((self.root/'compact/manifest.json').exists())

    def test_input_change_never_publishes_success(self):
        real = builder.digest
        calls = 0
        def changed(path):
            nonlocal calls
            if Path(path).resolve() == self.topology.resolve():
                calls += 1
                if calls > 1: return 'f'*64
            return real(path)
        with mock.patch.object(builder, 'digest', side_effect=changed):
            with self.assertRaisesRegex(ValueError, 'Input changed'): self.build()
        self.assertFalse((self.root/'compact/manifest.json').exists())
        self.assertEqual(real(self.topology), self.expected)


if __name__ == '__main__':
    unittest.main(verbosity=2)
