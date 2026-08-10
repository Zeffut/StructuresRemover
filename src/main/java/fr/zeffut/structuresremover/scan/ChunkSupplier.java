package fr.zeffut.structuresremover.scan;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.util.math.ChunkPos;

import java.util.List;

/**
 * Feeds a scan the chunks it should visit, one at a time, without ever materialising the whole
 * list of a large world in memory.
 */
public interface ChunkSupplier {
	long EXHAUSTED = Long.MIN_VALUE;

	/** @return the next packed chunk position, or {@link #EXHAUSTED} when there is nothing left */
	long next();

	/** How far through the work we are, between 0 and 1. */
	double progress();

	long visited();

	/**
	 * Walks every generated chunk of a dimension, region file by region file.
	 */
	final class WholeWorld implements ChunkSupplier {
		private final RegionIndex index;
		private final List<Long> regions;
		private final LongArrayList currentRegionChunks = new LongArrayList();

		private int regionCursor;
		private int chunkCursor;
		private long visited;

		public WholeWorld(RegionIndex index) {
			this.index = index;
			this.regions = index.listRegions();
		}

		public int regionCount() {
			return this.regions.size();
		}

		@Override
		public long next() {
			while (this.chunkCursor >= this.currentRegionChunks.size()) {
				if (this.regionCursor >= this.regions.size()) {
					return EXHAUSTED;
				}

				this.currentRegionChunks.clear();
				this.currentRegionChunks.addAll(this.index.chunksInRegion(this.regions.get(this.regionCursor)));
				this.regionCursor++;
				this.chunkCursor = 0;
			}

			this.visited++;
			return this.currentRegionChunks.getLong(this.chunkCursor++);
		}

		@Override
		public double progress() {
			if (this.regions.isEmpty()) {
				return 1.0;
			}

			double regionProgress = (double) Math.max(0, this.regionCursor - 1) / this.regions.size();
			double insideRegion = this.currentRegionChunks.isEmpty()
					? 0.0
					: (double) this.chunkCursor / this.currentRegionChunks.size() / this.regions.size();
			return Math.min(1.0, regionProgress + insideRegion);
		}

		@Override
		public long visited() {
			return this.visited;
		}
	}

	/**
	 * Walks a square of chunks around a centre, skipping anything that was never generated.
	 */
	final class Square implements ChunkSupplier {
		private final RegionIndex index;
		private final int minX;
		private final int minZ;
		private final int maxX;
		private final int maxZ;

		private int x;
		private int z;
		private long visited;
		private final long total;

		public Square(RegionIndex index, ChunkPos centre, int radius) {
			this.index = index;
			this.minX = centre.x - radius;
			this.minZ = centre.z - radius;
			this.maxX = centre.x + radius;
			this.maxZ = centre.z + radius;
			this.x = this.minX;
			this.z = this.minZ;
			this.total = (long) (radius * 2 + 1) * (radius * 2 + 1);
		}

		@Override
		public long next() {
			while (this.z <= this.maxZ) {
				int chunkX = this.x;
				int chunkZ = this.z;

				this.x++;

				if (this.x > this.maxX) {
					this.x = this.minX;
					this.z++;
				}

				this.visited++;

				if (this.index.chunkExists(chunkX, chunkZ)) {
					return ChunkPos.toLong(chunkX, chunkZ);
				}
			}

			return EXHAUSTED;
		}

		@Override
		public double progress() {
			return this.total == 0 ? 1.0 : Math.min(1.0, (double) this.visited / this.total);
		}

		@Override
		public long visited() {
			return this.visited;
		}
	}
}
