package com.nobodiiiii.createbiotech.mixin;

import java.util.function.Predicate;
import java.util.stream.Stream;

import com.nobodiiiii.createbiotech.registry.CBFluids;
import com.nobodiiiii.createbiotech.registry.CBPoiTypes;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.portal.PortalForcer;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PortalForcer.class)
public abstract class PortalForcerMixin {

	@Shadow
	@Final
	protected ServerLevel level;

	@WrapOperation(method = "findClosestPortalPosition",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/ai/village/poi/PoiManager;getInSquare(Ljava/util/function/Predicate;Lnet/minecraft/core/BlockPos;ILnet/minecraft/world/entity/ai/village/poi/PoiManager$Occupancy;)Ljava/util/stream/Stream;"))
	private Stream<PoiRecord> createBiotech$includeTeleportationPoi(PoiManager poiManager,
		Predicate<Holder<PoiType>> portalType, BlockPos searchOrigin, int searchRadius,
		PoiManager.Occupancy occupancy, Operation<Stream<PoiRecord>> original) {
		return original.call(poiManager,
			portalType.or(holder -> holder.is(CBPoiTypes.TELEPORTATION_KEY)),
			searchOrigin, searchRadius, occupancy);
	}

	@WrapOperation(method = "findClosestPortalPosition",
		at = @At(value = "INVOKE",
			target = "Ljava/util/stream/Stream;filter(Ljava/util/function/Predicate;)Ljava/util/stream/Stream;",
			ordinal = 1))
	private Stream<BlockPos> createBiotech$acceptTeleportationFluid(Stream<BlockPos> positions,
		Predicate<BlockPos> validPortalBlock, Operation<Stream<BlockPos>> original) {
		return original.call(positions,
			validPortalBlock.or(pos -> level.getFluidState(pos)
				.getType()
				.isSame(CBFluids.TELEPORTATION.get())));
	}
}
