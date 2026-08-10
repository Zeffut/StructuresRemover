package fr.zeffut.structuresremover.undo;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps the last removal of each player so it can be reverted with a single command.
 * Only one step of history is kept — undoing again after an undo is not supported.
 */
public final class UndoManager {
	private static final Map<UUID, UndoRecord> RECORDS = new HashMap<>();

	private UndoManager() {
	}

	public static void store(UUID player, UndoRecord record) {
		if (record.hasOverflowed() || record.size() == 0) {
			RECORDS.remove(player);
			return;
		}

		RECORDS.put(player, record);
	}

	@Nullable
	public static UndoRecord take(UUID player) {
		return RECORDS.remove(player);
	}

	public static boolean has(UUID player) {
		return RECORDS.containsKey(player);
	}

	public static void clearAll() {
		RECORDS.clear();
	}
}
