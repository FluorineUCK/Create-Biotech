package com.nobodiiiii.createbiotech.content.buttercat.fluid;

import com.nobodiiiii.createbiotech.foundation.feature.CBFeature;

import net.minecraft.core.BlockPos;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.world.item.DispensibleContainerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DispenserBlock;

public class CreamBucketDispenseBehavior extends DefaultDispenseItemBehavior {
	private static final DefaultDispenseItemBehavior DEFAULT = new DefaultDispenseItemBehavior();

	@Override
	protected ItemStack execute(BlockSource source, ItemStack stack) {
		if (!CBFeature.BUTTER_CAT.isEnabled())
			return stack;

		DispensibleContainerItem container = (DispensibleContainerItem) stack.getItem();
		BlockPos target = source.pos().relative(source.state().getValue(DispenserBlock.FACING));
		Level level = source.level();
		if (container.emptyContents(null, level, target, null, stack))
			return new ItemStack(Items.BUCKET);
		return DEFAULT.dispense(source, stack);
	}
}
