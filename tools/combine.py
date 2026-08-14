#!/usr/bin/env python3
"""Merge several deletion lists into one, keeping each position exactly once.

The lists overlap on purpose: the shrines are found twice, once by their palette and once as a
family of repeated structures. Feeding the same position to the purge twice is not harmful but it
muddles the count — the second pass finds air already there and reports it as a refusal — so the
lists are merged here instead, and the merged file is the one that gets applied and verified.
"""
import sys
from collections import Counter


def main():
    if len(sys.argv) < 3:
        print('usage: combine.py <out> <list> [<list> ...]')
        return 1

    out_path, sources = sys.argv[1], sys.argv[2:]
    seen = {}
    counts = Counter()

    for source in sources:
        added = 0

        with open(source) as fh:
            for line in fh:
                parts = line.split()

                if len(parts) < 4:
                    continue

                key = (int(parts[0]), int(parts[1]), int(parts[2]))

                if key not in seen:
                    seen[key] = parts[3]
                    added += 1

                counts[source] += 1

        print('%-28s %8d lines, %8d new' % (source, counts[source], added))

    with open(out_path, 'w') as out:
        for (x, y, z), name in sorted(seen.items()):
            out.write('%d %d %d %s\n' % (x, y, z, name))

    print('\n%d distinct positions written to %s' % (len(seen), out_path))
    return 0


if __name__ == '__main__':
    sys.exit(main())
