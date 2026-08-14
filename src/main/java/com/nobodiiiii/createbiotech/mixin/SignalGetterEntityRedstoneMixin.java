package com.nobodiiiii.createbiotech.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.nobodiiiii.createbiotech.content.dingdongchicken.EntityRedstoneIndex;
import com.nobodiiiii.createbiotech.content.dingdongchicken.EntityRedstoneLevelAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.SignalGetter;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SignalGetter.class)
public interface SignalGetterEntityRedstoneMixin {

	@ModifyReturnValue(method = "getSignal", at = @At("RETURN"))
	private int createBiotech$addEntitySignal(int original, BlockPos pos, Direction direction) {
		return addEntitySignal(original, pos);
	}

	@ModifyReturnValue(method = "getControlInputSignal", at = @At("RETURN"))
	private int createBiotech$addEntityControlSignal(int original, BlockPos pos, Direction direction,
		boolean diodesOnly) {
		return diodesOnly ? original : addEntitySignal(original, pos);
	}

	@ModifyReturnValue(method = "getBestNeighborSignal", at = @At("RETURN"))
	private int createBiotech$powerOccupiedBlock(int original, BlockPos pos) {
		return addEntitySignal(original, pos);
	}

	@ModifyReturnValue(method = "hasNeighborSignal", at = @At("RETURN"))
	private boolean createBiotech$powerOccupiedBlock(boolean original, BlockPos pos) {
		if (original || !((Object) this instanceof EntityRedstoneLevelAccess access))
			return original;
		EntityRedstoneIndex index = access.createBiotech$getEntityRedstoneIndex();
		return index.hasAnySources() && index.isSource(pos);
	}

	private int addEntitySignal(int original, BlockPos pos) {
		if (original >= 15 || !((Object) this instanceof EntityRedstoneLevelAccess access))
			return original;
		EntityRedstoneIndex index = access.createBiotech$getEntityRedstoneIndex();
		if (!index.hasAnySources())
			return original;
		return index.isSource(pos) ? 15 : original;
	}
}
