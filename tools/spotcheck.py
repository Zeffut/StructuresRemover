"""An independent spot-check of a cleaned copy.

    python3 spotcheck.py server_verify2.txt /path/to/cleanmap/region 20000
    python3 spotcheck.py server_verify2.txt /path/to/original/region 20000

Samples positions out of a deletion list at random, reads them straight out of the region files with
terrain included, and says what is actually standing there now. It never consults the run's own
report — the point is to ask the map, not to re-read what the mod said it did.

Run it against both copies. On the cleaned one every sampled position should be empty; on the
original every one should still hold the block the list names. Only the pair means anything: a
check that finds nothing standing proves the cleaning worked, or proves it was pointed at an empty
directory, and on its own it cannot say which.

That pair is also how two separately cleaned copies are compared without moving either of them.
Region files cannot be compared byte for byte — Anvil keeps a per-chunk modification timestamp in
its header, so two runs of the same deletion on the same map produce different bytes. What can be
compared is what the maps now contain.
"""
import os
import random
import sys
from collections import Counter, defaultdict
from multiprocessing import Pool

sys.path.insert(0, '/home/user/botw')
import discover
import nbt
import survey


def full_blocks(path, wanted):
    """{(x, y, z): name} for just the positions asked for, terrain and all; air simply absent."""
    out = {}
    by_chunk = defaultdict(list)

    for x, y, z in wanted:
        by_chunk[(x >> 4, z >> 4)].append((x, y, z))

    for cx, cz, offset, sectors in survey.chunk_entries(path):
        here = by_chunk.get((cx, cz))

        if not here:
            continue

        raw = survey.read_chunk(path, offset, sectors)

        if raw is None:
            continue

        try:
            root = nbt.parse(raw)
        except Exception:
            continue

        want = set(here)

        for section in root.get('sections', []):
            base = section.get('Y', 0) << 4

            if not any(base <= y <= base + 15 for _x, y, _z in here):
                continue

            for x, y, z, full in discover.all_section_blocks(section):
                key = ((cx << 4) + x, base + y, (cz << 4) + z)

                if key in want:
                    name = full.split('[')[0]

                    if name != 'minecraft:air':
                        out[key] = name

    return out


def check(args):
    region_dir, (rx, rz), want = args
    path = os.path.join(region_dir, 'r.%d.%d.mca' % (rx, rz))

    if not os.path.isfile(path):
        return None, []

    here = full_blocks(path, [(x, y, z) for x, y, z, _n in want])
    gone = 0
    left = []

    for x, y, z, name in want:
        found = here.get((x, y, z))

        if found is None:
            gone += 1
        else:
            left.append((x, y, z, name, found))

    return gone, left


def main():
    listing, region_dir, count = sys.argv[1], sys.argv[2], int(sys.argv[3])
    rows = []

    with open(listing) as fh:
        for line in fh:
            parts = line.split()

            if len(parts) >= 4:
                rows.append((int(parts[0]), int(parts[1]), int(parts[2]), parts[3]))

    random.seed(7)
    sample = random.sample(rows, min(count, len(rows)))
    print('%d listed positions; asking the map about %d of them, in %s'
          % (len(rows), len(sample), region_dir), flush=True)

    by_region = defaultdict(list)

    for x, y, z, name in sample:
        by_region[(x >> 9, z >> 9)].append((x, y, z, name))

    gone = 0
    missing = 0
    left = []
    done = 0

    with Pool(4) as pool:
        for count_gone, remains in pool.imap_unordered(
                check, [(region_dir, key, want) for key, want in sorted(by_region.items())],
                chunksize=1):
            done += 1

            if count_gone is None:
                missing += 1
                continue

            gone += count_gone
            left.extend(remains)

            if done % 200 == 0:
                print('  %d/%d regions' % (done, len(by_region)), flush=True)

    print('\n  cleared, nothing there now: %d' % gone)
    print('  still standing:              %d' % len(left))

    if missing:
        print('  regions absent from this copy: %d' % missing)

    counts = Counter('%s is now %s' % (name, found) for _x, _y, _z, name, found in left)

    for text, n in counts.most_common(10):
        print('    %s x%d' % (text, n))

    return 0


if __name__ == '__main__':
    sys.exit(main())
