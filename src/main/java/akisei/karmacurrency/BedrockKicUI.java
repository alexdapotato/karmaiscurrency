package akisei.karmacurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.form.util.FormBuilder;
import org.geysermc.cumulus.util.FormImage;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

/** Bedrock frontend for KIC. All state changes go through the existing KIC services. */
public final class BedrockKicUI {
    private static final String ICON_KARMA = "textures/items/nether_star.png";
    private static final String ICON_LIGHT = "textures/items/emerald.png";
    private static final String ICON_DARK = "textures/items/wither_skeleton_skull.png";
    private static final String ICON_COMPASS = "textures/items/compass.png";
    private static final String ICON_BACK = "textures/items/arrow.png";
    private static final String ICON_QUESTS = "textures/items/writable_book.png";

    private BedrockKicUI() {}

    public static boolean isBedrock(ServerPlayer player) {
        try {
            if (FloodgateApi.getInstance().isFloodgatePlayer(player.getUUID())) return true;
        } catch (Throwable ignored) {
            // Floodgate is optional on Fabric; Geyser's API is the fallback below.
        }
        return isGeyserPlayer(player);
    }

    /** Opens the KIC dashboard for command-driven or otherwise context-free entry. */
    public static boolean openMain(ServerPlayer player) {
        if (!isBedrock(player)) return false;
        guarded(player, () -> showMain(player, null, null));
        return true;
    }

    /** Opens the same dashboard while retaining the shrine navigation context. */
    public static boolean openMain(ServerPlayer player, ShrineSite site, Runnable onBack) {
        if (!isBedrock(player)) return false;
        guarded(player, () -> showMain(player, site, onBack));
        return true;
    }

    /** Bedrock counterpart to the Java shrine root. */
    public static boolean openShrine(ServerPlayer player, ShrineSite site) {
        if (!isBedrock(player)) return false;
        guarded(player, () -> showMain(player, site, null));
        return true;
    }

    /** Bedrock counterpart to direct shop entry such as /karma shop. */
    public static boolean openShop(ServerPlayer player, ShrineSite site, Runnable onBack) {
        if (!isBedrock(player)) return false;
        if (site != null && site.type() == ShrineType.LIGHT && !CurseBlessingService.canUseLightShrine(player)) {
            tell(player, "The Devil commitment bars you from Light Shrines.");
            return false;
        }
        if (site != null && site.type() == ShrineType.DARK && !CurseBlessingService.canUseDarkShrine(player)) {
            tell(player, "Devotion bars you from Dark Shrines.");
            return false;
        }
        Runnable parent = onBack == null ? () -> showMain(player, null, null) : onBack;
        guarded(player, () -> {
            if (site == null) {
                ShopCategory directCategory = SocialCurrencyService.getStance(player) == SocialCurrencyService.Stance.DARK
                    ? ShopCategory.VILLAIN : ShopCategory.COMMON;
                showShop(player, directCategory, parent);
            } else if (site.type() == ShrineType.NEUTRAL || site.type() == ShrineType.MONUMENT) {
                showShopSections(player, site, parent);
            } else {
                showShop(player, ShopCategory.forShrineType(site.type()), parent);
            }
        });
        return true;
    }

    /** Opens the Quest list, including permanent The Hunt and rotating Trials / Contracts. */
    public static boolean openQuests(ServerPlayer player, ShrineSite site, Runnable onBack) {
        if (!isBedrock(player)) return false;
        guarded(player, () -> showQuests(player, site, onBack));
        return true;
    }

    /** Opens the existing compass targeting behavior as a Cumulus form. */
    public static boolean openShrineCompass(ServerPlayer player, ShrineSite site, Runnable onBack) {
        if (!isBedrock(player)) return false;
        guarded(player, () -> showCompass(player, site));
        return true;
    }

    private static FloodgatePlayer floodgatePlayer(ServerPlayer player) {
        return FloodgateApi.getInstance().getPlayer(player.getUUID());
    }

    private static void send(ServerPlayer player, FormBuilder<?, ?, ?> builder) {
        boolean sent = false;
        try {
            FloodgatePlayer bedrock = floodgatePlayer(player);
            sent = bedrock != null && bedrock.sendForm(builder);
        } catch (Throwable ignored) {
            // Floodgate is optional on Fabric; fall back to Geyser's form API.
        }
        if (!sent) {
            try {
                sent = sendWithGeyser(player, builder);
            } catch (Throwable failure) {
                KarmaIsCurrency.LOGGER.warn("Could not send a KIC Bedrock form to {}", player.getName().getString(), failure);
            }
        }
        if (!sent) tell(player, "KIC could not open that screen. Please try again.");
    }

    private static boolean isGeyserPlayer(ServerPlayer player) {
        try {
            Class<?> apiClass = Class.forName("org.geysermc.geyser.api.GeyserApi");
            Object api = apiClass.getMethod("api").invoke(null);
            return api != null && (boolean) apiClass.getMethod("isBedrockPlayer", java.util.UUID.class)
                .invoke(api, player.getUUID());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean sendWithGeyser(ServerPlayer player, FormBuilder<?, ?, ?> builder) throws Exception {
        Class<?> apiClass = Class.forName("org.geysermc.geyser.api.GeyserApi");
        Object api = apiClass.getMethod("api").invoke(null);
        if (api == null) return false;
        Class<?> builderClass = Class.forName("org.geysermc.cumulus.form.util.FormBuilder");
        return (boolean) apiClass.getMethod("sendForm", java.util.UUID.class, builderClass)
            .invoke(api, player.getUUID(), builder);
    }

    private static void guarded(ServerPlayer player, Runnable action) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        if (!server.isSameThread()) {
            server.execute(() -> guarded(player, action));
            return;
        }
        try {
            action.run();
        } catch (Throwable failure) {
            KarmaIsCurrency.LOGGER.warn("A KIC Bedrock UI action failed for {}", player.getGameProfile().name(), failure);
            tell(player, "KIC could not complete that action. Check your current status and try again.");
        }
    }

    private static void tell(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message));
    }

    private static String karmaCounterLine(ServerPlayer player) {
        return "Karma: " + compactKarma(KarmaService.get(player)) + "/"
            + compactKarma(KarmaService.currentCap(player));
    }

    private static String compactKarma(int points) {
        String formatted = KarmaService.format(points);
        return formatted.endsWith(".00") ? formatted.substring(0, formatted.length() - 3) : formatted;
    }

    private static void simple(ServerPlayer player, String title, String content, List<Choice> choices) {
        SimpleForm.Builder form = SimpleForm.builder().title(title).content(content);
        List<Choice> stableChoices = List.copyOf(choices);
        for (Choice choice : stableChoices) {
            form.button(choice.label(), FormImage.Type.PATH, choice.icon());
        }
        form.validResultHandler(response -> guarded(player, () -> {
            int selected = response.clickedButtonId();
            if (selected >= 0 && selected < stableChoices.size()) {
                stableChoices.get(selected).action().run();
            }
        }));
        send(player, form);
    }

    private static Choice choice(String label, String icon, Runnable action) {
        return new Choice(label, icon, action);
    }

    private static void showMain(ServerPlayer player, ShrineSite site, Runnable onBack) {
        String content = karmaCounterLine(player)
            + "\nAlignment: " + KicUiText.alignmentStatus(player);

        List<Choice> choices = new ArrayList<>();
        Runnable parent = () -> showMain(player, site, onBack);
        if (KarmaGameRules.enabled(player, KarmaGameRules.KARMA_MENU_ENABLED)) {
            boolean neutralShrine = site != null && site.type() == ShrineType.NEUTRAL;
            choices.add(choice(neutralShrine ? "Karma" : "Karma & Sigils", ICON_KARMA,
                () -> showStatus(player, site, parent)));
        }
        if (KarmaGameRules.enabled(player, KarmaGameRules.QUESTS_ENABLED)
                || KarmaGameRules.enabled(player, KarmaGameRules.DUELS_ENABLED)) {
            choices.add(choice("Quests", ICON_QUESTS, () -> showQuests(player, site, parent)));
        }
        if (KarmaGameRules.enabled(player, KarmaGameRules.TITLES_ENABLED)) {
            choices.add(choice("Titles", "textures/items/name_tag.png", () -> showTitles(player, parent)));
        }
        if (KarmaGameRules.enabled(player, KarmaGameRules.KARMA_MENU_ENABLED)) {
            choices.add(choice("Reward breakdowns", ICON_QUESTS, () -> simple(player, "Reward breakdowns",
                String.join("\n\n", KarmaService.rewardBreakdown(player)), List.of(choice("Back", ICON_BACK, parent)))));
        }
        if (KarmaGameRules.enabled(player, KarmaGameRules.SOCIAL_CURRENCY_ENABLED)) {
            choices.add(choice("World Progression", "textures/items/end_crystal.png", () -> showWorldProgression(player, parent)));
        }
        if (site != null) {
            if (KarmaGameRules.enabled(player, KarmaGameRules.SHOP_ENABLED)) {
                if (site.type() == ShrineType.MONUMENT || site.type() == ShrineType.NEUTRAL) {
                    choices.add(choice("Shop", "textures/blocks/chest_front.png",
                        () -> showShopSections(player, site, parent)));
                } else {
                    choices.add(choice("Shop", "textures/blocks/chest_front.png",
                        () -> showShop(player, ShopCategory.forShrineType(site.type()), parent)));
                }
            }
            if (KarmaGameRules.enabled(player, KarmaGameRules.SHRINE_COMPASS_ENABLED)) {
                choices.add(choice("Shrine Compass", ICON_COMPASS, () -> showCompass(player, site)));
            }
        } else if (KarmaGameRules.enabled(player, KarmaGameRules.SHRINES_ENABLED)) {
            choices.add(choice("Shrine", ICON_COMPASS, () -> showShrineChooser(player, parent)));
        }
        if (onBack != null) choices.add(choice("Back", ICON_BACK, onBack));
        choices.add(choice("Close", ICON_DARK, () -> {}));
        String title = site == null ? "Karma Is Currency" : "Karma Is Currency — " + site.displayName();
        simple(player, title, content, choices);
    }

    private static void showStatus(ServerPlayer player, ShrineSite site, Runnable onBack) {
        if (!KarmaGameRules.enabled(player, KarmaGameRules.KARMA_MENU_ENABLED)) {
            tell(player, "The Karma menu is disabled for this world.");
            onBack.run();
            return;
        }

        String content = karmaCounterLine(player)
            + "\nAlignment: " + KicUiText.alignmentStatus(player)
            + "\nReputation: " + SocialCurrencyService.getReputation(player)
            + "\nInfamy: " + KarmaService.getInfamyPoints(player)
            + "\nPhysical Influence: " + SocialTokenService.count(player);

        List<Choice> choices = new ArrayList<>();
        boolean sigilsEnabled = KarmaGameRules.enabled(player, KarmaGameRules.SIGILS_ENABLED)
            && (site == null || site.type() != ShrineType.NEUTRAL);
        boolean showBlessingTab = site == null || site.type() == ShrineType.LIGHT || site.type() == ShrineType.MONUMENT;
        boolean showCurseTab = site == null || site.type() == ShrineType.DARK || site.type() == ShrineType.MONUMENT;
        if (sigilsEnabled && showBlessingTab && KarmaGameRules.enabled(player, KarmaGameRules.BLESSINGS_ENABLED)) {
            choices.add(choice("Blessings", ICON_LIGHT, () -> showBlessings(player, site,
                () -> showStatus(player, site, onBack))));
        }
        if (sigilsEnabled && showCurseTab && KarmaGameRules.enabled(player, KarmaGameRules.CURSES_ENABLED)
                && CurseBlessingService.canUseDarkShrine(player)) {
            choices.add(choice("Curses", ICON_DARK, () -> showCurses(player, site,
                () -> showStatus(player, site, onBack))));
        }
        if (KarmaGameRules.enabled(player, KarmaGameRules.TITLES_ENABLED)) {
            choices.add(choice("Titles", "textures/items/name_tag.png", () -> showTitles(player,
                () -> showStatus(player, site, onBack))));
        }
        if (KarmaGameRules.enabled(player, KarmaGameRules.SOCIAL_CURRENCY_ENABLED)) {
            choices.add(choice("Social Currency", ICON_LIGHT, () -> showSocialCurrency(player,
                () -> showStatus(player, site, onBack))));
        }
        choices.add(choice("Back", ICON_BACK, onBack));
        simple(player, "Karma Status", content, choices);
    }

    private static void showQuests(ServerPlayer player, ShrineSite site, Runnable onBack) {
        boolean questsEnabled = KarmaGameRules.enabled(player, KarmaGameRules.QUESTS_ENABLED);
        boolean trialsEnabled = questsEnabled && KarmaGameRules.enabled(player, KarmaGameRules.TRIALS_ENABLED);
        boolean contractsEnabled = questsEnabled && KarmaGameRules.enabled(player, KarmaGameRules.CONTRACTS_ENABLED);
        boolean duelsEnabled = false;
        boolean showTrials = trialsEnabled;
        boolean showContracts = contractsEnabled;

        List<Choice> choices = new ArrayList<>();
        List<QuestService.QuestId> active = QuestService.active(player);
        choices.add(choice("Active Quest: " + active.size() + "/" + QuestService.MAX_ACTIVE_QUESTS,
            ICON_QUESTS, () -> showQuests(player, site, onBack)));
        for (QuestService.QuestId quest : active) {
            choices.add(choice(quest.displayName(), questBedrockIcon(quest), () -> {
                tell(player, QuestService.isActive(player, quest)
                    ? QuestService.status(player, quest) : "That quest is no longer active.");
                showQuests(player, site, onBack);
            }));
        }
        if (!active.isEmpty() && QuestRevamp.submission(active.getFirst())) {
            choices.add(choice("Review Submission", ICON_QUESTS, () -> {
                if(!QuestRevamp.submit(player,false)){showQuests(player,site,onBack);return;}
                QuestService.QuestId accepted=active.getFirst();
                long acceptedRun=QuestRevamp.get(player,"started");
                simple(player,"Confirm Quest Submission",QuestService.objectiveDescription(player,accepted)+"\n"+QuestService.rewardDescription(player,accepted)+"\nThe full bundle will be consumed.",
                    List.of(choice("Confirm Submission",ICON_QUESTS,()->{if(QuestService.activeQuest(player)==accepted && QuestRevamp.get(player,"started")==acceptedRun)QuestRevamp.submit(player,true);showQuests(player,site,onBack);}),
                        choice("Back",ICON_BACK,()->showQuests(player,site,onBack))));
            }));
        }
        if (!active.isEmpty()) {
            choices.add(choice("Cancel Active Quest", ICON_BACK, () -> {
                QuestService.cancelActive(player);
                showQuests(player, site, onBack);
            }));
        }

        if (showTrials) {
            List<QuestService.QuestId> trials = QuestService.currentTrials(player);
            for (QuestService.QuestId quest : trials) {
                addBedrockQuestChoice(player, site, onBack, choices, quest);
            }
        }
        if (showContracts) {
            List<QuestService.QuestId> contracts = QuestService.currentContracts(player);
            for (QuestService.QuestId quest : contracts) {
                addBedrockQuestChoice(player, site, onBack, choices, quest);
            }
        }
        if (duelsEnabled) addBedrockQuestChoice(player, site, onBack, choices, QuestService.QuestId.DUEL);
        if (questsEnabled) {
            addBedrockQuestChoice(player, site, onBack, choices, QuestService.QuestId.HUNT);
        } else {
            choices.add(choice("The Hunt [DISABLED]", ICON_BACK, () -> {
                tell(player, "The Hunt is disabled in this world.");
                showQuests(player, site, onBack);
            }));
        }
        ServerPlayer duelChallenger = QuestService.pendingDuelChallenger(player);
        if (duelChallenger != null) {
            choices.add(choice("Duel invitation: " + duelChallenger.getName().getString(), ICON_DARK,
                () -> showDuelConsent(player, site, onBack, duelChallenger)));
        }

        choices.add(choice("Back", ICON_BACK, onBack));
        StringBuilder content = new StringBuilder(questsEnabled
            ? "Shared KIC cycle. " + QuestService.nextCycleStatus()
            : "Quests are disabled in this world.\n")
            .append("\nActive Quest: ");
        if (active.isEmpty()) content.append("None (0/1).");
        else content.append(active.getFirst().displayName()).append(" (1/1).\n").append(QuestService.status(player, active.getFirst()));
        content.append("\n").append(QuestService.generalCooldownStatus(player))
            .append("\n").append(QuestService.perQuestCooldownStatus(player));
        SocialCurrencyService.Stance stance = SocialCurrencyService.getStance(player);
        if (stance == SocialCurrencyService.Stance.LIGHT && contractsEnabled) {
            content.append("\nContracts are unavailable for Light Aligned players.");
        }
        if (stance == SocialCurrencyService.Stance.DARK && trialsEnabled) {
            content.append("\nTrials are unavailable for Dark Aligned players. Eligible Dark Hunters may still join The Hunt.");
        }
        simple(player, "Quests", content.toString()
            + "\nMaximum active quests: " + QuestService.MAX_ACTIVE_QUESTS
            + "\nThe current pool is shared by the server.", choices);
    }

    private static void addBedrockQuestChoice(ServerPlayer player, ShrineSite site, Runnable onBack,
            List<Choice> choices, QuestService.QuestId quest) {
        String label = (quest == QuestService.QuestId.HUNT ? "The Hunt [PERMANENT]" : quest.displayName())
            + questStatusSuffix(player, quest);
        choices.add(choice(label, questBedrockIcon(quest), () -> {
            if (quest == QuestService.QuestId.HUNT) {
                showHuntChoice(player, site, onBack);
                return;
            }
            if (QuestService.isActive(player, quest)) {
                tell(player, QuestService.status(player, quest));
                showQuests(player, site, onBack);
                return;
            }
            String alignmentBlock = QuestService.alignmentBlockReason(player, quest);
            if (!alignmentBlock.isBlank()) {
                tell(player, alignmentBlock);
                showQuests(player, site, onBack);
                return;
            }
            showQuestOptions(player, site, onBack, quest);
        }));
    }

    private static void showQuestOptions(ServerPlayer player, ShrineSite site, Runnable onBack, QuestService.QuestId quest) {
        List<Choice> options = new ArrayList<>();
        options.add(choice("Review / Accept", ICON_QUESTS, () -> showQuestConfirmation(player, site, onBack, quest)));
        simple(player, quest.displayName(), QuestService.objectiveDescription(player, quest)
            + "\n\n" + QuestService.cooldownRuleDescription(quest), options);
    }

    private static void showQuestConfirmation(ServerPlayer player, ShrineSite site, Runnable onBack,
            QuestService.QuestId quest) {
        String currentState = QuestService.canAccept(player, quest)
            ? "Ready to accept."
            : QuestService.acceptanceBlockReason(player, quest);
        StringBuilder content = new StringBuilder()
            .append("Type: ").append(QuestRevamp.side(player,quest) > 0 ? "Trial" : "Contract")
            .append("\n\nObjective:\n").append(QuestService.objectiveDescription(player, quest))
            .append("\n\nBonus:\n").append(QuestService.bonusDescription(player, quest))
            .append("\n\nReward:\n").append(QuestService.rewardDescription(player, quest))
            .append("\n\n").append(QuestService.durationDescription(quest))
            .append("\n").append(QuestService.cooldownRuleDescription(quest));
        String activeCooldowns = QuestService.cooldownStatus(player, quest);
        content.append("\n").append(activeCooldowns.isBlank() ? "Cooldown: Ready" : activeCooldowns.replace(" | ", "\n"));
        content.append("\n\nCurrent status: ").append(currentState);

        ModalForm.Builder form = ModalForm.builder().title("Confirm " + quest.displayName() + "?")
            .content(content.toString()).button1("Cancel / Back").button2("Confirm / Accept");
        form.validResultHandler(response -> guarded(player, () -> {
            if (response.clickedFirst()) {
                showQuests(player, site, onBack);
                return;
            }
            String alignmentBlock = QuestService.alignmentBlockReason(player, quest);
            if (!alignmentBlock.isBlank()) {
                tell(player, alignmentBlock);
                showQuests(player, site, onBack);
                return;
            }
            if (quest == QuestService.QuestId.HUNT) {
                if (!QuestService.canAccept(player, quest)) {
                    tell(player, QuestService.acceptanceBlockReason(player, quest));
                    showQuests(player, site, onBack);
                } else {
                    showHuntChoice(player, site, onBack);
                }
                return;
            }
            QuestService.accept(player, quest);
            showQuests(player, site, onBack);
        }));
        send(player, form);
    }

    private static void showHuntChoice(ServerPlayer player, ShrineSite site, Runnable onBack) {
        if (!KarmaGameRules.enabled(player, KarmaGameRules.QUESTS_ENABLED)) {
            tell(player, "Quests are disabled by the quests_enabled gamerule.");
            showQuests(player, site, onBack);
            return;
        }
        List<Choice> choices = new ArrayList<>();
        boolean light = QuestService.canBecomeHunted(player);
        boolean dark = QuestService.canJoinHunt(player);
        if (light) {
            choices.add(choice("Become the Hunted", ICON_LIGHT, () -> {
                if (!HuntService.acceptHunt(player.level().getServer(), player)) {
                    tell(player, QuestService.acceptanceBlockReason(player, QuestService.QuestId.HUNT));
                }
                showQuests(player, site, onBack);
            }));
        }
        if (dark) {
            List<HuntState.HuntRecord> hunts = QuestService.availableHuntsForHunter(player);
            for (HuntState.HuntRecord hunt : hunts) {
                choices.add(choice("Join: " + hunt.huntedName(), ICON_COMPASS, () -> {
                    QuestService.joinHunt(player, hunt.huntedName());
                    showQuests(player, site, onBack);
                }));
            }
            if (hunts.isEmpty()) {
                choices.add(choice("No Active Hunts", ICON_COMPASS, () -> showQuests(player, site, onBack)));
            }
        }
        if (!light && !dark) {
            choices.add(choice("Hunt unavailable", ICON_COMPASS, () -> {
                tell(player, QuestService.acceptanceBlockReason(player, QuestService.QuestId.HUNT));
                showQuests(player, site, onBack);
            }));
        }
        if (QuestService.isActive(player, QuestService.QuestId.HUNT)) {
            choices.add(choice("Cancel The Hunt", ICON_BACK, () -> {
                HuntService.cancelParticipation(player.level().getServer(), player);
                showQuests(player, site, onBack);
            }));
        }
        choices.add(choice("Back", ICON_BACK, () -> showQuests(player, site, onBack)));
        String status = QuestService.isActive(player, QuestService.QuestId.HUNT)
            ? "\n\n" + QuestService.status(player, QuestService.QuestId.HUNT) : "";
        simple(player, "The Hunt", "Choose how to participate."
            + status + "\n\n" + QuestService.cooldownRuleDescription(QuestService.QuestId.HUNT)
            + "\n" + QuestService.cooldownStatus(player, QuestService.QuestId.HUNT), choices);
    }

    private static void showDuelConsent(ServerPlayer player, ShrineSite site, Runnable onBack, ServerPlayer challenger) {
        if (QuestService.pendingDuelChallenger(player) != challenger) {
            tell(player, "That Duel invitation has expired.");
            showQuests(player, site, onBack);
            return;
        }
        String content = "Duel invitation from " + challenger.getName().getString()
            + "\n\nObjective:\n" + QuestService.objectiveDescription(challenger, QuestService.QuestId.DUEL)
            + "\n\nReward:\n" + QuestService.rewardDescription(challenger, QuestService.QuestId.DUEL)
            + "\n\nOnly the designated participants count. The winner receives the reward."
            + "\n\nYour current state: " + QuestService.activeCount(player) + "/1 active quests."
            + "\n" + QuestService.generalCooldownStatus(player);
        ModalForm.Builder form = ModalForm.builder().title("Duel Consent")
            .content(content).button1("Decline / Back").button2("Accept Duel")
            .validResultHandler(response -> guarded(player, () -> {
                if (response.clickedFirst()) QuestService.declineDuel(player, challenger);
                else QuestService.acceptDuel(player, challenger);
                showQuests(player, site, onBack);
            }));
        send(player, form);
    }

    private static String questBedrockIcon(QuestService.QuestId quest) {
        return switch (quest) {
            case ABUNDANCE, MERCY, COURAGE, OFFERING -> ICON_LIGHT;
            case HUNT, SURVIVOR, PILGRIMAGE, CHALLENGER -> ICON_COMPASS;
            case BLOOD_PRICE, EXECUTIONER, COLLECTOR, WARDEN, DUEL -> ICON_DARK;
        };
    }

    private static String questStatusSuffix(ServerPlayer player, QuestService.QuestId quest) {
        if (QuestService.isActive(player, quest)) return " [ACTIVE]";
        List<String> state = new ArrayList<>();
        String cooldown = QuestService.cooldownStatus(player, quest);
        if (!cooldown.isBlank()) state.add(cooldown);
        if (!QuestService.alignmentBlockReason(player, quest).isBlank()) state.add("UNAVAILABLE");
        if (quest != QuestService.QuestId.HUNT
                && QuestService.activeCount(player) >= QuestService.MAX_ACTIVE_QUESTS) state.add("1/1");
        if (state.isEmpty() && !QuestService.canAccept(player, quest)
                && !(quest == QuestService.QuestId.HUNT
                    && (QuestService.canBecomeHunted(player) || QuestService.canJoinHunt(player)))) state.add("LOCKED");
        return state.isEmpty() ? "" : " [" + String.join("; ", state) + "]";
    }

    private static void showBlessings(ServerPlayer player, ShrineSite site, Runnable onBack) {
        if (site != null && site.type() == ShrineType.DARK) {
            tell(player, "Dark Shrines provide Curses, not Blessings.");
            onBack.run();
            return;
        }
        if (site != null && site.type() == ShrineType.NEUTRAL) {
            tell(player, "Neutral Shrines do not provide Sigil functionality.");
            onBack.run();
            return;
        }
        if (!KarmaGameRules.enabled(player, KarmaGameRules.SIGILS_ENABLED)
                || !KarmaGameRules.enabled(player, KarmaGameRules.BLESSINGS_ENABLED)) {
            tell(player, "Blessings are disabled for this world.");
            onBack.run();
            return;
        }
        int stage = CommunityProgressionService.stage(player);
        List<CurseBlessingRegistry.BlessingDefinition> blessings = new ArrayList<>(CurseBlessingRegistry.liveBlessings(stage));
        for (String activeId : CurseBlessingService.activeBlessings(player)) {
            CurseBlessingRegistry.BlessingDefinition active = CurseBlessingRegistry.blessing(activeId);
            if (active != null && blessings.stream().noneMatch(current -> current.id().equals(activeId))) blessings.add(active);
        }
        List<Choice> choices = new ArrayList<>();
        for (CurseBlessingRegistry.BlessingDefinition blessing : blessings) {
            int stacks = CurseBlessingService.blessingStack(player, blessing.id());
            String suffix = stacks > 0 ? "  [Active " + stacks + "/" + blessing.maxStacks() + "]" : "";
            choices.add(choice(blessing.name() + suffix, blessingIcon(blessing.id()),
                () -> showSigilDetails(player, true, blessing.id(), site,
                    () -> showBlessings(player, site, onBack))));
        }
        choices.add(choice("Back", ICON_BACK, onBack));
        simple(player, "Blessings", KicCycle.statusLine() + "\nCurrent rotation", choices);
    }

    private static void showCurses(ServerPlayer player, ShrineSite site, Runnable onBack) {
        if (site != null && site.type() == ShrineType.LIGHT) {
            tell(player, "Light Shrines provide Blessings, not Curses.");
            onBack.run();
            return;
        }
        if (site != null && site.type() == ShrineType.NEUTRAL) {
            tell(player, "Neutral Shrines do not provide Sigil functionality.");
            onBack.run();
            return;
        }
        if (!KarmaGameRules.enabled(player, KarmaGameRules.SIGILS_ENABLED)
                || !KarmaGameRules.enabled(player, KarmaGameRules.CURSES_ENABLED)) {
            tell(player, "Curses are disabled for this world.");
            onBack.run();
            return;
        }
        if (!CurseBlessingService.canUseDarkShrine(player)) {
            tell(player, "Devotion prevents access to Curses.");
            onBack.run();
            return;
        }
        int stage = CommunityProgressionService.stage(player);
        List<CurseBlessingRegistry.CurseDefinition> curses = new ArrayList<>(CurseBlessingRegistry.liveCurses(stage));
        for (String activeId : CurseBlessingService.activeCurses(player)) {
            CurseBlessingRegistry.CurseDefinition active = CurseBlessingRegistry.curse(activeId);
            if (active != null && curses.stream().noneMatch(current -> current.id().equals(activeId))) curses.add(active);
        }
        List<Choice> choices = new ArrayList<>();
        choices.add(choice("Curse Slots  " + CurseBlessingService.activeCurseSlotCount(player) + "/"
                + CurseBlessingService.curseSlotLimit(player), ICON_DARK,
            () -> showCurseSlots(player, site, () -> showCurses(player, site, onBack))));
        for (CurseBlessingRegistry.CurseDefinition curse : curses) {
            int stacks = CurseBlessingService.curseStack(player, curse.id());
            String suffix = stacks > 0 ? "  [Active " + stacks + "/" + curse.maxStacks() + "]" : "";
            choices.add(choice(curse.name() + suffix, curseIcon(curse.id()),
                () -> showSigilDetails(player, false, curse.id(), site,
                    () -> showCurses(player, site, onBack))));
        }
        choices.add(choice("Back", ICON_BACK, onBack));
        simple(player, "Curses", KicCycle.statusLine() + "\nCurrent rotation", choices);
    }

    private static void showCurseSlots(ServerPlayer player, ShrineSite site, Runnable onBack) {
        if (!CurseBlessingService.canUseDarkShrine(player)) {
            tell(player, "Devotion prevents access to Curse Slots.");
            onBack.run();
            return;
        }
        int capacity = CurseBlessingService.curseSlotLimit(player);
        int active = CurseBlessingService.activeCurseSlotCount(player);
        List<Choice> choices = new ArrayList<>();
        List<String> activeCurses = CurseBlessingService.activeCurses(player);
        for (int slot = 0; slot < Math.max(capacity, activeCurses.size()); slot++) {
            String id = slot < activeCurses.size() ? activeCurses.get(slot) : null;
            if (id != null) {
                CurseBlessingRegistry.CurseDefinition definition = CurseBlessingRegistry.curse(id);
                int stacks = CurseBlessingService.curseStack(player, id);
                choices.add(choice("Slot " + (slot + 1) + ": " + (definition == null ? id : definition.name())
                    + " x" + stacks, curseIcon(id), () -> showSigilDetails(player, false, id, site, () -> showCurseSlots(player, site, onBack))));
            } else {
                choices.add(choice("Slot " + (slot + 1) + ": Empty", ICON_DARK, () -> {}));
            }
        }
        choices.add(choice("Back", ICON_BACK, onBack));
        simple(player, "Curse Slots", "Active Curse Slots: " + active + "/" + capacity
            + "\n" + CurseBlessingService.slotSummary(player) + "\n" + CurseBlessingService.sigilDetails(player), choices);
    }

    private static void showSigilDetails(ServerPlayer player, boolean blessing, String id,
            ShrineSite site, Runnable onBack) {
        boolean currentlyLive = isCurrentlyLive(player, blessing, id);
        int existingStacks = sigilStack(player, blessing, id);
        if (!currentlyLive && existingStacks <= 0) {
            tell(player, "This sigil is no longer available.");
            onBack.run();
            return;
        }

        String name;
        int maxStacks;
        CurseBlessingRegistry.SigilClassification classification;
        CurseBlessingRegistry.SigilDuration duration;
        List<String> requirements;
        String effect;
        if (blessing) {
            CurseBlessingRegistry.BlessingDefinition definition = CurseBlessingRegistry.blessing(id);
            name = definition.name();
            maxStacks = definition.maxStacks();
            classification = definition.classification();
            duration = definition.duration();
            requirements = definition.requirements().stream().filter(line -> !line.startsWith("Cost:")).toList();
            effect = definition.effectDescription();
        } else {
            CurseBlessingRegistry.CurseDefinition definition = CurseBlessingRegistry.curse(id);
            name = definition.name();
            maxStacks = definition.maxStacks();
            classification = definition.classification();
            duration = definition.duration();
            requirements = definition.requirements().stream().filter(line -> !line.startsWith("Cost:")).toList();
            effect = definition.effectDescription();
        }

        int stacks = sigilStack(player, blessing, id);
        int remaining = blessing ? CurseBlessingService.blessingMinutesRemaining(player, id) : 0;
        String activationState = stacks > 0 ? "Active" : "Inactive";
        String currentBlocker = activationBlocker(player, blessing, id, maxStacks);
        int configuredDuration = blessing ? CurseBlessingRegistry.blessing(id).durationMinutes() : 0;
        String durationText = duration == CurseBlessingRegistry.SigilDuration.PERMANENT
            ? "Permanent" : configuredDuration + " minutes per activation";
        String requirementText = requirements.isEmpty() ? "No requirements are registered."
            : String.join("\n", requirements.stream().filter(line -> !line.startsWith("Cost:")).toList()) + "\n" + String.join("\n", KicUiText.costLines(player, blessing, id));

        if (!currentlyLive && existingStacks > 0) currentBlocker = "This Sigil rotated out; existing stacks remain active, but it cannot be activated again until it returns.";
        final String blocker = currentBlocker;
        List<String> actions = new ArrayList<>();
        if (currentBlocker == null) actions.add("Activate");
        actions.add("Requirements");
        if (CurseBlessingService.canReleaseSigil(player, blessing, id)) actions.add("Remove all stacks (no refund)");
        actions.add("Back");
        CustomForm.Builder form = CustomForm.builder()
            .title(name)
            .label("Type: " + (blessing ? "Blessing" : "Curse"))
            .label("Classification: " + classification.displayName())
            .label("Status: " + activationState + " (" + stacks + "/" + maxStacks + " stacks)")
            .label(CurseBlessingService.slotSummary(player) + "\n" + CurseBlessingService.sigilDetails(player))
            .label(CurseBlessingService.activationPreview(player, blessing, id))
            .label("Duration: " + durationText + (blessing && remaining > 0 ? "\nRemaining: " + KicUiText.durationSeconds(remaining * 60L) : ""))
            .label(KicUiText.projectedEffect(player, blessing, id, effect))
            .label("Requirements\n" + requirementText);
        if (currentBlocker != null) form.label("Unavailable: " + currentBlocker);
        form.dropdown("Action", actions, 0);
        form.validResultHandler(response -> guarded(player, () -> {
            int selected = response.asDropdown();
            if (selected < 0 || selected >= actions.size()) return;
            switch (actions.get(selected)) {
                case "Activate" -> confirmSigilActivation(player, blessing, id, site, onBack);
                case "Requirements" -> showSigilRequirements(player, blessing, id, site,
                    () -> showSigilDetails(player, blessing, id, site, onBack));
                case "Remove all stacks (no refund)" -> {
                    ModalForm.Builder confirm = ModalForm.builder().title("Remove " + name + "?")
                        .content("Remove all stacks of this Sigil and free its slots. No items or Influence are refunded. Commitments cannot be removed here.")
                        .button1("Cancel").button2("Remove all stacks");
                    confirm.validResultHandler(answer -> guarded(player, () -> {
                        if (!answer.clickedFirst()) {
                            tell(player, CurseBlessingService.releaseSigil(player, blessing, id)
                                ? "Sigil removed. No refund." : "This Sigil cannot be removed here.");
                        }
                        onBack.run();
                    }));
                    send(player, confirm);
                }
                default -> onBack.run();
            }
        }));
        send(player, form);
    }

    private static void showSigilRequirements(ServerPlayer player, boolean blessing, String id,
            ShrineSite site, Runnable onBack) {
        if (!isCurrentlyLive(player, blessing, id)) {
            tell(player, "This sigil is no longer available.");
            onBack.run();
            return;
        }
        String name;
        int maxStacks;
        CurseBlessingRegistry.SigilDuration duration;
        List<String> requirements;
        if (blessing) {
            var definition = CurseBlessingRegistry.blessing(id);
            name = definition.name();
            maxStacks = definition.maxStacks();
            duration = definition.duration();
            requirements = definition.requirements().stream().filter(line -> !line.startsWith("Cost:")).toList();
        } else {
            var definition = CurseBlessingRegistry.curse(id);
            name = definition.name();
            maxStacks = definition.maxStacks();
            duration = definition.duration();
            requirements = definition.requirements().stream().filter(line -> !line.startsWith("Cost:")).toList();
        }
        int configuredDuration = blessing ? CurseBlessingRegistry.blessing(id).durationMinutes() : 0;
        String durationText = duration == CurseBlessingRegistry.SigilDuration.PERMANENT
            ? "Permanent" : configuredDuration + " minutes per activation";
        CustomForm.Builder form = CustomForm.builder()
            .title(name + " — Requirements")
            .label("Requirements\n" + (requirements.isEmpty() ? "No requirements are registered." : String.join("\n", requirements.stream().filter(line -> !line.startsWith("Cost:")).toList())))
            .label(String.join("\n", KicUiText.costLines(player, blessing, id)))
            .label("Maximum stacks: " + maxStacks)
            .label("Duration: " + durationText)
            .dropdown("Navigation", "Back");
        form.validResultHandler(response -> guarded(player, onBack));
        send(player, form);
    }

    private static void confirmSigilActivation(ServerPlayer player, boolean blessing, String id,
            ShrineSite site, Runnable onBack) {
        if (!isCurrentlyLive(player, blessing, id)) {
            tell(player, "This sigil is no longer available.");
            onBack.run();
            return;
        }
        int maxStacks = blessing ? CurseBlessingRegistry.blessing(id).maxStacks()
            : CurseBlessingRegistry.curse(id).maxStacks();
        String blocker = activationBlocker(player, blessing, id, maxStacks);
        if (blocker != null) {
            tell(player, blocker);
            showSigilDetails(player, blessing, id, site, onBack);
            return;
        }

        String name = blessing ? CurseBlessingRegistry.blessing(id).name() : CurseBlessingRegistry.curse(id).name();
        String effect = blessing ? CurseBlessingRegistry.blessing(id).effectDescription()
            : CurseBlessingRegistry.curse(id).effectDescription();
        List<String> requirements = blessing ? CurseBlessingRegistry.blessing(id).requirements()
            : CurseBlessingRegistry.curse(id).requirements();
        String materialCost = blessing
            ? CurseBlessingRegistry.costDescription(CurseBlessingService.activationCosts(player, true, id))
            : CurseBlessingRegistry.costDescription(CurseBlessingService.activationCosts(player, false, id));
        String content = KicUiText.commitmentWarning(blessing, id) + "\n\n" + String.join("\n", KicUiText.costLines(player, blessing, id)) + "\n\nEffect:\n" + effect + "\n\nCost:\n" + materialCost + "\n\nRequirements:\n" + String.join("\n", requirements.stream().filter(line -> !line.startsWith("Cost:")).toList())
            + "\n\nConfirm to activate one stack.";
        ModalForm.Builder form = ModalForm.builder()
            .title("Activate " + name + "?")
            .content(content)
            .button1("Cancel")
            .button2("Activate");
        form.validResultHandler(response -> guarded(player, () -> {
            if (response.clickedFirst()) {
                showSigilDetails(player, blessing, id, site, onBack);
                return;
            }
            activateSigil(player, blessing, id, site, onBack);
        }));
        send(player, form);
    }

    private static void activateSigil(ServerPlayer player, boolean blessing, String id,
            ShrineSite site, Runnable onBack) {
        if (!isCurrentlyLive(player, blessing, id)) {
            tell(player, "This sigil is no longer available.");
            onBack.run();
            return;
        }
        int maxStacks = blessing ? CurseBlessingRegistry.blessing(id).maxStacks()
            : CurseBlessingRegistry.curse(id).maxStacks();
        String blocker = activationBlocker(player, blessing, id, maxStacks);
        if (blocker != null) {
            tell(player, blocker);
            showSigilDetails(player, blessing, id, site, onBack);
            return;
        }

        boolean activated = blessing
            ? CurseBlessingService.addBlessing(player, id, 5)
            : CurseBlessingService.addCurse(player, id);
        String name = blessing ? CurseBlessingRegistry.blessing(id).name() : CurseBlessingRegistry.curse(id).name();
        if (activated) { KicUiText.success(player); tell(player, name + " Sigil activated."); }
        else tell(player, name + " could not be activated. Check its current requirements and restrictions.");
        showSigilDetails(player, blessing, id, site, onBack);
    }

    private static boolean isCurrentlyLive(ServerPlayer player, boolean blessing, String id) {
        if (blessing) {
            return CurseBlessingRegistry.blessing(id) != null
                && (CurseBlessingRegistry.isBlessingAvailable(id, CommunityProgressionService.stage(player))
                    || CurseBlessingService.blessingStack(player, id) > 0);
        }
        return CurseBlessingRegistry.curse(id) != null
            && (CurseBlessingRegistry.isCurseAvailable(id, CommunityProgressionService.stage(player))
                || CurseBlessingService.curseStack(player, id) > 0);
    }

    private static int sigilStack(ServerPlayer player, boolean blessing, String id) {
        return blessing ? CurseBlessingService.blessingStack(player, id) : CurseBlessingService.curseStack(player, id);
    }

    private static String activationBlocker(ServerPlayer player, boolean blessing, String id, int maxStacks) {
        return CurseBlessingService.activationBlocker(player, blessing, id, maxStacks);
    }

    private static String blessingIcon(String id) {
        return switch (id) {
            case "watcher" -> "textures/items/ender_eye.png";
            case "swiftness" -> "textures/items/sugar.png";
            case "protection" -> "textures/items/iron_chestplate.png";
            case "depth", "deep" -> "textures/items/heartofthesea.png";
            case "devotion" -> "textures/items/netherite_ingot.png";
            case "smite" -> ICON_KARMA;
            case "fortune", "prosperity" -> "textures/items/gold_pickaxe.png";
            case "miner" -> "textures/items/diamond_pickaxe.png";
            case "mountain" -> "textures/blocks/stone.png";
            case "night" -> "textures/items/phantom_membrane.png";
            case "smith", "mender" -> "textures/items/anvil.png";
            case "vanguard" -> "textures/items/shield.png";
            case "abundance" -> "textures/blocks/chest_front.png";
            case "hunter" -> "textures/items/bow_standby.png";
            case "haste" -> "textures/items/gold_pickaxe.png";
            case "harvest" -> "textures/items/wheat.png";
            case "aegis" -> "textures/items/shield.png";
            default -> "textures/items/emerald.png";
        };
    }

    private static String curseIcon(String id) {
        return switch (id) {
            case "justice" -> "textures/items/iron_sword.png";
            case "devil", "executioner" -> ICON_DARK;
            case "hanged_man" -> "textures/items/lead.png";
            case "temperance" -> "textures/items/iron_ingot.png";
            case "hollow" -> "textures/items/echo_shard.png";
            case "insomnia" -> "textures/items/phantom_membrane.png";
            case "predator" -> "textures/items/iron_sword.png";
            case "duelist" -> "textures/items/diamond_sword.png";
            case "fettered" -> "textures/items/chainmail_chestplate.png";
            default -> ICON_DARK;
        };
    }

    private static void showTitles(ServerPlayer player, Runnable onBack) {
        if (!KarmaGameRules.enabled(player, KarmaGameRules.TITLES_ENABLED)) {
            tell(player, "Titles are disabled for this world.");
            onBack.run();
            return;
        }
        List<Choice> choices = List.of(choice("Back", ICON_BACK, onBack));
        simple(player, "Titles", "Earned recognition\n" + QuestService.recognition(player).stream().filter(line -> line.startsWith("First completion:")).collect(java.util.stream.Collectors.joining("\n")) + "\n\nContribution milestones\n" + CommunityProgressionService.personalContributionSummary(player), choices);
    }

    private static void showTitleDetails(ServerPlayer player, int index, String name, int threshold,
            Runnable onBack) {
        int karma = Math.abs(KarmaService.get(player));
        int currentStage = KarmaService.individualKarmaStage(player);
        boolean unlocked = karma >= threshold;
        boolean current = currentStage == index;
        String state = current ? "Current stage" : unlocked ? "Unlocked" : "Locked";
        String thresholdText = threshold == 0 ? "Starting stage." : "Requires " + threshold + " Karma.";
        CustomForm.Builder form = CustomForm.builder()
            .title(name)
            .label("State: " + state)
            .label("Your Karma: " + KarmaService.format(KarmaService.get(player)))
            .label("Personal stage: " + currentStage + "/4")
            .label(thresholdText)
            .dropdown("Navigation", "Back");
        form.validResultHandler(response -> guarded(player, onBack));
        send(player, form);
    }

    private static void showSocialCurrency(ServerPlayer player, Runnable onBack) {
        if (!KarmaGameRules.enabled(player, KarmaGameRules.SOCIAL_CURRENCY_ENABLED)) {
            tell(player, "Social Currency is disabled for this world.");
            onBack.run();
            return;
        }
        SocialCurrencyService.Stance stance = SocialCurrencyService.getStance(player);
        SocialCurrencyService.RedemptionTarget target = stance == SocialCurrencyService.Stance.DARK
            ? SocialCurrencyService.RedemptionTarget.INFAMY : SocialCurrencyService.RedemptionTarget.REPUTATION;
        int materializationBalance = target == SocialCurrencyService.RedemptionTarget.REPUTATION
            ? SocialCurrencyService.getReputation(player) : KarmaService.getInfamyPoints(player);
        int redeemableTokens = SocialTokenService.count(player);
        int redemptionRate = stance == SocialCurrencyService.Stance.NEUTRAL
            ? SocialCurrencyService.neutralRedemptionPercent() : SocialCurrencyService.alignedRedemptionPercent();

        String content = karmaCounterLine(player)
            + "\nAlignment: " + KicUiText.alignmentStatus(player)
            + "\nReputation: " + SocialCurrencyService.getReputation(player)
            + "\nInfamy: " + KarmaService.getInfamyPoints(player)
            + "\nPhysical Influence: " + redeemableTokens
            + "\n\nRedeem: uses eligible token origins and redemption rates.\nOffer: accepts any origin, with offering tax and minimum.\nOffer items to earn Influence.\n" + offeringEconomySummary();

        List<Choice> choices = new ArrayList<>();
        choices.add(choice("Materialize " + target.displayName(), "textures/items/echo_shard.png",
            () -> showSocialAmountForm(player, false, target, Math.max(1, Math.min(64, materializationBalance)), onBack)));
        choices.add(choice("Redeem Physical Influence", "textures/items/emerald.png",
            () -> showSocialAmountForm(player, true, target, Math.max(1, Math.min(64, redeemableTokens)), onBack)));
        if (KarmaGameRules.enabled(player, KarmaGameRules.OFFERINGS_ENABLED)) {
            choices.add(choice("Offer Physical Influence (any origin)", "textures/items/echo_shard.png",
                () -> showInfluenceDematerialization(player, onBack)));
            choices.add(choice("Offer Items", "textures/items/gold_ingot.png",
                () -> showOfferings(player, onBack)));
        }
        choices.add(choice("Back", ICON_BACK, onBack));
        simple(player, "Social Currency", content, choices);
    }

    private static void showInfluenceDematerialization(ServerPlayer player, Runnable onBack) {
        showInfluenceDematerializationAmount(player, 0, onBack);
    }

    private static void showInfluenceDematerializationAmount(ServerPlayer player, int selected, Runnable onBack) {
        int available = SocialTokenService.count(player);
        int amount = WorldContributionAmounts.adjust(selected, 0, available);
        List<Choice> choices = new ArrayList<>();
        for (int delta : new int[] {-100, -10, -1, 1, 10, 100})
            choices.add(choice((delta > 0 ? "+" : "") + delta, "textures/items/echo_shard.png",
                () -> showInfluenceDematerializationAmount(player, WorldContributionAmounts.adjust(amount, delta, available), onBack)));
        choices.add(choice("Select maximum", "textures/items/echo_shard.png",
            () -> showInfluenceDematerializationAmount(player, SocialTokenService.count(player), onBack)));
        choices.add(choice("Type exact amount", "textures/items/echo_shard.png", () -> {
            CustomForm.Builder form = CustomForm.builder().title("Dematerialize Influence")
                .input("Amount", "Positive whole number", Integer.toString(amount));
            form.validResultHandler(response -> guarded(player,
                () -> confirmInfluenceDematerialization(player, WorldContributionAmounts.parse(response.asInput()), onBack)));
            send(player, form);
        }));
        choices.add(choice("Review " + amount, "textures/items/echo_shard.png",
            () -> confirmInfluenceDematerialization(player, amount, onBack)));
        choices.add(choice("Back", ICON_BACK, () -> showSocialCurrency(player, onBack)));
        simple(player, "Dematerialize Influence", "Selected: " + amount + "\nAvailable: " + available
            + "\nAccepts any origin.\n" + offeringEconomySummary(), choices);
    }

    private static void confirmInfluenceDematerialization(ServerPlayer player, int amount, Runnable onBack) {
        if (amount < SocialCurrencyService.OFFERING_MINIMUM_GROSS || amount > SocialTokenService.count(player)) {
            tell(player, "Select at least " + SocialCurrencyService.OFFERING_MINIMUM_GROSS + " Influence, within your available inventory.");
            showInfluenceDematerialization(player, onBack);
            return;
        }
        SocialCurrencyService.Stance target = KarmaService.rewardAlignment(player);
        ModalForm.Builder confirm = ModalForm.builder().title("Dematerialize Influence?")
            .content("Consume " + amount + " Influence of any origin for " + SocialCurrencyService.offeringNet(amount)
                + " " + KicUiText.target(player) + "?\n" + offeringEconomySummary())
            .button1("Cancel").button2("Dematerialize");
        confirm.validResultHandler(result -> guarded(player, () -> {
            if (!result.clickedFirst()) {
                if (KarmaService.rewardAlignment(player) != target) tell(player, "Alignment changed. Please review the conversion again.");
                else {
                    var conversion = SocialCurrencyService.dematerializeInfluence(player, amount);
                    if (!conversion.successful()) tell(player, conversion.message());
                }
            }
            showSocialCurrency(player, onBack);
        }));
        send(player, confirm);
    }

    private static void showSocialAmountForm(ServerPlayer player, boolean redeem,
            SocialCurrencyService.RedemptionTarget target, int selected, Runnable onBack) {
        int available = redeem ? SocialCurrencyService.redeemableInfluence(player)
            : SocialCurrencyService.getStance(player) == SocialCurrencyService.Stance.DARK
                ? KarmaService.getInfamyPoints(player) : SocialCurrencyService.getReputation(player);
        int amount = WorldContributionAmounts.adjust(selected, 0, available);
        List<Choice> choices = new ArrayList<>();
        for (int delta : new int[] {-100, -10, -1, 1, 10, 100})
            choices.add(choice((delta > 0 ? "+" : "") + delta, "textures/items/echo_shard.png",
                () -> showSocialAmountForm(player, redeem, target, WorldContributionAmounts.adjust(amount, delta, available), onBack)));
        choices.add(choice("Type exact amount", "textures/items/echo_shard.png",
            () -> showSocialExactInput(player, redeem, target, amount, onBack)));
        choices.add(choice("Select maximum", "textures/items/echo_shard.png",
            () -> showSocialAmountForm(player, redeem, target, available, onBack)));
        choices.add(choice("Review " + amount, "textures/items/echo_shard.png",
            () -> confirmSocialConversion(player, redeem, Integer.toString(amount), onBack)));
        choices.add(choice("Back", ICON_BACK, () -> showSocialCurrency(player, onBack)));
        simple(player, redeem ? "Redeem Physical Influence" : "Materialize currency",
            "Selected: " + amount + "\nAvailable: " + available + "\nConfirm on the next screen.", choices);
    }

    private static void showSocialExactInput(ServerPlayer player, boolean redeem,
            SocialCurrencyService.RedemptionTarget target, int defaultAmount, Runnable onBack) {
        CustomForm.Builder form = CustomForm.builder()
            .title(redeem ? "Redeem Physical Influence" : "Materialize Currency")
            .input("Amount", "Whole number", Integer.toString(defaultAmount));
        form.validResultHandler(response -> guarded(player, () -> {
            String raw = response.asInput();
            confirmSocialConversion(player, redeem, raw, onBack);
        }));
        send(player, form);
    }

    private static void confirmSocialConversion(ServerPlayer player, boolean redeem, String rawAmount,
            Runnable onBack) {
        int amount;
        try {
            amount = Integer.parseInt(rawAmount == null ? "" : rawAmount.trim());
        } catch (NumberFormatException ignored) {
            tell(player, "Enter a whole number greater than zero.");
            showSocialCurrency(player, onBack);
            return;
        }
        if (amount <= 0) {
            tell(player, "Enter a whole number greater than zero.");
            showSocialCurrency(player, onBack);
            return;
        }
        SocialCurrencyService.Stance stance = SocialCurrencyService.getStance(player);
        SocialCurrencyService.RedemptionTarget target = stance == SocialCurrencyService.Stance.DARK
            ? SocialCurrencyService.RedemptionTarget.INFAMY : SocialCurrencyService.RedemptionTarget.REPUTATION;
        if (!redeem && stance == SocialCurrencyService.Stance.NEUTRAL) {
            tell(player, "Neutral players cannot materialize Reputation or Infamy.");
            showSocialCurrency(player, onBack);
            return;
        }
        String action = redeem ? "Redeem " + amount + " Influence for " + target.displayName()
            : "Materialize " + amount + " " + target.displayName() + " into Influence";
        String rate = redeem
            ? (stance == SocialCurrencyService.Stance.NEUTRAL
                ? SocialCurrencyService.neutralRedemptionPercent() : SocialCurrencyService.alignedRedemptionPercent()) + "% redemption rate"
            : SocialCurrencyService.materializationPercent() + "% materialization rate";
        int availableInfluence = redeem ? SocialCurrencyService.redeemableInfluence(player) : SocialTokenService.count(player);
        ModalForm.Builder form = ModalForm.builder()
            .title(redeem ? "Redeem Influence?" : "Materialize Currency?")
            .content(action + "\nCurrent alignment: " + KicUiText.alignmentStatus(player) + "\n" + rate
                + "\nInfluence currently available: " + availableInfluence
                + "\nReceive: " + (int)((long)amount * (redeem ? (stance == SocialCurrencyService.Stance.NEUTRAL ? SocialCurrencyService.neutralRedemptionPercent() : SocialCurrencyService.alignedRedemptionPercent()) : SocialCurrencyService.materializationPercent()) / 100) + (redeem ? " " + target.displayName() : " Physical Influence"))
            .button1("Cancel").button2("Convert");
        form.validResultHandler(response -> guarded(player, () -> {
            if (response.clickedFirst()) {
                showSocialCurrency(player, onBack);
            } else {
                if (SocialCurrencyService.getStance(player) != stance) {
                    tell(player, "Alignment changed. Please review the conversion again.");
                    showSocialCurrency(player, onBack);
                    return;
                }
                convertSocialCurrency(player, redeem, amount, onBack);
            }
        }));
        send(player, form);
    }

    private static void convertSocialCurrency(ServerPlayer player, boolean redeem, int amount, Runnable onBack) {
        if (!KarmaGameRules.enabled(player, KarmaGameRules.SOCIAL_CURRENCY_ENABLED)) {
            tell(player, "Social Currency is disabled for this world.");
            showSocialCurrency(player, onBack);
            return;
        }
        if (amount <= 0) {
            tell(player, "Enter a whole number greater than zero.");
            showSocialCurrency(player, onBack);
            return;
        }

        SocialCurrencyService.Stance stance = SocialCurrencyService.getStance(player);
        SocialCurrencyService.RedemptionTarget target = stance == SocialCurrencyService.Stance.DARK
            ? SocialCurrencyService.RedemptionTarget.INFAMY : SocialCurrencyService.RedemptionTarget.REPUTATION;
        if (!redeem) {
            if (stance == SocialCurrencyService.Stance.NEUTRAL) {
                tell(player, "Neutral players cannot materialize Reputation or Infamy.");
                showSocialCurrency(player, onBack);
                return;
            }
            int balance = target == SocialCurrencyService.RedemptionTarget.REPUTATION
                ? SocialCurrencyService.getReputation(player) : KarmaService.getInfamyPoints(player);
            if (amount > balance) {
                tell(player, "You do not have enough " + target.displayName() + ".");
                showSocialCurrency(player, onBack);
                return;
            }
            int created = target == SocialCurrencyService.RedemptionTarget.REPUTATION
                ? SocialCurrencyService.materializeReputation(player, amount)
                : SocialCurrencyService.materializeInfamy(player, amount);
            if (created <= 0) tell(player, "That amount could not be materialized.");
            
        } else {
            SocialTokenService.Origin origin = switch (stance) {
                case LIGHT -> SocialTokenService.Origin.LIGHT;
                case DARK -> SocialTokenService.Origin.DARK;
                case NEUTRAL -> SocialTokenService.Origin.NEUTRAL;
            };
            int available = SocialTokenService.count(player, origin);
            if (origin != SocialTokenService.Origin.NEUTRAL) {
                available += SocialTokenService.count(player, SocialTokenService.Origin.NEUTRAL);
            }
            if (amount > available) {
                tell(player, "You do not have enough eligible Influence.");
                showSocialCurrency(player, onBack);
                return;
            }
            int redeemed = SocialCurrencyService.redeem(player, amount, target);
            if (redeemed <= 0) {
                tell(player, "These Influence tokens cannot be redeemed into " + target.displayName()
                    + " with your current alignment and redemption rate.");
            } else {
                // Shared conversion receipt is emitted by the service.
            }
        }
        showSocialCurrency(player, onBack);
    }

    private static void showOfferings(ServerPlayer player, Runnable onBack) {
        if (!KarmaGameRules.enabled(player, KarmaGameRules.OFFERINGS_ENABLED)) {
            tell(player, "Offerings are disabled for this world.");
            showSocialCurrency(player, onBack);
            return;
        }

        List<net.minecraft.world.item.Item> items = new ArrayList<>();
        List<Choice> choices = new ArrayList<>();
        for (net.minecraft.world.item.Item item : KarmaOfferingConfig.items()) {
            int owned = inventoryItemCount(player, item);
            if (owned <= 0) continue;
            double value = KarmaOfferingConfig.socialPerItem(item, CommunityProgressionService.stage(player));
            items.add(item);
            choices.add(choice(
                new ItemStack(item).getHoverName().getString()
                    + " (" + owned + " available; " + (item == Items.OMINOUS_BOTTLE ? "6–16 value by level" : KicUiText.number(value) + " value each") + ")",
                ICON_LIGHT,
                () -> showOfferingAmount(player, item, onBack)));
        }

        if (items.isEmpty()) {
            simple(player, "Offer Items",
                "You have no eligible offering items in your inventory.",
                List.of(choice("Back", ICON_BACK, () -> showSocialCurrency(player, onBack))));
            return;
        }

        choices.add(choice("Back", ICON_BACK, () -> showSocialCurrency(player, onBack)));
        simple(player, "Offer Items",
            "Select an item to offer.\n" + offeringEconomySummary(),
            choices);
    }

    private static void showOfferingAmount(ServerPlayer player,
                                           net.minecraft.world.item.Item item,
                                           Runnable onBack) {
        int owned = inventoryItemCount(player, item);
        if (owned <= 0) {
            tell(player, "You no longer have that offering item.");
            showOfferings(player, onBack);
            return;
        }

        double value = KarmaOfferingConfig.socialPerItem(item, CommunityProgressionService.stage(player));
        CustomForm.Builder form = CustomForm.builder()
            .title("Offer Items")
            .label("Offering: " + new ItemStack(item).getHoverName().getString()
                + "\nYou have " + owned + " available.\n"
                + (item == Items.OMINOUS_BOTTLE ? "Value: 6 + 2.5 × (bottle level − 1), from 6–16.\n" : "Value: " + KicUiText.number(value) + " Influence each.\n")
                + offeringEconomySummary())
            .input("Amount", "Whole number", "1");
        form.validResultHandler(response -> guarded(player, () -> {
            int amount;
            try {
                String rawAmount = response.asInput();
                amount = Integer.parseInt(rawAmount == null ? "" : rawAmount.trim());
            } catch (NumberFormatException ignored) {
                tell(player, "Enter a whole number greater than zero.");
                showOfferingAmount(player, item, onBack);
                return;
            }
            if (amount <= 0) {
                tell(player, "Enter a whole number greater than zero.");
                showOfferingAmount(player, item, onBack);
                return;
            }

            int currentOwned = inventoryItemCount(player, item);
            if (amount > currentOwned) {
                tell(player, "You only have " + currentOwned + " of that item available.");
                showOfferingAmount(player, item, onBack);
                return;
            }

            double gross = KarmaOfferingConfig.inventoryGross(player, item, amount);
            ModalForm.Builder confirm = ModalForm.builder()
                .title("Offer Items?")
                .content("Offer " + amount + " " + new ItemStack(item).getHoverName().getString()
                    + "?\n" + KicUiText.conversion(player, gross) + "\nPayout follows your alignment at confirmation.")
                .button1("Cancel").button2("Offer");
            confirm.validResultHandler(modal -> guarded(player, () -> {
                if (modal.clickedFirst()) {
                    showOfferingAmount(player, item, onBack);
                    return;
                }
                SocialCurrencyService.OfferingResult result = SocialCurrencyService.offerItem(player, item, amount);
                if (!result.successful()) tell(player, result.message());
                showOfferings(player, onBack);
            }));
            send(player, confirm);
        }));
        send(player, form);
    }

    private static int inventoryItemCount(ServerPlayer player, net.minecraft.world.item.Item item) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.is(item) && !SocialTokenService.isToken(stack)) count += stack.getCount();
        }
        return count;
    }

    private static void showWorldProgression(ServerPlayer player, Runnable onBack) {
        if (!KarmaGameRules.enabled(player, KarmaGameRules.SOCIAL_CURRENCY_ENABLED)) {
            tell(player, "World Progression is disabled with Social Currency for this world.");
            onBack.run();
            return;
        }
        CommunityProgressionService community = CommunityProgressionService.get(player.level().getServer());
        int stage = community.stage();
        int pool = community.pool();
        int previous = CommunityProgressionService.get(player.level().getServer()).thresholdForStage(stage);
        int next = CommunityProgressionService.get(player.level().getServer()).nextStageThreshold(stage);
        int progress = next > previous ? Math.clamp(pool - previous, 0, next - previous) : next - previous;
        int percent = next > previous ? progress * 100 / (next - previous) : 100;
        int filled = next > previous ? progress * 5 / (next - previous) : 5;
        String bar = java.util.stream.IntStream.rangeClosed(1, 5)
            .mapToObj(value -> "Stage " + value + (value <= stage ? " [unlocked]" : " [locked]"))
            .collect(java.util.stream.Collectors.joining("\n"));
        String progressLine = stage < CommunityProgressionService.MAX_STAGE
            ? "Progress to Stage " + (stage + 1) + ": "
                + CommunityProgressionService.formatInfluence(progress) + " / "
                + CommunityProgressionService.formatInfluence(next - previous)
            : "Maximum Stage reached.";
        String nextStageLine = stage < CommunityProgressionService.MAX_STAGE
            ? "Next-stage threshold: " + CommunityProgressionService.formatInfluence(next)
            : "Stage 5 is the cap. Additional Influence stays in the community pool.";
        String summary = "Stage " + stage + "/" + CommunityProgressionService.MAX_STAGE
            + "\n" + bar
            + "\nCumulative community Influence: " + CommunityProgressionService.formatInfluence(pool)
            + "\n" + progressLine
            + "\n" + nextStageLine
            + "\n\n" + String.join("\n", CommunityProgressionService.nextStagePreview(player))
            + "\n\n" + CommunityProgressionService.personalContributionSummary(player)
            + "\nYour Influence: " + CommunityProgressionService.availableInfluence(player)
            + "\nKarma cap: " + KarmaService.format(KarmaService.currentCap(player));

        List<Choice> choices = new ArrayList<>();
        choices.add(choice("Choose submission amount", "textures/items/echo_shard.png",
            () -> showWorldAmountSelector(player, 0, onBack)));
        choices.add(choice("Back", ICON_BACK, onBack));
        simple(player, "World Progression", summary + "\n\nReputation and Infamy contribute to the same pool.", choices);
    }

    private static void showWorldAmountSelector(ServerPlayer player, int selected, Runnable onBack) {
        int available = CommunityProgressionService.availableInfluence(player);
        int amount = WorldContributionAmounts.adjust(selected, 0, available);
        CommunityProgressionService data = CommunityProgressionService.get(player.level().getServer());
        int fill = WorldContributionAmounts.fill(available, data.pool(),
            CommunityProgressionService.get(player.level().getServer()).nextStageThreshold(data.stage()), data.stage() >= CommunityProgressionService.MAX_STAGE);
        List<Choice> choices = new ArrayList<>();
        choices.add(choice("Type exact amount", "textures/items/echo_shard.png",
            () -> showWorldAmountInput(player, amount, onBack)));
        for (int delta : new int[] {-100, -10, -1, 1, 10, 100}) {
            choices.add(choice((delta > 0 ? "+" : "") + delta, "textures/items/echo_shard.png",
                () -> showWorldAmountSelector(player, WorldContributionAmounts.adjust(amount, delta, CommunityProgressionService.availableInfluence(player)), onBack)));
        }
        choices.add(choice("Fill next stage (" + fill + ")", "textures/items/end_crystal.png",
            () -> {
                CommunityProgressionService current = CommunityProgressionService.get(player.level().getServer());
                showWorldAmountSelector(player, WorldContributionAmounts.fill(CommunityProgressionService.availableInfluence(player), current.pool(),
                    CommunityProgressionService.get(player.level().getServer()).nextStageThreshold(current.stage()), current.stage() >= CommunityProgressionService.MAX_STAGE), onBack);
            }));
        choices.add(choice("Select maximum (" + available + ")", "textures/items/echo_shard.png",
            () -> showWorldAmountSelector(player, CommunityProgressionService.availableInfluence(player), onBack)));
        choices.add(choice("Review submission: " + amount, "textures/items/echo_shard.png",
            () -> submitWorldContribution(player, amount, onBack)));
        choices.add(choice("Back", ICON_BACK, () -> showWorldProgression(player, onBack)));
        simple(player, "Contribution amount", "Selected: " + amount + " Influence"
            + "\nAvailable stored Influence: " + available
            + "\nCommunity pool: " + data.pool() + " -> " + ((long) data.pool() + amount)
            + "\nUse the buttons or type an exact whole number. Nothing is spent until confirmation.", choices);
    }

    private static void showWorldAmountInput(ServerPlayer player, int selected, Runnable onBack) {
        // Input is the first and only component: no label/response cursor ambiguity.
        CustomForm.Builder form = CustomForm.builder().title("World contribution")
            .input("Stored Influence to submit (available: " + CommunityProgressionService.availableInfluence(player) + ")",
                "Positive whole number", Integer.toString(Math.max(1, selected)));
        form.validResultHandler(response -> guarded(player, () -> {
            int amount = WorldContributionAmounts.parse(response.asInput());
            if (amount <= 0 || amount > CommunityProgressionService.availableInfluence(player)) {
                tell(player, "Enter a positive whole number no greater than your stored Influence. You can also use the amount buttons.");
                showWorldAmountSelector(player, selected, onBack);
                return;
            }
            submitWorldContribution(player, amount, onBack);
        }));
        send(player, form);
    }

    private static String offeringEconomySummary() {
        return SocialCurrencyService.OFFERING_TAX_PERCENT + "% offering tax; minimum gross value: "
            + SocialCurrencyService.OFFERING_MINIMUM_GROSS + ".";
    }

    private static void submitWorldContribution(ServerPlayer player, int amount, Runnable onBack) {
        if (amount <= 0 || CommunityProgressionService.availableInfluence(player) < amount) {
            tell(player, "Select a positive amount within your available stored Influence.");
            showWorldProgression(player, onBack);
            return;
        }
        int previewPool = CommunityProgressionService.get(player.level().getServer()).pool();
        String previewReview = CommunityProgressionService.contributionReview(player);
        ModalForm.Builder confirm = ModalForm.builder()
            .title("Submit Influence?")
            .content("Submit " + amount + " " + CommunityProgressionService.contributionCurrency(player) + " to World Progression?\n\n"
                + "Reputation and Infamy contribute to the same community pool.\n"
                + "Community pool: " + previewPool + " -> " + ((long) previewPool + amount) + "\n"
                + "This action cannot be undone.")
            .button1("Cancel").button2("Submit");
        confirm.validResultHandler(response -> guarded(player, () -> {
            if (response.clickedFirst()) {
                showWorldProgression(player, onBack);
                return;
            }
            if (!CommunityProgressionService.contributionReview(player).equals(previewReview)) {
                tell(player, "World progress changed. Please select and review your contribution again.");
                showWorldAmountSelector(player, 0, onBack);
                return;
            }
            if (!KarmaGameRules.enabled(player, KarmaGameRules.SOCIAL_CURRENCY_ENABLED)) {
                tell(player, "Social Currency is disabled for this world.");
                onBack.run();
                return;
            }
            if (!CommunityProgressionService.contributeInfluence(player, amount)) {
                tell(player, "You no longer have enough stored Influence.");
            }
            showWorldProgression(player, onBack);
        }));
        send(player, confirm);
    }

    private static void showHistory(ServerPlayer player, Runnable onBack) {
        List<String> entries = KicHistoryService.personal(player);
        StringBuilder content = new StringBuilder();
        if (entries.isEmpty()) content.append("No history yet.");
        else {
            for (int i = entries.size() - 1; i >= 0; i--) {
                content.append(entries.get(i)).append("\n");
            }
        }
        simple(player, "KIC History", content.toString(),
            List.of(choice("Back", ICON_BACK, onBack)));
    }

    private static void showContributionLog(ServerPlayer player, Runnable onBack) {
        List<String> entries = KicHistoryService.contributions(player.level().getServer());
        StringBuilder content = new StringBuilder();
        if (entries.isEmpty()) content.append("No community contributions yet.");
        else {
            for (int i = entries.size() - 1; i >= 0; i--) {
                content.append(entries.get(i)).append("\n");
            }
        }
        simple(player, "Contribution Log", content.toString(),
            List.of(choice("Back", ICON_BACK, onBack)));
    }

    private static void showShrineChooser(ServerPlayer player, Runnable onBack) {
        if (!KarmaGameRules.enabled(player, KarmaGameRules.SHRINES_ENABLED)) {
            tell(player, "Shrines are disabled for this world.");
            onBack.run();
            return;
        }
        StringBuilder content = new StringBuilder("Choose the nearest registered shrine by type.");
        List<Choice> choices = new ArrayList<>();
        for (ShrineType type : ShrineType.values()) {
            Optional<ShrineSite> nearest = nearestShrine(player, type);
            String label = nearest.map(site -> site.displayName()).orElse("No active shrine found");
            content.append("\n").append(type.displayName()).append(": ").append(label);
            choices.add(choice(type.displayName() + "\n" + label,
                shrineIcon(type), () -> openNearestShrine(player, type, onBack)));
        }
        choices.add(choice("Shrine Compass", ICON_COMPASS, () -> showCompass(player, null)));
        choices.add(choice("Back", ICON_BACK, onBack));
        simple(player, "Shrine", content.toString(), choices);
    }

    private static void openNearestShrine(ServerPlayer player, ShrineType type, Runnable onBack) {
        Optional<ShrineSite> nearest = nearestShrine(player, type);
        if (nearest.isEmpty()) {
            tell(player, "No active " + type.displayName() + " is available.");
            showShrineChooser(player, onBack);
            return;
        }
        showShrineHome(player, nearest.get(), onBack);
    }

    private static Optional<ShrineSite> nearestShrine(ServerPlayer player, ShrineType type) {
        ShrineManager.ServerPlayerPosition position = new ShrineManager.ServerPlayerPosition(
            player.blockPosition().getX(), player.blockPosition().getZ());
        return ShrineManager.nearestShrine(position, player.level().getServer(), type);
    }

    private static String shrineIcon(ShrineType type) {
        return switch (type) {
            case LIGHT -> ICON_LIGHT;
            case DARK -> ICON_DARK;
            case NEUTRAL -> "textures/items/amethyst_shard.png";
            case MONUMENT -> "textures/items/netherite_ingot.png";
        };
    }

    private static void showShrineHome(ServerPlayer player, ShrineSite site, Runnable onBack) {
        if (site == null) {
            tell(player, "That shrine is no longer available.");
            onBack.run();
            return;
        }
        if (!KarmaGameRules.enabled(player, KarmaGameRules.SHRINES_ENABLED)) {
            tell(player, "Shrines are disabled for this world.");
            onBack.run();
            return;
        }
        if (site.type() == ShrineType.LIGHT && !CurseBlessingService.canUseLightShrine(player)) {
            tell(player, "The Devil commitment bars you from Light Shrines.");
            onBack.run();
            return;
        }
        if (site.type() == ShrineType.DARK && !CurseBlessingService.canUseDarkShrine(player)) {
            tell(player, "Devotion bars you from Dark Shrines.");
            onBack.run();
            return;
        }
        switch (site.type()) {
            case LIGHT -> showLightShrine(player, site, onBack);
            case DARK -> showDarkShrine(player, site, onBack);
            case NEUTRAL -> showNeutralShrine(player, site, onBack);
            case MONUMENT -> showMonument(player, site, onBack);
        }
    }

    private static void showLightShrine(ServerPlayer player, ShrineSite site, Runnable onBack) {
        List<Choice> choices = new ArrayList<>();
        if (KarmaGameRules.enabled(player, KarmaGameRules.KARMA_MENU_ENABLED)) {
            choices.add(choice("Karma & Sigils", ICON_KARMA, () -> showStatus(player, site,
                () -> showLightShrine(player, site, onBack))));
        }
        if (KarmaGameRules.enabled(player, KarmaGameRules.SIGILS_ENABLED)
                && KarmaGameRules.enabled(player, KarmaGameRules.BLESSINGS_ENABLED)) {
            choices.add(choice("Blessings", ICON_LIGHT,
                () -> showBlessings(player, site, () -> showLightShrine(player, site, onBack))));
        }
        if (KarmaGameRules.enabled(player, KarmaGameRules.SHOP_ENABLED)) {
            choices.add(choice("Shop", "textures/blocks/chest_front.png", () -> showShop(player,
                ShopCategory.COMMON, () -> showLightShrine(player, site, onBack))));
        }
        if (KarmaGameRules.enabled(player, KarmaGameRules.SHRINE_COMPASS_ENABLED)) {
            choices.add(choice("Shrine Compass", ICON_COMPASS,
                () -> showCompass(player, site)));
        }
        choices.add(choice("Back", ICON_BACK, onBack));
        simple(player, site.displayName(), "Light Shrine", choices);
    }

    private static void showDarkShrine(ServerPlayer player, ShrineSite site, Runnable onBack) {
        List<Choice> choices = new ArrayList<>();
        if (KarmaGameRules.enabled(player, KarmaGameRules.KARMA_MENU_ENABLED)) {
            choices.add(choice("Karma & Sigils", ICON_KARMA, () -> showStatus(player, site,
                () -> showDarkShrine(player, site, onBack))));
        }
        if (KarmaGameRules.enabled(player, KarmaGameRules.SIGILS_ENABLED)
                && KarmaGameRules.enabled(player, KarmaGameRules.CURSES_ENABLED)) {
            choices.add(choice("Curses", ICON_DARK,
                () -> showCurses(player, site, () -> showDarkShrine(player, site, onBack))));
        }
        if (KarmaGameRules.enabled(player, KarmaGameRules.SHOP_ENABLED)) {
            choices.add(choice("Dark Shop", "textures/blocks/chest_front.png", () -> showShop(player,
                ShopCategory.VILLAIN, () -> showDarkShrine(player, site, onBack))));
        }
        if (KarmaGameRules.enabled(player, KarmaGameRules.SHRINE_COMPASS_ENABLED)) {
            choices.add(choice("Shrine Compass", ICON_COMPASS,
                () -> showCompass(player, site)));
        }
        choices.add(choice("Back", ICON_BACK, onBack));
        simple(player, site.displayName(), "Dark Shrine", choices);
    }

    private static void showNeutralShrine(ServerPlayer player, ShrineSite site, Runnable onBack) {
        List<Choice> choices = new ArrayList<>();
        if (KarmaGameRules.enabled(player, KarmaGameRules.KARMA_MENU_ENABLED)) {
            choices.add(choice("Karma Status", ICON_KARMA, () -> showStatus(player, site,
                () -> showNeutralShrine(player, site, onBack))));
        }
        if (KarmaGameRules.enabled(player, KarmaGameRules.SHOP_ENABLED)) {
            choices.add(choice("Shops", "textures/blocks/chest_front.png", () -> showShopSections(player, site,
                () -> showNeutralShrine(player, site, onBack))));
        }
        if (KarmaGameRules.enabled(player, KarmaGameRules.SHRINE_COMPASS_ENABLED)) {
            choices.add(choice("Shrine Compass", ICON_COMPASS,
                () -> showCompass(player, site)));
        }
        choices.add(choice("Back", ICON_BACK, onBack));
        simple(player, site.displayName(), "Neutral Shrine", choices);
    }

    private static void showMonument(ServerPlayer player, ShrineSite site, Runnable onBack) {
        List<Choice> choices = new ArrayList<>();
        if (KarmaGameRules.enabled(player, KarmaGameRules.KARMA_MENU_ENABLED)) {
            choices.add(choice("Karma & Sigils", ICON_KARMA,
                () -> showStatus(player, site, () -> showMonument(player, site, onBack))));
        }
        if (KarmaGameRules.enabled(player, KarmaGameRules.SOCIAL_CURRENCY_ENABLED)) {
            choices.add(choice("World Progression", "textures/items/end_crystal.png",
                () -> showWorldProgression(player, () -> showMonument(player, site, onBack))));
        }
        if (KarmaGameRules.enabled(player, KarmaGameRules.SHOP_ENABLED)) {
            choices.add(choice("Shop", "textures/blocks/chest_front.png",
                () -> showShopSections(player, site, () -> showMonument(player, site, onBack))));
        }
        if (KarmaGameRules.enabled(player, KarmaGameRules.SHRINE_COMPASS_ENABLED)) {
            choices.add(choice("Shrine Compass", ICON_COMPASS, () -> showCompass(player, site)));
        }
        choices.add(choice("Back", ICON_BACK, onBack));
        String content = karmaCounterLine(player)
            + "\nAlignment: " + KicUiText.alignmentStatus(player);
        simple(player, "Monument", content, choices);
    }

    private static void showShopSections(ServerPlayer player, ShrineSite site, Runnable onBack) {
        if (!KarmaGameRules.enabled(player, KarmaGameRules.SHOP_ENABLED)) {
            tell(player, "The shop is disabled for this world.");
            if (onBack != null) onBack.run();
            return;
        }
        List<Choice> choices = new ArrayList<>();
        if (site != null && ShopCategory.canAccess(player, ShopCategory.COMMON, site.type())) {
            choices.add(choice("Light Shop", "textures/blocks/chest_front.png", () -> showShop(player, ShopCategory.COMMON, () -> showShopSections(player, site, onBack))));
        }
        if (site != null && ShopCategory.canAccess(player, ShopCategory.VILLAIN, site.type())) {
            choices.add(choice("Dark Shop", ICON_DARK, () -> showShop(player, ShopCategory.VILLAIN, () -> showShopSections(player, site, onBack))));
        }
        choices.add(choice("Back", ICON_BACK, onBack == null ? () -> showMain(player, null, null) : onBack));
        simple(player, "Shrine Shop", KicCycle.statusLine(), choices);
    }

    private static void showShop(ServerPlayer player, ShopCategory category, Runnable onBack) {
        showShopSnapshot(player, category, onBack, KicCycle.currentCycleId(), KicCycle.statusLine());
    }

    private static String shopIcon(ShopReward reward) {
        return switch (reward.id().toLowerCase(java.util.Locale.ROOT)) {
            case "recovery_compass" -> "textures/items/recovery_compass.png";
            case "saddle" -> "textures/items/saddle.png";
            case "gilded_blackstone" -> "textures/blocks/gilded_blackstone.png";
            case "flower_banner_pattern" -> "textures/items/flower_banner_pattern.png";
            case "creeper_banner_pattern" -> "textures/items/creeper_banner_pattern.png";
            case "snout_banner_pattern" -> "textures/items/snout_banner_pattern.png";
            case "globe_banner_pattern" -> "textures/items/globe_banner_pattern.png";
            case "flow_banner_pattern" -> "textures/items/flow_banner_pattern.png";
            case "guster_banner_pattern" -> "textures/items/guster_banner_pattern.png";
            case "coast_armor_trim_smithing_template" -> "textures/items/coast_armor_trim_smithing_template.png";
            case "silence_armor_trim_smithing_template" -> "textures/items/silence_armor_trim_smithing_template.png";
            case "dune_armor_trim_smithing_template" -> "textures/items/dune_armor_trim_smithing_template.png";
            case "wild_armor_trim_smithing_template" -> "textures/items/wild_armor_trim_smithing_template.png";
            case "ward_armor_trim_smithing_template" -> "textures/items/ward_armor_trim_smithing_template.png";
            case "vex_armor_trim_smithing_template" -> "textures/items/vex_armor_trim_smithing_template.png";
            case "tide_armor_trim_smithing_template" -> "textures/items/tide_armor_trim_smithing_template.png";
            case "wayfinder_armor_trim_smithing_template" -> "textures/items/wayfinder_armor_trim_smithing_template.png";
            case "shaper_armor_trim_smithing_template" -> "textures/items/shaper_armor_trim_smithing_template.png";
            case "raiser_armor_trim_smithing_template" -> "textures/items/raiser_armor_trim_smithing_template.png";
            case "host_armor_trim_smithing_template" -> "textures/items/host_armor_trim_smithing_template.png";
            case "eye_armor_trim_smithing_template" -> "textures/items/eye_armor_trim_smithing_template.png";
            case "rib_armor_trim_smithing_template" -> "textures/items/rib_armor_trim_smithing_template.png";
            case "spire_armor_trim_smithing_template" -> "textures/items/spire_armor_trim_smithing_template.png";
            case "flow_armor_trim_smithing_template" -> "textures/items/flow_armor_trim_smithing_template.png";
            case "bolt_armor_trim_smithing_template" -> "textures/items/bolt_armor_trim_smithing_template.png";
            case "music_disc_13" -> "textures/items/record_13.png";
            case "music_disc_cat" -> "textures/items/record_cat.png";
            case "music_disc_blocks" -> "textures/items/record_blocks.png";
            case "music_disc_chirp" -> "textures/items/record_chirp.png";
            case "music_disc_pigstep" -> "textures/items/record_pigstep.png";
            case "heart_of_the_sea" -> "textures/items/heartofthesea.png";
            case "goat_horn" -> "textures/items/goat_horn.png";
            case "random_enchantment", "fixed_enchantment" -> "textures/items/enchanted_book.png";
            case "suspicious_stew" -> "textures/items/suspicious_stew.png";
            case "ender_pearl" -> "textures/items/ender_pearl.png";
            case "totem_of_undying" -> "textures/items/totem.png";
            case "tnt" -> "textures/blocks/tnt_side.png";
            case "obsidian" -> "textures/blocks/obsidian.png";
            case "crying_obsidian" -> "textures/blocks/crying_obsidian.png";
            case "magma_block" -> "textures/blocks/magma.png";
            case "fire_charge" -> "textures/items/fireball.png";
            case "froglight" -> "textures/blocks/ochre_froglight_side.png";
            case "nether_wart" -> "textures/items/nether_wart.png";
            case "torch" -> "textures/items/torch.png";
            case "lantern" -> "textures/items/lantern.png";
            case "soul_lantern" -> "textures/items/soul_lantern.png";
            case "sand" -> "textures/blocks/sand.png";
            case "sea_lantern" -> "textures/blocks/sea_lantern.png";
            case "wheat_seeds" -> "textures/items/wheat_seeds.png";
            case "beetroot_seeds" -> "textures/items/beetroot_seeds.png";
            case "torchflower_seeds" -> "textures/items/torchflower_seeds.png";
            case "pitcher_pod" -> "textures/items/pitcher_pod.png";
            case "cow_spawn_egg" -> "textures/items/spawn_egg_cow.png";
            case "pig_spawn_egg" -> "textures/items/spawn_egg_pig.png";
            case "sheep_spawn_egg" -> "textures/items/spawn_egg_sheep.png";
            case "chicken_spawn_egg" -> "textures/items/spawn_egg_chicken.png";
            case "rabbit_spawn_egg" -> "textures/items/spawn_egg_rabbit.png";
            case "horse_spawn_egg" -> "textures/items/spawn_egg_horse.png";
            default -> {
                String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(reward.item()).getPath();
                yield switch (reward.itemCategory()) {
                    case BUILDING -> "textures/blocks/" + path + ".png";
                    case UTILITY, RESOURCES, COMBAT, DECORATION, RARE -> "textures/items/" + path + ".png";
                };
            }
        };
    }

    private static void showShopSnapshot(ServerPlayer player, ShopCategory category, Runnable onBack,
            long cycle, String cycleStatus) {
        if (!KarmaGameRules.enabled(player, KarmaGameRules.SHOP_ENABLED)) {
            tell(player, "The shop is disabled for this world.");
            onBack.run();
            return;
        }
        List<ShopReward> rewards = KarmaShopCatalog.rewardsFor(category, cycle);
        List<Choice> choices = new ArrayList<>();
        for (ShopReward reward : rewards) {
            ShopPurchaseState purchases = ShopPurchaseState.get(player.level().getServer());
            int bought = purchases.count(player.getUUID(), category, reward.id(), cycle);
            String limit = reward.maxPurchases() == 0 ? "" : "  [" + bought + "/" + reward.maxPurchases() + "]";
            String lock = KarmaShopCatalog.purchaseBlocker(player, reward, cycle);
            String icon = shopIcon(reward);
            choices.add(choice(reward.displayName() + "\n"
                    + (lock.isEmpty() ? KarmaShopCatalog.priceLabel(player, reward) : lock) + limit, icon,
                () -> {
                    if (!lock.isEmpty()) showLockedShopItem(player, category, reward, onBack, cycle, cycleStatus);
                    else confirmPurchase(player, category, reward, onBack, cycle, cycleStatus);
                }));
        }
        choices.add(choice("Back", ICON_BACK, onBack));
        String balances = "Reputation: " + SocialCurrencyService.getReputation(player)
            + "  |  Infamy: " + KarmaService.getInfamyPoints(player);
        simple(player, category.displayName(), cycleStatus + "\n" + balances, choices);
    }

    private static void showLockedShopItem(ServerPlayer player, ShopCategory category, ShopReward reward,
            Runnable onBack, long cycle, String cycleStatus) {
        String lock = KarmaShopCatalog.purchaseBlocker(player, reward, cycle);
        CustomForm.Builder form = CustomForm.builder().title(reward.displayName())
            .label("LOCKED\n" + lock)
            .label(KarmaShopCatalog.balanceLabel(player, reward))
            .dropdown("Navigation", "Back");
        form.validResultHandler(response -> guarded(player,
            () -> showShopSnapshot(player, category, onBack, cycle, cycleStatus)));
        send(player, form);
    }

    private static void confirmPurchase(ServerPlayer player, ShopCategory category, ShopReward selected,
            Runnable onBack, long cycle, String cycleStatus) {
        if (!KarmaGameRules.enabled(player, KarmaGameRules.SHOP_ENABLED)) {
            tell(player, "The shop is disabled for this world.");
            showShopSnapshot(player, category, onBack, cycle, cycleStatus);
            return;
        }
        ShopReward reward = currentShopReward(category, selected.id(), cycle);
        if (reward == null) {
            tell(player, "That reward is no longer available in this shop.");
            showShop(player, category, onBack);
            return;
        }
        String lock = KarmaShopCatalog.purchaseBlocker(player, reward, cycle);
        if (!lock.isEmpty()) {
            tell(player, lock);
            showShopSnapshot(player, category, onBack, cycle, cycleStatus);
            return;
        }
        String content = "Item: " + reward.displayName() + " x" + reward.count()
            + "\nCost: " + KarmaShopCatalog.priceLabel(player, reward) + "\n" + KarmaShopCatalog.balanceLabel(player, reward)
            + "\nConfirm to purchase this item.";
        ModalForm.Builder form = ModalForm.builder().title("Purchase Reward?")
            .content(content).button1("Cancel").button2("Purchase");
        form.validResultHandler(response -> guarded(player, () -> {
            if (response.clickedFirst()) {
                showShopSnapshot(player, category, onBack, cycle, cycleStatus);
                return;
            }
            if (!KarmaGameRules.enabled(player, KarmaGameRules.SHOP_ENABLED)) {
                tell(player, "The shop is disabled for this world.");
                showShopSnapshot(player, category, onBack, cycle, cycleStatus);
                return;
            }
            ShopReward current = currentShopReward(category, selected.id(), cycle);
            if (current == null) {
                tell(player, "That reward is no longer available in this shop.");
            } else {
                KarmaShopCatalog.PurchaseResult result = KarmaShopCatalog.purchase(player, current, cycle);
                if (!result.successful()) tell(player, result.message());
            }
            showShopSnapshot(player, category, onBack, cycle, cycleStatus);
        }));
        send(player, form);
    }

    private static ShopReward currentShopReward(ShopCategory category, String rewardId, long cycle) {
        return KarmaShopCatalog.rewardsFor(category, cycle).stream()
            .filter(reward -> reward.id().equals(rewardId))
            .findFirst().orElse(null);
    }

    private static void showCompass(ServerPlayer player, ShrineSite site) {
        if (!KarmaGameRules.enabled(player, KarmaGameRules.SHRINE_COMPASS_ENABLED)) {
            tell(player, "Shrine compasses are disabled for this world.");
            return;
        }
        List<Choice> choices = new ArrayList<>();
        choices.add(choice("Nearest Shrine\n" + compassTargetLabel(player, null), ICON_COMPASS,
            () -> retargetCompass(player, null, site)));
        for (ShrineType type : ShrineType.values()) {
            choices.add(choice(type.displayName() + "\n" + compassTargetLabel(player, type),
                shrineIcon(type), () -> retargetCompass(player, type, site)));
        }
        choices.add(choice("Close", ICON_BACK, () -> player.closeContainer()));
        String owned = ShrineCompass.hasShrineCompass(player)
            ? "Choose where your Shrine Compass should point."
            : "Choose a destination to obtain a Shrine Compass for it.";
        simple(player, "Shrine Compass", owned, choices);
    }

    private static String compassTargetLabel(ServerPlayer player, ShrineType type) {
        ShrineManager.ServerPlayerPosition position = new ShrineManager.ServerPlayerPosition(
            player.blockPosition().getX(), player.blockPosition().getZ());
        MinecraftServer server = player.level().getServer();
        if (type == null) {
            return ShrineManager.nearestShrineTarget(position, server)
                .map(target -> target.generated().map(ShrineSite::displayName).orElse("Predicted shrine"))
                .orElse("No shrine location available");
        }
        return ShrineManager.nearestShrine(position, server, type)
            .map(ShrineSite::displayName).orElse("No active " + type.displayName() + " available");
    }

    private static void retargetCompass(ServerPlayer player, ShrineType type, ShrineSite site) {
        if (!KarmaGameRules.enabled(player, KarmaGameRules.SHRINE_COMPASS_ENABLED)) {
            tell(player, "Shrine compasses are disabled for this world.");
            return;
        }
        ShrineManager.ServerPlayerPosition position = new ShrineManager.ServerPlayerPosition(
            player.blockPosition().getX(), player.blockPosition().getZ());
        MinecraftServer server = player.level().getServer();
        Optional<ShrineManager.ShrineTarget> target;
        if (type == null) {
            target = ShrineManager.nearestShrineTarget(position, server);
        } else {
            target = ShrineManager.nearestShrine(position, server, type)
                .map(ShrineManager.ShrineTarget::generated);
        }
        if (target.isEmpty()) {
            tell(player, "No " + (type == null ? "shrine" : type.displayName()) + " location is available.");
            showCompass(player, site);
            return;
        }

        ShrineManager.ShrineTarget selected = target.get();
        ItemStack compass = findShrineCompass(player);
        if (compass != null) {
            if (selected.generated().isPresent()) {
                ShrineCompass.retarget(compass, selected.generated().get(), server);
                tell(player, "Compass now points to the nearest " + selected.generated().get().displayName() + ".");
            } else {
                ShrineCompass.retargetPredicted(compass, selected.position().getX(), selected.position().getZ());
                tell(player, "Compass now points to the nearest predicted shrine location.");
            }
        } else {
            boolean bypassCooldown = !KarmaGameRules.enabled(player, KarmaGameRules.COMPASS_COOLDOWN_ENABLED);
            boolean given = selected.generated().isPresent()
                ? ShrineCompass.give(player, selected.generated().get(), bypassCooldown)
                : ShrineCompass.givePredicted(player, selected.position().getX(), selected.position().getZ(), bypassCooldown);
            if (given) {
                tell(player, "Shrine Compass obtained.");
            } else if (KarmaGameRules.enabled(player, KarmaGameRules.COMPASS_COOLDOWN_ENABLED)
                    && !KarmaService.canReceiveShrineCompass(player)) {
                tell(player, "Shrine Compass cooldown: " + KicUiText.durationSeconds(KarmaService.getShrineCompassCooldownMinutes(player) * 60L));
            } else {
                tell(player, "A Shrine Compass could not be given.");
            }
        }
        player.closeContainer();
    }

    private static ItemStack findShrineCompass(ServerPlayer player) {
        ItemStack inventoryCompass = player.getInventory().getNonEquipmentItems().stream()
            .filter(ShrineCompass::isShrineCompass).findFirst().orElse(null);
        if (inventoryCompass != null) return inventoryCompass;
        return ShrineCompass.isShrineCompass(player.getOffhandItem()) ? player.getOffhandItem() : null;
    }

    private record Choice(String label, String icon, Runnable action) {}
}
