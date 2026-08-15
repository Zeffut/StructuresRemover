#!/usr/bin/env python3
"""Take the body of a creature that is built out of landscape blocks.

Everything else here refuses to touch stone, ice and snow, and that refusal is what has kept the
ground intact. The map's stone and ice creatures are built out of exactly those blocks, so the rule
that protects the map also protects them: deleting their visible parts leaves a headless lump of ice
sitting on the mountain.

The way out is not to relax the rule but to find something that tells a creature's body apart from
the hill it stands on. A body is a small pocket of ice or rock touching the creature's built parts;
the hill is a mass that runs for thousands of blocks. So the fill starts from the built parts, moves
only through landscape blocks that touch them, stays inside the creature's own bounding box grown by
a small margin, and gives up entirely if it grows past a cap. Reaching the cap means the fill has
walked out into the terrain, and the right answer there is to take nothing.

Nothing here is applied on its own. It writes a list, and reports what it refused.
"""
import os
import sys
from collections import Counter, defaultdict, deque
from multiprocessing import Pool

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import creature
import discover
import families

# How far past the built parts the body may reach, and how big it may be. The ice creatures carry
# about 190 blocks of ice; a cap of 600 leaves room for a bigger one and is nowhere near the size of
# a glacier.
MARGIN = 3
CAP = 600

# The creatures, named by the materials their built parts are summarised as, and by what their body
# is made of. Written down rather than guessed: these are the two the owner pointed at, and taking
# the body of anything else would be taking terrain for no reason.
CREATURES = (
    {
        'name': 'ice creature',
        'skeleton': frozenset(('minecraft:smooth_quartz', 'minecraft:smooth_quartz_stairs',
                               'minecraft:smooth_quartz_slab')),
        'body': {'minecraft:blue_ice', 'minecraft:packed_ice'},
    },
    {
        'name': 'stone creature',
        'skeleton': frozenset(('minecraft:cracked_stone_bricks', 'minecraft:stone_stairs',
                               'minecraft:stone_slab')),
        'body': {'minecraft:stone', 'minecraft:cobblestone', 'minecraft:andesite'},
    },
)


def body_of(blocks, skeleton, materials):
    """The landscape blocks that belong to a creature, or None if the fill escaped into terrain.

    ``blocks`` is the neighbourhood with terrain left in, ``skeleton`` the positions of its built
    parts, ``materials`` the block names its body may be made of.
    """
    low = tuple(min(p[i] for p in skeleton) - MARGIN for i in range(3))
    high = tuple(max(p[i] for p in skeleton) + MARGIN for i in range(3))

    seen = set()
    queue = deque(skeleton)

    while queue:
        x, y, z = queue.popleft()

        for dx, dy, dz in discover.NEIGHBOURS:
            cell = (x + dx, y + dy, z + dz)

            if cell in seen or cell in skeleton:
                continue

            if not all(low[i] <= cell[i] <= high[i] for i in range(3)):
                continue

            if blocks.get(cell) not in materials:
                continue

            seen.add(cell)
            queue.append(cell)

            if len(seen) > CAP:
                # Walked out of the creature and into the landscape. Take nothing.
                return None

    return seen


def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else 'bodies.txt'

    clumps, _scanned = discover.load()
    chosen = families.families(clumps)
    by_region = defaultdict(list)
    materials_of = {}
    total = 0

    for creature_kind in CREATURES:
        members = [e for f in chosen if f['materials'] == creature_kind['skeleton']
                   for e in f['members']]
        total += len(members)
        print('%s: %d of them' % (creature_kind['name'], len(members)))

        for entry in members:
            key = ((entry[1], entry[2], entry[3]), entry[0])
            materials_of[key] = creature_kind['body']

            for region in discover.regions_of(entry):
                by_region[region].append(key)

    print('%d creatures across %d regions' % (total, len(by_region)), flush=True)

    taken = 0
    refused = 0
    kinds = Counter()
    done = 0

    with open(out_path, 'w') as out:
        for (rx, rz), corners in sorted(by_region.items()):
            done += 1
            skeleton_blocks = discover.load_blocks(rx, rz)

            if skeleton_blocks is None:
                continue

            targets = set(corners)

            for blob in discover.owned_clumps(rx, rz, skeleton_blocks):
                corner = (min(c[0] for c in blob), min(c[1] for c in blob), min(c[2] for c in blob))

                if (corner, len(blob)) not in targets:
                    continue

                middle = (sum(c[0] for c in blob) // len(blob),
                          sum(c[1] for c in blob) // len(blob),
                          sum(c[2] for c in blob) // len(blob))
                span = max(max(c[i] for c in blob) - min(c[i] for c in blob) for i in range(3))
                near = creature.block_map(middle[0], middle[1], middle[2],
                                          span // 2 + MARGIN + 2, span // 2 + MARGIN + 2)

                materials = materials_of[(corner, len(blob))]

                if not any(near.get(c) in materials for c in _around(blob)):
                    continue

                body = body_of(near, set(blob), materials)

                if body is None:
                    refused += 1
                    continue

                for cell in body:
                    out.write('%d %d %d %s\n' % (cell + (near[cell],)))
                    kinds[near[cell]] += 1
                    taken += 1

            if done % 50 == 0:
                print('  %d/%d regions, %d blocks taken, %d refused'
                      % (done, len(by_region), taken, refused), flush=True)

    print('\n%d blocks written to %s' % (taken, out_path))
    print('%d bodies refused for growing past %d blocks' % (refused, CAP))

    for name, count in kinds.most_common(8):
        print('  %-40s %d' % (name.replace('minecraft:', ''), count))

    return 0


def _around(blob):
    """The positions touching a clump."""
    cells = set(blob)

    for x, y, z in blob:
        for dx, dy, dz in discover.NEIGHBOURS:
            cell = (x + dx, y + dy, z + dz)

            if cell not in cells:
                yield cell


if __name__ == '__main__':
    sys.exit(main())
