package akisei.karmacurrency;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

public final class KarmaCommands {
	private KarmaCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(
				Commands.literal("karma")
					.executes(context -> showBalance(context.getSource(), context.getSource().getPlayerOrException()))
					.then(Commands.literal("choose")
					.then(Commands.literal("light").executes(context -> chooseAlignment(context.getSource(), SocialCurrencyService.Stance.LIGHT)))
					.then(Commands.literal("dark").executes(context -> chooseAlignment(context.getSource(), SocialCurrencyService.Stance.DARK))))
				.then(Commands.literal("infamy")
						.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
						.then(Commands.literal("add").then(infamyNode("add")))
						.then(Commands.literal("remove").then(infamyNode("remove")))
						.then(Commands.literal("set").then(infamyNode("set"))))
					.then(Commands.literal("reputation")
						.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
						.then(Commands.literal("add").then(reputationNode("add")))
						.then(Commands.literal("remove").then(reputationNode("remove")))
						.then(Commands.literal("set").then(reputationNode("set"))))
					.then(questsCommand())
					.then(debugCommand())
					.then(Commands.literal("reload")
						.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
						.executes(context -> reload(context.getSource())))
			);
            dispatcher.register(Commands.literal("quest")
                .executes(context -> questStatus(context.getSource()))
                .then(Commands.literal("submit").executes(context -> QuestRevamp.submit(context.getSource().getPlayerOrException(),false)?1:0)
                    .then(Commands.literal("confirm").executes(context -> QuestRevamp.submit(context.getSource().getPlayerOrException(),true)?1:0))));
		});
	}

	private static int questStatus(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		QuestService.QuestId active = QuestService.activeQuest(player);
		String activeLine = active == null ? "Active quest: none (0/1)."
			: "Active quest: " + active.displayName() + " (1/1).\nObjective: "
				+ QuestService.objectiveDescription(player, active) + "\nBonus: "
				+ QuestService.bonusDescription(player, active) + "\nProgress: " + QuestService.status(player, active);
		SocialCurrencyService.Stance stance = SocialCurrencyService.getStance(player);
		String pools = switch (stance) {
			case NEUTRAL -> "Trials and Contracts are unavailable while Neutral.";
			case LIGHT -> "Available alignment: Trials.";
			case DARK -> "Available alignment: Contracts.";
		};
		String message = activeLine
			+ "\n" + QuestService.generalCooldownStatus(player)
			+ "\n" + QuestService.perQuestCooldownStatus(player)
			+ "\n" + pools
			+ "\nCurrent cycle: shared KIC shop cycle."
			+ "\n" + QuestService.nextCycleStatus();
		if (!KarmaGameRules.enabled(player, KarmaGameRules.QUESTS_ENABLED)) {
			message = "Quests are disabled by the quests_enabled gamerule. Trials, Contracts, and The Hunt are unavailable.\n"
				+ (active == QuestService.QuestId.DUEL ? activeLine + "\n" : "")
				+ (KarmaGameRules.enabled(player, KarmaGameRules.DUELS_ENABLED)
					? "Duels remain controlled independently by duels_enabled.\n" : "");
		}
		ServerPlayer challenger = QuestService.pendingDuelChallenger(player);
		if (challenger != null) message += "\nDuel invitation from: " + challenger.getName().getString() + ". Use /quest duel accept " + challenger.getName().getString() + " or /quest duel decline " + challenger.getName().getString() + ".";
		String statusMessage = message;
		source.sendSuccess(() -> Component.literal(statusMessage), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int duelInvite(CommandSourceStack source, ServerPlayer opponent) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		QuestService.inviteDuel(source.getPlayerOrException(), opponent);
		return Command.SINGLE_SUCCESS;
	}

	private static int duelAccept(CommandSourceStack source, ServerPlayer challenger) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		QuestService.acceptDuel(source.getPlayerOrException(), challenger);
		return Command.SINGLE_SUCCESS;
	}

	private static int duelDecline(CommandSourceStack source, ServerPlayer challenger) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		QuestService.declineDuel(source.getPlayerOrException(), challenger);
		return Command.SINGLE_SUCCESS;
	}

	private static LiteralArgumentBuilder<CommandSourceStack> debugCommand() {
		return Commands.literal("debug")
			.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
			.then(QuestDebugCommands.node())
            .then(QuestDebugCommands.inspectNode("endurance"))
            .then(QuestDebugCommands.inspectNode("challenger"))
            .then(QuestDebugCommands.rotationNode())
            .then(QuestDebugCommands.rewardNode())
            .then(Commands.literal("check")
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(context -> checkPlayerStats(context.getSource(), EntityArgument.getPlayer(context, "player")))))
			.then(Commands.literal("add").then(targetAmountNode("add")))
			.then(Commands.literal("remove").then(targetAmountNode("remove")))
			.then(Commands.literal("set").then(targetAmountNode("set")))
			.then(Commands.literal("infamy")
				.then(Commands.literal("add").then(infamyNode("add")))
				.then(Commands.literal("remove").then(infamyNode("remove")))
				.then(Commands.literal("set").then(infamyNode("set"))))
			.then(Commands.literal("reputation")
				.then(Commands.literal("add").then(reputationNode("add")))
				.then(Commands.literal("remove").then(reputationNode("remove")))
				.then(Commands.literal("set").then(reputationNode("set"))))
			.then(Commands.literal("limit")
				.then(Commands.argument("player", EntityArgument.player())
					.then(Commands.argument("karma", IntegerArgumentType.integer(0))
						.executes(context -> setKarmaLimit(context.getSource(),
							EntityArgument.getPlayer(context, "player"),
							IntegerArgumentType.getInteger(context, "karma"))))))
			.then(Commands.literal("token")
				.then(Commands.literal("add").then(tokenAmountNode("add")))
				.then(Commands.literal("remove").then(tokenAmountNode("remove")))
				.then(Commands.literal("materialize")
					.then(Commands.literal("reputation").then(tokenSourceAmountNode("reputation")))
				.then(Commands.literal("infamy").then(tokenSourceAmountNode("infamy"))))
				.then(Commands.literal("redeem")
					.then(Commands.literal("reputation").then(tokenRedeemAmountNode(SocialCurrencyService.RedemptionTarget.REPUTATION)))
				.then(Commands.literal("infamy").then(tokenRedeemAmountNode(SocialCurrencyService.RedemptionTarget.INFAMY)))) )
			.then(Commands.literal("reset")
				.then(Commands.argument("player", EntityArgument.player())
					.executes(context -> resetDebug(context.getSource(), EntityArgument.getPlayer(context, "player")))))
			.then(Commands.literal("quest")
				.then(Commands.literal("reset")
					.executes(context -> resetQuestDebug(context.getSource()))))
			.then(debugHuntCommand())
			.then(Commands.literal("commitment")
				.then(Commands.argument("player", EntityArgument.player())
					.then(Commands.argument("value", StringArgumentType.word())
						.executes(context -> debugCommitment(context.getSource(),
							EntityArgument.getPlayer(context, "player"),
							StringArgumentType.getString(context, "value"))))))
			.then(sigilCommand())
			.then(curseCommand())
			.then(blessingCommand())
			.then(Commands.literal("info")
				.executes(context -> infoDebug(context.getSource(), context.getSource().getPlayerOrException()))
				.then(Commands.argument("player", EntityArgument.player())
					.executes(context -> infoDebug(context.getSource(), EntityArgument.getPlayer(context, "player")))))
			.then(Commands.literal("trigger")
				.then(Commands.literal("shop_cycle")
					.executes(context -> triggerShopCycle(context.getSource())))
				.then(Commands.literal("karma").then(targetAmountNode("add")))
				.then(Commands.literal("infamy").then(infamyNode("add")))
				.then(Commands.literal("decay")
					.then(Commands.argument("player", EntityArgument.player())
						.executes(context -> triggerDecay(context.getSource(), EntityArgument.getPlayer(context, "player")))))
				.then(Commands.literal("shrine_scan")
					.executes(context -> triggerShrineScan(context.getSource(), context.getSource().getPlayerOrException())))
				.then(Commands.literal("event")
					.then(Commands.literal("trade").then(targetEventNode("trade")))
					.then(Commands.literal("cure").then(targetEventNode("cure")))
					.then(Commands.literal("villager_kill").then(targetEventNode("villager_kill")))
					.then(Commands.literal("ender_dragon").then(targetEventNode("ender_dragon")))
					.then(Commands.literal("wither").then(targetEventNode("wither")))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> debugHuntCommand() {
		return Commands.literal("hunt")
			.then(Commands.literal("status")
				.executes(context -> {
					context.getSource().sendSuccess(() -> Component.literal(HuntService.status(context.getSource().getServer())), false);
					return Command.SINGLE_SUCCESS;
				}))
			.then(Commands.literal("start")
				.then(Commands.argument("player", EntityArgument.player())
					.executes(context -> debugHuntStart(context.getSource(), EntityArgument.getPlayer(context, "player")))))
			.then(Commands.literal("advance")
				.then(Commands.argument("minutes", IntegerArgumentType.integer(1, 100000))
					.executes(context -> debugHuntAdvance(context.getSource(), IntegerArgumentType.getInteger(context, "minutes")))))
			.then(Commands.literal("relogin")
				.then(Commands.argument("player", EntityArgument.player())
					.executes(context -> debugHuntRelogin(context.getSource(), EntityArgument.getPlayer(context, "player")))))
			.then(Commands.literal("compass")
				.then(Commands.argument("player", EntityArgument.player())
					.executes(context -> debugHuntCompass(context.getSource(), EntityArgument.getPlayer(context, "player")))))
			.then(Commands.literal("kill")
				.then(Commands.argument("hunter", EntityArgument.player())
					.then(Commands.argument("hunted", EntityArgument.player())
						.executes(context -> debugHuntKill(context.getSource(),
							EntityArgument.getPlayer(context, "hunter"),
							EntityArgument.getPlayer(context, "hunted"))))));
	}

	private static int debugHuntStart(CommandSourceStack source, ServerPlayer hunted) {
		if (!HuntService.debugForceStart(source.getServer(), hunted)) {
			source.sendFailure(Component.literal("Could not start the debug Hunt. Check the max ongoing Hunts gamerule."));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Started debug Hunt for " + hunted.getName().getString() + "."), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int debugHuntAdvance(CommandSourceStack source, int minutes) {
		HuntService.debugAdvance(source.getServer(), minutes);
		source.sendSuccess(() -> Component.literal("Advanced Hunt debug time by " + minutes + " minute(s).\n"
			+ HuntService.status(source.getServer())), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int debugHuntRelogin(CommandSourceStack source, ServerPlayer player) {
		HuntService.debugRefreshLoginGrace(player);
		source.sendSuccess(() -> Component.literal("Refreshed Hunt login grace for " + player.getName().getString() + "."), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int debugHuntCompass(CommandSourceStack source, ServerPlayer player) {
		if (!HuntService.requestCompass(source.getServer(), player)) return 0;
		source.sendSuccess(() -> Component.literal("Issued a Hunt Compass to " + player.getName().getString() + "."), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int debugHuntKill(CommandSourceStack source, ServerPlayer hunter, ServerPlayer hunted) {
		HuntService.KillHandling handling =
			HuntService.onHuntedKilled(source.getServer(), hunter, hunted);
		if (!handling.handled()) {
			source.sendFailure(Component.literal("That hunter/hunted pair does not resolve an active Hunt."));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Processed a debug Hunt kill: " + hunter.getName().getString()
			+ " -> " + hunted.getName().getString() + "."), true);
		return Command.SINGLE_SUCCESS;
	}

	private static LiteralArgumentBuilder<CommandSourceStack> questsCommand() {
		return Commands.literal("quests")
			.executes(context -> openQuestMenu(context.getSource()))
			.then(Commands.literal("hunt")
				.then(Commands.literal("accept")
					.executes(context -> questHuntAccept(context.getSource())))
				.then(Commands.literal("status")
					.executes(context -> questHuntStatus(context.getSource())))
				.then(Commands.literal("hunter")
					.then(Commands.literal("join")
						.requires(source -> HuntService.hasActiveHunt(source.getServer()))
						.executes(context -> questHuntHunterJoin(context.getSource()))
						.then(Commands.argument("hunted", StringArgumentType.word())
							.executes(context -> questHuntHunterJoin(context.getSource(),
								StringArgumentType.getString(context, "hunted")))))
					.then(Commands.literal("leave")
						.executes(context -> questHuntHunterLeave(context.getSource()))))
				.then(Commands.literal("compass")
					.executes(context -> questHuntCompass(context.getSource()))));
	}

	private static int openQuestMenu(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		boolean questsEnabled = KarmaGameRules.enabled(player, KarmaGameRules.QUESTS_ENABLED);
		if (!questsEnabled && !KarmaGameRules.enabled(player, KarmaGameRules.DUELS_ENABLED)) {
			source.sendFailure(Component.literal("Quests are disabled by the quests_enabled gamerule."));
			return 0;
		}
		if (BedrockKicUI.openQuests(player, null, () -> player.closeContainer())) return Command.SINGLE_SUCCESS;
		QuestMenu.open(player, null, player::closeContainer);
		return Command.SINGLE_SUCCESS;
	}

	private static int questHuntAccept(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		return HuntService.acceptHunt(source.getServer(), player) ? Command.SINGLE_SUCCESS : 0;
	}

	private static int questHuntStatus(CommandSourceStack source) {
		source.sendSuccess(() -> Component.literal(HuntService.status(source.getServer())), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int questHuntHunterJoin(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		return HuntService.joinHunter(source.getServer(), player) ? Command.SINGLE_SUCCESS : 0;
	}

	private static int questHuntHunterJoin(CommandSourceStack source, String hunted) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		return HuntService.joinHunter(source.getServer(), player, hunted) ? Command.SINGLE_SUCCESS : 0;
	}

	private static int questHuntHunterLeave(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		return HuntService.leaveHunter(source.getServer(), player) ? Command.SINGLE_SUCCESS : 0;
	}

	private static int questHuntCompass(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		return HuntService.requestCompass(source.getServer(), player) ? Command.SINGLE_SUCCESS : 0;
	}

	private static LiteralArgumentBuilder<CommandSourceStack> sigilCommand() {
		return Commands.literal("sigil")
			.then(Commands.literal("add")
				.then(Commands.argument("player", EntityArgument.player())
					.then(Commands.argument("id", StringArgumentType.word())
						.executes(context -> debugSigilAdd(context.getSource(), EntityArgument.getPlayer(context, "player"),
							StringArgumentType.getString(context, "id"), 5))
						.then(Commands.argument("minutes", IntegerArgumentType.integer(1, 10080))
							.executes(context -> debugSigilAdd(context.getSource(), EntityArgument.getPlayer(context, "player"),
								StringArgumentType.getString(context, "id"),
								IntegerArgumentType.getInteger(context, "minutes")))))))
			.then(Commands.literal("remove")
				.then(Commands.argument("player", EntityArgument.player())
					.then(Commands.argument("id", StringArgumentType.word())
						.executes(context -> debugSigilRemove(context.getSource(), EntityArgument.getPlayer(context, "player"),
							StringArgumentType.getString(context, "id"))))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> curseCommand() {
		return Commands.literal("curse")
			.then(curseIdArgument()
				.then(Commands.literal("add")
					.executes(context -> debugCurse(context.getSource(), context.getSource().getPlayerOrException(),
						StringArgumentType.getString(context, "id"), false))
					.then(Commands.argument("player", EntityArgument.player())
						.executes(context -> debugCurse(context.getSource(), EntityArgument.getPlayer(context, "player"),
							StringArgumentType.getString(context, "id"), false))))
				.then(Commands.literal("remove")
					.executes(context -> debugCurse(context.getSource(), context.getSource().getPlayerOrException(),
						StringArgumentType.getString(context, "id"), true))
					.then(Commands.argument("player", EntityArgument.player())
						.executes(context -> debugCurse(context.getSource(), EntityArgument.getPlayer(context, "player"),
							StringArgumentType.getString(context, "id"), true)))))
			// Keep the previous admin syntax available for existing command blocks.
			.then(Commands.literal("add")
				.then(Commands.argument("player", EntityArgument.player())
					.then(curseIdArgument()
						.executes(context -> debugCurse(context.getSource(), EntityArgument.getPlayer(context, "player"),
							StringArgumentType.getString(context, "id"), false)))))
			.then(Commands.literal("remove")
				.then(Commands.argument("player", EntityArgument.player())
					.then(curseIdArgument()
						.executes(context -> debugCurse(context.getSource(), EntityArgument.getPlayer(context, "player"),
							StringArgumentType.getString(context, "id"), true)))));
	}

	private static RequiredArgumentBuilder<CommandSourceStack, String> curseIdArgument() {
		return Commands.argument("id", StringArgumentType.word()).suggests((context, builder) -> {
			for (CurseBlessingRegistry.CurseDefinition curse : CurseBlessingRegistry.curses()) {
				builder.suggest(curse.id());
			}
			return builder.buildFuture();
		});
	}

	private static LiteralArgumentBuilder<CommandSourceStack> blessingCommand() {
		return Commands.literal("blessing")
			.then(Commands.literal("add")
				.then(Commands.argument("player", EntityArgument.player())
					.then(Commands.argument("id", StringArgumentType.word())
						.then(Commands.argument("minutes", IntegerArgumentType.integer(1, 10080))
							.executes(context -> debugBlessing(context.getSource(), EntityArgument.getPlayer(context, "player"),
								StringArgumentType.getString(context, "id"), IntegerArgumentType.getInteger(context, "minutes")))))));
	}

	private static ArgumentBuilder<CommandSourceStack, ?> targetEventNode(String event) {
		return Commands.argument("player", EntityArgument.player())
			.executes(context -> triggerEvent(context.getSource(), EntityArgument.getPlayer(context, "player"), event));
	}

	private static ArgumentBuilder<CommandSourceStack, ?> targetAmountNode(String operation) {
		return Commands.argument("player", EntityArgument.player())
			.then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.0, 1000000.0))
				.executes(context -> debugKarma(
					context.getSource(), EntityArgument.getPlayer(context, "player"),
					DoubleArgumentType.getDouble(context, "amount"), operation)));
	}

	private static ArgumentBuilder<CommandSourceStack, ?> infamyNode(String operation) {
		return Commands.argument("player", EntityArgument.player())
			.then(Commands.argument("amount", IntegerArgumentType.integer(0, Integer.MAX_VALUE))
				.executes(context -> debugInfamy(
					context.getSource(), EntityArgument.getPlayer(context, "player"),
					IntegerArgumentType.getInteger(context, "amount"), operation)));
	}

	private static ArgumentBuilder<CommandSourceStack, ?> reputationNode(String operation) {
		return Commands.argument("player", EntityArgument.player())
			.then(Commands.argument("amount", IntegerArgumentType.integer(0, Integer.MAX_VALUE))
				.executes(context -> debugReputation(
					context.getSource(), EntityArgument.getPlayer(context, "player"),
					IntegerArgumentType.getInteger(context, "amount"), operation)));
	}

	private static ArgumentBuilder<CommandSourceStack, ?> tokenAmountNode(String operation) {
		return Commands.argument("player", EntityArgument.player())
			.then(Commands.argument("amount", IntegerArgumentType.integer(0, Integer.MAX_VALUE))
					.executes(context -> debugTokens(
					context.getSource(), EntityArgument.getPlayer(context, "player"),
					IntegerArgumentType.getInteger(context, "amount"), operation)));
	}

	private static ArgumentBuilder<CommandSourceStack, ?> tokenSourceAmountNode(String sourceCurrency) {
		return Commands.argument("player", EntityArgument.player())
			.then(Commands.argument("amount", IntegerArgumentType.integer(1, Integer.MAX_VALUE))
					.executes(context -> materializeTokens(
					context.getSource(), EntityArgument.getPlayer(context, "player"),
					IntegerArgumentType.getInteger(context, "amount"), sourceCurrency)));
	}

	private static ArgumentBuilder<CommandSourceStack, ?> tokenRedeemAmountNode(SocialCurrencyService.RedemptionTarget target) {
		return Commands.argument("player", EntityArgument.player())
			.then(Commands.argument("amount", IntegerArgumentType.integer(1, Integer.MAX_VALUE))
					.executes(context -> redeemTokens(
					context.getSource(), EntityArgument.getPlayer(context, "player"),
					IntegerArgumentType.getInteger(context, "amount"), target)));
	}

	private static int showBalance(CommandSourceStack source, ServerPlayer player) {
		source.sendSuccess(() -> Component.literal(
			"Karma: " + KarmaService.format(KarmaService.get(player))
				+ "\nReputation: " + SocialCurrencyService.getReputation(player)
				+ "\nInfamy: " + KarmaService.getInfamyPoints(player)
				+ "\nInfluence Tokens: " + SocialTokenService.count(player)), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int openShop(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		if (BedrockKicUI.openShop(player, null, () -> BedrockKicUI.openMain(player))) return Command.SINGLE_SUCCESS;
		player.openMenu(new net.minecraft.world.SimpleMenuProvider((containerId, inventory, ignored) -> new KarmaShopMenu(containerId, inventory, player), Component.literal("Karma Ledger")));
		return Command.SINGLE_SUCCESS;
	}

	private static int showPoints(CommandSourceStack source, ServerPlayer player) {
		source.sendSuccess(() -> Component.literal("Infamy Points: " + KarmaService.getInfamyPoints(player)), false);
		return Command.SINGLE_SUCCESS;
	}


	private static int chooseAlignment(CommandSourceStack source, SocialCurrencyService.Stance stance) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		if (KarmaService.chooseAlignment(player, stance)) {
			source.sendSuccess(() -> Component.literal("Starting alignment chosen: " + stance + " (+/-100 Karma)."), false);
			return Command.SINGLE_SUCCESS;
		}
		source.sendFailure(Component.literal("Alignment can only be chosen once, and only before you have Karma."));
		return 0;
	}

	private static int buy(CommandSourceStack source, String rewardId) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		ShopReward reward = KarmaShopCatalog.find(rewardId);
		if (reward == null) {
			source.sendFailure(Component.literal("Unknown shop reward."));
			return 0;
		}
		KarmaShopCatalog.PurchaseResult result = KarmaShopCatalog.purchase(player, reward);
		if (!result.successful()) {
			source.sendFailure(result.asComponent());
			return 0;
		}
		source.sendSuccess(result::asComponent, false);
		return Command.SINGLE_SUCCESS;
	}

	private static int setBalance(CommandSourceStack source, ServerPlayer player, double karma) {
		int points = (int) Math.round(karma * KarmaService.POINTS_PER_KARMA);
		KarmaService.set(player, points);
		source.sendSuccess(() -> Component.literal("Set " + player.getName().getString() + "'s Karma to " + KarmaService.format(points)), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int debugKarma(CommandSourceStack source, ServerPlayer player, double amount, String operation) {
		int points = (int) Math.round(amount * KarmaService.POINTS_PER_KARMA);
		if (operation.equals("add")) {
			KarmaService.add(player, points, "debug add");
		} else if (operation.equals("remove")) {
			KarmaService.add(player, -points, "debug remove");
		} else {
			KarmaService.set(player, points);
		}
		source.sendSuccess(() -> Component.literal("Debug Karma for " + player.getName().getString() + ": "
			+ KarmaService.format(KarmaService.get(player))), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int debugInfamy(CommandSourceStack source, ServerPlayer player, int amount, String operation) {
		if (operation.equals("add")) {
			KarmaService.addInfamyPoints(player, amount, "debug add");
		} else if (operation.equals("remove")) {
			KarmaService.addInfamyPoints(player, -amount, "debug remove");
		} else {
			KarmaService.setInfamyPoints(player, amount);
		}
		source.sendSuccess(() -> Component.literal("Debug Infamy for " + player.getName().getString() + ": "
			+ KarmaService.getInfamyPoints(player)), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int debugReputation(CommandSourceStack source, ServerPlayer player, int amount, String operation) {
		if (operation.equals("add")) {
			SocialCurrencyService.addReputation(player, amount, "debug add");
		} else if (operation.equals("remove")) {
			SocialCurrencyService.addReputation(player, -amount, "debug remove");
		} else {
			SocialCurrencyService.setReputation(player, amount);
		}
		source.sendSuccess(() -> Component.literal("Debug Reputation for " + player.getName().getString() + ": "
			+ SocialCurrencyService.getReputation(player)), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int debugTokens(CommandSourceStack source, ServerPlayer player, int amount, String operation) {
		if (operation.equals("add")) {
			SocialTokenService.insert(player, amount);
		} else {
			int available = SocialTokenService.count(player);
			if (available < amount) {
				source.sendFailure(Component.literal("Player only has " + available + " Influence Token(s)."));
				return 0;
			}
			SocialTokenService.remove(player, amount);
		}
		source.sendSuccess(() -> Component.literal("Debug Influence Tokens for " + player.getName().getString() + ": "
			+ SocialTokenService.count(player)), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int setKarmaLimit(CommandSourceStack source, ServerPlayer player, int wholeKarma) {
		KarmaService.setCapOverride(player, wholeKarma);
		String message = wholeKarma <= 0
			? "Cleared the title-based Karma cap override for " + player.getName().getString() + "."
			: "Set " + player.getName().getString() + "'s Karma cap to "
				+ KarmaService.format(KarmaService.currentCap(player)) + ".";
		source.sendSuccess(() -> Component.literal(message), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int materializeTokens(CommandSourceStack source, ServerPlayer player, int amount, String sourceCurrency) {
		int created = sourceCurrency.equals("reputation")
			? SocialCurrencyService.materializeReputation(player, amount)
			: SocialCurrencyService.materializeInfamy(player, amount);
		if (created <= 0) {
			source.sendFailure(Component.literal("Materialization failed. Check the source balance and materialization rate."));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Materialized " + amount + " " + sourceCurrency
			+ " into " + created + " Influence Token(s) at "
			+ SocialCurrencyService.materializationPercent() + "%."), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int redeemTokens(CommandSourceStack source, ServerPlayer player, int amount, SocialCurrencyService.RedemptionTarget target) {
		int before = SocialTokenService.count(player);
		int gained = SocialCurrencyService.redeem(player, amount, target);
		if (gained <= 0) {
			source.sendFailure(Component.literal("Token redemption failed. Current Karma stance is "
				+ SocialCurrencyService.getStance(player) + "."));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Redeemed " + amount + " Influence Token(s) into "
			+ gained + " " + target.displayName() + " (tokens: " + before + " -> "
			+ SocialTokenService.count(player) + ")."), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int resetDebug(CommandSourceStack source, ServerPlayer player) {
		QuestService.resetPlayer(source.getServer(), player);
		KarmaService.set(player, 0);
		KarmaService.setInfamyPoints(player, 0);
		SocialCurrencyService.setReputation(player, 0);
		SocialTokenService.remove(player, SocialTokenService.count(player));
		KarmaPlayerState.clear(player, "alignment_choice");
		KarmaPlayerState.clear(player, "trade_count");
		KarmaPlayerState.clear(player, "light_progress");
		KarmaPlayerState.clear(player, "dark_progress");
		CurseBlessingService.removeAllCurses(player);
		CurseBlessingService.removeAllBlessings(player);
		CurseBlessingService.clearCommitment(player);
		source.sendSuccess(() -> Component.literal("Reset Karma, Reputation, Infamy, Influence Tokens, and Quest state for " + player.getName().getString() + "."), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int resetQuestDebug(CommandSourceStack source) {
		QuestService.resetAll(source.getServer());
		source.sendSuccess(() -> Component.literal("Reset all active Hunt/Quest state, cooldowns, pending Hunt rewards, and Hunt debug time."), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int debugCommitment(CommandSourceStack source, ServerPlayer player, String value) {
		CurseBlessingService.Commitment commitment = switch (value.toLowerCase()) {
			case "devotion", "light" -> CurseBlessingService.Commitment.DEVOTION;
			case "devil", "dark" -> CurseBlessingService.Commitment.DEVIL;
			case "clear", "none" -> CurseBlessingService.Commitment.NONE;
			default -> null;
		};
		if (commitment == null) {
			source.sendFailure(Component.literal("Unknown commitment. Use devotion, devil, or clear."));
			return 0;
		}
		if (commitment == CurseBlessingService.Commitment.NONE) {
			CurseBlessingService.clearCommitment(player);
			source.sendSuccess(() -> Component.literal("Cleared permanent commitment for " + player.getName().getString() + "."), true);
			return Command.SINGLE_SUCCESS;
		}
		if (!CurseBlessingService.commit(player, commitment)) {
			source.sendFailure(Component.literal("Could not apply that commitment."));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Applied " + commitment + " to " + player.getName().getString() + "."), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int debugSigilAdd(CommandSourceStack source, ServerPlayer player, String id, int minutes) {
		String normalized = id.toLowerCase(java.util.Locale.ROOT);
		boolean isCurse = CurseBlessingRegistry.curse(normalized) != null;
		boolean isBlessing = CurseBlessingRegistry.blessing(normalized) != null;
		if (!isCurse && !isBlessing) {
			source.sendFailure(Component.literal("Unknown Sigil: " + id));
			return 0;
		}
		boolean added = isCurse
			? CurseBlessingService.addCurse(player, normalized)
			: CurseBlessingService.addBlessing(player, normalized, minutes);
		if (!added) {
			source.sendFailure(Component.literal("Could not add Sigil " + normalized + ". Check its restrictions and slot limits."));
			return 0;
		}
		int stacks = isCurse ? CurseBlessingService.curseStack(player, normalized) : CurseBlessingService.blessingStack(player, normalized);
		source.sendSuccess(() -> Component.literal("Added " + normalized + " Sigil (stacks: " + stacks + ") to " + player.getName().getString() + "."), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int debugSigilRemove(CommandSourceStack source, ServerPlayer player, String id) {
		String normalized = id.toLowerCase(java.util.Locale.ROOT);
		if (!CurseBlessingService.removeSigil(player, normalized)) {
			source.sendFailure(Component.literal("Sigil " + normalized + " is not active on " + player.getName().getString() + "."));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Removed " + normalized + " Sigil from " + player.getName().getString() + "."), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int debugCurse(CommandSourceStack source, ServerPlayer player, String id, boolean remove) {
		String normalizedId = id.toLowerCase(java.util.Locale.ROOT);
		if (CurseBlessingRegistry.curse(normalizedId) == null) {
			source.sendFailure(Component.literal("Unknown curse: " + normalizedId));
			return 0;
		}
		if (remove) {
			int previousStacks = CurseBlessingService.curseStack(player, normalizedId);
			if (previousStacks == 0 || !CurseBlessingService.removeSigil(player, normalizedId)) {
				source.sendFailure(Component.literal(player.getName().getString() + " does not have Curse " + normalizedId + "."));
				return 0;
			}
			source.sendSuccess(() -> Component.literal("Removed Curse " + normalizedId + " (" + previousStacks
				+ " stack(s) cleared; active stacks: " + CurseBlessingService.curseStack(player, normalizedId) + ") from "
				+ player.getName().getString() + "."), true);
			return Command.SINGLE_SUCCESS;
		}
		if (!CurseBlessingService.addCurse(player, normalizedId)) {
			source.sendFailure(Component.literal("Could not add curse " + normalizedId + ". Check commitment and slot limits."));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Added " + normalizedId + " (stacks: "
			+ CurseBlessingService.curseStack(player, normalizedId) + ") to " + player.getName().getString() + "."), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int debugBlessing(CommandSourceStack source, ServerPlayer player, String id, int minutes) {
		if (CurseBlessingRegistry.blessing(id) == null) {
			source.sendFailure(Component.literal("Unknown blessing: " + id));
			return 0;
		}
		if (!CurseBlessingService.addBlessing(player, id, minutes)) {
			source.sendFailure(Component.literal("Could not add blessing " + id + ". Check commitment and slot limits."));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Added " + id + " for " + minutes + " minute(s) (stacks: "
			+ CurseBlessingService.blessingStack(player, id) + ") to " + player.getName().getString() + "."), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int infoDebug(CommandSourceStack source, ServerPlayer player) {
		ShrineManager.ServerPlayerPosition position = new ShrineManager.ServerPlayerPosition(
			player.blockPosition().getX(), player.blockPosition().getZ());
		String shrine = ShrineManager.nearestShrine(position, player.level().getServer())
			.map(site -> site.statueName().isBlank()
				? site.type().displayName() + " at " + site.position().toShortString()
				: site.statueName() + " (" + site.type().displayName() + ") at " + site.position().toShortString())
			.orElse("none");
		String shrineDistance = ShrineManager.nearestShrine(position, player.level().getServer())
			.map(site -> String.format("%.1f", Math.sqrt(
				Math.pow(site.position().getX() - player.getX(), 2)
					+ Math.pow(site.position().getZ() - player.getZ(), 2))))
			.orElse("n/a");
		source.sendSuccess(() -> Component.literal(
			"Debug info for " + player.getName().getString()
				+ ": Karma=" + KarmaService.format(KarmaService.get(player))
				+ ", Reputation=" + SocialCurrencyService.getReputation(player)
				+ ", Infamy=" + KarmaService.getInfamyPoints(player)
				+ ", Tokens=" + SocialTokenService.count(player)
				+ ", Commitment=" + CurseBlessingService.commitment(player)
				+ ", Sigil pool=" + CurseBlessingService.activeSigilSlotCount(player) + "/" + CurseBlessingService.sigilSlotLimit(player)
				+ ", Compass cooldown=" + KarmaService.getShrineCompassCooldownMinutes(player) + "m"
				+ ", Shrine distance=" + shrineDistance
				+ ", nearest shrine=" + shrine), false);
		return Command.SINGLE_SUCCESS;
	}

    private static int checkPlayerStats(CommandSourceStack source, ServerPlayer player) {
        source.sendSuccess(() -> Component.literal("Karma stats for " + player.getName().getString()
            + "\nKarma: " + KarmaService.format(KarmaService.get(player)) + " / +/-" + KarmaService.format(KarmaService.currentCap(player))
            + "\nStance: " + SocialCurrencyService.getStance(player)
            + " | Preferred: " + (KarmaService.alignmentChoice(player) < 0 ? "DARK" : KarmaService.alignmentChoice(player) > 0 ? "LIGHT" : "UNSET")
            + " | Reward alignment: " + KarmaService.rewardAlignment(player)
            + "\nReputation: " + SocialCurrencyService.getReputation(player) + " | Infamy: " + KarmaService.getInfamyPoints(player)
            + " | Physical Influence: " + SocialTokenService.count(player)
            + "\nBonus hearts: " + (KarmaService.healthBonusHalfHearts(KarmaService.get(player), ShrineConfig.current().karmaHeartThreshold, ShrineConfig.current().karmaMaxBonusHalfHearts) / 2.0)
            + " | Decay floor: " + KarmaService.format(KarmaService.naturalDecayFloor(player))
            + "\nWorld stage: " + CommunityProgressionService.stage(player) + " | Commitment: " + CurseBlessingService.commitment(player)
            + "\n" + CurseBlessingService.slotSummary(player) + "\n" + CurseBlessingService.sigilDetails(player)
            + "\nHead drop cooldown: " + KarmaService.getPlayerHeadCooldownMinutes(player) + "m"
            + " | Bonus objectives: " + KarmaGameRules.enabled(player, KarmaGameRules.BONUS_OBJECTIVES_ENABLED)), false);
        return Command.SINGLE_SUCCESS;
    }

	private static int triggerEvent(CommandSourceStack source, ServerPlayer player, String event) {
		int before = KarmaService.get(player);
		int beforeInfamy = KarmaService.getInfamyPoints(player);
		switch (event) {
			case "trade" -> KarmaService.recordVillagerTrade(player);
			case "cure" -> KarmaService.awardAlignedEvent(player, "cure", 0, "DEBUG: cure");
			case "villager_kill" -> KarmaService.awardAlignedEvent(player, "villager_kill", 0, "DEBUG: villager_kill");
			case "ender_dragon" -> KarmaService.awardAlignedEvent(player, "ender_dragon", ShrineConfig.current().socialEnderDragonGain, "DEBUG: ender_dragon");
			case "wither" -> KarmaService.awardAlignedEvent(player, "wither", ShrineConfig.current().socialWitherGain, "DEBUG: wither");
			default -> {
				source.sendFailure(Component.literal("Unknown debug event: " + event));
				return 0;
			}
		}
		int after = KarmaService.get(player);
		int afterInfamy = KarmaService.getInfamyPoints(player);
		KarmaIsCurrency.LOGGER.info("[DEBUG TRIGGER] event={} player={} karma={} -> {} infamy={} -> {}", event, player.getGameProfile().name(), KarmaService.format(before), KarmaService.format(after), beforeInfamy, afterInfamy);
		source.sendSuccess(() -> Component.literal("Triggered " + event + " for " + player.getName().getString() + ". Karma "
			+ KarmaService.format(before) + " -> " + KarmaService.format(after) + ", Infamy " + beforeInfamy + " -> " + afterInfamy), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int triggerShopCycle(CommandSourceStack source) {
		long cycle = KicCycle.triggerDebugCycle();
		source.sendSuccess(() -> Component.literal("Triggered KIC shop cycle " + cycle + ". The next real time boundary will still use the normal schedule."), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int triggerDecay(CommandSourceStack source, ServerPlayer player) {
		int beforeKarma = KarmaService.get(player);
		int beforeRep = SocialCurrencyService.getReputation(player);
		int beforeInfamy = KarmaService.getInfamyPoints(player);
		int karmaDelta = KarmaService.debugDecayKarmaOnce(player);
		// Social decay remains configurable and defaults to zero, so this still exercises
		// that path without applying a second Karma decay pass.
		KarmaService.maintainSocialCurrencies(player);
		source.sendSuccess(() -> Component.literal("Triggered one Karma decay interval for " + player.getName().getString()
			+ ". Karma " + KarmaService.format(beforeKarma) + " -> " + KarmaService.format(KarmaService.get(player))
			+ " (" + (karmaDelta >= 0 ? "+" : "") + KarmaService.format(karmaDelta) + ")"
			+ ", Reputation " + beforeRep + " -> " + SocialCurrencyService.getReputation(player)
			+ ", Infamy " + beforeInfamy + " -> " + KarmaService.getInfamyPoints(player)), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int triggerShrineScan(CommandSourceStack source, ServerPlayer player) {
		ShrineManager.ServerPlayerPosition position = new ShrineManager.ServerPlayerPosition(player.blockPosition().getX(), player.blockPosition().getZ());
		String result = ShrineManager.nearestShrineTarget(position, source.getServer())
			.map(target -> target.generated().map(site -> "generated " + site.displayName() + " at " + site.position().toShortString())
				.orElse("predicted shrine at " + target.position().getX() + ",? ," + target.position().getZ())).orElse("none");
		source.sendSuccess(() -> Component.literal("Shrine debug scan: " + result), true);
		return Command.SINGLE_SUCCESS;
	}


	private static int reload(CommandSourceStack source) {
		ShrineConfig.load();
		QuestConfig.load();
		ShrineManager.reloadConfig();
		StatueNames.load();
		KarmaShopCatalog.reload();
		source.sendSuccess(() -> Component.literal(
			"Reloaded Karma, shrine, quest, statue-name, and shop configuration. Existing shrine locations were not moved."), true);
		return Command.SINGLE_SUCCESS;
	}

}
