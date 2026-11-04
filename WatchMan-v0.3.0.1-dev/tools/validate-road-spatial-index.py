#!/usr/bin/env python3
"""Independent source/envelope audit; deliberately imports no spatial builder code."""
import argparse
import contextlib
import hashlib
import json
import math
from pathlib import Path
import sqlite3


def digest(path):
    value = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024*1024), b''): value.update(block)
    return value.hexdigest()


def expected_bounds(x1, y1, x2, y2):
    scale = 10_000_000
    if any(not isinstance(v, int) for v in (x1,y1,x2,y2)) or not (
            -180*scale <= x1 <= 180*scale and -180*scale <= x2 <= 180*scale and
            -90*scale <= y1 <= 90*scale and -90*scale <= y2 <= 90*scale):
        raise ValueError('Invalid graph coordinate')
    p, q = math.radians(y1/scale), math.radians(y2/scale)
    dlon = math.radians((x2-x1)/scale)
    h = min(1.0, max(0.0, math.sin((p-q)/2)**2 + math.cos(p)*math.cos(q)*math.sin(dlon/2)**2))
    # atan2 formulation, separate from generator's inverse-sine implementation.
    radius = math.degrees(2*math.atan2(math.sqrt(h), math.sqrt(1-h)))
    lo = max(-90*scale, math.floor(y1-radius*scale)-2)
    hi = min(90*scale, math.ceil(y1+radius*scale)+2)
    if lo == -90*scale or hi == 90*scale: return -180*scale, 180*scale, lo, hi
    width = radius*scale/math.cos(math.radians(max(abs(lo),abs(hi))/scale))
    left, right = math.floor(x1-width)-2, math.ceil(x1+width)+2
    if left <= -180*scale or right >= 180*scale: left, right = -180*scale, 180*scale
    return left, right, lo, hi


def validate(graph, directory, expected):
    graph, directory = Path(graph).resolve(strict=True), Path(directory).resolve(strict=True)
    database = directory/'spatial.sqlite'
    if digest(graph) != expected.lower(): raise ValueError('Wrong source graph hash')
    manifest = json.loads((directory/'manifest.json').read_text(encoding='utf-8'))
    if manifest.get('format') != 'watchman-road-spatial/1' or manifest.get('source_graph_sha256') != expected.lower():
        raise ValueError('Spatial source/format mismatch')
    if manifest.get('routing_ready') is not False or manifest.get('live_setup_completion_credit') != 0:
        raise ValueError('Spatial data cannot grant routing/setup acceptance')
    if manifest.get('envelope') != 'endpoint-centered-distance-cap/v1': raise ValueError('Unknown envelope contract')
    record = manifest['database']
    if record['file'] != 'spatial.sqlite' or record['bytes'] != database.stat().st_size or record['sha256'] != digest(database):
        raise ValueError('Spatial database integrity mismatch')
    with contextlib.closing(sqlite3.connect(graph.as_uri()+'?mode=ro', uri=True)) as source, \
            contextlib.closing(sqlite3.connect(database.as_uri()+'?mode=ro', uri=True)) as index:
        for db in (source,index): db.execute('PRAGMA cache_size=-8192')
        if index.execute('PRAGMA user_version').fetchone() != (1,): raise ValueError('Wrong spatial schema')
        if index.execute('PRAGMA integrity_check').fetchall() != [('ok',)]: raise ValueError('Spatial integrity failure')
        if index.execute("SELECT rtreecheck('segment_bounds')").fetchone() != ('ok',): raise ValueError('R-tree integrity failure')
        schema = index.execute("SELECT sql FROM sqlite_master WHERE name='segment_bounds'").fetchone()[0]
        if 'using rtree_i32' not in schema.lower(): raise ValueError('Index is not integer R-tree')
        embedded = json.loads(index.execute("SELECT value FROM metadata WHERE key='manifest'").fetchone()[0])
        if embedded != {k:v for k,v in manifest.items() if k != 'database'}: raise ValueError('Metadata mismatch')
        count = source.execute('SELECT COUNT(*) FROM segments').fetchone()[0]
        if count != manifest['segments'] or count != index.execute('SELECT COUNT(*) FROM segment_bounds').fetchone()[0]:
            raise ValueError('Segment inventory mismatch')
        boxes = iter(index.execute('SELECT sid,min_lon,max_lon,min_lat,max_lat FROM segment_bounds ORDER BY sid'))
        checked = 0
        for sid, x1,y1,x2,y2 in source.execute('SELECT s.sid,a.lon_e7,a.lat_e7,b.lon_e7,b.lat_e7 '
                'FROM segments s JOIN nodes a ON a.nid=s.from_nid JOIN nodes b ON b.nid=s.to_nid ORDER BY s.sid'):
            row = next(boxes, None)
            if row is None or row[0] != sid: raise ValueError('Segment identity mismatch')
            bounds = expected_bounds(x1,y1,x2,y2)
            # Floating rearrangement may cross one integer rounding boundary; only one
            # E7 unit is allowed, within the generator's two-unit outward padding.
            if any(abs(a-b) > 1 for a,b in zip(row[1:],bounds)): raise ValueError(f'Source envelope mismatch at segment {sid}')
            west,east,south,north = row[1:]
            for x,y in ((x1,y1),(x2,y2)):
                if not (west <= x <= east and south <= y <= north): raise ValueError('Source endpoint excluded')
            checked += 1
        if checked != count or next(boxes,None) is not None: raise ValueError('Missing/extra segment geometry')
        plan = [row[3] for row in index.execute('EXPLAIN QUERY PLAN SELECT sid FROM segment_bounds '
            'WHERE min_lon<=? AND max_lon>=? AND min_lat<=? AND max_lat>=?', (0,0,0,0))]
        if not any('VIRTUAL TABLE INDEX' in line for line in plan): raise ValueError('No R-tree query plan')
    if digest(graph) != expected.lower() or digest(database) != record['sha256']: raise ValueError('Input changed during audit')
    return {'result':'PASS', 'segments': checked, 'source_graph_sha256': expected.lower(),
            'spatial_sha256':record['sha256'], 'query_plan':plan, 'scope':'source-bound spatial coverage, not legal snapping/routing'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--graph', type=Path, required=True)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--expected-sha256', required=True)
    args = parser.parse_args()
    print(json.dumps(validate(args.graph, args.directory, args.expected_sha256),sort_keys=True),flush=True)
