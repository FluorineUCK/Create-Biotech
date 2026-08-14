package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingPreparation;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingService;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberIndex;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.factorycluster.LogisticsBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;
import com.nobodiiiii.createbiotech.foundation.item.CBItemData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

class PatternStorageCoreBlockEntityTest {
	private static final RegistryAccess REGISTRIES = RegistryAccess.EMPTY;
	private static final UUID SPACE = UUID.fromString("69d47c2c-71ee-488c-8dc9-21e4d2b143b4");
	private static final ResourceLocation TEST_ATTACHMENT_ID = ResourceLocation.fromNamespaceAndPath(
		"create_biotech", "pattern_core_round_trip_test");
	private static final IAttachmentSerializer<StringTag, String> TEST_ATTACHMENT_SERIALIZER =
		new IAttachmentSerializer<>() {
			@Override
			public String read(IAttachmentHolder holder, StringTag tag,
				net.minecraft.core.HolderLookup.Provider provider) {
				return tag.getAsString();
			}

			@Override
			public StringTag write(String value, net.minecraft.core.HolderLookup.Provider provider) {
				return StringTag.valueOf(value);
			}
		};
	private static final AttachmentType<String> TEST_ATTACHMENT = createTestAttachment();
	private static boolean testAttachmentRegistered;
	private static final SpaceAddress SHELF_ADDRESS = new SpaceAddress(Level.OVERWORLD, SPACE,
		new BlockPos(1, 0, 0));

	@Test
	void sixWritableBooksExposeSixHundredDistinctLogicalPagesAndStableIdentity() {
		List<ItemStack> slots = new ArrayList<>();
		for (int slot = 0; slot < 6; slot++)
			slots.add(writableBook("page-" + slot));

		List<PatternPageKey> keys = PatternStorageCoreBlockEntity.pageKeys(SHELF_ADDRESS, slots);

		assertEquals(600, keys.size());
		assertEquals(600, new HashSet<>(keys).size());
		assertEquals("page-0", PatternStorageCoreBlockEntity.readPage(slots,
			new PatternPageKey(SHELF_ADDRESS, 0, 0)));
		assertEquals("", PatternStorageCoreBlockEntity.readPage(slots,
			new PatternPageKey(SHELF_ADDRESS, 0, 99)));
		PatternPageKey reference = new PatternPageKey(SHELF_ADDRESS, 0, 0);
		assertAll(
			() -> assertNotEquals(reference, new PatternPageKey(SHELF_ADDRESS, 1, 0)),
			() -> assertNotEquals(reference, new PatternPageKey(SHELF_ADDRESS, 0, 1)),
			() -> assertNotEquals(reference, new PatternPageKey(new SpaceAddress(Level.OVERWORLD,
				SPACE, new BlockPos(2, 0, 0)), 0, 0)));
	}

	@Test
	void efficiencyBookContributesOnlyItsQuadraticBudgetBonus() {
		Holder<Enchantment> efficiency = Holder.direct(allocate(Enchantment.class));
		ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
		enchantments.set(efficiency, 3);
		ItemStack enchantedBook = new ItemStack(Items.ENCHANTED_BOOK);
		enchantedBook.set(DataComponents.STORED_ENCHANTMENTS, enchantments.toImmutable());
		List<ItemStack> slots = List.of(enchantedBook, ItemStack.EMPTY, ItemStack.EMPTY,
			ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);

		assertTrue(PatternStorageCoreBlockEntity.pageKeys(SHELF_ADDRESS, slots).isEmpty());
		assertEquals(10, PatternStorageCoreBlockEntity.efficiencyBonus(slots, efficiency));
	}

	@Test
	void insertingAndReplacingWritableBookUpdatesKeysThenUsesFingerprintDetection() {
		PatternLibraryIndex index = new PatternLibraryIndex();
		List<ItemStack> empty = List.of(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY,
			ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
		assertFalse(PatternStorageCoreBlockEntity.synchronizePageOrder(index,
			PatternStorageCoreBlockEntity.pageKeys(SHELF_ADDRESS, empty)));

		List<ItemStack> first = List.of(writableBook("first"), ItemStack.EMPTY, ItemStack.EMPTY,
			ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
		assertTrue(PatternStorageCoreBlockEntity.synchronizePageOrder(index,
			PatternStorageCoreBlockEntity.pageKeys(SHELF_ADDRESS, first)));
		assertEquals(100, index.pageOrder().size());
		PatternJsonParser parser = new PatternJsonParser(REGISTRIES);
		index.tickFingerprintChecks(key -> PatternStorageCoreBlockEntity.readPage(first, key), parser, 100);
		long indexedGeneration = index.generation();

		List<ItemStack> replacement = List.of(writableBook("second"), ItemStack.EMPTY, ItemStack.EMPTY,
			ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
		assertFalse(PatternStorageCoreBlockEntity.synchronizePageOrder(index,
			PatternStorageCoreBlockEntity.pageKeys(SHELF_ADDRESS, replacement)));
		assertEquals(indexedGeneration, index.generation());
		index.beginNextFingerprintSweep();
		index.tickFingerprintChecks(key -> PatternStorageCoreBlockEntity.readPage(replacement, key), parser, 1);
		assertEquals(indexedGeneration + 1, index.generation());
	}

	@Test
	void pageTopologyChangesOnlyWhenWritableSlotMembershipChanges() {
		List<ItemStack> first = List.of(writableBook("first"), ItemStack.EMPTY, ItemStack.EMPTY,
			ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
		List<ItemStack> edited = List.of(writableBook("edited body"), ItemStack.EMPTY, ItemStack.EMPTY,
			ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
		List<ItemStack> moved = List.of(ItemStack.EMPTY, writableBook("edited body"), ItemStack.EMPTY,
			ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);

		assertEquals(PatternStorageCoreBlockEntity.pageTopology(first),
			PatternStorageCoreBlockEntity.pageTopology(edited));
		assertNotEquals(PatternStorageCoreBlockEntity.pageTopology(first),
			PatternStorageCoreBlockEntity.pageTopology(moved));
	}

	@Test
	void ordinaryShelfChangesDoNotRebuildUnchangedPageTopology() {
		bootstrap();
		PatternStorageCoreBlockEntity core = new ReadyCore();
		BlockPos chiseled = new BlockPos(1, 0, 0);
		Map<BlockPos, Integer> topology = Map.of(chiseled, 0);
		PatternStructureSnapshot initial = new PatternStructureSnapshot(
			PatternLibraryScanner.StructureState.VALID,
			List.of(BlockPos.ZERO, chiseled), List.of(), List.of(chiseled),
			BlockPos.ZERO, chiseled, 1);
		core.applyStructureScan(new PatternLibraryScanner.ScanResult(
			PatternLibraryScanner.StructureState.VALID, initial), topology, 0);
		long indexedGeneration = core.libraryIndex().generation();
		BlockPos ordinary = new BlockPos(0, 0, 1);
		PatternStructureSnapshot queueOnlyChange = new PatternStructureSnapshot(
			PatternLibraryScanner.StructureState.VALID,
			List.of(BlockPos.ZERO, chiseled, ordinary), List.of(ordinary), List.of(chiseled),
			BlockPos.ZERO, new BlockPos(1, 0, 1), 2);

		core.applyStructureScan(new PatternLibraryScanner.ScanResult(
			PatternLibraryScanner.StructureState.VALID, queueOnlyChange), topology, 0);

		assertEquals(indexedGeneration, core.libraryIndex().generation());
		assertTrue(core.libraryIndex().pageOrder().isEmpty());
		assertEquals(2, core.queueCount());
	}

	@Test
	void coreExposesBoundedPanelSummaryFromRuntimeCounters() {
		PatternStorageCoreBlockEntity core = new ReadyCore();
		PatternStructureSnapshot snapshot = snapshotWithChiseledShelf();
		core.applyStructureScan(new PatternLibraryScanner.ScanResult(
			PatternLibraryScanner.StructureState.VALID, snapshot));
		PatternPageKey key = new PatternPageKey(SHELF_ADDRESS, 0, 0);
		core.libraryIndex().rebuildPageOrder(List.of(key));
		core.libraryIndex().tickFingerprintChecks(ignored -> "{",
			new PatternJsonParser(REGISTRIES), 1);

		PatternLibrarySummary summary = core.summary();

		assertAll(
			() -> assertEquals(PatternLibraryScanner.StructureState.VALID, summary.reason()),
			() -> assertEquals(600, summary.capacity()),
			() -> assertEquals(1, summary.pages()),
			() -> assertEquals(1, summary.indexedPages()),
			() -> assertEquals(1, summary.errorPages()),
			() -> assertEquals(1, summary.errors().size()));
	}

	@Test
	void unloadedKnownMemberStopsAllIndexAdvancementAndRetainsPriorState() {
		PatternStorageCoreBlockEntity core = new ReadyCore();
		PatternStructureSnapshot snapshot = snapshotWithChiseledShelf();
		core.applyStructureScan(new PatternLibraryScanner.ScanResult(
			PatternLibraryScanner.StructureState.VALID, snapshot));
		PatternPageKey key = new PatternPageKey(SHELF_ADDRESS, 0, 0);
		core.libraryIndex().rebuildPageOrder(List.of(key));
		CompoundTag before = core.libraryIndex().save(REGISTRIES);

		boolean advanced = core.serviceIndexIfMembersLoaded(pos -> !pos.equals(new BlockPos(1, 0, 0)),
			ignored -> "changed", new PatternJsonParser(REGISTRIES), 100);

		assertFalse(advanced);
		assertEquals(before, core.libraryIndex().save(REGISTRIES));
		assertSame(snapshot, core.structureSnapshot());
		assertEquals(PatternLibraryScanner.StructureState.PARTIAL, core.structureState());
	}

	@Test
	void missingShelfEntityBetweenScheduledScansStopsServiceBeforeReadingOrRestarting() {
		ReadyCore core = new ReadyCore();
		PatternStructureSnapshot snapshot = snapshotWithChiseledShelf();
		core.applyStructureScan(new PatternLibraryScanner.ScanResult(
			PatternLibraryScanner.StructureState.VALID, snapshot),
			Map.of(new BlockPos(1, 0, 0), 0), 0);
		PatternPageKey key = new PatternPageKey(SHELF_ADDRESS, 0, 0);
		core.libraryIndex().rebuildPageOrder(List.of(key));
		core.libraryIndex().tickFingerprintChecks(ignored -> "{",
			new PatternJsonParser(REGISTRIES), 1);
		core.libraryIndex().enqueue(query(Items.IRON_INGOT));
		core.libraryIndex().tickQueries(1);
		core.libraryIndex().enqueue(query(Items.GOLD_INGOT));
		CompoundTag retained = core.libraryIndex().save(REGISTRIES);
		AtomicInteger reads = new AtomicInteger();
		core.shelfEntitiesReadable = false;

		boolean advanced = core.serviceIndexIfMembersLoaded(ignored -> true, ignored -> {
			reads.incrementAndGet();
			return "";
		}, new PatternJsonParser(REGISTRIES), 1);

		assertFalse(advanced);
		assertEquals(0, reads.get(), "Wrong/missing shelf BE must gate before PageReader");
		assertEquals(PatternLibraryScanner.StructureState.PARTIAL, core.structureState());
		assertEquals(retained, core.libraryIndex().save(REGISTRIES),
			"Incomplete inspection must retain cache, active queries, ready replies, and cursors");
	}

	@Test
	void completedRepliesRemainQueuedUntilBoundedDrain() {
		PatternStorageCoreBlockEntity core = new ReadyCore();
		core.applyStructureScan(new PatternLibraryScanner.ScanResult(
			PatternLibraryScanner.StructureState.VALID, coreOnlySnapshot()));
		PatternQuery first = query(Items.IRON_INGOT);
		PatternQuery second = query(Items.GOLD_INGOT);
		core.libraryIndex().enqueue(first);
		core.libraryIndex().enqueue(second);

		assertTrue(core.serviceIndexIfMembersLoaded(ignored -> true, ignored -> "",
			new PatternJsonParser(REGISTRIES), 2));
		assertEquals(2, core.libraryIndex().readyReplyCount());
		assertTrue(core.serviceIndexIfMembersLoaded(ignored -> true, ignored -> "",
			new PatternJsonParser(REGISTRIES), 2));
		assertEquals(2, core.libraryIndex().readyReplyCount());
		assertFalse(core.canRebind());
		assertEquals(1, core.libraryIndex().drainReplies(first.requesterComputerId(), 99).size());
		assertEquals(1, core.libraryIndex().readyReplyCount());
		assertFalse(core.canRebind());
		assertEquals(1, core.libraryIndex().drainReplies(second.requesterComputerId(), 99).size());
		assertTrue(core.canRebind());
	}

	@Test
	void partialScanSynchronizesStatusWithoutReplacingSnapshotOrIndex() {
		SyncTrackingCore core = new SyncTrackingCore();
		PatternStructureSnapshot snapshot = coreOnlySnapshot();
		core.applyStructureScan(new PatternLibraryScanner.ScanResult(
			PatternLibraryScanner.StructureState.VALID, snapshot));
		core.libraryIndex().enqueue(query(Items.IRON_INGOT));
		CompoundTag before = core.libraryIndex().save(REGISTRIES);
		int priorSyncs = core.syncs;

		core.applyStructureScan(new PatternLibraryScanner.ScanResult(
			PatternLibraryScanner.StructureState.PARTIAL, null));

		assertEquals(PatternLibraryScanner.StructureState.PARTIAL, core.structureState());
		assertSame(snapshot, core.structureSnapshot());
		assertEquals(before, core.libraryIndex().save(REGISTRIES));
		assertEquals(priorSyncs + 1, core.syncs);
		CompoundTag client = new CompoundTag();
		core.write(client, REGISTRIES, true);
		PatternCoreClientState projected = PatternCoreClientState.load(
			client.getCompound("ClientState")).orElseThrow();
		assertEquals(PatternLibraryScanner.StructureState.PARTIAL, projected.structureState());
		assertEquals(1, projected.logicalMembers());
	}

	@Test
	void realScannerConflictThenSameValidStructureRetainsQueriesRepliesAndGeneration() {
		BlockPos shelf = new BlockPos(1, 0, 0);
		Map<BlockPos, PatternLibraryScanner.MemberKind> members = new java.util.LinkedHashMap<>();
		members.put(BlockPos.ZERO, PatternLibraryScanner.MemberKind.CORE_LOWER);
		members.put(shelf, PatternLibraryScanner.MemberKind.CHISELED_BOOKSHELF);
		PatternLibraryScanner.ScanResult valid = PatternLibraryScanner.scan(
			scannerView(members), BlockPos.ZERO, 64, 16);
		ReadyCore core = coreWithRetainedWork(valid, shelf);
		CompoundTag retained = core.libraryIndex().save(REGISTRIES);
		members.put(new BlockPos(-1, 0, 0), PatternLibraryScanner.MemberKind.CORE_LOWER);
		PatternLibraryScanner.ScanResult conflict = PatternLibraryScanner.scan(
			scannerView(members), BlockPos.ZERO, 64, 16);

		core.applyStructureScan(conflict, Map.of(), 0);

		assertEquals(PatternLibraryScanner.StructureState.CORE_CONFLICT,
			core.structureState());
		assertEquals(retained, core.libraryIndex().save(REGISTRIES));
		members.remove(new BlockPos(-1, 0, 0));
		PatternLibraryScanner.ScanResult sameValid = PatternLibraryScanner.scan(
			scannerView(members), BlockPos.ZERO, 64, 16);
		core.applyStructureScan(sameValid, Map.of(shelf, 0), 0);
		assertEquals(PatternLibraryScanner.StructureState.VALID, core.structureState());
		assertEquals(retained, core.libraryIndex().save(REGISTRIES));
	}

	@Test
	void everyNonValidScannerStateFreezesRetainedIndexState() {
		BlockPos shelf = new BlockPos(1, 0, 0);
		PatternLibraryScanner.ScanResult valid = new PatternLibraryScanner.ScanResult(
			PatternLibraryScanner.StructureState.VALID, snapshotWithChiseledShelf());
		for (PatternLibraryScanner.StructureState state : PatternLibraryScanner.StructureState.values()) {
			if (state == PatternLibraryScanner.StructureState.VALID)
				continue;
			ReadyCore core = coreWithRetainedWork(valid, shelf);
			CompoundTag retained = core.libraryIndex().save(REGISTRIES);

			core.applyStructureScan(new PatternLibraryScanner.ScanResult(state, null),
				Map.of(), 0);

			assertEquals(retained, core.libraryIndex().save(REGISTRIES), state.name());
		}
	}

	@Test
	void persistedTopologyPreventsFirstEquivalentValidRefreshFromRestartingLoadedWork() {
		BlockPos shelf = new BlockPos(1, 0, 0);
		PatternLibraryScanner.ScanResult valid = new PatternLibraryScanner.ScanResult(
			PatternLibraryScanner.StructureState.VALID, snapshotWithChiseledShelf());
		ReadyCore source = coreWithRetainedWork(valid, shelf);
		CompoundTag saved = source.saveWithFullMetadata(REGISTRIES);

		assertTrue(serverState(saved).contains("PageTopologies", Tag.TAG_LIST),
			"Writable-slot topology must be part of authoritative server persistence");
		PatternStorageCoreBlockEntity restored = core();
		restored.loadWithComponents(saved, REGISTRIES);
		CompoundTag retained = restored.libraryIndex().save(REGISTRIES);
		assertEquals(1, restored.libraryIndex().activeQueryCount());
		assertEquals(1, restored.libraryIndex().readyReplyCount());

		restored.applyStructureScan(valid, Map.of(shelf, 0), 0);

		assertEquals(retained, restored.libraryIndex().save(REGISTRIES));
		assertEquals(1, restored.libraryIndex().activeQueryCount());
		assertEquals(1, restored.libraryIndex().readyReplyCount());
	}

	@Test
	void priorNestedServerShapeRestoresBindingQueriesRepliesAndCursorBeforeMatchingReconstruction() {
		BlockPos shelf = new BlockPos(1, 0, 0);
		PatternLibraryScanner.ScanResult valid = new PatternLibraryScanner.ScanResult(
			PatternLibraryScanner.StructureState.VALID, snapshotWithChiseledShelf());
		ReadyCore source = coreWithRetainedWork(valid, shelf);
		source.installLibrarianSnapshot(capturedLibrarian("minecraft:plains", 3,
			"\"Prior nested librarian\"", true));
		source.commitClusterBinding(binding(source, UUID.randomUUID(), 11));
		CompoundTag priorNested = source.saveWithFullMetadata(REGISTRIES);
		CompoundTag priorIndex = serverState(priorNested).getCompound("PatternIndex");
		priorIndex.remove("ReplyRequesterCursor");
		serverState(priorNested).remove("PageTopologies");
		assertEquals(Set.of("LibrarianSnapshotBox", "PendingSafeRelease", "StructureState",
			"StructureSnapshot", "PatternIndex"), serverState(priorNested).getAllKeys());
		assertEquals(1, priorIndex.getList("Queries", Tag.TAG_COMPOUND).size());
		assertTrue(priorIndex.getList("Queries", Tag.TAG_COMPOUND).getCompound(0)
			.contains("Cursor", Tag.TAG_INT));
		assertEquals(1, priorIndex.getList("Replies", Tag.TAG_COMPOUND).size());

		ReadyCore restored = new ReadyCore();
		restored.loadWithComponents(priorNested, REGISTRIES);

		assertEquals(source.memberId(), restored.memberId());
		assertEquals(source.bindingState(), restored.bindingState());
		assertEquals(1, restored.libraryIndex().activeQueryCount());
		assertEquals(1, restored.libraryIndex().readyReplyCount());
		CompoundTag restoredIndex = restored.libraryIndex().save(REGISTRIES);
		restoredIndex.remove("ReplyRequesterCursor");
		assertEquals(priorIndex, restoredIndex);
		assertTrue(topologyRefreshPending(restored));
		assertFalse(restored.queryAccessReady(),
			"Prior nested state must remain gated until loaded shelf topology is reconstructed");

		CompoundTag retained = restored.libraryIndex().save(REGISTRIES);
		restored.applyStructureScan(valid, Map.of(shelf, 0), 0);

		assertFalse(topologyRefreshPending(restored));
		assertTrue(restored.queryAccessReady());
		assertEquals(retained, restored.libraryIndex().save(REGISTRIES));
	}

	@Test
	void oversizedTopologyEnvelopeIsRejectedBeforeMutatingAuthoritativeState() {
		PatternStorageCoreBlockEntity target = seededCore();
		CompoundTag before = authoritativeSnapshot(target);
		ReadyCore source = coreWithRetainedWork(new PatternLibraryScanner.ScanResult(
			PatternLibraryScanner.StructureState.VALID, snapshotWithChiseledShelf()),
			new BlockPos(1, 0, 0));
		CompoundTag oversized = source.saveWithFullMetadata(REGISTRIES);
		serverState(oversized).put("PageTopologies", topologyEntries(1025, 0));

		target.loadWithComponents(oversized, REGISTRIES);

		assertEquals(before, authoritativeSnapshot(target));
	}

	@Test
	void mismatchedTopologyMaskAndPageOrderRemainRecoverableButCannotServeBeforeRefresh() {
		BlockPos shelf = new BlockPos(1, 0, 0);
		PatternLibraryScanner.ScanResult valid = new PatternLibraryScanner.ScanResult(
			PatternLibraryScanner.StructureState.VALID, snapshotWithChiseledShelf());
		ReadyCore source = coreWithRetainedWork(valid, shelf);
		CompoundTag mismatched = source.saveWithFullMetadata(REGISTRIES);
		serverState(mismatched).getList("PageTopologies", Tag.TAG_COMPOUND)
			.getCompound(0).putInt("WritableSlots", 1);
		CompoundTag recoverableIndex = serverState(mismatched).getCompound("PatternIndex").copy();

		ReadyCore restored = new ReadyCore();
		restored.loadWithComponents(mismatched, REGISTRIES);

		assertEquals(recoverableIndex, restored.libraryIndex().save(REGISTRIES));
		assertTrue(topologyRefreshPending(restored));
		assertFalse(restored.queryAccessReady());
		assertFalse(restored.enqueueQuery(query(Items.DIAMOND)));
		CompoundTag retained = restored.libraryIndex().save(REGISTRIES);

		restored.applyStructureScan(valid, Map.of(shelf, 0), 0);

		assertFalse(topologyRefreshPending(restored));
		assertTrue(restored.queryAccessReady());
		assertEquals(retained, restored.libraryIndex().save(REGISTRIES),
			"Equivalent derived empty page order must install corrected topology without restart");
	}

	@Test
	void topologyPositionsMustExactlyMatchPersistedValidSnapshot() {
		BlockPos shelf = new BlockPos(1, 0, 0);
		PatternLibraryScanner.ScanResult valid = new PatternLibraryScanner.ScanResult(
			PatternLibraryScanner.StructureState.VALID, snapshotWithChiseledShelf());
		ReadyCore source = coreWithRetainedWork(valid, shelf);
		CompoundTag mismatched = source.saveWithFullMetadata(REGISTRIES);
		ListTag topology = serverState(mismatched).getList("PageTopologies", Tag.TAG_COMPOUND);
		topology.getCompound(0).putLong("Pos", new BlockPos(2, 0, 0).asLong());
		CompoundTag recoverableIndex = serverState(mismatched).getCompound("PatternIndex").copy();

		ReadyCore restored = new ReadyCore();
		restored.loadWithComponents(mismatched, REGISTRIES);

		assertEquals(recoverableIndex, restored.libraryIndex().save(REGISTRIES));
		assertTrue(topologyRefreshPending(restored));
		assertFalse(restored.queryAccessReady());
	}

	@Test
	void internallyConsistentTopologyFromAnotherSpaceRemainsPendingAndCannotServe() {
		BlockPos shelf = new BlockPos(1, 0, 0);
		ReadyCore source = new ReadyCore();
		source.applyStructureScan(new PatternLibraryScanner.ScanResult(
			PatternLibraryScanner.StructureState.VALID, snapshotWithChiseledShelf()),
			Map.of(shelf, 0), 0);
		SpaceAddress wrongShelf = new SpaceAddress(Level.NETHER, UUID.randomUUID(), shelf);
		source.libraryIndex().rebuildPageOrder(PatternStorageCoreBlockEntity.pageKeys(
			wrongShelf, List.of(writableBook("{}"), ItemStack.EMPTY, ItemStack.EMPTY,
				ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY)));
		CompoundTag wrongSpace = source.saveWithFullMetadata(REGISTRIES);
		serverState(wrongSpace).getList("PageTopologies", Tag.TAG_COMPOUND)
			.getCompound(0).putInt("WritableSlots", 1);

		ReadyCore restored = new ReadyCore();
		restored.loadWithComponents(wrongSpace, REGISTRIES);

		assertTrue(topologyRefreshPending(restored));
		assertFalse(restored.queryAccessReady());
	}

	@Test
	void internallyConsistentSnapshotRootedAtAnotherCoreRemainsPendingAndCannotServe() {
		bootstrap();
		BlockPos otherCore = new BlockPos(5, 0, 0);
		BlockPos otherShelf = new BlockPos(6, 0, 0);
		PatternStructureSnapshot displaced = new PatternStructureSnapshot(
			PatternLibraryScanner.StructureState.VALID,
			List.of(otherCore, otherShelf), List.of(), List.of(otherShelf),
			otherCore, otherShelf, 1);
		ReadyCore source = new ReadyCore();
		source.applyStructureScan(new PatternLibraryScanner.ScanResult(
			PatternLibraryScanner.StructureState.VALID, displaced),
			Map.of(otherShelf, 0), 0);

		ReadyCore restored = new ReadyCore();
		restored.loadWithComponents(source.saveWithFullMetadata(REGISTRIES), REGISTRIES);

		assertTrue(topologyRefreshPending(restored));
		assertFalse(restored.queryAccessReady());
	}

	@Test
	void topologyDecodeAcceptsExactMaximumAndRejectsMaximumPlusOneBeforeCopying() {
		assertEquals(1024, PatternStorageCoreBlockEntity
			.loadPageTopologies(topologyEntries(1024, 0), 1024).orElseThrow().size());
		assertTrue(PatternStorageCoreBlockEntity
			.loadPageTopologies(topologyEntries(1025, 0), 1024).isEmpty());
	}

	@Test
	void validMaximumStructureCrossValidatesEmptyMasksAndEmptyPageOrder() {
		List<BlockPos> chiseled = new ArrayList<>();
		for (int ordinal = 0; ordinal < 1023; ordinal++)
			chiseled.add(new BlockPos(ordinal + 1, ordinal / 1024, 0));
		List<BlockPos> members = new ArrayList<>(1024);
		members.add(BlockPos.ZERO);
		members.addAll(chiseled);
		PatternStructureSnapshot maximum = new PatternStructureSnapshot(
			PatternLibraryScanner.StructureState.VALID, members, List.of(), chiseled,
			BlockPos.ZERO, chiseled.getLast(), 1);
		Map<BlockPos, Integer> topologies = PatternStorageCoreBlockEntity
			.loadPageTopologies(topologyEntries(1023, 0), 1024).orElseThrow();

		assertTrue(PatternStorageCoreBlockEntity.persistedTopologyMatches(
			maximum, topologies, List.of(), 1024));
	}

	@Test
	void missingExpectedShelfContentsMakesInspectionIncompleteInsteadOfShrinkingTopology() {
		BlockPos first = new BlockPos(1, 0, 0);
		BlockPos second = new BlockPos(2, 0, 0);
		PatternStructureSnapshot snapshot = new PatternStructureSnapshot(
			PatternLibraryScanner.StructureState.VALID,
			List.of(BlockPos.ZERO, first, second), List.of(), List.of(first, second),
			BlockPos.ZERO, second, 1);
		List<ItemStack> emptyShelf = List.of(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY,
			ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
		Holder<Enchantment> efficiency = Holder.direct(allocate(Enchantment.class));

		var incomplete = PatternStorageCoreBlockEntity.inspectShelfContents(snapshot,
			pos -> pos.equals(first) ? emptyShelf : null, efficiency);

		assertFalse(incomplete.complete());
		assertTrue(incomplete.topologies().isEmpty(),
			"A partial inspection must never publish a smaller topology");
		var complete = PatternStorageCoreBlockEntity.inspectShelfContents(snapshot,
			ignored -> emptyShelf, efficiency);
		assertTrue(complete.complete());
		assertEquals(Map.of(first, 0, second, 0), complete.topologies());
	}

	@Test
	void repeatedIdenticalStructureScanDoesNotResyncSteadyState() {
		bootstrap();
		SyncTrackingCore core = new SyncTrackingCore();
		PatternLibraryScanner.ScanResult valid = new PatternLibraryScanner.ScanResult(
			PatternLibraryScanner.StructureState.VALID, coreOnlySnapshot());
		core.applyStructureScan(valid);
		int afterChange = core.syncs;

		core.applyStructureScan(valid);

		assertEquals(afterChange, core.syncs);
	}

	@Test
	void registrationLeavesFreshCoreUnboundAndPreparedCommitRekeysExactlyOnce() {
		MinecraftServer server = allocate(DedicatedServer.class);
		ServerCore core = new ServerCore(server);
		ClusterMemberIndex.register(server, core);
		assertNull(core.bindingState());
		assertTrue(ClusterMemberIndex.members(server, UUID.randomUUID(),
			ClusterMemberType.PATTERN_CORE).isEmpty());

		ClusterBinding oldBinding = binding(core, UUID.randomUUID(), 1);
		ClusterBinding newBinding = binding(core, UUID.randomUUID(), 2);
		core.commitClusterBinding(oldBinding);
		assertEquals(List.of(core), ClusterMemberIndex.members(server, oldBinding.clusterId(),
			ClusterMemberType.PATTERN_CORE));
		assertEquals(ClusterBindingPreparation.READY, core.prepareClusterBinding(newBinding));
		assertDoesNotThrow(() -> core.commitClusterBinding(newBinding));

		assertTrue(ClusterMemberIndex.members(server, oldBinding.clusterId(),
			ClusterMemberType.PATTERN_CORE).isEmpty());
		assertEquals(List.of(core), ClusterMemberIndex.members(server, newBinding.clusterId(),
			ClusterMemberType.PATTERN_CORE));
		ClusterMemberIndex.unregister(server, core);
	}

	@Test
	void failedPreparationDoesNotMutateBindingOrIndex() {
		PatternStorageCoreBlockEntity core = core();
		ClusterBinding current = binding(core, UUID.randomUUID(), 4);
		core.commitClusterBinding(current);
		core.libraryIndex().enqueue(query(Items.IRON_INGOT));
		CompoundTag indexBefore = core.libraryIndex().save(REGISTRIES);

		assertEquals(ClusterBindingPreparation.ACTIVE,
			core.prepareClusterBinding(binding(core, UUID.randomUUID(), 5)));
		assertEquals(current, core.bindingState());
		assertEquals(indexBefore, core.libraryIndex().save(REGISTRIES));
	}

	@Test
	void queryGateRequiresReadyAccessAndMatchingLogisticsIdentity() {
		PatternStorageCoreBlockEntity core = core();
		UUID logistics = UUID.randomUUID();
		ClusterBinding proposed = new ClusterBinding(UUID.randomUUID(), 0,
			new ClusterAuthority(ClusterMemberType.PATTERN_CORE, core.memberId()),
			List.of(new LogisticsBinding(logistics, "network")));
		assertAll(
			() -> assertTrue(PatternStorageCoreBlockEntity.bindingAllowsQuery(
				ClusterBindingService.BindingAccess.READY, proposed, logistics)),
			() -> assertFalse(PatternStorageCoreBlockEntity.bindingAllowsQuery(
				ClusterBindingService.BindingAccess.CONFLICT, proposed, logistics)),
			() -> assertFalse(PatternStorageCoreBlockEntity.bindingAllowsQuery(
				ClusterBindingService.BindingAccess.AUTHORITY_OFFLINE, proposed, logistics)),
			() -> assertFalse(PatternStorageCoreBlockEntity.bindingAllowsQuery(
				ClusterBindingService.BindingAccess.READY, proposed, UUID.randomUUID())),
			() -> assertFalse(PatternStorageCoreBlockEntity.bindingAllowsQuery(
				ClusterBindingService.BindingAccess.READY, null, logistics)));
	}

	@Test
	void everyQueryOperationRequiresValidStructureAndReadyAuthority() {
		assertAll(
			() -> assertTrue(PatternStorageCoreBlockEntity.queryOperationAllowed(
				PatternLibraryScanner.StructureState.VALID,
				ClusterBindingService.BindingAccess.READY)),
			() -> assertFalse(PatternStorageCoreBlockEntity.queryOperationAllowed(
				PatternLibraryScanner.StructureState.UNFORMED,
				ClusterBindingService.BindingAccess.READY)),
			() -> assertFalse(PatternStorageCoreBlockEntity.queryOperationAllowed(
				PatternLibraryScanner.StructureState.PARTIAL,
				ClusterBindingService.BindingAccess.READY)),
			() -> assertFalse(PatternStorageCoreBlockEntity.queryOperationAllowed(
				PatternLibraryScanner.StructureState.VALID,
				ClusterBindingService.BindingAccess.AUTHORITY_OFFLINE)),
			() -> assertFalse(PatternStorageCoreBlockEntity.queryOperationAllowed(
				PatternLibraryScanner.StructureState.VALID,
				ClusterBindingService.BindingAccess.CONFLICT)));
	}

	@Test
	void replyDispatchAllowanceCannotBeResetByRepeatedCallsInOneTick() {
		ReplyDispatchAllowance allowance = new ReplyDispatchAllowance();

		assertEquals(2, allowance.available(40, 2, 99));
		assertEquals(2, allowance.available(40, 2, 99),
			"A requester with no matching reply must not consume another requester's allowance");
		allowance.consume(2);
		assertEquals(0, allowance.available(40, 2, 99));
		assertEquals(1, allowance.available(41, 1, 99));
		allowance.consume(1);
		assertEquals(0, allowance.available(41, 1, 1));
		assertEquals(0, allowance.available(42, 3, -1));
		assertEquals(3, allowance.available(42, 3, 3));
	}

	@Test
	void librarianProjectionAndBudgetUseCapturedTypeLevelAndPlainName() {
		PatternStorageCoreBlockEntity core = core();
		core.installLibrarianSnapshot(capturedLibrarian("minecraft:swamp", 4, "\"Ada\"", false));
		CompoundTag packet = new CompoundTag();
		core.write(packet, REGISTRIES, true);
		PatternCoreClientState state = PatternCoreClientState.load(packet.getCompound("ClientState"))
			.orElseThrow();

		assertAll(
			() -> assertTrue(state.renderLibrarian()),
			() -> assertEquals(ResourceLocation.withDefaultNamespace("swamp"), state.villagerType()),
			() -> assertEquals(4, state.librarianLevel()),
			() -> assertEquals("Ada", state.customName()),
			() -> assertEquals(4, core.librarianLevel()),
			() -> assertEquals(64, PatternStorageCoreBlockEntity.baseBudget(4)));
	}

	@Test
	void clientStateCodecCoversDefaultsAboveDefaultsMaximaAndEveryUpperBoundary() {
		PatternCoreClientState defaults = new PatternCoreClientState(false,
			ResourceLocation.withDefaultNamespace("plains"), 1, null,
			PatternLibraryScanner.StructureState.UNFORMED, false, 0, 0, 0, 0, 1);
		PatternCoreClientState aboveDefaults = new PatternCoreClientState(true,
			ResourceLocation.withDefaultNamespace("desert"), 3, "Curator",
			PatternLibraryScanner.StructureState.VALID, true, 65, 65, 65, 601, 66);
		PatternCoreClientState maxima = new PatternCoreClientState(true,
			ResourceLocation.withDefaultNamespace("swamp"), 5, "x".repeat(64),
			PatternLibraryScanner.StructureState.PARTIAL, false, 1024, 1024, 1024, 10000, 1024);

		assertAll(
			() -> assertEquals(defaults, PatternCoreClientState.load(defaults.save()).orElseThrow()),
			() -> assertEquals(aboveDefaults,
				PatternCoreClientState.load(aboveDefaults.save()).orElseThrow()),
			() -> assertEquals(maxima, PatternCoreClientState.load(maxima.save()).orElseThrow()));
		assertEquals(Set.of("RenderLibrarian", "VillagerType", "LibrarianLevel",
			"StructureState", "PendingSafeRelease", "LogicalMembers", "OrdinaryBookshelves",
			"ChiseledBookshelves", "SearchBudget", "QueueCount"), defaults.save().getAllKeys());
		assertEquals(Set.of("RenderLibrarian", "VillagerType", "LibrarianLevel", "CustomName",
			"StructureState", "PendingSafeRelease", "LogicalMembers", "OrdinaryBookshelves",
			"ChiseledBookshelves", "SearchBudget", "QueueCount"), maxima.save().getAllKeys());

		for (String key : List.of("LogicalMembers", "OrdinaryBookshelves", "ChiseledBookshelves",
			"QueueCount")) {
			CompoundTag invalid = maxima.save();
			invalid.putInt(key, 1025);
			assertTrue(PatternCoreClientState.load(invalid).isEmpty(), key);
		}
		CompoundTag budget = maxima.save();
		budget.putInt("SearchBudget", 10001);
		assertTrue(PatternCoreClientState.load(budget).isEmpty());
		for (int level : List.of(0, 6)) {
			CompoundTag invalid = maxima.save();
			invalid.putInt("LibrarianLevel", level);
			assertTrue(PatternCoreClientState.load(invalid).isEmpty());
		}
		CompoundTag name = maxima.save();
		name.putString("CustomName", "x".repeat(65));
		assertTrue(PatternCoreClientState.load(name).isEmpty());
		CompoundTag wrongType = defaults.save();
		wrongType.putString("LogicalMembers", "65");
		assertTrue(PatternCoreClientState.load(wrongType).isEmpty());
		CompoundTag extraKey = defaults.save();
		extraKey.putBoolean("Debug", true);
		assertTrue(PatternCoreClientState.load(extraKey).isEmpty());
		for (String key : List.of("RenderLibrarian", "PendingSafeRelease")) {
			CompoundTag nonCanonicalBoolean = defaults.save();
			nonCanonicalBoolean.putByte(key, (byte) 2);
			assertTrue(PatternCoreClientState.load(nonCanonicalBoolean).isEmpty(), key);
		}
		CompoundTag unknownVillagerType = defaults.save();
		unknownVillagerType.putString("VillagerType", "create_biotech:not_a_villager_type");
		assertTrue(PatternCoreClientState.load(unknownVillagerType).isEmpty());
	}

	@Test
	void fullMetadataAndNeoForgeStateRoundTripThroughRealBlockEntityApis() {
		PatternStorageCoreBlockEntity source = seededCore();
		registerTestAttachment();
		source.getPersistentData().putString("PatternReviewMarker", "persistent-data");
		source.setData(TEST_ATTACHMENT, "attachment-data");

		CompoundTag saved = source.saveWithFullMetadata(REGISTRIES);
		saved.putBoolean("keepPacked", true);

		assertTrue(saved.contains("id", Tag.TAG_STRING));
		assertTrue(saved.contains("x", Tag.TAG_INT));
		assertTrue(saved.contains("y", Tag.TAG_INT));
		assertTrue(saved.contains("z", Tag.TAG_INT));
		assertTrue(saved.contains("NeoForgeData", Tag.TAG_COMPOUND));
		assertTrue(saved.contains("neoforge:attachments", Tag.TAG_COMPOUND));
		assertEquals(Set.of("LibraryId", "BindingState", "BindingStateInvalid", "ServerState"),
			patternData(saved).getAllKeys());

		PatternStorageCoreBlockEntity restored = core();
		restored.loadWithComponents(saved, REGISTRIES);

		assertAll(
			() -> assertEquals(source.memberId(), restored.memberId()),
			() -> assertEquals(source.bindingState(), restored.bindingState()),
			() -> assertEquals(source.structureSnapshot(), restored.structureSnapshot()),
			() -> assertEquals(source.libraryIndex().save(REGISTRIES),
				restored.libraryIndex().save(REGISTRIES)),
			() -> assertEquals("persistent-data",
				restored.getPersistentData().getString("PatternReviewMarker")),
			() -> assertEquals("attachment-data", restored.getData(TEST_ATTACHMENT)));
	}

	@Test
	void validClientIdentityReplacementPreservesAllServerOnlyState() {
		PatternStorageCoreBlockEntity target = seededCore();
		UUID transmitted = UUID.randomUUID();
		PatternCoreClientState projected = new PatternCoreClientState(true,
			ResourceLocation.withDefaultNamespace("taiga"), 5, "Client name",
			PatternLibraryScanner.StructureState.PARTIAL, true, 65, 4, 3, 601, 5);
		CompoundTag packet = new CompoundTag();
		packet.putUUID("LibraryId", transmitted);
		packet.put("ClientState", projected.save());
		ClusterBinding binding = target.bindingState();
		ItemStack snapshot = target.snapshot();
		PatternStructureSnapshot structure = target.structureSnapshot();
		CompoundTag index = target.libraryIndex().save(REGISTRIES);

		target.read(packet, REGISTRIES, true);

		assertAll(
			() -> assertEquals(transmitted, target.memberId()),
			() -> assertEquals(projected, target.clientState()),
			() -> assertEquals(binding, target.bindingState()),
			() -> assertTrue(ItemStack.isSameItemSameComponents(snapshot, target.snapshot())),
			() -> assertSame(structure, target.structureSnapshot()),
			() -> assertEquals(index, target.libraryIndex().save(REGISTRIES)));
	}

	@Test
	void malformedClientIdentityRejectsPacketAtomically() {
		PatternStorageCoreBlockEntity target = seededCore();
		CompoundTag valid = new CompoundTag();
		valid.putUUID("LibraryId", UUID.randomUUID());
		valid.put("ClientState", new PatternCoreClientState(true,
			ResourceLocation.withDefaultNamespace("jungle"), 2, "Ignored",
			PatternLibraryScanner.StructureState.VALID, false, 3, 1, 1, 16, 2).save());
		valid.putString("LibraryId", "not-an-nbt-uuid");
		CompoundTag before = authoritativeSnapshot(target);
		UUID identity = target.memberId();
		PatternCoreClientState clientState = target.clientState();

		target.read(valid, REGISTRIES, true);

		assertAll(
			() -> assertEquals(identity, target.memberId()),
			() -> assertEquals(clientState, target.clientState()),
			() -> assertEquals(before, authoritativeSnapshot(target)));
	}

	@Test
	void serverStateRoundTripsAndCorruptChildrenAreIsolatedLosslessly() {
		PatternStorageCoreBlockEntity source = seededCore();
		CompoundTag saved = new CompoundTag();
		source.write(saved, REGISTRIES, false);
		assertEquals(Set.of("PatternLibrary"), saved.getAllKeys());
		assertEquals(Set.of("LibraryId", "BindingState", "BindingStateInvalid", "ServerState"),
			patternData(saved).getAllKeys());
		assertEquals(Set.of("LibrarianSnapshotBox", "PendingSafeRelease", "StructureState",
			"StructureSnapshot", "PageTopologies", "PatternIndex"),
			serverState(saved).getAllKeys());
		assertFalse(saved.contains("ClientState"));

		PatternStorageCoreBlockEntity restored = core();
		restored.read(saved, REGISTRIES, false);
		CompoundTag roundTripped = new CompoundTag();
		restored.write(roundTripped, REGISTRIES, false);
		assertEquals(saved, roundTripped);

		PatternStructureSnapshot retained = restored.structureSnapshot();
		CompoundTag corruptStructure = saved.copy();
		serverState(corruptStructure).put("StructureSnapshot", new CompoundTag());
		restored.read(corruptStructure, REGISTRIES, false);
		assertEquals(retained, restored.structureSnapshot());

		CompoundTag corruptIndexChild = saved.copy();
		serverState(corruptIndexChild).getCompound("PatternIndex")
			.getList("Queries", Tag.TAG_COMPOUND).getCompound(0).remove("QueryId");
		PatternStorageCoreBlockEntity isolated = core();
		isolated.read(corruptIndexChild, REGISTRIES, false);
		assertAll(
			() -> assertEquals(source.bindingState(), isolated.bindingState()),
			() -> assertEquals(source.structureSnapshot(), isolated.structureSnapshot()),
			() -> assertEquals(0, isolated.libraryIndex().activeQueryCount()),
			() -> assertEquals(1, isolated.libraryIndex().readyReplyCount()));

		CompoundTag corruptBox = saved.copy();
		CompoundTag raw = new CompoundTag();
		raw.putString("RecoveryMarker", "retain-this-exact-tag");
		serverState(corruptBox).put("LibrarianSnapshotBox", raw);
		PatternStorageCoreBlockEntity recovery = core();
		recovery.read(corruptBox, REGISTRIES, false);
		CompoundTag recoverySaved = new CompoundTag();
		recovery.write(recoverySaved, REGISTRIES, false);
		assertTrue(recovery.pendingSafeRelease());
		assertEquals(raw, serverState(recoverySaved)
			.getCompound("LibrarianSnapshotBox"));

		CompoundTag unknownType = saved.copy();
		CompoundTag unknownRaw = (CompoundTag) capturedLibrarian("evil:missing", 4,
			"\"Unknown\"", false).save(REGISTRIES);
		serverState(unknownType).put("LibrarianSnapshotBox", unknownRaw);
		PatternStorageCoreBlockEntity unknownRecovery = core();
		unknownRecovery.read(unknownType, REGISTRIES, false);
		CompoundTag unknownResaved = new CompoundTag();
		unknownRecovery.write(unknownResaved, REGISTRIES, false);
		assertTrue(unknownRecovery.pendingSafeRelease());
		assertEquals(unknownRaw, serverState(unknownResaved)
			.getCompound("LibrarianSnapshotBox"));
	}

	@Test
	void invalidServerStateEnvelopeIsRejectedWithoutPartialMutation() {
		PatternStorageCoreBlockEntity target = seededCore();
		CompoundTag before = authoritativeSnapshot(target);
		CompoundTag malformed = before.copy();
		serverState(malformed).putString("PendingSafeRelease", "wrong-type");
		serverState(malformed).putString("StructureState", "VALID");

		target.read(malformed, REGISTRIES, false);

		assertEquals(before, authoritativeSnapshot(target));
	}

	@Test
	void clientProjectionRecursivelyExcludesSensitiveServerState() {
		String rawJson = "unique-sensitive-pattern-json";
		PatternStorageCoreBlockEntity core = core();
		core.installLibrarianSnapshot(capturedLibrarian("minecraft:desert", 3, "\"Archivist\"", true));
		core.commitClusterBinding(binding(core, UUID.randomUUID(), 7));
		PatternPageKey key = new PatternPageKey(SHELF_ADDRESS, 0, 0);
		core.libraryIndex().rebuildPageOrder(List.of(key));
		core.libraryIndex().tickFingerprintChecks(ignored -> rawJson,
			new PatternJsonParser(REGISTRIES), 1);
		core.libraryIndex().enqueue(query(Items.IRON_INGOT));
		core.libraryIndex().tickQueries(1);
		CompoundTag client = new CompoundTag();
		core.write(client, REGISTRIES, true);

		Set<String> keys = new HashSet<>();
		List<String> strings = new ArrayList<>();
		collect(client, keys, strings);
		assertEquals(Set.of("LibraryId", "ClientState"), client.getAllKeys());
		for (String forbidden : List.of("BindingState", "BindingStateInvalid", "LibrarianSnapshotBox",
			"UUID", "Offers", "Recipes", "Brain", "Inventory", "ServerState", "PatternIndex",
			"PageOrder", "Fingerprint", "Cache", "Queries", "Replies", "Cursor", "NoAI",
			"CapturedEntity"))
			assertFalse(keys.contains(forbidden), forbidden);
		assertFalse(strings.stream().anyMatch(value -> value.contains(rawJson)));
	}

	private static PatternStorageCoreBlockEntity seededCore() {
		PatternStorageCoreBlockEntity core = core();
		core.installLibrarianSnapshot(capturedLibrarian("minecraft:swamp", 4, "\"Server name\"", true));
		core.commitClusterBinding(binding(core, UUID.randomUUID(), 3));
		core.applyStructureScan(new PatternLibraryScanner.ScanResult(
			PatternLibraryScanner.StructureState.VALID, coreOnlySnapshot()));
		core.libraryIndex().enqueue(query(Items.IRON_INGOT));
		core.libraryIndex().enqueue(query(Items.GOLD_INGOT));
		core.libraryIndex().tickQueries(1);
		return core;
	}

	private static ReadyCore coreWithRetainedWork(PatternLibraryScanner.ScanResult valid,
		BlockPos shelf) {
		ReadyCore core = new ReadyCore();
		core.applyStructureScan(valid, Map.of(shelf, 0), 0);
		core.libraryIndex().enqueue(query(Items.IRON_INGOT));
		core.libraryIndex().tickQueries(1);
		core.libraryIndex().enqueue(query(Items.GOLD_INGOT));
		return core;
	}

	private static PatternLibraryScanner.View scannerView(
		Map<BlockPos, PatternLibraryScanner.MemberKind> members) {
		return new PatternLibraryScanner.View() {
			@Override public boolean isLoaded(BlockPos pos) { return true; }
			@Override public boolean sameSpace(BlockPos first, BlockPos second) { return true; }
			@Override public PatternLibraryScanner.MemberKind memberAt(BlockPos pos) {
				return members.getOrDefault(pos, PatternLibraryScanner.MemberKind.NONE);
			}
		};
	}

	private static CompoundTag authoritativeSnapshot(PatternStorageCoreBlockEntity core) {
		CompoundTag saved = new CompoundTag();
		core.write(saved, REGISTRIES, false);
		return saved;
	}

	private static CompoundTag patternData(CompoundTag root) {
		return root.getCompound("PatternLibrary");
	}

	private static CompoundTag serverState(CompoundTag root) {
		return patternData(root).getCompound("ServerState");
	}

	private static ListTag topologyEntries(int count, int mask) {
		ListTag entries = new ListTag();
		for (int ordinal = 0; ordinal < count; ordinal++) {
			CompoundTag entry = new CompoundTag();
			entry.putLong("Pos", new BlockPos(ordinal + 1, ordinal / 1024, 0).asLong());
			entry.putInt("WritableSlots", mask);
			entries.add(entry);
		}
		return entries;
	}

	private static boolean topologyRefreshPending(PatternStorageCoreBlockEntity core) {
		try {
			Field field = PatternStorageCoreBlockEntity.class
				.getDeclaredField("topologyRefreshPending");
			field.setAccessible(true);
			return field.getBoolean(core);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static void registerTestAttachment() {
		if (testAttachmentRegistered)
			return;
		try {
			Class<?> registryClass = Class.forName("net.minecraft.core.Registry");
			Object registry = Class.forName("net.neoforged.neoforge.registries.NeoForgeRegistries")
				.getField("ATTACHMENT_TYPES").get(null);
			registryClass.getMethod("register", registryClass, ResourceLocation.class, Object.class)
				.invoke(null, registry, TEST_ATTACHMENT_ID, TEST_ATTACHMENT);
			testAttachmentRegistered = true;
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	@SuppressWarnings("unchecked")
	private static AttachmentType<String> createTestAttachment() {
		try {
			Object builder = AttachmentType.class.getMethod("builder", Supplier.class)
				.invoke(null, (Supplier<String>) () -> "");
			builder.getClass().getMethod("serialize", IAttachmentSerializer.class)
				.invoke(builder, TEST_ATTACHMENT_SERIALIZER);
			return (AttachmentType<String>) builder.getClass().getMethod("build").invoke(builder);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static PatternQuery query(net.minecraft.world.item.Item item) {
		return new PatternQuery(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
			new StackKey(new ItemStack(item)), Long.MAX_VALUE, 0);
	}

	private static ClusterBinding binding(PatternStorageCoreBlockEntity core, UUID clusterId,
		long revision) {
		return new ClusterBinding(clusterId, revision,
			new ClusterAuthority(ClusterMemberType.PATTERN_CORE, core.memberId()),
			List.of(new LogisticsBinding(UUID.randomUUID(), "network")));
	}

	private static PatternStructureSnapshot coreOnlySnapshot() {
		return new PatternStructureSnapshot(PatternLibraryScanner.StructureState.VALID,
			List.of(BlockPos.ZERO), List.of(), List.of(), BlockPos.ZERO, BlockPos.ZERO, 1);
	}

	private static PatternStructureSnapshot snapshotWithChiseledShelf() {
		BlockPos shelf = new BlockPos(1, 0, 0);
		return new PatternStructureSnapshot(PatternLibraryScanner.StructureState.VALID,
			List.of(BlockPos.ZERO, shelf), List.of(), List.of(shelf), BlockPos.ZERO, shelf, 1);
	}

	private static ItemStack writableBook(String page) {
		ItemStack stack = new ItemStack(Items.WRITABLE_BOOK);
		stack.set(DataComponents.WRITABLE_BOOK_CONTENT,
			new WritableBookContent(List.of(Filterable.passThrough(page))));
		return stack;
	}

	private static ItemStack capturedLibrarian(String type, int level, String customName,
		boolean sensitive) {
		ItemStack stack = new ItemStack(Items.PAPER);
		CompoundTag captured = new CompoundTag();
		captured.putString("id", "minecraft:villager");
		CompoundTag data = new CompoundTag();
		data.putString("type", type);
		data.putString("profession", "minecraft:librarian");
		data.putInt("level", level);
		captured.put("VillagerData", data);
		captured.putString("CustomName", customName);
		if (sensitive) {
			captured.putUUID("UUID", UUID.randomUUID());
			CompoundTag offers = new CompoundTag();
			offers.put("Recipes", new ListTag());
			captured.put("Offers", offers);
			captured.put("Brain", new CompoundTag());
			captured.put("Inventory", new ListTag());
			captured.putBoolean("NoAI", true);
		}
		CompoundTag itemData = new CompoundTag();
		itemData.put("CapturedEntity", captured);
		CBItemData.set(stack, itemData);
		return stack;
	}

	private static void collect(Tag tag, Set<String> keys, List<String> strings) {
		if (tag instanceof CompoundTag compound) {
			for (String key : compound.getAllKeys()) {
				keys.add(key);
				collect(compound.get(key), keys, strings);
			}
		} else if (tag instanceof ListTag list) {
			list.forEach(child -> collect(child, keys, strings));
		} else if (tag instanceof StringTag string) {
			strings.add(string.getAsString());
		}
	}

	private static PatternStorageCoreBlockEntity core() {
		bootstrap();
		return new PatternStorageCoreBlockEntity(BlockEntityType.FURNACE, BlockPos.ZERO,
			Blocks.FURNACE.defaultBlockState());
	}

	private static void bootstrap() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		markMinecraftBootstrapped();
	}

	private static <T> T allocate(Class<T> type) {
		try {
			Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static void markMinecraftBootstrapped() {
		try {
			Field bootstrapped = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			bootstrapped.setAccessible(true);
			bootstrapped.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static void installEmptyLoadingModList() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) != null)
				return;
			Object empty = type.getMethod("of", List.class, List.class, List.class, List.class,
				java.util.Map.class).invoke(null, List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
			if (empty == null)
				throw new IllegalStateException("LoadingModList.of returned null");
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static final class SyncTrackingCore extends PatternStorageCoreBlockEntity {
		private int syncs;

		private SyncTrackingCore() {
			super(BlockEntityType.FURNACE, BlockPos.ZERO, Blocks.FURNACE.defaultBlockState());
			bootstrap();
		}

		@Override
		public void sendData() {
			syncs++;
		}
	}

	private static final class ReadyCore extends PatternStorageCoreBlockEntity {
		private boolean shelfEntitiesReadable = true;

		private ReadyCore() {
			super(BlockEntityType.FURNACE, BlockPos.ZERO, Blocks.FURNACE.defaultBlockState());
			bootstrap();
		}

		@Override
		boolean queryAccessReady() {
			return structureState() == PatternLibraryScanner.StructureState.VALID
				&& !topologyRefreshPending(this);
		}

		@Override
		boolean shelfEntityReadable(BlockPos ignored) {
			return shelfEntitiesReadable;
		}

		@Override
		SpaceAddress currentLibrarySpace() {
			return new SpaceAddress(Level.OVERWORLD, SPACE, BlockPos.ZERO);
		}
	}

	private static final class ServerCore extends PatternStorageCoreBlockEntity {
		private final MinecraftServer server;

		private ServerCore(MinecraftServer server) {
			super(BlockEntityType.FURNACE, BlockPos.ZERO, Blocks.FURNACE.defaultBlockState());
			this.server = server;
			bootstrap();
		}

		@Override
		MinecraftServer server() {
			return server;
		}
	}
}
