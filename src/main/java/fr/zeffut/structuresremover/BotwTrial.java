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

		// Learn from the first few known structures, exactly as a player would with /sr add <name>.
		StructurePattern pattern = null;

		for (int i = 0; i < Math.min(examples, inArea.size()); i++) {
			BlockPos pos = inArea.get(i);
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
		StructuresRemover.LOGGER.info("[trial] anchor block: {} ({} orientations)",
				target.variants().get(0).anchorState().getBlock().getName().getString(), target.variants().size());

		RegionIndex index = new RegionIndex(world);
		ChunkSupplier supplier = new ChunkSupplier.Square(index,
				new ChunkPos(centreX >> 4, centreZ >> 4), radiusChunks);
		ScanJob job = new ScanJob(world, UUID.randomUUID(), List.of(target), options, false, supplier, index);

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
