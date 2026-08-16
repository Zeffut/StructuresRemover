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
| RAM | 2 GB of heap is enough — the work is spread over ticks and chunks are released as it goes |
| The mod | built from this repository at **`61e6ddd` or later** — see below, this one matters |

**The jar has to be recent, and an old one fails in a way that looks like a machine problem.** Build
it from a checkout of this branch:

    ./gradlew build      # -> build/libs/structuresremover-1.0.0.jar

Before `61e6ddd` the startup path ran the whole list between two ticks. Minecraft only releases
chunks when it ticks, so every chunk the list touches was held at once. That gives two failures,
both of which read as hardware trouble rather than as a bug:

- the tick watchdog fires at 60 seconds — `A single server tick took 60.02 seconds`, forced stop,
  **exit code 0**, nothing useful in the terminal. Measured at around 4,000 chunks of 13,700.
- with the watchdog off, `OutOfMemoryError` at 6,000–8,000 chunks under `-Xmx4G`.

`61e6ddd` hands the job to the tick loop instead. The same work then holds about a gigabyte and
finishes in a couple of minutes. If you see either failure above, you are running an older jar —
rebuild rather than raising `-Xmx`.

The map is cleaned once, offline. Whatever server the map finally runs on — Paper, Spigot, vanilla —
needs none of this.

## Step 1 — work on a copy, never the original

    cp -r /path/to/world /path/to/work/cleanmap

Every later step points at the copy. If anything goes wrong the original is untouched and the copy
is thrown away.

## Step 2 — lay out the server

Download into an empty directory `server/`:

- the Fabric server launcher for Minecraft **1.21.11**, loader **0.19.3** or newer
  (https://fabricmc.net/use/server/). It downloads under a long versioned name —
  `fabric-server-mc.1.21.11-loader.0.19.3-launcher.x.y.z.jar` or similar, not
  `fabric-server-launch.jar`. Either rename it or substitute the real name everywhere below.
- Fabric API **0.141.6+1.21.11** or newer (https://modrinth.com/mod/fabric-api) → `server/mods/`
- `structuresremover-1.0.0.jar`, built as above from `61e6ddd` or later → `server/mods/`

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
    max-tick-time=-1

The last two lines are not optional, and they guard against opposite failures.

`pause-when-empty-seconds=0`: a server with nobody on it pauses after sixty seconds and stops
ticking, and the purge runs on ticks. Leave it at the default and the run stops partway through
without saying anything, looking exactly like a hang.

`max-tick-time=-1` turns off the watchdog that kills the server when one tick takes more than sixty
seconds. With a current jar no tick comes close, so this is insurance rather than a fix — but the
failure it insures against is a silent one. The watchdog stops the server with **exit code 0** and
says almost nothing, so a run killed by it looks like a run that finished.

`level-name` must match the directory name of the copy. Getting this wrong is the most common
mistake: the server silently generates a brand new empty world and cleans nothing. Step 4 catches it.

## Step 3 — get the list

    B=https://raw.githubusercontent.com/Zeffut/StructuresRemover/claude/structure-selection-deletion-mod-js5d55/deliverables
    curl -L -o server_verify2.txt.gz $B/server_verify2.txt.gz
    curl -L -o server_bodies.txt     $B/server_bodies.txt
    gunzip server_verify2.txt.gz
    wc -l server_verify2.txt        # expect 1685107
    wc -l server_bodies.txt         # expect 6231
    head -1 server_verify2.txt      # expect four fields: x y z minecraft:<block>

`server_verify2.txt.gz` is the current list. `server_verify.txt.gz` is the first pass, 1,538,786
positions across 75 families, and is kept only so a run can be reproduced — use `2` unless you have
a reason not to.

## Step 3b — check the list is about this map

A list is built by reading one map, and is worthless against another: where the two happen to agree
it deletes the right block for the wrong reason, and where they do not it names a block that is not
there. This is what happened with an earlier list here — 66,825 positions holding landscape — and it
only came out once the purge was already running.

    cd StructuresRemover/tools
    SR_REGION_DIR=/path/to/work/cleanmap/region \
        python3 fits.py /path/to/server_verify2.txt 4 /path/to/server_bodies.txt

**Name the body list as the third argument, and do not read the percentage.** 6,231 of the listed
positions are creature bodies and are landscape on purpose; without being told so, `fits.py` counts
them as disagreements, exceeds its own 0.1% threshold, and reports that the list does not describe
the map — against the map it does describe. A check that condemns the correct case gets ignored, so
it is worth understanding rather than working around.

| line | expected | what a departure means |
|---|---|---|
| `hold landscape where landscape was expected` | **6231** | fewer means the bodies are already gone: this copy is not fresh |
| `hold a different built block` | **0** | this is the drift meter |
| `hold a landscape block` | **0** | landscape where none was declared: wrong base |
| `are in a region that is not there` | **0** | missing regions |
| the percentage | **99.63%** | never 100%, and that is correct |

The drift meter is the second line, not the percentage. Renames inflate it without meaning
anything — the same list against a 1.21.8 copy of the archive showed 110 differences of which 106
were `chain` where the upgraded list says `iron_chain` — so read what the differing blocks are
before concluding from the count.

This step is the one that catches a wrong base, and it is cheap. It is worth knowing what its
failure looks like from both sides, because the two maps in play here differ asymmetrically: the
first-pass list built on the **archive** put 66,825 positions on landscape when tried against the
**server**, while the current list built on the **server**, run in full against the **archive**,
differed at four positions out of 1,685,107. Whichever direction you are going, run `fits.py`
before the purge, not after.

## Step 4 — dry run first

    java -Xmx4G -Dstructuresremover.purge="$PWD/server_verify2.txt" \
         -Dstructuresremover.purge.dry=true \
         -jar fabric-server-launch.jar nogui

Use an **absolute** path for the list; the server resolves relative paths against its own directory.

It runs every check, writes nothing, then stops on its own. Read the tally in `logs/latest.log`:

    [purge] 1685107 blocks listed across N chunks (dry run)
    [purge] cleared 1678876 blocks of 1685107 listed
    [purge] refused because the block was landscape: 6231
    [purge] cleared under a different name than listed: N

**Do not go on unless these hold:**

- *cleared* is exactly **1,678,876** and *refused because the block was landscape* is exactly
  **6,231**. The two sum to the 1,685,107 listed. A *cleared* near zero means the server is not
  looking at the right world — check `level-name` against the copy's directory name.

  Those 6,231 are the creature bodies, which step 5b covers. They sit inside the main list and are
  refused there, because they are made of `stone`, `cobblestone`, `andesite`, `blue_ice` and
  `packed_ice` — landscape by both writings of the rule, the Python one in `discover.py` and the
  Java one in `BulkPurge.isTerrain`. Checked: the two lists intersect in 6,231 positions and no
  others, they disagree on no block name, and all five materials are terrain under both rules.

  This is a better check than a `0` would be, because it has a non-trivial expected value — an
  empty world reports zero refusals too, while 6,231 is a number only the right map produces. Read
  it together with step 3b, though. What decides a refusal is the block standing in the world, not
  the name written in the list, so 6,231 is the count only where `fits.py` agreed. If it found
  disagreements, the refusals rise by the same amount, and **the excess over 6,231 is the number of
  positions where the list and the world disagree**. Below 6,231 means the bodies are already gone,
  which on a fresh copy means it is not fresh.
- *cleared under a different name than listed* may be a few thousand. That is expected and not a
  problem: a world upgraded to 1.21.11 has had `minecraft:chain` renamed to `minecraft:iron_chain`
  under it. Those blocks are still deleted, and counted separately so the difference is visible
  instead of hidden.

## Step 5 — the real run

Same command without the dry-run flag:

    java -Xmx4G -Dstructuresremover.purge="$PWD/server_verify2.txt" \
         -jar fabric-server-launch.jar nogui

It reports progress every 2,000 chunks and stops by itself when finished. Expect a few minutes.
Check the same three lines again, on the real run this time.

If the machine is short of memory the JVM may be killed part way through. That is recoverable:
run the same command again. The list is idempotent — every position it names ends up as air, and a
position already cleared is simply counted as refused the second time round, which makes the tally
of a resumed run meaningless while leaving the result correct. When resuming, trust step 6, not the
tally.

## Step 5b — the creatures, which are made of landscape

The map's stone and ice creatures are built out of ice, stone, cobblestone and andesite. The purge
refuses those by default — that refusal is what keeps the ground intact — so their bodies come as a
separate list and the run has to be told, in as many words, that it may delete landscape:

    java -Xmx4G -Dstructuresremover.purge="$PWD/server_bodies.txt" \
         -Dstructuresremover.purge.terrain=true \
         -jar fabric-server-launch.jar nogui

Expect `cleared 6231 blocks of 6231 listed` and a warning line saying the run was allowed to delete
landscape blocks. Run it after step 5, on the same copy.

## Step 6 — check the result

The tally says what the server thinks it did. This says what actually changed:

    git clone https://github.com/Zeffut/StructuresRemover
    cd StructuresRemover/tools
    pip install numpy
    python3 verify_purge.py /path/to/server_verify2.txt 4 /path/to/server/cleanmap/region /path/to/server_bodies.txt

Only numpy is needed. `discover.py` also uses scipy where it is installed and falls back to a plain
walk where it is not, so scipy is never required to check a purge.

The last argument names the positions where deleting a landscape block was deliberate. Without it
every creature body reads as the failure this tool exists to catch, and a real one would be lost
among them.

It reads both the original world and the cleaned one back from disk, finds **every** difference
between them, and only then compares against the list. Expect:

    1685106 of 1685107 listed blocks are now air
    0 of those were landscape blocks and should have been refused
    6231 landscape blocks were deleted where that was asked for
    0 listed blocks changed into something other than air
    0 blocks were destroyed that were not on the list
    66 blocks appeared where there was air
    of the blocks destroyed off the list, 0 were landscape

Those are the figures from the run this list came out of. The one position short of 1,685,107 is
the guard working rather than a miss: the list said `minecraft:hay_block`, the world held
`minecraft:dirt_path`, and a listed position holding landscape is refused whatever the list says.

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

    /sr purgedry /full/path/to/server_verify2.txt
    /sr purge /full/path/to/server_verify2.txt
    /sr purge stop

The work is spread over ticks so the server stays playable. Progress and the same tally go to the
server log.

Since `61e6ddd` the startup path in steps 4 and 5 does the same thing — one job, one tick loop, one
implementation. The choice between them is about whether you want to restart the server, not about
how the work is done. Before that commit they were genuinely different, and the startup path is
what the memory and watchdog failures at the top of this document describe.
