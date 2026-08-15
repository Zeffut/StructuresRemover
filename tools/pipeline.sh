#!/bin/bash
# The whole cleaning, from a scanned map to a verified deletion list.
#
# Every step reads the map named by SR_REGION_DIR. That matters more than it sounds: the server's
# world is not the archive this project started from, and a list built from one does not fit the
# other — applying the archive's list to the server's map had 66,825 blocks refused because there
# was ground where the list expected a structure.
#
# Usage: SR_REGION_DIR=server/botwproject/region SR_STATE=server_structures.pickle ./pipeline.sh <prefix>
set -e

PREFIX="${1:-server}"
cd "$(dirname "$0")"

echo "=== families"
python3 -u families.py | head -20

echo "=== the blocks of every member"
python3 -u emit_families.py "${PREFIX}_repeats.txt" | tail -3

echo "=== the shrines, by their palette"
SR_FAMILY="${PREFIX}_family.pickle" SR_CORES="${PREFIX}_cores.pickle" python3 -u family.py 4 | grep -Ev "^  [0-9]+/" | head -6
python3 -u emit_family.py "${PREFIX}_family.pickle" "${PREFIX}_shrines.txt" | head -2

echo "=== the creatures built out of landscape"
python3 -u bodies.py "${PREFIX}_bodies.txt" | tail -6

echo "=== one list, each position once"
python3 combine.py "${PREFIX}_all.txt" "${PREFIX}_repeats.txt" "${PREFIX}_shrines.txt"

echo "=== drop anything in a chunk that does not exist"
python3 -u keep_existing.py "${PREFIX}_all.txt" "${PREFIX}_main.txt" | tail -3

echo "=== nothing on the main list may be landscape"
python3 - "${PREFIX}_main.txt" <<'PY'
import sys
sys.path.insert(0, '.')
import discover

total = bad = 0

for line in open(sys.argv[1]):
    parts = line.split()
    total += 1

    if discover.is_terrain(parts[3]):
        bad += 1

print('%d blocks listed, %d of them landscape' % (total, bad))
sys.exit(1 if bad else 0)
PY

echo "=== done"
wc -l "${PREFIX}_main.txt" "${PREFIX}_bodies.txt"
