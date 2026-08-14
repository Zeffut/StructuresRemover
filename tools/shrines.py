#!/usr/bin/env python3
"""Measure the method against the map's own answer key.

The map ships a datapack whose markers say where each of the 137 shrines is. Those positions are
never used to find anything — they are only used afterwards, to check what the scan found on its
own. For every marker this reports which clump the scan built there, how big it is and what it is
made of, and then how those 137 clumps group together.

Written to shrines.pickle so the grouping can be tried different ways without re-reading the map.
"""
import os
import pickle
import sys
from collections import Counter, defaultdict
from multiprocessing import Pool

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import discover

OUT = 'shrines.pickle'


def markers():
    """The 137 shrine positions from the map's datapack, as integer block positions."""
    with open('shrine_markers.pickle', 'rb') as fh:
        entries = pickle.load(fh)

    out = []

    for tags, kind, pos in entries:
        if 'botw.shrine.exterior' not in tags:
            continue

        name = next((t for t in tags if t.startswith('botw.shrine.') and t != 'botw.shrine.exterior'),
                    'botw.shrine.?')
        out.append((name, (int(pos[0] // 1), int(pos[1] // 1), int(pos[2] // 1))))

    return out


def distance(blob, point):
    """How far the nearest block of a clump is from a point, in blocks."""
    px, py, pz = point
    return min(max(abs(x - px), abs(y - py), abs(z - pz)) for x, y, z in blob)


def find_in_region(args):
    """For each marker in one region, the clump the scan built around it."""
    (rx, rz), wanted = args
    blocks = discover.load_blocks(rx, rz)

    if blocks is None:
        return []

    found = list(discover.clumps(blocks, discover.MIN_SIZE, discover.MAX_SIZE))
    out = []

    for name, point in wanted:
        near = [blob for blob in found if distance(blob, point) <= 24]

        if not near:
            out.append((name, point, None))
            continue

        # The shrine is the biggest thing standing at the marker; anything else nearby is scenery.
        blob = max(near, key=len)
        digest, size = discover.canonical(blob, blocks)
        corner = (min(c[0] for c in blob), min(c[1] for c in blob), min(c[2] for c in blob))
        materials = Counter(blocks[c].split('[')[0] for c in blob)
        cells = frozenset((x - corner[0], y - corner[1], z - corner[2], blocks[(x, y, z)])
                          for x, y, z in blob)
        out.append((name, point, (digest, size, corner, tuple(materials.most_common(10)), cells)))

    return out


def main():
    wanted = defaultdict(list)

    for name, point in markers():
        wanted[(point[0] >> 9, point[2] >> 9)].append((name, point))

    print('%d shrines across %d regions' % (sum(len(v) for v in wanted.values()), len(wanted)),
          flush=True)

    results = []
    done = 0

    with Pool(4) as pool:
        for found in pool.imap_unordered(find_in_region, sorted(wanted.items()), chunksize=1):
            done += 1
            results.extend(found)

            if done % 10 == 0:
                hit = sum(1 for _, _, r in results if r is not None)
                print('  %d/%d regions, %d shrines matched to a clump'
                      % (done, len(wanted), hit), flush=True)

    with open(OUT, 'wb') as fh:
        pickle.dump(results, fh)

    missing = [(n, p) for n, p, r in results if r is None]
    hits = [(n, p, r) for n, p, r in results if r is not None]
    print('\n%d of %d shrines sit in a clump the scan found' % (len(hits), len(results)))

    for name, point in missing[:10]:
        print('  no clump at %s %s' % (name, point))

    sizes = sorted(r[1] for _, _, r in hits)

    if sizes:
        print('clump size: smallest %d, median %d, largest %d'
              % (sizes[0], sizes[len(sizes) // 2], sizes[-1]))

    exact = defaultdict(list)

    for name, point, r in hits:
        exact[r[0]].append(name)

    repeated = sorted((len(v) for v in exact.values()), reverse=True)
    print('exact fingerprints: %d distinct, biggest group %d shrines'
          % (len(exact), repeated[0] if repeated else 0))

    common = Counter()

    for _, _, r in hits:
        for name, _count in r[3]:
            common[name] += 1

    print('\nmaterials, and how many of the %d shrines contain them:' % len(hits))

    for name, count in common.most_common(12):
        print('  %-45s %d' % (name.replace('minecraft:', ''), count))


if __name__ == '__main__':
    main()
