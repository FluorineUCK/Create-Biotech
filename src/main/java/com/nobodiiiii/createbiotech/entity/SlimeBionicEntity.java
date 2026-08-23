package com.nobodiiiii.createbiotech.entity;

import org.jetbrains.annotations.Nullable;

import com.nobodiiiii.createbiotech.content.slimemimic.SlimeMimicAccess;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalAssembly;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

/** A real, AI-free entity whose visible body is supplied by a surgical assembly. */
public class SlimeBionicEntity extends PathfinderMob {
	private static final String ASSEMBLY_TAG = "SurgicalAssembly";
	private static final String SOURCE_FORM_TAG = "BionicSourceForm";
	private static final EntityDataAccessor<CompoundTag> ASSEMBLY = SynchedEntityData.defineId(
		SlimeBionicEntity.class, EntityDataSerializers.COMPOUND_TAG);
	@Nullable
	private CompoundTag cachedAssemblyData;
	@Nullable
	private SurgicalAssembly cachedAssembly;

	public SlimeBionicEntity(EntityType<? extends SlimeBionicEntity> type, Level level) {
		super(type, level);
		// The bionic body begins in the same synced slime state used by ordinary mimics.
		// Loading a cured entity can still restore this value to false from its saved data.
		((SlimeMimicAccess) (Object) this).createBiotech$setSlimeMimic(true);
		setNoAi(true);
		setPersistenceRequired();
	}

	public static AttributeSupplier.Builder createAttributes() {
		return createMobAttributes()
			.add(Attributes.MAX_HEALTH, 10.0d)
			.add(Attributes.MOVEMENT_SPEED, 0.0d)
			.add(Attributes.KNOCKBACK_RESISTANCE, 0.15d);
	}

	@Override
	protected void registerGoals() {
		// Deliberately empty: the bionic body has no source-creature AI.
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
		setNoAi(true);
		if (tag.contains(ASSEMBLY_TAG, Tag.TAG_COMPOUND)) {
			SurgicalAssembly assembly = SurgicalAssembly.load(tag.getCompound(ASSEMBLY_TAG));
			if (assembly != null)
				setAssembly(assembly);
		}
	}
}
