#!/usr/bin/env python3
"""Find the families of structure a map repeats, without being told what to look for.

The shrines were found by handing the tool their palette. That only works because someone had
already looked at a shrine. This does the finding instead: it reads the clump summaries the scan
recorded for the whole map and asks which of them are members of the same family.

The signal is size agreement. Group the clumps by what they are mostly made of and two very
different things turn up. A family of repeated structures has members that are all about the same
size — 113 clumps of acacia fence and honey block, every one of them 215 or 216 blocks, is a prop
the builder placed 113 times. A material that is simply popular has members of every size, from a
doorstep to a whole castle, and is not a family at all. So a group is a family only when its members
agree on size, and the biggest member is not far off the typical one.

What comes out is a list of candidates with evidence attached — how many, how big, what of, where —
because deciding that a family should be deleted is not a decision this should be making on its own.
"""
import os
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import discover

MIN_MEMBERS = 8
MIN_BLOCKS = 60

# Families the map's owner looked at and asked to keep.
#
# The point of the family search is to find what repeats; it cannot tell repeated filler from
# repeated scenery someone placed on purpose. That call is the owner's, and once made it belongs
# here rather than in a list that gets regenerated — a decision that has to be remembered by hand is
# a decision that gets forgotten.
#
# The honey block trees are the case that proves it: 559 of them, 147,247 blocks, a fifth of
# everything the list touched on the playable map, and they are deliberate decor.
KEEP = (
    {'minecraft:honey_block', 'minecraft:honeycomb_block'},
)


def is_kept(materials):
    """True when a family matches something the owner asked to keep."""
    return any(rule <= set(materials) for rule in KEEP)

# How much the members of a family are allowed to differ in size. A repeated structure is not
# identical here — the shrines vary by a few blocks each — but it does not vary by a factor of ten.
SPREAD = 2.5


def profile(entry):
    """What a clump is mostly made of, which is what family members share."""
    return frozenset(entry[7][:3])


def families(clumps, min_members=MIN_MEMBERS, min_blocks=MIN_BLOCKS, spread=SPREAD):
    """The material groups whose members agree on size closely enough to be one repeated thing."""
    groups = defaultdict(list)

    for entry in clumps:
        if entry[0] >= min_blocks:
            groups[profile(entry)].append(entry)

    out = []

    for key, members in groups.items():
        if len(members) < min_members:
            continue

        sizes = sorted(e[0] for e in members)
        low = sizes[len(sizes) // 10]
        high = sizes[9 * len(sizes) // 10]

        if low <= 0 or high / low > spread:
            continue

        # One outlier the size of a castle means the group is a material, not a family.
        if sizes[-1] > sizes[len(sizes) // 2] * spread * 2:
            continue

        if is_kept(key):
            continue

        out.append({'materials': key, 'members': members, 'count': len(members),
                    'median': sizes[len(sizes) // 2], 'smallest': sizes[0], 'largest': sizes[-1],
                    'blocks': sum(sizes)})

    return sorted(out, key=lambda f: -f['blocks'])


def describe(found, limit=30):
    print('%d families\n' % len(found))
    print('%6s %8s %8s %9s  %-46s %s'
          % ('count', 'median', 'largest', 'blocks', 'made mostly of', 'example'))

    for family in found[:limit]:
        sample = max(family['members'], key=lambda e: e[0])
        materials = ', '.join(sorted(m.replace('minecraft:', '') for m in family['materials']))
        print('%6d %8d %8d %9d  %-46s %d %d %d'
              % (family['count'], family['median'], family['largest'], family['blocks'],
                 materials[:46], sample[1], sample[2], sample[3]))


def main():
    min_members = int(sys.argv[1]) if len(sys.argv) > 1 else MIN_MEMBERS
    min_blocks = int(sys.argv[2]) if len(sys.argv) > 2 else MIN_BLOCKS

    clumps, scanned = discover.load()
    print('%d clumps from %d regions\n' % (len(clumps), len(scanned)))

    found = families(clumps, min_members, min_blocks)
    describe(found)

    print('\n%d families, %d structures, %d blocks in total'
          % (len(found), sum(f['count'] for f in found), sum(f['blocks'] for f in found)))


if __name__ == '__main__':
    main()
