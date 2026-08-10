package fr.zeffut.structuresremover.scan;

import fr.zeffut.structuresremover.pattern.PatternVariant;
import net.minecraft.block.BlockState;

/**
 * Compares one orientation of a pattern against the world at a given corner.
 *
 * <p>Kept free of any world type so it can be exercised against an in-memory grid.
 */
public final class PatternMatcher {
	private PatternMatcher() {
	}

	/**
	 * How many cells a comparison looks at, which is what {@code tolerance} is a percentage of.
	 */
	public static int comparedCells(PatternVariant variant, boolean matchAir) {
		return matchAir
				? variant.sizeX() * variant.sizeY() * variant.sizeZ()
				: variant.solidCount();
	}

	public static int allowedMismatches(PatternVariant variant, boolean matchAir, int tolerance) {
		return (int) ((long) comparedCells(variant, matchAir) * (100 - tolerance) / 100L);
	}

	/**
	 * @param matchAir  when false, empty cells of the pattern accept anything in the world
	 * @param tolerance percentage of compared cells that must be equal, 100 for an exact copy
	 * @return true when the world holds a copy of {@code variant} with its corner at the origin
	 */
	public static boolean matchesAt(BlockSource source, PatternVariant variant,
			int originX, int originY, int originZ, boolean matchAir, int tolerance) {
		int allowed = allowedMismatches(variant, matchAir, tolerance);
		int mismatches = 0;

		for (int y = 0; y < variant.sizeY(); y++) {
			for (int z = 0; z < variant.sizeZ(); z++) {
				for (int x = 0; x < variant.sizeX(); x++) {
					BlockState expected = variant.stateAt(x, y, z);

					if (expected.isAir() && !matchAir) {
						continue;
					}

					BlockState actual = source.getBlockState(originX + x, originY + y, originZ + z);

					// A missing chunk means we cannot prove this is a copy, so it is not one.
					if (actual == null) {
						return false;
					}

					if (actual != expected) {
						mismatches++;

						if (mismatches > allowed) {
							return false;
						}
					}
				}
			}
		}

		return true;
	}
}
