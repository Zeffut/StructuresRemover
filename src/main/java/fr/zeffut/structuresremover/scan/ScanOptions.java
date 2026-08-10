package fr.zeffut.structuresremover.scan;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

/**
 * Per-player knobs controlling how copies are matched and what happens to them.
 */
public final class ScanOptions {
	/** Also look for copies rotated by 90/180/270 degrees. */
	public boolean rotations = true;

	/** Also look for mirrored copies (doubles the number of variants tested). */
	public boolean mirrors = false;

	/** Percentage of cells that must match for a copy to count. 100 means a byte-for-byte copy. */
	public int tolerance = 100;

	/** Whether empty cells of the pattern must also be empty in the world. */
	public boolean matchAir = true;

	/** Block put in place of the removed structure. */
	public BlockState fill = Blocks.AIR.getDefaultState();

	/** Leave the copy the selection was taken from untouched. */
	public boolean keepOriginal = true;

	/** Also kill non-player entities standing inside a removed copy (item frames, armour stands...). */
	public boolean removeEntities = false;

	/** Stop after this many copies. 0 means no limit. */
	public int maxMatches = 0;

	/** Chunks pulled from disk per server tick while scanning. Lower it if the server lags. */
	public int chunksPerTick = 8;

	/** Blocks written per server tick while removing. */
	public int blocksPerTick = 20000;

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
