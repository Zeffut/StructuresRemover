package fr.zeffut.structuresremover.storage;

import fr.zeffut.structuresremover.StructuresRemover;
import fr.zeffut.structuresremover.pattern.SavedPattern;
import fr.zeffut.structuresremover.pattern.StructurePattern;
import fr.zeffut.structuresremover.scan.ScanOptions;
import fr.zeffut.structuresremover.selection.PlayerSelection;
import fr.zeffut.structuresremover.selection.SelectionManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Reads and writes each player's selection, options and saved structures under
 * {@code <world>/structuresremover/<uuid>.dat}.
 *
 * <p>The data sits inside the world save rather than next to the mod, because patterns record the
 * coordinates they were captured at: carrying them over to another world would be meaningless.
 *
 * <p>Block states are stored as a palette plus one index per cell, so a structure made of five
 * different blocks costs five block state tags and a packed int array rather than one tag per
 * block.
 */
public final class PlayerDataStorage {
	private static final String DIRECTORY = "structuresremover";
	private static final String EXTENSION = ".dat";

	/** Version tag, so a future format change can be detected rather than misread. */
	private static final int FORMAT = 1;

	private PlayerDataStorage() {
	}

	private static Path directory(MinecraftServer server) {
		return server.getSavePath(WorldSavePath.ROOT).resolve(DIRECTORY);
	}

	// ---------------------------------------------------------------- loading

	public static void loadAll(MinecraftServer server) {
		Path directory = directory(server);

		if (!Files.isDirectory(directory)) {
			return;
		}

		RegistryEntryLookup<Block> blocks = server.getRegistryManager().getOrThrow(RegistryKeys.BLOCK);
		int loaded = 0;

		try (Stream<Path> files = Files.list(directory)) {
			List<Path> paths = files.filter(path -> path.getFileName().toString().endsWith(EXTENSION)).toList();

			for (Path path : paths) {
				if (load(path, blocks)) {
					loaded++;
				}
			}
		} catch (IOException exception) {
			StructuresRemover.LOGGER.error("Could not list saved selections in {}", directory, exception);
		}

		if (loaded > 0) {
			StructuresRemover.LOGGER.info("Restored selections and structures for {} player(s).", loaded);
		}
	}

	private static boolean load(Path path, RegistryEntryLookup<Block> blocks) {
		String fileName = path.getFileName().toString();
		UUID player;

		try {
			player = UUID.fromString(fileName.substring(0, fileName.length() - EXTENSION.length()));
		} catch (IllegalArgumentException exception) {
			return false;
		}

		NbtCompound root;

		try {
			root = NbtIo.readCompressed(path, NbtSizeTracker.ofUnlimitedBytes());
		} catch (IOException exception) {
			StructuresRemover.LOGGER.error("Could not read {}", path, exception);
			return false;
		}

		if (root.getInt("format", 0) != FORMAT) {
			StructuresRemover.LOGGER.warn("Ignoring {}: unknown format version", path);
			return false;
		}

		readSelection(root.getCompoundOrEmpty("selection"), SelectionManager.get(player));
		readOptions(root.getCompoundOrEmpty("options"), SelectionManager.options(player), blocks);

		LinkedHashMap<String, SavedPattern> patterns = SelectionManager.patterns(player);
		patterns.clear();

		for (int i = 0; i < root.getListOrEmpty("patterns").size(); i++) {
			SavedPattern saved = readPattern(root.getListOrEmpty("patterns").getCompoundOrEmpty(i), blocks);

			if (saved != null) {
				patterns.put(saved.name(), saved);
			}
		}

		return true;
	}

	private static void readSelection(NbtCompound nbt, PlayerSelection selection) {
		selection.setWandEnabled(nbt.getBoolean("wand", false));
		selection.setOutlineShown(nbt.getBoolean("outline", true));

		RegistryKey<World> world = readWorldKey(nbt.getString("world", ""));
		int[] pos1 = nbt.getIntArray("pos1").orElse(null);
		int[] pos2 = nbt.getIntArray("pos2").orElse(null);

		if (world == null || pos1 == null || pos2 == null || pos1.length != 3 || pos2.length != 3) {
			return;
		}

		selection.setBox(world, new BlockBox(
				Math.min(pos1[0], pos2[0]), Math.min(pos1[1], pos2[1]), Math.min(pos1[2], pos2[2]),
				Math.max(pos1[0], pos2[0]), Math.max(pos1[1], pos2[1]), Math.max(pos1[2], pos2[2])));
	}

	private static void readOptions(NbtCompound nbt, ScanOptions options, RegistryEntryLookup<Block> blocks) {
		ScanOptions defaults = new ScanOptions();
		options.rotations = nbt.getBoolean("rotations", defaults.rotations);
		options.mirrors = nbt.getBoolean("mirrors", defaults.mirrors);
		options.matchAir = nbt.getBoolean("matchAir", defaults.matchAir);
		options.keepOriginal = nbt.getBoolean("keepOriginal", defaults.keepOriginal);
		options.removeEntities = nbt.getBoolean("removeEntities", defaults.removeEntities);
		options.tolerance = nbt.getInt("tolerance", defaults.tolerance);
		options.maxMatches = nbt.getInt("maxMatches", defaults.maxMatches);
		options.chunksPerTick = nbt.getInt("chunksPerTick", defaults.chunksPerTick);
		options.blocksPerTick = nbt.getInt("blocksPerTick", defaults.blocksPerTick);
		options.fill = nbt.getCompound("fill")
				.map(fill -> NbtHelper.toBlockState(blocks, fill))
				.orElse(Blocks.AIR.getDefaultState());
	}

	@Nullable
	private static SavedPattern readPattern(NbtCompound nbt, RegistryEntryLookup<Block> blocks) {
		String name = nbt.getString("name", "");
		RegistryKey<World> world = readWorldKey(nbt.getString("world", ""));
		int[] origin = nbt.getIntArray("origin").orElse(null);
		int[] size = nbt.getIntArray("size").orElse(null);
		int[] cells = nbt.getIntArray("cells").orElse(null);
		NbtList paletteNbt = nbt.getListOrEmpty("palette");

		if (name.isEmpty() || world == null || origin == null || size == null || cells == null
				|| origin.length != 3 || size.length != 3) {
			return null;
		}

		if ((long) size[0] * size[1] * size[2] != cells.length) {
			StructuresRemover.LOGGER.warn("Ignoring saved structure '{}': size does not match its cells", name);
			return null;
		}

		BlockState[] palette = new BlockState[paletteNbt.size()];

		for (int i = 0; i < palette.length; i++) {
			palette[i] = NbtHelper.toBlockState(blocks, paletteNbt.getCompoundOrEmpty(i));
		}

		BlockState[] states = new BlockState[cells.length];

		for (int i = 0; i < cells.length; i++) {
			int index = cells[i];

			if (index < 0 || index >= palette.length) {
				StructuresRemover.LOGGER.warn("Ignoring saved structure '{}': corrupt palette index", name);
				return null;
			}

			states[i] = palette[index];
		}

		int[] stored = nbt.getIntArray("agreement").orElse(null);
		int examples = nbt.getInt("examples", 1);
		int[] agreement = new int[states.length];

		for (int i = 0; i < agreement.length; i++) {
			agreement[i] = stored == null || i >= stored.length ? examples : stored[i];
		}

		try {
			StructurePattern pattern = StructurePattern.of(size[0], size[1], size[2], states, agreement,
					nbt.getInt("examples", 1), new BlockPos(origin[0], origin[1], origin[2]));
			return new SavedPattern(name, world, pattern);
		} catch (StructurePattern.PatternException exception) {
			StructuresRemover.LOGGER.warn("Ignoring saved structure '{}': {}", name, exception.getMessage());
			return null;
		}
	}

	@Nullable
	private static RegistryKey<World> readWorldKey(String value) {
		if (value.isEmpty()) {
			return null;
		}

		Identifier identifier = Identifier.tryParse(value);
		return identifier == null ? null : RegistryKey.of(RegistryKeys.WORLD, identifier);
	}

	// ---------------------------------------------------------------- saving

	/** Writes out every player whose data changed since the last flush. */
	public static void saveDirty(MinecraftServer server) {
		Set<UUID> dirty = SelectionManager.drainDirty();

		if (dirty.isEmpty()) {
			return;
		}

		Path directory = directory(server);

		try {
			Files.createDirectories(directory);
		} catch (IOException exception) {
			StructuresRemover.LOGGER.error("Could not create {}", directory, exception);
			return;
		}

		for (UUID player : dirty) {
			save(directory.resolve(player + EXTENSION), player);
		}
	}

	private static void save(Path path, UUID player) {
		NbtCompound root = new NbtCompound();
		root.putInt("format", FORMAT);
		root.put("selection", writeSelection(SelectionManager.get(player)));
		root.put("options", writeOptions(SelectionManager.options(player)));

		NbtList patterns = new NbtList();

		for (SavedPattern saved : SelectionManager.patterns(player).values()) {
			patterns.add(writePattern(saved));
		}

		root.put("patterns", patterns);

		try {
			NbtIo.writeCompressed(root, path);
		} catch (IOException exception) {
			StructuresRemover.LOGGER.error("Could not write {}", path, exception);
		}
	}

	private static NbtCompound writeSelection(PlayerSelection selection) {
		NbtCompound nbt = new NbtCompound();
		nbt.putBoolean("wand", selection.isWandEnabled());
		nbt.putBoolean("outline", selection.isOutlineShown());

		if (selection.isComplete()) {
			nbt.putString("world", selection.getWorld().getValue().toString());
			nbt.putIntArray("pos1", toArray(selection.getPos1()));
			nbt.putIntArray("pos2", toArray(selection.getPos2()));
		}

		return nbt;
	}

	private static NbtCompound writeOptions(ScanOptions options) {
		NbtCompound nbt = new NbtCompound();
		nbt.putBoolean("rotations", options.rotations);
		nbt.putBoolean("mirrors", options.mirrors);
		nbt.putBoolean("matchAir", options.matchAir);
		nbt.putBoolean("keepOriginal", options.keepOriginal);
		nbt.putBoolean("removeEntities", options.removeEntities);
		nbt.putInt("tolerance", options.tolerance);
		nbt.putInt("maxMatches", options.maxMatches);
		nbt.putInt("chunksPerTick", options.chunksPerTick);
		nbt.putInt("blocksPerTick", options.blocksPerTick);
		nbt.put("fill", NbtHelper.fromBlockState(options.fill));
		return nbt;
	}

	private static NbtCompound writePattern(SavedPattern saved) {
		StructurePattern pattern = saved.pattern();
		NbtCompound nbt = new NbtCompound();
		nbt.putString("name", saved.name());
		nbt.putString("world", saved.world().getValue().toString());
		nbt.putIntArray("origin", toArray(pattern.getOrigin()));
		nbt.putIntArray("size", new int[] {pattern.getSizeX(), pattern.getSizeY(), pattern.getSizeZ()});

		Map<BlockState, Integer> paletteIndices = new HashMap<>();
		List<BlockState> palette = new ArrayList<>();
		int[] cells = new int[pattern.getSizeX() * pattern.getSizeY() * pattern.getSizeZ()];
		int cursor = 0;

		for (int y = 0; y < pattern.getSizeY(); y++) {
			for (int z = 0; z < pattern.getSizeZ(); z++) {
				for (int x = 0; x < pattern.getSizeX(); x++) {
					BlockState state = pattern.stateAt(x, y, z);
					Integer index = paletteIndices.get(state);

					if (index == null) {
						index = palette.size();
						paletteIndices.put(state, index);
						palette.add(state);
					}

					cells[cursor++] = index;
				}
			}
		}

		NbtList paletteNbt = new NbtList();

		for (BlockState state : palette) {
			paletteNbt.add(NbtHelper.fromBlockState(state));
		}

		nbt.put("palette", paletteNbt);
		nbt.putIntArray("cells", cells);
		nbt.putIntArray("agreement", pattern.agreementCounts());
		nbt.putInt("examples", pattern.getExampleCount());
		return nbt;
	}

	private static int[] toArray(BlockPos pos) {
		return new int[] {pos.getX(), pos.getY(), pos.getZ()};
	}
}
