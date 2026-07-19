package com.yision.allay.item.allaycourier;

import java.util.function.Consumer;

import com.simibubi.create.foundation.item.render.SimpleCustomRenderer;
import com.yision.allay.client.render.AllayCourierItemRenderer;

import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

public class IncompleteAllayCourierItem extends Item {
	public IncompleteAllayCourierItem(Properties properties) {
		super(properties);
	}

	@SuppressWarnings("removal")
	@Override
	@OnlyIn(Dist.CLIENT)
	public void initializeClient(Consumer<IClientItemExtensions> consumer) {
		consumer.accept(SimpleCustomRenderer.create(this, new AllayCourierItemRenderer(false)));
	}
}
