# Cleaning the map locally — instructions for an agent

Applies a deletion list to a copy of a Minecraft world. The list names individual block positions;
those are the only blocks that get touched.

Everything below is meant to be followed literally. Where a step says to check something, the check
is the point of the step — do not skip it and report success.

## What this needs

| | |
|---|---|
| Java | 21 or newer (`java -version`) |
| Server | **Fabric** for Minecraft **1.21.11** — Paper and Spigot will not load the mod |
| Fabric API | required, the mod does not work without it |
| Disk | about 2.5× the size of the world: the copy, plus room for the server |
| RAM | 4 GB of heap is enough; 6 GB if the machine has it |

The map is cleaned once, offline. Whatever server the map finally runs on — Paper, Spigot, vanilla —
needs none of this.

## Step 1 — work on a copy, never the original

    cp -r /path/to/world /path/to/work/cleanmap

Every later step points at the copy. If anything goes wrong the original is untouched and the copy
is thrown away.

## Step 2 — lay out the server

Download into an empty directory `server/`:

- the Fabric server launcher for Minecraft **1.21.11**, loader **0.19.3** or newer
  (https://fabricmc.net/use/server/)
- Fabric API **0.141.6+1.21.11** or newer (https://modrinth.com/mod/fabric-api) → `server/mods/`
- `structuresremover-1.0.0.jar` → `server/mods/`

Then:

    cd server
    echo "eula=true" > eula.txt
    mv /path/to/work/cleanmap ./cleanmap

`server.properties` — create it with at least:

    level-name=cleanmap
    online-mode=false
    max-players=1
    spawn-protection=0
    pause-when-empty-seconds=0

That last line is not optional. A server with nobody on it pauses after sixty seconds and stops
ticking, and the purge runs on ticks — leave it at the default and the run stops partway through
without saying anything, looking exactly like a hang.

`level-name` must match the directory name of the copy. Getting this wrong is the most common
mistake: the server silently generates a brand new empty world and cleans nothing. Step 4 catches it.

## Step 3 — get the list

    curl -L -o all_purge_clean.txt.gz https://raw.githubusercontent.com/Zeffut/StructuresRemover/claude/structure-selection-deletion-mod-js5d55/deliverables/all_purge_clean.txt.gz
    gunzip all_purge_clean.txt.gz
    wc -l all_purge_clean.txt  # expect 1104511
    head -1 all_purge_clean.txt      # expect four fields: x y z minecraft:<block>

## Step 4 — dry run first

    java -Xmx4G -Dstructuresremover.purge="$PWD/all_purge_clean.txt" \
         -Dstructuresremover.purge.dry=true \
         -jar fabric-server-launch.jar nogui

Use an **absolute** path for the list; the server resolves relative paths against its own directory.

It runs every check, writes nothing, then stops on its own. Read the tally in `logs/latest.log`:

    [purge] 1104511 blocks listed across 12662 chunks (dry run)
    [purge] cleared N blocks of 1104511 listed
    [purge] refused because the block was landscape: 0
    [purge] cleared under a different name than listed: N

**Do not go on unless these hold:**

- *cleared* is close to 1,104,511. A number near zero means the server is not looking at the right
  world — check `level-name` against the copy's directory name.
- *refused because the block was landscape* is **0**. This counts blocks the list named that turned
  out to be terrain; they are never deleted whatever the list says. Anything other than zero means
  the list does not match this map, and it should be reported rather than worked around.
- *cleared under a different name than listed* may be a few thousand. That is expected and not a
  problem: a world upgraded to 1.21.11 has had `minecraft:chain` renamed to `minecraft:iron_chain`
  under it. Those blocks are still deleted, and counted separately so the difference is visible
  instead of hidden.

## Step 5 — the real run

Same command without the dry-run flag:

    java -Xmx4G -Dstructuresremover.purge="$PWD/all_purge_clean.txt" \
         -jar fabric-server-launch.jar nogui

It reports progress every 2,000 chunks and stops by itself when finished. Expect a few minutes.
Check the same three lines again, on the real run this time.

If the machine is short of memory the JVM may be killed part way through. That is recoverable:
run the same command again. The list is idempotent — every position it names ends up as air, and a
position already cleared is simply counted as refused the second time round, which makes the tally
of a resumed run meaningless while leaving the result correct. When resuming, trust step 6, not the
tally.

## Step 6 — check the result

The tally says what the server thinks it did. This says what actually changed:

    git clone https://github.com/Zeffut/StructuresRemover
    cd StructuresRemover/tools
    pip install numpy scipy
    python3 verify_purge.py /path/to/all_purge_clean.txt 4 /path/to/server/cleanmap/region

It reads both the original world and the cleaned one back from disk, finds **every** difference
between them, and only then compares against the list. Expect:

    1104511 of 1104511 listed blocks are now air
    0 of those were landscape blocks and should have been refused
    0 listed blocks changed into something other than air
    0 blocks were destroyed that were not on the list
    88 blocks appeared where there was air
    of the blocks destroyed off the list, 0 were landscape

`verify_purge.py` reads its "before" from `ORIGINAL` at the top of the file — point that at the
untouched original world's `region` directory.

The renames it also reports — `chain` to `iron_chain`, `grass` to `short_grass` — are the game's own
upgrade, not deletions. So are the blocks that appear where there was air: loading a chunk saved
before its generation finished lets that generation resume and place leaves and flowers.

## Step 7 — hand back the map

`server/cleanmap` is the cleaned world. It can be dropped into any server, Paper included; the mod
was only ever a tool for this one pass.

## Alternative: from inside a running server

If the server is already up and the map is loaded, an operator can do the same thing from the
console without restarting:

    /sr purgedry /full/path/to/all_purge_clean.txt
    /sr purge /full/path/to/all_purge_clean.txt
    /sr purge stop

The work is spread over ticks so the server stays playable. Progress and the same tally go to the
server log.
