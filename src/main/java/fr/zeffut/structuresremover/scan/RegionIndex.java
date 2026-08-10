package fr.zeffut.structuresremover.scan;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.dimension.DimensionType;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Tells which chunks of a world have actually been generated, by reading the 4 KiB header of each
 * {@code r.X.Z.mca} region file.
 *
 * <p>This is what keeps a whole-world scan safe: asking the server for a chunk that was never
 * generated would generate it on the spot, and a scan would end up creating terrain instead of
 * only visiting it.
 */
public final class RegionIndex {
	private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
	private static final int HEADER_BYTES = 4096;

	private final ServerWorld world;
	private final Path regionDirectory;
	private final Long2ObjectOpenHashMap<BitSet> headerCache = new Long2ObjectOpenHashMap<>();

	public RegionIndex(ServerWorld world) {
		this.world = world;
		Path worldRoot = world.getServer().getSavePath(WorldSavePath.ROOT);
		this.regionDirectory = DimensionType.getSaveDirectory(world.getRegistryKey(), worldRoot).resolve("region");
	}

	/** Every region file present on disk, as packed region coordinates. */
	public List<Long> listRegions() {
		List<Long> regions = new ArrayList<>();

		if (!Files.isDirectory(this.regionDirectory)) {
			return regions;
		}

		try (Stream<Path> files = Files.list(this.regionDirectory)) {
			files.forEach(file -> {
				Matcher matcher = REGION_FILE.matcher(file.getFileName().toString());

				if (matcher.matches()) {
					int regionX = Integer.parseInt(matcher.group(1));
					int regionZ = Integer.parseInt(matcher.group(2));
					regions.add(ChunkPos.toLong(regionX, regionZ));
				}
			});
		} catch (IOException exception) {
			return regions;
		}

		return regions;
	}

	/** Chunk positions stored inside one region file, as packed {@link ChunkPos} longs. */
	public List<Long> chunksInRegion(long region) {
		int regionX = ChunkPos.getPackedX(region);
		int regionZ = ChunkPos.getPackedZ(region);
		BitSet present = this.header(regionX, regionZ);
		List<Long> chunks = new ArrayList<>();

		if (present == null) {
			return chunks;
		}

		for (int index = present.nextSetBit(0); index >= 0; index = present.nextSetBit(index + 1)) {
			int chunkX = (regionX << 5) + (index & 31);
			int chunkZ = (regionZ << 5) + (index >> 5);
			chunks.add(ChunkPos.toLong(chunkX, chunkZ));
		}

		return chunks;
	}

	/**
	 * Whether the chunk really exists, and can therefore be visited without generating anything.
	 *
	 * <p>A chunk that is loaded right now but has never been saved counts too — otherwise a
	 * structure the player has just built would be invisible to a scan until the next autosave.
	 */
	public boolean chunkExists(int chunkX, int chunkZ) {
		BitSet present = this.header(chunkX >> 5, chunkZ >> 5);

		if (present != null && present.get((chunkX & 31) + ((chunkZ & 31) << 5))) {
			return true;
		}

		return this.world.getChunkManager().isChunkLoaded(chunkX, chunkZ);
	}

	private BitSet header(int regionX, int regionZ) {
		long key = ChunkPos.toLong(regionX, regionZ);

		if (this.headerCache.containsKey(key)) {
			return this.headerCache.get(key);
		}

		BitSet present = readHeader(this.regionDirectory.resolve("r." + regionX + "." + regionZ + ".mca"));
		this.headerCache.put(key, present);
		return present;
	}

	private static BitSet readHeader(Path file) {
		if (!Files.isRegularFile(file)) {
			return null;
		}

		byte[] header = new byte[HEADER_BYTES];

		try (RandomAccessFile handle = new RandomAccessFile(file.toFile(), "r")) {
			if (handle.length() < HEADER_BYTES) {
				return null;
			}

			handle.readFully(header);
		} catch (IOException exception) {
			return null;
		}

		BitSet present = new BitSet(1024);

		for (int i = 0; i < 1024; i++) {
			int offset = i * 4;
			// A zeroed 4-byte entry means "no chunk stored here".
			boolean stored = header[offset] != 0 || header[offset + 1] != 0
					|| header[offset + 2] != 0 || header[offset + 3] != 0;

			if (stored) {
				present.set(i);
			}
		}

		return present;
	}
}
