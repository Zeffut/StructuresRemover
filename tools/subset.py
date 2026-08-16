#!/usr/bin/env python3
"""Is the server's built content a subset of the archive's?

Two measurements taken from opposite directions disagree in a way that has only one shape:

    a list built on the archive, tried against the server -> 66,825 positions holding terrain
    a list built on the server, tried against the archive -> 0 disagreements in 20,000

That is what a subset looks like. Everything the server has, the archive has; the archive has more.
It is a hypothesis about the two maps, not about any list, so it is settled by reading both maps and
comparing them directly — region by region, position by position, built blocks only.

Sampled rather than exhaustive: 1,687 regions at about ten seconds each is hours, and the question
is which of three shapes the difference has, not its exact size.

    SR_ARCHIVE=/path/to/archive/region SR_SERVER=/path/to/server/region python3 subset.py 24

NOT YET RUN. This was written when the shell it was meant to run in stopped being available, so the
logic below has never executed. Treat a first run as a test of the tool as much as of the maps: if
it reports something surprising, suspect it before believing it.
"""
import os
import random
import sys
from collections import Counter
from multiprocessing import Pool

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import discover

ARCHIVE = os.environ.get('SR_ARCHIVE', 'extracted/botwproject/region')
SERVER = os.environ.get('SR_SERVER', 'server/botwproject/region')


def compare(key):
    """For one region, how the two copies differ — built blocks only, terrain excluded."""
    rx, rz = key

    discover.REGION_DIR = ARCHIVE
    archive = discover.load_blocks(rx, rz)

    discover.REGION_DIR = SERVER
    server = discover.load_blocks(rx, rz)

    if archive is None or server is None:
        return None

    archive_keys = set(archive)
    server_keys = set(server)
    both = archive_keys & server_keys

    return {
        'region': key,
        'archive': len(archive_keys),
        'server': len(server_keys),
        'server_only': len(server_keys - archive_keys),
        'archive_only': len(archive_keys - server_keys),
        'renamed': sum(1 for c in both if archive[c] != server[c]),
        'server_only_kinds': Counter(server[c] for c in list(server_keys - archive_keys)[:400]),
        'archive_only_kinds': Counter(archive[c] for c in list(archive_keys - server_keys)[:400]),
    }


def main():
    count = int(sys.argv[1]) if len(sys.argv) > 1 else 24

    shared = sorted({f[2:-4] for f in os.listdir(ARCHIVE) if f.endswith('.mca')}
                    & {f[2:-4] for f in os.listdir(SERVER) if f.endswith('.mca')})
    keys = [tuple(int(part) for part in name.split('.')) for name in shared]
    print('%d regions in both copies' % len(keys), flush=True)

    random.seed(11)
    sample = random.sample(keys, min(count, len(keys)))

    totals = Counter()
    server_only_kinds = Counter()
    archive_only_kinds = Counter()
    done = 0

    with Pool(4) as pool:
        for result in pool.imap_unordered(compare, sample, chunksize=1):
            done += 1

            if result is None:
                continue

            for field in ('archive', 'server', 'server_only', 'archive_only', 'renamed'):
                totals[field] += result[field]

            server_only_kinds.update(result['server_only_kinds'])
            archive_only_kinds.update(result['archive_only_kinds'])
            print('  r.%d.%d: archive %7d  server %7d  server-only %6d  archive-only %6d'
                  % (result['region'][0], result['region'][1], result['archive'],
                     result['server'], result['server_only'], result['archive_only']), flush=True)

    print('\nacross %d regions, built blocks only:' % done)
    print('  in the archive                 %9d' % totals['archive'])
    print('  in the server                  %9d' % totals['server'])
    print('  in the server, not the archive %9d' % totals['server_only'])
    print('  in the archive, not the server %9d' % totals['archive_only'])
    print('  in both under a different name %9d' % totals['renamed'])

    # The renamed count is expected to be large and means nothing on its own: the server upgraded
    # the world to 1.21.11 and the game renamed chain to iron_chain under it. It is printed so it
    # cannot be mistaken for a difference somebody made.
    if totals['server_only'] == 0:
        print('\n  the server is a subset: nothing built on it is absent from the archive')
    else:
        print('\n  not a subset. what the server has and the archive does not:')

        for name, n in server_only_kinds.most_common(10):
            print('    %-46s %d' % (name, n))

    print('\n  what the archive has and the server does not:')

    for name, n in archive_only_kinds.most_common(10):
        print('    %-46s %d' % (name, n))

    return 0


if __name__ == '__main__':
    sys.exit(main())
