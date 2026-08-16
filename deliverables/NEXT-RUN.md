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

    SR_REGION_DIR=/path/to/fresh/region python3 tools/fits.py server_verify2.txt 4

`fits.py` checks that every listed position holds the block the list names. The result splits the
work in two:

- **100.0%** — the built content has not moved. Apply `server_verify2.txt` as it stands, per
  `RUNBOOK.md`, and skip to step 3. This is the cheap outcome and worth hoping for.
- **below 100%** — players have built or broken things where the list points. The shortfall is the
  size of the problem: a few hundred is drift at the edges, tens of thousands means the base is
  wrong in the way an archive-built list is wrong against the server. **Do not force a list that
  does not fit.** The whole failure mode this project has hit twice is a list applied to a map it
  was not built from.

## Step 2 — only if it no longer fits: rebuild the scan on the new base

    SR_REGION_DIR=<fresh>/region SR_STATE=fresh.pickle python3 tools/discover.py
    SR_STATE=fresh.pickle python3 tools/families.py
    SR_STATE=fresh.pickle python3 tools/emit_families.py repeats.txt
    SR_STATE=fresh.pickle python3 tools/standalone.py 8 25 standalone.txt
    SR_STATE=fresh.pickle python3 tools/family.py 4 && python3 tools/emit_family.py shrines.txt
    python3 tools/combine.py all.txt repeats.txt standalone.txt shrines.txt
    python3 tools/bodies.py bodies.txt

Then `fits.py` against the new list — it must return 100%, since it was built from that very map,
and anything else means a bug rather than drift.

**Two decisions must survive the rebuild**, and they live in `tools/families.py` rather than in any
list precisely so that they do:

- the **honey block trees** — 630 of them, 164,724 blocks, deliberate decor, kept at the owner's
  request;
- the **copper machines** — `gray_concrete` + `waxed_copper_block`, the machines of the map's four
  peoples. Four copies cannot reach the eight a family needs, so nothing has ever listed them, but
  that is a threshold and thresholds move. Three sites still stand on the server, at
  `10129 475 5174`, `-335 311 90` and `5862 498 2965`.

Check both are still in `KEEP` before emitting anything.

## Step 3 — apply, and verify against the map rather than the report

`RUNBOOK.md` has the full procedure. The three gates, in order, none of them skippable:

1. `fits.py` at 100% **before** the purge.
2. the dry run reporting `cleared` + `refused` = `listed`, with `refused` equal to the body count.
3. `verify_purge.py` afterwards, which reads both copies off disk and finds every difference before
   consulting the list. `0 blocks were destroyed that were not on the list` is the line that
   matters.

`tools/spotcheck.py` is the cheap independent cross-check: sample the list against the cleaned copy
and against the untouched original. Everything sampled must be gone from one and present in the
other. One direction alone proves nothing — an empty directory passes the first test.

## Step 4 — getting it back onto the server

Unresolved, and it is the part with no automated route. The panel offers no upload: `write_file` is
text only, `extract_archive` needs an archive already on the server, `install_world` reads
CurseForge, and `create_snapshot` is refused to a sub-user. The map has to go back by SFTP from a
machine that has it, which means the workstation rather than a sandbox — sandbox egress is HTTP and
TLS only.

Take a snapshot or note the newest smartbackup id before replacing anything.

## Known open items

- **Moblin camps.** 181 towers of identical signature — 3×4×3, 12 spruce trapdoors, 9 fences —
  found in the archive around `1792 96 6555`. Never checked against the server's map, and the two
  are not the same map, so treat the coordinates as a starting point and not as a location.
- **Are the two maps nested?** A list built on the archive put 66,825 positions on landscape when
  tried against the server; a list built on the server, sampled 20,000 times against the archive,
  disagreed nowhere. That is the shape of the server being a subset of the archive, and the copper
  machines corroborate it — six sites in the archive, three on the server, three removals the owner
  confirmed. `tools/subset.py` was written to settle it region by region and has never been run.
