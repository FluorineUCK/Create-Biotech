package com.nobodiiiii.createbiotech.content.squidprinter;

import java.util.function.Consumer;

import com.simibubi.create.content.processing.AssemblyOperatorBlockItem;
import com.simibubi.create.foundation.item.render.SimpleCustomRenderer;

import net.minecraft.world.level.block.Block;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

public class SquidPrinterItem extends AssemblyOperatorBlockItem {

	public SquidPrinterItem(Block block, Properties properties) {
		super(block, properties);
	}

	@Override
	@OnlyIn(Dist.CLIENT)
	public void initializeClient(Consumer<IClientItemExtensions> consumer) {
		consumer.accept(SimpleCustomRenderer.create(this, new SquidPrinterItemRenderer()));
	}
}
