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
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.List;
import java.util.UUID;

/**
 * Dev-only harness: stamps structures into a freshly started server and runs the real scan over
 * them, covering the world-facing paths the unit tests cannot reach.
 *
 * <p>Enabled with {@code -Dstructuresremover.selftest=true}; does nothing otherwise.
 */
final class DevSelfTest {
	private DevSelfTest() {
	}

	static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (!Boolean.getBoolean("structuresremover.selftest")) {
				return;
			}

			try {
				run(server);
			} catch (Exception exception) {
				StructuresRemover.LOGGER.error("[selftest] blew up", exception);
			}

			server.stop(false);
		});
	}

	private static void run(MinecraftServer server) throws Exception {
		ServerWorld world = server.getOverworld();
		int y = world.getBottomY() + 5;

		// Each scenario uses a different block so the structures of one are not copies of another.

		// 1. plain copies, well inside single chunks
		check(world, "nearby", new BlockPos(0, y, 0),
				List.of(new BlockPos(32, y, 0), new BlockPos(0, y, 32)), Blocks.OAK_PLANKS, false);

		// 2. structure straddling a chunk boundary (x = 14..16 spans chunk 0 and chunk 1)
		check(world, "crossChunk", new BlockPos(14, y + 8, 0),
				List.of(new BlockPos(46, y + 8, 0)), Blocks.BRICKS, false);

		// 3. a structure built only from blocks the anchor heuristic considers common
		check(world, "commonBlocks", new BlockPos(0, y + 16, 0),
				List.of(new BlockPos(32, y + 16, 0)), Blocks.STONE, false);

		// 4. copies that are only reachable through the whole-world walk
		check(world, "wholeWorld", new BlockPos(0, y + 24, 0),
				List.of(new BlockPos(32, y + 24, 0), new BlockPos(0, y + 24, 32)), Blocks.GOLD_BLOCK, true);

		// 5. a structure that exists nowhere else: must report zero without claiming a match
		check(world, "noCopies", new BlockPos(0, y + 32, 0), List.of(), Blocks.DIAMOND_BLOCK, false);

		diagnoseFlush(world);
	}

	/**
	 * How many chunks the region files expose right after a save, compared with how many the server
	 * actually has loaded.
	 */
	private static void diagnoseFlush(ServerWorld world) throws Exception {
		int loaded = world.getChunkManager().getLoadedChunkCount();
		StructuresRemover.LOGGER.info("[selftest] flush: {} chunks loaded in memory", loaded);

		world.getServer().saveAll(true, true, true);
		StructuresRemover.LOGGER.info("[selftest] flush: right after saveAll -> {} chunks on disk",
				countOnDisk(world));

		for (int attempt = 1; attempt <= 3; attempt++) {
			Thread.sleep(1000L);
			StructuresRemover.LOGGER.info("[selftest] flush: {}s later -> {} chunks on disk",
					attempt, countOnDisk(world));
		}
	}

	private static int countOnDisk(ServerWorld world) {
		RegionIndex index = new RegionIndex(world);
		int total = 0;

		for (long region : index.listRegions()) {
			total += index.chunksInRegion(region).size();
		}

		return total;
	}

	private static void check(ServerWorld world, String label, BlockPos origin, List<BlockPos> copies,
			Block body, boolean wholeWorld) throws Exception {
		stamp(world, origin, body);
		copies.forEach(copy -> stamp(world, copy, body));

		StructurePattern pattern = StructurePattern.capture(world, BlockBox.create(origin, origin.add(2, 2, 2)), true);
		Target target = Target.of(new SavedPattern(label, world.getRegistryKey(), pattern), false, false);

		RegionIndex index = new RegionIndex(world);
		ChunkSupplier supplier = wholeWorld
				? new ChunkSupplier.WholeWorld(index)
				: new ChunkSupplier.Square(index, new ChunkPos(0, 0), 4);

		if (wholeWorld) {
			StructuresRemover.LOGGER.info("[selftest] {}: {} region(s) to walk", label,
					((ChunkSupplier.WholeWorld) supplier).regionCount());
		}

		ScanJob job = new ScanJob(world, UUID.randomUUID(), List.of(target), new ScanOptions(),
				false, supplier, index);

		int guard = 0;

		while (!job.tick() && guard++ < 1_000_000) {
			// drive the job to completion synchronously
		}

		String verdict = job.matches().size() == copies.size() ? "OK" : "*** FAIL ***";
		StructuresRemover.LOGGER.info("[selftest] {}: {} found {}, expected {} (anchor {}, {} chunks visited)",
				label, verdict, job.matches().size(), copies.size(),
				target.variants().get(0).anchorState().getBlock(), supplier.visited());

		for (Match match : job.matches()) {
			StructuresRemover.LOGGER.info("[selftest]   {} -> {}", label, match.origin());
		}
	}

	/** A 3x3x3 shell with a beacon in the middle, unless the body block is meant to be the whole thing. */
	private static void stamp(ServerWorld world, BlockPos origin, Block body) {
		BlockState bodyState = body.getDefaultState();

		for (int y = 0; y < 3; y++) {
			for (int z = 0; z < 3; z++) {
				for (int x = 0; x < 3; x++) {
					world.setBlockState(origin.add(x, y, z), bodyState,
							Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
				}
			}
		}
	}
}
