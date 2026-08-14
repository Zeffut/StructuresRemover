#!/usr/bin/env python3
"""Turn the confirmed groups of identical structures into an explicit list of blocks to delete.

Only groups whose members were fingerprinted as copies block for block are considered, and only the
blocks of those clumps ever reach the list. Terrain is never in it, because terrain was excluded
before the clumps were formed at all — it is not that it is filtered out later, it was never there.
"""
import os
import pickle
import sys
from collections import defaultdict
from multiprocessing import Pool

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import discover


def emit_region(args):
    """The blocks of every wanted clump in one region, as ``x y z block`` lines."""
    (rx, rz), corners = args
    blocks = discover.load_blocks(rx, rz)

    if blocks is None:
        return 0, []

    wanted = set(corners)
    lines = []
    matched = 0

    for blob in discover.owned_clumps(rx, rz, blocks):
        corner = (min(c[0] for c in blob), min(c[1] for c in blob), min(c[2] for c in blob))

        if corner not in wanted:
            continue

        matched += 1

        for cell in blob:
            lines.append('%d %d %d %s' % (cell + (blocks[cell].split('[')[0],)))

    return matched, lines


def main():
    min_copies = int(sys.argv[1]) if len(sys.argv) > 1 else 2
    min_blocks = int(sys.argv[2]) if len(sys.argv) > 2 else discover.MIN_SIZE
    out_path = sys.argv[3] if len(sys.argv) > 3 else 'purge.txt'

    with open('exact.pickle', 'rb') as fh:
        exact = pickle.load(fh)

    chosen = {d: v for d, v in exact.items() if len(v) >= min_copies and v[0][0] >= min_blocks}
    print('%d groups total, %d kept (at least %d copies, at least %d blocks)'
          % (len(exact), len(chosen), min_copies, min_blocks))

    by_region = defaultdict(list)

    for entries in chosen.values():
        for size, corner, materials in entries:
            # Grown by the structure's own size, because a structure lying across a region border is
            # claimed by the region holding its centre, which is not the one holding its corner.
            span = int(round(size ** (1 / 3))) + 2
            entry = (size, corner[0], corner[1], corner[2], span, span, span, ())

            for region in discover.regions_of(entry):
                by_region[region].append(corner)

    instances = sum(len(v) for v in by_region.values())
    print('  %d instances spread over %d regions' % (instances, len(by_region)), flush=True)

    written = 0
    matched = 0
    done = 0

    with open(out_path, 'w') as out, Pool(4) as pool:
        for count, lines in pool.imap_unordered(emit_region, sorted(by_region.items()), chunksize=1):
            done += 1
            matched += count
            written += len(lines)

            if lines:
                out.write('\n'.join(lines))
                out.write('\n')

            if done % 50 == 0:
                print('  %d/%d regions, %d instances, %d blocks'
                      % (done, len(by_region), matched, written), flush=True)

    print('\nwrote %d blocks for %d instances to %s' % (written, matched, out_path))

    if matched != instances:
        print('WARNING: %d instances were expected but %d were found again'
              % (instances, matched))


if __name__ == '__main__':
    main()
