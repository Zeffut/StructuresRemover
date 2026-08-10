package fr.zeffut.structuresremover.scan;

import fr.zeffut.structuresremover.pattern.PatternVariant;
import net.minecraft.util.math.BlockPos;

/**
 * One copy of the pattern found in the world, identified by the corner it starts at and the
 * orientation it was found in.
 */
public record Match(BlockPos origin, PatternVariant variant) {
}
