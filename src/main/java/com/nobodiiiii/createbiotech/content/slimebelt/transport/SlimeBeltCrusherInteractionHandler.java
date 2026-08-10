package com.nobodiiiii.createbiotech.content.slimebelt.transport;

import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltLoopGeometry.Track;
import com.nobodiiiii.createbiotech.content.beltsurface.CrusherInteractionCore;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlock;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import net.neoforged.neoforge.items.ItemHandlerHelper;

/** Horizontal crushing-wheel interaction for the slime belt's FRONT track only. */
public final class SlimeBeltCrusherInteractionHandler {

	private SlimeBeltCrusherInteractionHandler() {}

	public static boolean checkForCrushers(SlimeBeltInventory beltInventory, TransportedItemStack currentItem,
		float nextOffset) {
		return CrusherInteractionCore.check(new SlimeBeltSurfaceTickContext(beltInventory, Track.FRONT),
			currentItem, nextOffset);
	}
}
