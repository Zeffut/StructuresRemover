#!/usr/bin/env python3
"""Second pass: turn the scan's clump summaries into groups of structures that really are copies.

The scan records, for every clump on the map, how big it is, what shape its bounding box has and
what it is made of. Two clumps cannot be copies unless those summaries already agree, so only the
summaries seen more than once are worth reading back — a few per cent of the map, instead of all of
it. Those get their blocks read again and hashed exactly, four rotations each, and the ones whose
hash matches are copies block for block.

The result is written to exact.pickle for emit.py to turn into a deletion list.
"""
import os
import pickle
import sys
from collections import Counter, defaultdict
from multiprocessing import Pool

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import discover

OUT = 'exact.pickle'


def fingerprint_region(args):
    """Hashes the wanted clumps of one region exactly, reading the region only once."""
    (rx, rz), corners = args
    blocks = discover.load_blocks(rx, rz)

    if blocks is None:
        return []

    wanted = set(corners)
    out = []

    for blob in discover.owned_clumps(rx, rz, blocks):
        corner = (min(c[0] for c in blob), min(c[1] for c in blob), min(c[2] for c in blob))

        if corner not in wanted:
            continue

        digest, size = discover.canonical(blob, blocks)
        materials = Counter(blocks[c].split('[')[0] for c in blob)
        out.append((digest, size, corner, tuple(materials.most_common(8))))

    return out


def main():
    workers = int(sys.argv[1]) if len(sys.argv) > 1 else 4

    clumps, scanned = discover.load()
    print('%d regions scanned, %d clumps recorded' % (len(scanned), len(clumps)))

    by_summary = defaultdict(list)

    for entry in clumps:
        by_summary[discover.key_of(entry)].append(entry)

    candidates = [e for group in by_summary.values() if len(group) > 1 for e in group]
    print('%d distinct summaries, %d clumps share one with another clump'
          % (len(by_summary), len(candidates)))

    by_region = defaultdict(list)

    for entry in candidates:
        for region in discover.regions_of(entry):
            by_region[region].append((entry[1], entry[2], entry[3]))

    print('reading back %d regions to fingerprint them exactly' % len(by_region), flush=True)

    groups = defaultdict(list)
    done = 0

    with Pool(workers) as pool:
        for found in pool.imap_unordered(fingerprint_region, sorted(by_region.items()), chunksize=1):
            done += 1

            for digest, size, corner, materials in found:
                groups[digest].append((size, corner, materials))

            if done % 50 == 0:
                repeated = sum(1 for g in groups.values() if len(g) > 1)
                print('  %d/%d regions, %d fingerprints, %d repeated'
                      % (done, len(by_region), len(groups), repeated), flush=True)

    exact = {d: v for d, v in groups.items() if len(v) > 1}

    with open(OUT, 'wb') as fh:
        pickle.dump(exact, fh)

    instances = sum(len(v) for v in exact.values())
    blocks = sum(len(v) * v[0][0] for v in exact.values())
    print('\n%d groups of identical structures, %d instances, %d blocks in total'
          % (len(exact), instances, blocks))

    ranked = sorted(((len(v), v[0][0], d) for d, v in exact.items()), reverse=True)
    print('\n%7s %7s %9s  %-22s %s' % ('copies', 'blocks', 'total', 'example position', 'made of'))

    for count, size, digest in ranked[:40]:
        _, corner, materials = exact[digest][0]
        made_of = ', '.join('%s x%d' % (n.replace('minecraft:', ''), c) for n, c in materials[:5])
        print('%7d %7d %9d  %-22s %s'
              % (count, size, count * size, '%d %d %d' % corner, made_of))


if __name__ == '__main__':
    main()
