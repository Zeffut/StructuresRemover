package fr.zeffut.structuresremover.scan;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.dimension.DimensionType;
import org.jetbrains.annotations.Nullable;

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
 * Tells which chunks of a world really exist, so a scan can visit them without generating anything.
 *
 * <p>Two sources are merged, and both are needed:
 * <ul>
 *   <li>the 4 KiB header of each {@code r.X.Z.mca} file, which lists the chunks written to disk;
 *   <li>the chunks the server currently holds in memory.
 * </ul>
 *
 * <p>The second source is not a nicety. A chunk that was just generated or just built in can sit in
 * memory for a long time before it reaches a region file — {@code saveAll} does not reliably flush
 * it — so a disk-only walk silently skips exactly the area the player is working in.
 *
 * <p>Asking the server for a chunk that exists in neither place would generate it, which is why
 * nothing outside this union is ever visited.
 */
public final class RegionIndex {
	private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
	private static final int HEADER_BYTES = 4096;
	private static final int CHUNKS_PER_REGION_SIDE = 32;

	/** Margin in chunks added around each player when guessing which regions hold loaded chunks. */
	private static final int VIEW_DISTANCE_MARGIN = 4;

	private final ServerWorld world;
	private final Path regionDirectory;
	private final Long2ObjectOpenHashMap<BitSet> headerCache = new Long2ObjectOpenHashMap<>();

	public RegionIndex(ServerWorld world) {
		this.world = world;
		Path worldRoot = world.getServer().getSavePath(WorldSavePath.ROOT);
		this.regionDirectory = DimensionType.getSaveDirectory(world.getRegistryKey(), worldRoot).resolve("region");
	}

	/**
	 * Every region worth walking: those with a file on disk, plus those covering chunks that are
	 * only in memory so far.
	 */
	public List<Long> listRegions() {
		LongOpenHashSet regions = new LongOpenHashSet();

		if (Files.isDirectory(this.regionDirectory)) {
			try (Stream<Path> files = Files.list(this.regionDirectory)) {
				files.forEach(file -> {
					Matcher matcher = REGION_FILE.matcher(file.getFileName().toString());

					if (matcher.matches()) {
						regions.add(ChunkPos.toLong(
								Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))));
					}
				});
			} catch (IOException exception) {
				// An unreadable region directory just means the in-memory chunks are all we have.
			}
		}

		this.addLoadedRegions(regions);

		List<Long> ordered = new ArrayList<>(regions.size());

		for (LongIterator iterator = regions.iterator(); iterator.hasNext(); ) {
			ordered.add(iterator.nextLong());
		}

		return ordered;
	}

	/**
	 * Loaded chunks cannot be enumerated directly, so the regions that may hold them are derived
	 * from where chunks get loaded: around players, and around force-loaded chunks.
	 */
	private void addLoadedRegions(LongOpenHashSet regions) {
		int reach = this.world.getServer().getPlayerManager().getViewDistance() + VIEW_DISTANCE_MARGIN;

		for (ServerPlayerEntity player : this.world.getPlayers()) {
			ChunkPos centre = player.getChunkPos();
			addRegionsCovering(regions, centre.x - reach, centre.z - reach, centre.x + reach, centre.z + reach);
		}

		for (LongIterator iterator = this.world.getChunkManager().getForcedChunks().iterator(); iterator.hasNext(); ) {
			long chunk = iterator.nextLong();
			int chunkX = ChunkPos.getPackedX(chunk);
			int chunkZ = ChunkPos.getPackedZ(chunk);
			addRegionsCovering(regions, chunkX, chunkZ, chunkX, chunkZ);
		}
	}

	private static void addRegionsCovering(LongOpenHashSet regions, int minChunkX, int minChunkZ,
			int maxChunkX, int maxChunkZ) {
		for (int regionX = minChunkX >> 5; regionX <= maxChunkX >> 5; regionX++) {
			for (int regionZ = minChunkZ >> 5; regionZ <= maxChunkZ >> 5; regionZ++) {
				regions.add(ChunkPos.toLong(regionX, regionZ));
			}
		}
	}

	/** Chunk positions inside one region that exist on disk or in memory, as packed {@link ChunkPos} longs. */
	public List<Long> chunksInRegion(long region) {
		int regionX = ChunkPos.getPackedX(region);
		int regionZ = ChunkPos.getPackedZ(region);
		BitSet onDisk = this.header(regionX, regionZ);
		List<Long> chunks = new ArrayList<>();

		for (int index = 0; index < CHUNKS_PER_REGION_SIDE * CHUNKS_PER_REGION_SIDE; index++) {
			int chunkX = (regionX << 5) + (index & 31);
			int chunkZ = (regionZ << 5) + (index >> 5);

			if ((onDisk != null && onDisk.get(index)) || this.world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) {
				chunks.add(ChunkPos.toLong(chunkX, chunkZ));
			}
		}

		return chunks;
	}

	/**
	 * Whether the chunk really exists, and can therefore be visited without generating anything.
	 */
	public boolean chunkExists(int chunkX, int chunkZ) {
		BitSet onDisk = this.header(chunkX >> 5, chunkZ >> 5);

		if (onDisk != null && onDisk.get((chunkX & 31) + ((chunkZ & 31) << 5))) {
			return true;
		}

		return this.world.getChunkManager().isChunkLoaded(chunkX, chunkZ);
	}

	@Nullable
	private BitSet header(int regionX, int regionZ) {
		long key = ChunkPos.toLong(regionX, regionZ);

		if (this.headerCache.containsKey(key)) {
			return this.headerCache.get(key);
		}

		BitSet present = readHeader(this.regionDirectory.resolve("r." + regionX + "." + regionZ + ".mca"));
		this.headerCache.put(key, present);
		return present;
	}

	@Nullable
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
