# Cleaning in stages, not in one sweep

The owner asked for this to be done part by part, structure by structure, never by generality, and
for the result to be as clean as possible. Two of those are already true of how the tools work, and
saying so is not a way of dodging the request — it is what the request is protecting against:

**Nothing is ever described by a box, a radius, or a rule applied at run time.** Every deletion is an
explicit list of individual block positions, each one carrying the name of the block that must be
standing there. The mod refuses any position whose block does not match, and refuses any position
holding landscape. A structure is removed because it was individually identified, block by block, as
a member of a family that repeats — never because it fell inside a region somebody drew.

What staging adds on top is the thing the tools cannot give: **a human looking at the result before
the next batch runs.** 1.68 million positions applied in one go is one decision. Applied in five
batches with a check between each, it is five decisions, and the first four are cheap to undo
because the world is a copy.

## Order, and why this order

The batches are not equal in risk, and they are deliberately ordered so that the ones with an
independent way to check them come first. If the chain is broken, it should break where the break is
visible.

### Batch 1 — the shrines · 147 structures, 77,943 blocks

First because they are the only thing on the map with an **answer key**. The map's own datapack tags
a marker entity at each shrine, in the entity regions, written by the map's author and by nothing
this project produced. `tools/tags.py` reads them: 137 exteriors, 136 interiors.

So this batch can be scored rather than eyeballed — every marker should stand in emptiness
afterwards. It was 137 of 137 last time. The 10 extra structures beyond the 137 all contain the
shrine's own glazed terracotta and sit together off to one side; they are shrines the datapack does
not mark.

Check before batch 2: `python3 tools/evaluate.py`, then fly to three or four marker coordinates.

### Batch 2 — the large landmarks · ~390 structures, ~175,000 blocks

The families whose members are big and few: the 61 birch-and-dark-oak builds of 804 blocks each, the
48 light-blue-concrete builds of 812, the 178 green concrete and terracotta of 182, the 281 oak fence
builds of 175.

These are the Sheikah towers, the stables and the gerudo tents. Few enough to look at individually,
big enough that a mistake is unmissable. If something is going to be removed that should have
stayed, this is the batch where a person will notice.

Check before batch 3: fly to one member of each family. Four or five places, ten minutes.

### Batch 3 — the repeated village houses · ~11,500 structures, ~1,100,000 blocks

The bulk of the work and the bulk of the blocks: 2,453 oak plank builds of median 75, 1,292 with
glass panes, 1,010 with white terracotta, 858 of gray concrete powder, and the rest of the 83
families.

Large in count, but the least surprising: they are vanilla village houses scattered through the
wilderness, and the family test — same materials, same size within a factor of 2.5 — is at its most
reliable on things that are genuinely copies.

Split it further if you like, one family per run. Nothing prevents that; `combine.py` takes any set
of lists.

Check before batch 4: `verify_purge.py` on the batch. `0 blocks were destroyed that were not on the
list` is the line that matters.

### Batch 4 — the small standalone things · 43 families, 3,708 structures

The props repeated many times over — one placed 1,964 times, and 42 other families like it. These
are the ones added in the second pass, and they carry a condition none of the others do: they are
taken **only where they stand on their own**.

That test was measured on the map: count the built blocks within 3 of each candidate, and keep the
family only if its members are, as a rule, out in the open. Of 94 such families, 43 stand alone and
are taken; **51 sit inside built-up places and are left** — village farms, mineshaft chains, a patch
of light blue terracotta that belongs to a bigger build.

This batch is last among the built things because it is the one where the judgement is finest. A
prop in a field leaves a clean gap; a patch of a wall leaves a hole in a building.

Check before batch 5: fly to two or three of the 43. This is the batch worth the most looking.

### Batch 5 — the creature bodies · 6,231 blocks, and a flag

Separate for a reason that matters: the stone and ice creatures are built out of ice, stone,
cobblestone and andesite — the blocks everything else here refuses to touch. Removing them means
breaking the rule that keeps the ground intact, so it is broken **out loud**: a separate list, a flag
the run has to be given by name, and a warning in the log when a run has it.

    -Dstructuresremover.purge=server_bodies.txt -Dstructuresremover.purge.terrain=true

Four of the 32 creatures are deliberately left alone: their bodies ran past 600 blocks, meaning the
fill had walked out of the creature and into the hillside, and the right answer there is to take
nothing.

## What is never touched, in any batch

| | |
|---|---|
| terrain and everything that grows | excluded before clumping, so it is never a candidate; and refused again at write time |
| the honey block trees | 630 of them, 164,724 blocks — deliberate decor, kept at the owner's request |
| the copper machines | the machines of the four peoples; three still standing at `10129 475 5174`, `-335 311 90`, `5862 498 2965` |
| the four great fairy fountains | Tera, Kaysa, Mija, Cotera — `keepout.py` drops anything within 40 blocks of them |
| the 51 attached small families | left because they stand among other buildings |

The first of those is worth being precise about, because it is the one the owner has asked about
most. Terrain is not filtered out of a list afterwards — it is excluded before structures are
assembled, so a terrain block cannot become part of a structure in the first place. Then the mod
refuses it again when writing. Two independent implementations of the same rule, in two languages,
written twice on purpose.

## Before any of it

The list must be built from the map it will be applied to. This is the failure this project has hit
twice, and it is the only one that has ever destroyed anything.

1. Server stopped, so the save on disk is consistent and nobody is building during the copy.
2. Fresh backup taken, its id written down.
3. **A copy made and never touched again** — it is the "before" that every check compares against.
4. A second copy, which is the one that gets cleaned.
5. `fits.py` against the copy, read as `NEXT-RUN.md` sets out.

## And a timing question the owner should settle

People are cleaning the map by hand today, and the owner has said it will not be up to date before
this evening. A list built from a backup taken mid-work is stale the moment they place another block.

That does not make it dangerous — a position whose block is already gone is refused, not deleted —
but it makes it incomplete, and incomplete is exactly what "as clean as possible" is asking to
avoid. The backup wants taking once they have stopped.

Running the whole chain today on today's backup is still worth doing as a rehearsal: it proves the
tools end to end before anything depends on them, and two of them have never been run at all.
