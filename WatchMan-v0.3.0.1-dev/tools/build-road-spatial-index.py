#!/usr/bin/env python3
"""Immutable integer R-tree sidecar for every original segment; proximity is not permission."""
import argparse
import contextlib
import hashlib
import json
import math
from pathlib import Path
import shutil
import sqlite3

FORMAT = 'watchman-road-spatial/1'
GIB = 1024 ** 3
E7 = 10_000_000


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def digest(path):
    result = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''): result.update(block)
    return result.hexdigest()


def envelope(lon1, lat1, lon2, lat2):
    """Conservative cap about endpoint1 contains the entire minor geodesic arc.

    Latitude moves at most arc distance. Along any path within that cap,
    |d longitude| <= ds/cos(max |latitude|). Crossing a pole or the longitude
    seam uses the full longitude range. This deliberately prefers extra hits
    over omissions; exact distance/projection belongs to the consumer.
    """
    for lon, lat in ((lon1, lat1), (lon2, lat2)):
        if not isinstance(lon, int) or not isinstance(lat, int) or not (-180*E7 <= lon <= 180*E7 and -90*E7 <= lat <= 90*E7):
            raise ValueError('Invalid source E7 coordinate')
    a, b = math.radians(lat1/E7), math.radians(lat2/E7)
    delta = math.radians((lon2-lon1)/E7)
    hav = math.sin((b-a)/2)**2 + math.cos(a)*math.cos(b)*math.sin(delta/2)**2
    angle = 2*math.asin(math.sqrt(min(1.0, max(0.0, hav))))
    # Two E7 units of outward rounding cover binary arithmetic at boundaries.
    radius = math.degrees(angle)
    south = max(-90*E7, math.floor((lat1/E7-radius)*E7)-2)
    north = min(90*E7, math.ceil((lat1/E7+radius)*E7)+2)
    if south == -90*E7 or north == 90*E7:
        return -180*E7, 180*E7, south, north
    max_lat = max(abs(south), abs(north))/E7
    span = radius/math.cos(math.radians(max_lat))
    west = math.floor((lon1/E7-span)*E7)-2
    east = math.ceil((lon1/E7+span)*E7)+2
    if west <= -180*E7 or east >= 180*E7:
        west, east = -180*E7, 180*E7
    return west, east, south, north


def build(graph, output, expected, reserve_bytes=2*GIB, max_bytes=GIB):
    graph = Path(graph).resolve(strict=True)
    output = Path(output).resolve()
    if reserve_bytes < 0 or max_bytes < 4096: raise ValueError('Invalid storage budget')
    if digest(graph) != expected.lower(): raise ValueError('Unexpected source graph hash')
    output.mkdir(parents=True, exist_ok=False)
    working = output/'spatial.working.sqlite'
    def budget():
        if shutil.disk_usage(output).free < reserve_bytes: raise RuntimeError('Free-space reserve reached')
        if sum(p.stat().st_size for p in output.iterdir() if p.is_file()) > max_bytes:
            raise RuntimeError('Spatial output budget exceeded')
    try:
        budget()
        with contextlib.closing(sqlite3.connect(graph.as_uri()+'?mode=ro', uri=True)) as source, \
                contextlib.closing(sqlite3.connect(working)) as db:
            source.execute('PRAGMA cache_size=-8192')
            db.execute('PRAGMA page_size=4096')
            db.execute('PRAGMA cache_size=-8192')
            db.execute('PRAGMA temp_store=FILE')
            db.execute('PRAGMA synchronous=FULL')
            db.execute(f'PRAGMA max_page_count={max_bytes//4096}')
            if source.execute('PRAGMA user_version').fetchone() != (1,): raise ValueError('Unexpected graph schema')
            if source.execute('PRAGMA quick_check').fetchall() != [('ok',)]: raise ValueError('Source graph integrity failure')
            source_meta = json.loads(source.execute("SELECT value FROM metadata WHERE key='manifest'").fetchone()[0])
            if source_meta.get('format') != 'watchman-compact-road-graph/1': raise ValueError('Unexpected graph format')
            count = source.execute('SELECT COUNT(*) FROM segments').fetchone()[0]
            if not count or count != source_meta['counts']['segments']: raise ValueError('Source segment inventory mismatch')
            db.executescript('PRAGMA user_version=1; CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL) WITHOUT ROWID; '
                'CREATE VIRTUAL TABLE segment_bounds USING rtree_i32(sid,min_lon,max_lon,min_lat,max_lat);')
            seen = 0
            for sid, lon1, lat1, lon2, lat2 in source.execute('SELECT s.sid,a.lon_e7,a.lat_e7,b.lon_e7,b.lat_e7 '
                    'FROM segments s JOIN nodes a ON a.nid=s.from_nid JOIN nodes b ON b.nid=s.to_nid ORDER BY s.sid'):
                if sid <= 0: raise ValueError('Invalid segment identity')
                db.execute('INSERT INTO segment_bounds VALUES(?,?,?,?,?)', (sid, *envelope(lon1, lat1, lon2, lat2)))
                seen += 1
                if seen % 10000 == 0: budget()
            if seen != count: raise ValueError('Missing source segment geometry')
            report = {'format': FORMAT, 'source_graph_sha256': expected.lower(), 'segments': count,
                'routing_ready': False, 'live_setup_completion_credit': 0,
                'coordinates': 'integer E7', 'envelope': 'endpoint-centered-distance-cap/v1',
                'identity': 'sid in exact source graph revision; candidate arcs=2*sid-1,2*sid; not permissions',
                'generator_sha256': digest(__file__), 'sqlite_version': sqlite3.sqlite_version,
                'cache_kib': {'source': 8192, 'output': 8192}}
            db.execute('INSERT INTO metadata VALUES(?,?)', ('manifest', canonical(report)))
            if db.execute("SELECT rtreecheck('segment_bounds')").fetchone() != ('ok',): raise ValueError('R-tree integrity failure')
            if db.execute('PRAGMA integrity_check').fetchall() != [('ok',)]: raise ValueError('Spatial integrity failure')
            db.commit()
        if digest(graph) != expected.lower(): raise ValueError('Source graph changed during indexing')
        budget()
        final = output/'spatial.sqlite'
        working.rename(final)
        report['database'] = {'file': final.name, 'bytes': final.stat().st_size, 'sha256': digest(final)}
        with (output/'manifest.working.json').open('x', encoding='utf-8', newline='\n') as stream:
            stream.write(canonical(report)+'\n')
        budget()
        (output/'manifest.working.json').rename(output/'manifest.json')
        print(canonical(report), flush=True)
        return report
    except Exception as failure:
        with (output/'failure.json').open('x', encoding='utf-8', newline='\n') as stream:
            stream.write(canonical({'routing_ready': False, 'error': type(failure).__name__, 'message': str(failure)})+'\n')
        raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--graph', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--expected-sha256', required=True)
    parser.add_argument('--reserve-gib', type=float, default=2)
    parser.add_argument('--max-output-gib', type=float, default=1)
    args = parser.parse_args()
    build(args.graph, args.output, args.expected_sha256, int(args.reserve_gib*GIB), int(args.max_output_gib*GIB))
