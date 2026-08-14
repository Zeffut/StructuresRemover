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
import itertools
import os
import pickle
import re
import struct
import sys
from collections import Counter, defaultdict, deque
from multiprocessing import Pool

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import survey
import nbt

try:
    import numpy as _np
    from scipy.ndimage import label as _label
except ImportError:  # The scan still works without them, just far slower.
    _np = None
    _label = None

REGION_DIR = 'extracted/botwproject/region'

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
    # Named one by one because they read as worked stone but generate on their own.
    'minecraft:smooth_basalt', 'minecraft:gilded_blackstone', 'minecraft:cobblestone',
    'minecraft:infested_stone', 'minecraft:infested_cobblestone', 'minecraft:infested_deepslate',
    'minecraft:mossy_cobblestone', 'minecraft:muddy_mangrove_roots', 'minecraft:cobbled_deepslate',
}


# Marks of a block that went through a crafting table or a stonecutter. Stone is landscape; stone
# bricks, stone stairs and deepslate tiles are walls and floors somebody laid. Without this the
# prefixes above swallow them — a shrine built of stone brick would be read as scenery, come out of
# the scan full of holes, and be refused at deletion time.
WORKED_MARKERS = (
    'brick', 'polished', 'chiseled', 'cut_', 'smooth', 'stairs', 'slab', 'wall', 'button',
    'pressure_plate', 'pillar', 'tile', 'cutter', 'cracked', 'mossy', 'carved', 'glazed',
)


def is_terrain(name):
    """True for anything that is landscape rather than something someone built."""
    if name in NATURAL_EXACT:
        return True

    # Checked before the worked marks, because a few natural blocks are named like worked ones:
    # coral wall fans grow, and smooth basalt is what a geode is lined with.
    if name.endswith(NATURAL_SUFFIXES):
        return True

    short = name.partition(':')[2] or name

    for marker in WORKED_MARKERS:
        if marker in short:
            return False

    return name.startswith(TERRAIN_PREFIXES)


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

    if _np is not None:
        yield from _unpack(data, names, bits, per_long, mask, base_y)
        return

    for i in range(4096):
        value = data[i // per_long]
        entry = (value >> ((i % per_long) * bits)) & mask

        if entry >= len(names):
            continue

        short, full = names[entry]

        if is_terrain(short):
            continue

        yield i & 15, base_y + (i >> 8), (i >> 4) & 15, full


def _unpack(data, names, bits, per_long, mask, base_y):
    """Reads a section's packed indices all at once, and skips the terrain without touching it.

    A section holds 4096 blocks; a map holds millions of sections. Pulling the indices apart one at
    a time in Python is what made a whole-map scan take a day.
    """
    packed = _np.asarray(data, dtype=_np.int64).view(_np.uint64)
    slots = _np.arange(4096, dtype=_np.int64)
    which = slots // per_long

    if which[-1] >= packed.size:
        return

    shifts = ((slots % per_long) * bits).astype(_np.uint64)
    entries = _np.right_shift(packed[which], shifts) & _np.uint64(mask)
    entries = entries.astype(_np.int64)

    # A palette index that is out of range means a corrupt section; treat it as terrain, which is
    # the reading that leaves the world alone.
    wanted = _np.array([not is_terrain(short) for short, _ in names] + [False], dtype=bool)
    entries[entries >= len(names)] = len(names)
    keep = _np.flatnonzero(wanted[entries])

    if keep.size == 0:
        return

    chosen = entries[keep].tolist()

    for i, entry in zip(keep.tolist(), chosen):
        yield i & 15, base_y + (i >> 8), (i >> 4) & 15, names[entry][1]


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


def load_blocks(rx, rz):
    """Every non-terrain block of region (rx, rz), plus a two-chunk margin of its neighbours.

    The margin is what keeps a building that straddles a region border in one piece. Anything that
    reads a clump back has to load exactly the same margin, or the clump it rebuilds is a different
    shape from the one the scan fingerprinted.
    """
    path = os.path.join(REGION_DIR, 'r.%d.%d.mca' % (rx, rz))

    if not os.path.isfile(path):
        return None

    blocks = collect(path)

    for nx, nz in ((rx - 1, rz), (rx + 1, rz), (rx, rz - 1), (rx, rz + 1),
                   (rx - 1, rz - 1), (rx + 1, rz - 1), (rx - 1, rz + 1), (rx + 1, rz + 1)):
        neighbour = os.path.join(REGION_DIR, 'r.%d.%d.mca' % (nx, nz))

        if not os.path.isfile(neighbour):
            continue

        def near(cx, cz, rx=rx, rz=rz):
            return (min(abs(cx - (rx << 5)), abs(cx - ((rx << 5) + 31))) <= 2
                    and min(abs(cz - (rz << 5)), abs(cz - ((rz << 5) + 31))) <= 2)

        blocks.update(collect(neighbour, near))

    return blocks


def owned_clumps(rx, rz, blocks, min_size=None, max_size=None):
    """The clumps of a region, restricted to the ones this region owns.

    A clump belongs to whichever region its centre falls in, so a build sitting on a seam is
    reported once rather than by both neighbours.
    """
    min_size = MIN_SIZE if min_size is None else min_size
    max_size = MAX_SIZE if max_size is None else max_size
    x0, x1 = rx << 9, (rx << 9) + 511
    z0, z1 = rz << 9, (rz << 9) + 511

    for blob in clumps(blocks, min_size, max_size):
        cx = sum(c[0] for c in blob) // len(blob)
        cz = sum(c[2] for c in blob) // len(blob)

        if x0 <= cx <= x1 and z0 <= cz <= z1:
            yield blob


NEIGHBOURS = [(dx, dy, dz)
              for dx in (-1, 0, 1) for dy in (-1, 0, 1) for dz in (-1, 0, 1)
              if (dx, dy, dz) != (0, 0, 0)]


# The bounds the scan runs with. Anything that reads a clump back later — to describe it, or to turn
# it into a list of blocks to delete — must use these same numbers. A clump that grows past the
# ceiling is abandoned part-grown, and where that happens depends on the ceiling, so a different
# ceiling yields different clumps and the fingerprints stop lining up.
MIN_SIZE = 25
MAX_SIZE = 200_000


def clumps(blocks, min_size, max_size):
    """Splits the block map into connected clumps, ignoring ones that are too small or too large."""
    if _label is not None:
        return _clumps_labelled(blocks, min_size, max_size)

    return _clumps_walked(blocks, min_size, max_size)


def _clumps_labelled(blocks, min_size, max_size):
    """Same result as the walk below, done by scipy on a grid instead of block by block.

    A region of this map holds a few million non-terrain blocks, and walking each one's twenty-six
    neighbours in Python costs about forty seconds per region — most of the scan. Labelling the same
    grid is the identical operation written in C, and it is what makes a whole-map pass practical.
    """
    count = len(blocks)

    if count == 0:
        return []

    coords = _np.fromiter(itertools.chain.from_iterable(blocks), dtype=_np.int32, count=3 * count)
    coords = coords.reshape(count, 3)
    low = coords.min(axis=0)
    local = coords - low

    grid = _np.zeros(local.max(axis=0) + 1, dtype=_np.uint8)
    index = (local[:, 0], local[:, 1], local[:, 2])
    grid[index] = 1

    # A 3x3x3 block of ones is the twenty-six-way connectivity used by the walk: blocks that meet
    # at an edge or a corner are one structure, not two.
    labels, _count = _label(grid, structure=_np.ones((3, 3, 3), dtype=_np.uint8))
    del grid

    mine = labels[index]
    del labels
    sizes = _np.bincount(mine)
    keep = _np.flatnonzero((sizes >= min_size) & (sizes <= max_size))

    if keep.size == 0:
        return []

    order = _np.argsort(mine, kind='stable')
    starts = _np.searchsorted(mine[order], keep, side='left')
    ends = _np.searchsorted(mine[order], keep, side='right')

    return [[tuple(row) for row in coords[order[start:end]].tolist()]
            for start, end in zip(starts.tolist(), ends.tolist())]


def _clumps_walked(blocks, min_size, max_size):
    """The plain version, kept so the scan still runs where scipy is not installed."""
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

        # A component is always walked to the end, even when it is far past the ceiling. Stopping
        # early would leave the rest of it unvisited, and those leftovers would be picked up as
        # clumps of their own — arbitrary slices of one cave system, which is not a structure.
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


def descriptor(blob, blocks):
    """A small summary of a clump: how big it is, what shape, and what it is made of.

    Exact fingerprints only bring together copies that match block for block, and hand-placed builds
    rarely do — of the map's 137 shrines, one is an exact copy of another. This descriptor is what
    lets near-identical families be found afterwards, from the checkpoint alone, without paying for
    another full pass over the map.
    """
    xs = [c[0] for c in blob]
    ys = [c[1] for c in blob]
    zs = [c[2] for c in blob]
    counts = Counter(blocks[c].split('[')[0] for c in blob)
    # Sorted horizontally so the same build seen under two rotations gives the same shape.
    dx, dz = sorted((max(xs) - min(xs) + 1, max(zs) - min(zs) + 1))
    top = tuple(name for name, _ in counts.most_common(6))
    return dx, max(ys) - min(ys) + 1, dz, top


def scan_region(args):
    path, min_size, max_size = args
    match = re.match(r'r\.(-?\d+)\.(-?\d+)\.mca', os.path.basename(path))
    rx, rz = int(match.group(1)), int(match.group(2))

    blocks = load_blocks(rx, rz)
    found = []

    for blob in owned_clumps(rx, rz, blocks, min_size, max_size):
        # Only the summary and where to find it. Keeping every block of every clump for a whole map
        # would not fit in memory, and the few that turn out to matter are cheap to read back.
        #
        # The exact fingerprint is deliberately not computed here. Sorting a clump's blocks four
        # times over is the most expensive thing in the scan, and it is pointless for a clump that
        # has no twin: two clumps cannot be copies unless their summaries already agree, so the
        # fingerprint is left to a second pass that only looks at summaries seen more than once.
        found.append((len(blob), min(c[0] for c in blob), min(c[1] for c in blob),
                      min(c[2] for c in blob)) + descriptor(blob, blocks))

    return path, found


STATE = 'structures.pickle'


def regions_of(entry):
    """Every region a clump could be claimed by, given its corner and its extent.

    A clump belongs to whichever region its centre falls in, but all that is recorded is its lowest
    corner — and for a clump lying across a region border those are different regions. Looking only
    where the corner is silently loses it, so every region the clump reaches into is searched; its
    centre is inside its own bounding box, so the right one is always among them.
    """
    size, x, y, z, dx, dy, dz, mats = entry
    # The two horizontal extents are recorded sorted, so which one belongs to x and which to z is no
    # longer known; the wider is the safe assumption for both.
    span = max(dx, dz) - 1
    return {(rx, rz)
            for rx in range(x >> 9, ((x + span) >> 9) + 1)
            for rz in range(z >> 9, ((z + span) >> 9) + 1)}


def key_of(entry):
    """The summary two clumps must share before they can possibly be copies of each other."""
    size, _, _, _, dx, dy, dz, mats = entry
    return size, dx, dy, dz, mats


def save(found, scanned):
    """Checkpoint, so a scan that is interrupted resumes instead of starting over."""
    tmp = STATE + '.tmp'

    with open(tmp, 'wb') as fh:
        pickle.dump({'clumps': found, 'scanned': sorted(scanned)}, fh)

    os.replace(tmp, STATE)


def load():
    if not os.path.isfile(STATE):
        return [], set()

    with open(STATE, 'rb') as fh:
        data = pickle.load(fh)

    if not isinstance(data, dict) or 'clumps' not in data:
        # An older checkpoint, from before clump summaries were recorded. Its contents cannot be
        # mixed with new ones, so it is ignored rather than half-trusted.
        return [], set()

    return list(data['clumps']), set(data.get('scanned', ()))


def main():
    min_size = int(sys.argv[1]) if len(sys.argv) > 1 else MIN_SIZE
    max_size = int(sys.argv[2]) if len(sys.argv) > 2 else MAX_SIZE
    limit = int(sys.argv[3]) if len(sys.argv) > 3 else 0

    files = sorted(glob.glob(os.path.join(REGION_DIR, '*.mca')))

    if limit:
        files = files[:limit]

    everything, scanned = load()
    todo = [f for f in files if os.path.basename(f) not in scanned]

    print('scanning %d region files (%d already done, clump size %d..%d)'
          % (len(todo), len(scanned), min_size, max_size), flush=True)

    done = 0

    with Pool(4) as pool:
        for path, found in pool.imap_unordered(scan_region, [(f, min_size, max_size) for f in todo], chunksize=1):
            done += 1
            scanned.add(os.path.basename(path))
            everything.extend(found)

            if done % 25 == 0:
                seen = defaultdict(int)

                for entry in everything:
                    seen[key_of(entry)] += 1

                repeated = sum(n for n in seen.values() if n > 1)
                print('  %d/%d regions, %d clumps, %d shapes, %d clumps share a shape'
                      % (done, len(todo), len(everything), len(seen), repeated), flush=True)
                save(everything, scanned)

    save(everything, scanned)

    by_key = defaultdict(list)

    for entry in everything:
        by_key[key_of(entry)].append(entry)

    repeated = sorted(((len(v), k[0], k) for k, v in by_key.items() if len(v) > 1), reverse=True)
    print('\n%d distinct shapes, %d of them appear more than once' % (len(by_key), len(repeated)))
    print('\n%8s %8s  %s' % ('copies', 'blocks', 'example position'))

    for count, size, key in repeated[:40]:
        sample = by_key[key][0]
        print('%8d %8d  %d %d %d' % (count, size, sample[1], sample[2], sample[3]))


if __name__ == '__main__':
    main()
