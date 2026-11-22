package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import com.nobodiiiii.createbiotech.CreateBiotech;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

public final class ComputerBlockTags {
	public static final TagKey<Block> INTERNAL_COMPONENTS = TagKey.create(Registries.BLOCK,
		CreateBiotech.asResource("computer_internal_components"));

	private ComputerBlockTags() {}
}
