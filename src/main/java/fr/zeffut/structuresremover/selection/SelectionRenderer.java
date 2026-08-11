package fr.zeffut.structuresremover.selection;

import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockBox;

/**
 * Draws the current selection as a box of particles, for the owning player only.
 *
 * <p>Particles are sent as packets from the server, so this works for players on a plain vanilla
 * client — which is the whole point of not registering anything client-side.
 */
public final class SelectionRenderer {
	private static final int INTERVAL_TICKS = 10;

	/** Particles are spread out on large selections so a 200-block box does not flood the client. */
	private static final int MAX_PARTICLES_PER_EDGE = 48;

	/** Beyond this distance the outline is not drawn: the player cannot see it anyway. */
	private static final double MAX_VIEW_DISTANCE = 160.0;

	private static int tickCounter;

	private SelectionRenderer() {
	}

	public static void tick(MinecraftServer server) {
		if (++tickCounter % INTERVAL_TICKS != 0) {
			return;
		}

		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			PlayerSelection selection = SelectionManager.get(player.getUuid());

			if (!selection.isOutlineShown() || !selection.isComplete()) {
				continue;
			}

			if (!player.getEntityWorld().getRegistryKey().equals(selection.getWorld())) {
				continue;
			}

			BlockBox box = selection.toBox();

			if (box != null) {
				draw(player, box);
			}
		}
	}

	private static void draw(ServerPlayerEntity player, BlockBox box) {
		// Block coordinates name the corner of a block, so the far side needs the +1 to wrap the
		// whole block rather than cutting through its middle.
		double minX = box.getMinX();
		double minY = box.getMinY();
		double minZ = box.getMinZ();
		double maxX = box.getMaxX() + 1.0;
		double maxY = box.getMaxY() + 1.0;
		double maxZ = box.getMaxZ() + 1.0;

		double centreX = (minX + maxX) / 2.0;
		double centreY = (minY + maxY) / 2.0;
		double centreZ = (minZ + maxZ) / 2.0;

		if (player.squaredDistanceTo(centreX, centreY, centreZ)
				> MAX_VIEW_DISTANCE * MAX_VIEW_DISTANCE) {
			return;
		}

		for (double[] corner : new double[][] {
				{minY, minZ}, {minY, maxZ}, {maxY, minZ}, {maxY, maxZ}}) {
			edge(player, minX, corner[0], corner[1], maxX, corner[0], corner[1]);
		}

		for (double[] corner : new double[][] {
				{minX, minZ}, {minX, maxZ}, {maxX, minZ}, {maxX, maxZ}}) {
			edge(player, corner[0], minY, corner[1], corner[0], maxY, corner[1]);
		}

		for (double[] corner : new double[][] {
				{minX, minY}, {minX, maxY}, {maxX, minY}, {maxX, maxY}}) {
			edge(player, corner[0], corner[1], minZ, corner[0], corner[1], maxZ);
		}
	}

	private static void edge(ServerPlayerEntity player,
			double x1, double y1, double z1, double x2, double y2, double z2) {
		double length = Math.max(Math.abs(x2 - x1), Math.max(Math.abs(y2 - y1), Math.abs(z2 - z1)));
		int steps = Math.min(MAX_PARTICLES_PER_EDGE, Math.max(1, (int) length));
		ServerWorld world = (ServerWorld) player.getEntityWorld();

		for (int i = 0; i <= steps; i++) {
			double progress = (double) i / steps;
			world.spawnParticles(player, ParticleTypes.HAPPY_VILLAGER, true, false,
					x1 + (x2 - x1) * progress,
					y1 + (y2 - y1) * progress,
					z1 + (z2 - z1) * progress,
					1, 0.0, 0.0, 0.0, 0.0);
		}
	}
}
