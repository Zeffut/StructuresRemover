package fr.zeffut.structuresremover.scan;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

/**
 * Per-player knobs controlling how copies are matched and what happens to them.
 *
 * <p>The defaults are the strict ones: an exact copy, in the same orientation, with the air around
 * the structure ignored. Rotations and mirrors have to be asked for explicitly.
 */
public final class ScanOptions {
	/** Also look for copies rotated by 90/180/270 degrees. */
	public boolean rotations = false;

	/** Also look for mirrored copies (doubles the number of variants tested). */
	public boolean mirrors = false;

	/** Percentage of cells that must match for a copy to count. 100 means a byte-for-byte copy. */
	public int tolerance = 100;

	/**
	 * Whether empty cells of the pattern must also be empty in the world.
	 *
	 * <p>Off by default: only the blocks of the structure itself are compared, so a sloppy
	 * selection with air around the structure still matches, and a copy with a tree grown next to
	 * it is still a copy. Turning it on additionally requires the empty space to be empty.
	 */
	public boolean matchAir = false;

	/** Block put in place of the removed structure. */
	public BlockState fill = Blocks.AIR.getDefaultState();

	/** Leave the copy the pattern was captured from untouched. */
	public boolean keepOriginal = true;

	/** Also kill non-player entities standing inside a removed copy (item frames, armour stands...). */
	public boolean removeEntities = false;

	/** Stop after this many copies. 0 means no limit. */
	public int maxMatches = 0;

	/** Chunks pulled from disk per server tick while scanning. Lower it if the server lags. */
	public int chunksPerTick = 8;

	/** Blocks written per server tick while removing. */
	public int blocksPerTick = 20000;

	/**
	 * Whether a captured pattern is shrunk to the blocks it actually contains.
	 *
	 * <p>Only meaningful while {@link #matchAir} is off, since with air matching the empty margin
	 * is part of what is being matched.
	 */
	public boolean trimsPatterns() {
		return !this.matchAir;
	}

	public ScanOptions copy() {
		ScanOptions copy = new ScanOptions();
		copy.rotations = this.rotations;
		copy.mirrors = this.mirrors;
		copy.tolerance = this.tolerance;
		copy.matchAir = this.matchAir;
		copy.fill = this.fill;
		copy.keepOriginal = this.keepOriginal;
		copy.removeEntities = this.removeEntities;
		copy.maxMatches = this.maxMatches;
		copy.chunksPerTick = this.chunksPerTick;
		copy.blocksPerTick = this.blocksPerTick;
		return copy;
	}
}
