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

## Finding the families a map repeats, without being told what to look for

Handing the tool a palette only works because somebody has already looked at one of the structures.
This works the families out from the scan alone:

    python3 families.py                    # what repeats, with evidence
    python3 emit_families.py repeats.txt   # the blocks of every member
    python3 combine.py all.txt repeats.txt shrines_purge.txt

The signal is size agreement. Group clumps by what they are mostly made of and two very different
things appear. A family of repeated structures has members that are all about the same size — 135
clumps of acacia fence and honey block, every one of them 215 or 216 blocks, is a prop the builder
placed 135 times. A material that is merely popular has members of every size, from a doorstep to a
whole castle, and is not a family at all.

On this map that recovers the landmarks unprompted: shrines, Sheikah towers, stables, gerudo tents,
and several thousand vanilla village houses out in the wilderness — without being given a single
block name to look for.

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

## Reading the map's own answer key

    python3 tags.py            # every marker tag in the map, with counts
    python3 tags.py interior   # the positions of one kind

The shrine datapack tags a marker entity at each shrine. Those markers live in the map's entity
regions, not in anything this project produced, so they can always be read back — which matters,
because a scan artefact can be lost while the map cannot. 137 exteriors and 136 interiors.

## What does not work: applying by commands

`commands.py` turns a deletion list into `fill` and `setblock` commands, so it could be applied
through a datapack — text, which is all some hosting panels can write. It compresses well: one
`fill ... replace` per structure per material, splitting the box until it holds nothing but the
structure's own blocks, took 84,174 positions down to 3,327 commands.

It was then tried on a copy of the map, and it should not be used. Of the 84,174 listed blocks, 631
were cleared. 847 turned into something other than air. And 12 blocks were destroyed that were on no
list.

Two separate reasons, both fundamental. Commands fail silently in a function when the chunk they
name is not loaded, and a whole-map list reaches chunks nobody is standing in. And `fill` and
`setblock` run the game's block updates: fences and stairs reconnect, light and fluids move, and
things fall. The mod writes with `FORCE_STATE` and no propagation, which is the whole reason it
exists.

The tool is kept because the measurement is worth keeping, not because the route is usable.
