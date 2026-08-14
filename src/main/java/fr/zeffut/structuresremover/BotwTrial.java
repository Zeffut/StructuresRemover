package fr.zeffut.structuresremover;

import fr.zeffut.structuresremover.pattern.SavedPattern;
import fr.zeffut.structuresremover.pattern.StructurePattern;
import fr.zeffut.structuresremover.scan.ChunkSupplier;
import fr.zeffut.structuresremover.scan.Match;
import fr.zeffut.structuresremover.scan.RegionIndex;
import fr.zeffut.structuresremover.scan.ScanJob;
import fr.zeffut.structuresremover.scan.ScanOptions;
import fr.zeffut.structuresremover.scan.Target;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Measures detection against a real map with a known answer.
 *
 * <p>Reads a file of structure positions (the ground truth), learns a pattern from a few of them,
 * scans an area, and reports how many of the known structures were found and how many hits landed
 * somewhere else. Enabled with {@code -Dstructuresremover.trial=<file>}.
 */
final class BotwTrial {
	/** A hit counts as one of the known structures if it lands within this many blocks of one. */
	private static final int HIT_RADIUS = 24;

	/**
	 * Fewest leftovers worth turning into a variant pattern.
	 *
	 * <p>Below three there is no way to tell the structure from the ground it stands in, so the
	 * pattern would delete its whole box. Measured on a shrine dug into a mountain, that took 11510
	 * blocks of hillside with it.
	 */
	private static final int MIN_EXAMPLES_FOR_VARIANT = 3;

	private BotwTrial() {
	}

	static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			String file = System.getProperty("structuresremover.trial", "");

			if (file.isEmpty()) {
				return;
			}

			try {
				run(server, Path.of(file));
			} catch (Exception exception) {
				StructuresRemover.LOGGER.error("[trial] blew up", exception);
			}

			server.stop(false);
		});
	}

	private static void run(MinecraftServer server, Path truthFile) throws Exception {
		ServerWorld world = server.getOverworld();
		List<BlockPos> truth = readPositions(truthFile);

		int centreX = Integer.getInteger("structuresremover.trial.x", 3521);
		int centreZ = Integer.getInteger("structuresremover.trial.z", 5922);
		int radiusChunks = Integer.getInteger("structuresremover.trial.radius", 50);
		int examples = Integer.getInteger("structuresremover.trial.examples", 4);
		int reach = Integer.getInteger("structuresremover.trial.box", 14);

		List<BlockPos> inArea = new ArrayList<>();

		for (BlockPos pos : truth) {
			if (Math.abs(pos.getX() - centreX) <= radiusChunks * 16
					&& Math.abs(pos.getZ() - centreZ) <= radiusChunks * 16) {
				inArea.add(pos);
			}
		}

		StructuresRemover.LOGGER.info("[trial] {} known structures overall, {} inside the scan area",
				truth.size(), inArea.size());

		ScanOptions options = new ScanOptions();
		options.rotations = true;
		options.chunksPerTick = 64;
		// The measurement wants every copy counted, including the ones used as examples.
		options.keepOriginal = false;
		options.tolerance = Integer.getInteger("structuresremover.trial.tolerance", 100);

		// Learn from a spread-out handful, exactly as a player would with /sr add <name>. Picking
		// neighbours would teach the pattern their shared ground instead of the structure.
		List<BlockPos> chosen = spreadOut(
				Boolean.getBoolean("structuresremover.trial.globalexamples") ? truth : inArea, examples);
		StructurePattern pattern = null;

		for (int i = 0; i < chosen.size(); i++) {
			BlockPos pos = chosen.get(i);
			BlockBox box = new BlockBox(
					pos.getX() - reach, pos.getY() - 4, pos.getZ() - reach,
					pos.getX() + reach, pos.getY() + 12, pos.getZ() + reach);
			StructurePattern example = StructurePattern.capture(world, box, true);

			pattern = pattern == null ? example : pattern.merge(example, options.rotations, options.mirrors);
			StructuresRemover.LOGGER.info(
					"[trial] example {} at {} -> pattern {}x{}x{}, {} blocks, {} required",
					i + 1, pos.toShortString(), pattern.getSizeX(), pattern.getSizeY(), pattern.getSizeZ(),
					pattern.getSolidCount(), pattern.getRequiredCount());
		}

		if (pattern == null) {
			StructuresRemover.LOGGER.error("[trial] no example available");
			return;
		}

		Target target = Target.of(new SavedPattern("shrine", world.getRegistryKey(), pattern),
				options.rotations, options.mirrors);
		StructuresRemover.LOGGER.info("[trial] required cells make up: {}", describeRequired(pattern));
		pattern.materialEvidence().entrySet().stream()
				.sorted((a, b) -> Double.compare(
						(double) b.getValue()[1] / b.getValue()[0], (double) a.getValue()[1] / a.getValue()[0]))
				.limit(28)
				.forEach(entry -> StructuresRemover.LOGGER.info("[trial]   material {} agreed {}/{} = {}",
						entry.getKey().getName().getString(), entry.getValue()[1], entry.getValue()[0],
						String.format("%.3f", (double) entry.getValue()[1] / entry.getValue()[0])));
		StructuresRemover.LOGGER.info("[trial] materials judged part of the structure: {}",
				pattern.structureMaterials().stream().map(b -> b.getName().getString()).sorted().toList());
		StructuresRemover.LOGGER.info("[trial] anchor block: {} ({} orientations)",
				target.variants().get(0).anchorState().getBlock().getName().getString(), target.variants().size());

		RegionIndex index = new RegionIndex(world);

		if ("probe".equals(System.getProperty("structuresremover.trial.mode", ""))) {
			probeEachTarget(world, index, List.of(target), options, truth);
			return;
		}

		if ("cover".equals(System.getProperty("structuresremover.trial.mode", ""))) {
			// Only the structures inside the scanned area: the rest may not even be on disk.
			coverVariants(world, index, target, options, inArea, reach, examples);
			return;
		}

		boolean remove = "remove".equals(System.getProperty("structuresremover.trial.mode", ""));
		ChunkSupplier supplier = new ChunkSupplier.Square(index,
				new ChunkPos(centreX >> 4, centreZ >> 4), radiusChunks);
		ScanJob job = new ScanJob(world, UUID.randomUUID(), List.of(target), options, remove, supplier, index);

		long start = System.nanoTime();
		int guard = 0;

		while (!job.tick() && guard++ < 5_000_000) {
			// drive the scan to completion
		}

		double seconds = (System.nanoTime() - start) / 1e9;
		List<Match> matches = job.matches();

		int hitKnown = 0;
		int elsewhere = 0;
		boolean[] seen = new boolean[inArea.size()];

		for (Match match : matches) {
			int best = -1;

			for (int i = 0; i < inArea.size(); i++) {
				if (inArea.get(i).getSquaredDistance(match.origin()) <= (double) HIT_RADIUS * HIT_RADIUS) {
					best = i;
					break;
				}
			}

			if (best >= 0) {
				if (!seen[best]) {
					seen[best] = true;
					hitKnown++;
				}
			} else {
				elsewhere++;
			}
		}

		StructuresRemover.LOGGER.info("[trial] ==========================================");
		StructuresRemover.LOGGER.info("[trial] scanned {} chunks in {}s", supplier.visited(), String.format("%.0f", seconds));
		StructuresRemover.LOGGER.info("[trial] known structures found: {}/{}", hitKnown, inArea.size());
		StructuresRemover.LOGGER.info("[trial] hits away from a known structure: {}", elsewhere);
		StructuresRemover.LOGGER.info("[trial] total matches: {}", matches.size());

		for (int i = 0; i < inArea.size(); i++) {
			if (!seen[i]) {
				StructuresRemover.LOGGER.info("[trial]   MISSED {}", inArea.get(i).toShortString());
			}
		}

		for (Match match : matches) {
			boolean known = false;

			for (BlockPos pos : inArea) {
				if (pos.getSquaredDistance(match.origin()) <= (double) HIT_RADIUS * HIT_RADIUS) {
					known = true;
					break;
				}
			}

			if (!known) {
				StructuresRemover.LOGGER.info("[trial]   EXTRA  {}", match.origin().toShortString());
			}
		}

		if (remove) {
			verifyRemoval(world, index, target, options, centreX, centreZ, radiusChunks);
		}
	}

	/**
	 * After a removal, scanning the same ground again must come back empty: whatever is left would
	 * be a copy the delete pass walked past.
	 */
	private static void verifyRemoval(ServerWorld world, RegionIndex index, Target target,
			ScanOptions options, int centreX, int centreZ, int radiusChunks) {
		ChunkSupplier supplier = new ChunkSupplier.Square(index,
				new ChunkPos(centreX >> 4, centreZ >> 4), radiusChunks);
		ScanJob check = new ScanJob(world, UUID.randomUUID(), List.of(target), options, false, supplier, index);
		int guard = 0;

		while (!check.tick() && guard++ < 5_000_000) {
			// re-scan the area now that the structures should be gone
		}

		StructuresRemover.LOGGER.info("[trial] AFTER REMOVAL: {} copies still detected (expected 0)",
				check.matches().size());

		for (Match leftover : check.matches()) {
			StructuresRemover.LOGGER.info("[trial]   LEFTOVER {}", leftover.origin().toShortString());
		}
	}

	/**
	 * Chases full coverage: whatever the first pattern misses is treated as a second kind of
	 * structure and learnt in its own right, which is what a player would do with a second name.
	 */
	private static void coverVariants(ServerWorld world, RegionIndex index, Target first,
			ScanOptions options, List<BlockPos> truth, int reach, int examples) throws Exception {
		List<Target> targets = new ArrayList<>();
		targets.add(first);
		List<BlockPos> missed = probeEachTarget(world, index, targets, options, truth);

		for (int round = 2; round <= 4 && missed.size() >= MIN_EXAMPLES_FOR_VARIANT; round++) {
			StructuresRemover.LOGGER.info("[trial] --- round {}: learning a variant from {} leftovers",
					round, missed.size());
			StructurePattern pattern = null;

			for (BlockPos pos : spreadOut(missed, Math.min(examples, missed.size()))) {
				BlockBox box = new BlockBox(
						pos.getX() - reach, pos.getY() - 4, pos.getZ() - reach,
						pos.getX() + reach, pos.getY() + 12, pos.getZ() + reach);
				StructurePattern example = StructurePattern.capture(world, box, true);
				pattern = pattern == null ? example : pattern.merge(example, options.rotations, options.mirrors);
			}

			if (pattern == null || pattern.getRequiredCount() == 0) {
				StructuresRemover.LOGGER.info("[trial] round {}: nothing learnable from the leftovers", round);
				break;
			}

			Target variant = Target.of(new SavedPattern("variant" + round, world.getRegistryKey(), pattern),
					options.rotations, options.mirrors);
			StructuresRemover.LOGGER.info("[trial] round {}: {} required cells, anchor {}", round,
					pattern.getRequiredCount(),
					variant.variants().get(0).anchorState().getBlock().getName().getString());
			targets.add(variant);

			List<BlockPos> stillMissed = probeEachTarget(world, index, targets, options, truth);

			if (stillMissed.size() >= missed.size()) {
				StructuresRemover.LOGGER.info("[trial] round {} did not help, stopping", round);
				break;
			}

			missed = stillMissed;
		}

		StructuresRemover.LOGGER.info("[trial] FINAL COVERAGE: {}/{} with {} pattern(s), {} left over",
				truth.size() - missed.size(), truth.size(), targets.size(), missed.size());

		for (BlockPos pos : missed) {
			StructuresRemover.LOGGER.info("[trial]   UNCOVERED {}", pos.toShortString());
		}

		int centreX = Integer.getInteger("structuresremover.trial.x", 3521);
		int centreZ = Integer.getInteger("structuresremover.trial.z", 5922);
		int areaRadius = Integer.getInteger("structuresremover.trial.arearadius", 55);
		boolean remove = Boolean.getBoolean("structuresremover.trial.removeafter");

		scanArea(world, index, targets, options, truth, centreX, centreZ, areaRadius, remove);
	}

	/** Runs the finished pattern set over an area and scores it against the known answer. */
	private static void scanArea(ServerWorld world, RegionIndex index, List<Target> targets,
			ScanOptions options, List<BlockPos> truth, int centreX, int centreZ, int radiusChunks,
			boolean remove) {
		List<BlockPos> inArea = new ArrayList<>();

		for (BlockPos pos : truth) {
			if (Math.abs(pos.getX() - centreX) <= radiusChunks * 16
					&& Math.abs(pos.getZ() - centreZ) <= radiusChunks * 16) {
				inArea.add(pos);
			}
		}

		ChunkSupplier supplier = new ChunkSupplier.Square(index,
				new ChunkPos(centreX >> 4, centreZ >> 4), radiusChunks);
		ScanJob job = new ScanJob(world, UUID.randomUUID(), targets, options, remove, supplier, index);
		int guard = 0;

		while (!job.tick() && guard++ < 5_000_000) {
			// drive the area pass to completion
		}

		int hit = 0;
		int elsewhere = 0;
		boolean[] seen = new boolean[inArea.size()];

		for (Match match : job.matches()) {
			int best = -1;

			for (int i = 0; i < inArea.size(); i++) {
				if (inArea.get(i).getSquaredDistance(match.origin()) <= (double) HIT_RADIUS * HIT_RADIUS) {
					best = i;
					break;
				}
			}

			if (best >= 0) {
				if (!seen[best]) {
					seen[best] = true;
					hit++;
				}
			} else {
				elsewhere++;
				StructuresRemover.LOGGER.info("[trial]   EXTRA {}", match.origin().toShortString());
			}
		}

		StructuresRemover.LOGGER.info("[trial] AREA ({} chunks): {}/{} known found, {} hits elsewhere, {} matches",
				supplier.visited(), hit, inArea.size(), elsewhere, job.matches().size());

		if (remove) {
			verifyRemoval(world, index, targets.get(0), options, centreX, centreZ, radiusChunks);
		}
	}

	/** Greedy farthest-point pick, so the examples come from as different a setting as possible. */
	private static List<BlockPos> spreadOut(List<BlockPos> all, int count) {
		List<BlockPos> chosen = new ArrayList<>();

		if (all.isEmpty()) {
			return chosen;
		}

		chosen.add(all.get(0));

		while (chosen.size() < Math.min(count, all.size())) {
			BlockPos best = null;
			double bestDistance = -1;

			for (BlockPos candidate : all) {
				if (chosen.contains(candidate)) {
					continue;
				}

				double nearest = Double.MAX_VALUE;

				for (BlockPos picked : chosen) {
					nearest = Math.min(nearest, picked.getSquaredDistance(candidate));
				}

				if (nearest > bestDistance) {
					bestDistance = nearest;
					best = candidate;
				}
			}

			if (best == null) {
				break;
			}

			chosen.add(best);
		}

		return chosen;
	}

	/**
	 * Runs the real scan in a small window around every known structure.
	 *
	 * <p>Measuring the detection rate does not need the whole map walked — only the places where an
	 * answer is already known. This keeps the loaded chunk count small enough that the server can
	 * still let go of them.
	 */
	private static List<BlockPos> probeEachTarget(ServerWorld world, RegionIndex index, List<Target> targets,
			ScanOptions options, List<BlockPos> truth) {
		int found = 0;
		int index0 = 0;
		long start = System.nanoTime();
		List<BlockPos> missed = new ArrayList<>();

		for (BlockPos pos : truth) {
			index0++;
			ChunkSupplier supplier = new ChunkSupplier.Square(index,
					new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4), 2);
			ScanJob job = new ScanJob(world, UUID.randomUUID(), targets, options, false, supplier, index);
			int guard = 0;

			while (!job.tick() && guard++ < 100_000) {
				// drive this small scan to completion
			}

			// A match anywhere in the window is not proof: it may be some other build nearby. Only a
			// match whose box actually reaches the known position counts.
			boolean onTarget = job.matches().stream().anyMatch(match -> {
				BlockPos origin = match.origin();
				return pos.getX() >= origin.getX() - 2
						&& pos.getX() <= origin.getX() + match.variant().sizeX() + 2
						&& pos.getZ() >= origin.getZ() - 2
						&& pos.getZ() <= origin.getZ() + match.variant().sizeZ() + 2
						&& pos.getY() >= origin.getY() - 4
						&& pos.getY() <= origin.getY() + match.variant().sizeY() + 4;
			});

			if (onTarget) {
				found++;
			} else {
				missed.add(pos);
			}

			if (index0 % 25 == 0) {
				StructuresRemover.LOGGER.info("[trial] probed {}/{}, found {}", index0, truth.size(), found);
			}
		}

		StructuresRemover.LOGGER.info("[trial] ==========================================");
		StructuresRemover.LOGGER.info("[trial] PROBE: {}/{} known structures detected in {}s",
				found, truth.size(), String.format("%.0f", (System.nanoTime() - start) / 1e9));

		for (BlockPos pos : missed) {
			StructuresRemover.LOGGER.info("[trial]   MISSED {}", pos.toShortString());
		}

		return missed;
	}

	/** Which blocks the learnt "must match" core is made of. */
	private static String describeRequired(StructurePattern pattern) {
		java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();

		for (int y = 0; y < pattern.getSizeY(); y++) {
			for (int z = 0; z < pattern.getSizeZ(); z++) {
				for (int x = 0; x < pattern.getSizeX(); x++) {
					if (pattern.isRequired(x, y, z) && !pattern.stateAt(x, y, z).isAir()) {
						counts.merge(pattern.stateAt(x, y, z).getBlock().getName().getString(), 1, Integer::sum);
					}
				}
			}
		}

		return counts.toString();
	}

	private static List<BlockPos> readPositions(Path file) throws Exception {
		List<BlockPos> positions = new ArrayList<>();

		for (String line : Files.readAllLines(file)) {
			String[] parts = line.trim().split("\\s+");

			if (parts.length == 3) {
				positions.add(new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
						Integer.parseInt(parts[2])));
			}
		}

		return positions;
	}
}
