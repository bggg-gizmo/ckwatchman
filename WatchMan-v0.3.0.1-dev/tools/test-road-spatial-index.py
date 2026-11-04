#!/usr/bin/env python3
"""Spatial coverage/tamper tests, including independent spherical interpolation oracle."""
import contextlib
import importlib.util
import io
import json
import math
from pathlib import Path
import random
import sqlite3
import tempfile
import unittest
from unittest import mock


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(file))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


builder = load('spatial_builder', 'build-road-spatial-index.py')
audit = load('spatial_audit', 'validate-road-spatial-index.py')
compact = load('compact_fixture_builder', 'build-compact-road-graph.py')
fixtures = load('raw_fixture_builder', 'test-road-topology.py')


class SpatialTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='watchman-spatial-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        raw = self.root/'source.osm'
        raw.write_text(fixtures.fixture(), encoding='utf-8')
        with contextlib.redirect_stdout(io.StringIO()):
            fixtures.topology.build(raw,self.root/'topology','DC',reserve_bytes=0)
            topology = self.root/'topology/topology.sqlite'
            compact.build(topology,self.root/'compact',compact.digest(topology),reserve_bytes=0)
        self.graph = self.root/'compact/graph.sqlite'
        self.expected = builder.digest(self.graph)

    def build(self, name='spatial', **kwargs):
        with contextlib.redirect_stdout(io.StringIO()):
            return builder.build(self.graph,self.root/name,self.expected,reserve_bytes=0,**kwargs)

    def validate(self):
        return audit.validate(self.graph,self.root/'spatial',self.expected)

    def rehash(self):
        directory = self.root/'spatial'
        manifest = json.loads((directory/'manifest.json').read_text(encoding='utf-8'))
        manifest['database']['sha256'] = builder.digest(directory/'spatial.sqlite')
        manifest['database']['bytes'] = (directory/'spatial.sqlite').stat().st_size
        (directory/'manifest.json').write_text(json.dumps(manifest),encoding='utf-8')

    def test_complete_source_retention_and_integer_index(self):
        result = self.build()
        checked = self.validate()
        self.assertEqual(checked['result'],'PASS')
        self.assertEqual(checked['segments'],result['segments'])
        self.assertFalse(result['routing_ready'])
        self.assertEqual(result['live_setup_completion_credit'],0)
        self.assertEqual(builder.digest(self.graph),self.expected)

    def test_byte_determinism(self):
        self.assertEqual(self.build('one'),self.build('two'))
        for file in ('spatial.sqlite','manifest.json'):
            self.assertEqual((self.root/'one'/file).read_bytes(),(self.root/'two'/file).read_bytes())

    def test_wrong_source_and_existing_output_refused(self):
        self.build()
        before = builder.digest(self.root/'spatial/spatial.sqlite')
        with self.assertRaises(FileExistsError): self.build()
        self.assertEqual(before,builder.digest(self.root/'spatial/spatial.sqlite'))
        with self.assertRaisesRegex(ValueError,'Unexpected source'):
            builder.build(self.graph,self.root/'wrong','0'*64,reserve_bytes=0)
        self.assertFalse((self.root/'wrong').exists())

    def test_storage_failure_does_not_publish(self):
        with self.assertRaises(Exception): self.build(max_bytes=4096)
        self.assertTrue((self.root/'spatial/failure.json').is_file())
        self.assertFalse((self.root/'spatial/manifest.json').exists())

    def test_mutated_source_does_not_publish(self):
        real = builder.digest
        calls = 0
        def changed(path):
            nonlocal calls
            if Path(path).resolve() == self.graph:
                calls += 1
                if calls > 1: return 'e'*64
            return real(path)
        with mock.patch.object(builder,'digest',side_effect=changed):
            with self.assertRaisesRegex(ValueError,'changed during indexing'): self.build()
        self.assertFalse((self.root/'spatial/manifest.json').exists())

    def test_rehashed_omission_is_rejected(self):
        self.build()
        with contextlib.closing(sqlite3.connect(self.root/'spatial/spatial.sqlite')) as db,db:
            db.execute('DELETE FROM segment_bounds WHERE sid=(SELECT MIN(sid) FROM segment_bounds)')
        self.rehash()
        with self.assertRaisesRegex(ValueError,'inventory mismatch'): self.validate()

    def test_rehashed_changed_identity_is_rejected(self):
        self.build()
        with contextlib.closing(sqlite3.connect(self.root/'spatial/spatial.sqlite')) as db,db:
            db.execute('UPDATE segment_bounds SET sid=999999 WHERE sid=(SELECT MIN(sid) FROM segment_bounds)')
        self.rehash()
        with self.assertRaisesRegex(ValueError,'identity mismatch'): self.validate()

    def test_rehashed_shrunk_and_expanded_envelopes_are_rejected(self):
        self.build()
        with contextlib.closing(sqlite3.connect(self.root/'spatial/spatial.sqlite')) as db,db:
            db.execute('UPDATE segment_bounds SET min_lon=min_lon-100000 WHERE sid=(SELECT MIN(sid) FROM segment_bounds)')
        self.rehash()
        with self.assertRaisesRegex(ValueError,'envelope mismatch'): self.validate()
        with contextlib.closing(sqlite3.connect(self.root/'spatial/spatial.sqlite')) as db,db:
            db.execute('UPDATE segment_bounds SET min_lon=max_lon WHERE sid=(SELECT MIN(sid) FROM segment_bounds)')
        self.rehash()
        with self.assertRaisesRegex(ValueError,'envelope mismatch'): self.validate()

    def test_invalid_coordinates_refused(self):
        for coordinates in ((1800000001,0,0,0),(0,-900000001,0,0),(0.5,0,0,0)):
            with self.assertRaises(ValueError): builder.envelope(*coordinates)

    def test_polar_dateline_degenerate_and_long_geometry(self):
        self.assertEqual(builder.envelope(1799000000,0,-1799000000,0)[:2],(-1800000000,1800000000))
        self.assertEqual(builder.envelope(0,899999999,1800000000,899999999)[:2],(-1800000000,1800000000))
        self.assertEqual(builder.envelope(0,0,0,0),(-2,2,-2,2))
        self.assertEqual(builder.envelope(0,0,1800000000,0),(-1800000000,1800000000,-900000000,900000000))
        # Endpoints are at60N but the minor arc goes farther north; endpoint-only boxes fail.
        west,east,south,north = builder.envelope(-450000000,600000000,450000000,600000000)
        self.assertGreater(north,600000000)
        with contextlib.closing(sqlite3.connect(':memory:')) as db:
            db.execute('CREATE VIRTUAL TABLE bounds USING rtree_i32(sid,w,e,s,n)')
            db.execute('INSERT INTO bounds VALUES(1,?,?,?,?)',(west,east,south,north))
            mid_lat = math.degrees(math.atan(math.tan(math.radians(60))/math.cos(math.radians(45))))*1e7
            self.assertEqual(db.execute('SELECT sid FROM bounds WHERE w<=0 AND e>=0 AND s<=? AND n>=?',(mid_lat,mid_lat)).fetchall(),[(1,)])

    def test_independent_interpolated_sphere_coverage(self):
        rng = random.Random(91317)
        segments = [(-450000000,600000000,450000000,600000000),(1799000000,10000000,-1799000000,10000000),
                    (0,899999999,1800000000,899999999),(0,0,0,0)]
        segments += [(rng.randint(-1800000000,1800000000),rng.randint(-900000000,900000000),
                      rng.randint(-1800000000,1800000000),rng.randint(-900000000,900000000)) for _ in range(2000)]
        def vector(x,y):
            lon,lat = math.radians(x/1e7),math.radians(y/1e7)
            return math.cos(lat)*math.cos(lon),math.cos(lat)*math.sin(lon),math.sin(lat)
        for x1,y1,x2,y2 in segments:
            bounds = builder.envelope(x1,y1,x2,y2)
            reference = audit.expected_bounds(x1,y1,x2,y2)
            self.assertTrue(all(abs(a-b)<=1 for a,b in zip(bounds,reference)))
            a,b = vector(x1,y1),vector(x2,y2)
            for step in range(33):
                t=step/32
                # Normalized chord points cover the minor great-circle arc without
                # sharing any bounding-cap implementation with the generator.
                p=tuple((1-t)*u+t*v for u,v in zip(a,b))
                norm=math.sqrt(sum(v*v for v in p))
                if norm < 1e-12: continue # antipodal geometry is not uniquely snappable
                lon=math.degrees(math.atan2(p[1],p[0]))*1e7
                lat=math.degrees(math.atan2(p[2],math.hypot(p[0],p[1])))*1e7
                self.assertTrue(bounds[0]-1<=lon<=bounds[1]+1 and bounds[2]-1<=lat<=bounds[3]+1,
                                (x1,y1,x2,y2,bounds,lon,lat))


if __name__ == '__main__': unittest.main(verbosity=2)
