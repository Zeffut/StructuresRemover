#!/usr/bin/env python3
"""Write the blocks of every detected structure as an explicit deletion list.

One ``x y z block`` line per block, which is the only thing the purge will act on. Nothing is
described by a box or a radius, so there is no way for the deletion to reach a block that was not
individually identified as part of a structure.
"""
import pickle
import sys
from collections import Counter


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else 'family.pickle'
    out_path = sys.argv[2] if len(sys.argv) > 2 else 'shrines_purge.txt'

    with open(src, 'rb') as fh:
        found = pickle.load(fh)

    seen = set()
    materials = Counter()
    written = 0

    with open(out_path, 'w') as out:
        for structure in found:
            for x, y, z, name in structure['cells']:
                if (x, y, z) in seen:
                    continue

                seen.add((x, y, z))
                materials[name] += 1
                out.write('%d %d %d %s\n' % (x, y, z, name))
                written += 1

    print('%d structures, %d blocks written to %s' % (len(found), written, out_path))
    print('most common:')

    for name, count in materials.most_common(10):
        print('  %-42s %d' % (name.replace('minecraft:', ''), count))


if __name__ == '__main__':
    main()
