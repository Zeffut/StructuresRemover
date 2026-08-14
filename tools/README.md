# Offline map tools

Python helpers that read Minecraft region files directly. They exist because a full pass over a
large map is minutes of work this way and hours through a running server, and because finding
repeated structures needs to look at the whole map before it can say what repeats.

Point them at a world with `SR_REGION_DIR`:

```bash
export SR_REGION_DIR=/path/to/world/region
python3 discover.py 25 20000        # find every structure that repeats identically
python3 emit.py 3 25 purge.txt      # list the blocks of groups repeating 3+ times
```

`purge.txt` holds one `x y z block` line per block. Feed it to the mod with:

```bash
./gradlew runPurge -Dsrpurge=/abs/path/purge.txt        # -Dsrdry=true to check without writing
```

The mod refuses any line whose block is landscape, or whose block is not the one the list names,
and reports both counts. Terrain is therefore protected twice: it never enters a clump during
discovery, and it is rejected again at write time.

| file | what it does |
| --- | --- |
| `nbt.py` | minimal read-only NBT parser |
| `survey.py` | region file layout: which chunks exist, and their raw NBT |
| `blocks.py` | random access to any block, decoding section palettes |
| `discover.py` | groups non-terrain blocks into clumps and fingerprints them across rotations |
| `emit.py` | turns chosen groups back into an explicit block list |
| `find_repeats.py` | earlier, coarser pass: which block types form many separate clusters |
