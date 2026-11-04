"""Export audited real SQLite pages and native R-tree query answers for the JVM decoder test."""
import argparse
import hashlib
import json
import random
import sqlite3
import struct
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("database", type=Path)
parser.add_argument("sha256")
parser.add_argument("output", type=Path)
args = parser.parse_args()
def digest():
    with args.database.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()
if digest() != args.sha256.lower():
    raise ValueError("Untrusted source spatial database")
db = sqlite3.connect(args.database.resolve().as_uri()+"?mode=ro", uri=True)
db.execute("pragma cache_size=-2048")
if db.execute("select rtreecheck('segment_bounds')").fetchone()[0] != "ok":
    raise ValueError("Native R-tree audit failed")
queries = []
randomizer = random.Random(20260918)
# Representative actual source-envelope centers at multiple search radii; independent
# native SQL computes coarse IDs, not the Kotlin decoder or exact spherical projection.
total = db.execute("select count(*) from segment_bounds").fetchone()[0]
for _ in range(64):
    sid = randomizer.randint(1, total)
    a,b,c,d = db.execute("select min_lon,max_lon,min_lat,max_lat from segment_bounds where sid=?", (sid,)).fetchone()
    x,y = (a+b)//2,(c+d)//2
    for radius in (1000, 10000, 100000):
        queries.append([(max(-1800000000,x-radius),min(1800000000,x+radius),max(-900000000,y-radius),min(900000000,y+radius))])
queries += [[(-1800000000,-1790000000,-900000000,900000000),(1790000000,1800000000,-900000000,900000000)], [(0,1,0,1)]]
count = db.execute("select count(*) from segment_bounds_node").fetchone()[0]
observations = 0
with args.output.open("xb") as out:
    out.write(struct.pack(">II",0x57535031,count))
    for node,blob in db.execute("select nodeno,data from segment_bounds_node order by nodeno"):
        out.write(struct.pack(">qI",node,len(blob)));out.write(blob)
    out.write(struct.pack(">I",len(queries)))
    for boxes in queries:
        ids = set()
        out.write(struct.pack(">I",len(boxes)))
        for a,b,c,d in boxes:
            out.write(struct.pack(">iiii",a,b,c,d))
            ids.update(row[0] for row in db.execute("select sid from segment_bounds where min_lon<=? and max_lon>=? and min_lat<=? and max_lat>=?",(b,a,d,c)))
        if len(ids)>100000:
            raise ValueError("Fixture query exceeds explicit row bound")
        out.write(struct.pack(">I",len(ids)))
        for sid in sorted(ids): out.write(struct.pack(">q",sid))
        observations += len(ids)
db.close()
if digest()!=args.sha256.lower():
    raise ValueError("Source changed during export")
print(json.dumps({"source_sha256":args.sha256.lower(),"pages":count,"queries":len(queries),"candidate_observations":observations,"fixture_bytes":args.output.stat().st_size,"fixture_sha256":hashlib.sha256(args.output.read_bytes()).hexdigest()},sort_keys=True))
