# Map tools

Command-line tools that work directly on the save files, next to the mod. They read region files
themselves rather than going through a server, so a whole map can be examined in minutes; the mod is
what actually writes to the world, from a list these produce.

They are all deliberately built the same way: **nothing is ever described by a box or a radius.**
Every deletion is an explicit list of individual block positions, and terrain is excluded before a
structure is assembled rather than filtered out afterwards — so a terrain block is never a candidate
in the first place.

## Finding structures that repeat identically

    python3 discover.py            # every clump on the map, summarised      -> structures.pickle
    python3 cluster.py             # exact fingerprints for candidates only  -> exact.pickle
    python3 emit.py 2 25 purge.txt # the blocks of the confirmed groups      -> purge.txt

`discover.py` splits the map into connected clumps of non-terrain blocks and records a summary of
each — size, shape, materials. `cluster.py` then fingerprints exactly, four rotations each, but only
the clumps whose summary is shared with another clump, since two clumps cannot be copies unless
their summaries already agree. On this map that is a few per cent of the total.

## Finding structures that repeat but are not identical

Exact matching cannot find the shrines. Of the 137 on this map, no two are the same build — matching
block for block yields 122 different things where a person sees one thing repeated 137 times.

    python3 family.py 4            # sweep for the palette, then grow        -> family.pickle
    python3 emit_family.py         # the blocks of every one found           -> shrines_purge.txt

`family.py` searches by material instead of by shape: it sweeps for the blocks that belong to this
kind of structure and nothing else, joins the ones that touch into cores, and grows each core into
whatever it is physically attached to — bounded by the core's own bounding box plus a margin, so the
shrine comes along and the house next door does not. A core that would grow past the size cap gets
progressively tighter margins rather than being dropped, because a shrine dropped for being too big
is a shrine left standing.

## Checking the result

    python3 evaluate.py            # score against the map's own answer key
    python3 verify_purge.py        # diff the purged map against the original, terrain included

`evaluate.py` uses the marker entities the map's own datapack ships, which say where each shrine is.
Those positions are never used to find anything — only afterwards, to check what was found without
them.

`verify_purge.py` reads both copies of the map back from disk and asks what actually changed, before
consulting the deletion list. Every difference is found first, then matched against the list;
anything that changed and was not on the list is the failure it exists to catch.

## Measured on the BOTW map

| | |
|---|---|
| shrines found | 137 / 137 |
| shrines with no trace left afterwards | 137 / 137 |
| listed blocks actually cleared | 103,172 / 103,172 |
| blocks changed that were not on the list | 0 |
| terrain blocks touched | 0 |

The 3,531 `chain` → `iron_chain` differences the diff also reports are the game renaming a block
while upgrading the world to 1.21.11, not deletions.

Alongside the 137 shrines, 39 unmarked structures were removed. Every one of them contains the
shrine's own glazed terracotta; several are plainly shrines the datapack does not mark, sitting
together off to one side of the map.
