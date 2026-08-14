#!/usr/bin/env python3
"""Find every structure that is repeated identically across a map.

The method never has to guess what is structure and what is ground. Blocks that make up terrain are
listed once, up front; everything else is grouped into connected clumps, and two clumps count as
copies of each other only when their blocks match exactly, position for position, under one of the
four rotations. What gets deleted later is precisely the blocks of those clumps, so the ground
cannot be touched: it was never part of a clump in the first place.

Regions are processed with a margin of neighbouring chunks so a building on a region border is not
cut in half; each clump is claimed by the region its centre falls in, so it is found exactly once.
"""
import glob
import hashlib
import os
import pickle
import re
import struct
import sys
from collections import defaultdict, deque
from multiprocessing import Pool

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import survey
import nbt

REGION_DIR = os.environ.get('SR_REGION_DIR', 'world/region')

# Anything that occurs as landscape. Deleting one of these is exactly what must never happen, so the
# list is deliberately generous: a structure block wrongly listed here merely survives, while a
# terrain block wrongly left out could be removed.
TERRAIN_PREFIXES = (
    'minecraft:stone', 'minecraft:cobblestone', 'minecraft:deepslate', 'minecraft:tuff',
    'minecraft:granite', 'minecraft:diorite', 'minecraft:andesite', 'minecraft:calcite',
    'minecraft:dirt', 'minecraft:coarse_dirt', 'minecraft:rooted_dirt', 'minecraft:mud',
    'minecraft:clay', 'minecraft:grass_block', 'minecraft:podzol', 'minecraft:mycelium',
    'minecraft:sand', 'minecraft:red_sand', 'minecraft:gravel', 'minecraft:sandstone',
    'minecraft:red_sandstone', 'minecraft:terracotta', 'minecraft:water', 'minecraft:lava',
    'minecraft:bedrock', 'minecraft:obsidian', 'minecraft:magma', 'minecraft:soul_sand',
    'minecraft:soul_soil', 'minecraft:netherrack', 'minecraft:basalt', 'minecraft:blackstone',
    'minecraft:end_stone', 'minecraft:snow', 'minecraft:ice', 'minecraft:packed_ice',
    'minecraft:blue_ice', 'minecraft:powder_snow', 'minecraft:moss', 'minecraft:packed_mud',
)

# Growing things, which repeat naturally and are not builds.
NATURAL_SUFFIXES = (
    '_log', '_wood', '_leaves', '_sapling', '_propagule', '_roots', '_fungus', '_vine',
    '_mushroom', '_mushroom_block', '_coral', '_coral_block', '_coral_fan', '_coral_wall_fan',
    '_flower', '_bush', '_grass', '_fern', '_seagrass', '_kelp', '_bamboo', '_sprouts',
    '_ore', '_amethyst_bud', '_amethyst_cluster',
)

NATURAL_EXACT = {
    'minecraft:air', 'minecraft:cave_air', 'minecraft:void_air', 'minecraft:vine',
    'minecraft:kelp', 'minecraft:kelp_plant', 'minecraft:seagrass', 'minecraft:tall_seagrass',
    'minecraft:short_grass', 'minecraft:tall_grass', 'minecraft:fern', 'minecraft:large_fern',
    'minecraft:dead_bush', 'minecraft:cactus', 'minecraft:sugar_cane', 'minecraft:lily_pad',
    'minecraft:snow', 'minecraft:cobweb', 'minecraft:glow_lichen', 'minecraft:sculk',
    'minecraft:sculk_vein', 'minecraft:pointed_dripstone', 'minecraft:dripstone_block',
    'minecraft:moss_block', 'minecraft:moss_carpet', 'minecraft:bamboo', 'minecraft:sea_pickle',
    'minecraft:brown_mushroom_block', 'minecraft:red_mushroom_block', 'minecraft:mushroom_stem',
    'minecraft:melon', 'minecraft:pumpkin', 'minecraft:amethyst_block', 'minecraft:budding_amethyst',
}


def is_terrain(name):
    """True for anything that is landscape rather than something someone built."""
    if name in NATURAL_EXACT:
        return True

    if name.startswith(TERRAIN_PREFIXES):
        return True

    return name.endswith(NATURAL_SUFFIXES)


def section_blocks(section):
    """Yields (x, y, z, name) for the non-terrain blocks of one chunk section."""
    states = section.get('block_states')

    if not states:
        return

    palette = states.get('palette') or []

    if not palette:
        return

    names = []
    interesting = False

    for entry in palette:
        name = entry.get('Name', 'minecraft:air')
        props = entry.get('Properties')

        if props:
            full = '%s[%s]' % (name, ','.join('%s=%s' % (k, props[k]) for k in sorted(props)))
        else:
            full = name

        names.append((name, full))

        if not is_terrain(name):
            interesting = True

    # The cheap win: a section made only of stone, dirt and air is skipped without unpacking it.
    if not interesting:
        return

    base_y = section.get('Y', 0) << 4
    data = states.get('data')

    if len(palette) == 1 or not data:
        short, full = names[0]

        if is_terrain(short):
            return

        for i in range(4096):
            yield i & 15, base_y + (i >> 8), (i >> 4) & 15, full

        return

    bits = max(4, (len(palette) - 1).bit_length())
    per_long = 64 // bits
    mask = (1 << bits) - 1

    for i in range(4096):
        value = data[i // per_long]
        entry = (value >> ((i % per_long) * bits)) & mask

        if entry >= len(names):
            continue

        short, full = names[entry]

        if is_terrain(short):
            continue

        yield i & 15, base_y + (i >> 8), (i >> 4) & 15, full


def collect(path, chunk_filter=None):
    """Returns {(x, y, z): block} for every non-terrain block in a region file."""
    blocks = {}

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

        ox = cx << 4
        oz = cz << 4

        for section in root.get('sections', []):
            for x, y, z, name in section_blocks(section):
                blocks[(ox + x, y, oz + z)] = name

    return blocks


NEIGHBOURS = [(dx, dy, dz)
              for dx in (-1, 0, 1) for dy in (-1, 0, 1) for dz in (-1, 0, 1)
              if (dx, dy, dz) != (0, 0, 0)]


def clumps(blocks, min_size, max_size):
    """Splits the block map into connected clumps, ignoring ones that are too small or too large."""
    seen = set()
    out = []

    for start in blocks:
        if start in seen:
            continue

        seen.add(start)
        blob = [start]
        queue = deque([start])

        while queue:
            x, y, z = queue.popleft()

            for dx, dy, dz in NEIGHBOURS:
                neighbour = (x + dx, y + dy, z + dz)

                if neighbour in blocks and neighbour not in seen:
                    seen.add(neighbour)
                    blob.append(neighbour)
                    queue.append(neighbour)

            if len(blob) > max_size:
                break

        if min_size <= len(blob) <= max_size:
            out.append(blob)

    return out


def canonical(blob, blocks):
    """A hash that is the same for two clumps that are copies, in any of the four rotations."""
    best = None

    for rotation in range(4):
        cells = []

        for x, y, z in blob:
            if rotation == 0:
                rx, rz = x, z
            elif rotation == 1:
                rx, rz = -z, x
            elif rotation == 2:
                rx, rz = -x, -z
            else:
                rx, rz = z, -x

            cells.append((rx, y, rz, blocks[(x, y, z)]))

        minx = min(c[0] for c in cells)
        miny = min(c[1] for c in cells)
        minz = min(c[2] for c in cells)
        normalised = sorted((c[0] - minx, c[1] - miny, c[2] - minz, c[3]) for c in cells)
        text = ';'.join('%d,%d,%d,%s' % cell for cell in normalised)

        if best is None or text < best:
            best = text

    return hashlib.sha1(best.encode()).hexdigest()[:16], len(blob)


def scan_region(args):
    path, min_size, max_size = args
    match = re.match(r'r\.(-?\d+)\.(-?\d+)\.mca', os.path.basename(path))
    rx, rz = int(match.group(1)), int(match.group(2))

    # Pull in two chunks of the neighbours so a build on the seam stays whole.
    blocks = collect(path)

    for nx, nz in ((rx - 1, rz), (rx + 1, rz), (rx, rz - 1), (rx, rz + 1),
                   (rx - 1, rz - 1), (rx + 1, rz - 1), (rx - 1, rz + 1), (rx + 1, rz + 1)):
        neighbour = os.path.join(os.path.dirname(path), 'r.%d.%d.mca' % (nx, nz))

        if not os.path.isfile(neighbour):
            continue

        def near(cx, cz, rx=rx, rz=rz):
            return (min(abs(cx - (rx << 5)), abs(cx - ((rx << 5) + 31))) <= 2
                    and min(abs(cz - (rz << 5)), abs(cz - ((rz << 5) + 31))) <= 2)

        blocks.update(collect(neighbour, near))

    found = []
    x0, x1 = rx << 9, (rx << 9) + 511
    z0, z1 = rz << 9, (rz << 9) + 511

    for blob in clumps(blocks, min_size, max_size):
        cx = sum(c[0] for c in blob) // len(blob)
        cz = sum(c[2] for c in blob) // len(blob)

        # Claimed by the region holding its centre, so a seam build is reported once.
        if not (x0 <= cx <= x1 and z0 <= cz <= z1):
            continue

        digest, size = canonical(blob, blocks)
        # Only the fingerprint and where to find it: keeping every block of every clump for a whole
        # map would not fit in memory, and they are cheap to read back for the few groups that matter.
        found.append((digest, size, min(c[0] for c in blob), min(c[1] for c in blob),
                      min(c[2] for c in blob)))

    return found


def main():
    min_size = int(sys.argv[1]) if len(sys.argv) > 1 else 25
    max_size = int(sys.argv[2]) if len(sys.argv) > 2 else 20000
    limit = int(sys.argv[3]) if len(sys.argv) > 3 else 0

    files = sorted(glob.glob(os.path.join(REGION_DIR, '*.mca')))

    if limit:
        files = files[:limit]

    print('scanning %d region files (clump size %d..%d)' % (len(files), min_size, max_size), flush=True)

    groups = defaultdict(list)
    done = 0

    with Pool(4) as pool:
        for found in pool.imap_unordered(scan_region, [(f, min_size, max_size) for f in files], chunksize=1):
            done += 1

            for digest, size, x, y, z in found:
                groups[digest].append((size, x, y, z))

            if done % 25 == 0:
                repeated = sum(1 for g in groups.values() if len(g) > 1)
                print('  %d/%d regions, %d distinct shapes, %d repeated'
                      % (done, len(files), len(groups), repeated), flush=True)

    with open('structures.pickle', 'wb') as fh:
        pickle.dump(dict(groups), fh)

    repeated = sorted(((len(v), v[0][0], k) for k, v in groups.items() if len(v) > 1), reverse=True)
    print('\n%d distinct shapes, %d of them repeated' % (len(groups), len(repeated)))
    print('\n%8s %8s  %s' % ('copies', 'blocks', 'example position'))

    for count, size, digest in repeated[:40]:
        sample = groups[digest][0]
        print('%8d %8d  %d %d %d' % (count, size, sample[1], sample[2], sample[3]))


if __name__ == '__main__':
    main()
