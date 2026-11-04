#!/usr/bin/env python3
"""Generate source-audited synthetic Android reader fixtures through the actual PBF/topology tools."""
import argparse
import importlib.util
import json
from pathlib import Path


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(file))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


fixture = load('reader_fixture_helpers', 'test-road-topology.py')
compact = load('reader_fixture_compact', 'build-compact-road-graph.py')
audit = load('reader_fixture_audit', 'validate-compact-road-graph.py')


def source(guarded):
    nodes = ''.join(fixture.node(i, -77 + i / 10000, 38 + i / 10000,
                                 **({'barrier': 'block'} if i == 6 else {})) for i in range(1, 7))
    ways = ''.join(fixture.way(i, refs, {'highway': 'residential', 'name': f'Synthetic way {i}',
                                      **({'oneway': 'yes'} if i == 101 else {})}) for i, refs in (
        (101,[1,2]),(102,[2,3]),(103,[3,4]),(104,[2,5]),(105,[5,3]),(106,[3,6]),(107,[1,2,1,5])))
    relations = fixture.relation(77, [('way',101,'from'),('way',102,'via'),('way',103,'to')],
                                 {'type':'restriction','restriction':'no_straight_on'})
    if guarded:
        relations += fixture.relation(88, [('relation',77,'via')], {'type':'restriction','restriction':'only_right_turn'})
        relations += fixture.relation(89, [('node',999,'via')], {'type':'restriction','restriction':'no_left_turn'})
    return '<?xml version="1.0" encoding="UTF-8"?><osm version="0.6">' + nodes + ways + relations + '</osm>'


def permission_source():
    nodes = ''.join(fixture.node(i, -77 + i / 10000, 38 + i / 10000,
                                 **({'barrier': 'block'} if i == 6 else {})) for i in range(1, 9))
    roads = ((101,[1,2],{'access':'destination','oneway':'yes'}),
             (102,[2,3],{}),(103,[3,4],{'access':'private'}),
             (104,[2,5],{}),(105,[5,3],{}),(106,[3,6],{}),
             (107,[4,7],{}),(108,[3,8],{'access':'customers'}))
    ways = ''.join(fixture.way(i, refs, {'highway':'service','name':f'Permission fixture {i}',**tags})
                   for i, refs, tags in roads)
    relations = fixture.relation(77, [('way',101,'from'),('way',102,'via'),('way',103,'to')],
                                 {'type':'restriction','restriction':'no_straight_on'})
    return '<?xml version="1.0" encoding="UTF-8"?><osm version="0.6">' + nodes + ways + relations + '</osm>'


def build(output, permissions_only=False):
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    report = {'synthetic_only': True, 'routing_acceptance': False, 'fixtures': {}}
    cases = (('permission',permission_source()),) if permissions_only else (('good',source(False)),('guarded',source(True)))
    for name, content in cases:
        root = output/name
        root.mkdir()
        raw = root/'roads.osm'
        raw.write_text(content, encoding='utf-8')
        fixture.topology.build(raw, root/'topology', 'DC')
        fixture.audit_tool.validate(raw, root/'topology')
        topology = root/'topology/topology.sqlite'
        expected = compact.digest(topology)
        result = compact.build(topology, root/'compact', expected)
        audit.validate(topology, root/'compact', expected)
        report['fixtures'][name] = {'database_sha256': result['database']['sha256'],
                                    'counts': result['counts'], 'bytes': result['database']['bytes']}
    with (output/'reader-fixtures.json').open('x', encoding='utf-8') as target:
        json.dump(report, target, indent=2, sort_keys=True)
        target.write('\n')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--permissions-only', action='store_true', help='Build the separate endpoint-permission fixture, leaving older fixtures untouched')
    args = parser.parse_args()
    print(json.dumps(build(args.output, args.permissions_only), sort_keys=True))
