package fr.zeffut.structuresremover.scan;

import fr.zeffut.structuresremover.pattern.PatternVariant;
import fr.zeffut.structuresremover.pattern.StructurePattern;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternMatcherTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.createGameVersion();
		Bootstrap.initialize();
	}

	/** A tiny sparse world: everything not explicitly set is air, except outside the known area. */
	private static final class FakeWorld implements BlockSource {
		private final Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();
		private int voidBelow = Integer.MIN_VALUE;

		void set(int x, int y, int z, BlockState state) {
			this.blocks.put(BlockPos.asLong(x, y, z), state);
		}

		/** Makes every position with {@code y < limit} report "unknown", like a missing chunk. */
		void unknownBelow(int limit) {
			this.voidBelow = limit;
		}

		@Override
		@Nullable
		public BlockState getBlockState(int x, int y, int z) {
			if (y < this.voidBelow) {
				return null;
			}

			BlockState state = this.blocks.get(BlockPos.asLong(x, y, z));
			return state == null ? Blocks.AIR.getDefaultState() : state;
		}
	}

	/**
	 * A 2x2x3 hut: a stone floor, an oak plank wall, and one north-facing furnace as the rare block.
	 */
	private static StructurePattern hut() {
		int sizeX = 2;
		int sizeY = 2;
		int sizeZ = 3;
		BlockState[] states = new BlockState[sizeX * sizeY * sizeZ];
		java.util.Arrays.fill(states, Blocks.AIR.getDefaultState());

		for (int z = 0; z < sizeZ; z++) {
			for (int x = 0; x < sizeX; x++) {
				states[(0 * sizeZ + z) * sizeX + x] = Blocks.STONE.getDefaultState();
			}
		}

		states[(1 * sizeZ + 0) * sizeX + 0] = Blocks.OAK_PLANKS.getDefaultState();
		states[(1 * sizeZ + 2) * sizeX + 1] = Blocks.FURNACE.getDefaultState()
				.with(HorizontalFacingBlock.FACING, Direction.NORTH);

		try {
			return StructurePattern.of(sizeX, sizeY, sizeZ, states, BlockPos.ORIGIN);
		} catch (StructurePattern.PatternException exception) {
			throw new AssertionError(exception);
		}
	}

	/** Stamps a variant into the fake world with its corner at the given position. */
	private static void stamp(FakeWorld world, PatternVariant variant, int originX, int originY, int originZ) {
		for (int y = 0; y < variant.sizeY(); y++) {
			for (int z = 0; z < variant.sizeZ(); z++) {
				for (int x = 0; x < variant.sizeX(); x++) {
					world.set(originX + x, originY + y, originZ + z, variant.stateAt(x, y, z));
				}
			}
		}
	}

	private static PatternVariant variant(StructurePattern pattern, BlockRotation rotation) {
		return pattern.buildVariants(true, false).stream()
				.filter(candidate -> candidate.rotation() == rotation)
				.findFirst()
				.orElseThrow();
	}

	@Test
	void findsAnExactCopy() {
		StructurePattern pattern = hut();
		PatternVariant upright = variant(pattern, BlockRotation.NONE);
		FakeWorld world = new FakeWorld();
		stamp(world, upright, 100, 64, -30);

		assertTrue(PatternMatcher.matchesAt(world, upright, 100, 64, -30, true, 100));
	}

	@Test
	void rejectsACopyThatIsOffByOneBlock() {
		StructurePattern pattern = hut();
		PatternVariant upright = variant(pattern, BlockRotation.NONE);
		FakeWorld world = new FakeWorld();
		stamp(world, upright, 0, 64, 0);
		world.set(0, 65, 0, Blocks.SPRUCE_PLANKS.getDefaultState());

		assertFalse(PatternMatcher.matchesAt(world, upright, 0, 64, 0, true, 100));
	}

	@Test
	void rejectsAShiftedCopy() {
		StructurePattern pattern = hut();
		PatternVariant upright = variant(pattern, BlockRotation.NONE);
		FakeWorld world = new FakeWorld();
		stamp(world, upright, 0, 64, 0);

		assertFalse(PatternMatcher.matchesAt(world, upright, 1, 64, 0, true, 100));
	}

	@Test
	void findsARotatedCopyOnlyWithTheRotatedVariant() {
		StructurePattern pattern = hut();
		PatternVariant upright = variant(pattern, BlockRotation.NONE);
		PatternVariant turned = variant(pattern, BlockRotation.CLOCKWISE_90);

		FakeWorld world = new FakeWorld();
		stamp(world, turned, 500, 70, 500);

		assertTrue(PatternMatcher.matchesAt(world, turned, 500, 70, 500, true, 100));
		assertFalse(PatternMatcher.matchesAt(world, upright, 500, 70, 500, true, 100));
	}

	@Test
	void anchorOffsetRecoversTheCorner() {
		// This is exactly what the scan does: it spots the anchor block in the world, subtracts the
		// anchor offset, and expects the copy to start there.
		StructurePattern pattern = hut();

		for (PatternVariant variant : pattern.buildVariants(true, true)) {
			FakeWorld world = new FakeWorld();
			stamp(world, variant, 10, 64, 20);

			int anchorWorldX = 10 + variant.anchorX();
			int anchorWorldY = 64 + variant.anchorY();
			int anchorWorldZ = 20 + variant.anchorZ();

			assertEquals(variant.anchorState(), world.getBlockState(anchorWorldX, anchorWorldY, anchorWorldZ));
			assertTrue(PatternMatcher.matchesAt(world, variant,
					anchorWorldX - variant.anchorX(),
					anchorWorldY - variant.anchorY(),
					anchorWorldZ - variant.anchorZ(), true, 100));
		}
	}

	@Test
	void airIsAWildcardWhenMatchAirIsOff() {
		StructurePattern pattern = hut();
		PatternVariant upright = variant(pattern, BlockRotation.NONE);
		FakeWorld world = new FakeWorld();
		stamp(world, upright, 0, 64, 0);

		// Vegetation grew into a cell the pattern leaves empty.
		world.set(1, 65, 0, Blocks.OAK_LEAVES.getDefaultState());

		assertFalse(PatternMatcher.matchesAt(world, upright, 0, 64, 0, true, 100));
		assertTrue(PatternMatcher.matchesAt(world, upright, 0, 64, 0, false, 100));
	}

	@Test
	void toleranceAllowsAFewChangedBlocks() {
		StructurePattern pattern = hut();
		PatternVariant upright = variant(pattern, BlockRotation.NONE);
		FakeWorld world = new FakeWorld();
		stamp(world, upright, 0, 64, 0);
		world.set(0, 64, 0, Blocks.COBBLESTONE.getDefaultState());

		// 1 of 12 cells differs, so 100% rejects and anything at or below ~91% accepts.
		assertFalse(PatternMatcher.matchesAt(world, upright, 0, 64, 0, true, 100));
		assertTrue(PatternMatcher.matchesAt(world, upright, 0, 64, 0, true, 90));
	}

	@Test
	void toleranceIsAPercentageOfTheComparedCells() {
		StructurePattern pattern = hut();
		PatternVariant upright = variant(pattern, BlockRotation.NONE);

		// 2x2x3 = 12 cells when air counts, 8 solid blocks when it does not.
		assertEquals(12, PatternMatcher.comparedCells(upright, true));
		assertEquals(8, PatternMatcher.comparedCells(upright, false));
		assertEquals(0, PatternMatcher.allowedMismatches(upright, true, 100));
		assertEquals(1, PatternMatcher.allowedMismatches(upright, true, 90));
	}

	@Test
	void aMissingChunkNeverCountsAsAMatch() {
		StructurePattern pattern = hut();
		PatternVariant upright = variant(pattern, BlockRotation.NONE);
		FakeWorld world = new FakeWorld();
		stamp(world, upright, 0, 64, 0);
		// The bottom layer of the copy falls in a chunk that was never generated.
		world.unknownBelow(65);

		assertFalse(PatternMatcher.matchesAt(world, upright, 0, 64, 0, true, 100));
	}

	@Test
	void scanningAGridFindsEveryCopy() {
		StructurePattern pattern = hut();
		List<PatternVariant> variants = pattern.buildVariants(true, false);
		FakeWorld world = new FakeWorld();

		// Ten copies, each in a different orientation, spread far apart.
		int copies = 10;

		for (int i = 0; i < copies; i++) {
			stamp(world, variants.get(i % variants.size()), i * 50, 64, i * 37);
		}

		int found = 0;

		for (int i = 0; i < copies; i++) {
			for (PatternVariant variant : variants) {
				if (PatternMatcher.matchesAt(world, variant, i * 50, 64, i * 37, true, 100)) {
					found++;
					break;
				}
			}
		}

		assertEquals(copies, found);
	}
}
