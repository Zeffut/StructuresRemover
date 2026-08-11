package fr.zeffut.structuresremover.scan;

import fr.zeffut.structuresremover.pattern.PatternVariant;
import net.minecraft.util.math.BlockPos;

/**
 * One copy found in the world: which structure it is, where its corner sits, and in which
 * orientation it was found.
 */
public record Match(BlockPos origin, PatternVariant variant, String patternName) {
}
