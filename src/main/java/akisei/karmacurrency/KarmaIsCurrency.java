package akisei.karmacurrency;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KarmaIsCurrency implements ModInitializer {
	public static final String MOD_ID = "karmaiscurrency";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		KarmaGameRules.KARMA_MENU_ENABLED.toString();
		// Force registration of the persistent player attachment before any player data is loaded.
		KarmaPlayerState.ATTACHMENT.toString();
		HuntService.register();
		ShrineBlocks.initialize();
		ShrineFoundationProcessor.register();
		ShrineRingPlacement.register();
		ShrineConfig.load();
		StatueNames.load();
		KarmaOfferingConfig.load();
		QuestConfig.load();
		ServerLifecycleEvents.SERVER_STARTED.register(server -> { KarmaShopCatalog.reload(); KicCycle.bindServer(server); });
		ServerLifecycleEvents.SERVER_STOPPING.register(KicCycle::unbindServer);
		KarmaEvents.register();
		KarmaCommands.register();
		ShrineManager.register();
		ShrineCommands.register();
		ShrineInteractions.register();
		LOGGER.info("Karma Is Currency loaded");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
