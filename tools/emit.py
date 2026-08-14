#!/usr/bin/env python3
"""Turn the discovered structure groups into an explicit list of blocks to delete.

Only groups that repeat are considered, and only the blocks of those clumps ever reach the list.
Terrain is never in it, because terrain was excluded before the clumps were formed at all.
"""
import os
import pickle
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import discover
import survey


def region_of(x, z):
    return x >> 9, z >> 9


def main():
    min_copies = int(sys.argv[1]) if len(sys.argv) > 1 else 3
    min_blocks = int(sys.argv[2]) if len(sys.argv) > 2 else 25
    out_path = sys.argv[3] if len(sys.argv) > 3 else 'purge.txt'

    groups = pickle.load(open('structures.pickle', 'rb'))
    chosen = {d: v for d, v in groups.items() if len(v) >= min_copies and v[0][0] >= min_blocks}

    print('%d shapes total, %d repeated at least %d times with at least %d blocks'
          % (len(groups), len(chosen), min_copies, min_blocks))

    instances = sum(len(v) for v in chosen.values())
    blocks_estimate = sum(len(v) * v[0][0] for v in chosen.values())
    print('  %d instances, about %d blocks' % (instances, blocks_estimate))

    # Which region each wanted instance sits in, so each region file is read once.
    wanted = defaultdict(list)

    for digest, entries in chosen.items():
        for size, x, y, z in entries:
            wanted[region_of(x, z)].append((digest, size, x, y, z))

    print('  spread over %d regions' % len(wanted))

    written = 0
    matched = 0

    with open(out_path, 'w') as out:
        for done, ((rx, rz), items) in enumerate(sorted(wanted.items()), 1):
            path = os.path.join(discover.REGION_DIR, 'r.%d.%d.mca' % (rx, rz))

            if not os.path.isfile(path):
                continue

            # Re-derive the clumps of this region exactly as the scan did, then keep the ones whose
            # fingerprint is on the wanted list.
            found = discover.scan_region((path, 25, 20000))
            by_key = {(d, x, y, z) for d, s, x, y, z in found}
            blocks = discover.collect(path)

            for nx, nz in ((rx - 1, rz), (rx + 1, rz), (rx, rz - 1), (rx, rz + 1),
                           (rx - 1, rz - 1), (rx + 1, rz - 1), (rx - 1, rz + 1), (rx + 1, rz + 1)):
                neighbour = os.path.join(discover.REGION_DIR, 'r.%d.%d.mca' % (nx, nz))

                if os.path.isfile(neighbour):
                    def near(cx, cz, rx=rx, rz=rz):
                        return (min(abs(cx - (rx << 5)), abs(cx - ((rx << 5) + 31))) <= 2
                                and min(abs(cz - (rz << 5)), abs(cz - ((rz << 5) + 31))) <= 2)

                    blocks.update(discover.collect(neighbour, near))

            x0, x1 = rx << 9, (rx << 9) + 511
            z0, z1 = rz << 9, (rz << 9) + 511
            keys = {(d, x, y, z) for d, s, x, y, z in items}

            for blob in discover.clumps(blocks, 25, 20000):
                cx = sum(c[0] for c in blob) // len(blob)
                cz = sum(c[2] for c in blob) // len(blob)

                if not (x0 <= cx <= x1 and z0 <= cz <= z1):
                    continue

                digest, size = discover.canonical(blob, blocks)
                key = (digest, min(c[0] for c in blob), min(c[1] for c in blob), min(c[2] for c in blob))

                if key not in keys:
                    continue

                matched += 1

                for x, y, z in blob:
                    name = blocks[(x, y, z)].split('[')[0]
                    out.write('%d %d %d %s\n' % (x, y, z, name))
                    written += 1

            if done % 25 == 0:
                print('  %d/%d regions, %d instances, %d blocks'
                      % (done, len(wanted), matched, written), flush=True)

    print('\nwrote %d blocks for %d instances to %s' % (written, matched, out_path))


if __name__ == '__main__':
    main()
