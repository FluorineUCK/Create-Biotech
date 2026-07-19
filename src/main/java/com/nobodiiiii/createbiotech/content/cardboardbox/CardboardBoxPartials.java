package com.nobodiiiii.createbiotech.content.cardboardbox;

import net.minecraft.core.registries.Registries;

import net.minecraft.core.registries.BuiltInRegistries;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.registry.CBItems;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.logistics.box.PackageItem;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;


public class CardboardBoxPartials {

	public static final ResourceLocation SMALL_BOX_LOGISTICS_LOCATION =
		CreateBiotech.asResource("item/small_cardboard_box_logistics");
	public static final ResourceLocation LARGE_BOX_LOGISTICS_LOCATION =
		CreateBiotech.asResource("item/large_cardboard_box_logistics");
	public static final PartialModel SMALL_BOX_LOGISTICS = PartialModel.of(SMALL_BOX_LOGISTICS_LOCATION);
	public static final PartialModel LARGE_BOX_LOGISTICS = PartialModel.of(LARGE_BOX_LOGISTICS_LOCATION);

	private CardboardBoxPartials() {}

	public static void register() {
		register(CBItems.CARDBOARD_BOX.get(), SMALL_BOX_LOGISTICS);
		register(CBItems.LARGE_CARDBOARD_BOX.get(), LARGE_BOX_LOGISTICS);
	}

	public static ResourceLocation getLogisticsModelLocation(ItemStack stack) {
		return stack.is(CBItems.LARGE_CARDBOARD_BOX.get()) ? LARGE_BOX_LOGISTICS_LOCATION : SMALL_BOX_LOGISTICS_LOCATION;
	}

	private static void register(Item item, PartialModel box) {
		ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
		if (key == null || !(item instanceof PackageItem packageItem))
			return;

		AllPartialModels.PACKAGES.put(key, box);
		AllPartialModels.PACKAGE_RIGGING.put(key, PartialModel.of(packageItem.style.getRiggingModel()));
	}
}
