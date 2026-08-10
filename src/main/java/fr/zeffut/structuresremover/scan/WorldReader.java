package fr.zeffut.structuresremover.scan;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Reads block states without ever triggering world generation.
 *
 * <p>{@code World#getBlockState} happily generates a missing chunk on the spot. During a
 * whole-world scan a candidate copy can straddle a chunk that was never generated, so every read
 * goes through here: already-loaded chunks are used directly, chunks that exist on disk are loaded,
 * and anything else reports "no block" so the candidate is rejected.
 */
public final class WorldReader implements BlockSource {
	private final ServerWorld world;
	private final RegionIndex regionIndex;
	private final Long2ObjectOpenHashMap<Chunk> cache = new Long2ObjectOpenHashMap<>();
	private final BlockPos.Mutable cursor = new BlockPos.Mutable();

	public WorldReader(ServerWorld world, RegionIndex regionIndex) {
		this.world = world;
		this.regionIndex = regionIndex;
	}

	/**
	 * @return the block state at the position, or {@code null} when the chunk does not exist
	 */
	@Override
	@Nullable
	public BlockState getBlockState(int x, int y, int z) {
		if (y < this.world.getBottomY() || y >= this.world.getTopY()) {
			return null;
		}

		Chunk chunk = this.chunk(x >> 4, z >> 4);
		return chunk == null ? null : chunk.getBlockState(this.cursor.set(x, y, z));
	}

	@Nullable
	public Chunk chunk(int chunkX, int chunkZ) {
		long key = ChunkPos.toLong(chunkX, chunkZ);

		if (this.cache.containsKey(key)) {
			return this.cache.get(key);
		}

		Chunk chunk = this.world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);

		if (chunk == null && this.regionIndex.chunkExists(chunkX, chunkZ)) {
			chunk = this.world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, true);
		}

		this.cache.put(key, chunk);
		return chunk;
	}

	/** Drops cached chunk references so the server is free to unload them again. */
	public void releaseCache() {
		this.cache.clear();
	}

	public int cachedChunkCount() {
		return this.cache.size();
	}
}
