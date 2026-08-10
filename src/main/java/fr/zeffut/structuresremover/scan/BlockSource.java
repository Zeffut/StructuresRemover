package fr.zeffut.structuresremover.scan;

import net.minecraft.block.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Read-only view of block states used by {@link PatternMatcher}.
 *
 * <p>Returning {@code null} means "unknown" — a chunk that does not exist, or a position outside
 * the world height — and is never treated as air.
 */
@FunctionalInterface
public interface BlockSource {
	@Nullable
	BlockState getBlockState(int x, int y, int z);
}
