package fr.zeffut.structuresremover.util;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.UUID;

/**
 * Message helpers.
 *
 * <p>Everything is sent as literal text rather than translation keys on purpose: the mod is meant
 * to work on a dedicated server with vanilla clients, which would only show raw keys.
 */
public final class Chat {
	private Chat() {
	}

	public static Text prefixed(Text body) {
		return Text.literal("[SR] ").formatted(Formatting.DARK_AQUA).append(body);
	}

	public static void toPlayer(MinecraftServer server, UUID player, Text message) {
		ServerPlayerEntity target = server.getPlayerManager().getPlayer(player);

		if (target != null) {
			target.sendMessage(prefixed(message), false);
		}
	}

	public static void actionBar(MinecraftServer server, UUID player, Text message) {
		ServerPlayerEntity target = server.getPlayerManager().getPlayer(player);

		if (target != null) {
			target.sendMessage(message, true);
		}
	}
}
