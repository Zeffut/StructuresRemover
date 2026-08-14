package fr.zeffut.structuresremover.scan;

import fr.zeffut.structuresremover.pattern.PatternVariant;
import fr.zeffut.structuresremover.pattern.SavedPattern;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;
import java.util.Set;
import net.minecraft.block.Block;

/**
 * One structure the scan is hunting for, already expanded into the orientations to test.
 *
 * @param origin where the pattern was captured, so that copy can be spared
 * @param world  the dimension it was captured in; {@code origin} only means something there
 */
public record Target(String name, RegistryKey<World> world, BlockPos origin, List<PatternVariant> variants,
		Set<Block> materials) {
	public static Target of(SavedPattern saved, boolean rotations, boolean mirrors) {
		return new Target(saved.name(), saved.world(), saved.pattern().getOrigin(),
				saved.pattern().buildVariants(rotations, mirrors), saved.pattern().structureMaterials());
	}

	/** Pairs a single orientation with the structure it belongs to. */
	public record Candidate(Target target, PatternVariant variant) {
	}
}
