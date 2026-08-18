package com.nobodiiiii.createbiotech.content.allay.block.allayport;

import java.util.function.Consumer;

import com.simibubi.create.foundation.item.render.SimpleCustomRenderer;
import com.nobodiiiii.createbiotech.content.allay.client.render.AllayPortItemRenderer;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

public class AllayPortItem extends BlockItem {

	public AllayPortItem(Block block, Properties properties) {
		super(block, properties);
	}

	@SuppressWarnings("removal")
	@Override
	@OnlyIn(Dist.CLIENT)
	public void initializeClient(Consumer<IClientItemExtensions> consumer) {
		consumer.accept(SimpleCustomRenderer.create(this, new AllayPortItemRenderer()));
	}
}
