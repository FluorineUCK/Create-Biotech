package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryIdentityClaims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

class LibraryCarriageVirtualizationTest {
	@Test
	void carriageRemoveAndSaveEntitySerializeDiscardThenRecreatePreservesOwnership() {
		Object server = new Object();
		Object sourceCore = new Object();
		Object serializedCarriage = new Object();
		Object recreatedCarriage = new Object();
		Object reassembledCore = new Object();
		Object intruder = new Object();
		UUID libraryId = UUID.fromString("68fc9c1f-96a4-45c5-8451-b20e425b510d");
		UUID token = UUID.fromString("0bdf07f3-bf13-4b66-bde5-066449ce23c7");
		List<String> order = new ArrayList<>();

		assertTrue(LibraryIdentityClaims.claimLibraryIdentity(
			server, libraryId, sourceCore));
		assertTrue(LibraryIdentityClaims.transferLibraryIdentityToMovementLease(
			server, libraryId, sourceCore, serializedCarriage, token));
		order.add("serialize");

		LibraryCarriageVirtualization.discardAfterSerialization(serializedCarriage,
			() -> {
				order.add("detach");
				assertTrue(LibraryIdentityClaims.detachMovementLeaseOwner(
					server, libraryId, serializedCarriage, token));
			}, () -> {
				order.add("discard");
				assertTrue(LibraryCarriageVirtualization
					.isSerializedCarriageDiscard(serializedCarriage));
				assertFalse(LibraryCarriageVirtualization.shouldReleaseForRemoval(
					"DISCARDED", true));
				assertFalse(LibraryIdentityClaims.claimLibraryIdentity(
					server, libraryId, intruder));
			});

		assertEquals(List.of("serialize", "detach", "discard"), order);
		assertFalse(LibraryCarriageVirtualization
			.isSerializedCarriageDiscard(serializedCarriage));
		assertTrue(LibraryIdentityClaims.restoreProcessLocalMovementLease(
			server, libraryId, recreatedCarriage, token));
		assertTrue(LibraryIdentityClaims.activateLibraryIdentityFromMovementOwner(
			server, libraryId, recreatedCarriage, reassembledCore, token, () -> {}, () -> {}));
		LibraryIdentityClaims.releaseLibraryIdentity(server, libraryId, reassembledCore);
	}

	@Test
	void ordinaryPermanentDiscardAndKillStillRelease() {
		assertTrue(LibraryCarriageVirtualization.shouldReleaseForRemoval(
			"DISCARDED", false));
		assertTrue(LibraryCarriageVirtualization.shouldReleaseForRemoval("KILLED", false));
		assertFalse(LibraryCarriageVirtualization.shouldReleaseForRemoval(
			"UNLOADED_TO_CHUNK", false));
	}

	@Test
	void vanillaChunkSaveThenUnloadFastReloadAndReassemblyDoesNotDependOnOldOwnerGc() {
		Object server = new Object();
		Object sourceCore = new Object();
		FakeOwner retiringContraption = new FakeOwner();
		Object reloadedContraption = new Object();
		Object reassembledCore = new Object();
		Object intruder = new Object();
		UUID libraryId = UUID.fromString("ee4a18c8-650d-44c1-84aa-82aac8aa784d");
		UUID token = UUID.fromString("af8f0813-53ac-47a8-86bb-90b9bb7c01c4");
		List<String> order = new ArrayList<>();

		assertTrue(LibraryIdentityClaims.claimLibraryIdentity(
			server, libraryId, sourceCore));
		assertTrue(LibraryIdentityClaims.transferLibraryIdentityToMovementLease(
			server, libraryId, sourceCore, retiringContraption, token));
		assertTrue(retiringContraption.createBiotech$addPatternCoreLease(libraryId, token));
		CompoundTag savedEntity = new CompoundTag();
		LibraryContraptionMovement.writeLeases(retiringContraption, savedEntity);
		order.add("save");

		LibraryContraptionMovementHandler.handleRemoval("UNLOADED_TO_CHUNK", false,
			() -> {
				order.add("detach");
				LibraryContraptionMovement.detachAfterSerialization(retiringContraption,
					(id, movementToken) -> LibraryIdentityClaims.detachMovementLeaseOwner(
						server, id, retiringContraption, movementToken));
			}, () -> order.add("release"));

		assertEquals(List.of("save", "detach"), order,
			"vanilla unload must detach only after entity NBT captured the lease pair");
		assertTrue(retiringContraption.createBiotech$patternCoreLeases().isEmpty(),
			"the retired object must drop its process-local rows deterministically");
		assertFalse(LibraryIdentityClaims.claimLibraryIdentity(server, libraryId, intruder),
			"detaching the Java owner must not unlock the durable token claim");
		CompoundTag serializedLease = savedEntity
			.getList("CreateBiotechPatternCoreLeases", Tag.TAG_COMPOUND).getCompound(0);
		assertTrue(LibraryIdentityClaims.restoreProcessLocalMovementLease(server,
			serializedLease.getUUID("LibraryId"), reloadedContraption,
			serializedLease.getUUID("Token")),
			"a quick reload must replace the specifically retired owner while it remains strongly reachable");
		assertTrue(LibraryIdentityClaims.activateLibraryIdentityFromMovementOwner(server,
			libraryId, reloadedContraption, reassembledCore, token, () -> {}, () -> {}));
		LibraryIdentityClaims.releaseLibraryIdentity(server, libraryId, reassembledCore);
		assertTrue(LibraryIdentityClaims.claimLibraryIdentity(server, libraryId, intruder));
		LibraryIdentityClaims.releaseLibraryIdentity(server, libraryId, intruder);
	}

	@Test
	void nonPersistentPermanentDestroyReleasesInsteadOfDetaching() {
		Object server = new Object();
		Object sourceCore = new Object();
		FakeOwner destroyedContraption = new FakeOwner();
		Object replacement = new Object();
		UUID libraryId = UUID.fromString("cfa30870-635e-46bc-beec-82b7fc3bd0c9");
		UUID token = UUID.fromString("f00f2f38-1b69-48d0-8a03-428c77bdf7db");
		List<String> actions = new ArrayList<>();

		assertTrue(LibraryIdentityClaims.claimLibraryIdentity(
			server, libraryId, sourceCore));
		assertTrue(LibraryIdentityClaims.transferLibraryIdentityToMovementLease(
			server, libraryId, sourceCore, destroyedContraption, token));
		assertTrue(destroyedContraption.createBiotech$addPatternCoreLease(libraryId, token));

		LibraryContraptionMovementHandler.handleRemoval("DISCARDED", false,
			() -> actions.add("detach"), () -> {
				actions.add("release");
				LibraryContraptionMovement.releaseAll(destroyedContraption,
					(id, ignoredToken) -> LibraryIdentityClaims.releaseLibraryIdentity(
						server, id, destroyedContraption));
			});

		assertEquals(List.of("release"), actions);
		assertTrue(destroyedContraption.createBiotech$patternCoreLeases().isEmpty());
		assertTrue(LibraryIdentityClaims.claimLibraryIdentity(server, libraryId, replacement),
			"a permanent non-persistent destroy must unlock the disposed identity");
		LibraryIdentityClaims.releaseLibraryIdentity(server, libraryId, replacement);
	}

	private static final class FakeOwner implements LibraryMovementLeaseOwner {
		private final Map<UUID, UUID> leases = new LinkedHashMap<>();

		@Override
		public boolean createBiotech$addPatternCoreLease(UUID libraryId, UUID movementToken) {
			UUID existing = leases.get(libraryId);
			if (existing != null)
				return existing.equals(movementToken);
			if (leases.containsValue(movementToken))
				return false;
			leases.put(libraryId, movementToken);
			return true;
		}

		@Override
		public boolean createBiotech$removePatternCoreLease(UUID libraryId, UUID movementToken) {
			return movementToken.equals(leases.get(libraryId)) && leases.remove(libraryId) != null;
		}

		@Override
		public Map<UUID, UUID> createBiotech$patternCoreLeases() {
			return Map.copyOf(leases);
		}

		@Override
		public void createBiotech$clearPatternCoreLeases() {
			leases.clear();
		}
	}
}
