#!/usr/bin/env python3
"""Compare a purged map against the original, block by block, terrain included.

The deletion list says what should have changed. This reads both maps back from disk and asks what
actually changed, without consulting the list first — every difference is found, then matched
against the list. Anything that changed and was not on the list is the failure this exists to catch,
and a terrain block among those is the one that must never happen.

Only the regions the list touches are read, since nothing else can have changed.
"""
import os
import sys
from collections import Counter, defaultdict
from multiprocessing import Pool

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import discover
import nbt
import survey

try:
    import numpy as np
except ImportError:
    np = None

# Blocks the game renames as it upgrades a world. A world is read here as it was saved and by the
# server through its upgrades, so these differ without anything having been done to them.
RENAMES = {
    ('minecraft:chain', 'minecraft:iron_chain'),
    ('minecraft:grass', 'minecraft:short_grass'),
    ('minecraft:grass_path', 'minecraft:dirt_path'),
}

ORIGINAL = os.environ.get('SR_ORIGINAL', 'extracted/botwproject/region')
PURGED = '../StructuresRemover/run/alltest/region'

# Both directories travel to the workers inside the work item. ORIGINAL could ride on os.environ,
# which spawn passes on, but PURGED comes from argv and used to be a module global mutated in
# main(): a forked worker inherits that mutation and a spawned one does not, so on Windows every
# worker read the default path instead. A missing directory yields no chunks, no differences, and
# a report that says nothing was destroyed off the list — the check passing by failing to look.


def section_names(section):
    """The 4096 block names of one section, in index order, or None if it holds nothing."""
    states = section.get('block_states') or {}
    palette = states.get('palette') or []

    if not palette:
        return None

    names = [entry.get('Name', 'minecraft:air') for entry in palette]
    data = states.get('data')

    if len(palette) == 1 or not data:
        return [names[0]] * 4096

    bits = max(4, (len(palette) - 1).bit_length())
    per_long = 64 // bits
    mask = (1 << bits) - 1

    if np is not None:
        packed = np.asarray(data, dtype=np.int64).view(np.uint64)
        slots = np.arange(4096, dtype=np.int64)
        which = slots // per_long

        if which[-1] >= packed.size:
            return None

        shifts = ((slots % per_long) * bits).astype(np.uint64)
        entries = (np.right_shift(packed[which], shifts) & np.uint64(mask)).astype(np.int64)
        entries[entries >= len(names)] = 0
        return [names[i] for i in entries.tolist()]

    out = []

    for i in range(4096):
        entry = (data[i // per_long] >> ((i % per_long) * bits)) & mask
        out.append(names[entry] if entry < len(names) else names[0])

    return out


def chunk_sections(path, wanted=None):
    """{(chunk x, chunk z, section y): [names]} for one region file.

    ``wanted`` limits it to the chunks the deletion list touches. Decoding every section of every
    chunk in a region means unpacking tens of millions of blocks that the purge never looked at, and
    over 788 regions that does not finish. The purge can only alter a chunk it loads, and it only
    loads the chunks the list names, so those are the ones compared.
    """
    out = {}

    for cx, cz, offset, sectors in survey.chunk_entries(path):
        if wanted is not None and (cx, cz) not in wanted:
            continue

        raw = survey.read_chunk(path, offset, sectors)

        if raw is None:
            continue

        try:
            root = nbt.parse(raw)
        except Exception:
            continue

        for section in root.get('sections', []):
            names = section_names(section)

            if names is not None:
                out[(cx, cz, section.get('Y', 0))] = names

    return out


def compare(args):
    """Every block that differs between the two copies of one region."""
    name, wanted, original, purged = args
    before = chunk_sections(os.path.join(original, name), wanted)
    after = chunk_sections(os.path.join(purged, name), wanted)
    changes = []

    for key, old in before.items():
        new = after.get(key)

        if new is None or old == new:
            continue

        cx, cz, sy = key
        ox, oy, oz = cx << 4, sy << 4, cz << 4

        for i, (a, b) in enumerate(zip(old, new)):
            if a != b:
                changes.append((ox + (i & 15), oy + (i >> 8), oz + ((i >> 4) & 15), a, b))

    return name, changes


def main():
    global PURGED

    listing = sys.argv[1] if len(sys.argv) > 1 else 'shrines_purge.txt'
    workers = int(sys.argv[2]) if len(sys.argv) > 2 else 4

    if len(sys.argv) > 3:
        PURGED = sys.argv[3]

    # Positions where deleting a landscape block was deliberate. The map's stone and ice creatures
    # are built out of landscape blocks, so removing them means saying which ones in advance —
    # otherwise every one of them reads here as the failure this tool exists to catch, and the real
    # thing would be lost among them.
    allowed = set()

    if len(sys.argv) > 4:
        with open(sys.argv[4]) as fh:
            for line in fh:
                parts = line.split()

                if len(parts) >= 3:
                    allowed.add((int(parts[0]), int(parts[1]), int(parts[2])))

        print('%d positions allowed to be landscape' % len(allowed))

    listed = {}

    with open(listing) as fh:
        for line in fh:
            parts = line.split()
            listed[(int(parts[0]), int(parts[1]), int(parts[2]))] = parts[3]

    touched = defaultdict(set)

    for x, y, z in listed:
        touched['r.%d.%d.mca' % (x >> 9, z >> 9)].add((x >> 4, z >> 4))

    regions = sorted(touched)
    print('%d blocks listed, in %d chunks across %d regions'
          % (len(listed), sum(len(v) for v in touched.values()), len(regions)), flush=True)
    print('before: %s' % ORIGINAL, flush=True)
    print('after:  %s' % PURGED, flush=True)

    for label, path in (('original', ORIGINAL), ('purged', PURGED)):
        if not os.path.isdir(path):
            print('the %s region directory %s is not there. Nothing can be compared against it, '
                  'and a comparison against nothing reports no damage.' % (label, path))
            return 1

    cleared = 0
    renamed = Counter()
    survived = []
    listed_terrain = []
    created = Counter()
    destroyed = []
    unexpected = []
    done = 0

    with Pool(workers) as pool:
        for name, changes in pool.imap_unordered(
                compare, [(r, touched[r], ORIGINAL, PURGED) for r in regions], chunksize=1):
            done += 1

            for x, y, z, old, new in changes:
                if (x, y, z) in listed:
                    if new == 'minecraft:air':
                        cleared += 1

                        if discover.is_terrain(old) and (x, y, z) not in allowed:
                            # The one failure the unlisted-changes check below cannot see. A listed
                            # position holding a landscape block should have been refused at write
                            # time; if it was cleared anyway it counts as cleared and nothing else
                            # would ever mention it.
                            listed_terrain.append((x, y, z, old))
                    else:
                        survived.append((x, y, z, old, new))
                elif (old, new) in RENAMES:
                    # The game renamed this while upgrading the world; nothing was deleted.
                    renamed[old + ' -> ' + new] += 1
                elif old == 'minecraft:air':
                    # Something appeared where there was nothing. Not a deletion, but the world did
                    # change: loading a chunk saved before it was finished lets generation resume.
                    created[new] += 1
                elif new == 'minecraft:air':
                    # The failure this exists to catch: a block destroyed that nobody listed.
                    destroyed.append((x, y, z, old))
                else:
                    unexpected.append((x, y, z, old, new))

            if done % 25 == 0:
                print('  %d/%d regions, %d cleared, %d unexpected'
                      % (done, len(regions), cleared, len(unexpected)), flush=True)

    print('\n%d of %d listed blocks are now air' % (cleared, len(listed)))
    print('%d of those were landscape blocks and should have been refused' % len(listed_terrain))

    if allowed:
        print('%d landscape blocks were deleted where that was asked for' % len(allowed))

    for x, y, z, old in listed_terrain[:10]:
        print('  %d %d %d  %s was deleted' % (x, y, z, old))

    print('%d listed blocks changed into something other than air' % len(survived))

    # Printed rather than only counted: a listed position that became something else is either a
    # rename the game did under us or a block the purge did not put to air, and telling those two
    # apart needs the names. Counting it and hiding it leaves the reader nothing to go on.
    for x, y, z, old, new in survived[:10]:
        print('  %d %d %d  %s -> %s' % (x, y, z, old, new))
    print('\n%d blocks were destroyed that were not on the list' % len(destroyed))

    for x, y, z, old in destroyed[:10]:
        print('  %d %d %d  %s' % (x, y, z, old))

    print('%d blocks appeared where there was air' % sum(created.values()))

    for name, count in created.most_common(8):
        print('  %s x%d' % (name, count))

    print('%d blocks changed in some other way' % len(unexpected))

    if renamed:
        print('\nrenamed by the game while upgrading the world, not deleted:')

        for text, count in renamed.most_common(5):
            print('  %s x%d' % (text, count))

    # Tested on the block that was there BEFORE: a landscape block destroyed shows up as the old
    # value, not the new one. Asking what it became would answer a different question.
    terrain = [c for c in destroyed if discover.is_terrain(c[3])]
    print('\nof the blocks destroyed off the list, %d were landscape' % len(terrain))

    for x, y, z, old in terrain[:10]:
        print('  %d %d %d  %s' % (x, y, z, old))

    kinds = Counter('%s -> %s' % (c[3], c[4]) for c in unexpected)

    for text, count in kinds.most_common(10):
        print('  %s x%d' % (text, count))


if __name__ == '__main__':
    sys.exit(main() or 0)
