#!/usr/bin/env python3
"""Turn a deletion list into Minecraft commands, so it can be applied without touching the files.

The mod needs a Fabric server and a jar on disk. A server that is neither — Paper, and reachable
only through a panel that writes text — can still be given a datapack, because a datapack is text.

A million and a half `setblock` lines would be fifty megabytes and is not worth trying. What makes
it tractable is that these blocks come in structures: for one structure and one of its materials,
a single `fill ... replace` over its bounding box removes every block of that material inside it.
That is only correct if the box holds no other block of that material — a neighbour's floor, the
ground beneath — so purity is checked against the map, per structure and per material, and anything
that fails falls back to `setblock` for that material alone.

Nothing here decides what to delete. It reads a list that has already been checked and turns it into
another form of the same thing.
"""
import os
import sys
from collections import Counter, defaultdict
from multiprocessing import Pool

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import creature
import discover

# Commands per function file. A function runs in one tick, and Minecraft stops a chain at 65,536
# commands; well under that keeps a tick from lasting seconds.
PER_FILE = 8000


def structures_of(listing):
    """Groups a deletion list back into the structures it came from, by connectivity."""
    blocks = {}

    with open(listing) as fh:
        for line in fh:
            parts = line.split()

            if len(parts) >= 4:
                blocks[(int(parts[0]), int(parts[1]), int(parts[2]))] = parts[3]

    print('%d positions to express as commands' % len(blocks), flush=True)
    found = discover._clumps_walked(blocks, 1, 10_000_000)
    print('%d separate structures' % len(found), flush=True)
    return blocks, found


def plan_one(args):
    """For one structure, the commands that clear it, and whether each material was safe to fill."""
    cells, names = args
    low = tuple(min(c[i] for c in cells) for i in range(3))
    high = tuple(max(c[i] for c in cells) for i in range(3))
    by_material = defaultdict(list)

    for cell in cells:
        by_material[names[cell]].append(cell)

    # What is actually in that box on the map, so a fill cannot take a neighbour's blocks with it.
    span = max(high[i] - low[i] for i in range(3))
    middle = tuple((low[i] + high[i]) // 2 for i in range(3))
    near = creature.block_map(middle[0], middle[1], middle[2], span // 2 + 2, span // 2 + 2)

    fills = []
    setblocks = []

    for material, positions in by_material.items():
        here = [c for c, name in near.items() if name == material]

        for command in _clear(positions, here, material):
            (fills if command.startswith('fill') else setblocks).append(command)

    return fills, setblocks


def _clear(positions, here, material, depth=0):
    """The commands that clear these positions and nothing else.

    A fill takes everything of one material inside a box, so it is only usable where the box holds
    nothing of that material but these. A shrine standing against a building rarely offers such a
    box at first — but the trespassers are usually a handful, and cutting the box in two until they
    are on their own side turns most of it back into fills. One fill replaces as many setblocks as
    the box holds blocks, so it is worth going a few levels down for.
    """
    if not positions:
        return []

    if len(positions) <= 2 or depth > 12:
        return ['setblock %d %d %d air' % c for c in positions]

    low = tuple(min(c[i] for c in positions) for i in range(3))
    high = tuple(max(c[i] for c in positions) for i in range(3))
    inside = [c for c in here if all(low[i] <= c[i] <= high[i] for i in range(3))]

    if len(inside) == len(positions):
        return ['fill %d %d %d %d %d %d air replace %s'
                % (low[0], low[1], low[2], high[0], high[1], high[2], material)]

    axis = max(range(3), key=lambda i: high[i] - low[i])

    if high[axis] == low[axis]:
        return ['setblock %d %d %d air' % c for c in positions]

    middle = (low[axis] + high[axis]) // 2
    left = [c for c in positions if c[axis] <= middle]
    right = [c for c in positions if c[axis] > middle]

    return (_clear(left, inside, material, depth + 1)
            + _clear(right, inside, material, depth + 1))


def main():
    listing = sys.argv[1]
    out_dir = sys.argv[2] if len(sys.argv) > 2 else 'commands'
    workers = int(sys.argv[3]) if len(sys.argv) > 3 else 4

    names, found = structures_of(listing)
    os.makedirs(out_dir, exist_ok=True)

    lines = []
    fill_count = 0
    setblock_count = 0
    done = 0

    with Pool(workers) as pool:
        for fills, setblocks in pool.imap_unordered(
                plan_one, ((cells, names) for cells in found), chunksize=8):
            done += 1
            lines.extend(fills)
            lines.extend(setblocks)
            fill_count += len(fills)
            setblock_count += len(setblocks)

            if done % 1000 == 0:
                print('  %d/%d structures, %d fills, %d setblocks'
                      % (done, len(found), fill_count, setblock_count), flush=True)

    print('\n%d fills and %d setblocks' % (fill_count, setblock_count))

    files = 0
    written = 0

    for start in range(0, len(lines), PER_FILE):
        chunk = lines[start:start + PER_FILE]
        files += 1
        path = os.path.join(out_dir, 'part%03d.mcfunction' % files)

        with open(path, 'w') as out:
            out.write('\n'.join(chunk))
            out.write('\n')

        written += sum(len(line) + 1 for line in chunk)

    print('%d command files, %.1f MB in total' % (files, written / 1e6))
    return 0


if __name__ == '__main__':
    sys.exit(main())
