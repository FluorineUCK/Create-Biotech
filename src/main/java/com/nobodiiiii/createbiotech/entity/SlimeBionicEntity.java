package com.nobodiiiii.createbiotech.entity;

import org.jetbrains.annotations.Nullable;

import com.nobodiiiii.createbiotech.content.slimemimic.SlimeMimicAccess;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalAssembly;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalGait;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalLimbType;
import com.nobodiiiii.createbiotech.entity.ai.SlimeBionicBodyRotationControl;
import com.nobodiiiii.createbiotech.entity.ai.SlimeBionicGroundNavigation;
import com.nobodiiiii.createbiotech.entity.animation.SlimeBionicAttackTiming;
import com.nobodiiiii.createbiotech.network.CBPackets;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;

/** A real, walking entity whose visible body is supplied by a surgical assembly. */
public class SlimeBionicEntity extends PathfinderMob {
	private static final String ASSEMBLY_TAG = "SurgicalAssembly";
	private static final String SOURCE_FORM_TAG = "BionicSourceForm";
	private static final double DEFAULT_ATTACK_DISTANCE_SQR = 5.0d * 5.0d;
	private static final int ATTACK_EVENT_STRIDE = 4;
	private static final int ATTACK_EVENT_VARIANTS =
		SlimeBionicAttackTiming.PLAYBACK_TICKS * ATTACK_EVENT_STRIDE;
	private static final byte ATTACK_EVENT_BASE = -64;
	private static final EntityDataAccessor<CompoundTag> ASSEMBLY = SynchedEntityData.defineId(
		SlimeBionicEntity.class, EntityDataSerializers.COMPOUND_TAG);
	@Nullable
	private CompoundTag cachedAssemblyData;
	@Nullable
	private SurgicalAssembly cachedAssembly;
	@Nullable
	private SurgicalAssembly clientBoundsAssembly;
	@Nullable
	private SurgicalAssembly.BodyBounds clientBodyBounds;
	@Nullable
	private SurgicalAssembly reportedBoundsAssembly;
	@Nullable
	private SurgicalAssembly.BodyBounds reportedBodyBounds;
	private int attackAnimationTick;
	private int attackAnimationDuration = SlimeBionicAttackTiming.PLAYBACK_TICKS;
	private boolean attackAnimationLeft;
	private boolean attackAnimationWeapon;
	private boolean nextEmptyHandAttackLeft;

	public SlimeBionicEntity(EntityType<? extends SlimeBionicEntity> type, Level level) {
		super(type, level);
		// The bionic body begins in the same synced slime state used by ordinary mimics.
		// Loading a cured entity can still restore this value to false from its saved data.
		((SlimeMimicAccess) (Object) this).createBiotech$setSlimeMimic(true);
		setPersistenceRequired();
	}

	public static AttributeSupplier.Builder createAttributes() {
		return createMobAttributes()
			.add(Attributes.MAX_HEALTH, 400.0d)
			.add(Attributes.MOVEMENT_SPEED, SurgicalGait.VILLAGER_WALK_SPEED)
			.add(Attributes.ATTACK_DAMAGE, 3.0d)
			.add(Attributes.ARMOR, 2.0d)
			.add(Attributes.FOLLOW_RANGE, 35.0d)
			.add(Attributes.KNOCKBACK_RESISTANCE, 0.15d);
	}

	/** Mirrors {@code Zombie}'s goal set so a stitched body already behaves like something alive. */
	@Override
	protected void registerGoals() {
		goalSelector.addGoal(0, new FloatGoal(this));
		goalSelector.addGoal(2, new BionicAttackGoal(this, 1.0d, false));
		goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 1.0d));
		goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0f));
		goalSelector.addGoal(8, new RandomLookAroundGoal(this));
		targetSelector.addGoal(1, new HurtByTargetGoal(this));
		targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
		targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Villager.class, false));
	}

	@Override
	protected BodyRotationControl createBodyControl() {
		return new SlimeBionicBodyRotationControl(this);
	}

	@Override
	protected PathNavigation createNavigation(Level level) {
		return new SlimeBionicGroundNavigation(this, level);
	}

	@Override
	protected void updateWalkAnimation(float movement) {
		// Vanilla clamps movement * 4 to 1, so speeds above roughly 0.25 blocks/tick cannot raise
		// cadence. Preserve the full configured bionic range and let the renderer cap swing angle.
		float animationSpeed = Math.min(movement * 4.0f, SurgicalGait.MAX_WALK_ANIMATION_SPEED);
		walkAnimation.update(animationSpeed, 0.4f);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(ASSEMBLY, new CompoundTag());
	}

	public void setAssembly(SurgicalAssembly assembly) {
		CompoundTag encoded = assembly.save();
		entityData.set(ASSEMBLY, encoded);
		cachedAssemblyData = encoded;
		cachedAssembly = assembly;
		clientBoundsAssembly = null;
		clientBodyBounds = null;
		reportedBoundsAssembly = null;
		reportedBodyBounds = null;
		refreshMovementSpeed(assembly);
		refreshDimensions();
	}

	/** Applies the leg-length curve to the authoritative movement attribute. */
	private void refreshMovementSpeed(SurgicalAssembly assembly) {
		if (level().isClientSide)
			return;
		SurgicalAssembly.BodyBounds bounds = assembly.bodyBounds();
		double speed = SurgicalGait.movementSpeed(bounds == null ? 0.0d : bounds.legLength());
		var movement = getAttribute(Attributes.MOVEMENT_SPEED);
		if (movement != null && movement.getBaseValue() != speed)
			movement.setBaseValue(speed);
	}

	@Nullable
	public SurgicalAssembly getAssembly() {
		CompoundTag encoded = entityData.get(ASSEMBLY);
		if (encoded != cachedAssemblyData) {
			cachedAssemblyData = encoded;
			cachedAssembly = SurgicalAssembly.load(encoded);
		}
		return cachedAssembly;
	}

	/** Applies the renderer's exact visible envelope on the client, including slime-shell inflation. */
	public void setClientBodyBounds(SurgicalAssembly assembly, SurgicalAssembly.BodyBounds bounds) {
		if (!level().isClientSide || assembly == null || bounds == null || getAssembly() != assembly)
			return;
		if (clientBoundsAssembly == assembly && bounds.equals(clientBodyBounds))
			return;
		clientBoundsAssembly = assembly;
		clientBodyBounds = bounds;
		refreshDimensions();
		if (!bounds.equals(assembly.bodyBounds())
			&& (reportedBoundsAssembly != assembly || !bounds.equals(reportedBodyBounds))) {
			reportedBoundsAssembly = assembly;
			reportedBodyBounds = bounds;
			CBPackets.sendToServer(new SlimeBionicBodyBoundsPacket(getId(), bounds));
		}
	}

	@Override
	protected EntityDimensions getDefaultDimensions(Pose pose) {
		SurgicalAssembly.BodyBounds bounds = activeBodyBounds();
		if (bounds == null)
			return super.getDefaultDimensions(pose);
		// Vanilla mobs use one centred, yaw-independent square footprint whose side is the body's
		// lateral width. Their fore-aft model depth is deliberately not promoted to collision width.
		float width = bounds.width();
		float height = bounds.minY() + bounds.height();
		float eyeHeight = Mth.clamp(bounds.minY() + bounds.height() * 0.85f, 0.0f, height);
		return EntityDimensions.fixed(width, height).withEyeHeight(eyeHeight);
	}

	@Nullable
	SurgicalAssembly.BodyBounds activeBodyBounds() {
		SurgicalAssembly assembly = getAssembly();
		return level().isClientSide && clientBoundsAssembly == assembly
			? clientBodyBounds : assembly == null ? null : assembly.bodyBounds();
	}

	@Override
	public boolean isWithinMeleeAttackRange(LivingEntity target) {
		SurgicalAssembly assembly = getAssembly();
		SurgicalAssembly.AttackGeometry geometry = assembly == null ? null : assembly.attackGeometry();
		if (geometry == null)
			return distanceToSqr(target) <= DEFAULT_ATTACK_DISTANCE_SQR;
		AABB targetBounds = target.getBoundingBox();
		double x = getX();
		double z = getZ();
		double dx = x < targetBounds.minX ? targetBounds.minX - x
			: x > targetBounds.maxX ? x - targetBounds.maxX : 0.0d;
		double dz = z < targetBounds.minZ ? targetBounds.minZ - z
			: z > targetBounds.maxZ ? z - targetBounds.maxZ : 0.0d;
		boolean weapon = hasAttackWeapon();
		SurgicalAssembly.ArmAttackGeometry arm = geometry.arm(preferredAttackLeft(weapon));
		if (arm == null)
			return false;
		double reach = arm.maximumHorizontalReach(weapon);
		return dx * dx + dz * dz <= reach * reach
			&& targetBounds.maxY >= getY() + arm.minimumY(weapon)
			&& targetBounds.minY <= getY() + arm.maximumY(weapon);
	}

	@Override
	public void aiStep() {
		super.aiStep();
		if (attackAnimationTick > 0)
			attackAnimationTick--;
	}

	/** Starts one telegraphed attack and atomically sends its duration, style and side to clients. */
	private int beginAttackAnimation(int attackInterval) {
		attackAnimationDuration = SlimeBionicAttackTiming.playbackTicks(attackInterval);
		attackAnimationTick = attackAnimationDuration;
		attackAnimationWeapon = hasAttackWeapon();
		if (attackAnimationWeapon) {
			attackAnimationLeft = preferredAttackLeft(true);
		} else {
			attackAnimationLeft = nextEmptyHandAttackLeft;
			nextEmptyHandAttackLeft = !nextEmptyHandAttackLeft;
		}
		int encoded = (attackAnimationDuration - 1) * ATTACK_EVENT_STRIDE
			+ (attackAnimationWeapon ? 2 : 0) + (attackAnimationLeft ? 1 : 0);
		level().broadcastEntityEvent(this, (byte) (ATTACK_EVENT_BASE + encoded));
		return attackAnimationDuration;
	}

	/** Side the next attack will request before the renderer/geometry applies single-arm fallback. */
	private boolean preferredAttackLeft(boolean weapon) {
		if (!weapon)
			return nextEmptyHandAttackLeft;
		boolean mainHand = isAttackWeapon(getMainHandItem());
		HumanoidArm arm = mainHand ? getMainArm() : getMainArm().getOpposite();
		return arm == HumanoidArm.LEFT;
	}

	/** Applies the scheduled hit without restarting its already-running animation. */
	private boolean performAnimatedAttackDamage(Entity target) {
		return super.doHurtTarget(target);
	}

	/** Tests only the current target against the baked hand curve; no world entity scan is needed. */
	private boolean sweptAttackIntersects(LivingEntity target,
		SurgicalAssembly.ArmAttackGeometry arm, int previousTick, int currentTick, int duration,
		boolean weaponAttack, float previousBodyYaw, float currentBodyYaw) {
		float tickFrom = (float) previousTick / duration;
		float tickTo = (float) currentTick / duration;
		float from = Math.max(tickFrom,
			SlimeBionicAttackTiming.hitWindowStart(weaponAttack));
		float to = Math.min(tickTo,
			SlimeBionicAttackTiming.hitWindowEnd(weaponAttack));
		if (to < from)
			return false;
		AABB targetBounds = target.getBoundingBox().inflate(arm.radius());
		float cursor = from;
		Vec3 start = attackPoint(arm.sample(weaponAttack, cursor), attackYaw(cursor, tickFrom, tickTo,
			previousBodyYaw, currentBodyYaw));
		while (true) {
			float nextBoundary = ((float) Math.floor(cursor
				* (SurgicalAssembly.AttackGeometry.PATH_SAMPLES - 1)) + 1.0f)
				/ (SurgicalAssembly.AttackGeometry.PATH_SAMPLES - 1);
			float next = Math.min(to, nextBoundary);
			if (next <= cursor + 1.0e-6f)
				next = to;
			Vec3 end = attackPoint(arm.sample(weaponAttack, next), attackYaw(next, tickFrom, tickTo,
				previousBodyYaw, currentBodyYaw));
			AABB segmentBounds = new AABB(start, end).inflate(1.0e-6d);
			if (targetBounds.intersects(segmentBounds)
				&& (targetBounds.contains(start) || targetBounds.contains(end)
					|| targetBounds.clip(start, end).isPresent()))
				return true;
			if (next >= to - 1.0e-6f)
				return false;
			cursor = next;
			start = end;
		}
	}

	private static float attackYaw(float progress, float tickFrom, float tickTo,
		float previousBodyYaw, float currentBodyYaw) {
		float fraction = tickTo <= tickFrom ? 1.0f
			: Mth.clamp((progress - tickFrom) / (tickTo - tickFrom), 0.0f, 1.0f);
		return Mth.rotLerp(fraction, previousBodyYaw, currentBodyYaw);
	}

	private Vec3 attackPoint(Vec3 local, float bodyYaw) {
		return local.yRot(-bodyYaw * Mth.DEG_TO_RAD).add(position());
	}

	@Override
	public void handleEntityEvent(byte id) {
		int encoded = id - ATTACK_EVENT_BASE;
		if (encoded >= 0 && encoded < ATTACK_EVENT_VARIANTS) {
			attackAnimationDuration = encoded / ATTACK_EVENT_STRIDE + 1;
			attackAnimationTick = attackAnimationDuration;
			attackAnimationLeft = (encoded & 1) != 0;
			attackAnimationWeapon = (encoded & 2) != 0;
		} else
			super.handleEntityEvent(id);
	}

	public int getAttackAnimationTick() {
		return attackAnimationTick;
	}

	public int getAttackAnimationDuration() {
		return attackAnimationDuration;
	}

	public boolean isAttackAnimationLeft() {
		return attackAnimationLeft;
	}

	public boolean isAttackAnimationWeapon() {
		return attackAnimationWeapon;
	}

	public boolean hasAttackWeapon() {
		return isAttackWeapon(getMainHandItem()) || isAttackWeapon(getOffhandItem());
	}

	/** Authored attack timing is only valid when the body can visibly bend an attacking arm. */
	private boolean hasArticulatedAttackArm() {
		SurgicalAssembly assembly = getAssembly();
		return assembly != null && assembly.limbs().stream()
			.anyMatch(limb -> limb.type() == SurgicalLimbType.ELBOW);
	}

	public static boolean isAttackWeapon(ItemStack stack) {
		return stack != null && stack.is(Tags.Items.MELEE_WEAPON_TOOLS);
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
		super.onSyncedDataUpdated(key);
		if (ASSEMBLY.equals(key)) {
			clientBoundsAssembly = null;
			clientBodyBounds = null;
			reportedBoundsAssembly = null;
			reportedBodyBounds = null;
			refreshDimensions();
		}
	}

	@Override
	public void addAdditionalSaveData(CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		SlimeMimicAccess slimeState = (SlimeMimicAccess) (Object) this;
		tag.putBoolean(SOURCE_FORM_TAG, !slimeState.createBiotech$isSlimeMimic());
		CompoundTag assembly = entityData.get(ASSEMBLY);
		if (!assembly.isEmpty())
			tag.put(ASSEMBLY_TAG, assembly.copy());
	}

	@Override
	public void readAdditionalSaveData(CompoundTag tag) {
		super.readAdditionalSaveData(tag);
		// Saves predating this flag always represented the original bionic slime form.
		boolean sourceForm = tag.contains(SOURCE_FORM_TAG, Tag.TAG_BYTE) && tag.getBoolean(SOURCE_FORM_TAG);
		((SlimeMimicAccess) (Object) this).createBiotech$setSlimeMimic(!sourceForm);
		// Bodies packed before the AI existed were saved with NoAI set; wake those up on load.
		setNoAi(false);
		if (tag.contains(ASSEMBLY_TAG, Tag.TAG_COMPOUND)) {
			SurgicalAssembly assembly = SurgicalAssembly.load(tag.getCompound(ASSEMBLY_TAG));
			if (assembly != null)
				setAssembly(assembly);
		}
	}

	/** {@code ZombieAttackGoal} verbatim; the vanilla class is bound to {@code Zombie}. */
	private static class BionicAttackGoal extends MeleeAttackGoal {
		private final SlimeBionicEntity bionic;
		private int raiseArmTicks;
		@Nullable
		private LivingEntity pendingAttackTarget;
		private int pendingAttackElapsed;
		private int pendingAttackDuration;
		private int pendingImpactTick;
		private boolean pendingImpactApplied;
		@Nullable
		private SurgicalAssembly.ArmAttackGeometry pendingArmGeometry;
		private float pendingPreviousBodyYaw;

		private BionicAttackGoal(SlimeBionicEntity bionic, double speedModifier, boolean followingTargetEvenIfNotSeen) {
			super(bionic, speedModifier, followingTargetEvenIfNotSeen);
			this.bionic = bionic;
		}

		@Override
		public void start() {
			super.start();
			raiseArmTicks = 0;
			clearPendingAttack();
		}

		@Override
		public void stop() {
			super.stop();
			clearPendingAttack();
			bionic.setAggressive(false);
		}

		@Override
		public boolean canContinueToUse() {
			return (pendingAttackTarget != null && pendingAttackTarget.isAlive())
				|| super.canContinueToUse();
		}

		@Override
		protected void checkAndPerformAttack(LivingEntity target) {
			if (pendingAttackTarget != null)
				return;
			if (!canPerformAttack(target))
				return;
			resetAttackCooldown();
			bionic.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
			if (!bionic.hasArticulatedAttackArm()) {
				bionic.doHurtTarget(target);
				return;
			}
			pendingAttackDuration = bionic.beginAttackAnimation(getAttackInterval());
			pendingImpactTick = SlimeBionicAttackTiming.impactTick(pendingAttackDuration,
				bionic.isAttackAnimationWeapon());
			SurgicalAssembly assembly = bionic.getAssembly();
			SurgicalAssembly.AttackGeometry attackGeometry = assembly == null
				? null : assembly.attackGeometry();
			pendingArmGeometry = attackGeometry == null ? null
				: attackGeometry.arm(bionic.isAttackAnimationLeft());
			pendingAttackTarget = target;
			pendingAttackElapsed = 0;
			pendingImpactApplied = false;
			pendingPreviousBodyYaw = bionic.yBodyRot;
		}

		private void advancePendingAttack() {
			int previousElapsed = pendingAttackElapsed;
			pendingAttackElapsed++;
			LivingEntity target = pendingAttackTarget;
			if (!pendingImpactApplied && target != null && target.isAlive()) {
				if (pendingArmGeometry != null) {
					if (bionic.sweptAttackIntersects(target, pendingArmGeometry, previousElapsed,
						pendingAttackElapsed, pendingAttackDuration, bionic.isAttackAnimationWeapon(),
						pendingPreviousBodyYaw, bionic.yBodyRot)) {
						pendingImpactApplied = true;
						bionic.performAnimatedAttackDamage(target);
					}
				} else if (pendingAttackElapsed >= pendingImpactTick) {
					pendingImpactApplied = true;
					if (bionic.isWithinMeleeAttackRange(target))
						bionic.performAnimatedAttackDamage(target);
				}
			}
			pendingPreviousBodyYaw = bionic.yBodyRot;
			if (pendingAttackElapsed >= pendingAttackDuration)
				clearPendingAttack();
		}

		private void clearPendingAttack() {
			pendingAttackTarget = null;
			pendingAttackElapsed = 0;
			pendingAttackDuration = 0;
			pendingImpactTick = 0;
			pendingImpactApplied = false;
			pendingArmGeometry = null;
			pendingPreviousBodyYaw = bionic.yBodyRot;
		}

		@Override
		public void tick() {
			if (pendingAttackTarget != null)
				advancePendingAttack();
			super.tick();
			raiseArmTicks++;
			bionic.setAggressive(raiseArmTicks >= 5 && getTicksUntilNextAttack() < getAttackInterval() / 2);
		}
	}
}
