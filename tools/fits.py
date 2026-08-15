#!/usr/bin/env python3
"""Check that a deletion list describes the map it is about to be applied to.

A list is built by reading one map. Applied to another it is worse than useless: where the two maps
happen to agree it deletes the right block for the wrong reason, and where they do not it names a
block that is not there. Both happened here — a list from the project's archive, tried against the
server's own world, had 66,825 positions holding landscape and 303 kinds of name mismatch — and
neither showed up until the purge was already running.

So it is asked in advance, by reading the map. For every listed position: is the block standing
there the one the list names?
"""
import os
import sys
from collections import Counter, defaultdict
from multiprocessing import Pool

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import discover


def main():
    listing = sys.argv[1]
    workers = int(sys.argv[2]) if len(sys.argv) > 2 else 4

    by_region = defaultdict(list)

    with open(listing) as fh:
        for line in fh:
            parts = line.split()

            if len(parts) >= 4:
                x, y, z = int(parts[0]), int(parts[1]), int(parts[2])
                by_region[(x >> 9, z >> 9)].append((x, y, z, parts[3]))

    total = sum(len(v) for v in by_region.values())
    print('%d positions across %d regions of %s'
          % (total, len(by_region), discover.REGION_DIR), flush=True)

    agree = 0
    landscape = Counter()
    mismatch = Counter()
    absent = 0
    done = 0

    with Pool(workers) as pool:
        for result in pool.imap_unordered(_one, sorted(by_region.items()), chunksize=1):
            done += 1
            a, l, m, ab = result
            agree += a
            landscape.update(l)
            mismatch.update(m)
            absent += ab

            if done % 50 == 0:
                print('  %d/%d regions, %d agree' % (done, len(by_region), agree), flush=True)

    wrong = sum(landscape.values()) + sum(mismatch.values()) + absent
    print('\n%d of %d listed positions hold the block the list names (%.1f%%)'
          % (agree, total, 100.0 * agree / max(1, total)))
    print('%d hold a landscape block' % sum(landscape.values()))
    print('%d hold a different built block' % sum(mismatch.values()))
    print('%d are in a region that is not there' % absent)

    for name, count in landscape.most_common(5):
        print('  landscape: %s x%d' % (name, count))

    for name, count in mismatch.most_common(5):
        print('  different: %s x%d' % (name, count))

    if wrong > total // 1000:
        print('\nThis list does not describe this map. Build it from the map it will be applied to.')
        return 1

    return 0


def _one(item):
    (rx, rz), wanted = item
    blocks = discover.load_blocks(rx, rz)

    if blocks is None:
        return 0, Counter(), Counter(), len(wanted)

    agree = 0
    landscape = Counter()
    mismatch = Counter()

    for x, y, z, name in wanted:
        present = blocks.get((x, y, z))

        if present is None:
            # Not in the non-terrain map: either landscape or air. Both mean the list is wrong here,
            # and the purge would refuse it, so it is counted rather than passed over.
            landscape[name] += 1
        elif present.split('[')[0] == name:
            agree += 1
        else:
            mismatch[present.split('[')[0]] += 1

    return agree, landscape, mismatch, 0


if __name__ == '__main__':
    sys.exit(main())
