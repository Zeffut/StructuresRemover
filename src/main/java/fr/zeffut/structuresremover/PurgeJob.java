package fr.zeffut.structuresremover;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Clears an explicit list of blocks, a few chunks at a time.
 *
 * <p>The list is a file of {@code x y z block} lines naming individual positions — produced by
 * scanning the map for structures that repeat — and those positions are the only thing that is ever
 * touched. Two independent checks stand between the list and the world: the block standing there
 * must not be a landscape block, and it is reported when it is not the block the list names. The
 * first is absolute and the second is only ever counted, because a world upgraded to a newer game
 * version renames blocks under its feet.
 *
 * <p>A whole-map list runs to thirteen thousand chunks and several minutes of work, so it is spread
 * over ticks rather than done in one go: a server running this stays responsive instead of hanging
 * until it finishes.
 */
public final class PurgeJob {
	/** Chunks handled per tick. Enough to finish a large list in minutes without stalling a tick. */
	private static final int CHUNKS_PER_TICK = 8;

	/** The run started from a command, if there is one. At most one runs at a time. */
	private static PurgeJob running;

	/** How often the world is flushed and progress reported. */
	private static final int REPORT_EVERY = 2000;

	private final ServerWorld world;
	private final boolean dryRun;
	private final Map<Long, List<String[]>> byChunk;
	private final Iterator<Map.Entry<Long, List<String[]>>> remaining;
	private final int chunkCount;
	private final long listed;

	private final BlockPos.Mutable cursor = new BlockPos.Mutable();
	private final Map<String, Integer> renames = new TreeMap<>();

	private long cleared;
	private long refusedTerrain;
	private long renamed;
	private int chunksDone;

	private PurgeJob(ServerWorld world, boolean dryRun, Map<Long, List<String[]>> byChunk, long listed) {
		this.world = world;
		this.dryRun = dryRun;
		this.byChunk = byChunk;
		this.remaining = byChunk.entrySet().iterator();
		this.chunkCount = byChunk.size();
		this.listed = listed;
	}

	/**
	 * Reads a deletion list and prepares the work, grouped so each chunk is visited once.
	 */
	public static PurgeJob read(ServerWorld world, Path file, boolean dryRun) throws Exception {
		Map<Long, List<String[]>> byChunk = new TreeMap<>();
		long listed = 0;

		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;

			while ((line = reader.readLine()) != null) {
				String[] parts = line.trim().split("\\s+");

				if (parts.length < 4) {
					continue;
				}

				listed++;
				long key = ChunkPos.toLong(Integer.parseInt(parts[0]) >> 4, Integer.parseInt(parts[2]) >> 4);
				byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(parts);
			}
		}

		return new PurgeJob(world, dryRun, byChunk, listed);
	}

	public long listed() {
		return listed;
	}

	public int chunkCount() {
		return chunkCount;
	}

	public int chunksDone() {
		return chunksDone;
	}

	public long cleared() {
		return cleared;
	}

	public boolean finished() {
		return !remaining.hasNext();
	}

	/**
	 * Does one tick's worth of work.
	 *
	 * @return true once there is nothing left to do
	 */
	public boolean step(MinecraftServer server) {
		for (int i = 0; i < CHUNKS_PER_TICK && remaining.hasNext(); i++) {
			applyChunk(remaining.next().getValue());
			chunksDone++;

			if (chunksDone % REPORT_EVERY == 0) {
				StructuresRemover.LOGGER.info("[purge] {}/{} chunks, {} cleared",
						chunksDone, chunkCount, cleared);
				server.saveAll(true, false, false);
			}
		}

		return finished();
	}

	/** Hands a job to the server to carry out over the coming ticks. Returns false if one is already. */
	public static boolean start(PurgeJob job) {
		if (running != null) {
			return false;
		}

		running = job;
		return true;
	}

	/** The run in progress, or null. */
	public static PurgeJob running() {
		return running;
	}

	/** Abandons the run in progress. What has already been cleared stays cleared. */
	public static PurgeJob stop() {
		PurgeJob job = running;
		running = null;
		return job;
	}

	/** Advances the run in progress by one tick's worth of work. */
	public static void tick(MinecraftServer server) {
		PurgeJob job = running;

		if (job == null) {
			return;
		}

		if (job.step(server)) {
			running = null;
			job.report();
			server.saveAll(true, false, false);
		}
	}

	/** Runs the whole list without giving the server a chance to tick. */
	public void runToEnd(MinecraftServer server) {
		while (!step(server)) {
			// Nothing else to do: the startup path has no ticks to spread the work over.
		}
	}

	private void applyChunk(List<String[]> entries) {
		BlockState air = Blocks.AIR.getDefaultState();

		for (String[] parts : entries) {
			cursor.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
			BlockState present = world.getBlockState(cursor);

			if (BulkPurge.isTerrain(present.getBlock())) {
				refusedTerrain++;
				continue;
			}

			String expected = parts[3].toLowerCase(Locale.ROOT);
			String actual = Registries.BLOCK.getId(present.getBlock()).toString();

			if (!actual.equals(expected)) {
				// The list is read from the save files and the world from a server that may have
				// upgraded it: minecraft:chain became minecraft:iron_chain in 1.21.11, and a strict
				// name check silently skipped every one of them. What protects the ground is the
				// landscape test above; the rename is reported rather than hidden.
				renames.merge(expected + " -> " + actual, 1, Integer::sum);
				renamed++;
			}

			if (!dryRun) {
				world.setBlockState(cursor, air,
						Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS);
			}

			cleared++;
		}
	}

	/** Writes the tally, which is the record of what the run actually did. */
	public void report() {
		StructuresRemover.LOGGER.info("[purge] ==========================================");
		StructuresRemover.LOGGER.info("[purge] cleared {} blocks of {} listed", cleared, listed);
		StructuresRemover.LOGGER.info("[purge] refused because the block was landscape: {}", refusedTerrain);
		StructuresRemover.LOGGER.info("[purge] cleared under a different name than listed: {}", renamed);

		for (Map.Entry<String, Integer> entry : renames.entrySet()) {
			StructuresRemover.LOGGER.info("[purge]   {} x{}", entry.getKey(), entry.getValue());
		}
	}

	/** A one-line summary for whoever asked for the run. */
	public String summary() {
		return String.format(Locale.ROOT,
				"%d blocks cleared of %d listed, %d refused as landscape, %d renamed by the game",
				cleared, listed, refusedTerrain, renamed);
	}
}
