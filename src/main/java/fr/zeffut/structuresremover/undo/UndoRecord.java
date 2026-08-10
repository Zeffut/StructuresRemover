package fr.zeffut.structuresremover.undo;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything a removal overwrote, so it can be put back.
 *
 * <p>Block states are interned by Minecraft, so the state list costs one reference per block.
 * Block entity NBT is only kept for the handful of positions that had one.
 */
public final class UndoRecord {
	/** Beyond this many blocks the record is dropped rather than eating the server's heap. */
	public static final int MAX_BLOCKS = 4_000_000;

	private final RegistryKey<World> world;
	private final LongArrayList positions = new LongArrayList();
	private final List<BlockState> states = new ArrayList<>();
	private final Long2ObjectOpenHashMap<NbtCompound> blockEntities = new Long2ObjectOpenHashMap<>();

	private boolean overflowed;

	public UndoRecord(RegistryKey<World> world) {
		this.world = world;
	}

	public RegistryKey<World> getWorld() {
		return this.world;
	}

	public void record(long pos, BlockState state, @Nullable NbtCompound blockEntityNbt) {
		if (this.overflowed) {
			return;
		}

		if (this.positions.size() >= MAX_BLOCKS) {
			this.overflowed = true;
			this.positions.clear();
			this.states.clear();
			this.blockEntities.clear();
			return;
		}

		this.positions.add(pos);
		this.states.add(state);

		if (blockEntityNbt != null) {
			this.blockEntities.put(pos, blockEntityNbt);
		}
	}

	/** True when the operation was too big to keep an undo for. */
	public boolean hasOverflowed() {
		return this.overflowed;
	}

	public int size() {
		return this.positions.size();
	}

	public long positionAt(int index) {
		return this.positions.getLong(index);
	}

	public BlockState stateAt(int index) {
		return this.states.get(index);
	}

	@Nullable
	public NbtCompound blockEntityAt(long pos) {
		return this.blockEntities.get(pos);
	}
}
