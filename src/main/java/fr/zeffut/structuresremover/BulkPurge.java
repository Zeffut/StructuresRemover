package fr.zeffut.structuresremover;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;

import java.nio.file.Path;
import java.util.Set;

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
			"_log", "_wood", "_leaves", "_sapling", "_propagule", "_roots", "_fungus", "_vine",
			"_mushroom", "_mushroom_block", "_coral", "_coral_block", "_coral_fan", "_coral_wall_fan",
			"_flower", "_bush", "_grass", "_fern", "_seagrass", "_kelp", "_bamboo", "_sprouts",
			"_ore", "_amethyst_bud", "_amethyst_cluster",
	};

	/**
	 * Things that grow, named individually because their identifiers share no useful prefix.
	 *
	 * <p>Kept in step with the scanner's list: whatever the scan refuses to treat as part of a
	 * structure, this refuses to delete. The two are written down twice on purpose — the scan runs
	 * over the save files and this runs against the live world, and a single mistake in one of them
	 * should not be enough to take a block out of the ground.
	 */
	private static final Set<String> NATURAL_EXACT = Set.of(
			"air", "cave_air", "void_air", "vine", "kelp", "kelp_plant", "seagrass", "tall_seagrass",
			"short_grass", "tall_grass", "fern", "large_fern", "dead_bush", "cactus", "sugar_cane",
			"lily_pad", "snow", "cobweb", "glow_lichen", "sculk", "sculk_vein", "pointed_dripstone",
			"dripstone_block", "moss_block", "moss_carpet", "bamboo", "sea_pickle",
			"brown_mushroom_block", "red_mushroom_block", "mushroom_stem", "melon", "pumpkin",
			"amethyst_block", "budding_amethyst",
			// Named one by one because they read as worked stone but generate on their own.
			"smooth_basalt", "gilded_blackstone", "cobblestone", "mossy_cobblestone",
			"cobbled_deepslate", "infested_stone", "infested_cobblestone", "infested_deepslate",
			"muddy_mangrove_roots");

	/**
	 * Marks of a block that went through a crafting table or a stonecutter.
	 *
	 * <p>Stone is landscape; stone bricks, stone stairs and deepslate tiles are walls and floors
	 * somebody laid. Without this the prefixes above swallow them, and the purge quietly refuses to
	 * remove the very structures it was pointed at.
	 */
	private static final String[] WORKED_MARKERS = {
			"brick", "polished", "chiseled", "cut_", "smooth", "stairs", "slab", "wall", "button",
			"pressure_plate", "pillar", "tile", "cutter", "cracked", "mossy", "carved", "glazed",
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
				// Only on failure. On success the job is now queued on the tick loop and stops the
				// server itself when it is done; stopping here would end the run before it began.
				StructuresRemover.LOGGER.error("[purge] blew up", exception);
				server.stop(false);
			}
		});
	}

	/** True when a block is landscape and must survive regardless of what the list says. */
	static boolean isTerrain(Block block) {
		Identifier id = Registries.BLOCK.getId(block);

		if (!"minecraft".equals(id.getNamespace())) {
			return false;
		}

		String path = id.getPath();

		if (NATURAL_EXACT.contains(path)) {
			return true;
		}

		// Before the worked marks, because a few natural blocks are named like worked ones: coral
		// wall fans grow, and smooth basalt is what a geode is lined with.
		for (String suffix : NATURAL_SUFFIXES) {
			if (path.endsWith(suffix)) {
				return true;
			}
		}

		for (String marker : WORKED_MARKERS) {
			if (path.contains(marker)) {
				return false;
			}
		}

		for (String prefix : TERRAIN_PREFIXES) {
			if (path.equals(prefix) || path.startsWith(prefix + "_")) {
				return true;
			}
		}

		return false;
	}

	private static void run(MinecraftServer server, Path file) throws Exception {
		boolean dryRun = Boolean.getBoolean("structuresremover.purge.dry");
		boolean allowTerrain = Boolean.getBoolean("structuresremover.purge.terrain");
		PurgeJob job = PurgeJob.read(server.getOverworld(), file, dryRun, allowTerrain);

		StructuresRemover.LOGGER.info("[purge] {} blocks listed across {} chunks{}{}",
				job.listed(), job.chunkCount(), dryRun ? " (dry run)" : "",
				allowTerrain ? " (landscape blocks may be deleted)" : "");

		// Handed to the tick loop rather than run here and now. Running it in one go was the
		// original design and it exhausted the heap on a whole-map list: the server only unloads
		// chunks between ticks, so thirteen thousand of them piled up unreleased. Going through
		// ticks lets them go as it moves on, and the server stops itself when the job reports done.
		job.stopServerWhenDone();
		PurgeJob.start(job);
	}
}
