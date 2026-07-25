package com.nobodiiiii.createbiotech.mixin;

import java.util.Comparator;
import java.util.Optional;

import com.nobodiiiii.createbiotech.registry.CBFluids;
import com.nobodiiiii.createbiotech.registry.CBPoiTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.portal.PortalForcer;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = PortalForcer.class, priority = 1100)
public abstract class PortalForcerMixin {

	@Shadow
	@Final
	protected ServerLevel level;

	@Inject(method = "findClosestPortalPosition", at = @At("RETURN"), cancellable = true)
	private void createBiotech$includeTeleportationPoi(BlockPos searchOrigin, boolean isNether,
		WorldBorder worldBorder, CallbackInfoReturnable<Optional<BlockPos>> cir) {
		int searchRadius = isNether ? 16 : 128;
		Comparator<BlockPos> nearestPortal = Comparator
			.<BlockPos>comparingDouble(pos -> pos.distSqr(searchOrigin))
			.thenComparingInt(Vec3i::getY);

		Optional<BlockPos> teleportationPortal = level.getPoiManager()
			.getInSquare(holder -> holder.is(CBPoiTypes.TELEPORTATION_KEY), searchOrigin,
				searchRadius, PoiManager.Occupancy.ANY)
			.map(PoiRecord::getPos)
			.filter(worldBorder::isWithinBounds)
			.filter(pos -> level.getFluidState(pos)
				.getType()
				.isSame(CBFluids.TELEPORTATION.get()))
			.min(nearestPortal);

		if (teleportationPortal.isEmpty())
			return;

		Optional<BlockPos> originalPortal = cir.getReturnValue();
		if (originalPortal.isEmpty()
			|| nearestPortal.compare(teleportationPortal.get(), originalPortal.get()) < 0)
			cir.setReturnValue(teleportationPortal);
	}
}
