#!/usr/bin/env python3
"""Drop from a deletion list anything standing near a place that must not be touched.

    python3 keepout.py in.txt out.txt

Most things kept off the list are kept off by what they are made of: `families.py` holds the honey
block trees and the copper machines as material signatures, and no list ever carries them. That only
works for things whose palette somebody has looked at.

The great fairy fountains are the case it does not cover. They came into use after the lists were
built, their palette has never been read, and there are four of them — below the eight a family
needs, so nothing has listed them yet. "Below a threshold" is not protection, it is a coincidence
that holds until the threshold moves, and the copper machines are already in the repository as an
instance of exactly that.

So these are protected by where they are instead of by what they are, which needs no knowledge of
the build. The radius is generous on purpose: it costs a few structures left standing near a
fountain, and it buys not having to be right about how big a fountain is.

Subtractive only. This can leave something standing that should have gone; it cannot delete anything
that would otherwise have survived. That asymmetry is why it is a separate pass at the end rather
than a condition threaded through the scan.
"""
import sys
from collections import Counter

# Named because a bare list of numbers invites somebody to prune it. Every one of these is a place a
# player goes to on purpose.
KEEP_OUT = [
    ('Tera, gerudo desert', 1780, 79, 6721, 40),
    ('Kaysa, Tabantha', 2539, 188, 4122, 40),
    ('Mija, volcano', 6891, 175, 3763, 40),
    ('Cotera, zora lands', 5669, 172, 5024, 40),
]


def main():
    source = sys.argv[1]
    out_path = sys.argv[2]

    kept = Counter()
    written = 0
    read = 0

    with open(source) as fh, open(out_path, 'w') as out:
        for line in fh:
            parts = line.split()

            if len(parts) < 4:
                continue

            read += 1
            x, y, z = int(parts[0]), int(parts[1]), int(parts[2])
            near = None

            for name, cx, cy, cz, radius in KEEP_OUT:
                # A box rather than a sphere: it is the shape of a build, and asking whether a block
                # is inside one is a question with no arithmetic to get wrong.
                if abs(x - cx) <= radius and abs(y - cy) <= radius and abs(z - cz) <= radius:
                    near = name
                    break

            if near is None:
                out.write(line)
                written += 1
            else:
                kept[near] += 1

    print('%d positions read, %d written to %s' % (read, written, out_path))

    if not kept:
        print('nothing was near a kept place — the list did not reach any of them')
        return 0

    print('%d positions dropped for standing near a kept place:' % sum(kept.values()))

    for name, count in kept.most_common():
        print('  %-28s %d' % (name, count))

    return 0


if __name__ == '__main__':
    sys.exit(main())
