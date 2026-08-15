#!/usr/bin/env python3
"""Read the tagged marker entities the map's own datapack places.

The shrine datapack tags a marker at each shrine. Those markers are in the map itself, not in any
scan artefact, so they can always be read back: they are the one piece of ground truth that does not
depend on anything this project produced.

Prints every distinct tag with how many entities carry it, and writes the positions of the ones
asked for.
"""
import glob
import os
import sys
from collections import Counter, defaultdict
from multiprocessing import Pool

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nbt
import survey

ENTITY_DIR = 'extracted/botwproject/entities'


def read_region(path):
    """Every tagged entity of one entity region file, as (tags, position)."""
    out = []

    for cx, cz, offset, sectors in survey.chunk_entries(path):
        raw = survey.read_chunk(path, offset, sectors)

        if raw is None:
            continue

        try:
            root = nbt.parse(raw)
        except Exception:
            continue

        for entity in root.get('Entities', []):
            tags = entity.get('Tags')

            if not tags:
                continue

            pos = entity.get('Pos')

            if not pos or len(pos) < 3:
                continue

            out.append((tuple(tags), (int(pos[0] // 1), int(pos[1] // 1), int(pos[2] // 1))))

    return out


def main():
    wanted = sys.argv[1] if len(sys.argv) > 1 else None
    files = sorted(glob.glob(os.path.join(ENTITY_DIR, '*.mca')))
    print('reading %d entity regions' % len(files), flush=True)

    found = []

    with Pool(4) as pool:
        for entities in pool.imap_unordered(read_region, files, chunksize=8):
            found.extend(entities)

    print('%d tagged entities' % len(found))

    counts = Counter(tag for tags, _pos in found for tag in tags)
    print('\ntags carried by more than one entity:')

    for tag, count in counts.most_common(25):
        if count > 1:
            print('  %-40s %d' % (tag, count))

    if not wanted:
        return

    picked = [(tags, pos) for tags, pos in found if any(wanted in tag for tag in tags)]
    print('\n%d entities carry a tag containing %r' % (len(picked), wanted))

    if not picked:
        return

    xs = [p[0] for _t, p in picked]
    ys = [p[1] for _t, p in picked]
    zs = [p[2] for _t, p in picked]
    print('  x %d..%d   y %d..%d   z %d..%d'
          % (min(xs), max(xs), min(ys), max(ys), min(zs), max(zs)))

    out_path = wanted.replace('.', '_') + '.txt'

    with open(out_path, 'w') as out:
        for tags, pos in sorted(picked, key=lambda e: e[1]):
            name = next((t for t in tags if t.startswith('botw.shrine.') and t.count('.') == 2
                         and t.rsplit('.', 1)[1].isdigit()), tags[0])
            out.write('%s %d %d %d\n' % (name, pos[0], pos[1], pos[2]))

    print('  written to %s' % out_path)

    for tags, pos in sorted(picked, key=lambda e: e[1])[:8]:
        print('    %s  %s' % (pos, ','.join(tags)))


if __name__ == '__main__':
    main()
