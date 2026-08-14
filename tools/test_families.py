#!/usr/bin/env python3
"""Checks on the rule that decides which clumps form a family.

That rule is what stands between "this is a structure the builder repeated" and "this is a popular
material", and getting it wrong is expensive in both directions: too loose and a castle joins the
list, too tight and a family of shrines is missed. So it is exercised on made-up clumps whose right
answer is known, rather than judged by eye on the map.

Run with: python3 test_families.py
"""
import sys

import families


def clump(size, materials, position=(0, 0, 0)):
    """A clump summary in the shape the scan records: size, corner, extent, materials."""
    return (size, position[0], position[1], position[2], 8, 8, 8, tuple(materials))


WOOD = ('minecraft:oak_planks', 'minecraft:oak_stairs', 'minecraft:glass_pane')
STONE = ('minecraft:stone_bricks', 'minecraft:stone_brick_stairs', 'minecraft:torch')


def check(name, condition):
    print('%-4s %s' % ('ok' if condition else 'FAIL', name))
    return condition


def main():
    passed = []

    # A structure repeated at a consistent size is exactly what should be found.
    same = [clump(200 + i % 5, WOOD, (i * 100, 64, 0)) for i in range(20)]
    found = families.families(same, min_members=8, min_blocks=60)
    passed.append(check('twenty clumps of about 200 blocks form one family',
                        len(found) == 1 and found[0]['count'] == 20))

    # The same materials at wildly different sizes are a material, not a family: this is the case
    # that would otherwise put a whole castle on a deletion list next to a doorstep.
    spread = [clump(size, WOOD, (i * 100, 64, 0))
              for i, size in enumerate([60, 90, 140, 300, 900, 2000, 6000, 20000, 60000, 100000])]
    passed.append(check('the same materials at every size form no family',
                        families.families(spread, min_members=8, min_blocks=60) == []))

    # One outlier is enough to say the group is not a repeated structure.
    with_castle = [clump(200, WOOD, (i * 100, 64, 0)) for i in range(20)]
    with_castle.append(clump(100000, WOOD, (9999, 64, 0)))
    passed.append(check('one clump the size of a castle disqualifies the group',
                        families.families(with_castle, min_members=8, min_blocks=60) == []))

    # Families are kept apart by what they are made of.
    mixed = ([clump(200, WOOD, (i * 100, 64, 0)) for i in range(10)]
             + [clump(400, STONE, (i * 100, 64, 500)) for i in range(10)])
    found = families.families(mixed, min_members=8, min_blocks=60)
    passed.append(check('two different structures give two families',
                        len(found) == 2 and {f['count'] for f in found} == {10}))

    # Too few copies is not a repeated structure.
    passed.append(check('three copies are not enough to call it repeated',
                        families.families([clump(200, WOOD, (i * 100, 64, 0)) for i in range(3)],
                                          min_members=8, min_blocks=60) == []))

    # Small clumps are excluded before grouping, so a family cannot be made of scattered scenery.
    passed.append(check('clumps under the size floor never form a family',
                        families.families([clump(30, WOOD, (i * 100, 64, 0)) for i in range(50)],
                                          min_members=8, min_blocks=60) == []))

    # Real shrines vary by a few blocks each and must still group; this is the case that exact
    # fingerprinting failed on, giving 122 answers for 137 shrines.
    shrines = [clump(size, ('minecraft:gray_concrete', 'minecraft:light_blue_glazed_terracotta',
                            'minecraft:spruce_planks'), (i * 100, 64, 0))
               for i, size in enumerate([235, 260, 271, 273, 280, 288, 291, 302, 311, 330, 355, 588])]
    found = families.families(shrines, min_members=8, min_blocks=60)
    passed.append(check('shrines that are all slightly different still form one family',
                        len(found) == 1 and found[0]['count'] == 12))

    print('\n%d of %d checks passed' % (sum(passed), len(passed)))
    return 0 if all(passed) else 1


if __name__ == '__main__':
    sys.exit(main())
