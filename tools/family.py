#!/usr/bin/env python3
"""Find every copy of one kind of structure, even when no two copies are identical.

Exact fingerprints cannot do this job. Of the 137 shrines on this map, no two are the same build:
they differ by a few blocks each, so matching block for block finds 122 different things where a
person sees one thing repeated 137 times.

What they do share is a palette. A shrine is made of grey concrete, light blue and grey glazed
terracotta, light blue stained glass and spruce — a combination that occurs nowhere else on the
map. So the structure is found by its materials instead of its shape:

  1. Sweep the map for the signature blocks alone, the ones that belong to nothing else, and join
     the ones that touch into cores.
  2. Keep a core only if it carries enough of the signature to be the real thing.
  3. Grow each core outwards into whatever else it is physically attached to, staying inside its own
     bounding box plus a small margin, so the whole shrine comes along and the house next door does
     not.

Terrain never enters at any step: the sweep only ever looks at blocks that are on a list of things
somebody placed, and growth is restricted to blocks the scanner already refuses to call landscape.
"""
import glob
import os
import pickle
import re
import sys
from collections import Counter, defaultdict, deque
from multiprocessing import Pool

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import discover
import nbt
import survey

OUT = os.environ.get('SR_FAMILY', 'family.pickle')

# Blocks that, on this map, mean "shrine" and nothing else.
#
# Light blue stained glass was in this list to begin with and had to come out: the map uses it by
# the hundred thousand elsewhere, so cores formed inside unrelated builds and then grew through
# them. What survives here is glazed terracotta, which the map uses almost nowhere but shrines, and
# grey concrete, which is common enough to be worth including but not on its own.
SIGNATURE = {
    'minecraft:light_blue_glazed_terracotta',
    'minecraft:gray_glazed_terracotta',
    'minecraft:blue_glazed_terracotta',
    'minecraft:gray_concrete',
}

# Of those, the ones that carry the decision. Measured over the whole map: every shrine has at least
# five of these, while of 441 things that were not shrines only 34 had any at all.
DISTINCTIVE = {
    'minecraft:light_blue_glazed_terracotta',
    'minecraft:gray_glazed_terracotta',
    'minecraft:blue_glazed_terracotta',
}

MIN_CORE = 12
MIN_DISTINCTIVE = 10

# How far past its core a structure is allowed to reach, and how big it may end up. The cap is what
# stops a core that formed inside a large build from dragging the whole build into the deletion
# list: the largest real shrine is about 1100 blocks.
MARGINS = (8, 4, 2, 0)
MAX_STRUCTURE = 3000

# How far out to look for stray distinctive blocks once a structure is settled.
SWEEP = 20
CORES = os.environ.get('SR_CORES', 'cores.pickle')


def palette_blocks(path, names, chunk_filter=None):
    """Every block of a region whose name is in ``names``.

    Reads far less than a full sweep: a section whose palette holds none of the wanted names is
    skipped without unpacking it, and on this map that is almost every section.
    """
    out = {}

    for cx, cz, offset, sectors in survey.chunk_entries(path):
        if chunk_filter is not None and not chunk_filter(cx, cz):
            continue

        raw = survey.read_chunk(path, offset, sectors)

        if raw is None:
            continue

        try:
            root = nbt.parse(raw)
        except Exception:
            continue

        ox, oz = cx << 4, cz << 4

        for section in root.get('sections', []):
            states = section.get('block_states') or {}
            palette = states.get('palette') or []

            if not any(entry.get('Name') in names for entry in palette):
                continue

            for x, y, z, full in discover.section_blocks(section):
                if full.split('[')[0] in names:
                    out[(ox + x, y, oz + z)] = full.split('[')[0]

    return out


def cores_in_region(args):
    """The shrine cores whose centre falls in one region."""
    path, = args
    match = re.match(r'r\.(-?\d+)\.(-?\d+)\.mca', os.path.basename(path))
    rx, rz = int(match.group(1)), int(match.group(2))

    blocks = palette_blocks(path, SIGNATURE)

    for nx, nz in ((rx - 1, rz), (rx + 1, rz), (rx, rz - 1), (rx, rz + 1),
                   (rx - 1, rz - 1), (rx + 1, rz - 1), (rx - 1, rz + 1), (rx + 1, rz + 1)):
        neighbour = os.path.join(os.path.dirname(path), 'r.%d.%d.mca' % (nx, nz))

        if not os.path.isfile(neighbour):
            continue

        def near(cx, cz, rx=rx, rz=rz):
            return (min(abs(cx - (rx << 5)), abs(cx - ((rx << 5) + 31))) <= 2
                    and min(abs(cz - (rz << 5)), abs(cz - ((rz << 5) + 31))) <= 2)

        blocks.update(palette_blocks(neighbour, SIGNATURE, near))

    if not blocks:
        return []

    x0, x1 = rx << 9, (rx << 9) + 511
    z0, z1 = rz << 9, (rz << 9) + 511
    out = []

    for blob in discover.clumps(blocks, MIN_CORE, 100_000):
        cx = sum(c[0] for c in blob) // len(blob)
        cz = sum(c[2] for c in blob) // len(blob)

        if not (x0 <= cx <= x1 and z0 <= cz <= z1):
            continue

        counts = Counter(blocks[c] for c in blob)
        distinctive = sum(n for name, n in counts.items() if name in DISTINCTIVE)

        if distinctive < MIN_DISTINCTIVE:
            continue

        low = (min(c[0] for c in blob), min(c[1] for c in blob), min(c[2] for c in blob))
        high = (max(c[0] for c in blob), max(c[1] for c in blob), max(c[2] for c in blob))
        out.append({'region': (rx, rz), 'size': len(blob), 'low': low, 'high': high,
                    'distinctive': distinctive, 'counts': dict(counts)})

    return out


def grow_region(args):
    """Grows each core of one region into the rest of the structure it is attached to."""
    (rx, rz), cores = args
    blocks = discover.load_blocks(rx, rz)

    if blocks is None:
        return []

    out = []

    for core in cores:
        low, high = core['low'], core['high']
        seeds = [c for c in blocks
                 if low[0] <= c[0] <= high[0] and low[1] <= c[1] <= high[1] and low[2] <= c[2] <= high[2]
                 and blocks[c].split('[')[0] in SIGNATURE]

        # Shrines standing against a village overflow into it at the roomier margins, and a shrine
        # dropped for being too big is a shrine left on the map. So the margin gives way instead:
        # each attempt reaches less far, and the last one takes the core by itself, which is made of
        # signature blocks and is a shrine whatever stands next to it.
        for margin in MARGINS:
            seen = _reach(blocks, seeds, low, high, margin)

            if len(seen) <= MAX_STRUCTURE:
                break
        else:
            seen = set(seeds)
            margin = None

        seen = _sweep_up(blocks, seen, low, high)
        counts = Counter(blocks[c].split('[')[0] for c in seen)
        out.append({'low': low, 'high': high, 'core': core['size'], 'size': len(seen),
                    'margin': margin, 'counts': dict(counts),
                    'cells': sorted((c[0], c[1], c[2], blocks[c].split('[')[0]) for c in seen)})

    return out


def _sweep_up(blocks, seen, low, high):
    """Picks up stray distinctive blocks the growth could not reach.

    A shrine's outlying bits — a step, a lamp bracket — sit a few blocks clear of everything else,
    so nothing connects them to the core and they are left standing after the rest is gone. Taking
    them needs no connection to justify it: glazed terracotta appears nowhere on this map but
    shrines, so one lying beside a confirmed shrine is part of it.
    """
    limit = (low[0] - SWEEP, low[1] - SWEEP, low[2] - SWEEP,
             high[0] + SWEEP, high[1] + SWEEP, high[2] + SWEEP)
    strays = [c for c, name in blocks.items()
              if c not in seen
              and limit[0] <= c[0] <= limit[3] and limit[1] <= c[1] <= limit[4]
              and limit[2] <= c[2] <= limit[5]
              and name.split('[')[0] in DISTINCTIVE]

    # The strays themselves and nothing else. Letting the growth run on from them was tried and
    # went badly: starting a fresh walk this far outside the core is enough of a foothold to reach
    # a neighbouring build, and the total jumped from 103,000 blocks to 172,000. What is actually
    # being justified here is the stray block, not its surroundings.
    return seen | set(strays)


def _reach(blocks, seeds, low, high, margin):
    """What the core can be walked to, staying inside its own box grown by ``margin``.

    Both limits matter: connectivity alone would follow a shrine that leans on a village straight
    into the village, and the box alone would take in whatever happens to stand beside it.
    """
    limit = (low[0] - margin, low[1] - margin, low[2] - margin,
             high[0] + margin, high[1] + margin, high[2] + margin)
    seen = set(seeds)
    queue = deque(seeds)

    while queue:
        x, y, z = queue.popleft()

        for dx, dy, dz in discover.NEIGHBOURS:
            cell = (x + dx, y + dy, z + dz)

            if cell in seen or cell not in blocks:
                continue

            if not (limit[0] <= cell[0] <= limit[3] and limit[1] <= cell[1] <= limit[4]
                    and limit[2] <= cell[2] <= limit[5]):
                continue

            seen.add(cell)
            queue.append(cell)

    return seen


def sweep(workers):
    """Phase one: find the cores, and keep them so the growth rules can be changed cheaply."""
    files = sorted(glob.glob(os.path.join(discover.REGION_DIR, '*.mca')))
    print('sweeping %d regions for the signature blocks' % len(files), flush=True)

    cores = []
    done = 0

    with Pool(workers) as pool:
        for found in pool.imap_unordered(cores_in_region, [(f,) for f in files], chunksize=4):
            done += 1
            cores.extend(found)

            if done % 200 == 0:
                print('  %d/%d regions, %d cores' % (done, len(files), len(cores)), flush=True)

    with open(CORES, 'wb') as fh:
        pickle.dump(cores, fh)

    print('%d cores found' % len(cores), flush=True)
    return cores


def main():
    workers = int(sys.argv[1]) if len(sys.argv) > 1 else 4
    reuse = len(sys.argv) > 2 and sys.argv[2] == 'reuse'

    if reuse and os.path.isfile(CORES):
        with open(CORES, 'rb') as fh:
            cores = pickle.load(fh)

        print('reusing %d cores from %s' % (len(cores), CORES), flush=True)
    else:
        cores = sweep(workers)

    by_region = defaultdict(list)

    for core in cores:
        by_region[core['region']].append(core)

    print('growing them into whole structures across %d regions' % len(by_region), flush=True)

    grown = []
    done = 0

    with Pool(workers) as pool:
        for found in pool.imap_unordered(grow_region, sorted(by_region.items()), chunksize=1):
            done += 1
            grown.extend(found)

            if done % 20 == 0:
                print('  %d/%d regions, %d structures' % (done, len(by_region), len(grown)), flush=True)

    with open(OUT, 'wb') as fh:
        pickle.dump(grown, fh)

    pulled_in = Counter('core only' if s['margin'] is None else s['margin']
                        for s in grown if s['margin'] != MARGINS[0])
    print('%d structures needed a tighter margin than %d: %s'
          % (sum(pulled_in.values()), MARGINS[0], dict(pulled_in)))
    sizes = sorted(s['size'] for s in grown)
    total = sum(sizes)
    print('\n%d structures, %d blocks in total' % (len(grown), total))

    if sizes:
        print('size: smallest %d, median %d, largest %d'
              % (sizes[0], sizes[len(sizes) // 2], sizes[-1]))

    materials = Counter()

    for structure in grown:
        for name, count in structure['counts'].items():
            materials[name] += count

    print('\nwhat they are made of, over all %d structures:' % len(grown))

    for name, count in materials.most_common(20):
        print('  %-45s %d' % (name.replace('minecraft:', ''), count))


if __name__ == '__main__':
    main()
