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
import java.util.HashMap;
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
	private final Map<Long, List<int[]>> byChunk;
	private final List<String> names;
	private final Iterator<Map.Entry<Long, List<int[]>>> remaining;
	private final int chunkCount;
	private final long listed;

	private final BlockPos.Mutable cursor = new BlockPos.Mutable();
	private final Map<String, Integer> renames = new TreeMap<>();

	private boolean stopServerWhenDone;
	private long cleared;
	private long refusedTerrain;
	private long renamed;
	private int chunksDone;

	private PurgeJob(ServerWorld world, boolean dryRun, Map<Long, List<int[]>> byChunk,
			List<String> names, long listed) {
		this.world = world;
		this.dryRun = dryRun;
		this.byChunk = byChunk;
		this.names = names;
		this.remaining = byChunk.entrySet().iterator();
		this.chunkCount = byChunk.size();
		this.listed = listed;
	}

	/**
	 * Reads a deletion list and prepares the work, grouped so each chunk is visited once.
	 *
	 * <p>Each position becomes four ints rather than the four strings it was written as. A
	 * whole-map list is over a million and a half of them, and keeping those as split strings cost
	 * about half a gigabyte of heap — enough, on top of the chunks being held, to run a four
	 * gigabyte server out of memory before it finished. The block names repeat endlessly across the
	 * list, so they are kept once in a table and referred to by number.
	 */
	public static PurgeJob read(ServerWorld world, Path file, boolean dryRun) throws Exception {
		Map<Long, List<int[]>> byChunk = new TreeMap<>();
		Map<String, Integer> ids = new HashMap<>();
		List<String> names = new ArrayList<>();
		long listed = 0;

		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;

			while ((line = reader.readLine()) != null) {
				String[] parts = line.trim().split("\\s+");

				if (parts.length < 4) {
					continue;
				}

				listed++;
				int x = Integer.parseInt(parts[0]);
				int y = Integer.parseInt(parts[1]);
				int z = Integer.parseInt(parts[2]);
				Integer id = ids.get(parts[3]);

				if (id == null) {
					id = names.size();
					ids.put(parts[3], id);
					names.add(parts[3].toLowerCase(Locale.ROOT));
				}

				long key = ChunkPos.toLong(x >> 4, z >> 4);
				byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[]{x, y, z, id});
			}
		}

		return new PurgeJob(world, dryRun, byChunk, names, listed);
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

			if (job.stopServerWhenDone) {
				server.stop(false);
			}
		}
	}

	/**
	 * Asks for the server to shut down once this job is finished.
	 *
	 * <p>For the startup path, where the only reason the server is running is to apply the list.
	 */
	public void stopServerWhenDone() {
		this.stopServerWhenDone = true;
	}

	private void applyChunk(List<int[]> entries) {
		BlockState air = Blocks.AIR.getDefaultState();

		for (int[] entry : entries) {
			cursor.set(entry[0], entry[1], entry[2]);
			BlockState present = world.getBlockState(cursor);

			if (BulkPurge.isTerrain(present.getBlock())) {
				refusedTerrain++;
				continue;
			}

			String expected = names.get(entry[3]);
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
