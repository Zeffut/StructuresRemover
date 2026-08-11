package fr.zeffut.structuresremover.selection;

import fr.zeffut.structuresremover.pattern.SavedPattern;
import fr.zeffut.structuresremover.scan.ScanOptions;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Holds each player's selection, saved patterns and scan options. Everything lives in memory only:
 * selections are cheap to redo and are not worth persisting across restarts.
 */
public final class SelectionManager {
	/** More than this many structures in one scan is almost certainly a mistake. */
	public static final int MAX_PATTERNS = 16;

	private static final Map<UUID, PlayerSelection> SELECTIONS = new HashMap<>();
	private static final Map<UUID, ScanOptions> OPTIONS = new HashMap<>();
	private static final Map<UUID, LinkedHashMap<String, SavedPattern>> PATTERNS = new HashMap<>();

	private SelectionManager() {
	}

	public static PlayerSelection get(UUID player) {
		return SELECTIONS.computeIfAbsent(player, uuid -> new PlayerSelection());
	}

	public static ScanOptions options(UUID player) {
		return OPTIONS.computeIfAbsent(player, uuid -> new ScanOptions());
	}

	/** The player's saved structures, in the order they were added. */
	public static LinkedHashMap<String, SavedPattern> patterns(UUID player) {
		return PATTERNS.computeIfAbsent(player, uuid -> new LinkedHashMap<>());
	}

	public static void forget(UUID player) {
		SELECTIONS.remove(player);
		OPTIONS.remove(player);
		PATTERNS.remove(player);
	}

	public static void clearAll() {
		SELECTIONS.clear();
		OPTIONS.clear();
		PATTERNS.clear();
	}
}
