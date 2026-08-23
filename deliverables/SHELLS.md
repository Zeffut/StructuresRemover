# The village houses would be left as shells

Found by reading the deletion list position by position, at two village houses picked from two
different families, rather than by any aggregate measure. It is the kind of fault that every count
this project has produced says nothing about.

## What the list takes from a village house, and what it leaves

At `-5090 68 180`, one member of the 1,292-strong `glass_pane, oak_planks, oak_stairs` family:

| y | what the list names there |
|---|---|
| 68 | oak plank floor |
| 69 | yellow bed, oak door |
| 70 | **two glass panes** |
| 71 | two wall torches |
| 72–74 | oak stair roof, rising one block per block of x |

At `-1301 68 3345`, from the 462-strong `oak_planks, oak_slab, oak_stairs` family, the same shape:
plank floor at 68, white bed and chest at 69, **glass panes alone at 70**, stair roof at 71.

Both houses have nothing listed at wall height except the windows. The window panes are the proof:
a pane exists only set into a wall, so the wall is there in the world and is not in the list.

The reason is the terrain rule, working exactly as written. A vanilla village house is built of
cobblestone and oak logs, and both are protected:

- `cobblestone` is named in `NATURAL_EXACT`, in `discover.py` and again in `BulkPurge.isTerrain`,
  because cobblestone generates on its own and taking it would mean taking the ground.
- `oak_log` matches the `_log` suffix in `NATURAL_SUFFIXES`. **The whole list of 1,685,107 positions
  contains zero `oak_log`.**

So applying the list to a village house removes the floor, the roof, the door, the bed, the chest,
the torches and the windows, and leaves the cobblestone walls and the log frame standing — roofless,
floorless, with the windows knocked out. Several thousand times over, out in the wilderness.

## Why no check caught it

Every verification this project runs asks the same question in different ways: *did anything get
destroyed that was not on the list?* The answer has consistently been no, and that answer is true.

None of them asks *does what remains look right?* A shell is not a wrong deletion — every block
removed was correctly identified as part of a repeated structure. It is an **incomplete** one, and
incompleteness is invisible to a tool that only compares against its own list.

That is why looking at individual structures was worth doing, and why it found in twenty minutes
what 1.68 million verified positions did not.

## What is and is not affected

Not everything. It depends on whether a family is built of protected materials:

| | |
|---|---|
| **affected** | the vanilla village houses — cobblestone walls, log frames. The bulk of batch 3 |
| not affected | the shrines: glazed terracotta, concrete, stained glass, none of it protected |
| not affected | the Sheikah towers, stables, gerudo tents — concrete, terracotta, prismarine |
| not affected | anything whose whole palette is worked material |

So the fault is concentrated, not general. It sits precisely on the family that happens to be built
of the two things the ground rule exists to protect.

## The fix already exists in this repository

`bodies.py` solves the identical problem for the stone and ice creatures, which are built out of
ice, stone, cobblestone and andesite — the same protected blocks. It does not weaken the rule. It
starts from the structure's own already-identified built blocks, fills outward **only** through the
body's materials, stays inside the structure's own bounding box plus a small margin, and **abandons
the structure entirely** if the fill grows past a cap — because growing past the cap means the fill
has walked out of the build and into the hillside, and the right answer there is to take nothing.

Four of the 32 creatures were abandoned on exactly that ground. The mechanism is proven to fail
safe, which is the property that matters when the thing being touched is the ground.

The same pass over the village houses would produce a second list of authorised-terrain positions,
applied like `server_bodies.txt` is: separate file, separate run, a flag named explicitly on the
command line, and a warning in the log. The rule is never weakened — it is broken deliberately, in
one place, where somebody decided it should be.

## Before doing that

Look at one house in game first. This document is built from reading a text file; it says the walls
are not in the list, and it infers what they are made of from what the rule protects. Standing in
one of these houses settles in ten seconds what the inference cannot: what the walls actually are,
and whether a shell is what would really be left.

Then decide. Leaving the houses whole is also an answer — the list can simply drop those families,
and the map keeps a few thousand vanilla houses rather than gaining a few thousand ruins.
