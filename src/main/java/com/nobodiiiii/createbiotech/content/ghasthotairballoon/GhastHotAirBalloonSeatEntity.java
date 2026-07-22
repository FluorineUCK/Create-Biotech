package com.nobodiiiii.createbiotech.content.ghasthotairballoon;

import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBEntityTypes;

import dev.ryanhcode.sable.companion.SubLevelAccess;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;

public class GhastHotAirBalloonSeatEntity extends Entity implements IEntityWithComplexSpawn {

	public static final double GHAST_PASSENGER_Y_OFFSET = 7 / 16d;
	private static final String STATION_POS_TAG = "StationPos";
	private static final String STATION_SPACE_TAG = "StationSpace";

	@Nullable
	private BlockPos stationPos;
	@Nullable
	private UUID stationSubLevelId;

	public GhastHotAirBalloonSeatEntity(EntityType<?> type, Level level) {
		super(type, level);
		noPhysics = true;
	}

	public GhastHotAirBalloonSeatEntity(Level level, BlockPos stationPos) {
		this(level, stationPos, SubLevelCompat.getSpaceId(level, stationPos));
	}

	public GhastHotAirBalloonSeatEntity(Level level, BlockPos stationPos,
		@Nullable UUID stationSubLevelId) {
		this(CBEntityTypes.GHAST_HOT_AIR_BALLOON_SEAT.get(), level);
		this.stationPos = stationPos.immutable();
		this.stationSubLevelId = stationSubLevelId;
		moveToStation();
		setOldPosAndRot();
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
		// The seat itself is already at the projected ghast passenger point. Keeping both the
		// seat and ghast in outer-world coordinates avoids ever placing a ghast in Sable's plot.
		callback.accept(passenger, this.getX(), this.getY(), this.getZ());
	}

	@Override
	public void setDeltaMovement(Vec3 motion) {}

	@Override
	public void tick() {
		super.tick();
		if (level().isClientSide)
			return;
		if (!isVehicle() || !isStationValid()) {
			discard();
			return;
		}

		moveToStation();
		if (getFirstPassenger() instanceof Ghast ghast) {
			SubLevelAccess subLevel = SubLevelCompat.getContaining(level(), stationPos);
			float localYaw = GhastHotAirBalloonAssemblyStationBlock.getFacingYaw(
				level().getBlockState(stationPos));
			float worldYaw = SubLevelCompat.localYawToWorld(subLevel, localYaw);
			ghast.setYRot(worldYaw);
			ghast.setYBodyRot(worldYaw);
			ghast.setYHeadRot(worldYaw);
		}
	}

	public boolean belongsTo(Level level, BlockPos stationPos) {
		return this.stationPos != null && this.stationPos.equals(stationPos)
			&& SubLevelCompat.matchesSpace(level, stationPos, stationSubLevelId);
	}

	private boolean isStationValid() {
		if (stationPos == null || !level().isLoaded(stationPos))
			return false;
		if (!SubLevelCompat.matchesSpace(level(), stationPos, stationSubLevelId))
			return false;
		return level().getBlockState(stationPos).is(CBBlocks.GHAST_HOT_AIR_BALLOON_ASSEMBLY_STATION.get());
	}

	private void moveToStation() {
		if (stationPos == null)
			return;
		Vec3 position = GhastHotAirBalloonAssemblyStationBlock.getGhastDockingWorldPosition(level(), stationPos);
		setPos(position.x, position.y, position.z);
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
	public void writeSpawnData(RegistryFriendlyByteBuf buffer) {
		buffer.writeBoolean(stationPos != null);
		if (stationPos != null)
			buffer.writeBlockPos(stationPos);
		buffer.writeBoolean(stationSubLevelId != null);
		if (stationSubLevelId != null)
			buffer.writeUUID(stationSubLevelId);
	}

	@Override
	public void readSpawnData(RegistryFriendlyByteBuf buffer) {
		stationPos = buffer.readBoolean() ? buffer.readBlockPos() : null;
		stationSubLevelId = buffer.readBoolean() ? buffer.readUUID() : null;
	}

	@Override
	protected void readAdditionalSaveData(CompoundTag tag) {
		stationPos = NbtUtils.readBlockPos(tag, STATION_POS_TAG).orElseGet(() -> blockPosition().below());
		stationSubLevelId = tag.hasUUID(STATION_SPACE_TAG) ? tag.getUUID(STATION_SPACE_TAG)
			: SubLevelCompat.getSpaceId(level(), stationPos);
	}

	@Override
	protected void addAdditionalSaveData(CompoundTag tag) {
		if (stationPos != null)
			tag.put(STATION_POS_TAG, NbtUtils.writeBlockPos(stationPos));
		if (stationSubLevelId != null)
			tag.putUUID(STATION_SPACE_TAG, stationSubLevelId);
	}

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
