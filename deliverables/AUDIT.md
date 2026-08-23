# Looking at the structures one at a time

Every number this project has produced answers the same question: *was anything destroyed that was
not on the list?* No, consistently, and that stays true. It is the wrong question for judging whether
the result is **clean**, because a structure can be correctly identified, correctly deleted, and
still leave something nobody would want.

So this is the other pass: pick structures out of the list, read every position around them, and ask
what a player standing there would see afterwards. Done by reading `server_verify2.txt` directly —
no aggregate, no sampling statistics, individual buildings at named coordinates.

Two findings, one of them large. Neither is a bug in the tools. Both are the tools doing exactly
what they were told, on cases nobody had looked at.

## Finding 1 — the biggest family in the list is underground walkways

The largest family by blocks: **2,453 structures, 205,277 blocks, 12% of the entire deletion list.**
Its summary in the family report is just `oak_planks`, which is unhelpful enough that it was worth
opening.

Four members, read at random from opposite corners of the map:

| | |
|---|---|
| `619 6 -57` | 3 wide in z, y 6–7, pure oak planks, running 20+ blocks in x |
| `4810 7 -446` | 3 wide, y 7 |
| `4811 2 -1660` | 3 wide, y 2 |
| `13198 5 7998` | 3 wide, y 5, running in x |

Always **exactly three blocks wide**. Always pure oak planks — nothing attached, because a clump is
a maximal connected group and these have no second material in them. Always deep: y between 2 and
19. Median member is 75 blocks, which is a 3 × 25 strip.

Counted across the whole list: **137,511 oak plank positions at y 0–9, and 69,643 more at y 10–19.**
About 207,000, which is the whole family.

**They are not mineshafts.** That was the first guess and it is wrong: a vanilla mineshaft corridor
is three wide, but it comes with fences, rails and cobwebs. The list contains **zero rails below
y=10**, and 34 oak fences in total down there. Whatever these are, they were placed without any of
a mineshaft's furniture.

So: a network of three-wide wooden walkways threaded under the whole map, in straight runs, several
thousand segments. That is infrastructure, not a repeated decoration, and it is the single largest
thing the purge would remove.

**What cannot be read from a text file:** whether those planks bridge open air. If they are a floor
over caves, removing them opens holes to fall through. If they rest on stone, removing them exposes
stone and costs nothing. That difference decides whether this family may be deleted at all, and it
takes one look in game to settle. Fly to `13198 5 7998` and look down.

Until somebody has, **this family should be held out of the list.** It is 12% of the work and 100%
of the unknown.

## Finding 2 — the village houses would be left as shells

Covered in full in `SHELLS.md`. In short: at wall height, the list contains the window panes and not
the wall they are set into. Cobblestone is protected as landscape because it generates on its own,
`oak_log` is protected by the `_log` suffix, and the whole list contains **zero `oak_log`**. So the
purge takes floor, roof, door, bed, chest, torches and windows, and leaves a roofless cobblestone
shell.

Confirmed at `-5090 68 180` and `-1301 68 3345`, two members of two different families.

## Every family opened so far

Not everything is affected, and saying which is as useful as saying which is not. Eleven families
opened, one member each, read position by position.

| family | sample | verdict |
|---|---|---|
| oak planks, 2,453 | `619 6 -57` | **hold** — the underground walkways above |
| glass pane + oak, 1,292 | `-5090 68 180` | **shell** — nothing at wall height but the window panes |
| oak + slab + stairs, 462 | `-1301 68 3345` | **shell** — same signature |
| cobblestone stairs + oak, 421 | `12205 70 3883` | **shell** — doors and torches at y 70, no wall |
| oak + trapdoor, 331 | `160 70 -3635` | **shell** — panes, torch, door, no wall |
| oak + stairs + wall torch, 674 | `11533 70 2695` | **clean** — `white_terracotta` walls listed at y 70–71 |
| stables, dark oak, 61 × 804 | `-158 67 -4910` | **clean** — `dark_oak_planks` wall posts at y 73–75 |
| green concrete, 178 | `3517 152 4109` | **clean** — solid fill y 158–161 |
| light blue concrete, 268 | `8172 134 -55` | **clean** — a tower, y 90 to 139, lapis and stairs, nothing protected |
| gray concrete powder, 858 | `6077 223 2590` | **clean** — sparse, high, y 221–224 |
| shrines, 147 | — | **clean** — glazed terracotta, concrete, stained glass |

**The shell problem is a village variant problem, not a general one.** It hits the houses whose walls
are raw cobblestone or logs — the plains and taiga builds. The savanna houses at `11533` have
`white_terracotta` walls, which are crafted and therefore listed, and they come out whole. Confirmed
shells so far: 1,292 + 462 + 421 + 331 = **2,506 structures**, with two oak-plank families not yet
opened.

One more thing the stable shows, and it is the rule working the way it should: `cobblestone_stairs`
**is** in the list while raw cobblestone is not. Stairs carry a worked marker, so cut stone is told
apart from the stone it was cut from. That same distinction is what the shells finding runs into
from the other side — the wall is raw, the roof is cut, and only the roof goes.

## What this changes

Nothing about the method and nothing about the verification, both of which did what they claim.
What it changes is what "finished" means: a list that passes every check can still be a list that
should not be applied as it stands.

Three things now want a decision rather than a measurement:

1. The 2,453 underground walkways — hold out until somebody looks.
2. The village houses with raw walls — either extend into their walls the way `bodies.py` extends
   into the creatures, or drop those four families and leave the houses whole.
3. Everything else — unaffected, and can proceed as `PLAN.md` sets out.

Two oak-plank families remain unopened. This document is a beginning, not a survey.
