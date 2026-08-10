package fr.zeffut.structuresremover.scan;

import fr.zeffut.structuresremover.undo.UndoRecord;
import fr.zeffut.structuresremover.util.Chat;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.util.UUID;

/**
 * Puts back everything a {@link ScanJob} removed, in the same tick-budgeted way.
 */
public final class UndoJob implements Job {
	private final ServerWorld world;
	private final UUID owner;
	private final UndoRecord record;
	private final int blocksPerTick;

	private int cursor;
	private boolean cancelled;

	public UndoJob(ServerWorld world, UUID owner, UndoRecord record, int blocksPerTick) {
		this.world = world;
		this.owner = owner;
		this.record = record;
		this.blocksPerTick = Math.max(64, blocksPerTick);
	}

	@Override
	public boolean tick() {
		if (this.cancelled) {
			this.report("Undo cancelled after " + this.cursor + " blocks.", Formatting.YELLOW);
			return true;
		}

		int end = Math.min(this.record.size(), this.cursor + this.blocksPerTick);
		BlockPos.Mutable position = new BlockPos.Mutable();

		for (; this.cursor < end; this.cursor++) {
			long packed = this.record.positionAt(this.cursor);
			position.set(BlockPos.unpackLongX(packed), BlockPos.unpackLongY(packed), BlockPos.unpackLongZ(packed));
			BlockState state = this.record.stateAt(this.cursor);

			this.world.setBlockState(position, state,
					Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS);

			NbtCompound nbt = this.record.blockEntityAt(packed);

			if (nbt != null) {
				BlockEntity blockEntity = BlockEntity.createFromNbt(
						position.toImmutable(), state, nbt, this.world.getRegistryManager());

				if (blockEntity != null) {
					this.world.addBlockEntity(blockEntity);
				}
			}
		}

		if (this.cursor >= this.record.size()) {
			this.report("Restored " + this.record.size() + " blocks.", Formatting.GREEN);
			return true;
		}

		return false;
	}

	private void report(String message, Formatting colour) {
		Chat.toPlayer(this.world.getServer(), this.owner, Text.literal(message).formatted(colour));
	}

	@Override
	public void cancel() {
		this.cancelled = true;
	}

	@Override
	public UUID owner() {
		return this.owner;
	}

	@Override
	public Text describe() {
		int percent = this.record.size() == 0
				? 100
				: MathHelper.clamp(this.cursor * 100 / this.record.size(), 0, 100);
		return Text.literal("undo — " + percent + "% (" + this.cursor + "/" + this.record.size() + " blocks)");
	}
}
