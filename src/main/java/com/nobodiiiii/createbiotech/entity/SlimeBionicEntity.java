package com.nobodiiiii.createbiotech.entity;

import org.jetbrains.annotations.Nullable;

import com.nobodiiiii.createbiotech.content.slimemimic.SlimeMimicAccess;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalAssembly;
import com.nobodiiiii.createbiotech.network.CBPackets;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** A real, walking entity whose visible body is supplied by a surgical assembly. */
public class SlimeBionicEntity extends PathfinderMob {
	private static final String ASSEMBLY_TAG = "SurgicalAssembly";
	private static final String SOURCE_FORM_TAG = "BionicSourceForm";
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
	private float collisionBodyYaw = Float.NaN;

	public SlimeBionicEntity(EntityType<? extends SlimeBionicEntity> type, Level level) {
		super(type, level);
		// The bionic body begins in the same synced slime state used by ordinary mimics.
		// Loading a cured entity can still restore this value to false from its saved data.
		((SlimeMimicAccess) (Object) this).createBiotech$setSlimeMimic(true);
		setPersistenceRequired();
	}

	public static AttributeSupplier.Builder createAttributes() {
		// Placeholder statistics. Deriving them from the packed assembly is a separate step.
		return createMobAttributes()
			.add(Attributes.MAX_HEALTH, 20.0d)
			.add(Attributes.MOVEMENT_SPEED, 0.23d)
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
		refreshDimensions();
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
		float width = Math.max(bounds.width(), bounds.depth());
		return EntityDimensions.fixed(width, bounds.height()).withEyeHeight(bounds.height() * 0.85f);
	}

	@Nullable
	private SurgicalAssembly.BodyBounds activeBodyBounds() {
		SurgicalAssembly assembly = getAssembly();
		return level().isClientSide && clientBoundsAssembly == assembly
			? clientBodyBounds : assembly == null ? null : assembly.bodyBounds();
	}

	@Override
	protected AABB makeBoundingBox() {
		SurgicalAssembly.BodyBounds bounds = activeBodyBounds();
		if (bounds == null)
			return super.makeBoundingBox();
		double radians = Math.toRadians(yBodyRot);
		double cosine = Math.abs(Math.cos(radians));
		double sine = Math.abs(Math.sin(radians));
		double halfX = (bounds.width() * cosine + bounds.depth() * sine) * 0.5d;
		double halfZ = (bounds.width() * sine + bounds.depth() * cosine) * 0.5d;
		return new AABB(getX() - halfX, getY(), getZ() - halfZ,
			getX() + halfX, getY() + bounds.height(), getZ() + halfZ);
	}

	@Override
	public void tick() {
		super.tick();
		if (activeBodyBounds() != null
			&& (Float.isNaN(collisionBodyYaw) || Math.abs(Mth.wrapDegrees(yBodyRot - collisionBodyYaw)) > 0.01f)) {
			collisionBodyYaw = yBodyRot;
			setBoundingBox(makeBoundingBox());
		}
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

		private BionicAttackGoal(SlimeBionicEntity bionic, double speedModifier, boolean followingTargetEvenIfNotSeen) {
			super(bionic, speedModifier, followingTargetEvenIfNotSeen);
			this.bionic = bionic;
		}

		@Override
		public void start() {
			super.start();
			raiseArmTicks = 0;
		}

		@Override
		public void stop() {
			super.stop();
			bionic.setAggressive(false);
		}

		@Override
		public void tick() {
			super.tick();
			raiseArmTicks++;
			bionic.setAggressive(raiseArmTicks >= 5 && getTicksUntilNextAttack() < getAttackInterval() / 2);
		}
	}
}
