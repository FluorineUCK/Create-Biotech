package com.nobodiiiii.createbiotech.mixin;

import java.util.Comparator;
import java.util.Optional;

import com.nobodiiiii.createbiotech.registry.CBFluids;
import com.nobodiiiii.createbiotech.registry.CBPoiTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.portal.PortalForcer;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PortalForcer.class)
public abstract class PortalForcerMixin {

	@Shadow
	@Final
	protected ServerLevel level;

	@Inject(method = "findClosestPortalPosition", at = @At("RETURN"), cancellable = true)
	private void createBiotech$includeTeleportationFluid(BlockPos searchOrigin, boolean isNether,
		WorldBorder worldBorder, CallbackInfoReturnable<Optional<BlockPos>> cir) {
		int searchRadius = isNether ? 16 : 128;
		Optional<PoiRecord> fluidPortal = level.getPoiManager()
			.getInSquare(holder -> holder.is(CBPoiTypes.TELEPORTATION_KEY), searchOrigin, searchRadius,
				PoiManager.Occupancy.ANY)
			.filter(record -> worldBorder.isWithinBounds(record.getPos()))
			.filter(record -> level.getFluidState(record.getPos())
				.getType()
				.isSame(CBFluids.TELEPORTATION.get()))
			.sorted(Comparator.<PoiRecord>comparingDouble(record -> record.getPos()
				.distSqr(searchOrigin))
				.thenComparingInt(record -> record.getPos()
					.getY()))
			.findFirst();
		if (fluidPortal.isEmpty())
			return;

		BlockPos fluidPos = fluidPortal.get()
			.getPos();
		Optional<BlockPos> existingPortal = cir.getReturnValue();
		if (existingPortal != null
			&& existingPortal.isPresent()
			&& createBiotech$comparePortalPositions(fluidPos, existingPortal.get(), searchOrigin) >= 0) {
			return;
		}

		cir.setReturnValue(Optional.of(fluidPos));
	}

	@Unique
	private int createBiotech$comparePortalPositions(BlockPos fluidPos, BlockPos existingPortal,
		BlockPos searchOrigin) {
		int distanceComparison = Double.compare(fluidPos.distSqr(searchOrigin),
			existingPortal.distSqr(searchOrigin));
		return distanceComparison != 0
			? distanceComparison
			: Integer.compare(fluidPos.getY(), existingPortal.getY());
	}
}
