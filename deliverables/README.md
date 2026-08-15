# The cleaned map, as a deletion list

`all_purge_clean.txt.gz` is the whole job: **1,104,511 block positions**, one per line, in the form

    x y z minecraft:oak_planks

Applying it to a copy of the map produces the cleaned map exactly — the same blocks removed, block
for block, because it is the same file that was applied and verified here. Nothing is described by a
box or a radius, so there is no way for it to reach a block that was not individually identified as
part of a repeated structure.

The map itself is not here: 5.9 GB unpacked, 2.6 GB compressed, far past what a git repository
holds. The list is 3.8 MB and produces the same result.

## Applying it

Needs a **Fabric** server on 1.21.11 with this mod in `mods/` — Paper and Spigot will not load it.
The map is cleaned offline, once; the server it eventually runs on does not need the mod at all.

    gunzip all_purge_clean.txt.gz

Point the server at a copy of the map, then either run it once with

    java -Dstructuresremover.purge=/full/path/to/all_purge_clean.txt -jar fabric-server.jar

or, on a server already running, from the console or in game as an operator:

    /sr purge /full/path/to/all_purge_clean.txt

`/sr purgedry <file>` runs every check and writes nothing, which is the safe way to see what it
would do first. `/sr purge stop` abandons a run in progress. Work is spread over ticks, so the
server stays playable; a list this size takes a few minutes.

Either way the tally lands in the server log:

    [purge] cleared N blocks of 1104511 listed
    [purge] refused because the block was landscape: 0
    [purge] cleared under a different name than listed: N

The middle line is the one that matters. It is the count of blocks the list named that turned out to
be landscape, which are never deleted whatever the list says. It should be zero.

The last line is not a problem: a world upgraded to 1.21.11 has had `minecraft:chain` renamed to
`minecraft:iron_chain` under it, so those blocks no longer carry the name the list recorded. They are
still deleted, and counted separately so the discrepancy is visible rather than hidden.

## What it removes

Measured by reading both copies of the map back from disk and finding every difference before
consulting the list:

| | |
|---|---|
| shrines removed | 137 of 137 |
| repeated structures | 6,912 across 70 families |
| listed blocks cleared | 1,104,511 of 1,104,511 |
| refused as landscape | 0 |
| **blocks destroyed that were not on the list** | **0** |
| terrain blocks destroyed | 0 |

Two things the diff reports that are not deletions. The game renames blocks as it upgrades a world —
295,956 `chain` to `iron_chain` and 1,636 `grass` to `short_grass` — so those differ without anything
having been done to them. And 88 blocks appeared where there was air: 63 oak leaves, 19 dandelions,
a few flowers and one hay bale. Loading a chunk that was saved before its generation had finished
lets that generation resume, which places them. Nothing was destroyed by it.

The honey block trees are deliberately **not** in the list: 630 of them, 164,724 blocks, kept at the
owner's request.

The families were worked out from the map itself rather than given: village houses out in the
wilderness, gerudo tents, Sheikah towers, stables, shrines. `tools/README.md` covers how, and how it
was checked.
