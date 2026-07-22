package com.yision.allay.entity.courier;

import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.registry.CBParticleTypes;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.yision.allay.item.allaycourier.AllayCourierItem;
import com.yision.allay.logistics.courier.AllayCourierTask;
import com.yision.allay.logistics.courier.AllayCourierTaskManager;
import com.yision.allay.registry.AllEntityTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * A cargo-carrying Allay. Movement, steering and animation come from vanilla {@link Allay}; this
 * class adds direct courier targeting, task metadata and placed-item interactions.
 */
public class AllayCourierEntity extends Allay implements Container {
	private static final int TASK_VALIDATION_INTERVAL = 20;
	private static final int INVALID_TASK_GRACE_CHECKS = 2;

	private static final EntityDataAccessor<ItemStack> DATA_PACKAGE =
		SynchedEntityData.defineId(AllayCourierEntity.class, EntityDataSerializers.ITEM_STACK);
	private static final EntityDataAccessor<Byte> DATA_PHASE =
		SynchedEntityData.defineId(AllayCourierEntity.class, EntityDataSerializers.BYTE);
	private static final EntityDataAccessor<Vector3f> DATA_LAUNCH_DIRECTION =
		SynchedEntityData.defineId(AllayCourierEntity.class, EntityDataSerializers.VECTOR3);
	private static final EntityDataAccessor<Byte> DATA_MISSION =
		SynchedEntityData.defineId(AllayCourierEntity.class, EntityDataSerializers.BYTE);

	private Vec3 launchDirection = new Vec3(0, 0, 1);
	private @Nullable Vec3 preciseFlightTarget;
	private double preciseFlightSpeed = 1.0;
	private @Nullable Vec3 guidedFlightTarget;
	private Vec3 guidedLookDirection = new Vec3(0, 0, 1);
	private double guidedMaxSpeed;
	private double guidedAcceleration;
	private boolean guidedPathLocked;
	private Vec3 guidedTargetVelocity = Vec3.ZERO;
	private boolean renderLogisticsHat = true;
	private @Nullable UUID taskId;
	private int invalidTaskChecks;

	public AllayCourierEntity(EntityType<? extends AllayCourierEntity> type, Level level) {
		super(type, level);
		setNoGravity(true);
	}

	public static AllayCourierEntity createWaiting(Level level, ItemStack box, Vec3 launchDirection) {
		AllayCourierEntity courier = new AllayCourierEntity(AllEntityTypes.ALLAY_COURIER.get(), level);
		courier.setPackage(box);
		courier.setPhase(Phase.WAITING);
		courier.setDeltaMovement(Vec3.ZERO);
		courier.alignToDirection(launchDirection);
		return courier;
	}

	public static AllayCourierEntity createEmpty(EntityType<? extends AllayCourierEntity> type, Level level) {
		return new AllayCourierEntity(type, level);
	}

	public static AllayCourierEntity createFromTask(ServerLevel level, AllayCourierTask task) {
		AllayCourierEntity courier = new AllayCourierEntity(AllEntityTypes.ALLAY_COURIER.get(), level);
		courier.setPackage(task.box());
		courier.setMission(task.mission());
		courier.setPhase(task.phase());
		courier.taskId = task.id();
		courier.setPos(task.position());
		courier.setDeltaMovement(task.velocityOnSpawn(level));
		courier.noPhysics = true;
		courier.alignToDirection(task.launchDirection());
		courier.hasImpulse = true;
		courier.hurtMarked = true;
		return courier;
	}

	@Override
	public void tick() {
		noPhysics = getPhase() != Phase.WAITING;
		super.tick();
		noPhysics = getPhase() != Phase.WAITING;
		validateTaskBinding();
		if (isRemoved()) {
			return;
		}
		spawnFlightTrail();
	}

	private void validateTaskBinding() {
		if (level().isClientSide || getPhase() == Phase.WAITING
			|| tickCount % TASK_VALIDATION_INTERVAL != 0
			|| !AllayCourierTaskManager.isCourierValidationReady()) {
			return;
		}

		if (taskId != null && AllayCourierTaskManager.isCurrentCourier(taskId, this)) {
			invalidTaskChecks = 0;
			return;
		}
		if (++invalidTaskChecks >= INVALID_TASK_GRACE_CHECKS) {
			discard();
		}
	}

	private void spawnFlightTrail() {
		if (!level().isClientSide || getPhase() == Phase.WAITING || tickCount % 3 != 0) {
			return;
		}
		Vec3 motion = getDeltaMovement();
		if (motion.lengthSqr() < 1.0E-6) {
			return;
		}
		Vec3 trailPosition = position()
			.add(0.0, getBbHeight() * 0.5, 0.0)
			.subtract(motion.normalize().scale(getBbWidth() * 0.75));
		double noteColor = (tickCount % 24) / 24.0;
		level().addParticle(CBParticleTypes.ALLAY_COURIER_NOTE.get(),
			trailPosition.x, trailPosition.y, trailPosition.z, noteColor, 0.0, 0.0);
	}

	/**
	 * Flies straight to the destination with the vanilla Allay {@code FlyingMoveControl}.
	 * Courier flight has no physics, so blocks never interrupt or redirect the route.
	 */
	public void flyDirectlyAsVanillaAllay(Vec3 target, double speedModifier) {
		setPreciseFlightTarget(target, speedModifier);
	}

	/**
	 * Uses the same vanilla FlyingMoveControl as an ordinary Allay for the final sub-block approach.
	 */
	public void approachPreciselyAsVanillaAllay(Vec3 target, double speedModifier) {
		setPreciseFlightTarget(target, speedModifier);
	}

	private void setPreciseFlightTarget(Vec3 target, double speedModifier) {
		preciseFlightTarget = target;
		preciseFlightSpeed = speedModifier;
		guidedFlightTarget = null;
		guidedTargetVelocity = Vec3.ZERO;
	}

	/**
	 * Takes direct control of the courier for the short, visible docking path. Unlike
	 * {@link net.minecraft.world.entity.ai.control.FlyingMoveControl}, this controller accounts for
	 * the Allay's retained velocity and clamps the next movement to the remaining distance.
	 */
	public void guideAlongDockingPath(Vec3 target, Vec3 lookDirection, double maxSpeed,
		double acceleration, boolean lockToPath, Vec3 targetVelocity) {
		preciseFlightTarget = null;
		guidedFlightTarget = target;
		guidedLookDirection = horizontalDirection(lookDirection);
		guidedMaxSpeed = Math.max(0.01, maxSpeed);
		guidedAcceleration = Math.max(0.005, acceleration);
		guidedPathLocked = lockToPath;
		guidedTargetVelocity = targetVelocity;
	}

	public void clearCourierDestination() {
		preciseFlightTarget = null;
		guidedFlightTarget = null;
		guidedTargetVelocity = Vec3.ZERO;
		getNavigation().stop();
		getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
		getBrain().eraseMemory(MemoryModuleType.PATH);
	}

	public void becomePlaced(Vec3 placementPosition) {
		taskId = null;
		invalidTaskChecks = 0;
		clearCourierDestination();
		setDeltaMovement(Vec3.ZERO);
		setPos(placementPosition);
		setPhase(Phase.WAITING);
		hasImpulse = true;
		hurtMarked = true;
	}

	@Override
	protected void customServerAiStep() {
		if (getPhase() == Phase.WAITING) {
			clearCourierDestination();
			setSpeed(0);
			setYya(0);
			setZza(0);
			setDeltaMovement(Vec3.ZERO);
			setNoGravity(true);
			return;
		}

		if (hasCourierDestination()) {
			getNavigation().stop();
			getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
			getBrain().eraseMemory(MemoryModuleType.PATH);
		}
		super.customServerAiStep();
		if (hasCourierDestination()) {
			getNavigation().stop();
			getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
			getBrain().eraseMemory(MemoryModuleType.PATH);
		}
		if (guidedFlightTarget != null) {
			tickGuidedFlight();
		} else if (preciseFlightTarget != null) {
			getMoveControl().setWantedPosition(
				preciseFlightTarget.x, preciseFlightTarget.y, preciseFlightTarget.z, preciseFlightSpeed);
		}
	}

	private boolean hasCourierDestination() {
		return preciseFlightTarget != null || guidedFlightTarget != null;
	}

	private void tickGuidedFlight() {
		setSpeed(0);
		setYya(0);
		setZza(0);

		Vec3 offset = guidedFlightTarget.subtract(position());
		double distance = offset.length();
		if (distance <= 1.0E-4) {
			setDeltaMovement(guidedTargetVelocity);
			turnToward(guidedLookDirection);
			return;
		}

		Vec3 direction = offset.scale(1.0 / distance);
		double brakingSpeed = Math.sqrt(2.0 * guidedAcceleration * distance);
		double desiredSpeed = Math.min(distance, Math.min(guidedMaxSpeed, brakingSpeed));
		Vec3 desiredRelativeVelocity = direction.scale(desiredSpeed);
		Vec3 relativeVelocity = getDeltaMovement().subtract(guidedTargetVelocity);
		Vec3 nextVelocity;

		if (guidedPathLocked) {
			double forwardSpeed = Math.max(0.0, relativeVelocity.dot(direction));
			double nextSpeed = approach(forwardSpeed, desiredSpeed, guidedAcceleration);
			nextVelocity = guidedTargetVelocity.add(direction.scale(Math.min(distance, nextSpeed)));
		} else {
			Vec3 correction = desiredRelativeVelocity.subtract(relativeVelocity);
			if (correction.lengthSqr() > guidedAcceleration * guidedAcceleration) {
				correction = correction.normalize().scale(guidedAcceleration);
			}
			Vec3 nextRelativeVelocity = relativeVelocity.add(correction);
			if (nextRelativeVelocity.lengthSqr() > distance * distance) {
				nextRelativeVelocity = nextRelativeVelocity.normalize().scale(distance);
			}
			nextVelocity = guidedTargetVelocity.add(nextRelativeVelocity);
		}

		setDeltaMovement(nextVelocity);
		turnToward(guidedLookDirection);
	}

	private void turnToward(Vec3 direction) {
		float targetYaw = yawForDirection(direction);
		float nextYaw = Mth.approachDegrees(getYRot(), targetYaw, 22.5f);
		setYRot(nextYaw);
		yBodyRot = nextYaw;
		yHeadRot = nextYaw;
		setXRot(Mth.approach(getXRot(), 0.0f, 8.0f));
	}

	private static float yawForDirection(Vec3 direction) {
		return (float) (Mth.atan2(direction.z, direction.x) * Mth.RAD_TO_DEG) - 90.0f;
	}

	private static double approach(double value, double target, double step) {
		if (value < target) {
			return Math.min(value + step, target);
		}
		return Math.max(value - step, target);
	}

	@Override
	public boolean canPickUpLoot() {
		return false;
	}

	@Override
	public boolean canTakeItem(ItemStack stack) {
		return false;
	}

	@Override
	public boolean wantsToPickUp(ItemStack stack) {
		return false;
	}

	public ItemStack getPackage() {
		return getEntityData().get(DATA_PACKAGE);
	}

	public void setPackage(ItemStack box) {
		ItemStack cargo = PackageItem.isPackage(box) ? box.copy() : ItemStack.EMPTY;
		getEntityData().set(DATA_PACKAGE, cargo);
		setItemInHand(InteractionHand.MAIN_HAND, cargo.copy());
	}

	@Override
	public float getHoldingItemAnimationProgress(float partialTick) {
		return getPackage().isEmpty() ? super.getHoldingItemAnimationProgress(partialTick) : 1.0f;
	}

	public Phase getPhase() {
		return Phase.byId(getEntityData().get(DATA_PHASE));
	}

	public void setPhase(Phase phase) {
		getEntityData().set(DATA_PHASE, (byte) phase.id);
		noPhysics = phase != Phase.WAITING;
	}

	public boolean shouldRenderLogisticsHat() {
		return renderLogisticsHat;
	}

	public void setRenderLogisticsHat(boolean renderLogisticsHat) {
		this.renderLogisticsHat = renderLogisticsHat;
	}

	public Mission getMission() {
		return Mission.byId(getEntityData().get(DATA_MISSION));
	}

	public void setMission(Mission mission) {
		getEntityData().set(DATA_MISSION, (byte) mission.id);
	}

	private void setLaunchDirection(Vec3 direction) {
		launchDirection = horizontalDirection(direction);
		getEntityData().set(DATA_LAUNCH_DIRECTION,
			new Vector3f((float) launchDirection.x, 0, (float) launchDirection.z));
	}

	private void alignToDirection(Vec3 direction) {
		setLaunchDirection(direction);
		float yRot = yawForDirection(launchDirection);
		setYRot(yRot);
		yRotO = yRot;
		yBodyRot = yRot;
		yBodyRotO = yRot;
		yHeadRot = yRot;
		yHeadRotO = yRot;
		setXRot(0);
		xRotO = 0;
	}

	private static Vec3 horizontalDirection(Vec3 direction) {
		Vec3 horizontal = new Vec3(direction.x, 0, direction.z);
		return horizontal.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : horizontal.normalize();
	}

	private boolean exposesPackageContents() {
		return getPhase() == Phase.WAITING && PackageItem.isPackage(getPackage());
	}

	@Override
	public int getContainerSize() {
		return PackageItem.SLOTS;
	}

	@Override
	public boolean isEmpty() {
		if (!exposesPackageContents()) {
			return true;
		}
		ItemStackHandler contents = CapturedEntityBoxHelper.getVisiblePackageContents(getPackage());
		for (int slot = 0; slot < contents.getSlots(); slot++) {
			if (!contents.getStackInSlot(slot).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	@Override
	public ItemStack getItem(int slot) {
		if (!exposesPackageContents() || slot < 0 || slot >= PackageItem.SLOTS) {
			return ItemStack.EMPTY;
		}
		return CapturedEntityBoxHelper.getVisiblePackageContents(getPackage()).getStackInSlot(slot).copy();
	}

	@Override
	public ItemStack removeItem(int slot, int amount) {
		return ItemStack.EMPTY;
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		return ItemStack.EMPTY;
	}

	@Override
	public void setItem(int slot, ItemStack stack) {}

	@Override
	public void setChanged() {}

	@Override
	public boolean stillValid(Player player) {
		return isAlive() && exposesPackageContents() && player.distanceToSqr(this) <= 64.0;
	}

	@Override
	public void clearContent() {}

	@Override
	public ItemStack getPickedResult(HitResult target) {
		if (getPhase() != Phase.WAITING) {
			return ItemStack.EMPTY;
		}
		ItemStack picked = AllayCourierItem.createLoaded(getPackage());
		AllayCourierItem.setHeadingAngle(picked, Math.round(getYRot()));
		return picked;
	}

	@Override
	public boolean hurt(net.minecraft.world.damagesource.DamageSource damageSource, float amount) {
		if (getPhase() != Phase.WAITING) {
			return false;
		}
		if (level().isClientSide()) {
			return true;
		}
		if (!(damageSource.getEntity() instanceof Player player)) {
			return false;
		}

		ItemStack droppedStack = AllayCourierItem.createLoaded(getPackage());
		AllayCourierItem.setHeadingAngle(droppedStack, Math.round(getYRot()));
		ItemEntity itemEntity = new ItemEntity(level(), getX(), getY(), getZ(), droppedStack);
		Vec3 popMotion = player.getLookAngle().multiply(1, 0, 1);
		popMotion = popMotion.lengthSqr() > 1.0E-6 ? popMotion.normalize().scale(0.12) : Vec3.ZERO;
		itemEntity.setDeltaMovement(popMotion.add(0, 0.08, 0));
		level().addFreshEntity(itemEntity);
		level().playSound(null, blockPosition(), SoundEvents.ITEM_FRAME_BREAK, SoundSource.PLAYERS, 0.7f, 1.0f);
		discard();
		return false;
	}

	@Override
	public boolean isPickable() {
		return getPhase() == Phase.WAITING;
	}

	@Override
	public boolean canBeCollidedWith() {
		return getPhase() == Phase.WAITING;
	}

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		if (getPhase() != Phase.WAITING) {
			return InteractionResult.PASS;
		}

		ItemStack heldItem = player.getItemInHand(hand);
		if (!heldItem.isEmpty()) {
			return InteractionResult.PASS;
		}
		if (level().isClientSide()) {
			return InteractionResult.SUCCESS;
		}

		ItemStack pickedUp = AllayCourierItem.createLoaded(getPackage());
		AllayCourierItem.setHeadingAngle(pickedUp, Math.round(getYRot()));
		player.setItemInHand(hand, pickedUp);
		level().playSound(null, blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2f,
			0.75f + level().random.nextFloat());
		discard();
		return InteractionResult.SUCCESS;
	}

	@Override
	public boolean shouldBeSaved() {
		return getPhase() == Phase.WAITING;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(DATA_PACKAGE, ItemStack.EMPTY);
		builder.define(DATA_PHASE, (byte) Phase.WAITING.id);
		builder.define(DATA_LAUNCH_DIRECTION, new Vector3f(0, 0, 1));
		builder.define(DATA_MISSION, (byte) Mission.PACKAGE_TO_PLAYER.id);
	}

	@Override
	public void readAdditionalSaveData(@NotNull CompoundTag tag) {
		super.readAdditionalSaveData(tag);
		setPackage(ItemStack.parseOptional(level().registryAccess(), tag.getCompound("Package")));
		if (tag.contains("LaunchDirection")) {
			CompoundTag direction = tag.getCompound("LaunchDirection");
			setLaunchDirection(new Vec3(direction.getDouble("X"), 0, direction.getDouble("Z")));
		}
		setPhase(Phase.byId(tag.getByte("Phase")));
		if (tag.contains("Mission")) {
			setMission(Mission.byId(tag.getByte("Mission")));
		}
		taskId = tag.hasUUID("CourierTaskId") ? tag.getUUID("CourierTaskId") : null;
	}

	@Override
	public void addAdditionalSaveData(@NotNull CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		tag.put("Package", getPackage().saveOptional(level().registryAccess()));
		CompoundTag direction = new CompoundTag();
		direction.putDouble("X", launchDirection.x);
		direction.putDouble("Z", launchDirection.z);
		tag.put("LaunchDirection", direction);
		tag.putByte("Phase", (byte) getPhase().id);
		tag.putByte("Mission", (byte) getMission().id);
		if (taskId != null) {
			tag.putUUID("CourierTaskId", taskId);
		}
	}

	public enum Phase {
		WAITING(0),
		TAKEOFF(1),
		EXITING_DIMENSION(2),
		CRUISE(3),
		LANDING(4);

		private final int id;

		Phase(int id) {
			this.id = id;
		}

		public static Phase byId(byte id) {
			for (Phase phase : values()) {
				if (phase.id == id) {
					return phase;
				}
			}
			return TAKEOFF;
		}
	}

	public enum Mission {
		PACKAGE_TO_PLAYER(0),
		PACKAGE_TO_ALLAY_PORT(1),
		CARRIER_RETURN(2),
		CARRIER_RETURN_TO_PLAYER(3);

		private final int id;

		Mission(int id) {
			this.id = id;
		}

		public static Mission byId(byte id) {
			for (Mission mission : values()) {
				if (mission.id == id) {
					return mission;
				}
			}
			return PACKAGE_TO_PLAYER;
		}
	}
}
