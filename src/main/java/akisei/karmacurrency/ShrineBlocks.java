package akisei.karmacurrency;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Vanilla block aliases used internally by the shrine system. */
public final class ShrineBlocks {
	/**
	 * Hidden physical shrine marker. This intentionally uses a vanilla block so
	 * vanilla clients do not need the Karma Is Currency block registry entry.
	 */
	public static final Block SHRINE_CORE = Blocks.STRUCTURE_VOID;

	private ShrineBlocks() {
	}

	public static void initialize() {
		// Kept as a no-op so existing initialization code remains unchanged.
	}
}
