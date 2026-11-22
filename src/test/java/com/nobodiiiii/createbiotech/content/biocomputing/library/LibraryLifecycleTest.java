package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryCoreItemData;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryIdentityClaims;



import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;


import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

class LibraryLifecycleTest {
	@BeforeAll
	static void bootstrap() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		markMinecraftBootstrapped();
	}

	@Test
	void libraryIdentityClaimIsSingleOwnerPerServerAndReleasable() {
		Object firstServer = new Object();
		Object secondServer = new Object();
		Object firstCore = new Object();
		Object secondCore = new Object();
		UUID libraryId = UUID.fromString("5f06644d-3028-432f-a4dd-561991425657");

		assertTrue(LibraryIdentityClaims.claimLibraryIdentity(
			firstServer, libraryId, firstCore));
		assertTrue(LibraryIdentityClaims.claimLibraryIdentity(
			firstServer, libraryId, firstCore), "the exact owner may reclaim idempotently");
		assertFalse(LibraryIdentityClaims.claimLibraryIdentity(
			firstServer, libraryId, secondCore));
		assertTrue(LibraryIdentityClaims.claimLibraryIdentity(
			secondServer, libraryId, secondCore), "claims are scoped to one server");

		LibraryIdentityClaims.releaseLibraryIdentity(
			firstServer, libraryId, secondCore);
		assertFalse(LibraryIdentityClaims.claimLibraryIdentity(
			firstServer, libraryId, secondCore), "a non-owner cannot release the claim");
		LibraryIdentityClaims.releaseLibraryIdentity(
			firstServer, libraryId, firstCore);
		assertTrue(LibraryIdentityClaims.claimLibraryIdentity(
			firstServer, libraryId, secondCore));

		LibraryIdentityClaims.releaseLibraryIdentity(
			firstServer, libraryId, secondCore);
		LibraryIdentityClaims.releaseLibraryIdentity(
			secondServer, libraryId, secondCore);
	}

	@Test
	void failedIdentityActivationRollsBackAndReleasesTheClaim() {
		Object server = new Object();
		Object failedCore = new Object();
		Object replacement = new Object();
		UUID libraryId = UUID.fromString("b583ce8d-91b7-4671-974d-ad7bdd1aca09");
		List<String> events = new ArrayList<>();

		assertThrows(IllegalStateException.class, () ->
			LibraryIdentityClaims.activateLibraryIdentity(server, libraryId, failedCore,
				() -> {
					events.add("activate");
					throw new IllegalStateException("activation failed");
				}, () -> events.add("rollback")));

		assertEquals(List.of("activate", "rollback"), events);
		assertTrue(LibraryIdentityClaims.claimLibraryIdentity(
			server, libraryId, replacement));
		LibraryIdentityClaims.releaseLibraryIdentity(server, libraryId, replacement);
	}

	@Test
	void failedMovementHandoffActivationRestoresTheExactRetainedClaim() {
		Object server = new Object();
		Object movingOwner = new Object();
		Object reassembledOwner = new Object();
		Object intruder = new Object();
		Object retryOwner = new Object();
		UUID libraryId = UUID.fromString("6f8477d6-8c70-4bcf-84aa-474349af7bf8");
		UUID movementToken = UUID.fromString("bb2a40eb-52f6-41ef-b962-4a275ab344ed");
		assertTrue(LibraryIdentityClaims.claimLibraryIdentity(
			server, libraryId, movingOwner));
		assertTrue(LibraryIdentityClaims.retainLibraryIdentityForMovement(
			server, libraryId, movingOwner, movementToken));

		assertThrows(IllegalStateException.class, () ->
			LibraryIdentityClaims.activateLibraryIdentityFromMovementOwner(server, libraryId,
				movingOwner, reassembledOwner, movementToken,
				() -> { throw new IllegalStateException("reassembly failed"); }, () -> {}));

		assertFalse(LibraryIdentityClaims.claimLibraryIdentity(
			server, libraryId, intruder),
			"failed token handoff must restore the token-gated retained claim");
		assertTrue(LibraryIdentityClaims.activateLibraryIdentityFromMovementOwner(server,
			libraryId, movingOwner, retryOwner, movementToken, () -> {}, () -> {}),
			"the exact token must remain able to retry after an activation exception");
		LibraryIdentityClaims.releaseLibraryIdentity(server, libraryId, retryOwner);
	}

	@Test
	void failedIdempotentActivationRestoresTheSameActiveOwner() {
		Object server = new Object();
		Object owner = new Object();
		Object intruder = new Object();
		UUID libraryId = UUID.fromString("bc1f97fa-4299-4cd4-903a-bb01002a81a9");
		assertTrue(LibraryIdentityClaims.claimLibraryIdentity(server, libraryId, owner));

		assertThrows(IllegalStateException.class, () ->
			LibraryIdentityClaims.activateLibraryIdentity(server, libraryId, owner,
				() -> { throw new IllegalStateException("retryable activation failed"); }, () -> {}));

		assertFalse(LibraryIdentityClaims.claimLibraryIdentity(server, libraryId, intruder),
			"an idempotent activation failure must not delete the pre-existing active claim");
		assertTrue(LibraryIdentityClaims.claimLibraryIdentity(server, libraryId, owner));
		LibraryIdentityClaims.releaseLibraryIdentity(server, libraryId, owner);
	}

	@Test
	void duplicateContraptionNbtCannotStealALiveRetainedOwner() {
		Object server = new Object();
		Object sourceCore = new Object();
		Object firstContraption = new Object();
		Object duplicateContraption = new Object();
		Object reassembledCore = new Object();
		UUID libraryId = UUID.fromString("811e1b4f-d9b2-4418-a83c-61b9bdb109d4");
		UUID movementToken = UUID.fromString("705631fc-5bad-4650-acba-a38a39500ddd");
		assertTrue(LibraryIdentityClaims.claimLibraryIdentity(
			server, libraryId, sourceCore));
		assertTrue(LibraryIdentityClaims.transferLibraryIdentityToMovementLease(
			server, libraryId, sourceCore, firstContraption, movementToken));

		assertFalse(LibraryIdentityClaims.restoreProcessLocalMovementLease(
			server, libraryId, duplicateContraption, movementToken),
			"a copied contraption NBT record must not replace its still-live exact owner");
		assertFalse(LibraryIdentityClaims.claimLibraryIdentity(
			server, libraryId, duplicateContraption),
			"an ordinary Core initialization cannot turn the copied bearer token into authority");
		assertTrue(LibraryIdentityClaims.activateLibraryIdentityFromMovementOwner(server,
			libraryId, firstContraption, reassembledCore, movementToken, () -> {}, () -> {}),
			"the original retained token must remain able to hand off after the duplicate is rejected");
		LibraryIdentityClaims.releaseLibraryIdentity(server, libraryId, reassembledCore);
	}

	@Test
	void failedIdentityWithdrawalStillUnregistersAndReleasesTheClaim() {
		Object server = new Object();
		Object failedCore = new Object();
		Object replacement = new Object();
		UUID libraryId = UUID.fromString("4ee5b9d0-f165-4c0d-8893-57210c0f270d");
		List<String> events = new ArrayList<>();
		assertTrue(LibraryIdentityClaims.claimLibraryIdentity(
			server, libraryId, failedCore));

		assertThrows(IllegalStateException.class, () ->
			LibraryIdentityClaims.retireLibraryIdentity(server, libraryId, failedCore,
				() -> {
					events.add("withdraw");
					throw new IllegalStateException("withdrawal failed");
				}, () -> events.add("unregister")));

		assertEquals(List.of("withdraw", "unregister"), events);
		assertTrue(LibraryIdentityClaims.claimLibraryIdentity(
			server, libraryId, replacement));
		LibraryIdentityClaims.releaseLibraryIdentity(server, libraryId, replacement);
	}

	@Test
	void physicalRemovalSnapshotPreservesClaimedConfiguration() {
		DedicatedServer server = allocate(DedicatedServer.class);
		LibraryBlockEntity core = new LibraryBlockEntity(
			net.minecraft.world.level.block.entity.BlockEntityType.FURNACE, BlockPos.ZERO,
			Blocks.FURNACE.defaultBlockState()) {
			@Override public ItemStack asConfiguredStack() {
				return asConfiguredStack(new ItemStack(Items.STONE));
			}
		};
		UUID id = UUID.fromString("d3f9cd22-b81d-4560-8580-ad02aadcf76a");
		LibraryCoreItemData data = LibraryCoreItemData.configured(
			com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency
				.of("creative-break"), Integer.MAX_VALUE, id, 7);
		assertTrue(core.claimItemConfiguration(server, data));

		ItemStack output = core.captureLibraryRemoval().coreItem();

		assertEquals(1, output.getCount());
		assertEquals(data, LibraryCoreItemData.read(output).orElseThrow());
		core.releaseLibraryIdentityClaim();
	}

	@SuppressWarnings({ "removal", "unchecked" })
	private static <T> T allocate(Class<T> type) {
		try {
			java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static void installEmptyLoadingModList() {
		try {
			Class<?> loadingModList = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (loadingModList.getMethod("get").invoke(null) != null) return;
			Object empty = loadingModList.getMethod("of", java.util.List.class,
				java.util.List.class, java.util.List.class, java.util.List.class,
				java.util.Map.class).invoke(null, java.util.List.of(), java.util.List.of(),
				java.util.List.of(), java.util.List.of(), java.util.Map.of());
			if (empty == null) throw new IllegalStateException("LoadingModList.of returned null");
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static void markMinecraftBootstrapped() {
		try {
			java.lang.reflect.Field bootstrapped = net.minecraft.server.Bootstrap.class
				.getDeclaredField("isBootstrapped");
			bootstrapped.setAccessible(true);
			bootstrapped.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	@Test
	void clientProjectionNeitherWritesNorConsumesAuthoritativeServerState() {
		CompoundTag clientUpdate = new CompoundTag();
		LibraryBlockEntity.writeAuthoritativeProjection(clientUpdate, true,
			tag -> tag.putByteArray("ServerSecret", new byte[] { 1, 2, 3 }));
		assertFalse(clientUpdate.contains("ServerSecret"));

		CompoundTag diskSave = new CompoundTag();
		LibraryBlockEntity.writeAuthoritativeProjection(diskSave, false,
			tag -> tag.putByteArray("ServerSecret", new byte[] { 1, 2, 3 }));
		assertArrayEquals(new byte[] { 1, 2, 3 }, diskSave.getByteArray("ServerSecret"));

		byte[][] authoritative = { new byte[] { 8, 5, 3 } };
		CompoundTag hostileClientUpdate = new CompoundTag();
		hostileClientUpdate.putByteArray("ServerSecret", new byte[] { 0 });
		LibraryBlockEntity.readAuthoritativeProjection(hostileClientUpdate, true,
			tag -> authoritative[0] = tag.getByteArray("ServerSecret"));
		assertArrayEquals(new byte[] { 8, 5, 3 }, authoritative[0]);

		LibraryBlockEntity.readAuthoritativeProjection(diskSave, false,
			tag -> authoritative[0] = tag.getByteArray("ServerSecret"));
		assertArrayEquals(new byte[] { 1, 2, 3 }, authoritative[0]);
	}

}
