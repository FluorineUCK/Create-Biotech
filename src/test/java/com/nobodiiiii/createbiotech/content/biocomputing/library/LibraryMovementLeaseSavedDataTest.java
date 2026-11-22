package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryMovementLeases;

import com.nobodiiiii.createbiotech.content.biocomputing.block.LibraryCoreBlock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;


import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

class LibraryMovementLeaseSavedDataTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		try {
			java.lang.reflect.Field bootstrapped = net.minecraft.server.Bootstrap.class
				.getDeclaredField("isBootstrapped");
			bootstrapped.setAccessible(true);
			bootstrapped.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static void installEmptyLoadingModList() {
		try {
			Class<?> loadingModList = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (loadingModList.getMethod("get").invoke(null) != null)
				return;
			Object empty = loadingModList.getMethod("of", java.util.List.class,
				java.util.List.class, java.util.List.class, java.util.List.class,
				java.util.Map.class).invoke(null, java.util.List.of(), java.util.List.of(),
				java.util.List.of(), java.util.List.of(), java.util.Map.of());
			if (empty == null)
				throw new IllegalStateException("LoadingModList.of returned null");
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	@Test
	void retainedIdentitySurvivesSaveAndReloadUntilExactDisposal() {
		UUID libraryId = UUID.fromString("508d6e42-ffdc-42a8-a7c0-9cd64f3ce22f");
		UUID token = UUID.fromString("21cf33a5-6cc2-4284-97f4-f349cc3e322c");
		LibraryMovementLeases original = new LibraryMovementLeases();
		assertTrue(original.reserve(libraryId, token));

		CompoundTag encoded = original.save(new CompoundTag(), RegistryAccess.EMPTY);
		LibraryMovementLeases restored =
			LibraryMovementLeases.load(encoded, RegistryAccess.EMPTY);

		assertTrue(restored.blocksClaim(libraryId, null));
		assertTrue(restored.blocksClaim(libraryId, UUID.randomUUID()));
		assertFalse(restored.blocksClaim(libraryId, token));
		assertFalse(restored.release(libraryId, UUID.randomUUID()),
			"a different contraption token must not dispose the durable owner");
		assertTrue(restored.release(libraryId, token));
		assertFalse(restored.blocksClaim(libraryId, null));
	}

	@Test
	void oneTokenAndOneLibraryCannotBeAliasedAcrossContraptions() {
		LibraryMovementLeases data = new LibraryMovementLeases();
		UUID libraryId = UUID.fromString("98afafbd-4b70-431d-bb28-d910c44585fb");
		UUID otherLibrary = UUID.fromString("a70f95af-ed87-412a-b292-c73ef7b93857");
		UUID token = UUID.fromString("c6cfda43-0575-4d8c-aea4-93ccf868384b");
		UUID otherToken = UUID.fromString("37def3c0-ed69-451d-82ac-7db1bdcc4850");

		assertTrue(data.reserve(libraryId, token));
		assertTrue(data.reserve(libraryId, token), "the exact persisted owner is idempotent");
		assertFalse(data.reserve(libraryId, otherToken));
		assertFalse(data.reserve(otherLibrary, token));
		assertEquals(1, data.leaseCount());
	}

	@Test
	void contraptionNbtCarriesTheSameLibraryAndTokenPair() {
		UUID libraryId = UUID.fromString("dd155adf-5111-4787-a679-1151b168582c");
		UUID token = UUID.fromString("52eb692e-258e-403d-8b8e-9beaf9da1824");
		FakeOwner owner = new FakeOwner();
		assertTrue(owner.createBiotech$addPatternCoreLease(libraryId, token));
		CompoundTag contraption = new CompoundTag();

		LibraryContraptionMovement.writeLeases(owner, contraption);

		var leases = contraption.getList("CreateBiotechPatternCoreLeases", Tag.TAG_COMPOUND);
		assertEquals(1, leases.size());
		assertEquals(libraryId, leases.getCompound(0).getUUID("LibraryId"));
		assertEquals(token, leases.getCompound(0).getUUID("Token"));
	}

	@Test
	void contraptionLeaseDecodeAcceptsOnlyPairsFromSerializedLowerCores() {
		UUID acceptedLibrary = UUID.fromString("6e61bc26-e334-4f35-8d44-92098ea77e79");
		UUID acceptedToken = UUID.fromString("0e10a566-c70a-413c-9b25-8e6a3c9feb71");
		UUID mismatchedLibrary = UUID.fromString("f569030c-f6e8-4991-96ab-0184cd4e8fc9");
		UUID serializedToken = UUID.fromString("2f0a979f-1386-4d32-a3ee-2b8553154e9f");
		UUID injectedToken = UUID.fromString("3f320c73-9fe6-44ba-9f28-d2243dc888dc");
		LibraryCoreBlock block = new LibraryCoreBlock(BlockBehaviour.Properties.of());
		Map<BlockPos, StructureBlockInfo> blocks = new LinkedHashMap<>();
		blocks.put(BlockPos.ZERO, serializedLowerCore(block, BlockPos.ZERO,
			acceptedLibrary, acceptedToken));
		blocks.put(BlockPos.ZERO.east(), serializedLowerCore(block, BlockPos.ZERO.east(),
			mismatchedLibrary, serializedToken));
		blocks.put(BlockPos.ZERO.above(), new StructureBlockInfo(BlockPos.ZERO.above(),
			block.defaultBlockState().setValue(LibraryCoreBlock.HALF,
				DoubleBlockHalf.UPPER),
			serializedCoreData(UUID.randomUUID(), UUID.randomUUID())));

		CompoundTag root = contraptionLeases(Map.of(
			acceptedLibrary, acceptedToken,
			mismatchedLibrary, injectedToken));

		assertEquals(Map.of(acceptedLibrary, acceptedToken),
			LibraryContraptionMovement.validatedSerializedLeases(blocks, root));

		ListTag raw = root.getList("CreateBiotechPatternCoreLeases", Tag.TAG_COMPOUND);
		raw.add(lease(UUID.randomUUID(), UUID.randomUUID()));
		assertTrue(LibraryContraptionMovement.validatedSerializedLeases(blocks, root).isEmpty(),
			"more lease rows than serialized lower cores must fail closed before decode");
	}

	@Test
	void durableLeaseLoadHasAGlobalRepresentationBound() {
		CompoundTag root = contraptionLeases(Map.of(
			UUID.fromString("6d3da04e-405e-4e7e-b476-c9656fc4a4d2"),
			UUID.fromString("f2dbbf2d-dde1-4a89-9a9f-55a45d8dc35b"),
			UUID.fromString("a54f8634-6de3-4309-b958-f2e7015dcff0"),
			UUID.fromString("b5a1d807-cb90-4147-bd36-6dcaf403ac95"),
			UUID.fromString("5a605234-41d1-4c75-804d-e15f185a2ba1"),
			UUID.fromString("8aa3c0a0-f32d-4e22-9c1b-8f423c92e7a8")));
		root.put("Leases", root.getList("CreateBiotechPatternCoreLeases", Tag.TAG_COMPOUND));

		LibraryMovementLeases bounded =
			LibraryMovementLeases.load(root, RegistryAccess.EMPTY, 2);

		assertEquals(0, bounded.leaseCount(),
			"an oversized global lease table must be rejected instead of partially trusted");
		UUID blockedLibrary = UUID.fromString("1b2322d3-bff4-4d44-b36a-a715b5fa6383");
		UUID blockedToken = UUID.fromString("c3a8cad6-cc39-4a1c-bc3e-37dbc1e3bff5");
		assertTrue(bounded.blocksClaim(blockedLibrary, null),
			"rejecting oversized persistence must lock claims rather than authoritatively empty them");
		assertFalse(bounded.reserve(blockedLibrary, blockedToken));

		CompoundTag persistedBarrier = bounded.save(new CompoundTag(), RegistryAccess.EMPTY);
		LibraryMovementLeases restarted =
			LibraryMovementLeases.load(persistedBarrier, RegistryAccess.EMPTY, 2);
		assertTrue(restarted.blocksClaim(blockedLibrary, null),
			"the fail-closed load state must survive its next save and restart");
		assertFalse(restarted.reserve(blockedLibrary, blockedToken));
	}

	@Test
	void reserveSaveAndReloadShareTheGlobalLeaseCap() {
		int maximum = LibraryMovementLeases.MAX_LEASES;
		LibraryMovementLeases data = new LibraryMovementLeases();
		for (int ordinal = 0; ordinal < maximum; ordinal++)
			assertTrue(data.reserve(library(ordinal), token(ordinal)),
				"entry " + ordinal + " must fit within the shared representation cap");
		assertTrue(data.reserve(library(0), token(0)),
			"the exact owner remains idempotent when the table is full");
		assertEquals(maximum, data.leaseCount());

		CompoundTag saved = data.save(new CompoundTag(), RegistryAccess.EMPTY);
		assertEquals(maximum, saved.getList("Leases", Tag.TAG_COMPOUND).size());
		LibraryMovementLeases restarted =
			LibraryMovementLeases.load(saved, RegistryAccess.EMPTY);
		assertEquals(maximum, restarted.leaseCount());
		assertFalse(restarted.reserve(library(maximum), token(maximum)),
			"the 16,385th distinct owner must trip a global claim barrier");
		assertTrue(restarted.isDirty(),
			"tripping the runtime barrier must schedule its durable representation");
		assertTrue(restarted.blocksClaim(library(maximum), null),
			"a rejected moving Core must stay protected even though no 16,385th row can be represented");
		assertEquals(0, restarted.leaseCount(),
			"the barrier supersedes rows that can no longer be represented authoritatively");

		CompoundTag failedClosed = restarted.save(new CompoundTag(), RegistryAccess.EMPTY);
		assertTrue(failedClosed.getBoolean("FailClosed"));
		assertEquals(0, failedClosed.getList("Leases", Tag.TAG_COMPOUND).size());
		LibraryMovementLeases barrierAfterRestart =
			LibraryMovementLeases.load(failedClosed, RegistryAccess.EMPTY);
		assertTrue(barrierAfterRestart.blocksClaim(library(maximum), null),
			"saving and restarting after overflow must not unlock the rejected moving Core");
		assertFalse(barrierAfterRestart.reserve(library(maximum), token(maximum)));
	}

	@Test
	void oversizedWrongElementTypeCannotBypassTheGlobalLoadCap() {
		CompoundTag root = new CompoundTag();
		ListTag malformed = new ListTag();
		malformed.add(StringTag.valueOf("one"));
		malformed.add(StringTag.valueOf("two"));
		malformed.add(StringTag.valueOf("three"));
		root.put("Leases", malformed);

		UUID blockedLibrary = UUID.fromString("d51dc268-f607-42c1-bb7c-4e1a31cc25a6");
		UUID blockedToken = UUID.fromString("fa54f227-59dd-4087-9b01-fe48e8f62f17");
		LibraryMovementLeases loaded =
			LibraryMovementLeases.load(root, RegistryAccess.EMPTY, 2);

		assertTrue(loaded.isDirty(),
			"normalizing malformed persistence into a barrier must schedule a save");
		assertTrue(loaded.blocksClaim(blockedLibrary, null),
			"typed getList must not turn an oversized non-compound list into empty authority");
		assertFalse(loaded.reserve(blockedLibrary, blockedToken));
		CompoundTag normalized = loaded.save(new CompoundTag(), RegistryAccess.EMPTY);
		assertTrue(normalized.getBoolean("FailClosed"));
		assertTrue(LibraryMovementLeases.load(normalized, RegistryAccess.EMPTY)
			.blocksClaim(blockedLibrary, null));
	}

	@Test
	void malformedLeaseRowCannotMakeItsLibraryAvailableForAnOrdinaryClaim() {
		UUID libraryId = UUID.randomUUID();
		CompoundTag row = new CompoundTag(); row.putUUID("LibraryId", libraryId);
		row.putString("Token", "damaged-token");
		ListTag rows = new ListTag(); rows.add(row);
		CompoundTag root = new CompoundTag(); root.put("Leases", rows);
		var restored = LibraryMovementLeases.load(root, RegistryAccess.EMPTY);
		assertTrue(restored.blocksClaim(libraryId, null), "A damaged owner must not become unclaimed");
		assertFalse(restored.reserve(libraryId, UUID.randomUUID()));
		assertTrue(restored.isDirty(), "The authority barrier must be persisted");
		var reloaded = LibraryMovementLeases.load(restored.save(new CompoundTag(), RegistryAccess.EMPTY), RegistryAccess.EMPTY);
		assertTrue(reloaded.blocksClaim(libraryId, null));
	}

	@Test
	void conflictingSerializedOwnersCannotBeChosenByRowOrder() {
		UUID first = UUID.randomUUID(), second = UUID.randomUUID();
		UUID token = UUID.randomUUID(), otherToken = UUID.randomUUID();
		for (boolean sameLibrary : java.util.List.of(false, true)) {
			for (boolean reversed : java.util.List.of(false, true)) {
				CompoundTag a = lease(first, token);
				CompoundTag b = lease(sameLibrary ? first : second, sameLibrary ? otherToken : token);
				ListTag rows = new ListTag(); rows.add(reversed ? b : a); rows.add(reversed ? a : b);
				CompoundTag root = new CompoundTag(); root.put("Leases", rows);
				var loaded = LibraryMovementLeases.load(root, RegistryAccess.EMPTY);
				assertTrue(loaded.blocksClaim(first, token), "A contradictory table cannot authorize its first row");
				assertTrue(loaded.blocksClaim(second, null), "Rejected rows must not become empty authority");
				assertFalse(loaded.reserve(first, token));
				assertTrue(loaded.isDirty());
				assertTrue(loaded.save(new CompoundTag(), RegistryAccess.EMPTY).getBoolean("FailClosed"));
			}
		}
	}

	@Test
	void malformedPersistedBarrierCannotDecodeAsAvailableAuthority() {
		CompoundTag root = new CompoundTag(); root.putString("FailClosed", "true");
		root.put("Leases", new ListTag());
		var loaded = LibraryMovementLeases.load(root, RegistryAccess.EMPTY);
		assertTrue(loaded.blocksClaim(UUID.randomUUID(), null), "Wrong-typed barrier cannot become false");
		assertTrue(loaded.isDirty());
		assertTrue(loaded.save(new CompoundTag(), RegistryAccess.EMPTY).getBoolean("FailClosed"));
	}

	private static UUID library(int ordinal) {
		return new UUID(0x1a11b00000000000L, ordinal + 1L);
	}

	private static UUID token(int ordinal) {
		return new UUID(0x7eaae00000000000L, ordinal + 1L);
	}

	private static StructureBlockInfo serializedLowerCore(LibraryCoreBlock block,
		BlockPos pos, UUID libraryId, UUID token) {
		return new StructureBlockInfo(pos, block.defaultBlockState().setValue(
			LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER),
			serializedCoreData(libraryId, token));
	}

	private static CompoundTag serializedCoreData(UUID libraryId, UUID token) {
		CompoundTag data = new CompoundTag();
		CompoundTag pattern = new CompoundTag();
		pattern.putUUID("LibraryId", libraryId);
		CompoundTag server = new CompoundTag();
		server.putUUID("MovementClaimToken", token);
		pattern.put("ServerState", server);
		data.put("PatternLibrary", pattern);
		return data;
	}

	private static CompoundTag contraptionLeases(Map<UUID, UUID> values) {
		CompoundTag root = new CompoundTag();
		ListTag leases = new ListTag();
		values.forEach((libraryId, token) -> leases.add(lease(libraryId, token)));
		root.put("CreateBiotechPatternCoreLeases", leases);
		return root;
	}

	private static CompoundTag lease(UUID libraryId, UUID token) {
		CompoundTag lease = new CompoundTag();
		lease.putUUID("LibraryId", libraryId);
		lease.putUUID("Token", token);
		return lease;
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
