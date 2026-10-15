package akisei.karmacurrency;

import java.util.Optional;
import java.util.SplittableRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.Level;

/** Temporary approximate last-known-location compass used by The Hunt. */
public final class HuntCompass {
    private static final String TAG = "KarmaHuntCompass";
    private static final String EXPIRES = "ExpiresAt";
    private static final String TARGET_X = "TargetX";
    private static final String TARGET_Y = "TargetY";
    private static final String TARGET_Z = "TargetZ";
    private static final String TARGET_DIMENSION = "TargetDimension";
    private static final String ERROR = "ErrorBlocks";

    private HuntCompass() {}

    public static ItemStack create(BlockPos lastKnown, ResourceKey<Level> dimension, long now, long seed) {
        int error = HuntConfig.current().compassErrorBlocks;
        SplittableRandom random = new SplittableRandom(seed);
        double angle = random.nextDouble(0.0, Math.PI * 2.0);
        double radius = Math.sqrt(random.nextDouble()) * error;
        int x = lastKnown.getX() + (int) Math.round(Math.cos(angle) * radius);
        int z = lastKnown.getZ() + (int) Math.round(Math.sin(angle) * radius);
        int y = lastKnown.getY();
        GlobalPos target = GlobalPos.of(dimension, new BlockPos(x, y, z));

        ItemStack compass = new ItemStack(Items.COMPASS);
        compass.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.of(target), false));
        compass.set(DataComponents.CUSTOM_NAME, Component.literal("Hunt Compass"));
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(TAG, true);
        tag.putLong(EXPIRES, now + HuntConfig.current().compassLifetimeMinutes * 60_000L);
        tag.putInt(TARGET_X, x);
        tag.putInt(TARGET_Y, y);
        tag.putInt(TARGET_Z, z);
        tag.putString(TARGET_DIMENSION, dimension.identifier().toString());
        tag.putInt(ERROR, error);
        compass.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return compass;
    }

    public static boolean isHuntCompass(ItemStack stack) {
        if (!stack.is(Items.COMPASS)) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBooleanOr(TAG, false);
    }

    public static long expiresAt(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return 0L;
        return data.copyTag().getLongOr(EXPIRES, 0L);
    }

    public static boolean isExpired(ItemStack stack, long now) {
        return isHuntCompass(stack) && expiresAt(stack) > 0L && expiresAt(stack) <= now;
    }

    public static void expireFrom(ServerPlayer player, long now) {
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (isExpired(stack, now)) stack.setCount(0);
        }
        ItemStack offhand = player.getOffhandItem();
        if (isExpired(offhand, now)) offhand.setCount(0);
    }

    public static void clearFrom(ServerPlayer player) {
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (isHuntCompass(stack)) stack.setCount(0);
        }
        if (isHuntCompass(player.getOffhandItem())) player.getOffhandItem().setCount(0);
    }

    public static Optional<BlockPos> target(ItemStack stack) {
        if (!isHuntCompass(stack)) return Optional.empty();
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return Optional.empty();
        CompoundTag tag = data.copyTag();
        return Optional.of(new BlockPos(
            tag.getIntOr(TARGET_X, 0),
            tag.getIntOr(TARGET_Y, 64),
            tag.getIntOr(TARGET_Z, 0)
        ));
    }
}
