package akisei.karmacurrency;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Keeps the lowest template layer from carving terrain where the shrine footprint intentionally
 * contains air. Higher template air is left untouched so the structure can still carve out its
 * normal interior and shape.
 */
public final class ShrineFoundationProcessor implements StructureProcessor {
    public static final Identifier ID = KarmaIsCurrency.id("shrine_foundation");
    public static final MapCodec<ShrineFoundationProcessor> MAP_CODEC = MapCodec.unit(ShrineFoundationProcessor::new);
    public static final ShrineFoundationProcessor INSTANCE = new ShrineFoundationProcessor();

    private ShrineFoundationProcessor() {
    }

    public static void register() {
        net.minecraft.core.Registry.register(BuiltInRegistries.STRUCTURE_PROCESSOR, ID, MAP_CODEC);
    }

    @Override
    public StructureTemplate.StructureBlockInfo processBlock(
            LevelReader level,
            BlockPos targetPosition,
            BlockPos referencePosition,
            BlockPos templateRelativePosition,
            StructureTemplate.StructureBlockInfo processedBlockInfo,
            StructurePlaceSettings settings) {
        if (templateRelativePosition.getY() == 0 && processedBlockInfo.state().isAir()) {
            return null;
        }
        return processedBlockInfo;
    }

    @Override
    public MapCodec<ShrineFoundationProcessor> codec() {
        return MAP_CODEC;
    }
}
