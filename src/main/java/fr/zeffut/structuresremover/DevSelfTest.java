package fr.zeffut.structuresremover;

import fr.zeffut.structuresremover.pattern.SavedPattern;
import fr.zeffut.structuresremover.pattern.StructurePattern;
import fr.zeffut.structuresremover.scan.ChunkSupplier;
import fr.zeffut.structuresremover.scan.Match;
import fr.zeffut.structuresremover.scan.RegionIndex;
import fr.zeffut.structuresremover.scan.ScanJob;
import fr.zeffut.structuresremover.scan.ScanOptions;
import fr.zeffut.structuresremover.scan.Target;
import fr.zeffut.structuresremover.selection.PlayerSelection;
import fr.zeffut.structuresremover.selection.SelectionManager;
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

	/** A stand-in for a player, so the harness can exercise the per-player storage. */
	private static final UUID FAKE_PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

	private static void run(MinecraftServer server) throws Exception {
		ServerWorld world = server.getOverworld();
		int y = world.getBottomY() + 5;

		String persist = System.getProperty("structuresremover.persist", "");

		if (persist.equals("write")) {
			writePersistentData(world);
			return;
		}

		if (persist.equals("verify")) {
			verifyPersistentData(world);
			return;
		}

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

	/** Phase 1: fill a player's slot with data, then let the shutdown hook write it out. */
	private static void writePersistentData(ServerWorld world) throws Exception {
		int y = world.getBottomY() + 5;

		PlayerSelection selection = SelectionManager.get(FAKE_PLAYER);
		selection.setBox(world.getRegistryKey(), new BlockBox(1, y, 3, 4, y + 3, 6));
		selection.setWandEnabled(true);
		selection.setOutlineShown(false);

		ScanOptions options = SelectionManager.options(FAKE_PLAYER);
		options.rotations = true;
		options.tolerance = 77;
		options.maxMatches = 12;
		options.fill = Blocks.STONE.getDefaultState();

		stamp(world, new BlockPos(0, y, 0), Blocks.OAK_PLANKS);
		stamp(world, new BlockPos(0, y + 8, 0), Blocks.BRICKS);

		SelectionManager.patterns(FAKE_PLAYER).put("house", new SavedPattern("house", world.getRegistryKey(),
				StructurePattern.capture(world, BlockBox.create(new BlockPos(0, y, 0), new BlockPos(2, y + 2, 2)), true)));
		SelectionManager.patterns(FAKE_PLAYER).put("tower", new SavedPattern("tower", world.getRegistryKey(),
				StructurePattern.capture(world, BlockBox.create(new BlockPos(0, y + 8, 0), new BlockPos(2, y + 10, 2)), true)));

		SelectionManager.markDirty(FAKE_PLAYER);
		StructuresRemover.LOGGER.info("[selftest] persist/write: stored selection, 2 structures and options");
	}

	/** Phase 2: a fresh server start must have restored all of it before anything else runs. */
	private static void verifyPersistentData(ServerWorld world) {
		int y = world.getBottomY() + 5;
		PlayerSelection selection = SelectionManager.get(FAKE_PLAYER);
		ScanOptions options = SelectionManager.options(FAKE_PLAYER);
		var patterns = SelectionManager.patterns(FAKE_PLAYER);

		expect("selection restored", selection.isComplete());
		expect("selection box", selection.toBox() != null
				&& selection.toBox().getMinX() == 1 && selection.toBox().getMaxZ() == 6
				&& selection.toBox().getMinY() == y);
		expect("selection world", world.getRegistryKey().equals(selection.getWorld()));
		expect("wand flag", selection.isWandEnabled());
		expect("outline flag", !selection.isOutlineShown());

		expect("option rotations", options.rotations);
		expect("option tolerance", options.tolerance == 77);
		expect("option maxMatches", options.maxMatches == 12);
		expect("option fill", options.fill.isOf(Blocks.STONE));

		expect("two structures", patterns.size() == 2);
		expect("structure names", patterns.containsKey("house") && patterns.containsKey("tower"));

		SavedPattern house = patterns.get("house");

		if (house != null) {
			expect("house size", house.pattern().getSizeX() == 3 && house.pattern().getSizeY() == 3
					&& house.pattern().getSizeZ() == 3);
			expect("house solid count", house.pattern().getSolidCount() == 27);
			expect("house origin", house.pattern().getOrigin().equals(new BlockPos(0, y, 0)));
			expect("house blocks", house.pattern().stateAt(1, 1, 1).isOf(Blocks.OAK_PLANKS));
			expect("house world", world.getRegistryKey().equals(house.world()));
		}

		SavedPattern tower = patterns.get("tower");

		if (tower != null) {
			expect("tower blocks", tower.pattern().stateAt(0, 0, 0).isOf(Blocks.BRICKS));
		}
	}

	private static void expect(String what, boolean ok) {
		StructuresRemover.LOGGER.info("[selftest] persist/verify: {} {}", ok ? "OK" : "*** FAIL ***", what);
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
