package fr.zeffut.structuresremover.selection;

import fr.zeffut.structuresremover.pattern.SavedPattern;
import fr.zeffut.structuresremover.scan.ScanOptions;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Holds each player's selection, saved structures and scan options.
 *
 * <p>This is the in-memory copy; {@code PlayerDataStorage} writes it into the world save. Anything
 * that changes a player's data has to call {@link #markDirty} so it gets flushed.
 */
public final class SelectionManager {
	/** More than this many structures in one scan is almost certainly a mistake. */
	public static final int MAX_PATTERNS = 16;

	private static final Map<UUID, PlayerSelection> SELECTIONS = new HashMap<>();
	private static final Map<UUID, ScanOptions> OPTIONS = new HashMap<>();
	private static final Map<UUID, LinkedHashMap<String, SavedPattern>> PATTERNS = new HashMap<>();
	private static final Set<UUID> DIRTY = new HashSet<>();

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

	/** Flags the player's data as needing a write. */
	public static void markDirty(UUID player) {
		DIRTY.add(player);
	}

	/** Returns the players awaiting a write and clears the pending set. */
	public static Set<UUID> drainDirty() {
		if (DIRTY.isEmpty()) {
			return Set.of();
		}

		Set<UUID> drained = Set.copyOf(DIRTY);
		DIRTY.clear();
		return drained;
	}

	/** Marks every known player dirty, so a shutdown writes everything currently in memory. */
	public static void markAllDirty() {
		DIRTY.addAll(SELECTIONS.keySet());
		DIRTY.addAll(OPTIONS.keySet());
		DIRTY.addAll(PATTERNS.keySet());
	}

	public static void clearAll() {
		SELECTIONS.clear();
		OPTIONS.clear();
		PATTERNS.clear();
		DIRTY.clear();
	}
}
