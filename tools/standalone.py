#!/usr/bin/env python3
"""Keep only the repeated structures that stand on their own.

Lowering the size floor finds thousands more repeated things, and they are not all the same kind of
thing. Some are props the builder placed over and over, standing by themselves in the landscape —
removing one leaves a clean gap. Others are pieces of something bigger: a patch of a wall, a section
of floor, repeated because the building it belongs to repeats. Removing those does not delete a
structure, it puts holes in one.

What tells them apart is what stands near them. A prop in a field has ground and air around it; a
patch of wall has the rest of the village. So each candidate is looked at on the map: count the
built blocks standing within a few blocks of it, and keep the family only if its members are, as a
rule, out on their own.

Asking what *touches* them instead was the first attempt and it measured nothing at all: a clump is
a maximal connected group, so anything built that touches it is already part of it. Every family
passed, which is how the mistake showed.
"""
import os
import sys
from collections import Counter, defaultdict
from multiprocessing import Pool

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import discover
import emit_families
import families

# How far to look around a structure, and how much built material may stand that close before it
# counts as part of a built-up place rather than a thing on its own, as a share of its own size.
#
# Touching was the first test and it was meaningless: a clump is a maximal connected group, so
# nothing built can touch it without being part of it, and every family passed. What separates a
# prop in a field from a patch of a wall is what stands *near* it.
NEAR = 3
CROWD_LIMIT = 0.5

# How many members of a family to look at, and how many of them must stand alone.
SAMPLE = 12
MUST_BE_ALONE = 0.75


def crowding(args):
    """For some members of one region, how much built material stands near each."""
    (rx, rz), wanted = args
    blocks = discover.load_blocks(rx, rz)

    if blocks is None:
        return []

    targets = dict(wanted)
    out = []

    for blob in discover.owned_clumps(rx, rz, blocks):
        corner = (min(c[0] for c in blob), min(c[1] for c in blob), min(c[2] for c in blob))
        key = (corner, len(blob))

        if key not in targets:
            continue

        own = set(blob)
        low = tuple(min(c[i] for c in blob) - NEAR for i in range(3))
        high = tuple(max(c[i] for c in blob) + NEAR for i in range(3))
        crowd = sum(1 for cell in blocks
                    if cell not in own
                    and all(low[i] <= cell[i] <= high[i] for i in range(3)))
        out.append((targets[key], crowd / max(1, len(blob))))

    return out


def main():
    min_members = int(sys.argv[1]) if len(sys.argv) > 1 else 8
    min_blocks = int(sys.argv[2]) if len(sys.argv) > 2 else 25
    out_path = sys.argv[3] if len(sys.argv) > 3 else 'standalone_families.txt'

    clumps, _scanned = discover.load()
    already = {(e[1], e[2], e[3], e[0]) for f in families.families(clumps) for e in f['members']}
    left = [e for e in clumps if (e[1], e[2], e[3], e[0]) not in already and e[0] >= min_blocks]

    candidates = [f for f in families.families(left, min_members=min_members, min_blocks=min_blocks)
                  if f['median'] < families.MIN_BLOCKS]
    print('%d families of small repeated things, %d structures, %d blocks'
          % (len(candidates), sum(f['count'] for f in candidates),
             sum(f['blocks'] for f in candidates)), flush=True)

    by_region = defaultdict(list)

    for index, family in enumerate(candidates):
        for entry in family['members'][:SAMPLE]:
            key = ((entry[1], entry[2], entry[3]), entry[0])

            for region in discover.regions_of(entry):
                by_region[region].append((key, index))

    print('looking at what stands near them, across %d regions' % len(by_region), flush=True)

    scores = defaultdict(list)
    done = 0

    with Pool(4) as pool:
        for results in pool.imap_unordered(crowding, sorted(by_region.items()), chunksize=1):
            done += 1

            for index, share in results:
                scores[index].append(share)

            if done % 100 == 0:
                print('  %d/%d regions' % (done, len(by_region)), flush=True)

    alone = []
    attached = []

    for index, family in enumerate(candidates):
        seen = scores.get(index, [])

        if not seen:
            continue

        share = sum(1 for s in seen if s <= CROWD_LIMIT) / len(seen)
        (alone if share >= MUST_BE_ALONE else attached).append((family, share))

    print('\n%d families stand alone: %d structures, %d blocks'
          % (len(alone), sum(f['count'] for f, _ in alone), sum(f['blocks'] for f, _ in alone)))
    print('%d families stand in a built-up place and are left alone: %d structures, %d blocks'
          % (len(attached), sum(f['count'] for f, _ in attached),
             sum(f['blocks'] for f, _ in attached)))

    wanted = defaultdict(list)

    for family, _share in alone:
        for entry in family['members']:
            for region in discover.regions_of(entry):
                wanted[region].append(((entry[1], entry[2], entry[3]), entry[0]))

    print('\nreading back %d regions for their blocks' % len(wanted), flush=True)
    written = 0
    matched = 0
    done = 0

    with open(out_path, 'w') as out, Pool(4) as pool:
        for count, lines in pool.imap_unordered(
                emit_families.emit_region, sorted(wanted.items()), chunksize=1):
            done += 1
            matched += count
            written += len(lines)

            if lines:
                out.write('\n'.join(lines))
                out.write('\n')

            if done % 50 == 0:
                print('  %d/%d regions, %d structures, %d blocks'
                      % (done, len(wanted), matched, written), flush=True)

    print('wrote %d blocks for %d structures to %s' % (written, matched, out_path))

    print('\nthe ones to take, biggest first:')

    for family, share in sorted(alone, key=lambda item: -item[0]['blocks'])[:12]:
        sample = family['members'][0]
        print('  %5d x %3d blocks  %-44s %d %d %d'
              % (family['count'], family['median'],
                 ', '.join(sorted(m.replace('minecraft:', '') for m in family['materials']))[:44],
                 sample[1], sample[2], sample[3]))

    print('\nleft alone for standing among other buildings:')

    for family, share in sorted(attached, key=lambda item: -item[0]['blocks'])[:8]:
        sample = family['members'][0]
        print('  %5d x %3d blocks  %-36s attached %.0f%%  %d %d %d'
              % (family['count'], family['median'],
                 ', '.join(sorted(m.replace('minecraft:', '') for m in family['materials']))[:36],
                 100 * (1 - share), sample[1], sample[2], sample[3]))

    return 0


if __name__ == '__main__':
    sys.exit(main())
