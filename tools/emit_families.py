#!/usr/bin/env python3
"""Write the blocks of every member of the chosen families as an explicit deletion list.

Members are located by re-deriving the clumps of their region exactly as the scan did, then keeping
the ones the scan claimed. Nothing is described by a box: every block written was individually part
of a structure, and terrain was excluded before those structures were assembled.
"""
import os
import sys
from collections import defaultdict
from multiprocessing import Pool

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import discover
import families


def emit_region(args):
    """The blocks of every wanted member in one region, as ``x y z block`` lines."""
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
    out_path = sys.argv[1] if len(sys.argv) > 1 else 'repeats_purge.txt'
    min_members = int(sys.argv[2]) if len(sys.argv) > 2 else families.MIN_MEMBERS
    min_blocks = int(sys.argv[3]) if len(sys.argv) > 3 else families.MIN_BLOCKS

    clumps, _scanned = discover.load()
    chosen = families.families(clumps, min_members, min_blocks)
    print('%d families, %d structures, about %d blocks'
          % (len(chosen), sum(f['count'] for f in chosen), sum(f['blocks'] for f in chosen)),
          flush=True)

    by_region = defaultdict(list)

    for family in chosen:
        for entry in family['members']:
            for region in discover.regions_of(entry):
                by_region[region].append((entry[1], entry[2], entry[3]))

    print('spread over %d regions' % len(by_region), flush=True)

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
                print('  %d/%d regions, %d structures, %d blocks'
                      % (done, len(by_region), matched, written), flush=True)

    expected = sum(f['count'] for f in chosen)
    print('\nwrote %d blocks for %d structures to %s' % (written, matched, out_path))

    if matched != expected:
        print('WARNING: %d structures were expected, %d were found again' % (expected, matched))


if __name__ == '__main__':
    main()
