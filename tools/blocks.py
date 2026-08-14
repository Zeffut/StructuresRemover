#!/usr/bin/env python3
"""Random access to a map's blocks straight from the region files.

Decoding the packed section data here means a whole shrine can be inspected in milliseconds,
without booting a server and waiting for chunk upgrades.
"""
import functools
import glob
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import survey
import nbt

REGION_DIR = survey.REGION_DIR


def _section_index_map(path):
    """Maps packed chunk position -> (offset, sectors) for one region file."""
    return {(cx, cz): (off, sec) for cx, cz, off, sec in survey.chunk_entries(path)}


class World:
    def __init__(self, region_dir=REGION_DIR):
        self.region_dir = region_dir
        self._region_cache = {}
        self._chunk_cache = {}
        self._cache_order = []

    def _region(self, rx, rz):
        key = (rx, rz)

        if key not in self._region_cache:
            path = os.path.join(self.region_dir, 'r.%d.%d.mca' % (rx, rz))
            self._region_cache[key] = (path, _section_index_map(path)) if os.path.isfile(path) else (None, {})

        return self._region_cache[key]

    def chunk(self, cx, cz):
        """Returns {section_y: (palette, data, bits)} for a chunk, or None."""
        key = (cx, cz)

        if key in self._chunk_cache:
            return self._chunk_cache[key]

        path, index = self._region(cx >> 5, cz >> 5)
        entry = index.get((cx, cz)) if path else None
        sections = None

        if entry is not None:
            raw = survey.read_chunk(path, entry[0], entry[1])

            if raw is not None:
                try:
                    root = nbt.parse(raw)
                except Exception:
                    root = None

                if root is not None:
                    sections = {}

                    for section in root.get('sections', []):
                        states = section.get('block_states')

                        if not states:
                            continue

                        palette = [entry_name(p) for p in states.get('palette', [])]
                        data = states.get('data')
                        bits = max(4, (max(1, len(palette) - 1)).bit_length()) if len(palette) > 1 else 0
                        sections[section.get('Y', 0)] = (palette, data, bits)

        self._chunk_cache[key] = sections
        self._cache_order.append(key)

        if len(self._cache_order) > 512:
            self._chunk_cache.pop(self._cache_order.pop(0), None)

        return sections

    def block(self, x, y, z):
        """Returns the block name at a position, or None when the chunk is absent."""
        sections = self.chunk(x >> 4, z >> 4)

        if sections is None:
            return None

        section = sections.get(y >> 4)

        if section is None:
            return 'minecraft:air'

        palette, data, bits = section

        if len(palette) == 0:
            return 'minecraft:air'

        if len(palette) == 1 or not data:
            return palette[0]

        index = ((y & 15) * 16 + (z & 15)) * 16 + (x & 15)
        per_long = 64 // bits
        value = data[index // per_long]
        shift = (index % per_long) * bits
        entry = (value >> shift) & ((1 << bits) - 1)
        return palette[entry] if entry < len(palette) else 'minecraft:air'


def entry_name(palette_entry):
    """Block name plus its properties, so two orientations are not confused."""
    name = palette_entry.get('Name', 'minecraft:air')
    props = palette_entry.get('Properties')

    if not props:
        return name

    tail = ','.join('%s=%s' % (k, props[k]) for k in sorted(props))
    return '%s[%s]' % (name, tail)
