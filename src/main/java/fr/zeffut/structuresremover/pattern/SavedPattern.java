package fr.zeffut.structuresremover.pattern;

import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

/**
 * A pattern a player has put aside under a name, so several different structures can be hunted
 * down in one pass over the world.
 *
 * <p>The blocks are read at capture time and kept: editing or losing the selection afterwards does
 * not change what was saved. {@code world} is only remembered so the copy the pattern came from can
 * be spared when scanning that same dimension.
 */
public record SavedPattern(String name, RegistryKey<World> world, StructurePattern pattern) {
	public String describe() {
		return this.name + " — " + this.pattern.getSizeX() + "x" + this.pattern.getSizeY() + "x"
				+ this.pattern.getSizeZ() + ", " + this.pattern.getSolidCount() + " blocks";
	}
}
