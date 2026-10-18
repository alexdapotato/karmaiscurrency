package akisei.karmacurrency;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Server-authoritative Java chest UI for the rotating KIC quest pools. */
public final class QuestMenu extends KarmaMenu {
    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;

    private static final int HEADER = 13;
    private static final int ACTIVE_COUNT = 4;
    private static final int ACTIVE_QUEST = 10; // R2C2
    private static final int ACTIVE_PROGRESS = 19; // R3C2
    private static final int COOLDOWN_STATUS = 22;
    private static final int TRIAL_LABEL = 29; // R4C3
    private static final int[] TRIAL_SLOTS = {30, 31, 32}; // R4C4-C6
    private static final int HUNT_SLOT = 33; // R4C7, independent of the rotating trial pool
    private static final int CONTRACT_LABEL = 38; // R5C3
    private static final int[] CONTRACT_SLOTS = {39, 40, 41}; // R5C4-C6
    private static final int DUEL_SLOT = 42; // Independent from rotating quest pools
    private static final int CANCEL_ACTIVE = 11;
    private static final int DUEL_INVITATION = 12;
    private static final int BACK = 53;

    private final ShrineSite site;
    private final Runnable onBack;

    private QuestMenu(int id, Inventory inventory, ServerPlayer player, ShrineSite site,
            Runnable onBack, SimpleContainer display) {
        super(MenuType.GENERIC_9x6, id, inventory, display, ROWS, player);
        this.site = site;
        this.onBack = onBack == null ? () -> {} : onBack;
        refresh();
    }

    public static void open(ServerPlayer player, ShrineSite site, Runnable onBack) {
        player.openMenu(new SimpleMenuProvider(
            (id, inventory, ignored) -> new QuestMenu(id, inventory, player, site, onBack, new SimpleContainer(SIZE)),
            Component.literal("Quests")));
    }

    public static void refreshIfOpen(ServerPlayer player) {
        if (player.containerMenu instanceof QuestMenu menu) {
            menu.refresh();
            menu.broadcastFullState();
        }
    }

    @Override
    public void clicked(int slotId, int button, ContainerInput input, Player clicker) {
        if (clicker != player) return;
        if (slotId >= 0 && slotId < SIZE) {
            if (input == ContainerInput.PICKUP && button == 0) handleClick(slotId);
            broadcastFullState();
            return;
        }
        super.clicked(slotId, button, input, clicker);
    }

    @Override
    public ItemStack quickMoveStack(Player clicker, int slotId) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return slots.indexOf(slot) >= SIZE;
    }

    @Override
    public boolean stillValid(Player other) {
        if (other != player || !other.isAlive()) return false;
        if (site == null) return true;
        if (other.level().dimension() != net.minecraft.world.level.Level.OVERWORLD) return false;
        BlockPos core = site.actualLodestonePosition();
        return other.distanceToSqr(core.getX() + 0.5D, core.getY() + 0.5D, core.getZ() + 0.5D) <= 64.0D;
    }

    private void handleClick(int slotId) {
        if (slotId == BACK) {
            onBack.run();
            return;
        }
        if (slotId == ACTIVE_PROGRESS && QuestRevamp.submission(QuestService.activeQuest(player))) {
            QuestRevamp.submit(player,false);refresh();return;
        }
        if (slotId == 14 && QuestRevamp.submission(QuestService.activeQuest(player))) {
            QuestRevamp.submit(player,true);refresh();return;
        }
        if (slotId == CANCEL_ACTIVE) {
            QuestService.cancelActive(player);
            refresh();
            return;
        }
        if (slotId == DUEL_INVITATION) {
            ServerPlayer challenger = QuestService.pendingDuelChallenger(player);
            if (challenger != null) openDuelConsent(challenger);
            return;
        }
        if (slotId == DUEL_SLOT && false) {
            openConfirmation(QuestService.QuestId.DUEL);
            return;
        }
        if (slotId == HUNT_SLOT) {
            if (!KarmaGameRules.enabled(player, KarmaGameRules.QUESTS_ENABLED)) {
                player.sendSystemMessage(Component.literal("Quests are disabled in this world."));
                refresh();
                return;
            }
            openHuntMenu();
            return;
        }

        QuestService.QuestId clicked = questAtSlot(slotId, QuestService.Type.TRIAL);
        if (clicked == null) clicked = questAtSlot(slotId, QuestService.Type.CONTRACT);
        if (clicked == null) return;

        String alignmentBlock = QuestService.alignmentBlockReason(player, clicked);
        if (!alignmentBlock.isBlank()) {
            player.sendSystemMessage(Component.literal(alignmentBlock));
            refresh();
            return;
        }
        openConfirmation(clicked);
    }

    private QuestService.QuestId questAtSlot(int slotId, QuestService.Type type) {
        List<QuestService.QuestId> quests = questsForPool(type);
        int[] slotsForPool = type == QuestService.Type.TRIAL ? TRIAL_SLOTS : CONTRACT_SLOTS;
        for (int i = 0; i < slotsForPool.length && i < quests.size(); i++) {
            if (slotId == slotsForPool[i]) return quests.get(i);
        }
        return null;
    }

    private List<QuestService.QuestId> questsForPool(QuestService.Type type) {
        return type == QuestService.Type.TRIAL
            ? QuestService.currentTrials(player) : QuestService.currentContracts(player);
    }

    private boolean showTrials() {
        return KarmaGameRules.enabled(player, KarmaGameRules.QUESTS_ENABLED)
            && KarmaGameRules.enabled(player, KarmaGameRules.TRIALS_ENABLED);
    }

    private boolean showContracts() {
        return KarmaGameRules.enabled(player, KarmaGameRules.QUESTS_ENABLED)
            && KarmaGameRules.enabled(player, KarmaGameRules.CONTRACTS_ENABLED);
    }

    private void openConfirmation(QuestService.QuestId quest) {
        player.closeContainer();
        player.openMenu(new SimpleMenuProvider(
            (id, inventory, ignored) -> new QuestConfirmationMenu(id, inventory, player, site, onBack, quest,
                new SimpleContainer(3 * 9)),
            Component.literal("Confirm Quest")));
    }

    private void openHuntMenu() {
        player.closeContainer();
        player.openMenu(new SimpleMenuProvider(
            (id, inventory, ignored) -> new HuntChoiceMenu(id, inventory, player, site, onBack, new SimpleContainer(SIZE)),
            Component.literal("The Hunt")));
    }

    private void refresh() {
        for (int slot = 0; slot < SIZE; slot++) {
            display.setItem(slot, ShrineMenu.icon(Items.STAINED_GLASS_PANE.gray(), " "));
        }

        display.setItem(HEADER, ShrineMenu.icon(Items.WRITABLE_BOOK, "Quest Cycle",
            "Shared KIC cycle.", QuestService.nextCycleStatus(),
            KarmaGameRules.enabled(player, KarmaGameRules.QUESTS_ENABLED)
                ? "Quest pools are shared by the server and fixed for this cycle."
                : "Quests are disabled in this world."));

        QuestService.QuestId active = QuestService.activeQuest(player);
        display.setItem(ACTIVE_COUNT, ShrineMenu.icon(Items.NETHER_STAR,
            "Active Quest " + (active == null ? "0/1" : "1/1"),
            active == null ? "No active quest." : active.displayName()));
        if (active == null) {
            display.setItem(ACTIVE_QUEST, ShrineMenu.icon(Items.STAINED_GLASS_PANE.gray(),
                "No Active Quest", "Active state: 0/1."));
            display.setItem(ACTIVE_PROGRESS, ShrineMenu.icon(Items.PAPER, "Quest Progress",
                "There is no active quest."));
        } else {
            String progress = QuestService.status(player, active);
            display.setItem(ACTIVE_QUEST, ShrineMenu.icon(active.icon(), active.displayName(),
                "Active state: 1/1", progress));
            display.setItem(ACTIVE_PROGRESS, ShrineMenu.icon(Items.PAPER, QuestRevamp.submission(active)?"Review Submission":"Progress / Timer", progress));
            if(QuestRevamp.submission(active) && QuestRevamp.get(player,"confirm_until")>=System.currentTimeMillis())
                display.setItem(14,ShrineMenu.icon(Items.EMERALD,"Confirm Submission — consumes items",QuestService.objectiveDescription(player,active),QuestService.rewardDescription(player,active)));
            display.setItem(CANCEL_ACTIVE, ShrineMenu.icon(Items.BARRIER, "Cancel Active Quest",
                "Ends the quest without a reward.", "No completion spent; same objective remains available."));
        }
        display.setItem(COOLDOWN_STATUS, ShrineMenu.icon(Items.CLOCK, "Cooldown",
            QuestService.generalCooldownStatus(player), QuestService.perQuestCooldownStatus(player)));

        if (showTrials()) {
            display.setItem(TRIAL_LABEL, ShrineMenu.icon(Items.EMERALD, "Trials",
                "Three Trials are available this cycle.",
                SocialCurrencyService.getStance(player) == SocialCurrencyService.Stance.DARK
                    ? "Normal Trials are unavailable for Dark Aligned players; Hunt is role-based." : ""));
            refreshPool(QuestService.Type.TRIAL, QuestService.currentTrials(player));
        }
        if (showContracts()) {
            display.setItem(CONTRACT_LABEL, ShrineMenu.icon(Items.IRON_SWORD, "Contracts",
                "Three Contracts are available this cycle.",
                SocialCurrencyService.getStance(player) == SocialCurrencyService.Stance.LIGHT
                    ? "Contracts are unavailable for Light Aligned players." : ""));
            refreshPool(QuestService.Type.CONTRACT, QuestService.currentContracts(player));
        }
        {
            List<String> huntLore = new java.util.ArrayList<>();
            huntLore.add("Permanent Quest-list entry; it does not use a rotating slot.");
            if (!KarmaGameRules.enabled(player, KarmaGameRules.QUESTS_ENABLED)) {
                huntLore.add("Disabled by quests_enabled.");
            } else if (QuestService.canBecomeHunted(player)) {
                huntLore.add("Accept as the Hunted.");
            }
            if (!QuestService.availableHuntsForHunter(player).isEmpty()) huntLore.add("Join an active Hunt as a Hunter.");
            if (QuestService.isActive(player, QuestService.QuestId.HUNT)) {
                huntLore.add(QuestService.status(player, QuestService.QuestId.HUNT));
            } else if (!QuestService.canBecomeHunted(player) && QuestService.availableHuntsForHunter(player).isEmpty()) {
                huntLore.add("No Hunt role is currently available to you.");
            }
            huntLore.add(QuestService.durationDescription(QuestService.QuestId.HUNT));
            display.setItem(HUNT_SLOT, ShrineMenu.icon(Items.COMPASS, "The Hunt", huntLore.toArray(String[]::new)));
        }
        if (false) {
            display.setItem(DUEL_SLOT, ShrineMenu.icon(Items.IRON_SWORD, "Duel",
                "Duels are controlled independently by duels_enabled.",
                QuestService.canAccept(player, QuestService.QuestId.DUEL)
                    ? "Click to review and accept."
                    : QuestService.acceptanceBlockReason(player, QuestService.QuestId.DUEL)));
        }
        display.setItem(BACK, ShrineMenu.icon(Items.ARROW, "Back"));
        ServerPlayer challenger = QuestService.pendingDuelChallenger(player);
        if (challenger != null) display.setItem(DUEL_INVITATION, ShrineMenu.icon(Items.IRON_SWORD,
            "Duel Invite: " + challenger.getName().getString(), "Review objective and reward.", "Explicit consent is required."));
    }

    private void openDuelConsent(ServerPlayer challenger) {
        player.closeContainer();
        player.openMenu(new SimpleMenuProvider((id, inventory, ignored) ->
            new DuelConsentMenu(id, inventory, player, site, onBack, challenger, new SimpleContainer(3 * 9)),
            Component.literal("Duel Consent")));
    }

    private void refreshPool(QuestService.Type type, List<QuestService.QuestId> quests) {
        int[] slotsForPool = type == QuestService.Type.TRIAL ? TRIAL_SLOTS : CONTRACT_SLOTS;
        for (int i = 0; i < slotsForPool.length; i++) {
            int slot = slotsForPool[i];
            if (i >= quests.size()) {
                display.setItem(slot, ShrineMenu.icon(Items.STAINED_GLASS_PANE.gray(), "No quest"));
                continue;
            }
            QuestService.QuestId quest = quests.get(i);
            boolean active = QuestService.isActive(player, quest);
            List<String> lore = new java.util.ArrayList<>();
            if (active) lore.add("ACTIVE");
            String alignmentBlock = QuestService.alignmentBlockReason(player, quest);
            if (!alignmentBlock.isBlank()) lore.add(alignmentBlock);
            String cooldown = QuestService.cooldownStatus(player, quest);
            if (!cooldown.isBlank()) lore.addAll(List.of(cooldown.split(" \\| ")));
            if (!active && alignmentBlock.isBlank() && QuestService.activeCount(player) >= QuestService.MAX_ACTIVE_QUESTS) {
                lore.add("Active quest limit: 1/1.");
            }
            if (!active && alignmentBlock.isBlank() && cooldown.isBlank()
                    && QuestService.activeCount(player) < QuestService.MAX_ACTIVE_QUESTS
                    && !QuestService.canAccept(player, quest)) {
                lore.add(QuestService.acceptanceBlockReason(player, quest));
            }
            if (lore.isEmpty()) lore.add("Click to review and accept.");
            lore.add(QuestService.objectiveDescription(player, quest));
            lore.add("Reward: " + QuestService.rewardDescription(player, quest));
            lore.add(QuestService.bonusDescription(player, quest));
            display.setItem(slot, ShrineMenu.icon(quest.icon(), quest.displayName(), lore.toArray(String[]::new)));
        }
    }

    private static final class QuestConfirmationMenu extends KarmaMenu {
        private static final int ROWS = 3;
        private static final int SIZE = ROWS * 9;
        private static final int SUMMARY = 4;
        private static final int OBJECTIVE = 10;
        private static final int REWARD = 13;
        private static final int TIMING = 16;
        private static final int CONFIRM = 22;
        private static final int BACK = 24;

        private final ShrineSite site;
        private final Runnable onBack;
        private final QuestService.QuestId quest;

        private QuestConfirmationMenu(int id, Inventory inventory, ServerPlayer player, ShrineSite site,
                Runnable onBack, QuestService.QuestId quest, SimpleContainer display) {
            super(MenuType.GENERIC_9x3, id, inventory, display, ROWS, player);
            this.site = site;
            this.onBack = onBack == null ? () -> {} : onBack;
            this.quest = quest;
            refresh();
        }

        @Override
        public void clicked(int slotId, int button, ContainerInput input, Player clicker) {
            if (clicker != player) return;
            if (slotId >= 0 && slotId < SIZE) {
                if (input == ContainerInput.PICKUP && button == 0) handle(slotId);
                broadcastFullState();
                return;
            }
            super.clicked(slotId, button, input, clicker);
        }

        @Override public ItemStack quickMoveStack(Player clicker, int slotId) { return ItemStack.EMPTY; }
        @Override public boolean canDragTo(Slot slot) { return slots.indexOf(slot) >= SIZE; }
        @Override public boolean stillValid(Player other) {
            if (other != player || !other.isAlive()) return false;
            if (site == null) return true;
            if (other.level().dimension() != net.minecraft.world.level.Level.OVERWORLD) return false;
            BlockPos core = site.actualLodestonePosition();
            return other.distanceToSqr(core.getX() + 0.5D, core.getY() + 0.5D, core.getZ() + 0.5D) <= 64.0D;
        }

        private void handle(int slotId) {
            if (slotId == BACK) {
                QuestMenu.open(player, site, onBack);
                return;
            }

            if (slotId != CONFIRM) return;
            String alignmentBlock = QuestService.alignmentBlockReason(player, quest);
            if (!alignmentBlock.isBlank()) {
                player.sendSystemMessage(Component.literal(alignmentBlock));
                QuestMenu.open(player, site, onBack);
                return;
            }
            if (quest == QuestService.QuestId.HUNT) {
                if (!QuestService.canAccept(player, quest)) {
                    player.sendSystemMessage(Component.literal(QuestService.acceptanceBlockReason(player, quest)));
                    QuestMenu.open(player, site, onBack);
                    return;
                }
                player.closeContainer();
                player.openMenu(new SimpleMenuProvider(
                    (id, inventory, ignored) -> new HuntChoiceMenu(id, inventory, player, site, onBack,
                        new SimpleContainer(QuestMenu.SIZE)), Component.literal("The Hunt")));
                return;
            }
            QuestService.accept(player, quest);
            QuestMenu.open(player, site, onBack);
        }

        private void refresh() {
            for (int slot = 0; slot < SIZE; slot++) {
                display.setItem(slot, ShrineMenu.icon(Items.STAINED_GLASS_PANE.gray(), " "));
            }
            String blocker = QuestService.canAccept(player, quest) ? "Ready to accept."
                : QuestService.acceptanceBlockReason(player, quest);
            display.setItem(SUMMARY, ShrineMenu.icon(quest.icon(), quest.displayName(),
                "Type: " + (QuestRevamp.side(player,quest) > 0 ? "Trial" : "Contract"),
                blocker));
            display.setItem(OBJECTIVE, ShrineMenu.icon(Items.PAPER, "Objective",
                QuestService.objectiveDescription(player, quest)));
            display.setItem(REWARD, ShrineMenu.icon(Items.GOLD_INGOT, "Reward",
                QuestService.rewardDescription(player, quest), "Bonus: " + QuestService.bonusDescription(player, quest)));
            List<String> timing = new java.util.ArrayList<>();
            timing.add(QuestService.durationDescription(quest));
            timing.add(QuestService.cooldownRuleDescription(quest));
            String specific = QuestService.cooldownStatus(player, quest);
            if (specific.isBlank()) timing.add("Cooldown: Ready");
            else timing.addAll(List.of(specific.split(" \\| ")));
            display.setItem(TIMING, ShrineMenu.icon(Items.CLOCK, "Duration / Cooldown",
                timing.toArray(String[]::new)));
            display.setItem(CONFIRM, ShrineMenu.icon(Items.EMERALD, "Confirm / Accept"));
            display.setItem(BACK, ShrineMenu.icon(Items.ARROW, "Cancel / Back"));
        }
    }

    private static final class DuelConsentMenu extends KarmaMenu {
        private static final int ROWS = 3;
        private static final int SIZE = ROWS * 9;
        private static final int SUMMARY = 4;
        private static final int OBJECTIVE = 10;
        private static final int REWARD = 13;
        private static final int DECLINE = 20;
        private static final int ACCEPT = 22;
        private static final int BACK = 24;
        private final ShrineSite site;
        private final Runnable onBack;
        private final ServerPlayer challenger;

        private DuelConsentMenu(int id, Inventory inventory, ServerPlayer player, ShrineSite site,
                Runnable onBack, ServerPlayer challenger, SimpleContainer display) {
            super(MenuType.GENERIC_9x3, id, inventory, display, ROWS, player);
            this.site = site;
            this.onBack = onBack == null ? () -> {} : onBack;
            this.challenger = challenger;
            refresh();
        }

        @Override public void clicked(int slotId, int button, ContainerInput input, Player clicker) {
            if (clicker != player) return;
            if (slotId >= 0 && slotId < SIZE) {
                if (input == ContainerInput.PICKUP && button == 0) handle(slotId);
                broadcastFullState();
                return;
            }
            super.clicked(slotId, button, input, clicker);
        }

        @Override public ItemStack quickMoveStack(Player clicker, int slotId) { return ItemStack.EMPTY; }
        @Override public boolean canDragTo(Slot slot) { return slots.indexOf(slot) >= SIZE; }
        @Override public boolean stillValid(Player other) {
            if (other != player || !other.isAlive()) return false;
            if (site == null) return true;
            if (other.level().dimension() != net.minecraft.world.level.Level.OVERWORLD) return false;
            BlockPos core = site.actualLodestonePosition();
            return other.distanceToSqr(core.getX() + 0.5D, core.getY() + 0.5D, core.getZ() + 0.5D) <= 64.0D;
        }

        private void handle(int slotId) {
            if (slotId == BACK) {
                QuestMenu.open(player, site, onBack);
            } else if (slotId == DECLINE) {
                QuestService.declineDuel(player, challenger);
                QuestMenu.open(player, site, onBack);
            } else if (slotId == ACCEPT) {
                QuestService.acceptDuel(player, challenger);
                QuestMenu.open(player, site, onBack);
            }
        }

        private void refresh() {
            for (int slot = 0; slot < SIZE; slot++) display.setItem(slot, ShrineMenu.icon(Items.STAINED_GLASS_PANE.gray(), " "));
            boolean pending = QuestService.pendingDuelChallenger(player) == challenger;
            display.setItem(SUMMARY, ShrineMenu.icon(Items.IRON_SWORD, "Duel Consent",
                "Challenger: " + challenger.getName().getString(),
                pending ? "The Duel starts only if both players consent." : "This invitation expired."));
            display.setItem(OBJECTIVE, ShrineMenu.icon(Items.PAPER, "Objective",
                QuestService.objectiveDescription(challenger, QuestService.QuestId.DUEL)));
            display.setItem(REWARD, ShrineMenu.icon(Items.GOLD_INGOT, "Winner's Reward",
                QuestService.rewardDescription(challenger, QuestService.QuestId.DUEL)));
            display.setItem(DECLINE, ShrineMenu.icon(Items.BARRIER, "Decline"));
            display.setItem(ACCEPT, ShrineMenu.icon(Items.EMERALD, "Accept Duel",
                "Active quests: " + QuestService.activeCount(player) + "/1",
                QuestService.generalCooldownStatus(player)));
            display.setItem(BACK, ShrineMenu.icon(Items.ARROW, "Back"));
        }
    }

    private static final class HuntChoiceMenu extends KarmaMenu {
        private final ShrineSite site;
        private final Runnable onBack;
        private final int size;
        private static final int HEADER = 13;
        private static final int BECOME_HUNTED = 29;
        private static final int[] HUNTS = {31, 32};
        private static final int CANCEL_HUNT = 49;
        private static final int BACK = 53;

        private HuntChoiceMenu(int id, Inventory inventory, ServerPlayer player, ShrineSite site,
                Runnable onBack, SimpleContainer display) {
            super(MenuType.GENERIC_9x6, id, inventory, display, ROWS, player);
            this.site = site;
            this.onBack = onBack == null ? () -> {} : onBack;
            this.size = SIZE;
            refresh();
        }

        @Override
        public void clicked(int slotId, int button, ContainerInput input, Player clicker) {
            if (clicker != player) return;
            if (slotId >= 0 && slotId < size) {
                if (input == ContainerInput.PICKUP && button == 0) handle(slotId);
                broadcastFullState();
                return;
            }
            super.clicked(slotId, button, input, clicker);
        }

        @Override public ItemStack quickMoveStack(Player clicker, int slotId) { return ItemStack.EMPTY; }
        @Override public boolean canDragTo(Slot slot) { return slots.indexOf(slot) >= size; }
        @Override public boolean stillValid(Player other) {
            if (other != player || !other.isAlive()) return false;
            if (site == null) return true;
            if (other.level().dimension() != net.minecraft.world.level.Level.OVERWORLD) return false;
            BlockPos core = site.actualLodestonePosition();
            return other.distanceToSqr(core.getX() + 0.5D, core.getY() + 0.5D, core.getZ() + 0.5D) <= 64.0D;
        }

        private void handle(int slotId) {
            if (slotId == BACK) { QuestMenu.open(player, site, onBack); return; }
            if (slotId == BECOME_HUNTED) { QuestService.accept(player, QuestService.QuestId.HUNT); refresh(); return; }
            if (slotId == CANCEL_HUNT) {
                HuntService.cancelParticipation(player.level().getServer(), player);
                QuestMenu.open(player, site, onBack);
                return;
            }
            for (int i = 0; i < HUNTS.length; i++) {
                if (slotId != HUNTS[i]) continue;
                List<HuntState.HuntRecord> hunts = QuestService.availableHuntsForHunter(player);
                if (i < hunts.size()) QuestService.joinHunt(player, hunts.get(i).huntedName());
                refresh();
                return;
            }
        }

        private void refresh() {
            for (int slot = 0; slot < size; slot++) display.setItem(slot, ShrineMenu.icon(Items.STAINED_GLASS_PANE.gray(), " "));
            boolean light = QuestService.canBecomeHunted(player);
            boolean dark = QuestService.canJoinHunt(player);
            display.setItem(HEADER, ShrineMenu.icon(Items.COMPASS, "The Hunt",
                QuestService.isActive(player, QuestService.QuestId.HUNT)
                    ? QuestService.status(player, QuestService.QuestId.HUNT)
                    : light ? "Become the Hunted." : "The Hunted role is unavailable.",
                dark ? "Join an active Hunt as a Hunter." : "The Hunter role is unavailable.",
                QuestService.cooldownRuleDescription(QuestService.QuestId.HUNT),
                QuestService.cooldownStatus(player, QuestService.QuestId.HUNT)));
            if (light) display.setItem(BECOME_HUNTED, ShrineMenu.icon(Items.GLOWSTONE, "Become the Hunted",
                QuestService.isActive(player, QuestService.QuestId.HUNT) ? "Already active." : "Accept the three-day Hunt."));
            if (dark) {
                List<HuntState.HuntRecord> hunts = QuestService.availableHuntsForHunter(player);
                for (int i = 0; i < HUNTS.length; i++) {
                    if (i < hunts.size()) display.setItem(HUNTS[i], ShrineMenu.icon(Items.COMPASS,
                        "Join: " + hunts.get(i).huntedName(), "Become a Hunter in this active Hunt."));
                }
            }
            if (QuestService.isActive(player, QuestService.QuestId.HUNT)) display.setItem(CANCEL_HUNT,
                ShrineMenu.icon(Items.BARRIER, "Cancel The Hunt", "Ends Hunt participation without a reward.",
                    "The normal Quest slot and cooldown are unchanged."));
            display.setItem(BACK, ShrineMenu.icon(Items.ARROW, "Back"));
        }
    }
}
