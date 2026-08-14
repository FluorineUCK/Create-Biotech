package com.nobodiiiii.createbiotech.content.dingdongchicken;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.registry.CBEntityTypes;
import com.simibubi.create.AllSoundEvents;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

public class DingDongChickenEntity extends Chicken {

	/** Matches Create's {@code DeskBellBlockEntity#ding()} timer. */
	public static final int SIGNAL_DURATION = 20;

	private static final EntityDataAccessor<Boolean> POWERED =
		SynchedEntityData.defineId(DingDongChickenEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Integer> DING_SEQUENCE =
		SynchedEntityData.defineId(DingDongChickenEntity.class, EntityDataSerializers.INT);

	private int poweredTicks;
	private int clientBellAnimationTicks;
	private boolean sourceRegistered;
	private long registeredSourcePosition;

	public DingDongChickenEntity(EntityType<? extends Chicken> entityType, Level level) {
		super(entityType, level);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(POWERED, false);
		builder.define(DING_SEQUENCE, 0);
	}

	@Override
	public void aiStep() {
		super.aiStep();
		if (level().isClientSide) {
			if (clientBellAnimationTicks > 0)
				clientBellAnimationTicks--;
			return;
		}

		if (poweredTicks <= 0) {
			unregisterSource();
			return;
		}

		syncSourcePosition();
		poweredTicks--;
		if (poweredTicks == 0) {
			entityData.set(POWERED, false);
			unregisterSource();
		}
	}

	@Override
	public InteractionResult mobInteract(Player player, InteractionHand hand) {
		InteractionResult original = super.mobInteract(player, hand);
		if (original.consumesAction())
			return original;

		AllSoundEvents.DESK_BELL_USE.play(level(), player, blockPosition());
		if (!level().isClientSide)
			activate();
		return InteractionResult.sidedSuccess(level().isClientSide);
	}

	public void activate() {
		poweredTicks = SIGNAL_DURATION;
		entityData.set(POWERED, true);
		entityData.set(DING_SEQUENCE, entityData.get(DING_SEQUENCE) + 1);
		syncSourcePosition();
	}

	public boolean isPowered() {
		return entityData.get(POWERED);
	}

	public float getBellAnimation(float partialTick) {
		return Math.max(0, clientBellAnimationTicks - partialTick) / SIGNAL_DURATION;
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
		super.onSyncedDataUpdated(key);
		if (DING_SEQUENCE.equals(key) && level().isClientSide)
			clientBellAnimationTicks = SIGNAL_DURATION;
	}

	@Override
	public void onAddedToLevel() {
		super.onAddedToLevel();
		if (poweredTicks > 0)
			syncSourcePosition();
	}

	@Override
	public void remove(Entity.RemovalReason reason) {
		unregisterSource();
		super.remove(reason);
	}

	@Override
	public void onRemovedFromLevel() {
		unregisterSource();
		super.onRemovedFromLevel();
	}

	@Override
	public void addAdditionalSaveData(CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		tag.putInt("DingDongPowerTicks", poweredTicks);
	}

	@Override
	public void readAdditionalSaveData(CompoundTag tag) {
		super.readAdditionalSaveData(tag);
		poweredTicks = Math.max(0, tag.getInt("DingDongPowerTicks"));
		entityData.set(POWERED, poweredTicks > 0);
	}

	@Nullable
	@Override
	public Chicken getBreedOffspring(ServerLevel level, AgeableMob otherParent) {
		return CBEntityTypes.DING_DONG_CHICKEN.get().create(level);
	}

	private void syncSourcePosition() {
		if (!(level() instanceof ServerLevel serverLevel))
			return;

		long currentPosition = blockPosition().asLong();
		if (sourceRegistered && currentPosition == registeredSourcePosition)
			return;

		EntityRedstoneIndex index = EntityRedstoneIndex.get(serverLevel);
		long oldPosition = registeredSourcePosition;
		boolean oldPositionChanged = sourceRegistered && index.removeSource(oldPosition);
		boolean newPositionChanged = index.addSource(currentPosition);

		registeredSourcePosition = currentPosition;
		sourceRegistered = true;

		// Both mutations are complete before consumers are invited to query the final state.
		if (oldPositionChanged)
			index.notifySourceChanged(serverLevel, oldPosition);
		if (newPositionChanged)
			index.notifySourceChanged(serverLevel, currentPosition);
	}

	private void unregisterSource() {
		if (!sourceRegistered)
			return;
		if (level() instanceof ServerLevel serverLevel) {
			EntityRedstoneIndex index = EntityRedstoneIndex.get(serverLevel);
			if (index.removeSource(registeredSourcePosition))
				index.notifySourceChanged(serverLevel, registeredSourcePosition);
		}
		sourceRegistered = false;
	}
}
