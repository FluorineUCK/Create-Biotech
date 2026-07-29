package com.nobodiiiii.createbiotech.content.automaticfishreleasemachine;

import java.util.function.Consumer;

import com.simibubi.create.content.kinetics.waterwheel.LargeWaterWheelBlockItem;
import com.simibubi.create.foundation.item.render.SimpleCustomRenderer;

import net.minecraft.world.level.block.Block;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

public class AutomaticFishReleaseMachineItem extends LargeWaterWheelBlockItem {

	public AutomaticFishReleaseMachineItem(Block block, Properties properties) {
		super(block, properties);
	}

	@Override
	@OnlyIn(Dist.CLIENT)
	public void initializeClient(Consumer<IClientItemExtensions> consumer) {
		consumer.accept(SimpleCustomRenderer.create(this, new AutomaticFishReleaseMachineItemRenderer()));
	}
}
