package com.nobodiiiii.createbiotech.content.biocomputing.library;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class LibraryContraptionMovementSourceTest {
	private static final Path JAVA = Path.of("src/main/java/com/nobodiiiii/createbiotech");

	@Test
	void createRemoveBlockEntityCallIsWrappedAtTheRealPreRemovalBoundary() throws Exception {
		String mixin = Files.readString(JAVA.resolve("mixin/ContraptionMixin.java"));
		assertTrue(mixin.contains("method = \"removeBlocksFromWorld\""));
		assertTrue(mixin.contains(
			"Lnet/minecraft/world/level/Level;removeBlockEntity(Lnet/minecraft/core/BlockPos;)V"));
		assertTrue(mixin.contains("LibraryContraptionMovement.removeBlockEntityWithLease"));
		assertTrue(mixin.contains("method = \"addBlocksToWorld\""));
		assertTrue(mixin.contains("LibraryContraptionMovement.claimReassembledCore"));

		String core = Files.readString(JAVA.resolve(
			"content/biocomputing/library/LibraryBlockEntity.java"));
		assertTrue(core.contains("new LibraryIdentityLifecycle(this)"));
		assertTrue(core.contains("identity.acceptMovementLease(server, movementOwner)"));
		String lifecycle = Files.readString(JAVA.resolve(
			"content/biocomputing/library/LibraryIdentityLifecycle.java"));
		assertTrue(lifecycle.contains("activateLibraryIdentity(server, id, owner,"));
		assertTrue(lifecycle.contains("activateLibraryIdentityFromMovementOwner(server, id,"));
		assertTrue(lifecycle.contains("movementOwner, owner, movementToken"));
	}

	@Test
	void contraptionPersistsLeasesAndPermanentEntityRemovalDisposesThem() throws Exception {
		String mixin = Files.readString(JAVA.resolve("mixin/ContraptionMixin.java"));
		assertTrue(mixin.contains("method = \"writeNBT\""));
		assertTrue(mixin.contains("method = \"readNBT\""));

		String handler = Files.readString(JAVA.resolve(
			"content/biocomputing/library/LibraryContraptionMovementHandler.java"));
		assertTrue(handler.contains("EntityLeaveLevelEvent"));
		assertTrue(handler.contains("LibraryCarriageVirtualization.isSerializedCarriageDiscard"));
		assertTrue(handler.contains("shouldReleaseForRemoval"));
		assertTrue(handler.contains("LibraryContraptionMovement.releaseAll"));

		String carriageMixin = Files.readString(JAVA.resolve("mixin/CarriageVirtualizationMixin.java"));
		assertTrue(carriageMixin.contains("method = \"removeAndSaveEntity\""));
		assertTrue(carriageMixin.contains("Lcom/simibubi/create/content/trains/entity/"
			+ "CarriageContraptionEntity;discard()V"));
		assertTrue(carriageMixin.contains("discardSerializedCarriage"));
	}

	@Test
	void vanillaChunkUnloadHooksThePostSerializationEntityBoundary() throws Exception {
		String mixins = Files.readString(Path.of("src/main/resources/create_biotech.mixins.json"));
		assertTrue(mixins.contains("PersistentEntitySectionManagerMixin"));

		String vanillaUnloadMixin = Files.readString(
			JAVA.resolve("mixin/PersistentEntitySectionManagerMixin.java"));
		assertTrue(vanillaUnloadMixin.contains("method = \"unloadEntity\""));
		assertTrue(vanillaUnloadMixin.contains("at = @At(\"HEAD\")"));
		assertTrue(vanillaUnloadMixin.contains("(EntityAccess entity,"));
		assertTrue(vanillaUnloadMixin.contains("onSerializedChunkUnload"));
	}
}
