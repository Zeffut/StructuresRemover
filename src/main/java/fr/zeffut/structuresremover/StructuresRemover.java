package fr.zeffut.structuresremover;

import fr.zeffut.structuresremover.command.StructuresRemoverCommand;
import fr.zeffut.structuresremover.scan.JobManager;
import fr.zeffut.structuresremover.selection.SelectionManager;
import fr.zeffut.structuresremover.selection.SelectionRenderer;
import fr.zeffut.structuresremover.selection.WandHandler;
import fr.zeffut.structuresremover.storage.PlayerDataStorage;
import fr.zeffut.structuresremover.undo.UndoManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Select a structure the way WorldEdit does, then wipe every identical copy of it from the map.
 *
 * <p>All of the work happens server-side, so the mod does not have to be installed on clients.
 */
public class StructuresRemover implements ModInitializer {
	public static final String MOD_ID = "structuresremover";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** How often pending selection changes are written out. */
	private static final int SAVE_INTERVAL_TICKS = 100;

	@Override
	public void onInitialize() {
		WandHandler.register();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				StructuresRemoverCommand.register(dispatcher, registryAccess));

		ServerTickEvents.END_SERVER_TICK.register(JobManager::tick);
		ServerTickEvents.END_SERVER_TICK.register(SelectionRenderer::tick);
		ServerTickEvents.END_SERVER_TICK.register(StructuresRemover::flushPeriodically);

		ServerLifecycleEvents.SERVER_STARTED.register(PlayerDataStorage::loadAll);

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			// A half-applied removal must not be left dangling across a restart.
			JobManager.cancel();
			JobManager.reset();

			// Selections and saved structures outlive the server; the undo history does not,
			// because it describes blocks that may be rewritten before the next start.
			SelectionManager.markAllDirty();
			PlayerDataStorage.saveDirty(server);
			SelectionManager.clearAll();
			UndoManager.clearAll();
		});

		DevSelfTest.register();
		BotwTrial.register();

		LOGGER.info("StructuresRemover ready — use /sr wand to start selecting.");
	}

	/**
	 * Selections change at typing speed, so they are batched instead of written on every command.
	 */
	private static void flushPeriodically(net.minecraft.server.MinecraftServer server) {
		if (server.getTicks() % SAVE_INTERVAL_TICKS == 0) {
			PlayerDataStorage.saveDirty(server);
		}
	}
}
