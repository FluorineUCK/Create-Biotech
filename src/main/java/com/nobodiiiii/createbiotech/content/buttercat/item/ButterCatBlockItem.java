package com.nobodiiiii.createbiotech.content.buttercat.item;

import java.util.function.Consumer;

import com.simibubi.create.foundation.item.render.SimpleCustomRenderer;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

/**
 * Block item for the cute cat on a shaft and the butter cat engine. Its model carries only
 * the item transforms; the cat itself is drawn by {@link ButterCatItemRenderer} so the stack
 * shows the breed it was picked up with.
 */
public class ButterCatBlockItem extends BlockItem {

	public ButterCatBlockItem(Block block, Properties properties) {
		super(block, properties);
	}

	@Override
	@OnlyIn(Dist.CLIENT)
	public void initializeClient(Consumer<IClientItemExtensions> consumer) {
		consumer.accept(SimpleCustomRenderer.create(this, new ButterCatItemRenderer()));
	}
}
