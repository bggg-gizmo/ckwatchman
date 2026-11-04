#!/usr/bin/env python3
"""Independently compare compact graph storage to verified source topology, not legal routing."""
import argparse
import hashlib
import itertools
import json
from pathlib import Path
import sqlite3


def sha(path):
    value = hashlib.sha256()
    with Path(path).open('rb') as stream:
        while block := stream.read(1024 * 1024):
            value.update(block)
    return value.hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def unpack(blob, count):
    """Independent bounded decoder: reject truncation, overlong encodings and trailing values."""
    require(0 <= count <= 100_000 and len(blob) <= count * 5, 'Packed way exceeds format bounds')
    position = 0
    previous = 0
    for _ in range(count):
        number = 0
        for byte_index in range(5):
            require(position < len(blob), 'Truncated packed node reference')
            byte = blob[position]
            position += 1
            number |= (byte & 127) << (7 * byte_index)
            if byte < 128:
                require(byte_index == 0 or byte != 0, 'Noncanonical packed reference')
                break
        else:
            raise ValueError('Oversized packed reference')
        require(number <= 2 ** 32 - 2, 'Packed delta outside format bound')
        previous += -(number // 2) - 1 if number & 1 else number // 2
        require(0 < previous <= 2 ** 31 - 1, 'Invalid decoded local node identity')
        yield previous
    require(position == len(blob), 'Trailing packed references')


def equal_rows(db, label, expected, actual):
    count = 0
    sentinel = object()
    for first, second in itertools.zip_longest(db.execute(expected), db.execute(actual), fillvalue=sentinel):
        require(first == second, f'{label} differs from source at row{count}: {first!r} != {second!r}')
        count += 1
    return count


def validate(topology, directory, expected):
    topology, directory = Path(topology).resolve(strict=True), Path(directory).resolve(strict=True)
    graph = directory / 'graph.sqlite'
    manifest = json.loads((directory / 'manifest.json').read_text(encoding='utf-8'))
    require(sha(topology) == expected.lower(), 'Unexpected source topology hash')
    require(manifest['source_topology_sha256'] == expected.lower(), 'Manifest source hash mismatch')
    require(manifest['format'] == 'watchman-compact-road-graph/1', 'Unsupported compact format')
    require(manifest['routing_ready'] is False and manifest['live_setup_completion_credit'] == 0, 'Invalid readiness/setup credit')
    require(manifest['database']['file'] == graph.name, 'Unexpected database name')
    require(manifest['database']['bytes'] == graph.stat().st_size, 'Database size mismatch')
    original_graph_hash = sha(graph)
    require(manifest['database']['sha256'] == original_graph_hash, 'Database hash mismatch')
    db = sqlite3.connect(graph.as_uri() + '?mode=ro', uri=True)
    try:
        db.execute('ATTACH DATABASE ? AS source', (topology.as_uri() + '?mode=ro',))
        db.execute('PRAGMA query_only=ON')
        db.execute('PRAGMA cache_size=-8192')
        db.execute('PRAGMA source.cache_size=-8192')
        db.execute('PRAGMA temp_store=FILE')
        db.execute('BEGIN')
        require(db.execute('PRAGMA user_version').fetchone() == (1,), 'Database schema version mismatch')
        require(db.execute('PRAGMA integrity_check').fetchall() == [('ok',)], 'SQLite integrity failed')
        require(db.execute('PRAGMA foreign_key_check').fetchone() is None, 'Foreign-key integrity failed')
        stored = json.loads(db.execute("SELECT value FROM metadata WHERE key='manifest'").fetchone()[0])
        require(stored == {k: v for k, v in manifest.items() if k != 'database'}, 'Embedded manifest mismatch')
        provenance = json.loads(db.execute("SELECT value FROM source.metadata WHERE key='provenance'").fetchone()[0])
        require(stored['source_provenance'] == provenance, 'Source provenance mismatch')
        audited = {}
        audited['nodes'] = equal_rows(db, 'Nodes',
            'SELECT id,lon_e7,lat_e7,version,stamp,tags FROM source.nodes ORDER BY id',
            'SELECT n.osm_id,n.lon_e7,n.lat_e7,n.version,s.raw,t.raw FROM nodes n JOIN stamps s ON s.id=n.stamp_id '
            'JOIN tags t ON t.id=n.tag_id ORDER BY n.osm_id')
        audited['ways'] = equal_rows(db, 'Ways',
            'SELECT id,version,stamp,kind,tags FROM source.ways ORDER BY id',
            'SELECT w.osm_id,w.version,s.raw,w.kind,t.raw FROM ways w JOIN stamps s ON s.id=w.stamp_id '
            'JOIN tags t ON t.id=w.tag_id ORDER BY w.osm_id')
        # Local IDs must have deterministic dense ordering; directed candidate identities depend on it.
        for table, local, order in (('nodes', 'nid', 'osm_id'), ('ways', 'wid', 'osm_id'), ('segments', 'sid', 'wid,ordinal')):
            for index, row in enumerate(db.execute(f'SELECT {local} FROM {table} ORDER BY {order}'), 1):
                require(row == (index,), f'Noncanonical local ID ordering: {table}')
        references = 0
        for osm_id, count, blob in db.execute('SELECT osm_id,node_count,node_refs FROM ways ORDER BY osm_id'):
            expected_refs = db.execute('SELECT n.nid FROM source.way_nodes wn LEFT JOIN nodes n ON n.osm_id=wn.node_id '
                                      'WHERE wn.way_id=? ORDER BY wn.ordinal', (osm_id,))
            sentinel = object()
            for decoded, row in itertools.zip_longest(unpack(blob, count), expected_refs, fillvalue=sentinel):
                require(row is not sentinel and decoded is not sentinel and row == (decoded,), f'Way geometry mismatch {osm_id}')
                references += 1
        require(references == db.execute('SELECT COUNT(*) FROM source.way_nodes').fetchone()[0], 'Geometry reference total mismatch')
        audited['segments'] = equal_rows(db, 'Segments',
            'SELECT way_id,ordinal,from_node,to_node,length_mm FROM source.segments ORDER BY way_id,ordinal',
            'SELECT w.osm_id,s.ordinal,a.osm_id,b.osm_id,s.length_mm FROM segments s JOIN ways w ON w.wid=s.wid '
            'JOIN nodes a ON a.nid=s.from_nid JOIN nodes b ON b.nid=s.to_nid ORDER BY w.osm_id,s.ordinal')
        audited['relations'] = equal_rows(db, 'Relations',
            'SELECT id,version,stamp,tags FROM source.relations ORDER BY id',
            'SELECT r.id,r.version,s.raw,t.raw FROM relations r JOIN stamps s ON s.id=r.stamp_id '
            'JOIN tags t ON t.id=r.tag_id ORDER BY r.id')
        audited['members'] = equal_rows(db, 'Members',
            'SELECT * FROM source.relation_members ORDER BY relation_id,ordinal',
            'SELECT * FROM members ORDER BY relation_id,ordinal')
        audited['relation_scope'] = equal_rows(db, 'Relation scope',
            "SELECT n.id,m.relation_id FROM source.relation_members m JOIN source.nodes n ON m.member_type='n' AND m.member_id=n.id UNION "
            "SELECT n.id,m.relation_id FROM source.relation_members m JOIN source.way_nodes wn ON m.member_type='w' AND m.member_id=wn.way_id "
            'JOIN source.nodes n ON n.id=wn.node_id ORDER BY 1,2',
            'SELECT n.osm_id,s.relation_id FROM relation_scope s JOIN nodes n ON n.nid=s.nid ORDER BY 1,2')
        missing = []
        for kind, table in (('n', 'nodes'), ('w', 'ways'), ('r', 'relations')):
            missing.append("SELECT m.relation_id,m.ordinal,m.member_type,m.member_id FROM source.relation_members m "
                           f"LEFT JOIN source.{table} t ON t.id=m.member_id WHERE m.member_type='{kind}' AND t.id IS NULL")
        audited['unresolved_members'] = equal_rows(db, 'Unresolved members', ' UNION ALL '.join(missing) + ' ORDER BY 1,2',
            'SELECT * FROM unresolved_members ORDER BY relation_id,ordinal')
        # Scope was independently checked above; every scope-less source relation must remain global.
        audited['unscoped_relations'] = equal_rows(db, 'Unscoped relations',
            'SELECT id FROM source.relations r WHERE NOT EXISTS (SELECT 1 FROM relation_scope s WHERE s.relation_id=r.id) ORDER BY id',
            'SELECT relation_id FROM unscoped_relations ORDER BY relation_id')
        for table in ('tags', 'stamps'):
            audited[table] = db.execute(f'SELECT COUNT(*) FROM {table}').fetchone()[0]
        require(audited == manifest['counts'], 'Inventory differs from manifest')
        require(manifest['candidate_directed_arcs'] == audited['segments'] * 2, 'Candidate arc count mismatch')
        # Check usable indexed adjacency, not merely index names in a hand-written manifest.
        for index, column in (('outgoing_forward', 'from_nid'), ('outgoing_backward', 'to_nid')):
            plan = str(db.execute(f'EXPLAIN QUERY PLAN SELECT sid FROM segments WHERE {column}=?', (1,)).fetchall())
            require(index in plan, f'Adjacency index is not used: {column}')
        require(sha(topology) == expected.lower() and sha(graph) == original_graph_hash, 'Input changed during validation')
        return {'verified_storage': True, 'routing_ready': False, 'counts': audited, 'node_references': references,
                'database_sha256': original_graph_hash, 'source_topology_sha256': expected.lower()}
    finally:
        db.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--topology', type=Path, required=True)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--expected-sha256', required=True)
    args = parser.parse_args()
    print(json.dumps(validate(args.topology, args.directory, args.expected_sha256), sort_keys=True))
