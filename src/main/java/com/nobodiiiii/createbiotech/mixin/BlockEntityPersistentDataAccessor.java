package com.nobodiiiii.createbiotech.mixin;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Reads a block entity's NeoForge persistent data without creating it.
 *
 * <p>{@code IBlockEntityExtension#getPersistentData()} allocates the tag on first call and
 * {@code BlockEntity#saveAdditional} then writes {@code NeoForgeData} whenever the field is
 * non-null, with no emptiness check. Probing it directly would therefore stamp an empty compound
 * into the saved NBT of every block entity that is merely asked about it.</p>
 */
@Mixin(BlockEntity.class)
public interface BlockEntityPersistentDataAccessor {

	@Nullable
	@Accessor("customPersistentData")
	CompoundTag createBiotech$getExistingPersistentData();
}
