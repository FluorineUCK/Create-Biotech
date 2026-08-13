package com.nobodiiiii.createbiotech.content.sonicdogcannon;

import java.util.function.Consumer;

import com.simibubi.create.content.equipment.potatoCannon.PotatoCannonItem;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

public class SonicDogCannonItem extends PotatoCannonItem {

	public SonicDogCannonItem(Properties properties) {
		super(properties);
	}

	@Override
	@OnlyIn(Dist.CLIENT)
	public void initializeClient(Consumer<IClientItemExtensions> consumer) {
		// The exported model already contains its gear, so it should use the regular item renderer.
	}
}
