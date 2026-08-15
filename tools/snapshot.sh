#!/bin/bash
# Keep a dated copy of the lists, and never overwrite an older one.
#
# Every run of the chain produces a new set of lists. Overwriting the last set loses the ability to
# say what was applied when, and to go back to a set that was known good — which matters here,
# because a list is only valid for the map it was built from. Each version is stamped, checksummed,
# and left alone afterwards.
set -euo pipefail

name="${1:?usage: snapshot.sh <name>}"
dir="versions/$(date -u +%Y%m%d-%H%M)-${name}"

if [ -e "$dir" ]; then
    echo "$dir already exists; refusing to overwrite" >&2
    exit 1
fi

mkdir -p "$dir"

for file in "${@:2}"; do
    [ -e "$file" ] && cp "$file" "$dir/"
done

cd "$dir"
sha256sum ./* > SHA256SUMS
echo "kept in $dir:"
wc -l ./*.txt 2>/dev/null | sed 's/^/  /'
echo "  checksums in SHA256SUMS"

# Prove the copy is readable and matches, rather than assuming it.
sha256sum -c SHA256SUMS --quiet && echo "  verified"
