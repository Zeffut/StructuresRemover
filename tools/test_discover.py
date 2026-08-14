#!/usr/bin/env python3
"""Checks on the parts of the scan that quietly lose structures when they are wrong.

Both of these were real faults, and neither showed up as an error — one produced structures that
were slices of a cave, the other dropped eighteen buildings out of seven and a half thousand. They
are cheap to check and expensive to miss, so they are checked.

Run with: python3 test_discover.py
"""
import sys

import discover


def check(name, condition):
    print('%-4s %s' % ('ok' if condition else 'FAIL', name))
    return condition


def box(x0, y0, z0, dx, dy, dz):
    return {(x, y, z): 'minecraft:oak_planks'
            for x in range(x0, x0 + dx) for y in range(y0, y0 + dy) for z in range(z0, z0 + dz)}


def main():
    passed = []

    # Two separated boxes are two structures; touching ones are a single structure. This is the
    # whole basis of what counts as one thing.
    apart = box(0, 0, 0, 4, 4, 4)
    apart.update(box(20, 0, 0, 4, 4, 4))
    passed.append(check('two boxes standing apart are two structures',
                        len(discover.clumps(apart, 1, 10_000)) == 2))

    joined = box(0, 0, 0, 4, 4, 4)
    joined.update(box(4, 0, 0, 4, 4, 4))
    passed.append(check('two boxes touching are one structure',
                        len(discover.clumps(joined, 1, 10_000)) == 1))

    # Diagonal contact counts, which is why a staircase does not come apart into steps.
    corner = box(0, 0, 0, 2, 2, 2)
    corner.update(box(2, 2, 2, 2, 2, 2))
    passed.append(check('boxes meeting at a corner are one structure',
                        len(discover.clumps(corner, 1, 10_000)) == 1))

    # A structure past the ceiling is dropped whole. It used to be abandoned part-grown, and the
    # unvisited remainder was then picked up as structures of its own — arbitrary slices of one cave.
    huge = box(0, 0, 0, 20, 20, 20)
    passed.append(check('a structure past the ceiling is dropped whole, leaving no offcuts',
                        discover.clumps(huge, 1, 500) == []))

    passed.append(check('a structure under the floor is dropped',
                        discover.clumps(box(0, 0, 0, 2, 2, 2), 100, 10_000) == []))

    # The two ways of splitting into structures must agree, or reading a structure back later gives
    # a different structure than the one that was recorded.
    if discover._label is not None:
        mixed = box(0, 0, 0, 5, 5, 5)
        mixed.update(box(30, 10, 30, 6, 3, 6))
        mixed.update(box(-40, 0, 12, 4, 4, 9))
        walked = sorted(sorted(b) for b in discover._clumps_walked(mixed, 1, 10_000))
        labelled = sorted(sorted(b) for b in discover._clumps_labelled(mixed, 1, 10_000))
        passed.append(check('the fast and the plain way of splitting agree exactly',
                            walked == labelled))

    # A structure lying across a region border is claimed by the region holding its centre, not its
    # corner. Searching only where the corner is lost eighteen buildings out of 7,557.
    passed.append(check('a structure inside one region is looked for in that region only',
                        discover.regions_of((100, 100, 64, 100, 8, 8, 8, ())) == {(0, 0)}))
    passed.append(check('a structure across a border is looked for in every region it reaches',
                        discover.regions_of((100, 508, 64, 508, 8, 8, 8, ()))
                        == {(0, 0), (1, 0), (0, 1), (1, 1)}))
    passed.append(check('negative coordinates land in the right regions',
                        discover.regions_of((100, -4, 64, -4, 8, 8, 8, ()))
                        == {(-1, -1), (0, -1), (-1, 0), (0, 0)}))

    # Worked stone is not landscape, and natural blocks that are named like worked ones are.
    passed.append(check('stone is landscape and stone bricks are not',
                        discover.is_terrain('minecraft:stone')
                        and not discover.is_terrain('minecraft:stone_bricks')
                        and not discover.is_terrain('minecraft:stone_stairs')
                        and not discover.is_terrain('minecraft:mud_brick_wall')))
    passed.append(check('smooth basalt and cobblestone are still landscape',
                        discover.is_terrain('minecraft:smooth_basalt')
                        and discover.is_terrain('minecraft:cobblestone')
                        and discover.is_terrain('minecraft:brain_coral_wall_fan')))

    print('\n%d of %d checks passed' % (sum(passed), len(passed)))
    return 0 if all(passed) else 1


if __name__ == '__main__':
    sys.exit(main())
