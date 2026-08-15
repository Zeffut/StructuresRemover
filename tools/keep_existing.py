#!/usr/bin/env python3
"""Drop from a deletion list every position whose chunk does not exist.

This has to happen before the list reaches the game. Asking the server for a block in a chunk the
world has never generated does not come back empty: it generates the chunk, writes it to disk and
returns fresh terrain. A deletion pass then quietly becomes a world generator — on this map that was
617,368 blocks brought into existence that were on no list, and two region files that had not been
there before.

The mod cannot settle this for itself; the server has no public way to ask whether a chunk exists
without loading it, and the question it can answer — "is this chunk loaded right now" — is a
different one that skips nearly everything. But the region files answer it exactly, and they are
right here.
"""
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import discover
import survey


def existing_chunks(region):
    """The chunks a region file actually holds, as {(chunk x, chunk z)}."""
    path = os.path.join(discover.REGION_DIR, 'r.%d.%d.mca' % region)

    if not os.path.isfile(path):
        return set()

    return {(cx, cz) for cx, cz, _offset, _sectors in survey.chunk_entries(path)}


def main():
    listing = sys.argv[1] if len(sys.argv) > 1 else 'all_purge2.txt'
    out_path = sys.argv[2] if len(sys.argv) > 2 else 'all_purge_final.txt'

    wanted = defaultdict(list)
    total = 0

    with open(listing) as fh:
        for line in fh:
            parts = line.split()

            if len(parts) < 4:
                continue

            total += 1
            x, z = int(parts[0]), int(parts[2])
            wanted[(x >> 9, z >> 9)].append((x, int(parts[1]), z, parts[3]))

    print('%d positions across %d regions' % (total, len(wanted)), flush=True)

    kept = 0
    dropped = 0
    missing_regions = 0

    with open(out_path, 'w') as out:
        for region in sorted(wanted):
            chunks = existing_chunks(region)

            if not chunks:
                missing_regions += 1
                dropped += len(wanted[region])
                continue

            for x, y, z, name in wanted[region]:
                if (x >> 4, z >> 4) in chunks:
                    out.write('%d %d %d %s\n' % (x, y, z, name))
                    kept += 1
                else:
                    dropped += 1

    print('%d kept, %d dropped for sitting in a chunk that does not exist' % (kept, dropped))
    print('%d of the regions do not exist at all' % missing_regions)
    print('written to %s' % out_path)


if __name__ == '__main__':
    main()
