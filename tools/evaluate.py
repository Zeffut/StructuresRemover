#!/usr/bin/env python3
"""Score a detection against the map's answer key.

Two numbers decide whether a method is usable. How many of the 137 shrines it finds, because
missing one means the job is not done. And what it finds that is not a shrine, because every one of
those is a build somewhere on the map that would be deleted by mistake.
"""
import pickle
import os
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shrines

REACH = 24


def load(path='family.pickle'):
    with open(path, 'rb') as fh:
        return pickle.load(fh)


def near(structure, point):
    """True when a marker falls inside a structure's box, allowing for the marker being offset."""
    low, high = structure['low'], structure['high']
    return all(low[i] - REACH <= point[i] <= high[i] + REACH for i in range(3))


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else 'family.pickle'
    found = load(path)
    truth = shrines.markers()

    matched = {}
    extra = []

    for structure in found:
        hits = [name for name, point in truth if near(structure, point)]

        if hits:
            for name in hits:
                matched.setdefault(name, []).append(structure)
        else:
            extra.append(structure)

    missing = [(name, point) for name, point in truth if name not in matched]

    print('%s' % path)
    print('  shrines found      %d / %d' % (len(matched), len(truth)))
    print('  structures matched %d' % (len(found) - len(extra)))
    print('  structures that are not a shrine: %d' % len(extra))

    if missing:
        print('\n  not found:')

        for name, point in missing[:15]:
            print('    %-20s %s' % (name, point))

    if extra:
        sizes = sorted(s['size'] for s in extra)
        print('\n  the %d non-shrines total %d blocks (smallest %d, median %d, largest %d)'
              % (len(extra), sum(sizes), sizes[0], sizes[len(sizes) // 2], sizes[-1]))
        materials = Counter()

        for structure in extra:
            for name, count in structure['counts'].items():
                materials[name] += count

        print('  made mostly of:')

        for name, count in materials.most_common(8):
            print('    %-42s %d' % (name.replace('minecraft:', ''), count))

        print('  a few of them:')

        for structure in sorted(extra, key=lambda s: -s['size'])[:8]:
            print('    %7d blocks at %s' % (structure['size'], structure['low']))


if __name__ == '__main__':
    main()
