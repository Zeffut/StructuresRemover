package fr.zeffut.structuresremover.selection;

import fr.zeffut.structuresremover.command.StructuresRemoverCommand;
import fr.zeffut.structuresremover.util.Chat;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;

/**
 * WorldEdit-style corner picking: left-click sets corner 1, right-click sets corner 2.
 *
 * <p>A vanilla item is used on purpose (no custom item is registered), so the mod only has to be
 * installed on the server and plain vanilla clients can still select structures.
 */
public final class WandHandler {
	/** Same item WorldEdit uses, so the muscle memory carries over. */
	public static final Item WAND_ITEM = Items.WOODEN_AXE;

	private WandHandler() {
	}

	public static void register() {
		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			if (world.isClient || !(player instanceof ServerPlayerEntity serverPlayer) || !isWielding(serverPlayer)) {
				return ActionResult.PASS;
			}

			PlayerSelection selection = SelectionManager.get(serverPlayer.getUuid());
			selection.setPos1(world.getRegistryKey(), pos);
			announce(serverPlayer, "Corner 1", pos, selection);
			return ActionResult.SUCCESS;
		});

		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (world.isClient || hand != Hand.MAIN_HAND
					|| !(player instanceof ServerPlayerEntity serverPlayer) || !isWielding(serverPlayer)) {
				return ActionResult.PASS;
			}

			BlockPos pos = hitResult.getBlockPos();
			PlayerSelection selection = SelectionManager.get(serverPlayer.getUuid());
			selection.setPos2(world.getRegistryKey(), pos);
			announce(serverPlayer, "Corner 2", pos, selection);
			return ActionResult.SUCCESS;
		});
	}

	private static boolean isWielding(ServerPlayerEntity player) {
		return SelectionManager.get(player.getUuid()).isWandEnabled()
				&& player.hasPermissionLevel(StructuresRemoverCommand.PERMISSION_LEVEL)
				&& player.getMainHandStack().isOf(WAND_ITEM);
	}

	private static void announce(ServerPlayerEntity player, String label, BlockPos pos, PlayerSelection selection) {
		BlockBox box = selection.toBox();
		String size = box == null
				? ""
				: " — " + box.getBlockCountX() + "x" + box.getBlockCountY() + "x" + box.getBlockCountZ();

		player.sendMessage(Chat.prefixed(Text.literal(label + ": " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + size)
				.formatted(Formatting.GREEN)), true);
	}
}
