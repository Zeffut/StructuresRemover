package fr.zeffut.structuresremover.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import fr.zeffut.structuresremover.pattern.PatternVariant;
import fr.zeffut.structuresremover.pattern.StructurePattern;
import fr.zeffut.structuresremover.scan.ChunkSupplier;
import fr.zeffut.structuresremover.scan.Job;
import fr.zeffut.structuresremover.scan.JobManager;
import fr.zeffut.structuresremover.scan.RegionIndex;
import fr.zeffut.structuresremover.scan.ScanJob;
import fr.zeffut.structuresremover.scan.ScanOptions;
import fr.zeffut.structuresremover.scan.UndoJob;
import fr.zeffut.structuresremover.selection.PlayerSelection;
import fr.zeffut.structuresremover.selection.SelectionManager;
import fr.zeffut.structuresremover.selection.WandHandler;
import fr.zeffut.structuresremover.undo.UndoManager;
import fr.zeffut.structuresremover.undo.UndoRecord;
import fr.zeffut.structuresremover.util.Chat;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.command.argument.BlockStateArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.function.BiConsumer;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * The {@code /structuresremover} (alias {@code /sr}) command tree.
 */
public final class StructuresRemoverCommand {
	/** Operator level required for every subcommand — this thing rewrites the map. */
	public static final int PERMISSION_LEVEL = 2;

	private static final int MAX_RADIUS_CHUNKS = 4096;

	private StructuresRemoverCommand() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
		LiteralArgumentBuilder<ServerCommandSource> root = literal("structuresremover")
				.requires(source -> source.hasPermissionLevel(PERMISSION_LEVEL))
				.executes(StructuresRemoverCommand::help)
				.then(literal("help").executes(StructuresRemoverCommand::help))
				.then(literal("wand").executes(StructuresRemoverCommand::toggleWand))
				.then(literal("pos1")
						.executes(context -> setCorner(context, true, null))
						.then(argument("pos", BlockPosArgumentType.blockPos())
								.executes(context -> setCorner(context, true,
										BlockPosArgumentType.getBlockPos(context, "pos")))))
				.then(literal("pos2")
						.executes(context -> setCorner(context, false, null))
						.then(argument("pos", BlockPosArgumentType.blockPos())
								.executes(context -> setCorner(context, false,
										BlockPosArgumentType.getBlockPos(context, "pos")))))
				.then(literal("sel").executes(StructuresRemoverCommand::showSelection))
				.then(literal("clear").executes(StructuresRemoverCommand::clearSelection))
				.then(literal("options").executes(StructuresRemoverCommand::showOptions))
				.then(literal("set")
						.then(boolOption("rotations", (options, value) -> options.rotations = value))
						.then(boolOption("mirrors", (options, value) -> options.mirrors = value))
						.then(boolOption("matchair", (options, value) -> options.matchAir = value))
						.then(boolOption("keeporiginal", (options, value) -> options.keepOriginal = value))
						.then(boolOption("removeentities", (options, value) -> options.removeEntities = value))
						.then(intOption("tolerance", 1, 100, (options, value) -> options.tolerance = value))
						.then(intOption("maxmatches", 0, 1_000_000, (options, value) -> options.maxMatches = value))
						.then(intOption("chunkspertick", 1, 256, (options, value) -> options.chunksPerTick = value))
						.then(intOption("blockspertick", 64, 2_000_000, (options, value) -> options.blocksPerTick = value))
						.then(literal("fill")
								.then(argument("block", BlockStateArgumentType.blockState(registryAccess))
										.executes(context -> {
											ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
											ScanOptions options = SelectionManager.options(player.getUuid());
											options.fill = BlockStateArgumentType.getBlockState(context, "block").getBlockState();
											return feedback(context, "fill = " + options.fill.getBlock().getName().getString());
										}))))
				.then(literal("scan")
						.then(literal("world").executes(context -> start(context, false, true, 0)))
						.then(literal("radius")
								.then(argument("chunks", IntegerArgumentType.integer(0, MAX_RADIUS_CHUNKS))
										.executes(context -> start(context, false, false,
												IntegerArgumentType.getInteger(context, "chunks"))))))
				.then(literal("remove")
						.then(literal("world").executes(context -> start(context, true, true, 0)))
						.then(literal("radius")
								.then(argument("chunks", IntegerArgumentType.integer(0, MAX_RADIUS_CHUNKS))
										.executes(context -> start(context, true, false,
												IntegerArgumentType.getInteger(context, "chunks"))))))
				.then(literal("cancel").executes(StructuresRemoverCommand::cancel))
				.then(literal("status").executes(StructuresRemoverCommand::status))
				.then(literal("undo").executes(StructuresRemoverCommand::undo));

		LiteralCommandNode<ServerCommandSource> node = dispatcher.register(root);
		dispatcher.register(literal("sr")
				.requires(source -> source.hasPermissionLevel(PERMISSION_LEVEL))
				.executes(StructuresRemoverCommand::help)
				.redirect(node));
	}

	private static LiteralArgumentBuilder<ServerCommandSource> boolOption(String name, BiConsumer<ScanOptions, Boolean> setter) {
		return literal(name).then(argument("value", BoolArgumentType.bool()).executes(context -> {
			ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
			boolean value = BoolArgumentType.getBool(context, "value");
			setter.accept(SelectionManager.options(player.getUuid()), value);
			return feedback(context, name + " = " + value);
		}));
	}

	private static LiteralArgumentBuilder<ServerCommandSource> intOption(String name, int min, int max, BiConsumer<ScanOptions, Integer> setter) {
		return literal(name).then(argument("value", IntegerArgumentType.integer(min, max)).executes(context -> {
			ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
			int value = IntegerArgumentType.getInteger(context, "value");
			setter.accept(SelectionManager.options(player.getUuid()), value);
			return feedback(context, name + " = " + value);
		}));
	}

	// ---------------------------------------------------------------- selection

	private static int toggleWand(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
		PlayerSelection selection = SelectionManager.get(player.getUuid());
		selection.setWandEnabled(!selection.isWandEnabled());

		if (selection.isWandEnabled()) {
			if (!player.getInventory().contains(stack -> stack.isOf(WandHandler.WAND_ITEM))) {
				player.getInventory().insertStack(new ItemStack(WandHandler.WAND_ITEM));
			}

			return feedback(context, "Wand enabled — left-click a block for corner 1, right-click for corner 2 with a "
					+ WandHandler.WAND_ITEM.getName().getString() + ".");
		}

		return feedback(context, "Wand disabled.");
	}

	private static int setCorner(CommandContext<ServerCommandSource> context, boolean first, BlockPos explicit)
			throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
		BlockPos pos = explicit != null ? explicit : player.getBlockPos();
		PlayerSelection selection = SelectionManager.get(player.getUuid());

		if (first) {
			selection.setPos1(player.getWorld().getRegistryKey(), pos);
		} else {
			selection.setPos2(player.getWorld().getRegistryKey(), pos);
		}

		return feedback(context, "Corner " + (first ? "1" : "2") + " set to "
				+ pos.getX() + " " + pos.getY() + " " + pos.getZ() + describeSize(selection));
	}

	private static String describeSize(PlayerSelection selection) {
		BlockBox box = selection.toBox();

		if (box == null) {
			return "";
		}

		return " (" + box.getBlockCountX() + "x" + box.getBlockCountY() + "x" + box.getBlockCountZ() + ")";
	}

	private static int showSelection(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
		PlayerSelection selection = SelectionManager.get(player.getUuid());
		BlockBox box = selection.toBox();

		if (box == null) {
			return error(context, "No complete selection — set both corners first.");
		}

		long volume = (long) box.getBlockCountX() * box.getBlockCountY() * box.getBlockCountZ();
		context.getSource().sendFeedback(() -> Chat.prefixed(Text.literal("Selection in ")
				.formatted(Formatting.GRAY)
				.append(Text.literal(selection.getWorld().getValue().toString()).formatted(Formatting.WHITE))
				.append(Text.literal(": " + box.getBlockCountX() + "x" + box.getBlockCountY() + "x"
						+ box.getBlockCountZ() + " = " + volume + " blocks").formatted(Formatting.AQUA))
				.append(Text.literal("\n  from " + box.getMinX() + " " + box.getMinY() + " " + box.getMinZ()
						+ " to " + box.getMaxX() + " " + box.getMaxY() + " " + box.getMaxZ())
						.formatted(Formatting.DARK_GRAY))), false);
		return 1;
	}

	private static int clearSelection(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
		SelectionManager.get(player.getUuid()).clear();
		return feedback(context, "Selection cleared.");
	}

	private static int showOptions(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
		ScanOptions options = SelectionManager.options(player.getUuid());

		String body = "rotations = " + options.rotations
				+ "\n  mirrors = " + options.mirrors
				+ "\n  tolerance = " + options.tolerance + "%"
				+ "\n  matchair = " + options.matchAir
				+ "\n  fill = " + options.fill.getBlock().getName().getString()
				+ "\n  keeporiginal = " + options.keepOriginal
				+ "\n  removeentities = " + options.removeEntities
				+ "\n  maxmatches = " + (options.maxMatches == 0 ? "unlimited" : options.maxMatches)
				+ "\n  chunkspertick = " + options.chunksPerTick
				+ "\n  blockspertick = " + options.blocksPerTick;

		context.getSource().sendFeedback(() -> Chat.prefixed(
				Text.literal("Options:\n  ").formatted(Formatting.GRAY)
						.append(Text.literal(body).formatted(Formatting.WHITE))), false);
		return 1;
	}

	// ---------------------------------------------------------------- jobs

	private static int start(CommandContext<ServerCommandSource> context, boolean remove, boolean wholeWorld, int radius)
			throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();

		if (JobManager.isBusy()) {
			return error(context, "Another operation is already running. Use /sr status or /sr cancel.");
		}

		PlayerSelection selection = SelectionManager.get(player.getUuid());
		BlockBox box = selection.toBox();

		if (box == null) {
			return error(context, "No complete selection — mark both corners with the wand first.");
		}

		ServerWorld world = context.getSource().getServer().getWorld(selection.getWorld());

		if (world == null) {
			return error(context, "The dimension the selection was made in is no longer loaded.");
		}

		if (!wholeWorld && !player.getWorld().getRegistryKey().equals(world.getRegistryKey())) {
			return error(context, "A radius scan is centred on you, but your selection is in "
					+ selection.getWorld().getValue() + ". Go back there, or use 'world' instead.");
		}

		StructurePattern pattern;

		try {
			pattern = StructurePattern.capture(world, box);
		} catch (StructurePattern.PatternException exception) {
			return error(context, exception.getMessage());
		}

		ScanOptions options = SelectionManager.options(player.getUuid()).copy();
		List<PatternVariant> variants = pattern.buildVariants(options.rotations, options.mirrors);
		RegionIndex regionIndex = new RegionIndex(world);

		ChunkSupplier supplier;

		if (wholeWorld) {
			// A whole-world walk is driven by the region files on disk, so anything still sitting
			// in memory has to be flushed first or it would simply be skipped.
			context.getSource().getServer().saveAll(true, true, true);

			ChunkSupplier.WholeWorld wholeWorldSupplier = new ChunkSupplier.WholeWorld(regionIndex);

			if (wholeWorldSupplier.regionCount() == 0) {
				return error(context, "No region files found for " + selection.getWorld().getValue() + ".");
			}

			supplier = wholeWorldSupplier;
		} else {
			supplier = new ChunkSupplier.Square(regionIndex, player.getChunkPos(), radius);
		}

		PatternVariant reference = variants.get(0);
		context.getSource().sendFeedback(() -> Chat.prefixed(Text.literal(
						(remove ? "Removing " : "Scanning for ") + "copies of a "
								+ pattern.getSizeX() + "x" + pattern.getSizeY() + "x" + pattern.getSizeZ()
								+ " structure (" + pattern.getSolidCount() + " solid blocks, "
								+ variants.size() + " orientation(s), anchor: "
								+ reference.anchorState().getBlock().getName().getString() + ")...")
				.formatted(Formatting.GRAY)), true);

		JobManager.start(new ScanJob(world, player.getUuid(), pattern, variants, options, remove, supplier, regionIndex));
		return 1;
	}

	private static int cancel(CommandContext<ServerCommandSource> context) {
		if (!JobManager.cancel()) {
			return error(context, "Nothing is running.");
		}

		return feedback(context, "Stopping at the next tick...");
	}

	private static int status(CommandContext<ServerCommandSource> context) {
		Job job = JobManager.current();

		if (job == null) {
			return feedback(context, "Idle.");
		}

		context.getSource().sendFeedback(() -> Chat.prefixed(job.describe().copy().formatted(Formatting.AQUA)), false);
		return 1;
	}

	private static int undo(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();

		if (JobManager.isBusy()) {
			return error(context, "Wait for the running operation to finish first.");
		}

		UndoRecord record = UndoManager.take(player.getUuid());

		if (record == null) {
			return error(context, "Nothing to undo.");
		}

		ServerWorld world = context.getSource().getServer().getWorld(record.getWorld());

		if (world == null) {
			return error(context, "The dimension of the last removal is no longer loaded.");
		}

		JobManager.start(new UndoJob(world, player.getUuid(), record,
				SelectionManager.options(player.getUuid()).blocksPerTick));
		return feedback(context, "Restoring " + record.size() + " blocks...");
	}

	// ---------------------------------------------------------------- misc

	private static int help(CommandContext<ServerCommandSource> context) {
		String body = """
				/sr wand                  toggle the selection wand
				/sr pos1 | pos2 [x y z]   set a corner without the wand
				/sr sel | clear           show or drop the selection
				/sr scan world|radius <n> count identical copies
				/sr remove world|radius <n> delete identical copies
				/sr options               show matching options
				/sr set <option> <value>  change a matching option
				/sr status | cancel       follow or stop the running job
				/sr undo                  restore the last removal""";

		context.getSource().sendFeedback(() -> Chat.prefixed(
				Text.literal("StructuresRemover\n").formatted(Formatting.AQUA)
						.append(Text.literal(body).formatted(Formatting.GRAY))), false);
		return 1;
	}

	private static int feedback(CommandContext<ServerCommandSource> context, String message) {
		context.getSource().sendFeedback(() -> Chat.prefixed(Text.literal(message).formatted(Formatting.GREEN)), false);
		return 1;
	}

	private static int error(CommandContext<ServerCommandSource> context, String message) {
		context.getSource().sendError(Chat.prefixed(Text.literal(message).formatted(Formatting.RED)));
		return 0;
	}
}
