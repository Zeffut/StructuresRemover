package fr.zeffut.structuresremover.scan;

import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

/**
 * Runs at most one world operation at a time for the whole server. Two concurrent whole-world
 * scans would fight over chunk loading and neither would finish sooner.
 */
public final class JobManager {
	@Nullable
	private static Job current;

	private JobManager() {
	}

	public static boolean isBusy() {
		return current != null;
	}

	@Nullable
	public static Job current() {
		return current;
	}

	public static void start(Job job) {
		current = job;
	}

	public static boolean cancel() {
		if (current == null) {
			return false;
		}

		current.cancel();
		return true;
	}

	public static void tick(MinecraftServer server) {
		if (current == null) {
			return;
		}

		if (current.tick()) {
			current = null;
		}
	}

	public static void reset() {
		current = null;
	}
}
