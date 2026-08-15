#!/usr/bin/env python3
"""Look at a place on the map with the terrain left in.

Everything else here reads the map with landscape blocks removed, which is what keeps the ground
safe. That makes some things invisible: the map's stone and ice creatures are built out of the very
blocks the scan refuses to look at, so to a scan they are indistinguishable from a hillside.

This reads a box with nothing filtered out, and reports it two ways — what is there, and how it is
stacked — so a creature can be told apart from the hill it stands on.
"""
import collections
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import discover
import nbt
import survey


def block_map(cx, cy, cz, radius=16, height=16):
    """{(x, y, z): name} for a box, terrain included."""
    out = {}
    rx, rz = cx >> 9, cz >> 9
    path = os.path.join(discover.REGION_DIR, 'r.%d.%d.mca' % (rx, rz))

    if not os.path.isfile(path):
        return out

    for ccx, ccz, offset, sectors in survey.chunk_entries(path):
        if abs((ccx << 4) - cx) > radius + 16 or abs((ccz << 4) - cz) > radius + 16:
            continue

        raw = survey.read_chunk(path, offset, sectors)

        if raw is None:
            continue

        root = nbt.parse(raw)

        for section in root.get('sections', []):
            base = section.get('Y', 0) << 4

            if base > cy + height or base + 15 < cy - height:
                continue

            for x, y, z, full in discover.all_section_blocks(section):
                px, py, pz = (ccx << 4) + x, base + y, (ccz << 4) + z

                if (abs(px - cx) <= radius and abs(pz - cz) <= radius
                        and abs(py - cy) <= height):
                    name = full.split('[')[0]

                    if name != 'minecraft:air':
                        out[(px, py, pz)] = name

    return out


def main():
    cx, cy, cz = (int(v) for v in sys.argv[1:4])
    radius = int(sys.argv[4]) if len(sys.argv) > 4 else 16
    height = int(sys.argv[5]) if len(sys.argv) > 5 else 16

    blocks = block_map(cx, cy, cz, radius, height)
    print('%d blocks in the box' % len(blocks))

    counts = collections.Counter(blocks.values())
    print('\nwhat is there:')

    for name, count in counts.most_common(18):
        print('  %-42s %d' % (name.replace('minecraft:', ''), count))

    print('\nby height, what is on each level:')

    for y in range(cy - height, cy + height + 1):
        level = collections.Counter(n for (_x, py, _z), n in blocks.items() if py == y)

        if not level:
            continue

        top = ', '.join('%s x%d' % (n.replace('minecraft:', ''), c) for n, c in level.most_common(4))
        print('  y=%-4d %4d  %s' % (y, sum(level.values()), top[:88]))


if __name__ == '__main__':
    main()
