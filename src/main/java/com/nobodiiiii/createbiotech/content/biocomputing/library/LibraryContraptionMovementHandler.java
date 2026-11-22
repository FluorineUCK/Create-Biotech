package com.nobodiiiii.createbiotech.content.biocomputing.library;

import java.util.Objects;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

/** Detaches serialized owners and releases only permanently destroyed contraptions. */
@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public final class LibraryContraptionMovementHandler {
	private LibraryContraptionMovementHandler() {}

	/** Called from vanilla's save-then-UNLOADED_TO_CHUNK boundary, not its earlier leave event. */
	public static void onSerializedChunkUnload(EntityAccess unloaded) {
		if (!(unloaded instanceof AbstractContraptionEntity entity)
			|| !(entity.getContraption() instanceof LibraryMovementLeaseOwner owner))
			return;
		handleRemoval(Entity.RemovalReason.UNLOADED_TO_CHUNK.name(), false,
			() -> LibraryContraptionMovement.detachAfterSerialization(owner, entity.level()),
			() -> LibraryContraptionMovement.releaseAll(owner, entity.level()));
	}

	@SubscribeEvent
	public static void onContraptionRemoved(EntityLeaveLevelEvent event) {
		if (event.getLevel().isClientSide
			|| !(event.getEntity() instanceof AbstractContraptionEntity entity)
			|| !(entity.getContraption() instanceof LibraryMovementLeaseOwner owner))
			return;
		Entity.RemovalReason reason = entity.getRemovalReason();
		boolean serializedCarriageDiscard =
			LibraryCarriageVirtualization.isSerializedCarriageDiscard(entity);
		handleRemoval(reason == null ? null : reason.name(), serializedCarriageDiscard,
			() -> LibraryContraptionMovement.detachAfterSerialization(owner, event.getLevel()),
			() -> LibraryContraptionMovement.releaseAll(owner, event.getLevel()));
	}

	static void handleRemoval(String reason, boolean serializedCarriageDiscard,
		Runnable detachSerializedOwner, Runnable releasePermanentOwner) {
		Objects.requireNonNull(detachSerializedOwner, "detach action");
		Objects.requireNonNull(releasePermanentOwner, "release action");
		if (Entity.RemovalReason.UNLOADED_TO_CHUNK.name().equals(reason)) {
			detachSerializedOwner.run();
			return;
		}
		if (reason != null && LibraryCarriageVirtualization.shouldReleaseForRemoval(
			reason, serializedCarriageDiscard))
			releasePermanentOwner.run();
	}
}
