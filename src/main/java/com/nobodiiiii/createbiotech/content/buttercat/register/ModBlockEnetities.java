package com.nobodiiiii.createbiotech.content.buttercat.register;

import com.nobodiiiii.createbiotech.content.buttercat.block.ButterCatEngineBlockEntity;
import com.tterrag.registrate.util.entry.BlockEntityEntry;

import static com.nobodiiiii.createbiotech.content.buttercat.ButterCatModule.REGISTRATE;

public class ModBlockEnetities {

    public static final BlockEntityEntry<ButterCatEngineBlockEntity> BUTTER_CAT_ENGINE_BE= REGISTRATE
            .blockEntity("butter_cat_engine_be", ButterCatEngineBlockEntity::new)
            .validBlocks(ModBlocks.CUTE_CAT_ON_SHAFT, ModBlocks.BUTTER_CAT_ENGINE)
            .register();

    public static void register() {}

}

