package fr.zeffut.structuresremover.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import fr.zeffut.structuresremover.pattern.SavedPattern;
import fr.zeffut.structuresremover.pattern.StructurePattern;
import fr.zeffut.structuresremover.scan.ChunkSupplier;
import fr.zeffut.structuresremover.scan.Job;
import fr.zeffut.structuresremover.scan.JobManager;
import fr.zeffut.structuresremover.scan.RegionIndex;
import fr.zeffut.structuresremover.scan.ScanJob;
import fr.zeffut.structuresremover.scan.ScanOptions;
import fr.zeffut.structuresremover.scan.Target;
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
import net.minecraft.command.permission.PermissionCheck;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BiConsumer;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * The {@code /structuresremover} (alias {@code /sr}) command tree.
 */
public final class StructuresRemoverCommand {
	/** Operator level 2 (gamemasters), required for every subcommand — this thing rewrites the map. */
	public static final PermissionCheck PERMISSION_CHECK = CommandManager.GAMEMASTERS_CHECK;

	/** How far {@code /sr pos1} looks for the block the player is aiming at. */
	public static final double AIM_DISTANCE = 128.0;

	private static final int MAX_RADIUS_CHUNKS = 4096;

	/** Name used when scanning straight from the selection, without saving it first. */
	private static final String SELECTION_NAME = "selection";

	private static final SuggestionProvider<ServerCommandSource> PATTERN_NAMES = (context, builder) -> {
		ServerPlayerEntity player = context.getSource().getPlayer();

		if (player != null) {
			SelectionManager.patterns(player.getUuid()).keySet().forEach(builder::suggest);
		}

		return builder.buildFuture();
	};

	private StructuresRemoverCommand() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
		LiteralArgumentBuilder<ServerCommandSource> root = literal("structuresremover")
				.requires(CommandManager.requirePermissionLevel(PERMISSION_CHECK))
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
				.then(literal("outline")
						.then(argument("value", BoolArgumentType.bool())
								.executes(StructuresRemoverCommand::setOutline)))
				.then(literal("trim").executes(StructuresRemoverCommand::trim))
				.then(resizeCommand("expand", 1))
				.then(resizeCommand("contract", -1))
				.then(literal("add")
						.executes(context -> addPattern(context, null))
						.then(argument("name", StringArgumentType.word())
								.executes(context -> addPattern(context, StringArgumentType.getString(context, "name")))))
				.then(literal("list").executes(StructuresRemoverCommand::listPatterns))
				.then(literal("forget")
						.then(literal("all").executes(StructuresRemoverCommand::forgetAll))
						.then(argument("name", StringArgumentType.word())
								.suggests(PATTERN_NAMES)
								.executes(StructuresRemoverCommand::forgetPattern)))
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
											SelectionManager.markDirty(player.getUuid());
											return feedback(context, "fill = " + options.fill.getBlock().getName().getString());
										}))))
				.then(scanCommand("scan", false))
				.then(scanCommand("remove", true))
				.then(literal("cancel").executes(StructuresRemoverCommand::cancel))
				.then(literal("status").executes(StructuresRemoverCommand::status))
				.then(literal("undo").executes(StructuresRemoverCommand::undo));

		LiteralCommandNode<ServerCommandSource> node = dispatcher.register(root);
		dispatcher.register(literal("sr")
				.requires(CommandManager.requirePermissionLevel(PERMISSION_CHECK))
				.executes(StructuresRemoverCommand::help)
				.redirect(node));
	}

	private static LiteralArgumentBuilder<ServerCommandSource> scanCommand(String name, boolean remove) {
		return literal(name)
				.then(literal("world").executes(context -> start(context, remove, true, 0)))
				.then(literal("radius")
						.then(argument("chunks", IntegerArgumentType.integer(0, MAX_RADIUS_CHUNKS))
								.executes(context -> start(context, remove, false,
										IntegerArgumentType.getInteger(context, "chunks")))));
	}

	/**
	 * Builds {@code expand} / {@code contract}, which differ only by the sign applied to the amount.
	 */
	private static LiteralArgumentBuilder<ServerCommandSource> resizeCommand(String name, int sign) {
		RequiredArgumentBuilder<ServerCommandSource, Integer> amount =
				argument("amount", IntegerArgumentType.integer(1, 4096))
						.executes(context -> resize(context, sign * IntegerArgumentType.getInteger(context, "amount"), null))
						.then(literal("all").executes(context ->
								resize(context, sign * IntegerArgumentType.getInteger(context, "amount"), null)));

		for (Direction direction : Direction.values()) {
			amount.then(literal(direction.asString()).executes(context ->
					resize(context, sign * IntegerArgumentType.getInteger(context, "amount"), direction)));
		}

		return literal(name).then(amount);
	}

	private static LiteralArgumentBuilder<ServerCommandSource> boolOption(String name, BiConsumer<ScanOptions, Boolean> setter) {
		return literal(name).then(argument("value", BoolArgumentType.bool()).executes(context -> {
			ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
			boolean value = BoolArgumentType.getBool(context, "value");
			setter.accept(SelectionManager.options(player.getUuid()), value);
			SelectionManager.markDirty(player.getUuid());
			return feedback(context, name + " = " + value);
		}));
	}

	private static LiteralArgumentBuilder<ServerCommandSource> intOption(String name, int min, int max, BiConsumer<ScanOptions, Integer> setter) {
		return literal(name).then(argument("value", IntegerArgumentType.integer(min, max)).executes(context -> {
			ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
			int value = IntegerArgumentType.getInteger(context, "value");
			setter.accept(SelectionManager.options(player.getUuid()), value);
			SelectionManager.markDirty(player.getUuid());
			return feedback(context, name + " = " + value);
		}));
	}

	// ---------------------------------------------------------------- selection

	private static int toggleWand(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
		PlayerSelection selection = SelectionManager.get(player.getUuid());
		selection.setWandEnabled(!selection.isWandEnabled());
		SelectionManager.markDirty(player.getUuid());

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
		BlockPos pos = explicit != null ? explicit : aimedBlock(player);
		PlayerSelection selection = SelectionManager.get(player.getUuid());

		if (first) {
			selection.setPos1(player.getEntityWorld().getRegistryKey(), pos);
		} else {
			selection.setPos2(player.getEntityWorld().getRegistryKey(), pos);
		}

		SelectionManager.markDirty(player.getUuid());
		return feedback(context, "Corner " + (first ? "1" : "2") + " set to "
				+ pos.getX() + " " + pos.getY() + " " + pos.getZ() + describeSize(selection));
	}

	/**
	 * The block the player is looking at, falling back to the block under their feet when they are
	 * aiming at the sky. Pointing is far quicker than walking to each corner.
	 */
	private static BlockPos aimedBlock(ServerPlayerEntity player) {
		HitResult hit = player.raycast(AIM_DISTANCE, 0.0F, false);

		if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit) {
			return blockHit.getBlockPos();
		}

		return player.getBlockPos();
	}

	private static String describeSize(PlayerSelection selection) {
		BlockBox box = selection.toBox();

		if (box == null) {
			return "";
		}

		return " (" + box.getBlockCountX() + "x" + box.getBlockCountY() + "x" + box.getBlockCountZ() + ")";
	}

	private static int setOutline(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
		boolean value = BoolArgumentType.getBool(context, "value");
		SelectionManager.get(player.getUuid()).setOutlineShown(value);
		SelectionManager.markDirty(player.getUuid());
		return feedback(context, "Selection outline " + (value ? "shown." : "hidden."));
	}

	/**
	 * Grows or shrinks the selection. A {@code null} direction means all six faces at once.
	 */
	private static int resize(CommandContext<ServerCommandSource> context, int amount, Direction direction)
			throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
		PlayerSelection selection = SelectionManager.get(player.getUuid());
		BlockBox box = selection.toBox();

		if (box == null) {
			return error(context, "No complete selection yet.");
		}

		int minX = box.getMinX();
		int minY = box.getMinY();
		int minZ = box.getMinZ();
		int maxX = box.getMaxX();
		int maxY = box.getMaxY();
		int maxZ = box.getMaxZ();

		if (direction == null) {
			minX -= amount;
			minY -= amount;
			minZ -= amount;
			maxX += amount;
			maxY += amount;
			maxZ += amount;
		} else {
			switch (direction) {
				case WEST -> minX -= amount;
				case EAST -> maxX += amount;
				case DOWN -> minY -= amount;
				case UP -> maxY += amount;
				case NORTH -> minZ -= amount;
				case SOUTH -> maxZ += amount;
			}
		}

		if (minX > maxX || minY > maxY || minZ > maxZ) {
			return error(context, "That would shrink the selection to nothing.");
		}

		ServerWorld world = (ServerWorld) player.getEntityWorld();
		minY = Math.max(minY, world.getBottomY());
		maxY = Math.min(maxY, world.getTopYInclusive());

		selection.setBox(selection.getWorld(), new BlockBox(minX, minY, minZ, maxX, maxY, maxZ));
		SelectionManager.markDirty(player.getUuid());
		return feedback(context, "Selection is now" + describeSize(selection) + ".");
	}

	/**
	 * Shrinks the selection onto the blocks it actually contains, dropping the empty margin.
	 */
	private static int trim(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
		PlayerSelection selection = SelectionManager.get(player.getUuid());
		BlockBox box = selection.toBox();

		if (box == null) {
			return error(context, "No complete selection yet.");
		}

		ServerWorld world = context.getSource().getServer().getWorld(selection.getWorld());

		if (world == null) {
			return error(context, "The dimension the selection was made in is no longer loaded.");
		}

		StructurePattern pattern;

		try {
			pattern = StructurePattern.capture(world, box, true);
		} catch (StructurePattern.PatternException exception) {
			return error(context, exception.getMessage());
		}

		BlockPos origin = pattern.getOrigin();
		selection.setBox(selection.getWorld(), new BlockBox(
				origin.getX(), origin.getY(), origin.getZ(),
				origin.getX() + pattern.getSizeX() - 1,
				origin.getY() + pattern.getSizeY() - 1,
				origin.getZ() + pattern.getSizeZ() - 1));
		SelectionManager.markDirty(player.getUuid());

		return feedback(context, "Trimmed to" + describeSize(selection) + " — "
				+ pattern.getSolidCount() + " blocks.");
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
		SelectionManager.markDirty(player.getUuid());
		return feedback(context, "Selection cleared.");
	}

	// ---------------------------------------------------------------- saved patterns

	private static int addPattern(CommandContext<ServerCommandSource> context, String name)
			throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
		PlayerSelection selection = SelectionManager.get(player.getUuid());
		BlockBox box = selection.toBox();

		if (box == null) {
			return error(context, "No complete selection — mark both corners first.");
		}

		LinkedHashMap<String, SavedPattern> patterns = SelectionManager.patterns(player.getUuid());

		if (patterns.size() >= SelectionManager.MAX_PATTERNS && (name == null || !patterns.containsKey(name))) {
			return error(context, "Too many saved structures (limit " + SelectionManager.MAX_PATTERNS
					+ "). Drop one with /sr forget <name>.");
		}

		ServerWorld world = context.getSource().getServer().getWorld(selection.getWorld());

		if (world == null) {
			return error(context, "The dimension the selection was made in is no longer loaded.");
		}

		ScanOptions options = SelectionManager.options(player.getUuid());
		StructurePattern pattern;

		try {
			pattern = StructurePattern.capture(world, box, options.trimsPatterns());
		} catch (StructurePattern.PatternException exception) {
			return error(context, exception.getMessage());
		}

		String chosen = name != null ? name : nextFreeName(patterns);
		patterns.put(chosen, new SavedPattern(chosen, selection.getWorld(), pattern));
		SelectionManager.markDirty(player.getUuid());

		return feedback(context, "Saved '" + chosen + "' — " + pattern.getSizeX() + "x" + pattern.getSizeY()
				+ "x" + pattern.getSizeZ() + ", " + pattern.getSolidCount() + " blocks. "
				+ patterns.size() + " structure(s) queued.");
	}

	private static String nextFreeName(LinkedHashMap<String, SavedPattern> patterns) {
		for (int i = 1; ; i++) {
			String candidate = "structure" + i;

			if (!patterns.containsKey(candidate)) {
				return candidate;
			}
		}
	}

	private static int listPatterns(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
		LinkedHashMap<String, SavedPattern> patterns = SelectionManager.patterns(player.getUuid());

		if (patterns.isEmpty()) {
			return feedback(context, "No saved structure. /sr scan will use the current selection.");
		}

		StringBuilder body = new StringBuilder();

		for (SavedPattern saved : patterns.values()) {
			body.append("\n  • ").append(saved.describe());
		}

		context.getSource().sendFeedback(() -> Chat.prefixed(
				Text.literal(patterns.size() + " saved structure(s):").formatted(Formatting.GRAY)
						.append(Text.literal(body.toString()).formatted(Formatting.WHITE))), false);
		return 1;
	}

	private static int forgetPattern(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
		String name = StringArgumentType.getString(context, "name");

		if (SelectionManager.patterns(player.getUuid()).remove(name) == null) {
			return error(context, "No saved structure called '" + name + "'.");
		}

		SelectionManager.markDirty(player.getUuid());
		return feedback(context, "Dropped '" + name + "'.");
	}

	private static int forgetAll(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
		int count = SelectionManager.patterns(player.getUuid()).size();
		SelectionManager.patterns(player.getUuid()).clear();
		SelectionManager.markDirty(player.getUuid());
		return feedback(context, "Dropped " + count + " saved structure(s).");
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

		ScanOptions options = SelectionManager.options(player.getUuid()).copy();
		List<Target> targets = new ArrayList<>();
		LinkedHashMap<String, SavedPattern> patterns = SelectionManager.patterns(player.getUuid());

		if (patterns.isEmpty()) {
			// Nothing saved: fall back to whatever is selected right now, so the quick path stays
			// a two-click affair.
			PlayerSelection selection = SelectionManager.get(player.getUuid());
			BlockBox box = selection.toBox();

			if (box == null) {
				return error(context, "Nothing to look for — select a structure, or save some with /sr add.");
			}

			ServerWorld source = context.getSource().getServer().getWorld(selection.getWorld());

			if (source == null) {
				return error(context, "The dimension the selection was made in is no longer loaded.");
			}

			try {
				StructurePattern pattern = StructurePattern.capture(source, box, options.trimsPatterns());
				targets.add(Target.of(new SavedPattern(SELECTION_NAME, selection.getWorld(), pattern),
						options.rotations, options.mirrors));
			} catch (StructurePattern.PatternException exception) {
				return error(context, exception.getMessage());
			}
		} else {
			for (SavedPattern saved : patterns.values()) {
				targets.add(Target.of(saved, options.rotations, options.mirrors));
			}
		}

		// The scan always runs in the dimension the player is standing in; a pattern captured in
		// the Nether can perfectly well be hunted in the Overworld.
		ServerWorld world = (ServerWorld) player.getEntityWorld();
		RegionIndex regionIndex = new RegionIndex(world);
		ChunkSupplier supplier;

		if (wholeWorld) {
			ChunkSupplier.WholeWorld wholeWorldSupplier = new ChunkSupplier.WholeWorld(regionIndex);

			if (wholeWorldSupplier.regionCount() == 0) {
				return error(context, "Nothing generated yet in " + world.getRegistryKey().getValue() + ".");
			}

			supplier = wholeWorldSupplier;
		} else {
			supplier = new ChunkSupplier.Square(regionIndex, player.getChunkPos(), radius);
		}

		int orientations = targets.stream().mapToInt(target -> target.variants().size()).sum();
		context.getSource().sendFeedback(() -> Chat.prefixed(Text.literal(
						(remove ? "Removing " : "Scanning for ") + targets.size() + " structure(s) in "
								+ world.getRegistryKey().getValue() + " (" + orientations + " orientation(s), "
								+ (options.matchAir ? "air matched" : "air ignored") + ", tolerance "
								+ options.tolerance + "%)...")
				.formatted(Formatting.GRAY)), true);

		JobManager.start(new ScanJob(world, player.getUuid(), targets, options, remove, supplier, regionIndex));
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
				Selecting
				  /sr wand                  toggle the selection wand
				  /sr pos1 | pos2 [x y z]   corner at the block you are aiming at
				  /sr expand | contract <n> [direction]
				  /sr trim                  shrink onto the blocks themselves
				  /sr sel | clear           show or drop the selection
				  /sr outline <true|false>  particle outline
				Structures
				  /sr add [name]            queue the selection as a structure to hunt
				  /sr list | forget <name>|all
				Hunting
				  /sr scan world|radius <n> count copies, changes nothing
				  /sr remove world|radius <n>
				  /sr status | cancel | undo
				  /sr options | set <option> <value>""";

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
