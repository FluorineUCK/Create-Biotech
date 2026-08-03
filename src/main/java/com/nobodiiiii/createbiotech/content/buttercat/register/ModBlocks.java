package com.nobodiiiii.createbiotech.content.buttercat.register;

import com.nobodiiiii.createbiotech.content.buttercat.block.ButterCatEngineBlock;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.foundation.data.BlockStateGen;
import com.simibubi.create.foundation.data.SharedProperties;
import com.simibubi.create.foundation.data.TagGen;
import com.tterrag.registrate.util.entry.BlockEntry;
import com.tterrag.registrate.providers.ProviderType;
import com.tterrag.registrate.util.nullness.NonNullBiConsumer;

import net.minecraft.world.level.material.MapColor;

import static com.nobodiiiii.createbiotech.content.buttercat.ButterCatModule.REGISTRATE;

public class ModBlocks {
    public static final BlockEntry<ButterCatEngineBlock> CUTE_CAT_ON_SHAFT = REGISTRATE
            .block("cute_cat_on_shaft", ButterCatEngineBlock::new)
            .initialProperties(SharedProperties::stone)
            .properties(p -> p.noOcclusion().mapColor(MapColor.METAL).forceSolidOff())
            .transform(TagGen.pickaxeOnly())
            .blockstate(BlockStateGen.horizontalBlockProvider(true))
            .setData(ProviderType.BLOCKSTATE, NonNullBiConsumer.noop())
            .setData(ProviderType.LOOT, NonNullBiConsumer.noop())
            .setData(ProviderType.LANG, NonNullBiConsumer.noop())
            .onRegister(block -> BlockStressValues.CAPACITIES.register(block, () -> {
                double maxStressCapacity = maxStressCapacity();
                double maxGeneratedRpm = maxGeneratedRpm();
                return maxGeneratedRpm == 0 ? 0 : maxStressCapacity / maxGeneratedRpm;
            }))
            .onRegister(block -> BlockStressValues.RPM.register(block,
                    new BlockStressValues.GeneratedRpm(
                            (int) Math.round(CBConfigs.SERVER.butterCat.maxGeneratedRpm.getDefault()),
                            true)))
            .item()
            .model((c, p) -> p.blockItem(c, "/item"))
            .setData(ProviderType.ITEM_MODEL, NonNullBiConsumer.noop())
            .setData(ProviderType.LANG, NonNullBiConsumer.noop())
            .build()
            .register();

    public static final BlockEntry<ButterCatEngineBlock> BUTTER_CAT_ENGINE = REGISTRATE
            .block("butter_cat_engine", ButterCatEngineBlock::new)
            .initialProperties(SharedProperties::stone)
            .properties(p -> p.noOcclusion().mapColor(MapColor.METAL).forceSolidOff())
            .transform(TagGen.pickaxeOnly())
            .blockstate(BlockStateGen.horizontalBlockProvider(true))
            .setData(ProviderType.BLOCKSTATE, NonNullBiConsumer.noop())
            .setData(ProviderType.LOOT, NonNullBiConsumer.noop())
            .setData(ProviderType.LANG, NonNullBiConsumer.noop())
            .onRegister(block -> BlockStressValues.CAPACITIES.register(block, () -> {
                double maxStressCapacity = maxStressCapacity();
                double maxGeneratedRpm = maxGeneratedRpm();
                return maxGeneratedRpm == 0 ? 0 : maxStressCapacity / maxGeneratedRpm;
            }))
            .onRegister(block -> BlockStressValues.RPM.register(block,
                    new BlockStressValues.GeneratedRpm(
                            (int) Math.round(CBConfigs.SERVER.butterCat.maxGeneratedRpm.getDefault()),
                            true)))
            .item()
            .model((c, p) -> p.blockItem(c, "/item_with_bread"))
            .setData(ProviderType.ITEM_MODEL, NonNullBiConsumer.noop())
            .setData(ProviderType.LANG, NonNullBiConsumer.noop())
            .build()
            .register();

    public static void register() {}

    private static double maxStressCapacity() {
        return CBConfigs.SERVER_SPEC.isLoaded()
                ? CBConfigs.SERVER.butterCat.maxStressCapacity.get()
                : CBConfigs.SERVER.butterCat.maxStressCapacity.getDefault();
    }

    private static double maxGeneratedRpm() {
        return CBConfigs.SERVER_SPEC.isLoaded()
                ? CBConfigs.SERVER.butterCat.maxGeneratedRpm.get()
                : CBConfigs.SERVER.butterCat.maxGeneratedRpm.getDefault();
    }
}

