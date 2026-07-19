package com.nobodiiiii.createbiotech.content.shulkerpackager;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.registry.CBItems;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.box.PackageStyles;
import com.simibubi.create.content.logistics.box.PackageStyles.PackageStyle;
import com.simibubi.create.foundation.item.ItemHelper;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

public class ShulkerPackageItem extends PackageItem {

	public static final PackageStyle STYLE = new PackageStyle("shulker", 12, 12, 23f, false);

	public ShulkerPackageItem(Item.Properties properties) {
		super(properties, STYLE);
		// Keep this package out of Create's global random style pools.
		PackageStyles.ALL_BOXES.remove(this);
		PackageStyles.STANDARD_BOXES.remove(this);
		PackageStyles.RARE_BOXES.remove(this);
	}

	@Override
	public String getDescriptionId() {
		return "item." + CreateBiotech.MOD_ID + ".shulker_package";
	}

	public static ItemStack containing(ItemStackHandler stacks) {
		ItemStack box = new ItemStack(CBItems.SHULKER_PACKAGE.get());
		box.set(AllDataComponents.PACKAGE_CONTENTS, ItemHelper.containerContentsFromHandler(stacks));
		return box;
	}
}
