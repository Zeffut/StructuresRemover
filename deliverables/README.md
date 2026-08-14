# The cleaned map, as a deletion list

`all_purge.txt.gz` is the whole job: **1,549,481 block positions**, one per line, in the form

    x y z minecraft:oak_planks

Applying it to a copy of the map produces the cleaned map exactly — the same blocks removed, block
for block, because it is the same file that was applied and verified here. Nothing is described by a
box or a radius, so there is no way for it to reach a block that was not individually identified as
part of a repeated structure.

The map itself is not here: 5.9 GB unpacked, 2.6 GB compressed, far past what a git repository
holds. The list is 5.6 MB and produces the same result.

## Applying it

Needs a **Fabric** server on 1.21.11 with this mod in `mods/` — Paper and Spigot will not load it.
The map is cleaned offline, once; the server it eventually runs on does not need the mod at all.

    gunzip all_purge.txt.gz

Point the server at a copy of the map, then either run it once with

    java -Dstructuresremover.purge=/full/path/to/all_purge.txt -jar fabric-server.jar

or, on a server already running, from the console or in game as an operator:

    /sr purge /full/path/to/all_purge.txt

`/sr purgedry <file>` runs every check and writes nothing, which is the safe way to see what it
would do first. `/sr purge stop` abandons a run in progress. Work is spread over ticks, so the
server stays playable; a list this size takes a few minutes.

Either way the tally lands in the server log:

    [purge] cleared N blocks of 1549481 listed
    [purge] refused because the block was landscape: 0
    [purge] cleared under a different name than listed: N

The middle line is the one that matters. It is the count of blocks the list named that turned out to
be landscape, which are never deleted whatever the list says. It should be zero.

The last line is not a problem: a world upgraded to 1.21.11 has had `minecraft:chain` renamed to
`minecraft:iron_chain` under it, so those blocks no longer carry the name the list recorded. They are
still deleted, and counted separately so the discrepancy is visible rather than hidden.

## What it removes

| | |
|---|---|
| shrines | 137, every one on the map |
| repeated structures | 7,557 across 75 families |
| blocks | 1,549,481 |
| terrain blocks | none |

The families were worked out from the map itself rather than given: village houses out in the
wilderness, gerudo tents, Sheikah towers, stables, shrines. `tools/README.md` covers how, and how it
was checked.
