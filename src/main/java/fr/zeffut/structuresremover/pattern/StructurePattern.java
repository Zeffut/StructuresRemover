package fr.zeffut.structuresremover.pattern;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
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

	/** How many different rare blocks are used to guess where an example lines up. */
	private static final int RARE_PIVOTS = 4;

	/** Cap on the offsets one pivot block may suggest, so alignment stays quick. */
	private static final int MAX_PIVOT_PAIRS = 4_000;

	/**
	 * How many cells per example a block may occupy and still count as trim rather than ground.
	 * A shrine's glass runs to a couple of cells per copy; the hillside it stands on runs to
	 * hundreds.
	 */
	private static final int MAX_TRIM_CELLS_PER_EXAMPLE = 8;

	/**
	 * Fewest cells a learnt pattern may rely on.
	 *
	 * <p>Below roughly this many, a pattern stops describing a particular building and starts
	 * describing a handful of blocks that turn up all over a map. Measured on a real map, a pattern
	 * worn down to seven cells fired about once every 1500 chunks away from any real copy — which
	 * over a whole world is hundreds of wrong deletions.
	 */
	public static final int MIN_REQUIRED_CELLS = 16;

	/** Fixed-point scale for the rarity weighting used when lining two examples up. */
	private static final int WEIGHT_SCALE = 1_000_000;

	/**
	 * How many examples must agree on a cell before the scan will match on it.
	 *
	 * <p>All of them. Settling for a majority was tried and measured worse: cells that most copies
	 * share but one lacks then become mandatory, and every copy missing one is rejected outright.
	 * Unanimity is what makes a required cell something the scan can rely on; {@code tolerance} is
	 * the knob for letting copies differ.
	 */
	private static int requiredAgreement(int exampleCount) {
		return Math.max(1, exampleCount);
	}

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
	private final int[] agreement;
	private final Map<Block, Integer> typeExamples;
	private final int solidCount;
	private final int exampleCount;
	private final BlockPos origin;

	private StructurePattern(int sizeX, int sizeY, int sizeZ, BlockState[] states, int[] agreement,
			int solidCount, int exampleCount, BlockPos origin) {
		this(sizeX, sizeY, sizeZ, states, agreement, solidCount, exampleCount, origin, countTypes(states));
	}

	private StructurePattern(int sizeX, int sizeY, int sizeZ, BlockState[] states, int[] agreement,
			int solidCount, int exampleCount, BlockPos origin, Map<Block, Integer> typeExamples) {
		this.typeExamples = typeExamples;
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.states = states;
		this.agreement = agreement;
		this.solidCount = solidCount;
		this.exampleCount = exampleCount;
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
			return new StructurePattern(sizeX, sizeY, sizeZ, states.clone(), allRequired(states), solidCount, 1, origin);
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

		return new StructurePattern(trimmedX, trimmedY, trimmedZ, trimmed, allRequired(trimmed), solidCount, 1,
				origin.add(minX, minY, minZ));
	}

	/** Rebuilds a pattern, including its required mask, as saved by the storage layer. */
	public static StructurePattern of(int sizeX, int sizeY, int sizeZ, BlockState[] states, int[] agreement,
			int exampleCount, BlockPos origin) throws PatternException {
		int solid = 0;

		for (BlockState state : states) {
			if (!state.isAir()) {
				solid++;
			}
		}

		if (solid == 0) {
			throw new PatternException("Structure contains only air.");
		}

		return new StructurePattern(sizeX, sizeY, sizeZ, states.clone(), agreement.clone(), solid,
				Math.max(1, exampleCount), origin);
	}

	/**
	 * The block types that belong to the structure itself rather than the ground it sits in.
	 *
	 * <p>A type qualifies when most of the cells holding it are cells the examples agreed on. The
	 * ground fails that test by its own nature: there is a lot of it, and two copies cut into
	 * different hillsides only ever line up a small fraction of it, so its agreement stays low
	 * across a large number of cells. A wall of the structure behaves the opposite way.
	 *
	 * <p>Only meaningful once several examples have been merged; a single example agrees with
	 * itself everywhere, so everything in the box would qualify.
	 */
	public Set<Block> structureMaterials() {
		// Two examples cannot tell a material from the ground: every cell either agrees or is
		// unique to one of them, so the test below would pass everything.
		if (this.exampleCount < 3) {
			return Set.of();
		}

		int threshold = footprintAgreement(this.exampleCount);
		Map<Block, int[]> tally = new HashMap<>();

		for (int i = 0; i < this.states.length; i++) {
			if (this.states[i].isAir()) {
				continue;
			}

			int[] counts = tally.computeIfAbsent(this.states[i].getBlock(), block -> new int[2]);
			counts[0]++;

			if (this.agreement[i] >= threshold) {
				counts[1]++;
			}
		}

		Set<Block> materials = new HashSet<>();
		int seenInMost = Math.max(2, (this.exampleCount * 3 + 3) / 4);

		for (Map.Entry<Block, int[]> entry : tally.entrySet()) {
			int[] counts = entry.getValue();

			// Signal one: most of this block's cells are cells the examples agreed on. That is the
			// body of the structure — walls and floor, always in the same place.
			if (counts[1] * 2 >= counts[0]) {
				materials.add(entry.getKey());
				continue;
			}

			// Signal two: the block turns up in nearly every copy yet never in quantity, and never
			// twice in the same spot. That is the trim — glass, lamps, banners — which moves around
			// with the entrance. Ground fails this because there is always a great deal of it.
			int examples = this.typeExamples.getOrDefault(entry.getKey(), 0);

			if (examples >= seenInMost && counts[0] <= MAX_TRIM_CELLS_PER_EXAMPLE * this.exampleCount) {
				materials.add(entry.getKey());
			}
		}

		return materials;
	}

	/** Per block type: how many cells hold it, and how many of those the examples agreed on. */
	public Map<Block, int[]> materialEvidence() {
		int threshold = footprintAgreement(this.exampleCount);
		Map<Block, int[]> tally = new HashMap<>();

		for (int i = 0; i < this.states.length; i++) {
			if (this.states[i].isAir()) {
				continue;
			}

			int[] counts = tally.computeIfAbsent(this.states[i].getBlock(), block -> new int[2]);
			counts[0]++;

			if (this.agreement[i] >= threshold) {
				counts[1]++;
			}
		}

		return tally;
	}

	/** Per-cell agreement counts, for the storage layer. */
	public int[] agreementCounts() {
		return this.agreement.clone();
	}

	/** Every block type a single example holds was, trivially, seen in one example. */
	private static Map<Block, Integer> countTypes(BlockState[] states) {
		Map<Block, Integer> seen = new HashMap<>();

		for (BlockState state : states) {
			if (!state.isAir()) {
				seen.put(state.getBlock(), 1);
			}
		}

		return seen;
	}

	/** A pattern taken from a single example demands every one of its cells. */
	private static int[] allRequired(BlockState[] states) {
		int[] agreement = new int[states.length];
		Arrays.fill(agreement, 1);
		return agreement;
	}

	public BlockState stateAt(int x, int y, int z) {
		return this.states[index(x, y, z, this.sizeX, this.sizeZ)];
	}

	/**
	 * Whether a cell has to match for a copy to count. Cells the examples disagreed on are not
	 * required: they are still cleared on removal, but they never reject a candidate.
	 */
	public boolean isRequired(int x, int y, int z) {
		// Air can be required too: when every example is empty here, that emptiness is part of the
		// structure and matchAir is what decides whether to hold the world to it.
		return this.agreement[index(x, y, z, this.sizeX, this.sizeZ)] >= requiredAgreement(this.exampleCount);
	}

	/**
	 * Whether a cell is part of what gets deleted. A cell only has to show up in most of the
	 * examples: that keeps the parts of the structure that vary, while the ground it was cut into —
	 * different under every copy — falls away.
	 */
	public boolean isInFootprint(int x, int y, int z) {
		int i = index(x, y, z, this.sizeX, this.sizeZ);
		return !this.states[i].isAir() && this.agreement[i] >= footprintAgreement(this.exampleCount);
	}

	/**
	 * Removal reaches a little wider than matching, to catch the parts that vary between copies.
	 *
	 * <p>From two examples on, a cell needs at least two of them behind it. Accepting a cell that
	 * only one example had would delete that example's hillside at every copy: measured, it turned
	 * 0.1% of touched ground into 12.6%.
	 */
	private static int footprintAgreement(int exampleCount) {
		if (exampleCount <= 1) {
			return 1;
		}

		return Math.max(2, (requiredAgreement(exampleCount) * 2) / 3);
	}

	/** How many examples were merged into this pattern. */
	public int getExampleCount() {
		return this.exampleCount;
	}

	/** Cells that must match; the rest of the footprint is only used when removing. */
	public int getRequiredCount() {
		int count = 0;

		int threshold = requiredAgreement(this.exampleCount);

		for (int i = 0; i < this.agreement.length; i++) {
			if (this.agreement[i] >= threshold && !this.states[i].isAir()) {
				count++;
			}
		}

		return count;
	}

	/** How many cells removal would clear. */
	public int getFootprintCount() {
		int count = 0;

		int threshold = footprintAgreement(this.exampleCount);

		for (int i = 0; i < this.agreement.length; i++) {
			if (!this.states[i].isAir() && this.agreement[i] >= threshold) {
				count++;
			}
		}

		return count;
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
	 * Folds another example of the same structure into this one.
	 *
	 * <p>Real maps rarely repeat a build byte for byte: it gets cut into different ground, decorated
	 * differently, or turned. Merging keeps the cells every example agrees on as the thing to match,
	 * and keeps the union of all their blocks as the thing to delete. Two or three examples are
	 * usually enough to separate the structure from the noise around it.
	 *
	 * <p>The other example is aligned automatically — the caller only has to select roughly the same
	 * structure, not the same corner.
	 */
	public StructurePattern merge(StructurePattern other, boolean rotations, boolean mirrors) {
		Alignment best = null;

		for (PatternVariant candidate : other.buildVariants(rotations, mirrors)) {
			Alignment alignment = this.bestAlignment(candidate);

			if (alignment != null && (best == null || alignment.score > best.score)) {
				best = alignment;
			}
		}

		int previousRequired = this.getRequiredCount();

		// An example that lines up with nothing distinctive would wipe out the agreement built so
		// far and leave a pattern vague enough to match anything, so it is refused instead.
		if (best == null || best.score <= 0) {
			return this;
		}

		int minX = Math.min(0, best.offsetX);
		int minY = Math.min(0, best.offsetY);
		int minZ = Math.min(0, best.offsetZ);
		int maxX = Math.max(this.sizeX, best.offsetX + best.variant.sizeX());
		int maxY = Math.max(this.sizeY, best.offsetY + best.variant.sizeY());
		int maxZ = Math.max(this.sizeZ, best.offsetZ + best.variant.sizeZ());

		int sizeX = maxX - minX;
		int sizeY = maxY - minY;
		int sizeZ = maxZ - minZ;

		BlockState air = Blocks.AIR.getDefaultState();
		BlockState[] states = new BlockState[sizeX * sizeY * sizeZ];
		int[] agreement = new int[states.length];
		Arrays.fill(states, air);

		for (int y = 0; y < sizeY; y++) {
			for (int z = 0; z < sizeZ; z++) {
				for (int x = 0; x < sizeX; x++) {
					int mineX = x + minX;
					int mineY = y + minY;
					int mineZ = z + minZ;
					boolean insideMine = mineX >= 0 && mineY >= 0 && mineZ >= 0
							&& mineX < this.sizeX && mineY < this.sizeY && mineZ < this.sizeZ;

					int theirX = mineX - best.offsetX;
					int theirY = mineY - best.offsetY;
					int theirZ = mineZ - best.offsetZ;
					boolean insideTheirs = theirX >= 0 && theirY >= 0 && theirZ >= 0
							&& theirX < best.variant.sizeX() && theirY < best.variant.sizeY()
							&& theirZ < best.variant.sizeZ();

					BlockState mine = insideMine ? this.stateAt(mineX, mineY, mineZ) : air;
					int mineAgreement = insideMine
							? this.agreement[index(mineX, mineY, mineZ, this.sizeX, this.sizeZ)] : 0;
					BlockState theirs = insideTheirs ? best.variant.stateAt(theirX, theirY, theirZ) : air;
					int theirAgreement = insideTheirs
							? best.variant.agreementAt(theirX, theirY, theirZ) : 0;

					int target = index(x, y, z, sizeX, sizeZ);

					if (!mine.isAir() && mine == theirs) {
						// Both examples put the same block here: that is what makes a cell reliable.
						states[target] = mine;
						agreement[target] = mineAgreement + theirAgreement;
					} else if (!mine.isAir()) {
						states[target] = mine;
						agreement[target] = mineAgreement;
					} else {
						states[target] = theirs;
						agreement[target] = theirAgreement;
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

		Map<Block, Integer> mergedTypes = new HashMap<>(this.typeExamples);

		for (Map.Entry<Block, Integer> entry : other.typeExamples.entrySet()) {
			mergedTypes.merge(entry.getKey(), entry.getValue(), Integer::sum);
		}

		StructurePattern merged = new StructurePattern(sizeX, sizeY, sizeZ, states, agreement, solid,
				this.exampleCount + other.exampleCount, this.origin.add(minX, minY, minZ), mergedTypes);

		// A copy that is too different does not belong in this pattern: folding it in would eat the
		// core down to something that matches half the map. It is a second kind of structure, and
		// wants a name of its own.
		if (merged.getRequiredCount() < MIN_REQUIRED_CELLS && previousRequired >= MIN_REQUIRED_CELLS) {
			return this;
		}

		return merged;
	}

	/**
	 * Finds where an oriented example sits relative to this pattern.
	 *
	 * <p>Translations are not searched blindly: the rarest block the two have in common pins them
	 * down, so only the handful of offsets that line up one of those blocks is ever scored.
	 */
	private Alignment bestAlignment(PatternVariant candidate) {
		Alignment best = null;
		LongOpenHashSet tried = new LongOpenHashSet();

		// One rare block can be a coincidence, so several of the rarest are used as pivots and the
		// offsets they suggest are all scored.
		for (BlockState key : this.rarestSharedStates(candidate, RARE_PIVOTS)) {
			List<int[]> mine = this.cellsOf(key);
			List<int[]> theirs = new ArrayList<>();

			for (int y = 0; y < candidate.sizeY(); y++) {
				for (int z = 0; z < candidate.sizeZ(); z++) {
					for (int x = 0; x < candidate.sizeX(); x++) {
						if (candidate.stateAt(x, y, z) == key) {
							theirs.add(new int[] {x, y, z});
						}
					}
				}
			}

			for (int[] a : mine) {
				for (int[] b : theirs) {
					int offsetX = a[0] - b[0];
					int offsetY = a[1] - b[1];
					int offsetZ = a[2] - b[2];

					if (!tried.add(BlockPos.asLong(offsetX, offsetY, offsetZ))) {
						continue;
					}

					int score = this.score(candidate, offsetX, offsetY, offsetZ);

					if (best == null || score > best.score) {
						best = new Alignment(candidate, offsetX, offsetY, offsetZ, score);
					}
				}
			}
		}

		return best;
	}

	/**
	 * How well an example fits at a given offset, counted on the cells the pattern already trusts.
	 * Scoring on the whole footprint would let a big pile of matching terrain outvote the structure.
	 */
	private int score(PatternVariant candidate, int offsetX, int offsetY, int offsetZ) {
		Map<BlockState, Integer> counts = this.countStates();
		long agreed = 0;

		for (int y = 0; y < candidate.sizeY(); y++) {
			for (int z = 0; z < candidate.sizeZ(); z++) {
				for (int x = 0; x < candidate.sizeX(); x++) {
					BlockState theirs = candidate.stateAt(x, y, z);

					if (theirs.isAir()) {
						continue;
					}

					int mineX = x + offsetX;
					int mineY = y + offsetY;
					int mineZ = z + offsetZ;

					if (mineX < 0 || mineY < 0 || mineZ < 0
							|| mineX >= this.sizeX || mineY >= this.sizeY || mineZ >= this.sizeZ) {
						continue;
					}

					if (this.stateAt(mineX, mineY, mineZ) == theirs) {
						// Rare blocks carry the signal. Without this weighting a beach of matching
						// sand outvotes the structure and the example lands in the wrong place.
						agreed += WEIGHT_SCALE / counts.getOrDefault(theirs, 1);
					}
				}
			}
		}

		return (int) Math.min(Integer.MAX_VALUE, agreed);
	}

	/** The least frequent block states the two patterns share, rarest first. */
	private List<BlockState> rarestSharedStates(PatternVariant candidate, int limit) {
		Map<BlockState, Integer> theirCounts = new HashMap<>();

		for (BlockState state : candidate.states()) {
			if (!state.isAir()) {
				theirCounts.merge(state, 1, Integer::sum);
			}
		}

		List<Map.Entry<BlockState, Long>> shared = new ArrayList<>();

		for (Map.Entry<BlockState, Integer> entry : this.countStates().entrySet()) {
			Integer theirs = theirCounts.get(entry.getKey());

			if (theirs != null) {
				// The product of the two counts is what the offset search will cost.
				shared.add(Map.entry(entry.getKey(), (long) entry.getValue() * theirs));
			}
		}

		shared.sort(Map.Entry.comparingByValue());
		List<BlockState> out = new ArrayList<>();

		for (Map.Entry<BlockState, Long> entry : shared) {
			if (out.size() >= limit || entry.getValue() > MAX_PIVOT_PAIRS) {
				break;
			}

			out.add(entry.getKey());
		}

		return out;
	}

	private Map<BlockState, Integer> countStates() {
		Map<BlockState, Integer> counts = new HashMap<>();

		for (BlockState state : this.states) {
			if (!state.isAir()) {
				counts.merge(state, 1, Integer::sum);
			}
		}

		return counts;
	}

	private List<int[]> cellsOf(BlockState state) {
		List<int[]> cells = new ArrayList<>();

		for (int y = 0; y < this.sizeY; y++) {
			for (int z = 0; z < this.sizeZ; z++) {
				for (int x = 0; x < this.sizeX; x++) {
					if (this.stateAt(x, y, z) == state) {
						cells.add(new int[] {x, y, z});
					}
				}
			}
		}

		return cells;
	}

	private record Alignment(PatternVariant variant, int offsetX, int offsetY, int offsetZ, int score) {
		Alignment {
		}
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
		int[] transformedAgreement = new int[this.states.length];

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

					int target = index(nx, y, nz, newSizeX, newSizeZ);
					transformed[target] = state.mirror(mirror).rotate(rotation);
					transformedAgreement[target] = this.agreement[index(x, y, z, this.sizeX, this.sizeZ)];
				}
			}
		}

		return this.pickAnchor(transformed, transformedAgreement, newSizeX, this.sizeY, newSizeZ, rotation, mirror);
	}

	/**
	 * Chooses the cell the world scan keys on. The best anchor is a block that is rare inside the
	 * pattern <em>and</em> unlikely to occur in ordinary terrain, since every occurrence of it in
	 * the world costs one full pattern comparison.
	 */
	private PatternVariant pickAnchor(BlockState[] states, int[] agreement, int sizeX, int sizeY, int sizeZ,
			BlockRotation rotation, BlockMirror mirror) {
		Map<BlockState, Integer> counts = new HashMap<>();

		// Only a required cell can anchor the scan: an optional one may simply not be there.
		int threshold = requiredAgreement(this.exampleCount);

		for (int i = 0; i < states.length; i++) {
			if (agreement[i] >= threshold && !states[i].isAir()) {
				counts.merge(states[i], 1, Integer::sum);
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
					int i = index(x, y, z, sizeX, sizeZ);

					if (agreement[i] >= threshold && states[i] == best) {
						anchorX = x;
						anchorY = y;
						anchorZ = z;
						break outer;
					}
				}
			}
		}

		int solid = 0;
		int requiredSolid = 0;

		for (int i = 0; i < states.length; i++) {
			if (!states[i].isAir()) {
				solid++;

				if (agreement[i] >= threshold) {
					requiredSolid++;
				}
			}
		}

		return new PatternVariant(sizeX, sizeY, sizeZ, states, agreement, threshold, rotation, mirror,
				anchorX, anchorY, anchorZ, best, solid, requiredSolid);
	}

	/** Thrown when a selection cannot be turned into a usable pattern. */
	public static final class PatternException extends Exception {
		public PatternException(String message) {
			super(message);
		}
	}
}
