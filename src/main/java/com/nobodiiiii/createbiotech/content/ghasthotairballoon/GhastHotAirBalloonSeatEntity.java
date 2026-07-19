package com.nobodiiiii.createbiotech.content.ghasthotairballoon;

import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBEntityTypes;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class GhastHotAirBalloonSeatEntity extends Entity {

	public static final double GHAST_PASSENGER_Y_OFFSET = 7 / 16d;

	public GhastHotAirBalloonSeatEntity(EntityType<?> type, Level level) {
		super(type, level);
		noPhysics = true;
	}

	public GhastHotAirBalloonSeatEntity(Level level, BlockPos stationPos) {
		this(CBEntityTypes.GHAST_HOT_AIR_BALLOON_SEAT.get(), level);
		setPos(stationPos.getX() + 0.5, stationPos.getY() + 1.0, stationPos.getZ() + 0.5);
	}

	public static EntityType.Builder<?> build(EntityType.Builder<?> builder) {
		@SuppressWarnings("unchecked")
		EntityType.Builder<GhastHotAirBalloonSeatEntity> entityBuilder =
			(EntityType.Builder<GhastHotAirBalloonSeatEntity>) builder;
		return entityBuilder.sized(0.25f, 0.35f);
	}

	@Override
	public void setPos(double x, double y, double z) {
		super.setPos(x, y, z);
		AABB bb = getBoundingBox();
		Vec3 diff = new Vec3(x, y, z).subtract(bb.getCenter());
		setBoundingBox(bb.move(diff));
	}

	@Override
	protected void positionRider(Entity passenger, Entity.MoveFunction callback) {
		if (!this.hasPassenger(passenger))
			return;
		callback.accept(passenger, this.getX(), this.getY() + GHAST_PASSENGER_Y_OFFSET, this.getZ());
	}

	@Override
	public void setDeltaMovement(Vec3 motion) {}

	@Override
	public void tick() {
		if (level().isClientSide)
			return;
		boolean blockPresent = level().getBlockState(blockPosition().below()).getBlock()
			== CBBlocks.GHAST_HOT_AIR_BALLOON_ASSEMBLY_STATION.get();
		if (isVehicle() && blockPresent)
			return;
		this.discard();
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return getPassengers().isEmpty() && passenger instanceof Ghast;
	}

	@Override
	protected void removePassenger(Entity entity) {
		super.removePassenger(entity);
		if (entity instanceof Ghast ghast) {
			ghast.setNoAi(false);
			CapturedEntityBoxHelper.unmarkAiDisabledByMod(ghast);
		}
	}

	@Override
	public Vec3 getDismountLocationForPassenger(net.minecraft.world.entity.LivingEntity rider) {
		return super.getDismountLocationForPassenger(rider).add(0, 0.5f, 0);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {}

	@Override
	protected void readAdditionalSaveData(CompoundTag tag) {}

	@Override
	protected void addAdditionalSaveData(CompoundTag tag) {}

	public static class Render extends EntityRenderer<GhastHotAirBalloonSeatEntity> {
		public Render(EntityRendererProvider.Context context) {
			super(context);
		}

		@Override
		public boolean shouldRender(GhastHotAirBalloonSeatEntity entity, Frustum frustum, double x, double y, double z) {
			return false;
		}

		@Override
		public ResourceLocation getTextureLocation(GhastHotAirBalloonSeatEntity entity) {
			return null;
		}
	}
}
