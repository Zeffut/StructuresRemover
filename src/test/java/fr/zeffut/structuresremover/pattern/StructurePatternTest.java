package fr.zeffut.structuresremover.pattern;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructurePatternTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.createGameVersion();
		Bootstrap.initialize();
	}

	private static StructurePattern pattern(int sizeX, int sizeY, int sizeZ, BlockState[] states) {
		return StructurePattern.of(sizeX, sizeY, sizeZ, states, BlockPos.ORIGIN);
	}

	private static BlockState[] filled(int cells, BlockState state) {
		BlockState[] states = new BlockState[cells];
		java.util.Arrays.fill(states, state);
		return states;
	}

	private static int index(int x, int y, int z, int sizeX, int sizeZ) {
		return (y * sizeZ + z) * sizeX + x;
	}

	@Test
	void rotatingSwapsHorizontalSizes() {
		BlockState[] states = filled(3 * 1 * 5, Blocks.AIR.getDefaultState());
		states[index(0, 0, 0, 3, 5)] = Blocks.GOLD_BLOCK.getDefaultState();

		List<PatternVariant> variants = pattern(3, 1, 5, states).buildVariants(true, false);
		PatternVariant rotated = variants.stream()
				.filter(variant -> variant.rotation() == BlockRotation.CLOCKWISE_90)
				.findFirst()
				.orElseThrow();

		assertEquals(5, rotated.sizeX());
		assertEquals(1, rotated.sizeY());
		assertEquals(3, rotated.sizeZ());
	}

	@Test
	void rotatingMovesTheCornerAndTheBlockState() {
		// A single north-facing furnace in the -X/-Z corner of a 2x1x3 box.
		BlockState furnace = Blocks.FURNACE.getDefaultState().with(HorizontalFacingBlock.FACING, Direction.NORTH);
		BlockState[] states = filled(2 * 1 * 3, Blocks.AIR.getDefaultState());
		states[index(0, 0, 0, 2, 3)] = furnace;

		PatternVariant rotated = pattern(2, 1, 3, states).buildVariants(true, false).stream()
				.filter(variant -> variant.rotation() == BlockRotation.CLOCKWISE_90)
				.findFirst()
				.orElseThrow();

		// Clockwise 90 maps (x, z) -> (sizeZ - 1 - z, x), so (0, 0) lands on (2, 0).
		BlockState moved = rotated.stateAt(2, 0, 0);
		assertTrue(moved.isOf(Blocks.FURNACE));
		assertEquals(Direction.EAST, moved.get(HorizontalFacingBlock.FACING));
		assertTrue(rotated.stateAt(0, 0, 0).isAir());
	}

	@Test
	void mirroringFlipsTheZAxis() {
		BlockState[] states = filled(1 * 1 * 3, Blocks.AIR.getDefaultState());
		states[index(0, 0, 0, 1, 3)] = Blocks.GOLD_BLOCK.getDefaultState();

		PatternVariant mirrored = pattern(1, 1, 3, states).buildVariants(false, true).stream()
				.filter(variant -> variant.mirror() == BlockMirror.LEFT_RIGHT)
				.findFirst()
				.orElseThrow();

		assertTrue(mirrored.stateAt(0, 0, 2).isOf(Blocks.GOLD_BLOCK));
		assertTrue(mirrored.stateAt(0, 0, 0).isAir());
	}

	@Test
	void symmetricPatternsCollapseToASingleVariant() {
		// A 1x1x1 stone cube looks the same in every orientation.
		List<PatternVariant> variants = pattern(1, 1, 1,
				new BlockState[] {Blocks.STONE.getDefaultState()}).buildVariants(true, true);

		assertEquals(1, variants.size());
	}

	@Test
	void anchorPrefersARareUncommonBlock() {
		// Mostly stone (very common) with one beacon: the beacon has to be the anchor, otherwise
		// the scan would test every stone block in the world.
		BlockState[] states = filled(4 * 1 * 4, Blocks.STONE.getDefaultState());
		states[index(2, 0, 1, 4, 4)] = Blocks.BEACON.getDefaultState();

		PatternVariant variant = pattern(4, 1, 4, states).buildVariants(false, false).get(0);

		assertNotNull(variant.anchorState());
		assertSame(Blocks.BEACON, variant.anchorState().getBlock());
		assertEquals(2, variant.anchorX());
		assertEquals(0, variant.anchorY());
		assertEquals(1, variant.anchorZ());
	}

	@Test
	void anchorIsPositionedSoTheOriginCanBeRecovered() {
		BlockState[] states = filled(3 * 2 * 3, Blocks.AIR.getDefaultState());
		states[index(1, 1, 2, 3, 3)] = Blocks.BEACON.getDefaultState();

		for (PatternVariant variant : pattern(3, 2, 3, states).buildVariants(true, true)) {
			Block anchorBlock = variant.anchorState().getBlock();
			assertSame(Blocks.BEACON, anchorBlock);
			// Whatever the orientation, the anchor cell must really hold the anchor state.
			assertSame(variant.anchorState(),
					variant.stateAt(variant.anchorX(), variant.anchorY(), variant.anchorZ()));
		}
	}

	@Test
	void solidCountIgnoresAir() {
		BlockState[] states = filled(2 * 2 * 2, Blocks.AIR.getDefaultState());
		states[0] = Blocks.STONE.getDefaultState();
		states[3] = Blocks.STONE.getDefaultState();

		assertEquals(2, pattern(2, 2, 2, states).getSolidCount());
		assertEquals(2, pattern(2, 2, 2, states).buildVariants(true, false).get(0).solidCount());
	}
}
