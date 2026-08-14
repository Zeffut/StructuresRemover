#!/usr/bin/env python3
"""Read Anvil region files directly to find out what a map is built from.

Parsing the region files beats walking the world through Minecraft here: the map holds 1.3 million
chunks, and only the section palettes are needed to tell which blocks live where.
"""
import glob
import gzip
import os
import random
import re
import struct
import sys
import zlib
from collections import Counter

REGION_DIR = 'extracted/botwproject/region'
NAME_RE = re.compile(rb'minecraft:[a-z_0-9]+')
SECTOR = 4096


def region_coords(path):
    m = re.match(r'r\.(-?\d+)\.(-?\d+)\.mca', os.path.basename(path))
    return int(m.group(1)), int(m.group(2))


def chunk_entries(path):
    """Yields (chunk_x, chunk_z, offset_sectors, sector_count) for every stored chunk."""
    rx, rz = region_coords(path)
    with open(path, 'rb') as fh:
        header = fh.read(SECTOR)

    if len(header) < SECTOR:
        return

    for i in range(1024):
        entry = header[i * 4:i * 4 + 4]

        if entry == b'\0\0\0\0':
            continue

        offset = int.from_bytes(entry[:3], 'big')
        count = entry[3]
        yield (rx << 5) + (i & 31), (rz << 5) + (i >> 5), offset, count


def read_chunk(path, offset, sectors):
    """Returns the decompressed chunk NBT bytes, or None if it cannot be read."""
    try:
        with open(path, 'rb') as fh:
            fh.seek(offset * SECTOR)
            head = fh.read(5)

            if len(head) < 5:
                return None

            length = struct.unpack('>I', head[:4])[0]
            compression = head[4]
            payload = fh.read(length - 1)
    except OSError:
        return None

    try:
        if compression == 1:
            return gzip.decompress(payload)
        if compression == 2:
            return zlib.decompress(payload)
        if compression == 3:
            return payload
    except (zlib.error, OSError, EOFError):
        return None

    return None


def sample(sample_size, seed=1):
    files = sorted(glob.glob(os.path.join(REGION_DIR, '*.mca')))
    random.seed(seed)
    picked = random.sample(files, min(len(files), 400))

    doc_freq = Counter()
    chunks_read = 0

    for path in picked:
        entries = list(chunk_entries(path))

        if not entries:
            continue

        for cx, cz, offset, sectors in random.sample(entries, min(len(entries), max(1, sample_size // len(picked)))):
            raw = read_chunk(path, offset, sectors)

            if raw is None:
                continue

            chunks_read += 1

            for name in set(NAME_RE.findall(raw)):
                doc_freq[name.decode()] += 1

    return chunks_read, doc_freq


if __name__ == '__main__':
    size = int(sys.argv[1]) if len(sys.argv) > 1 else 2000
    read, freq = sample(size)
    print('chunks sampled: %d, distinct names: %d\n' % (read, len(freq)))

    print('=== most widespread (terrain) ===')
    for name, n in freq.most_common(30):
        print('%6.2f%%  %s' % (100.0 * n / read, name))

    print('\n=== rare, present in 0.2%%-8%% of chunks (structure candidates) ===')
    rare = [(n, c) for n, c in freq.items() if 0.002 * read <= c <= 0.08 * read]
    rare.sort(key=lambda item: -item[1])

    for name, n in rare[:70]:
        print('%6.2f%%  %s' % (100.0 * n / read, name))
