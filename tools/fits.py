#!/usr/bin/env python3
"""Check that a deletion list describes the map it is about to be applied to.

A list is built by reading one map. Applied to another it is worse than useless: where the two maps
happen to agree it deletes the right block for the wrong reason, and where they do not it names a
block that is not there. Both happened here — a list from the project's archive, tried against the
server's own world, had 66,825 positions holding landscape and 303 kinds of name mismatch — and
neither showed up until the purge was already running.

So it is asked in advance, by reading the map. For every listed position: is the block standing
there the one the list names?

    python3 fits.py <listing> [workers] [landscape-list]

The third argument is the reason this file has a long docstring. Some listed positions are meant to
hold landscape — the creature bodies, which are built out of ice, stone, cobblestone and andesite —
and this tool reads landscape as a disagreement, because normally it is one. With the bodies inside
the main list that is 6,231 disagreements on a perfectly correct map, past the 0.1% threshold, and
the verdict comes back "this list does not describe this map" for the one map it does describe.

A check that condemns the correct case is worse than no check, because the way people get past it
is to stop reading it. So the positions where landscape is expected are named, exactly as
verify_purge.py already takes them, and counted apart from the ones that are not.
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

    # Positions where finding landscape is the expected answer rather than a disagreement. They
    # travel to the workers inside their region's work item, and nowhere else. An earlier version
    # kept them in a module-level global mutated here, on the grounds that forked workers inherit
    # it "which is how REGION_DIR already reaches them" — but REGION_DIR is read from os.environ,
    # and a spawned worker gets the environment while getting none of this process's mutations.
    # On Windows, where spawn is the only start method, that returned 0 expected instead of 6,231.
    expected_landscape = set()

    if len(sys.argv) > 3:
        with open(sys.argv[3]) as fh:
            for line in fh:
                parts = line.split()

                if len(parts) >= 3:
                    expected_landscape.add((int(parts[0]), int(parts[1]), int(parts[2])))

        print('%d positions where landscape is expected, from %s'
              % (len(expected_landscape), sys.argv[3]))

    by_region = defaultdict(list)

    with open(listing) as fh:
        for line in fh:
            parts = line.split()

            if len(parts) >= 4:
                x, y, z = int(parts[0]), int(parts[1]), int(parts[2])
                by_region[(x >> 9, z >> 9)].append((x, y, z, parts[3]))

    expected_by_region = defaultdict(set)

    for x, y, z in expected_landscape:
        expected_by_region[(x >> 9, z >> 9)].add((x, y, z))

    total = sum(len(v) for v in by_region.values())
    print('%d positions across %d regions of %s'
          % (total, len(by_region), discover.REGION_DIR), flush=True)

    work = [((rx, rz), wanted, expected_by_region.get((rx, rz), frozenset()))
            for (rx, rz), wanted in sorted(by_region.items())]

    agree = 0
    landscape = Counter()
    expected = Counter()
    mismatch = Counter()
    absent = 0
    done = 0

    with Pool(workers) as pool:
        for result in pool.imap_unordered(_one, work, chunksize=1):
            done += 1
            a, l, e, m, ab = result
            agree += a
            landscape.update(l)
            expected.update(e)
            mismatch.update(m)
            absent += ab

            if done % 50 == 0:
                print('  %d/%d regions, %d agree' % (done, len(by_region), agree), flush=True)

    wrong = sum(landscape.values()) + sum(mismatch.values()) + absent
    print('\n%d of %d listed positions hold the block the list names (%.2f%%)'
          % (agree, total, 100.0 * agree / max(1, total)))

    if expected_landscape:
        print('%d hold landscape where landscape was expected' % sum(expected.values()))

    print('%d hold a landscape block' % sum(landscape.values()))
    print('%d hold a different built block' % sum(mismatch.values()))
    print('%d are in a region that is not there' % absent)

    for name, count in landscape.most_common(5):
        print('  landscape: %s x%d' % (name, count))

    for name, count in mismatch.most_common(5):
        print('  different: %s x%d' % (name, count))

    # The percentage is not the measure and never was. A list holding 6,231 creature bodies cannot
    # reach 100% against the map it was built from, so reading the percentage alone rejects the one
    # map that is right. What measures drift is `wrong`: positions that disagree and were not
    # declared as places where they would.
    if expected_landscape and sum(expected.values()) != len(expected_landscape):
        print('\n%d of the %d declared landscape positions did not hold landscape. On a fresh copy '
              'that means it is not fresh.'
              % (len(expected_landscape) - sum(expected.values()), len(expected_landscape)))

    if wrong > total // 1000:
        print('\nThis list does not describe this map. Build it from the map it will be applied to.')
        return 1

    if wrong:
        print('\n%d positions disagree, under the 0.1%% threshold. That count is the drift: check '
              'it is renames and not somebody\'s building before going on.' % wrong)

    return 0


def _one(item):
    (rx, rz), wanted, expected_landscape = item
    blocks = discover.load_blocks(rx, rz)

    if blocks is None:
        return 0, Counter(), Counter(), Counter(), len(wanted)

    agree = 0
    landscape = Counter()
    expected = Counter()
    mismatch = Counter()

    for x, y, z, name in wanted:
        present = blocks.get((x, y, z))

        if present is None:
            # Not in the non-terrain map: either landscape or air. Normally that means the list is
            # wrong here and the purge would refuse it — unless this is one of the positions where
            # landscape is what was expected, which the caller has to say in advance.
            if (x, y, z) in expected_landscape:
                expected[name] += 1
            else:
                landscape[name] += 1
        elif present.split('[')[0] == name:
            agree += 1
        else:
            mismatch[present.split('[')[0]] += 1

    return agree, landscape, expected, mismatch, 0


if __name__ == '__main__':
    sys.exit(main())
