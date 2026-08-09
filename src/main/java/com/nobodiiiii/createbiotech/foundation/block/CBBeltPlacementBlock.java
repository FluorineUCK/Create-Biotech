package com.nobodiiiii.createbiotech.foundation.block;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.schematic.requirement.SpecialBlockItemRequirement;
import com.simibubi.create.content.kinetics.belt.BeltPart;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import com.simibubi.create.content.schematics.requirement.ItemRequirement.ItemUseType;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Placement hooks required to build any Biotech belt as one atomic chain. */
public interface CBBeltPlacementBlock extends CBBeltChainBlock, SpecialBlockItemRequirement {

	ItemStack createBiotech$connectorStack();

	void createBiotech$createChain(Level level, BlockPos start, BlockPos end);

	@Override
	default ItemRequirement getRequiredItems(BlockState state, BlockEntity blockEntity) {
		List<ItemStack> required = new ArrayList<>(2);
		BeltPart part = state.getValue(createBiotech$partProperty());
		if (part != BeltPart.MIDDLE)
			required.add(AllBlocks.SHAFT.asStack());
		if (part == BeltPart.START)
			required.add(createBiotech$connectorStack());
		return required.isEmpty() ? ItemRequirement.NONE : new ItemRequirement(ItemUseType.CONSUME, required);
	}
}
