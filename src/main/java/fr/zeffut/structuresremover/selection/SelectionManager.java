package fr.zeffut.structuresremover.selection;

import fr.zeffut.structuresremover.scan.ScanOptions;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Holds the per-player selection and scan options. Everything lives in memory only:
 * selections are cheap to redo and are not worth persisting across restarts.
 */
public final class SelectionManager {
	private static final Map<UUID, PlayerSelection> SELECTIONS = new HashMap<>();
	private static final Map<UUID, ScanOptions> OPTIONS = new HashMap<>();

	private SelectionManager() {
	}

	public static PlayerSelection get(UUID player) {
		return SELECTIONS.computeIfAbsent(player, uuid -> new PlayerSelection());
	}

	public static ScanOptions options(UUID player) {
		return OPTIONS.computeIfAbsent(player, uuid -> new ScanOptions());
	}

	public static void forget(UUID player) {
		SELECTIONS.remove(player);
		OPTIONS.remove(player);
	}

	public static void clearAll() {
		SELECTIONS.clear();
		OPTIONS.clear();
	}
}
