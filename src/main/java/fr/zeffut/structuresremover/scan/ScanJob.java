package fr.zeffut.structuresremover.scan;

import fr.zeffut.structuresremover.pattern.PatternVariant;
import fr.zeffut.structuresremover.pattern.StructurePattern;
import fr.zeffut.structuresremover.undo.UndoManager;
import fr.zeffut.structuresremover.undo.UndoRecord;
import fr.zeffut.structuresremover.util.Chat;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.text.Text;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Finds every copy of a pattern in a world and, in remove mode, wipes them.
 *
 * <p>The scan keys on a single rare block of the pattern (the "anchor"): chunk sections that do not
 * contain it are skipped at palette level, which is what makes scanning an entire map affordable.
 * Every anchor hit then triggers one full comparison of the pattern against the world.
 */
public final class ScanJob implements Job {
	private static final int PROGRESS_INTERVAL_TICKS = 40;

	private final ServerWorld world;
	private final UUID owner;
	private final Map<BlockState, List<PatternVariant>> variantsByAnchor = new HashMap<>();
	private final ScanOptions options;
	private final boolean removeMode;
	private final ChunkSupplier chunks;
	private final WorldReader reader;
	private final BlockPos patternOrigin;

	private final List<Match> matches = new ArrayList<>();
	private final LongOpenHashSet claimedOrigins = new LongOpenHashSet();

	private Phase phase = Phase.SCAN;
	private boolean cancelled;
	private int ticks;

	private int matchCursor;
	private int cellCursor;
	private long blocksChanged;
	private long totalBlocksToChange;
	private UndoRecord undo;

	public ScanJob(ServerWorld world, UUID owner, StructurePattern pattern, List<PatternVariant> variants,
			ScanOptions options, boolean removeMode, ChunkSupplier chunks, RegionIndex regionIndex) {
		this.world = world;
		this.owner = owner;
		this.options = options;
		this.removeMode = removeMode;
		this.chunks = chunks;
		this.reader = new WorldReader(world, regionIndex);
		this.patternOrigin = pattern.getOrigin();

		for (PatternVariant variant : variants) {
			this.variantsByAnchor.computeIfAbsent(variant.anchorState(), state -> new ArrayList<>()).add(variant);
		}

		if (removeMode) {
			this.undo = new UndoRecord(world.getRegistryKey());
		}
	}

	@Override
	public UUID owner() {
		return this.owner;
	}

	@Override
	public void cancel() {
		this.cancelled = true;
	}

	@Override
	public boolean tick() {
		if (this.cancelled) {
			this.finish(true);
			return true;
		}

		this.ticks++;

		boolean done = switch (this.phase) {
			case SCAN -> this.tickScan();
			case REMOVE -> this.tickRemove();
			case DONE -> true;
		};

		if (this.ticks % PROGRESS_INTERVAL_TICKS == 0 && !done) {
			this.reportProgress();
		}

		if (done) {
			this.finish(false);
		}

		return done;
	}

	// ---------------------------------------------------------------- scanning

	private boolean tickScan() {
		int budget = Math.max(1, this.options.chunksPerTick);

		for (int i = 0; i < budget; i++) {
			long packed = this.chunks.next();

			if (packed == ChunkSupplier.EXHAUSTED) {
				return this.finishScan();
			}

			this.scanChunk(ChunkPos.getPackedX(packed), ChunkPos.getPackedZ(packed));

			if (this.limitReached()) {
				return this.finishScan();
			}
		}

		// Chunks are only cached to keep a single match cheap; holding them longer would pin the
		// whole scanned area in memory.
		this.reader.releaseCache();
		return false;
	}

	private boolean finishScan() {
		if (!this.removeMode || this.matches.isEmpty()) {
			this.phase = Phase.DONE;
			return true;
		}

		this.totalBlocksToChange = 0;

		for (Match match : this.matches) {
			this.totalBlocksToChange += match.variant().solidCount();
		}

		this.phase = Phase.REMOVE;
		Chat.toPlayer(this.world.getServer(), this.owner, Text.literal("Scan done: ")
				.formatted(Formatting.GRAY)
				.append(Text.literal(this.matches.size() + " copies").formatted(Formatting.AQUA))
				.append(Text.literal(" — removing " + this.totalBlocksToChange + " blocks...").formatted(Formatting.GRAY)));
		return false;
	}

	private void scanChunk(int chunkX, int chunkZ) {
		Chunk chunk = this.reader.chunk(chunkX, chunkZ);

		if (chunk == null) {
			return;
		}

		ChunkSection[] sections = chunk.getSectionArray();

		for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
			ChunkSection section = sections[sectionIndex];

			if (section == null || section.isEmpty()) {
				continue;
			}

			// Palette-level rejection: if the section never stores an anchor state, nothing inside
			// it can start a copy, and we skip 4096 block reads.
			if (!section.hasAny(this.variantsByAnchor::containsKey)) {
				continue;
			}

			int baseY = chunk.sectionIndexToCoord(sectionIndex) << 4;

			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						List<PatternVariant> candidates = this.variantsByAnchor.get(state);

						if (candidates == null) {
							continue;
						}

						this.testCandidates(candidates,
								(chunkX << 4) + x, baseY + y, (chunkZ << 4) + z);

						if (this.limitReached()) {
							return;
						}
					}
				}
			}
		}
	}

	private void testCandidates(List<PatternVariant> candidates, int hitX, int hitY, int hitZ) {
		for (PatternVariant variant : candidates) {
			int originX = hitX - variant.anchorX();
			int originY = hitY - variant.anchorY();
			int originZ = hitZ - variant.anchorZ();

			long key = BlockPos.asLong(originX, originY, originZ);

			if (this.claimedOrigins.contains(key)) {
				continue;
			}

			if (!this.world.isInHeightLimit(originY)
					|| !this.world.isInHeightLimit(originY + variant.sizeY() - 1)) {
				continue;
			}

			if (!PatternMatcher.matchesAt(this.reader, variant, originX, originY, originZ,
					this.options.matchAir, this.options.tolerance)) {
				continue;
			}

			if (this.options.keepOriginal
					&& originX == this.patternOrigin.getX()
					&& originY == this.patternOrigin.getY()
					&& originZ == this.patternOrigin.getZ()) {
				// Still claim it, so a later orientation does not rediscover the original.
				this.claimedOrigins.add(key);
				continue;
			}

			this.claimedOrigins.add(key);
			this.matches.add(new Match(new BlockPos(originX, originY, originZ), variant));
			return;
		}
	}

	private boolean limitReached() {
		return this.options.maxMatches > 0 && this.matches.size() >= this.options.maxMatches;
	}

	// ---------------------------------------------------------------- removing

	private boolean tickRemove() {
		int budget = Math.max(64, this.options.blocksPerTick);
		// Cells that turn out to be air or already filled cost nothing to write but still cost time
		// to walk, so they get their own ceiling — otherwise a mostly-hollow pattern would run the
		// whole removal inside a single tick.
		int inspectionBudget = budget * 8;
		int written = 0;
		int inspected = 0;
		BlockPos.Mutable cursor = new BlockPos.Mutable();

		while (written < budget && inspected < inspectionBudget) {
			if (this.matchCursor >= this.matches.size()) {
				this.phase = Phase.DONE;
				return true;
			}

			Match match = this.matches.get(this.matchCursor);
			PatternVariant variant = match.variant();

			if (this.cellCursor == 0 && this.options.removeEntities) {
				this.removeEntities(match);
			}

			int cells = variant.sizeX() * variant.sizeY() * variant.sizeZ();

			while (this.cellCursor < cells && written < budget && inspected < inspectionBudget) {
				int index = this.cellCursor++;
				inspected++;
				int x = index % variant.sizeX();
				int z = (index / variant.sizeX()) % variant.sizeZ();
				int y = index / (variant.sizeX() * variant.sizeZ());

				if (variant.stateAt(x, y, z).isAir()) {
					continue;
				}

				cursor.set(match.origin().getX() + x, match.origin().getY() + y, match.origin().getZ() + z);
				BlockState previous = this.world.getBlockState(cursor);

				if (previous == this.options.fill) {
					continue;
				}

				NbtCompound blockEntityNbt = null;
				BlockEntity blockEntity = this.world.getBlockEntity(cursor);

				if (blockEntity != null) {
					blockEntityNbt = blockEntity.createNbtWithIdentifyingData(this.world.getRegistryManager());
				}

				this.undo.record(cursor.asLong(), previous, blockEntityNbt);

				// No neighbour updates: with thousands of blocks going away at once, physics would
				// cost more than the removal itself and would drop sand, water and redstone around.
				this.world.setBlockState(cursor, this.options.fill,
						Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS);
				written++;
				this.blocksChanged++;
			}

			if (this.cellCursor >= cells) {
				this.matchCursor++;
				this.cellCursor = 0;
			}
		}

		return false;
	}

	private void removeEntities(Match match) {
		BlockPos origin = match.origin();
		PatternVariant variant = match.variant();
		Box box = new Box(
				origin.getX(), origin.getY(), origin.getZ(),
				origin.getX() + variant.sizeX(), origin.getY() + variant.sizeY(), origin.getZ() + variant.sizeZ());

		for (Entity entity : this.world.getOtherEntities(null, box, e -> !(e instanceof PlayerEntity))) {
			entity.discard();
		}
	}

	// ---------------------------------------------------------------- reporting

	private void reportProgress() {
		int percent = MathHelper.clamp((int) (this.progress() * 100), 0, 100);
		String detail = this.phase == Phase.SCAN
				? this.chunks.visited() + " chunks, " + this.matches.size() + " copies found"
				: this.blocksChanged + "/" + this.totalBlocksToChange + " blocks removed";

		Chat.actionBar(this.world.getServer(), this.owner,
				Text.literal("StructuresRemover " + percent + "% — " + detail).formatted(Formatting.GRAY));
	}

	private double progress() {
		if (this.phase == Phase.SCAN) {
			return this.chunks.progress();
		}

		return this.totalBlocksToChange == 0 ? 1.0 : (double) this.blocksChanged / this.totalBlocksToChange;
	}

	private void finish(boolean aborted) {
		this.reader.releaseCache();

		if (this.removeMode && this.undo != null) {
			UndoManager.store(this.owner, this.undo);
		}

		Text summary;

		if (aborted) {
			summary = Text.literal("Cancelled after ")
					.formatted(Formatting.YELLOW)
					.append(Text.literal(this.chunks.visited() + " chunks").formatted(Formatting.AQUA))
					.append(Text.literal(", " + this.matches.size() + " copies found, "
							+ this.blocksChanged + " blocks removed.").formatted(Formatting.YELLOW));
		} else if (this.removeMode) {
			summary = Text.literal("Removed ")
					.formatted(Formatting.GREEN)
					.append(Text.literal(this.matches.size() + " copies").formatted(Formatting.AQUA))
					.append(Text.literal(" (" + this.blocksChanged + " blocks, "
							+ this.chunks.visited() + " chunks scanned).").formatted(Formatting.GREEN));
		} else {
			summary = Text.literal("Found ")
					.formatted(Formatting.GREEN)
					.append(Text.literal(this.matches.size() + " copies").formatted(Formatting.AQUA))
					.append(Text.literal(" in " + this.chunks.visited() + " chunks.").formatted(Formatting.GREEN));
		}

		Chat.toPlayer(this.world.getServer(), this.owner, summary);

		if (!this.matches.isEmpty()) {
			int shown = Math.min(this.matches.size(), 10);

			for (int i = 0; i < shown; i++) {
				Match match = this.matches.get(i);
				BlockPos origin = match.origin();
				Chat.toPlayer(this.world.getServer(), this.owner, Text.literal("  • ")
						.formatted(Formatting.DARK_GRAY)
						.append(Text.literal(origin.getX() + " " + origin.getY() + " " + origin.getZ())
								.formatted(Formatting.WHITE))
						.append(Text.literal(" (" + match.variant().describeOrientation() + ")")
								.formatted(Formatting.DARK_GRAY)));
			}

			if (this.matches.size() > shown) {
				Chat.toPlayer(this.world.getServer(), this.owner,
						Text.literal("  ... and " + (this.matches.size() - shown) + " more.")
								.formatted(Formatting.DARK_GRAY));
			}
		}

		if (this.removeMode && this.undo != null && this.undo.hasOverflowed()) {
			Chat.toPlayer(this.world.getServer(), this.owner,
					Text.literal("Operation too large to keep an undo — /sr undo will not work for it.")
							.formatted(Formatting.YELLOW));
		}
	}

	@Override
	public Text describe() {
		int percent = MathHelper.clamp((int) (this.progress() * 100), 0, 100);
		String mode = this.removeMode ? "remove" : "scan";
		return Text.literal(mode + " in " + this.world.getRegistryKey().getValue()
				+ " — " + percent + "% (" + this.phase.name().toLowerCase() + "), "
				+ this.matches.size() + " copies, " + this.chunks.visited() + " chunks visited");
	}

	private enum Phase {
		SCAN,
		REMOVE,
		DONE
	}
}
