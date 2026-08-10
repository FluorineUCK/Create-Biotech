package com.nobodiiiii.createbiotech.content.beltsurface;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * The single Create-compatible item surface shared by magma belts and the FRONT track of slime belts.
 * The slime BACK track intentionally does not implement this contract.
 */
public interface StandardItemBeltPort {

	BlockPos createBiotech$getBlockPos();

	boolean createBiotech$isHorizontalItemPort();

	boolean createBiotech$addressesItemPort(Direction side);

	boolean createBiotech$canInsertIntoItemPort(Direction side);

	ItemStack createBiotech$insertIntoItemPort(ItemStack stack, Direction side, boolean simulate);

	IItemHandler createBiotech$getItemHandler();

	Direction createBiotech$getMovementFacing();

	float createBiotech$getSpeed();

	float createBiotech$getDirectionAwareSpeed();

	Vec3 createBiotech$getEjectionPosition();
}
