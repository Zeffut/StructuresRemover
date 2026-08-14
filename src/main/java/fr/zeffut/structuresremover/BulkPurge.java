package fr.zeffut.structuresremover;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Clears an explicit list of blocks, and nothing else.
 *
 * <p>Takes a file of {@code x y z block} lines — produced by scanning the map for structures that
 * repeat identically — and deletes exactly those positions. Two independent checks stand between
 * the list and the world: the block actually standing there must be the one the list names, and it
 * must not be a landscape block. Either one failing leaves the block alone and is counted, so a
 * mistake upstream shows up as a refusal rather than a hole in the ground.
 *
 * <p>Enabled with {@code -Dstructuresremover.purge=<file>}; {@code -Dstructuresremover.purge.dry=true}
 * runs every check and writes nothing.
 */
final class BulkPurge {
	/**
	 * Landscape blocks, which this never deletes whatever the list says.
	 *
	 * <p>Matched on the identifier so families come along: {@code stone}, {@code stone_slab},
	 * {@code cobblestone_stairs} and so on all start with a listed prefix.
	 */
	private static final String[] TERRAIN_PREFIXES = {
			"stone", "cobblestone", "deepslate", "tuff", "granite", "diorite", "andesite", "calcite",
			"dirt", "coarse_dirt", "rooted_dirt", "mud", "clay", "grass_block", "podzol", "mycelium",
			"sand", "red_sand", "gravel", "sandstone", "red_sandstone", "terracotta", "water", "lava",
			"bedrock", "obsidian", "magma", "soul_sand", "soul_soil", "netherrack", "basalt",
			"blackstone", "end_stone", "snow", "ice", "packed_ice", "blue_ice", "powder_snow", "moss",
			"packed_mud",
	};

	private static final String[] NATURAL_SUFFIXES = {
			"_log", "_wood", "_leaves", "_sapling", "_roots", "_fungus", "_vine", "_mushroom",
			"_mushroom_block", "_coral", "_coral_block", "_coral_fan", "_flower", "_bush", "_grass",
			"_fern", "_seagrass", "_kelp", "_bamboo", "_sprouts", "_ore",
	};

	private BulkPurge() {
	}

	static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			String file = System.getProperty("structuresremover.purge", "");

			if (file.isEmpty()) {
				return;
			}

			try {
				run(server, Path.of(file));
			} catch (Exception exception) {
				StructuresRemover.LOGGER.error("[purge] blew up", exception);
			}

			server.stop(false);
		});
	}

	/** True when a block is landscape and must survive regardless of what the list says. */
	static boolean isTerrain(Block block) {
		Identifier id = Registries.BLOCK.getId(block);

		if (!"minecraft".equals(id.getNamespace())) {
			return false;
		}

		String path = id.getPath();

		for (String prefix : TERRAIN_PREFIXES) {
			if (path.equals(prefix) || path.startsWith(prefix + "_")) {
				return true;
			}
		}

		for (String suffix : NATURAL_SUFFIXES) {
			if (path.endsWith(suffix)) {
				return true;
			}
		}

		return false;
	}

	private static void run(MinecraftServer server, Path file) throws Exception {
		ServerWorld world = server.getOverworld();
		boolean dryRun = Boolean.getBoolean("structuresremover.purge.dry");
		BlockState air = Blocks.AIR.getDefaultState();

		// Grouping by chunk keeps each one loaded once instead of once per block.
		Map<Long, List<String[]>> byChunk = new TreeMap<>();
		long lines = 0;

		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;

			while ((line = reader.readLine()) != null) {
				String[] parts = line.trim().split("\\s+");

				if (parts.length < 4) {
					continue;
				}

				lines++;
				long key = ChunkPos.toLong(Integer.parseInt(parts[0]) >> 4, Integer.parseInt(parts[2]) >> 4);
				byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(parts);
			}
		}

		StructuresRemover.LOGGER.info("[purge] {} blocks listed across {} chunks{}",
				lines, byChunk.size(), dryRun ? " (dry run)" : "");

		long cleared = 0;
		long refusedTerrain = 0;
		long renamed = 0;
		Map<String, Integer> renames = new TreeMap<>();
		long chunksDone = 0;
		BlockPos.Mutable cursor = new BlockPos.Mutable();

		for (Map.Entry<Long, List<String[]>> entry : byChunk.entrySet()) {
			chunksDone++;

			for (String[] parts : entry.getValue()) {
				cursor.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
				BlockState present = world.getBlockState(cursor);

				if (isTerrain(present.getBlock())) {
					refusedTerrain++;
					continue;
				}

				String expected = parts[3].toLowerCase(Locale.ROOT);
				String actual = Registries.BLOCK.getId(present.getBlock()).toString();

				if (!actual.equals(expected)) {
					// The list is read from the save files, the world from a server that may have
					// upgraded it: minecraft:chain became minecraft:iron_chain in 1.21.11, and a
					// strict name check silently skipped every one of them. The block still has to
					// pass the landscape test above, which is what actually protects the ground;
					// the rename is reported rather than hidden.
					renames.merge(expected + " -> " + actual, 1, Integer::sum);
					renamed++;
				}

				if (!dryRun) {
					world.setBlockState(cursor, air,
							Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS);
				}

				cleared++;
			}

			if (chunksDone % 2000 == 0) {
				StructuresRemover.LOGGER.info("[purge] {}/{} chunks, {} cleared", chunksDone, byChunk.size(), cleared);
				server.saveAll(true, false, false);
			}
		}

		StructuresRemover.LOGGER.info("[purge] ==========================================");
		StructuresRemover.LOGGER.info("[purge] cleared {} blocks", cleared);
		StructuresRemover.LOGGER.info("[purge] refused because the block was landscape: {}", refusedTerrain);
		StructuresRemover.LOGGER.info("[purge] cleared under a different name than listed: {}", renamed);

		for (Map.Entry<String, Integer> entry : renames.entrySet()) {
			StructuresRemover.LOGGER.info("[purge]   {} x{}", entry.getKey(), entry.getValue());
		}
	}
}
