package fr.zeffut.structuresremover.pattern;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An immutable snapshot of every block state inside the selection, in its original orientation.
 *
 * <p>Only block states are captured — block entity contents (chest inventories, sign text, ...)
 * are deliberately ignored, so two houses whose chests hold different loot still count as
 * identical copies.
 */
public final class StructurePattern {
	/** Refuse to build a pattern bigger than this, to keep memory and scan cost sane. */
	public static final int MAX_CELLS = 2_000_000;

	/**
	 * Blocks that make terrible anchors: they show up everywhere in a world, so using one would
	 * force a full pattern comparison on millions of positions.
	 */
	private static final Set<Block> COMMON_BLOCKS = Set.of(
			Blocks.STONE, Blocks.DEEPSLATE, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT,
			Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM, Blocks.SAND, Blocks.RED_SAND,
			Blocks.GRAVEL, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE, Blocks.TUFF,
			Blocks.CALCITE, Blocks.NETHERRACK, Blocks.END_STONE, Blocks.SANDSTONE,
			Blocks.WATER, Blocks.LAVA, Blocks.SNOW, Blocks.SNOW_BLOCK, Blocks.ICE,
			Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.SHORT_GRASS, Blocks.TALL_GRASS,
			Blocks.FERN, Blocks.LARGE_FERN, Blocks.DEAD_BUSH, Blocks.SEAGRASS, Blocks.TALL_SEAGRASS
	);

	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final BlockState[] states;
	private final int solidCount;
	private final BlockPos origin;

	private StructurePattern(int sizeX, int sizeY, int sizeZ, BlockState[] states, int solidCount, BlockPos origin) {
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.states = states;
		this.solidCount = solidCount;
		this.origin = origin;
	}

	/**
	 * Reads every block of {@code box} out of {@code world}.
	 *
	 * @param trim shrink the pattern to the blocks it actually contains, dropping empty margins.
	 *             This is what lets a selection be drawn roughly around a structure instead of
	 *             exactly on its corners.
	 * @throws PatternException if the selection is too large or holds nothing but air
	 */
	public static StructurePattern capture(World world, BlockBox box, boolean trim) throws PatternException {
		int sizeX = box.getBlockCountX();
		int sizeY = box.getBlockCountY();
		int sizeZ = box.getBlockCountZ();

		long cells = (long) sizeX * sizeY * sizeZ;

		if (cells > MAX_CELLS) {
			throw new PatternException("Selection is too large: " + cells + " blocks (limit " + MAX_CELLS + ").");
		}

		BlockState[] states = new BlockState[(int) cells];
		BlockPos.Mutable cursor = new BlockPos.Mutable();

		for (int y = 0; y < sizeY; y++) {
			for (int z = 0; z < sizeZ; z++) {
				for (int x = 0; x < sizeX; x++) {
					cursor.set(box.getMinX() + x, box.getMinY() + y, box.getMinZ() + z);
					states[index(x, y, z, sizeX, sizeZ)] = world.getBlockState(cursor);
				}
			}
		}

		return of(sizeX, sizeY, sizeZ, states,
				new BlockPos(box.getMinX(), box.getMinY(), box.getMinZ()), trim);
	}

	/**
	 * Builds a pattern straight from an array of states, in the same {@code (y, z, x)} order
	 * {@link #capture} uses. Exists so the transform maths can be exercised without a world.
	 */
	public static StructurePattern of(int sizeX, int sizeY, int sizeZ, BlockState[] states, BlockPos origin)
			throws PatternException {
		return of(sizeX, sizeY, sizeZ, states, origin, false);
	}

	/**
	 * @param trim shrink to the smallest box containing every non-air cell, moving the origin with
	 *             it. This is what lets a selection be drawn roughly around a structure.
	 * @throws PatternException if there is not a single non-air block
	 */
	public static StructurePattern of(int sizeX, int sizeY, int sizeZ, BlockState[] states, BlockPos origin,
			boolean trim) throws PatternException {
		int solidCount = 0;
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;

		for (int y = 0; y < sizeY; y++) {
			for (int z = 0; z < sizeZ; z++) {
				for (int x = 0; x < sizeX; x++) {
					if (states[index(x, y, z, sizeX, sizeZ)].isAir()) {
						continue;
					}

					solidCount++;
					minX = Math.min(minX, x);
					minY = Math.min(minY, y);
					minZ = Math.min(minZ, z);
					maxX = Math.max(maxX, x);
					maxY = Math.max(maxY, y);
					maxZ = Math.max(maxZ, z);
				}
			}
		}

		if (solidCount == 0) {
			throw new PatternException("Selection contains only air.");
		}

		int trimmedX = maxX - minX + 1;
		int trimmedY = maxY - minY + 1;
		int trimmedZ = maxZ - minZ + 1;

		if (!trim || (trimmedX == sizeX && trimmedY == sizeY && trimmedZ == sizeZ)) {
			return new StructurePattern(sizeX, sizeY, sizeZ, states.clone(), solidCount, origin);
		}

		BlockState[] trimmed = new BlockState[trimmedX * trimmedY * trimmedZ];

		for (int y = 0; y < trimmedY; y++) {
			for (int z = 0; z < trimmedZ; z++) {
				for (int x = 0; x < trimmedX; x++) {
					trimmed[index(x, y, z, trimmedX, trimmedZ)] =
							states[index(minX + x, minY + y, minZ + z, sizeX, sizeZ)];
				}
			}
		}

		return new StructurePattern(trimmedX, trimmedY, trimmedZ, trimmed, solidCount,
				origin.add(minX, minY, minZ));
	}

	public BlockState stateAt(int x, int y, int z) {
		return this.states[index(x, y, z, this.sizeX, this.sizeZ)];
	}

	private static int index(int x, int y, int z, int sizeX, int sizeZ) {
		return (y * sizeZ + z) * sizeX + x;
	}

	public int getSizeX() {
		return this.sizeX;
	}

	public int getSizeY() {
		return this.sizeY;
	}

	public int getSizeZ() {
		return this.sizeZ;
	}

	public int getSolidCount() {
		return this.solidCount;
	}

	public int getCellCount() {
		return this.states.length;
	}

	/** Corner the pattern was captured from — used to skip the original copy during removal. */
	public BlockPos getOrigin() {
		return this.origin;
	}

	/**
	 * Expands the pattern into every orientation the scan should look for.
	 *
	 * @param rotations whether the three rotated orientations are included
	 * @param mirrors   whether mirrored orientations are included
	 */
	public List<PatternVariant> buildVariants(boolean rotations, boolean mirrors) {
		List<BlockRotation> rotationList = rotations
				? List.of(BlockRotation.NONE, BlockRotation.CLOCKWISE_90, BlockRotation.CLOCKWISE_180, BlockRotation.COUNTERCLOCKWISE_90)
				: List.of(BlockRotation.NONE);
		List<BlockMirror> mirrorList = mirrors
				? List.of(BlockMirror.NONE, BlockMirror.LEFT_RIGHT)
				: List.of(BlockMirror.NONE);

		List<PatternVariant> variants = new ArrayList<>(rotationList.size() * mirrorList.size());

		for (BlockMirror mirror : mirrorList) {
			for (BlockRotation rotation : rotationList) {
				PatternVariant variant = this.transform(rotation, mirror);

				// A symmetric structure produces identical variants; testing them twice is wasted work.
				boolean duplicate = variants.stream().anyMatch(existing -> existing.hasSameContent(variant));

				if (!duplicate) {
					variants.add(variant);
				}
			}
		}

		return variants;
	}

	private PatternVariant transform(BlockRotation rotation, BlockMirror mirror) {
		boolean swapAxes = rotation == BlockRotation.CLOCKWISE_90 || rotation == BlockRotation.COUNTERCLOCKWISE_90;
		int newSizeX = swapAxes ? this.sizeZ : this.sizeX;
		int newSizeZ = swapAxes ? this.sizeX : this.sizeZ;
		BlockState[] transformed = new BlockState[this.states.length];

		for (int y = 0; y < this.sizeY; y++) {
			for (int z = 0; z < this.sizeZ; z++) {
				for (int x = 0; x < this.sizeX; x++) {
					BlockState state = this.states[index(x, y, z, this.sizeX, this.sizeZ)];

					// Mirror first, then rotate — same order as vanilla structure templates.
					int mx = mirror == BlockMirror.FRONT_BACK ? this.sizeX - 1 - x : x;
					int mz = mirror == BlockMirror.LEFT_RIGHT ? this.sizeZ - 1 - z : z;

					int nx;
					int nz;

					switch (rotation) {
						case CLOCKWISE_90 -> {
							nx = this.sizeZ - 1 - mz;
							nz = mx;
						}
						case CLOCKWISE_180 -> {
							nx = this.sizeX - 1 - mx;
							nz = this.sizeZ - 1 - mz;
						}
						case COUNTERCLOCKWISE_90 -> {
							nx = mz;
							nz = this.sizeX - 1 - mx;
						}
						default -> {
							nx = mx;
							nz = mz;
						}
					}

					transformed[index(nx, y, nz, newSizeX, newSizeZ)] = state.mirror(mirror).rotate(rotation);
				}
			}
		}

		return this.pickAnchor(transformed, newSizeX, this.sizeY, newSizeZ, rotation, mirror);
	}

	/**
	 * Chooses the cell the world scan keys on. The best anchor is a block that is rare inside the
	 * pattern <em>and</em> unlikely to occur in ordinary terrain, since every occurrence of it in
	 * the world costs one full pattern comparison.
	 */
	private PatternVariant pickAnchor(BlockState[] states, int sizeX, int sizeY, int sizeZ,
			BlockRotation rotation, BlockMirror mirror) {
		Map<BlockState, Integer> counts = new HashMap<>();

		for (BlockState state : states) {
			if (!state.isAir()) {
				counts.merge(state, 1, Integer::sum);
			}
		}

		BlockState best = null;
		long bestScore = Long.MAX_VALUE;

		for (Map.Entry<BlockState, Integer> entry : counts.entrySet()) {
			long score = entry.getValue();

			if (COMMON_BLOCKS.contains(entry.getKey().getBlock())) {
				score *= 10_000L;
			}

			if (score < bestScore) {
				bestScore = score;
				best = entry.getKey();
			}
		}

		int anchorX = 0;
		int anchorY = 0;
		int anchorZ = 0;

		outer:
		for (int y = 0; y < sizeY; y++) {
			for (int z = 0; z < sizeZ; z++) {
				for (int x = 0; x < sizeX; x++) {
					if (states[index(x, y, z, sizeX, sizeZ)] == best) {
						anchorX = x;
						anchorY = y;
						anchorZ = z;
						break outer;
					}
				}
			}
		}

		int solid = 0;

		for (BlockState state : states) {
			if (!state.isAir()) {
				solid++;
			}
		}

		return new PatternVariant(sizeX, sizeY, sizeZ, states, rotation, mirror,
				anchorX, anchorY, anchorZ, best, solid);
	}

	/** Thrown when a selection cannot be turned into a usable pattern. */
	public static final class PatternException extends Exception {
		public PatternException(String message) {
			super(message);
		}
	}
}
