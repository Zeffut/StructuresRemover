#!/usr/bin/env python3
"""Find repeated structures on a map without knowing what they look like.

The idea: a block type used only by one kind of structure shows up as many small, well separated
clusters of chunks. Terrain blocks spread everywhere, one-off builds form a single cluster, and a
structure stamped 120 times across the map forms 120 clusters of a few chunks each. Ranking block
types by cluster count therefore surfaces the repeated builds on its own.
"""
import glob
import os
import pickle
import re
import sys
from collections import Counter, defaultdict, deque
from multiprocessing import Pool

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import survey

# Names that say nothing about builds: terrain, ores, biomes, and the structure/lighting keys that
# live in chunk NBT next to the block palettes.
BORING = {
    'air', 'cave_air', 'void_air', 'bedrock', 'stone', 'deepslate', 'dirt', 'coarse_dirt',
    'rooted_dirt', 'grass_block', 'podzol', 'mycelium', 'sand', 'red_sand', 'sandstone', 'gravel',
    'granite', 'diorite', 'andesite', 'tuff', 'calcite', 'water', 'lava', 'clay', 'mud',
    'coal_ore', 'iron_ore', 'gold_ore', 'redstone_ore', 'lapis_ore', 'emerald_ore', 'diamond_ore',
    'copper_ore', 'deepslate_coal_ore', 'deepslate_iron_ore', 'deepslate_gold_ore',
    'deepslate_redstone_ore', 'deepslate_lapis_ore', 'deepslate_emerald_ore',
    'deepslate_diamond_ore', 'deepslate_copper_ore', 'infested_stone',
    'short_grass', 'tall_grass', 'fern', 'large_fern', 'dead_bush', 'seagrass', 'tall_seagrass',
    'kelp', 'kelp_plant', 'snow', 'snow_block', 'ice', 'packed_ice', 'blue_ice', 'powder_snow',
    'full', 'structure_starts', 'structure_references', 'carvers', 'initialize_light', 'light',
    'biomes', 'heightmaps', 'block_ticks', 'fluid_ticks', 'post_features', 'spawn', 'surface',
    'noise', 'features', 'liquid_carvers', 'empty',
}

# Biome and structure identifiers also appear as "minecraft:xxx" strings inside chunk NBT.
BORING_SUFFIXES = ('_ocean', '_river', '_beach', '_hills', '_forest', '_taiga', '_savanna',
                   '_desert', '_jungle', '_swamp', '_plains', '_peaks', '_grove', '_slopes',
                   '_caves', '_cave', '_shore', '_end', '_nether', '_void')
BORING_EXACT_BIOMES = {'plains', 'forest', 'river', 'beach', 'desert', 'jungle', 'swamp', 'taiga',
                       'savanna', 'badlands', 'ocean', 'meadow', 'grove', 'mineshaft', 'village',
                       'stronghold', 'nether_wastes', 'the_end', 'the_void', 'sunflower_plains'}


def interesting(name):
    short = name.split(':', 1)[1]

    if short in BORING or short in BORING_EXACT_BIOMES:
        return False

    if short.startswith('village_') or short.startswith('ruined_') or short.endswith('_biome'):
        return False

    return not short.endswith(BORING_SUFFIXES)


def scan_region(path):
    """Returns {block name: [(chunk_x, chunk_z), ...]} for the interesting names in one region."""
    found = defaultdict(list)
    chunks = 0

    for cx, cz, offset, sectors in survey.chunk_entries(path):
        raw = survey.read_chunk(path, offset, sectors)

        if raw is None:
            continue

        chunks += 1

        for name in {n.decode() for n in survey.NAME_RE.findall(raw)}:
            if interesting(name):
                found[name].append((cx, cz))

    return chunks, dict(found)


def cluster(coords, gap=3):
    """Groups chunk coordinates into blobs, treating anything within `gap` chunks as connected."""
    remaining = set(coords)
    clusters = []

    while remaining:
        seed = remaining.pop()
        blob = [seed]
        queue = deque([seed])

        while queue:
            cx, cz = queue.popleft()

            for dx in range(-gap, gap + 1):
                for dz in range(-gap, gap + 1):
                    neighbour = (cx + dx, cz + dz)

                    if neighbour in remaining:
                        remaining.remove(neighbour)
                        blob.append(neighbour)
                        queue.append(neighbour)

        clusters.append(blob)

    return clusters


def main():
    files = sorted(glob.glob(os.path.join(survey.REGION_DIR, '*.mca')))
    print('scanning %d region files...' % len(files), flush=True)

    totals = defaultdict(list)
    chunk_total = 0

    with Pool(4) as pool:
        for done, (chunks, found) in enumerate(pool.imap_unordered(scan_region, files, chunksize=4), 1):
            chunk_total += chunks

            for name, coords in found.items():
                totals[name].extend(coords)

            if done % 200 == 0:
                print('  %d/%d regions, %d chunks, %d names' % (done, len(files), chunk_total, len(totals)), flush=True)

    print('\n%d chunks read, %d interesting names\n' % (chunk_total, len(totals)))

    with open('repeats.pickle', 'wb') as fh:
        pickle.dump({'chunks': chunk_total, 'names': dict(totals)}, fh)

    rows = []

    for name, coords in totals.items():
        if not 40 <= len(coords) <= 40000:
            continue

        blobs = cluster(coords)

        if len(blobs) < 15:
            continue

        sizes = sorted(len(b) for b in blobs)
        median = sizes[len(sizes) // 2]
        # A repeated build gives many clusters that are all about the same small size.
        uniformity = sizes[int(len(sizes) * 0.9)] / max(1, sizes[int(len(sizes) * 0.1)])
        rows.append((len(blobs), median, uniformity, name, blobs))

    rows.sort(key=lambda r: -r[0])

    print('%-34s %8s %8s %10s' % ('block', 'clusters', 'median', 'spread'))

    for count, median, uniformity, name, blobs in rows[:40]:
        print('%-34s %8d %8d %10.1f' % (name, count, median, uniformity))

    print('\n=== example positions of the top candidates ===')

    for count, median, uniformity, name, blobs in rows[:8]:
        print('\n%s (%d clusters, median %d chunks)' % (name, count, median))

        for blob in sorted(blobs, key=len, reverse=True)[:5]:
            cx = sum(c[0] for c in blob) // len(blob)
            cz = sum(c[1] for c in blob) // len(blob)
            print('   %d chunks around block x=%d z=%d' % (len(blob), cx * 16, cz * 16))


if __name__ == '__main__':
    main()
