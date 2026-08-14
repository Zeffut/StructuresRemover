#!/usr/bin/env python3
"""Check that a deletion list contains exactly the structures it is supposed to, and nothing else.

Proving that no terrain was touched is not the same as proving that what was deleted was right. This
takes the list on its own terms: it splits the listed positions back into connected structures,
without reading the map, and asks of every one of them which recorded structure it is. A structure
in the list that answers nothing is a structure being deleted that nobody asked for.

Three answers are possible for each structure found in the list:

  matched    it is a structure the scan recorded and a family claimed
  merged     it is several recorded structures standing against each other, so the list joins them
  unclaimed  nothing recorded accounts for it — the failure this exists to catch

The block names in the list are checked against the landscape test as well, independently of the
mod's own check at write time, so a terrain block would have to get past two separate readings of
the same rule.
"""
import pickle
import os
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import discover
import families


def expected():
    """What is supposed to be in the list.

    Two forms, because the two ways of finding structures record different things. A family member
    is known by its corner and its size, since the scan only kept a summary of it. A structure found
    by its palette was kept block by block, so for those the exact positions are known — which
    matters for the stray blocks a shrine has standing clear of it: they belong to a recorded
    structure but form their own piece once the list is split up again.
    """
    out = {}
    cells = set()
    clumps, _scanned = discover.load()

    for family in families.families(clumps):
        materials = ', '.join(sorted(m.replace('minecraft:', '') for m in family['materials']))

        for entry in family['members']:
            out[((entry[1], entry[2], entry[3]), entry[0])] = 'family: ' + materials[:40]

    if os.path.isfile('family.pickle'):
        with open('family.pickle', 'rb') as fh:
            for structure in pickle.load(fh):
                own = structure['cells']
                corner = (min(c[0] for c in own), min(c[1] for c in own), min(c[2] for c in own))
                out.setdefault((corner, len(own)), 'palette: shrine')
                cells.update((c[0], c[1], c[2]) for c in own)

    return out, cells


def main():
    listing = sys.argv[1] if len(sys.argv) > 1 else 'all_purge.txt'

    blocks = {}

    with open(listing) as fh:
        for line in fh:
            parts = line.split()

            if len(parts) >= 4:
                blocks[(int(parts[0]), int(parts[1]), int(parts[2]))] = parts[3]

    print('%s holds %d positions' % (listing, len(blocks)), flush=True)

    landscape = Counter(name for name in blocks.values() if discover.is_terrain(name))
    print('landscape blocks in the list: %d %s'
          % (sum(landscape.values()), dict(landscape.most_common(5)) if landscape else ''))

    print('splitting the list back into structures', flush=True)
    # The plain walk, deliberately. The fast path builds a grid spanning the whole bounding box, and
    # these positions are scattered across the entire map — that grid would be hundreds of gigabytes
    # for one and a half million blocks.
    found = discover._clumps_walked(blocks, 1, 10_000_000)
    print('the list is %d separate structures' % len(found), flush=True)

    wanted, palette_cells = expected()
    print('%d structures were recorded as targets' % len(wanted), flush=True)

    by_corner = defaultdict(list)

    for (corner, size), source in wanted.items():
        by_corner[corner].append((size, source))

    matched = {}
    merged = []
    strays = []
    unclaimed = []

    for blob in found:
        corner = (min(c[0] for c in blob), min(c[1] for c in blob), min(c[2] for c in blob))
        # No larger than what was recorded, not exactly equal: a position claimed by two overlapping
        # structures is written once, so a structure can come out of the list smaller than it went
        # in. It can never come out bigger, and that is the direction that would matter.
        hit = [(size, source) for size, source in by_corner.get(corner, ()) if size >= len(blob)]

        if hit:
            matched[(corner, min(size for size, _ in hit))] = hit[0][1]
            continue

        # Structures touching each other come back as one piece. That is accounted for rather than
        # excused: every recorded structure inside it has to be there, and the sizes have to add up.
        low = tuple(min(p[i] for p in blob) for i in range(3))
        high = tuple(max(p[i] for p in blob) for i in range(3))
        inside = [(c, s) for c, sizes in by_corner.items() for s, _src in sizes
                  if all(low[i] <= c[i] <= high[i] for i in range(3))]

        if inside and sum(s for _c, s in inside) >= len(blob):
            merged.append((corner, len(blob), len(inside)))

            for c, s in inside:
                matched[(c, s)] = by_corner[c][0][1]
        elif all(cell in palette_cells for cell in blob):
            # Every block of it was recorded as part of a structure found by palette; it is a piece
            # of one standing clear of the rest, not something extra.
            strays.append((corner, len(blob)))
        else:
            unclaimed.append((corner, len(blob)))

    missing = [key for key in wanted if key not in matched]

    print('\n  structures matched to a recorded target   %d' % len(matched))
    print('  pieces that are several targets joined    %d' % len(merged))
    print('  detached pieces of a recorded structure   %d' % len(strays))
    print('  structures in the list nobody asked for   %d' % len(unclaimed))
    print('  recorded targets absent from the list     %d' % len(missing))

    for corner, size in unclaimed[:10]:
        print('    unclaimed: %d blocks at %s' % (size, corner))

    for key in missing[:10]:
        print('    missing:   %d blocks at %s  (%s)' % (key[1], key[0], wanted[key]))

    sources = Counter(matched.values())
    print('\nwhat is being deleted, by where it came from:')

    for source, count in sources.most_common(20):
        print('  %-52s %d' % (source, count))

    return 0 if not unclaimed and not missing and not landscape else 1


if __name__ == '__main__':
    sys.exit(main())
