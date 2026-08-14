package fr.zeffut.structuresremover;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The landscape guard is the last thing standing between a deletion list and a hole in the map, so
 * it is checked block by block rather than trusted.
 */
class BulkPurgeTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.createGameVersion();
		Bootstrap.initialize();
	}

	@Test
	void groundIsNeverDeleted() {
		List<Block> ground = List.of(
				Blocks.STONE, Blocks.DEEPSLATE, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.SAND,
				Blocks.RED_SAND, Blocks.GRAVEL, Blocks.SANDSTONE, Blocks.WATER, Blocks.LAVA,
				Blocks.BEDROCK, Blocks.OBSIDIAN, Blocks.NETHERRACK, Blocks.SNOW, Blocks.ICE,
				Blocks.PACKED_ICE, Blocks.CLAY, Blocks.PODZOL, Blocks.MYCELIUM, Blocks.MUD,
				Blocks.CALCITE, Blocks.TUFF, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE,
				Blocks.TERRACOTTA, Blocks.MAGMA_BLOCK, Blocks.SOUL_SAND, Blocks.BASALT,
				Blocks.BLACKSTONE, Blocks.END_STONE, Blocks.POWDER_SNOW);

		for (Block block : ground) {
			assertTrue(BulkPurge.isTerrain(block), Registries.BLOCK.getId(block) + " is landscape");
		}
	}

	@Test
	void thingsThatGrowAreNeverDeleted() {
		List<Block> growing = List.of(
				Blocks.OAK_LOG, Blocks.OAK_LEAVES, Blocks.OAK_SAPLING, Blocks.SHORT_GRASS,
				Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN, Blocks.DEAD_BUSH, Blocks.CACTUS,
				Blocks.SUGAR_CANE, Blocks.LILY_PAD, Blocks.VINE, Blocks.KELP, Blocks.SEAGRASS,
				Blocks.BAMBOO, Blocks.COBWEB, Blocks.GLOW_LICHEN, Blocks.POINTED_DRIPSTONE,
				Blocks.MOSS_BLOCK, Blocks.MOSS_CARPET, Blocks.BROWN_MUSHROOM_BLOCK,
				Blocks.MUSHROOM_STEM, Blocks.PUMPKIN, Blocks.MELON, Blocks.BUDDING_AMETHYST,
				Blocks.IRON_ORE, Blocks.DIAMOND_ORE, Blocks.BRAIN_CORAL, Blocks.AIR);

		for (Block block : growing) {
			assertTrue(BulkPurge.isTerrain(block), Registries.BLOCK.getId(block) + " grows on its own");
		}
	}

	@Test
	void builtBlocksStayDeletable() {
		// If any of these were treated as landscape the purge would silently refuse to remove the
		// structures it was pointed at, which is the failure that is easy to miss.
		List<Block> built = List.of(
				Blocks.OAK_PLANKS, Blocks.ACACIA_PLANKS, Blocks.STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS,
				Blocks.BRICKS, Blocks.GLASS, Blocks.WHITE_WOOL, Blocks.YELLOW_WOOL, Blocks.TORCH,
				Blocks.CHEST, Blocks.LADDER, Blocks.IRON_BARS, Blocks.OAK_FENCE, Blocks.OAK_DOOR,
				Blocks.IRON_CHAIN, Blocks.LANTERN, Blocks.SEA_LANTERN, Blocks.GLOWSTONE, Blocks.RAIL,
				Blocks.OAK_SLAB, Blocks.OAK_STAIRS, Blocks.CRAFTING_TABLE, Blocks.FURNACE);

		for (Block block : built) {
			assertFalse(BulkPurge.isTerrain(block), Registries.BLOCK.getId(block) + " was built by someone");
		}
	}

	@Test
	void workedStoneIsNotMistakenForStone() {
		// The whole point of the worked-block marks. Every one of these starts with the name of a
		// landscape block, and a plain prefix test protects them all — which does not save the
		// ground, it just refuses to remove the walls and floors of the structures being targeted.
		assertTrue(BulkPurge.isTerrain(Blocks.STONE));
		assertTrue(BulkPurge.isTerrain(Blocks.DEEPSLATE));
		assertTrue(BulkPurge.isTerrain(Blocks.SANDSTONE));
		assertTrue(BulkPurge.isTerrain(Blocks.RED_SANDSTONE));

		assertFalse(BulkPurge.isTerrain(Blocks.STONE_BRICKS));
		assertFalse(BulkPurge.isTerrain(Blocks.STONE_BRICK_STAIRS));
		assertFalse(BulkPurge.isTerrain(Blocks.STONE_STAIRS));
		assertFalse(BulkPurge.isTerrain(Blocks.STONE_SLAB));
		assertFalse(BulkPurge.isTerrain(Blocks.SANDSTONE_STAIRS));
		assertFalse(BulkPurge.isTerrain(Blocks.RED_SANDSTONE_WALL));
		assertFalse(BulkPurge.isTerrain(Blocks.MUD_BRICK_SLAB));
		assertFalse(BulkPurge.isTerrain(Blocks.DEEPSLATE_TILES));
		assertFalse(BulkPurge.isTerrain(Blocks.POLISHED_ANDESITE));
		assertFalse(BulkPurge.isTerrain(Blocks.CUT_SANDSTONE));
		assertFalse(BulkPurge.isTerrain(Blocks.SMOOTH_STONE));
		assertFalse(BulkPurge.isTerrain(Blocks.COBBLESTONE_WALL));
		assertFalse(BulkPurge.isTerrain(Blocks.STONECUTTER));
	}

	@Test
	void naturalBlocksNamedLikeWorkedOnesSurvive() {
		// Smooth basalt lines amethyst geodes and gilded blackstone generates in bastions; both
		// carry a worked-block mark in their name and are landscape all the same.
		assertTrue(BulkPurge.isTerrain(Blocks.SMOOTH_BASALT));
		assertTrue(BulkPurge.isTerrain(Blocks.GILDED_BLACKSTONE));
		assertTrue(BulkPurge.isTerrain(Blocks.COBBLESTONE));
		assertTrue(BulkPurge.isTerrain(Blocks.MOSSY_COBBLESTONE));
		assertTrue(BulkPurge.isTerrain(Blocks.COBBLED_DEEPSLATE));
		assertTrue(BulkPurge.isTerrain(Blocks.BRAIN_CORAL_WALL_FAN));
	}
}
