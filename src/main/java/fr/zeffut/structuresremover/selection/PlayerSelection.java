package fr.zeffut.structuresremover.selection;

import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * The two corners a player has marked with the wand, plus the world they were marked in.
 * Both corners must belong to the same dimension for the selection to be usable.
 */
public final class PlayerSelection {
	@Nullable
	private BlockPos pos1;
	@Nullable
	private BlockPos pos2;
	@Nullable
	private RegistryKey<World> world;

	private boolean wandEnabled;

	public boolean isWandEnabled() {
		return this.wandEnabled;
	}

	public void setWandEnabled(boolean enabled) {
		this.wandEnabled = enabled;
	}

	@Nullable
	public BlockPos getPos1() {
		return this.pos1;
	}

	@Nullable
	public BlockPos getPos2() {
		return this.pos2;
	}

	@Nullable
	public RegistryKey<World> getWorld() {
		return this.world;
	}

	/**
	 * Marks the first corner. Marking a corner in a different dimension drops the other corner,
	 * because a selection can never span two dimensions.
	 */
	public void setPos1(RegistryKey<World> world, BlockPos pos) {
		if (this.world != null && !this.world.equals(world)) {
			this.pos2 = null;
		}

		this.world = world;
		this.pos1 = pos.toImmutable();
	}

	public void setPos2(RegistryKey<World> world, BlockPos pos) {
		if (this.world != null && !this.world.equals(world)) {
			this.pos1 = null;
		}

		this.world = world;
		this.pos2 = pos.toImmutable();
	}

	public void clear() {
		this.pos1 = null;
		this.pos2 = null;
		this.world = null;
	}

	public boolean isComplete() {
		return this.pos1 != null && this.pos2 != null && this.world != null;
	}

	/**
	 * @return the inclusive box covering both corners, or {@code null} if the selection is incomplete
	 */
	@Nullable
	public BlockBox toBox() {
		if (!this.isComplete()) {
			return null;
		}

		return BlockBox.create(this.pos1, this.pos2);
	}
}
