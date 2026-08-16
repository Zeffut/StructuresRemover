# Redoing the cleaning on the server's current map

The cleaned world that exists today was built from smartbackup **18169549**, 15 August 17:10. The
server has kept running since. The owner wants the map as it stands now cleaned, not that one, so
this is a fresh pass from a fresh backup — and the first question is whether the existing list still
fits, because the answer decides how much work follows.

Written down because the session that would have done it lost its shell part way through. Nothing
here is speculative: it is the same chain that produced the current list, with the two decisions
that changed since.

## What already exists, and what is superseded

| | |
|---|---|
| `deliverables/server_verify2.txt.gz` | 1,685,107 positions, 126 families, 15,713 structures — built on the **15 August** map |
| `deliverables/server_bodies.txt` | 6,231 creature-body positions, a subset of the above |
| the cleaned world itself | was at `run/servermap2` in an ephemeral container; assume it is gone |

The lists are the deliverable and they are in git. The world is not, and does not need to be — the
list reproduces it from the same base.

## Step 1 — take a fresh backup and find out how far the map has drifted

`list_smartbackups(server_id=480302)` for the newest id, then the same call with
`download_backup_id=<id>` for a link. Extract the world, then:

    SR_REGION_DIR=/path/to/fresh/region python3 tools/fits.py server_verify2.txt 4 server_bodies.txt

**The third argument is not optional here, and the percentage is not the answer.** 6,231 of the
listed positions are creature bodies, made of ice, stone, cobblestone and andesite — landscape by
construction. Without being told that, `fits.py` reads them as 6,231 disagreements, which is past
its own 0.1% threshold, and it returns `1` with "this list does not describe this map" against the
one map it does describe. Naming `server_bodies.txt` counts them apart.

Read the lines, not the percentage:

| line | expected | what a departure means |
|---|---|---|
| `hold landscape where landscape was expected` | **6,231** | fewer means the bodies are already gone, so the copy is not fresh |
| `hold a different built block` | **0** | something else is standing where the list names a block. This is the one that says *wrong map* |
| `hold a landscape block` | **0** on the map it was built from | the listed block is gone — removed, or replaced by ground. This is *removal since*, not wrong map |
| `are in a region that is not there` | **0** | missing regions |
| the percentage | **99.63%** on a fresh copy | never 100%, and that is correct |

The two middle lines are not the same kind of news and the tool currently adds them together. A
different built block means the list was read from another world. A missing one means somebody took
that block out of this one. Only the first condemns the list.

Then the work splits in two:

- **drift near zero** — apply `server_verify2.txt` as it stands, per `RUNBOOK.md`, and skip to
  step 3. This is the cheap outcome and worth hoping for.
- **drift large** — players have built or broken things where the list points, and the scan has to
  be rebuilt. Tens of thousands means the base is wrong in the way an archive-built list is wrong
  against the server. **Do not force a list that does not fit.** The failure this project has hit
  twice is a list applied to a map it was not built from.

### `fits.py` alone cannot make this decision

It only ever visits positions the list already names. So it bounds what has **disappeared** since
the list was built, and says nothing whatever about what has been **added**. A new copy of a
targeted structure, put up last night, is invisible to it — and that is precisely the thing a stale
list gets wrong.

An earlier draft of this document rested the whole apply-or-rebuild choice on this one test. It is
blind in one direction, so it cannot carry that weight. What answers the other direction is a scan
of the fresh map:

    SR_REGION_DIR=<fresh>/region SR_STATE=fresh.pickle python3 tools/discover.py
    SR_STATE=fresh.pickle python3 tools/families.py

and then a comparison of what comes out against the 126 families the list was built from. Same
families at the same counts means nothing was added and the list is complete. A family that has
gained members has gained them on the map, and those copies are not in the list.

That scan is most of the cost of a rebuild anyway, so the honest shape of the decision is: run it,
and rebuild if it disagrees. `fits.py` stays worth running first because it is cheap and it catches
a wrong base outright, but a clean `fits.py` is a necessary condition, not a sufficient one.

### Measured on backup 18439422, 16 August 13:09

Against the map the server was running twenty hours after the list was built:

| line | value |
|---|---|
| `hold landscape where landscape was expected` | 6,231 — the bodies, exactly |
| `hold a different built block` | **0** |
| `hold a landscape block` | 7,423 (0.44%) |
| `are in a region that is not there` | 0 |
| `chain` among the differences | none, as expected between two upgraded worlds |

Zero built-block disagreements: the list describes this map exactly, wherever its targets are still
standing. The 7,423 are positions whose block is simply gone. Applying the list there is safe rather
than wrong — `air` is in `NATURAL_EXACT`, so those positions are refused, not deleted, and the tally
would read 13,654 refusals instead of 6,231. That gap *is* the drift. The word for applying the list
unchanged is *insufficient*, not *dangerous*.

`fits.py` still prints "this list does not describe this map", because 7,423 is over its 0.1%
threshold. That verdict is too blunt for this case and the threshold wants splitting: a **different
built block** says the map is not the one the list was built from, while a **missing block** says
somebody removed something. The first should condemn; the second should be counted. Two orders of
magnitude separate this from the 66,825 a genuinely wrong base produced.

One measurement not yet made: `fits.py` lumps "holds landscape" together with "holds air", because
`load_blocks` returns only non-terrain and both read as an absence. They mean different things — air
is a removal, stone is a replacement. `spotcheck.py` has a `full_blocks` reader that sees terrain
too, which is where to get the distinction from.

For calibration, the same list measured against the **archive** — a different map, and a 1.21.8
build that was never upgraded — came out at 6,231 expected landscape, 0 unexpected landscape, and
110 different built blocks, of which 106 were `minecraft:chain` where the upgraded list says
`iron_chain`. Four positions genuinely differed out of 1,685,107. Renames inflate the drift meter
and are not drift; look at what the differing blocks are before concluding anything from the count.

## Step 2 — only if it no longer fits: rebuild the scan on the new base

    SR_REGION_DIR=<fresh>/region SR_STATE=fresh.pickle python3 tools/discover.py
    SR_STATE=fresh.pickle python3 tools/families.py
    SR_STATE=fresh.pickle python3 tools/emit_families.py repeats.txt
    SR_STATE=fresh.pickle python3 tools/standalone.py 8 25 standalone.txt
    SR_STATE=fresh.pickle python3 tools/family.py 4
    python3 tools/emit_family.py family.pickle shrines.txt
    python3 tools/combine.py all.txt repeats.txt standalone.txt shrines.txt
    python3 tools/bodies.py bodies.txt

`emit_family.py` takes the pickle first and the output second — it is the one script in this chain
that does not import `discover`, so it inherits neither `SR_STATE` nor `SR_REGION_DIR` and has to be
told both filenames. Given one argument it tries to unpickle the output file, which does not exist
yet. It fails loudly and `combine.py` then stops for want of `shrines.txt`, so the mistake costs
time rather than correctness — but it costs time.

Then `fits.py` against the new list, with the new body list as the third argument. Every listed
position was read from that very map, so `hold a different built block` must be **0** — not "near
zero". Anything above it is a bug in the chain, not drift.

**Two decisions must survive the rebuild**, and they live in `tools/families.py` rather than in any
list precisely so that they do:

- the **honey block trees** — 630 of them, 164,724 blocks, deliberate decor, kept at the owner's
  request. Note which rule actually holds them: `families.py` matches on a clump's three commonest
  materials, and in these trees honey is fifth and honeycomb seventh, so `{honey_block}` and
  `{honeycomb_block}` do not fire here. `{acacia_fence, orange_carpet}` is the one doing the work.
- the **copper machines** — `gray_concrete` + `waxed_copper_block`, the machines of the map's four
  peoples. Four copies cannot reach the eight a family needs, so nothing has ever listed them, but
  that is a threshold and thresholds move. Three sites still stand on the server, at
  `10129 475 5174`, `-335 311 90` and `5862 498 2965`.

  This rule has the same two-materials-in-a-top-three shape as the honey rule that never fired, so
  it was checked rather than assumed: all three sites summarise as `gray_concrete`,
  `waxed_copper_block`, `jungle_planks`, and `is_kept` returns true for that profile. It fires.

Check both are still in `KEEP` before emitting anything.

**And run the last filter, whichever branch you took:**

    python3 tools/keepout.py all.txt all_final.txt

The four great fairy fountains came into use after these lists were built and must not be touched:
Tera `1780 79 6721`, Kaysa `2539 188 4122`, Mija `6891 175 3763`, Cotera `5669 172 5024`. They are
not protected the way everything else is — `KEEP` in `families.py` matches on materials, and nobody
has read a fountain's palette. There are four of them, below the eight a family needs, so nothing
has listed them yet; that is a coincidence about a threshold, not protection, and the copper
machines are in this repository as the same coincidence written down before it broke.

`keepout.py` drops anything within 40 blocks of those four points. It is subtractive only: it can
leave a structure standing near a fountain, it cannot delete something that would otherwise have
survived.

Against the current list it drops **5 positions and no more**, which was checked rather than
assumed: Kaysa, Mija and Cotera have nothing listed within 30 blocks, and Tera has 5 spruce blocks
at about 31 — the edge of a shrine that runs from `1788` to `1799` and is built of light blue glazed
terracotta. They fall inside the 40-block box and go. That is the one place on the map where a
shrine and a fountain come close, and it is worth a look in game before applying: if the shrine is
what stands there and the fountain is clear of it, nothing is lost by dropping five slabs.

**Run it on the existing list too, not only after a rebuild.** If step 1 says the current list still
fits and you are about to apply it unchanged, put it through `keepout.py` first — the fountains came
into use after that list was written, so nothing in it knows about them.

## Step 3 — apply, and verify against the map rather than the report

`RUNBOOK.md` has the full procedure. The three gates, in order, none of them skippable:

1. `fits.py` **before** the purge, read as step 1 sets out: 6,231 expected landscape, 0 different
   built blocks. Not "100%", which no list carrying the bodies can reach.
2. the dry run reporting `cleared` + `refused` = `listed`. On a copy with no drift, `refused` is the
   6,231 bodies and `cleared` is 1,678,876 of 1,685,107. On a copy that has drifted, `refused` rises
   by exactly the count step 1 measured — 13,654 for backup 18439422 — because a position whose
   block is already gone is refused rather than deleted.
3. `verify_purge.py` afterwards, which reads both copies off disk and finds every difference before
   consulting the list. `0 blocks were destroyed that were not on the list` is the line that
   matters.

`tools/spotcheck.py` is the cheap independent cross-check: sample the list against the cleaned copy
and against the untouched original. Everything sampled must be gone from one and present in the
other. One direction alone proves nothing — an empty directory passes the first test.

## Step 4 — getting it back onto the server

The panel offers no upload: `write_file` is text only, `extract_archive` needs an archive already on
the server, `install_world` reads CurseForge, and `create_snapshot` is refused to a sub-user. The
map goes back by SFTP, from a machine that has both the map and a client.

**Which is why steps 1 to 3 belong on that same machine.** An earlier draft of this document left
them unplaced, and a sandbox is the obvious place to put them — but a sandbox's egress is HTTP and
TLS only, so the cleaned map would be born somewhere it cannot leave, and the whole business of
standing up an HTTP receiver on a VPS (`UPLOAD-ENDPOINT.md`) exists only to work around that. Run
the chain where the SFTP client is and none of it is needed.

The workstation qualifies: the panel API answers from it, it has Python with numpy, Java 21, an
OpenSSH client, and room for several copies of a 6 GB world.

Take a snapshot or note the newest smartbackup id before replacing anything.

## Known open items

- **Moblin camps.** 181 towers of identical signature — 3×4×3, 12 spruce trapdoors, 9 fences —
  found in the archive around `1792 96 6555`. Never checked against the server's map, and the two
  are not the same map, so treat the coordinates as a starting point and not as a location.
- **Are the two maps nested?** Largely settled, and not by sampling. The full list of 1,685,107
  positions was run against the archive: 6,231 expected landscape, 0 unexpected, and 110 different
  built blocks — 106 of them `minecraft:chain`, which is the 1.21.9 rename showing up because that
  archive is a 1.21.8 build that was never upgraded. **Four positions out of 1,685,107 genuinely
  differ**, `smooth_sandstone` ×3 and `spruce_planks` ×1.

  So at every position the server's list names, the archive holds the same block, bar four. That is
  the subset shape, measured rather than inferred, and the copper machines agree with it — six sites
  in the archive, three on the server, three removals the owner confirmed. `tools/subset.py` would
  answer the converse, whether the archive holds things the server does not, and has never been run.

- **`fits.py`'s third argument shipped broken, and the fix belongs in this repository.** The first
  run of it returned 0 expected-landscape instead of 6,231. The cause is a Windows/Linux difference:
  `EXPECTED_LANDSCAPE` was a module-level global mutated inside `main()`, which forked workers
  inherit and **spawned** workers do not. The comment justifying it pointed at `REGION_DIR` as
  precedent, but `REGION_DIR` is read from `os.environ`, and spawn passes the environment — a
  constant derived from the environment survives, a global mutated at runtime does not. Writing the
  two as the same mechanism is what hid it. The set has to travel in the work item instead. Anything
  written here that was never run deserves the same suspicion.

- **`keepout.py` has never been run either**, for the same reason. Its expected output on
  `server_verify2.txt` is 1,685,102 written of 1,685,107 read, with 5 dropped near Tera. Anything
  else means the tool, not the map.
