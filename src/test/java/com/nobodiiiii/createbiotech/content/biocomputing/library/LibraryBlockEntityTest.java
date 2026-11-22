package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryStructureScanner;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeShelfSource;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeMaintenanceArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipePageIndex;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipePageIndex.PageReader;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeLibrarySummary;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.ProductionLibrarySession;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.ProductionLibraryIndexArchive;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeLibraryClientView;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryStructureScanner;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryCoreItemData;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryIdentityClaims;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryMovementLeases;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.PatternQuery;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.ProductionLibraryRouting;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternPageKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternJsonParser;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryResident;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingPreparation;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingService;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberIndex;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.factorycluster.LogisticsBinding;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.PatternPriorityAuthority;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.LibraryDirectory;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.LibraryRegistration;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.LibrarySearchCoordinator;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.ProductionRouteTable;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteCandidate;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteSelectionRequest;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteSelectionScope;
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

class LibraryBlockEntityTest {
	private static final FactoryRuntimeConfig RUNTIME_CONFIG = new FactoryRuntimeConfig(1, 500, 4,
		32, 4, 4, 32, 64, 20, 10, 100, 3, 32, 16);
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

	@BeforeAll
	static void bootstrapMinecraft() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		markMinecraftBootstrapped();
	}

	@Test
	void sixWritableBooksExposeSixHundredDistinctLogicalPagesAndStableIdentity() {
		List<ItemStack> slots = new ArrayList<>();
		for (int slot = 0; slot < 6; slot++)
			slots.add(writableBook("page-" + slot));

		List<PatternPageKey> keys = LibraryBlockEntity.pageKeys(SHELF_ADDRESS, slots);

		assertEquals(600, keys.size());
		assertEquals(600, new HashSet<>(keys).size());
		assertEquals("page-0", LibraryBlockEntity.readPage(slots,
			new PatternPageKey(new RecipeSourceLocation(SHELF_ADDRESS.dimension(), SHELF_ADDRESS.subLevelId(), SHELF_ADDRESS.localPos()), 0, 0)));
		assertEquals("", LibraryBlockEntity.readPage(slots,
			new PatternPageKey(new RecipeSourceLocation(SHELF_ADDRESS.dimension(), SHELF_ADDRESS.subLevelId(), SHELF_ADDRESS.localPos()), 0, 99)));
		PatternPageKey reference = new PatternPageKey(new RecipeSourceLocation(SHELF_ADDRESS.dimension(), SHELF_ADDRESS.subLevelId(), SHELF_ADDRESS.localPos()), 0, 0);
		assertAll(
			() -> assertNotEquals(reference, new PatternPageKey(new RecipeSourceLocation(SHELF_ADDRESS.dimension(), SHELF_ADDRESS.subLevelId(), SHELF_ADDRESS.localPos()), 1, 0)),
			() -> assertNotEquals(reference, new PatternPageKey(new RecipeSourceLocation(SHELF_ADDRESS.dimension(), SHELF_ADDRESS.subLevelId(), SHELF_ADDRESS.localPos()), 0, 1)),
			() -> assertNotEquals(reference, new PatternPageKey(new RecipeSourceLocation(Level.OVERWORLD,
				SPACE, new BlockPos(2, 0, 0)), 0, 0)));
	}

	@Test
	@SuppressWarnings("unchecked")
	void legacyShelfReadsUseDetachedItemCopiesRatherThanContainerStacks() throws Exception {
		ItemStack source = new ItemStack(Items.WRITABLE_BOOK);
		var shelf = new net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity(
			BlockPos.ZERO, Blocks.CHISELED_BOOKSHELF.defaultBlockState()) {
			@Override public ItemStack getItem(int slot) { return slot == 2 ? source : ItemStack.EMPTY; }
		};
		var method = LibraryBlockEntity.class.getDeclaredMethod("shelfItems", shelf.getClass().getSuperclass());
		method.setAccessible(true);
		List<ItemStack> captured = (List<ItemStack>) method.invoke(null, shelf);
		assertEquals(6, captured.size());
		assertNotSame(source, captured.get(2));
		captured.get(2).setCount(3);
		assertEquals(1, source.getCount());
		assertEquals(1, ((List<ItemStack>) method.invoke(null, shelf)).get(2).getCount());
	}

	@Test
	void publishedCatalogRoundTripsCoordinatorQueryThroughWorker()
		throws Exception {
		core(); // install the same minimal Minecraft registry fixture used by BE tests
		UUID cluster = UUID.randomUUID(), epoch = UUID.randomUUID(), library = UUID.randomUUID();
		RecipePageIndex index = new RecipePageIndex();
		LibraryDirectory directory = new LibraryDirectory();
		directory.publish(new LibraryRegistration(library, cluster,
			new RecipeSourceLocation(Level.OVERWORLD, null, BlockPos.ZERO), 2, CommunicationFrequency.of("17"), 8, 0,
			LibraryRegistration.LoadState.LOADED, LibraryRegistration.Reachability.REACHABLE));
		LibrarySearchCoordinator coordinator = new LibrarySearchCoordinator(directory);
		LibrarySearchCoordinator.Update opened = coordinator.begin(new LibrarySearchCoordinator.SearchRequest(
			UUID.randomUUID(), UUID.randomUUID(), cluster, epoch, UUID.randomUUID(), CommunicationFrequency.of("17"),
			new StackKey(new ItemStack(Items.STONE)), 9, 0, UUID.randomUUID(), 8, 0,
			PatternPriorityAuthority.empty()));
		try (FactoryRuntimeService runtime = new FactoryRuntimeService(RUNTIME_CONFIG)) {
			runtime.start(); runtime.restoreSnapshot(ClusterEngineState.empty(cluster, epoch));
			var pageBefore = index.save(REGISTRIES);
			runtime.publishProductionLibraryAccess(cluster, Set.of(UUID.randomUUID()), 2,
				CommunicationFrequency.of("17"), 8, new SpaceAddress(Level.OVERWORLD, null, BlockPos.ZERO),
				LibraryRegistration.Reachability.REACHABLE, ProductionLibrarySession.catalog(library, 2, index, true));
			assertEquals(1, opened.queries().size());
			assertTrue(runtime.enqueueLibraryQuery(cluster, epoch, opened.queries().getFirst()));
			List<LibrarySearchCoordinator.Update> updates = List.of();
			long deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
			while (updates.isEmpty() && System.nanoTime() < deadline) {
				updates = runtime.drainLibraryReplies(cluster, 8).stream()
					.map(reply -> coordinator.accept(reply, 0)).toList();
				if (updates.isEmpty()) Thread.sleep(5);
			}
			assertEquals(LibrarySearchCoordinator.Status.NOT_FOUND, updates.getLast().status());
			assertEquals(pageBefore, index.save(REGISTRIES), "Worker operates on immutable catalog, not mutable page index");
			assertTrue(updates.getLast().queries().isEmpty());
		}
	}

	@Test
	void runtimeOnlyAcceptsSourcesForAScopeAlreadyOpenedByTheCoordinator() {
		core();
		UUID cluster = UUID.randomUUID(), epoch = UUID.randomUUID();
		RouteSelectionScope scope = new RouteSelectionScope(epoch, UUID.randomUUID(),
			new StackKey(new ItemStack(Items.STONE)), 6, 0, UUID.randomUUID());
		ProductionRouteTable.ProviderSource source = new ProductionRouteTable.ProviderSource(scope,
			UUID.randomUUID(), UUID.randomUUID(), "pattern", RouteCandidate.ProviderKind.PATTERN,
			7, RouteCandidate.Availability.AVAILABLE, RouteCandidate.Blocker.NONE);
		LibrarySearchCoordinator.Update ready = new LibrarySearchCoordinator.Update(
			LibrarySearchCoordinator.Status.SELECTING_ROUTE, List.of(), List.of(), Optional.empty(),
			Optional.empty(), Set.of(), false, List.of(source));
		try (FactoryRuntimeService runtime = new FactoryRuntimeService(RUNTIME_CONFIG)) {
			runtime.start(); runtime.restoreSnapshot(ClusterEngineState.empty(cluster, epoch));
			assertFalse(runtime.replaceRouteSources(cluster, scope, RouteCandidate.ProviderKind.PATTERN, ready.providerSources()));
			assertTrue(runtime.routeSourceSnapshot(cluster, scope).isEmpty(),
				"a collector adapter must not implicitly open its own selection lifecycle");
			assertTrue(runtime.beginRouteSelection(cluster, RouteSelectionRequest.patternOnly(scope)));
			assertTrue(runtime.replaceRouteSources(cluster, scope, RouteCandidate.ProviderKind.PATTERN, ready.providerSources()));
			assertTrue(runtime.completeRouteCollector(cluster, scope, RouteCandidate.ProviderKind.PATTERN));
			assertTrue(runtime.trySelectRoute(cluster, scope).isEmpty());
			for (RouteCandidate.ProviderKind kind : List.of(RouteCandidate.ProviderKind.AGENT,
				RouteCandidate.ProviderKind.FACTORY_GAUGE,
				RouteCandidate.ProviderKind.ORDINARY_PROVIDER)) {
				assertTrue(runtime.replaceRouteSources(cluster, scope, kind, List.of()));
				assertTrue(runtime.completeRouteCollector(cluster, scope, kind));
			}
			assertEquals("pattern", runtime.trySelectRoute(cluster, scope).orElseThrow()
				.winner().orElseThrow().providerKey());
		}
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

		assertTrue(LibraryBlockEntity.pageKeys(SHELF_ADDRESS, slots).isEmpty());
		assertEquals(10, LibraryBlockEntity.efficiencyBonus(slots, efficiency));
	}

	@Test
	void insertingAndReplacingWritableBookUpdatesKeysThenUsesFingerprintDetection() {
		RecipePageIndex index = new RecipePageIndex();
		List<ItemStack> empty = List.of(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY,
			ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
		assertFalse(LibraryBlockEntity.synchronizePageOrder(index,
			LibraryBlockEntity.pageKeys(SHELF_ADDRESS, empty)));

		List<ItemStack> first = List.of(writableBook("first"), ItemStack.EMPTY, ItemStack.EMPTY,
			ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
		assertTrue(LibraryBlockEntity.synchronizePageOrder(index,
			LibraryBlockEntity.pageKeys(SHELF_ADDRESS, first)));
		assertEquals(100, index.pageOrder().size());
		PatternJsonParser parser = new PatternJsonParser(REGISTRIES);
		index.tickFingerprintChecks(key -> LibraryBlockEntity.readPage(first, key), parser, 100);
		long indexedGeneration = index.generation();

		List<ItemStack> replacement = List.of(writableBook("second"), ItemStack.EMPTY, ItemStack.EMPTY,
			ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
		assertFalse(LibraryBlockEntity.synchronizePageOrder(index,
			LibraryBlockEntity.pageKeys(SHELF_ADDRESS, replacement)));
		assertEquals(indexedGeneration, index.generation());
		index.beginNextFingerprintSweep();
		index.tickFingerprintChecks(key -> LibraryBlockEntity.readPage(replacement, key), parser, 1);
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

		assertEquals(LibraryBlockEntity.pageTopology(first),
			LibraryBlockEntity.pageTopology(edited));
		assertNotEquals(LibraryBlockEntity.pageTopology(first),
			LibraryBlockEntity.pageTopology(moved));
	}

	@Test
	void physicalViewRetainsImmutableHistoryWithoutAuthorizingAPartialObservation() {
		ReadyCore core = new ReadyCore();
		BlockPos shelf = BlockPos.ZERO.east();
		var snapshot = new LibraryStructureScanner.Snapshot(List.of(BlockPos.ZERO, shelf), List.of(shelf), List.of(), BlockPos.ZERO, shelf);
		var valid = new LibraryStructureScanner.ScanResult(LibraryStructureScanner.State.VALID, snapshot);
		core.applyStructureScan(valid, Map.of(), 0);
		LibraryStructureScanner.State observed = core.structureState();
		LibraryStructureScanner.Snapshot retained = core.structureSnapshot();
		assertEquals(LibraryStructureScanner.State.VALID, observed);
		assertSame(snapshot, retained);
		assertThrows(UnsupportedOperationException.class, () -> retained.members().clear());
		var before = diskIndex(core);
		core.applyStructureScan(new LibraryStructureScanner.ScanResult(LibraryStructureScanner.State.PARTIAL,
			new LibraryStructureScanner.Snapshot(List.of(BlockPos.ZERO), List.of(), List.of(), BlockPos.ZERO, BlockPos.ZERO)));
		assertEquals(LibraryStructureScanner.State.PARTIAL, core.structureState());
		assertSame(retained, core.structureSnapshot());
		assertEquals(2, core.queueCount(), "Retained recipe queues are diagnostics, not access permission");
		assertFalse(core.indexAccessReady());
		assertFalse(core.serviceIndexIfMembersLoaded(ignored -> true, ignored -> { throw new AssertionError("PARTIAL read"); }, new PatternJsonParser(REGISTRIES), 1));
		assertEquals(before, diskIndex(core));
		core.applyStructureScan(new LibraryStructureScanner.ScanResult(LibraryStructureScanner.State.VALID,
			new LibraryStructureScanner.Snapshot(snapshot.members(), snapshot.ordinaryShelves(), snapshot.mediaShelves(), snapshot.min(), snapshot.max())), Map.of(), 0);
		assertTrue(core.indexAccessReady());
		assertSame(retained, core.structureSnapshot(), "Equal reconstruction retains the actual immutable layout");
		assertEquals(before, diskIndex(core));
	}

	@Test
	void ordinaryShelfChangesDoNotRebuildUnchangedPageTopology() {
		bootstrap();
		LibraryBlockEntity core = new ReadyCore();
		BlockPos chiseled = new BlockPos(1, 0, 0);
		Map<BlockPos, Integer> topology = Map.of(chiseled, 0);
		LibraryStructureScanner.Snapshot initial = new LibraryStructureScanner.Snapshot(
			List.of(BlockPos.ZERO, chiseled), List.of(), List.of(chiseled),
			BlockPos.ZERO, chiseled);
		core.applyStructureScan(new LibraryStructureScanner.ScanResult(
			LibraryStructureScanner.State.VALID, initial), topology, 0);
		long indexedGeneration = core.libraryIndex().generation();
		BlockPos ordinary = new BlockPos(0, 0, 1);
		LibraryStructureScanner.Snapshot queueOnlyChange = new LibraryStructureScanner.Snapshot(
			List.of(BlockPos.ZERO, chiseled, ordinary), List.of(ordinary), List.of(chiseled),
			BlockPos.ZERO, new BlockPos(1, 0, 1));

		core.applyStructureScan(new LibraryStructureScanner.ScanResult(
			LibraryStructureScanner.State.VALID, queueOnlyChange), topology, 0);

		assertEquals(indexedGeneration, core.libraryIndex().generation());
		assertTrue(core.libraryIndex().pageOrder().isEmpty());
		assertEquals(2, core.queueCount());
	}

	@Test
	void coreExposesBoundedPanelSummaryFromRuntimeCounters() {
		LibraryBlockEntity core = new ReadyCore();
		LibraryStructureScanner.Snapshot snapshot = snapshotWithChiseledShelf();
		core.applyStructureScan(new LibraryStructureScanner.ScanResult(
			LibraryStructureScanner.State.VALID, snapshot));
		PatternPageKey key = new PatternPageKey(new RecipeSourceLocation(SHELF_ADDRESS.dimension(), SHELF_ADDRESS.subLevelId(), SHELF_ADDRESS.localPos()), 0, 0);
		core.libraryIndex().rebuildPageOrder(List.of(key));
		core.libraryIndex().tickFingerprintChecks(ignored -> "{",
			new PatternJsonParser(REGISTRIES), 1);

		RecipeLibrarySummary summary = core.summary();

		assertAll(
			() -> assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryStructureScanner.State.VALID, summary.reason()),
			() -> assertEquals(600, summary.capacity()),
			() -> assertEquals(1, summary.pages()),
			() -> assertEquals(1, summary.indexedPages()),
			() -> assertEquals(1, summary.errorPages()),
			() -> assertEquals(1, summary.errors().size()));
	}

	@Test
	void unloadedKnownMemberStopsAllIndexAdvancementAndRetainsPriorState() {
		LibraryBlockEntity core = new ReadyCore();
		LibraryStructureScanner.Snapshot snapshot = snapshotWithChiseledShelf();
		core.applyStructureScan(new LibraryStructureScanner.ScanResult(
			LibraryStructureScanner.State.VALID, snapshot));
		PatternPageKey key = new PatternPageKey(new RecipeSourceLocation(SHELF_ADDRESS.dimension(), SHELF_ADDRESS.subLevelId(), SHELF_ADDRESS.localPos()), 0, 0);
		core.libraryIndex().rebuildPageOrder(List.of(key));
		CompoundTag before = diskIndex(core);

		boolean advanced = core.serviceIndexIfMembersLoaded(pos -> !pos.equals(new BlockPos(1, 0, 0)),
			ignored -> "changed", new PatternJsonParser(REGISTRIES), 100);

		assertFalse(advanced);
		assertEquals(before, diskIndex(core));
		assertSame(snapshot, core.structureSnapshot());
		assertEquals(LibraryStructureScanner.State.PARTIAL, core.structureState());
	}

	@Test
	void unboundLocalLibraryIndexesPagesWithoutAdmittingOrAdvancingProductionQueries() {
		MinecraftServer server = allocate(DedicatedServer.class);
		ServerCore core = new ServerCore(server);
		assertTrue(core.claimItemConfiguration(server, LibraryCoreItemData.configured(
			CommunicationFrequency.of("local-index"), 0, core.memberId(), 0)));
		try {
			core.applyStructureScan(new LibraryStructureScanner.ScanResult(
				LibraryStructureScanner.State.VALID, coreOnlySnapshot()));
			core.libraryIndex().rebuildPageOrder(List.of(new PatternPageKey(new RecipeSourceLocation(SHELF_ADDRESS.dimension(), SHELF_ADDRESS.subLevelId(), SHELF_ADDRESS.localPos()), 0, 0)));
			
			assertNull(frozenBinding(core));
			assertTrue(core.indexAccessReady());
			assertTrue(core.serviceIndexIfMembersLoaded(ignored -> true, ignored ->
				"{\"version\":2,\"type\":\"standard\",\"inputs\":[],\"outputs\":[{\"item\":\"minecraft:iron_ingot\",\"count\":1}],\"address\":\"local-index\"}",
				new PatternJsonParser(REGISTRIES), 8));
			assertEquals(1, core.recipeSnapshot().records().size());
			assertFalse(core.recipeSnapshot().reindexing());
			assertFalse(diskIndex(core).contains("RetiredProductionSearch"));
			assertFalse(diskIndex(core).contains("Queries"));
			assertFalse(com.nobodiiiii.createbiotech.content.factorycluster.ClusterMember.class.isInstance(core));
		} finally { core.releaseLibraryIdentityClaim(); }
	}

	@Test
	void localIndexStillRequiresTheCurrentLibraryIdentityClaim() {
		MinecraftServer server = allocate(DedicatedServer.class);
		ServerCore core = new ServerCore(server);
		core.applyStructureScan(new LibraryStructureScanner.ScanResult(
			LibraryStructureScanner.State.VALID, coreOnlySnapshot()));
		core.libraryIndex().rebuildPageOrder(List.of(new PatternPageKey(new RecipeSourceLocation(SHELF_ADDRESS.dimension(), SHELF_ADDRESS.subLevelId(), SHELF_ADDRESS.localPos()), 0, 0)));
		AtomicInteger reads = new AtomicInteger();
		PageReader pages = ignored -> { reads.incrementAndGet(); return ""; };
		assertFalse(core.serviceIndexIfMembersLoaded(ignored -> true, pages, new PatternJsonParser(REGISTRIES), 8));
		assertEquals(0, reads.get());
		assertTrue(core.claimItemConfiguration(server, LibraryCoreItemData.configured(
			CommunicationFrequency.of("local-identity"), 0, core.memberId(), 0)));
		try {
			assertTrue(core.serviceIndexIfMembersLoaded(ignored -> true, pages, new PatternJsonParser(REGISTRIES), 8));
		} finally { core.releaseLibraryIdentityClaim(); }
		int beforeReads = reads.get();
		CompoundTag before = diskIndex(core);
		assertFalse(core.serviceIndexIfMembersLoaded(ignored -> true, pages, new PatternJsonParser(REGISTRIES), 8));
		assertEquals(beforeReads, reads.get());
		assertEquals(before, diskIndex(core));
	}

	@Test
	void missingShelfEntityBetweenScheduledScansStopsServiceBeforeReadingOrRestarting() {
		ReadyCore core = new ReadyCore();
		seedHistory(core, true);
		LibraryStructureScanner.Snapshot snapshot = snapshotWithChiseledShelf();
		core.applyStructureScan(new LibraryStructureScanner.ScanResult(
			LibraryStructureScanner.State.VALID, snapshot),
			Map.of(new BlockPos(1, 0, 0), 0), 0);
		PatternPageKey key = new PatternPageKey(new RecipeSourceLocation(SHELF_ADDRESS.dimension(), SHELF_ADDRESS.subLevelId(), SHELF_ADDRESS.localPos()), 0, 0);
		core.libraryIndex().rebuildPageOrder(List.of(key));
		core.libraryIndex().tickFingerprintChecks(ignored -> "{",
			new PatternJsonParser(REGISTRIES), 1);
		CompoundTag retained = diskIndex(core);
		AtomicInteger reads = new AtomicInteger();
		core.shelfEntitiesReadable = false;

		boolean advanced = core.serviceIndexIfMembersLoaded(ignored -> true, ignored -> {
			reads.incrementAndGet();
			return "";
		}, new PatternJsonParser(REGISTRIES), 1);

		assertFalse(advanced);
		assertEquals(0, reads.get(), "Wrong/missing shelf BE must gate before PageReader");
		assertEquals(LibraryStructureScanner.State.PARTIAL, core.structureState());
		assertEquals(retained, diskIndex(core),
			"Incomplete inspection must retain cache, active queries, ready replies, and cursors");
	}

	@Test
	void gogglesShowIndexWorkNotOldServerRequests() {
		LibraryBlockEntity core = new ReadyCore();
		seedHistory(core, true);
		core.libraryIndex().rebuildPageOrder(List.of(new PatternPageKey(new RecipeSourceLocation(SHELF_ADDRESS.dimension(), SHELF_ADDRESS.subLevelId(), SHELF_ADDRESS.localPos()), 0, 0)));
		var packet = new CompoundTag(); core.write(packet, REGISTRIES, true);
		assertTrue(packet.getCompound("NormalGoggles").toString().contains("queued_pages"));
		assertFalse(packet.toString().contains("RetiredProductionSearch"));
		assertFalse(core.diagnosticIndexRows().toString().contains("activeQueries"));
	}

	@Test
	void partialScanSynchronizesStatusWithoutReplacingSnapshotOrIndex() {
		SyncTrackingCore core = new SyncTrackingCore();
		seedHistory(core, false);
		LibraryStructureScanner.Snapshot snapshot = coreOnlySnapshot();
		core.applyStructureScan(new LibraryStructureScanner.ScanResult(
			LibraryStructureScanner.State.VALID, snapshot));
		CompoundTag before = diskIndex(core);
		int priorSyncs = core.syncs;

		core.applyStructureScan(new LibraryStructureScanner.ScanResult(
			LibraryStructureScanner.State.PARTIAL, null));

		assertEquals(LibraryStructureScanner.State.PARTIAL, core.structureState());
		assertSame(snapshot, core.structureSnapshot());
		assertEquals(before, diskIndex(core));
		assertEquals(priorSyncs + 1, core.syncs);
		CompoundTag client = new CompoundTag();
		core.write(client, REGISTRIES, true);
		RecipeLibraryClientView projected = RecipeLibraryClientView.load(
			client.getCompound("ClientState")).orElseThrow();
		assertEquals(LibraryStructureScanner.State.PARTIAL, projected.structureState());
		assertEquals(1, projected.logicalMembers());
	}

	@Test
	void realScannerConflictThenSameValidStructureRetainsQueriesRepliesAndGeneration() {
		BlockPos shelf = new BlockPos(1, 0, 0);
		Map<BlockPos, LibraryStructureScanner.MemberKind> members = new java.util.LinkedHashMap<>();
		members.put(BlockPos.ZERO, LibraryStructureScanner.MemberKind.CORE_LOWER);
		members.put(shelf, LibraryStructureScanner.MemberKind.MEDIA_SHELF);
		LibraryStructureScanner.ScanResult valid = LibraryStructureScanner.scan(
			scannerView(members), BlockPos.ZERO, 64, 16);
		ReadyCore core = coreWithRetainedWork(valid, shelf);
		CompoundTag retained = diskIndex(core);
		members.put(new BlockPos(-1, 0, 0), LibraryStructureScanner.MemberKind.CORE_LOWER);
		LibraryStructureScanner.ScanResult conflict = LibraryStructureScanner.scan(
			scannerView(members), BlockPos.ZERO, 64, 16);

		core.applyStructureScan(conflict, Map.of(), 0);

		assertEquals(LibraryStructureScanner.State.CORE_CONFLICT,
			core.structureState());
		assertEquals(retained, diskIndex(core));
		members.remove(new BlockPos(-1, 0, 0));
		LibraryStructureScanner.ScanResult sameValid = LibraryStructureScanner.scan(
			scannerView(members), BlockPos.ZERO, 64, 16);
		core.applyStructureScan(sameValid, Map.of(shelf, 0), 0);
		assertEquals(LibraryStructureScanner.State.VALID, core.structureState());
		assertEquals(retained, diskIndex(core));
	}

	@Test
	void everyNonValidScannerStateFreezesRetainedIndexState() {
		BlockPos shelf = new BlockPos(1, 0, 0);
		LibraryStructureScanner.ScanResult valid = new LibraryStructureScanner.ScanResult(
			LibraryStructureScanner.State.VALID, snapshotWithChiseledShelf());
		for (LibraryStructureScanner.State state : LibraryStructureScanner.State.values()) {
			if (state == LibraryStructureScanner.State.VALID)
				continue;
			ReadyCore core = coreWithRetainedWork(valid, shelf);
			CompoundTag retained = diskIndex(core);

			core.applyStructureScan(new LibraryStructureScanner.ScanResult(state, null),
				Map.of(), 0);

			assertEquals(retained, diskIndex(core), state.name());
		}
	}

	@Test
	void persistedTopologyPreventsFirstEquivalentValidRefreshFromRestartingLoadedWork() {
		BlockPos shelf = new BlockPos(1, 0, 0);
		LibraryStructureScanner.ScanResult valid = new LibraryStructureScanner.ScanResult(
			LibraryStructureScanner.State.VALID, snapshotWithChiseledShelf());
		ReadyCore source = coreWithRetainedWork(valid, shelf);
		CompoundTag saved = source.saveWithFullMetadata(REGISTRIES);

		assertTrue(serverState(saved).contains("PageTopologies", Tag.TAG_LIST),
			"Writable-slot topology must be part of authoritative server persistence");
		LibraryBlockEntity restored = core();
		restored.loadWithComponents(saved, REGISTRIES);
		CompoundTag retained = diskIndex(restored);
		assertEquals(1, archivedCount(restored, "Queries"));
		assertEquals(1, archivedCount(restored, "Replies"));

		restored.applyStructureScan(valid, Map.of(shelf, 0), 0);

		assertEquals(retained, diskIndex(restored));
		assertEquals(1, archivedCount(restored, "Queries"));
		assertEquals(1, archivedCount(restored, "Replies"));
	}

	@Test
	void realPageChangeAdvancesIndexButNeverRewritesOrRestartsRetiredSearches() {
		BlockPos shelf = new BlockPos(1, 0, 0);
		var valid = new LibraryStructureScanner.ScanResult(
			LibraryStructureScanner.State.VALID, snapshotWithChiseledShelf());
		ReadyCore source = coreWithRetainedWork(valid, shelf);
		var saved = source.saveWithFullMetadata(REGISTRIES);
		var original = saved.copy();
		ReadyCore restored = new ReadyCore();
		restored.loadWithComponents(saved, REGISTRIES);
		assertEquals(1, archivedCount(restored, "Queries"));
		assertEquals(1, archivedCount(restored, "Replies"));
		long generation = restored.libraryIndex().generation();
		restored.applyStructureScan(valid, Map.of(shelf, 1), 0);
		assertEquals(generation + 1, restored.libraryIndex().generation());
		assertEquals(diskIndex(source).getCompound("RetiredProductionSearch"), diskIndex(restored).getCompound("RetiredProductionSearch"));
		assertTrue(restored.serviceIndexIfMembersLoaded(ignored -> true, key -> key.page() == 0
			? "{\"version\":2,\"type\":\"standard\",\"inputs\":[],\"outputs\":[{\"item\":\"minecraft:gold_ingot\",\"count\":1}],\"address\":\"restored\"}" : "",
			new PatternJsonParser(REGISTRIES), 100));
		assertEquals(1, restored.recipeSnapshot().records().size());
		assertEquals(Items.GOLD_INGOT, restored.recipeSnapshot().records().getFirst().mainOutput().stack().stack().getItem());
		assertEquals(diskIndex(source).getCompound("RetiredProductionSearch"), diskIndex(restored).getCompound("RetiredProductionSearch"));
		assertEquals(original, saved);
		assertEquals(original, source.saveWithFullMetadata(REGISTRIES), "Restored owners do not alias the source device");
	}

	@Test
	void priorNestedServerShapeRestoresBindingQueriesRepliesAndCursorBeforeMatchingReconstruction() {
		BlockPos shelf = new BlockPos(1, 0, 0);
		LibraryStructureScanner.ScanResult valid = new LibraryStructureScanner.ScanResult(
			LibraryStructureScanner.State.VALID, snapshotWithChiseledShelf());
		ReadyCore source = coreWithRetainedWork(valid, shelf);
		source.installLibrarianSnapshot(capturedLibrarian("minecraft:plains", 3,
			"\"Prior nested librarian\"", true));
		loadHistoricalBinding(source, binding(source, UUID.randomUUID(), 11));
		CompoundTag priorNested = source.saveWithFullMetadata(REGISTRIES);
		CompoundTag priorIndex = serverState(priorNested).getCompound("PatternIndex");
		flattenForLegacyFixture(priorIndex);
		priorIndex.remove("ReplyRequesterCursor");
		serverState(priorNested).remove("PageTopologies");
		serverState(priorNested).remove("MovementClaimToken");
		assertEquals(Set.of("LibrarianSnapshotBox", "PendingSafeRelease", "StructureState",
			"StructureSnapshot", "PatternIndex", "RoutingConfiguration"),
			serverState(priorNested).getAllKeys());
		assertEquals(Set.of("PageOrder", "Cache", "FingerprintCursor", "Generation",
			"Complete", "Queries", "Replies"), priorIndex.getAllKeys(),
			"The compatibility fixture must have the exact b72 PatternIndex root shape");
		assertEquals(1, priorIndex.getList("Queries", Tag.TAG_COMPOUND).size());
		assertTrue(priorIndex.getList("Queries", Tag.TAG_COMPOUND).getCompound(0)
			.contains("Cursor", Tag.TAG_INT));
		assertEquals(1, priorIndex.getList("Replies", Tag.TAG_COMPOUND).size());

		ReadyCore restored = new ReadyCore();
		restored.loadWithComponents(priorNested, REGISTRIES);

		assertEquals(source.memberId(), restored.memberId());
		assertEquals(frozenBinding(source), frozenBinding(restored));
		assertEquals(1, archivedCount(restored, "Queries"));
		assertEquals(1, archivedCount(restored, "Replies"));
		CompoundTag restoredIndex = diskIndex(restored);
		flattenForLegacyFixture(restoredIndex);
		restoredIndex.remove("ReplyRequesterCursor");
		assertEquals(priorIndex, restoredIndex);
		assertTrue(topologyRefreshPending(restored));
		assertFalse(restored.indexAccessReady(),
			"Prior nested state must remain gated until loaded shelf topology is reconstructed");

		CompoundTag retained = diskIndex(restored);
		restored.applyStructureScan(valid, Map.of(shelf, 0), 0);

		assertFalse(topologyRefreshPending(restored));
		assertTrue(restored.indexAccessReady());
		assertEquals(retained, diskIndex(restored));
	}

	@Test
	void currentEnvelopeMissingTopologyButContainingRequesterCursorIsRejectedAtomically() {
		BlockPos shelf = new BlockPos(1, 0, 0);
		LibraryStructureScanner.ScanResult valid = new LibraryStructureScanner.ScanResult(
			LibraryStructureScanner.State.VALID, snapshotWithChiseledShelf());
		ReadyCore source = coreWithRetainedWork(valid, shelf);
		CompoundTag malformedCurrent = source.saveWithFullMetadata(REGISTRIES);
		CompoundTag malformedIndex = serverState(malformedCurrent).getCompound("PatternIndex");
		flattenForLegacyFixture(malformedIndex);
		assertTrue(malformedIndex.hasUUID("ReplyRequesterCursor"),
			"The malformed-current fixture needs an unambiguous current-only index key");
		serverState(malformedCurrent).remove("PageTopologies");
		LibraryBlockEntity target = seededCore();
		CompoundTag before = authoritativeSnapshot(target);

		target.loadWithComponents(malformedCurrent, REGISTRIES);

		assertEquals(before, authoritativeSnapshot(target),
			"A damaged current envelope must not be mistaken for b72 or partially committed");
	}

	@Test
	void priorNestedCompatibilityRequiresExactB72PatternIndexTypesBeforeCommit() {
		BlockPos shelf = new BlockPos(1, 0, 0);
		LibraryStructureScanner.ScanResult valid = new LibraryStructureScanner.ScanResult(
			LibraryStructureScanner.State.VALID, snapshotWithChiseledShelf());
		ReadyCore source = coreWithRetainedWork(valid, shelf);
		CompoundTag malformedPrior = source.saveWithFullMetadata(REGISTRIES);
		CompoundTag malformedIndex = serverState(malformedPrior).getCompound("PatternIndex");
		flattenForLegacyFixture(malformedIndex);
		malformedIndex.remove("ReplyRequesterCursor");
		malformedIndex.putString("FingerprintCursor", "wrong-type");
		serverState(malformedPrior).remove("PageTopologies");
		LibraryBlockEntity target = seededCore();
		CompoundTag before = authoritativeSnapshot(target);

		target.loadWithComponents(malformedPrior, REGISTRIES);

		assertEquals(before, authoritativeSnapshot(target),
			"A prior-shaped envelope with non-b72 index types must be rejected atomically");
	}

	@Test
	void zeroMaskCurrentTopologyFromAnotherRootDimensionRemainsPending() {
		CompoundTag saved = zeroMaskCurrentState();
		ReadyCore restored = new ReadyCore(new SpaceAddress(Level.NETHER, null, BlockPos.ZERO));

		restored.loadWithComponents(saved, REGISTRIES);

		assertTrue(topologyRefreshPending(restored));
		assertFalse(restored.indexAccessReady());
	}

	@Test
	void zeroMaskCurrentTopologyFromAnotherSubLevelRemainsPending() {
		CompoundTag saved = zeroMaskCurrentState();
		ReadyCore restored = new ReadyCore(new SpaceAddress(Level.OVERWORLD, UUID.randomUUID(),
			BlockPos.ZERO));

		restored.loadWithComponents(saved, REGISTRIES);

		assertTrue(topologyRefreshPending(restored));
		assertFalse(restored.indexAccessReady());
	}

	@Test
	void ordinarySpaceZeroMaskCurrentRoundTripWaitsForLoadedReconstructionWithoutRestart() {
		BlockPos shelf = new BlockPos(1, 0, 0);
		LibraryStructureScanner.ScanResult valid = new LibraryStructureScanner.ScanResult(
			LibraryStructureScanner.State.VALID, snapshotWithChiseledShelf());
		CompoundTag saved = zeroMaskCurrentState();
		CompoundTag retainedIndex = serverState(saved).getCompound("PatternIndex").copy();
		assertEquals(0, serverState(saved).getList("PageTopologies", Tag.TAG_COMPOUND)
			.getCompound(0).getInt("WritableSlots"));
		assertTrue(retainedIndex.getList("PageOrder", Tag.TAG_COMPOUND).isEmpty());
		ReadyCore restored = new ReadyCore(new SpaceAddress(Level.OVERWORLD, null, BlockPos.ZERO));

		restored.loadWithComponents(saved, REGISTRIES);

		assertTrue(topologyRefreshPending(restored),
			"Zero-page persistence has no spatial witness and must wait for a loaded scan");
		assertFalse(restored.indexAccessReady());
		assertEquals(retainedIndex, diskIndex(restored));

		restored.applyStructureScan(valid, Map.of(shelf, 0), 0);

		assertFalse(topologyRefreshPending(restored));
		assertTrue(restored.indexAccessReady());
		assertEquals(retainedIndex, diskIndex(restored),
			"Matching zero-page reconstruction must not restart queries or drop replies");
	}

	@Test
	void oversizedTopologyEnvelopeIsRejectedBeforeMutatingAuthoritativeState() {
		LibraryBlockEntity target = seededCore();
		CompoundTag before = authoritativeSnapshot(target);
		ReadyCore source = coreWithRetainedWork(new LibraryStructureScanner.ScanResult(
			LibraryStructureScanner.State.VALID, snapshotWithChiseledShelf()),
			new BlockPos(1, 0, 0));
		CompoundTag oversized = source.saveWithFullMetadata(REGISTRIES);
		serverState(oversized).put("PageTopologies", topologyEntries(1025, 0));

		target.loadWithComponents(oversized, REGISTRIES);

		assertEquals(before, authoritativeSnapshot(target));
	}

	@Test
	void mismatchedTopologyMaskAndPageOrderRemainRecoverableButCannotServeBeforeRefresh() {
		BlockPos shelf = new BlockPos(1, 0, 0);
		LibraryStructureScanner.ScanResult valid = new LibraryStructureScanner.ScanResult(
			LibraryStructureScanner.State.VALID, snapshotWithChiseledShelf());
		ReadyCore source = coreWithRetainedWork(valid, shelf);
		CompoundTag mismatched = source.saveWithFullMetadata(REGISTRIES);
		serverState(mismatched).getList("PageTopologies", Tag.TAG_COMPOUND)
			.getCompound(0).putInt("WritableSlots", 1);
		CompoundTag recoverableIndex = serverState(mismatched).getCompound("PatternIndex").copy();

		ReadyCore restored = new ReadyCore();
		restored.loadWithComponents(mismatched, REGISTRIES);

		assertEquals(recoverableIndex, diskIndex(restored));
		assertTrue(topologyRefreshPending(restored));
		assertFalse(restored.indexAccessReady());
		CompoundTag retained = diskIndex(restored);

		restored.applyStructureScan(valid, Map.of(shelf, 0), 0);

		assertFalse(topologyRefreshPending(restored));
		assertTrue(restored.indexAccessReady());
		assertEquals(retained, diskIndex(restored),
			"Equivalent derived empty page order must install corrected topology without restart");
	}

	@Test
	void topologyPositionsMustExactlyMatchPersistedValidSnapshot() {
		BlockPos shelf = new BlockPos(1, 0, 0);
		LibraryStructureScanner.ScanResult valid = new LibraryStructureScanner.ScanResult(
			LibraryStructureScanner.State.VALID, snapshotWithChiseledShelf());
		ReadyCore source = coreWithRetainedWork(valid, shelf);
		CompoundTag mismatched = source.saveWithFullMetadata(REGISTRIES);
		ListTag topology = serverState(mismatched).getList("PageTopologies", Tag.TAG_COMPOUND);
		topology.getCompound(0).putLong("Pos", new BlockPos(2, 0, 0).asLong());
		CompoundTag recoverableIndex = serverState(mismatched).getCompound("PatternIndex").copy();

		ReadyCore restored = new ReadyCore();
		restored.loadWithComponents(mismatched, REGISTRIES);

		assertEquals(recoverableIndex, diskIndex(restored));
		assertTrue(topologyRefreshPending(restored));
		assertFalse(restored.indexAccessReady());
	}

	@Test
	void internallyConsistentTopologyFromAnotherSpaceRemainsPendingAndCannotServe() {
		BlockPos shelf = new BlockPos(1, 0, 0);
		ReadyCore source = new ReadyCore();
		source.applyStructureScan(new LibraryStructureScanner.ScanResult(
			LibraryStructureScanner.State.VALID, snapshotWithChiseledShelf()),
			Map.of(shelf, 0), 0);
		SpaceAddress wrongShelf = new SpaceAddress(Level.NETHER, UUID.randomUUID(), shelf);
		source.libraryIndex().rebuildPageOrder(LibraryBlockEntity.pageKeys(
			wrongShelf, List.of(writableBook("{}"), ItemStack.EMPTY, ItemStack.EMPTY,
				ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY)));
		CompoundTag wrongSpace = source.saveWithFullMetadata(REGISTRIES);
		serverState(wrongSpace).getList("PageTopologies", Tag.TAG_COMPOUND)
			.getCompound(0).putInt("WritableSlots", 1);

		ReadyCore restored = new ReadyCore();
		restored.loadWithComponents(wrongSpace, REGISTRIES);

		assertTrue(topologyRefreshPending(restored));
		assertFalse(restored.indexAccessReady());
	}

	@Test
	void internallyConsistentSnapshotRootedAtAnotherCoreRemainsPendingAndCannotServe() {
		bootstrap();
		BlockPos otherCore = new BlockPos(5, 0, 0);
		BlockPos otherShelf = new BlockPos(6, 0, 0);
		LibraryStructureScanner.Snapshot displaced = new LibraryStructureScanner.Snapshot(
			List.of(otherCore, otherShelf), List.of(), List.of(otherShelf),
			otherCore, otherShelf);
		ReadyCore source = new ReadyCore();
		source.applyStructureScan(new LibraryStructureScanner.ScanResult(
			LibraryStructureScanner.State.VALID, displaced),
			Map.of(otherShelf, 0), 0);

		ReadyCore restored = new ReadyCore();
		restored.loadWithComponents(source.saveWithFullMetadata(REGISTRIES), REGISTRIES);

		assertTrue(topologyRefreshPending(restored));
		assertFalse(restored.indexAccessReady());
	}

	@Test
	void topologyDecodeAcceptsExactMaximumAndRejectsMaximumPlusOneBeforeCopying() {
		assertEquals(1024, LibraryBlockEntity
			.loadPageTopologies(topologyEntries(1024, 0), 1024).orElseThrow().size());
		assertTrue(LibraryBlockEntity
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
		LibraryStructureScanner.Snapshot maximum = new LibraryStructureScanner.Snapshot( members, List.of(), chiseled,
			BlockPos.ZERO, chiseled.getLast());
		Map<BlockPos, Integer> topologies = LibraryBlockEntity
			.loadPageTopologies(topologyEntries(1023, 0), 1024).orElseThrow();

		assertTrue(RecipeMaintenanceArchive.matchesTopology(
			new LibraryStructureScanner.ScanResult(LibraryStructureScanner.State.VALID, maximum), topologies, List.of(), 1024));
	}

	@Test
	void missingExpectedShelfContentsMakesInspectionIncompleteInsteadOfShrinkingTopology() {
		BlockPos first = new BlockPos(1, 0, 0);
		BlockPos second = new BlockPos(2, 0, 0);
		LibraryStructureScanner.Snapshot snapshot = new LibraryStructureScanner.Snapshot(
			List.of(BlockPos.ZERO, first, second), List.of(), List.of(first, second),
			BlockPos.ZERO, second);
		List<ItemStack> emptyShelf = List.of(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY,
			ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
		Holder<Enchantment> efficiency = Holder.direct(allocate(Enchantment.class));

		var incomplete = RecipeShelfSource.inspect(snapshot.mediaShelves(),
			pos -> pos.equals(first) ? emptyShelf : null, efficiency);

		assertFalse(incomplete.complete());
		assertTrue(incomplete.topologies().isEmpty(),
			"A partial inspection must never publish a smaller topology");
		var complete = RecipeShelfSource.inspect(snapshot.mediaShelves(),
			ignored -> emptyShelf, efficiency);
		assertTrue(complete.complete());
		assertEquals(Map.of(first, 0, second, 0), complete.topologies());
	}

	@Test
	void repeatedIdenticalStructureScanDoesNotResyncSteadyState() {
		bootstrap();
		SyncTrackingCore core = new SyncTrackingCore();
		LibraryStructureScanner.ScanResult valid = new LibraryStructureScanner.ScanResult(
			LibraryStructureScanner.State.VALID, coreOnlySnapshot());
		core.applyStructureScan(valid);
		int afterChange = core.syncs;

		core.applyStructureScan(valid);

		assertEquals(afterChange, core.syncs);
	}

	@Test
	void freshItemRejectsUnavailableIdentityAuthorityWithoutRetryingForever() throws Exception {
		MinecraftServer server = allocate(DedicatedServer.class);
		var loaded = loadedMovementLeaseData();
		CompoundTag unavailable = new CompoundTag(); unavailable.putBoolean("FailClosed", true);
		var authority = LibraryMovementLeases.load(unavailable, REGISTRIES);
		synchronized (loaded) { loaded.put(server, authority); }
		var core = new ServerCore(server);
		var originalId = core.memberId();
		var originalRouting = core.routingConfiguration();
		var item = LibraryCoreItemData.configured(CommunicationFrequency.of("fresh-library"),
			7, Optional.empty(), 1);
		var executor = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
			Thread thread = new Thread(task, "library-placement-regression"); thread.setDaemon(true); return thread;
		});
		var started = new java.util.concurrent.CountDownLatch(1);
		var attempt = executor.submit(() -> { started.countDown(); return core.claimItemConfiguration(server, item); });
		try {
			assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS), "Fixture worker starts");
			try {
				assertFalse(attempt.get(2, java.util.concurrent.TimeUnit.SECONDS),
					"Unavailable identity authority must reject a fresh configured item");
			} catch (java.util.concurrent.TimeoutException stuck) {
				throw new AssertionError("Fresh item placement retries UUIDs forever against unavailable authority", stuck);
			}
			assertEquals(originalId, core.memberId());
			assertEquals(originalRouting, core.routingConfiguration());
			assertTrue(core.claimedItemData().isEmpty());
			assertTrue(authority.save(new CompoundTag(), REGISTRIES).getBoolean("FailClosed"));
		} finally {
			// Release the old implementation's busy loop after observing RED; never leave a spinning test worker.
			synchronized (loaded) { loaded.remove(server); }
			try { attempt.get(5, java.util.concurrent.TimeUnit.SECONDS); }
			finally { core.releaseLibraryIdentityClaim(); executor.shutdownNow(); }
		}
	}

	@Test
	void itemConfigurationClaimsPreservedLibraryIdentityAtomically() {
		MinecraftServer server = allocate(DedicatedServer.class);
		ServerCore first = new ServerCore(server);
		ServerCore duplicate = new ServerCore(server);
		UUID preserved = UUID.fromString("1b83ccbd-806b-4b60-a7ef-e891acc3b957");
		LibraryCoreItemData itemData = LibraryCoreItemData.configured(
			CommunicationFrequency.of("Library-A"), Integer.MIN_VALUE, preserved, 7);
		UUID duplicateOriginalIdentity = duplicate.memberId();
		ProductionLibraryRouting duplicateOriginalRouting =
			duplicate.routingConfiguration();

		assertTrue(first.claimItemConfiguration(server, itemData));
		assertEquals(preserved, first.memberId());
		assertEquals(new ProductionLibraryRouting(
			CommunicationFrequency.of("Library-A"), Integer.MIN_VALUE, 7),
			first.routingConfiguration());
		assertEquals(Optional.of(itemData), first.claimedItemData());
		LibraryCoreItemData replacementIdentity = LibraryCoreItemData.configured(
			CommunicationFrequency.of("Library-B"), 20, UUID.randomUUID(), 8);
		assertFalse(first.claimItemConfiguration(server, replacementIdentity),
			"a claimed Core cannot migrate its active identity through the placement API");
		assertEquals(Optional.of(itemData), first.claimedItemData());

		assertFalse(duplicate.claimItemConfiguration(server, itemData));
		assertEquals(duplicateOriginalIdentity, duplicate.memberId());
		assertEquals(duplicateOriginalRouting, duplicate.routingConfiguration());
		assertTrue(duplicate.claimedItemData().isEmpty());

		first.releaseLibraryIdentityClaim();
		assertTrue(duplicate.claimItemConfiguration(server, itemData));
		assertEquals(Optional.of(itemData), duplicate.claimedItemData());
		duplicate.releaseLibraryIdentityClaim();
	}

	@Test
	void placementDataFreezesExactRoutingAndMintsOneClaimingCoreItem() {
		MinecraftServer server = allocate(DedicatedServer.class);
		ServerCore core = new ServerCore(server);
		UUID preserved = UUID.fromString("77f9f9e9-8ac5-4a58-8124-af13724017cf");
		LibraryCoreItemData placed = LibraryCoreItemData.configured(
			CommunicationFrequency.of("Library-Frozen"), Integer.MAX_VALUE, preserved, 19);

		assertTrue(core.applyPlacementData(placed));
		assertEquals(new ProductionLibraryRouting(
			CommunicationFrequency.of("Library-Frozen"), Integer.MAX_VALUE, 19),
			core.routingConfiguration());
		ItemStack drop = core.asConfiguredStack(new ItemStack(Items.STONE));
		assertEquals(1, drop.getCount());
		assertEquals(placed, LibraryCoreItemData.read(drop).orElseThrow());

		LibraryCoreItemData attemptedMutation = LibraryCoreItemData.configured(
			CommunicationFrequency.of("Other"), Integer.MIN_VALUE, UUID.randomUUID(), 20);
		assertFalse(core.applyPlacementData(attemptedMutation));
		assertEquals(placed, LibraryCoreItemData.read(
			core.asConfiguredStack(new ItemStack(Items.STONE))).orElseThrow());
		core.releaseLibraryIdentityClaim();
	}

	@Test
	void placementWithoutPreservedIdentityGeneratesOneStableClaimedIdentity() {
		MinecraftServer server = allocate(DedicatedServer.class);
		ServerCore core = new ServerCore(server);
		UUID constructorIdentity = core.memberId();
		LibraryCoreItemData placed = LibraryCoreItemData.configured(
			CommunicationFrequency.of("Generated"), -20, Optional.empty(), 4);

		assertTrue(core.applyPlacementData(placed));
		LibraryCoreItemData claimed = LibraryCoreItemData.read(
			core.asConfiguredStack(new ItemStack(Items.STONE))).orElseThrow();
		assertEquals(constructorIdentity, claimed.preservedLibraryId().orElseThrow());
		assertEquals(placed.frequency(), claimed.frequency());
		assertEquals(placed.libraryPriority(), claimed.libraryPriority());
		assertEquals(placed.revision(), claimed.revision());
		assertEquals(claimed, LibraryCoreItemData.read(
			core.asConfiguredStack(new ItemStack(Items.STONE))).orElseThrow());
		core.releaseLibraryIdentityClaim();
	}

	@Test
	void aRejectedSecondMovementTransferCannotReopenStationaryStorage() {
		MinecraftServer server = allocate(DedicatedServer.class);
		ServerCore core = new ServerCore(server);
		Object movementOwner = new Object();
		assertTrue(core.applyPlacementData(LibraryCoreItemData.configured(
			CommunicationFrequency.of("Moving"), 0, Optional.empty(), 0)));
		try {
			assertTrue(core.libraryIdentityReady());
			assertTrue(core.retainLibraryIdentityForMovement(movementOwner));
			assertFalse(core.libraryIdentityReady());
			assertFalse(core.retainLibraryIdentityForMovement(new Object()));
			assertFalse(core.libraryIdentityReady(), "Failed retry must not turn a contraption lease into a stationary service");
		} finally {
			core.invalidate();
			LibraryIdentityClaims.releaseLibraryIdentity(server, core.memberId(), movementOwner);
		}
	}

	@Test
	void generatedPlacementIdentityRetriesAConstructorCollisionWithoutDisturbingItsOwner() {
		MinecraftServer server = allocate(DedicatedServer.class);
		ServerCore target = new ServerCore(server);
		ServerCore owner = new ServerCore(server);
		UUID collidedConstructorIdentity = target.memberId();
		LibraryCoreItemData ownerData = LibraryCoreItemData.configured(
			CommunicationFrequency.of("Existing"), 3, collidedConstructorIdentity, 2);
		assertTrue(owner.applyPlacementData(ownerData));

		LibraryCoreItemData generated = LibraryCoreItemData.configured(
			CommunicationFrequency.of("Generated"), 4, Optional.empty(), 5);
		assertTrue(target.applyPlacementData(generated));
		UUID generatedIdentity = target.memberId();
		assertNotEquals(collidedConstructorIdentity, generatedIdentity);
		assertEquals(ownerData, LibraryCoreItemData.read(
			owner.asConfiguredStack(new ItemStack(Items.STONE))).orElseThrow());
		assertEquals(generatedIdentity, LibraryCoreItemData.read(
			target.asConfiguredStack(new ItemStack(Items.STONE))).orElseThrow()
			.preservedLibraryId().orElseThrow());

		target.releaseLibraryIdentityClaim();
		owner.releaseLibraryIdentityClaim();
	}

	@Test
	void createRemovalOrderCapturesLeaseBeforeRemoveBlockEntityInvalidatesTheCore() {
		MinecraftServer server = allocate(DedicatedServer.class);
		ServerCore moving = new ServerCore(server);
		ServerCore intruder = new ServerCore(server);
		Object contraptionOwner = new Object();
		List<String> removalOrder = new ArrayList<>();
		UUID libraryId = UUID.fromString("66e868b0-459c-4c96-bbe1-482972a49d8b");
		LibraryCoreItemData data = LibraryCoreItemData.configured(
			CommunicationFrequency.of("Moving"), 17, libraryId, 6);
		assertTrue(moving.applyPlacementData(data));
		CompoundTag serializedMovement = new CompoundTag();
		moving.write(serializedMovement, REGISTRIES, false);

		LibraryContraptionMovement.removeBlockEntityWithLease(
			new LibraryContraptionMovement.RemovalAccess() {
				@Override
				public LibraryBlockEntity lowerCore() {
					removalOrder.add("lookup");
					return moving;
				}

				@Override
				public boolean captureLease(LibraryBlockEntity core) {
					removalOrder.add("capture");
					return core.retainLibraryIdentityForMovement(contraptionOwner);
				}

				@Override
				public void removeBlockEntity() {
					removalOrder.add("removeBlockEntity");
					moving.invalidate();
				}
			});
		assertEquals(List.of("lookup", "capture", "removeBlockEntity"), removalOrder,
			"the production wrapper must capture before Create invalidates the BE");
		assertFalse(intruder.applyPlacementData(data),
			"moving invalidation must not open a preserved-ID takeover window");

		ServerCore reassembled = new ServerCore(server);
		reassembled.read(serializedMovement, REGISTRIES, false);
		assertTrue(reassembled.acceptMovementLeaseBeforeFirstTick(server, contraptionOwner),
			"reassembly must take over the exact serialized movement token before Create discards its entity");
		assertEquals(Optional.of(data), reassembled.claimedItemData());
		assertFalse(intruder.applyPlacementData(data));
		reassembled.releaseLibraryIdentityClaim();
		assertTrue(intruder.applyPlacementData(data),
			"a completed handoff must retain the ordinary exact-owner release boundary");
		intruder.releaseLibraryIdentityClaim();
	}

	@Test
	void copiedMovementNbtCannotUseOrdinaryInitializeToStealTheRetainedClaim()
		throws ReflectiveOperationException {
		MinecraftServer server = allocate(DedicatedServer.class);
		ServerCore moving = new ServerCore(server);
		Object contraptionOwner = new Object();
		UUID libraryId = UUID.fromString("dfe29451-865c-41ce-a961-cb8549ac3e75");
		LibraryCoreItemData data = LibraryCoreItemData.configured(
			CommunicationFrequency.of("CopiedMovement"), 23, libraryId, 4);
		assertTrue(moving.applyPlacementData(data));
		CompoundTag copiedNbt = new CompoundTag();
		moving.write(copiedNbt, REGISTRIES, false);
		LibraryMovementLeases durable = new LibraryMovementLeases();
		Map<MinecraftServer, LibraryMovementLeases> loaded = loadedMovementLeaseData();
		synchronized (loaded) {
			loaded.put(server, durable);
		}
		try {
			assertTrue(moving.retainLibraryIdentityForMovement(contraptionOwner));
			moving.invalidate();

			ServerCore copiedCore = new ServerCore(server);
			copiedCore.read(copiedNbt, REGISTRIES, false);
			copiedCore.initialize();

			assertEquals(Optional.empty(), copiedCore.claimedItemData(),
				"a normal first tick must not treat the copied movement token as authority");
			ServerCore exactReassembly = new ServerCore(server);
			exactReassembly.read(copiedNbt, REGISTRIES, false);
			assertTrue(exactReassembly.acceptMovementLeaseBeforeFirstTick(server, contraptionOwner));
			exactReassembly.releaseLibraryIdentityClaim();
		} finally {
			synchronized (loaded) {
				loaded.remove(server);
			}
		}
	}

	@Test
	void copiedContraptionDisposalCannotEraseTheRealOwnersDurableLease() throws ReflectiveOperationException {
		MinecraftServer server = allocate(DedicatedServer.class);
		var durable = new LibraryMovementLeases();
		var loaded = loadedMovementLeaseData();
		synchronized (loaded) { loaded.put(server, durable); }
		try {
			var source = new ServerCore(server);
			UUID library = UUID.randomUUID(); Object realOwner = new Object();
			assertTrue(source.applyPlacementData(LibraryCoreItemData.configured(
				CommunicationFrequency.of("movement-owner"), 0, library, 0)));
			assertTrue(source.retainLibraryIdentityForMovement(realOwner));
			source.invalidate();
			UUID token = source.movementClaimToken();
			assertTrue(durable.matches(library, token));
			LibraryIdentityClaims.releaseMovementLease(server, library, new Object(), token);
			assertTrue(durable.matches(library, token), "Copied token without exact owner cannot erase persistent authority");
			assertTrue(LibraryIdentityClaims.activateLibraryIdentityFromMovementOwner(
				server, library, realOwner, realOwner, token, () -> {}, () -> {}));
			LibraryIdentityClaims.releaseLibraryIdentity(server, library, realOwner);
			assertFalse(durable.blocksClaim(library, null));
		} finally { synchronized (loaded) { loaded.remove(server); } }
	}

	@Test
	void directLibrarianInstallationClearsThenCommitsExactlyOnce() {
		LibraryBlockEntity core = core();
		ItemStack held = capturedLibrarian("minecraft:plains", 3, "\"Curator\"", true);
		InstallProbe probe = InstallProbe.success();

		assertEquals(LibraryResident.InstallResult.SUCCESS,
			core.installLibrarian(held, probe, probe::synchronize));
		assertTrue(core.hasSnapshot());
		assertEquals(1, probe.inspections);
		assertEquals(1, probe.clears);
		assertFalse(CapturedEntityBoxHelper.hasCapturedEntity(held));
		assertTrue(probe.clearedBeforeSync,
			"the capture box must be observably clear before the server snapshot is synchronized");
		ItemStack occupiedAttempt = capturedLibrarian(
			"minecraft:plains", 3, "\"Second\"", true);
		assertEquals(LibraryResident.InstallResult.OCCUPIED,
			core.installLibrarian(occupiedAttempt, probe, probe::synchronize));
		assertEquals(1, probe.inspections);
		assertEquals(1, probe.clears);
	}

	@Test
	void failedOrExceptionalLibrarianClearAndSyncRollbackBothOwners() {
		for (InstallProbe probe : List.of(InstallProbe.noOpClear(),
			InstallProbe.partialClearFailure(), InstallProbe.throwingClear(),
			InstallProbe.throwingSync())) {
			LibraryBlockEntity core = core();
			ItemStack held = capturedLibrarian("minecraft:plains", 3, "\"Atomic\"", true);
			ItemStack beforeHeld = held.copy();
			CompoundTag beforeCore = authoritativeSnapshot(core);
			var beforeDisplay = core.clientState();

			LibraryResident.InstallResult result =
				core.installLibrarian(held, probe, probe::synchronize);

			assertTrue(result == LibraryResident.InstallResult.CLEAR_FAILED
				|| result == LibraryResident.InstallResult.COMMIT_FAILED,
				probe.mode.name());
			assertTrue(ItemStack.matches(beforeHeld, held), probe.mode.name());
			assertEquals(beforeCore, authoritativeSnapshot(core), probe.mode.name());
			assertEquals(beforeDisplay, core.clientState(), probe.mode.name());
			assertEquals(beforeDisplay.residentView(), core.libraryResidentView(), probe.mode.name());
			assertFalse(core.hasSnapshot(), probe.mode.name());
		}
	}

	@Test
	void directLibrarianInstallationRejectsEveryInvalidClassWithoutMutation() {
		for (LibraryResident.InstallResult rejected : List.of(
			LibraryResident.InstallResult.NOT_BOX,
			LibraryResident.InstallResult.EMPTY_BOX,
			LibraryResident.InstallResult.MALFORMED,
			LibraryResident.InstallResult.UNSUPPORTED_ENTITY,
			LibraryResident.InstallResult.BABY,
			LibraryResident.InstallResult.DUPLICATE_IDENTITY)) {
			LibraryBlockEntity core = core();
			ItemStack held = capturedLibrarian("minecraft:plains", 2, "\"Rejected\"", true);
			InstallProbe probe = InstallProbe.rejected(rejected);
			CompoundTag before = authoritativeSnapshot(core);

			assertEquals(rejected, core.installLibrarian(held, probe, probe::synchronize), rejected.name());
			assertEquals(before, authoritativeSnapshot(core), rejected.name());
			assertEquals(0, probe.clears, rejected.name());
		}
	}

	@Test
	void residentReleaseClassificationDistinguishesFreshCoreFromMalformedRawState() {
		// This unit fixture has no registered mod item; native tests exercise its actual output.
		LibraryBlockEntity fresh = new LibraryBlockEntity(
			BlockEntityType.FURNACE, BlockPos.ZERO, Blocks.FURNACE.defaultBlockState()) {
			@Override public ItemStack asConfiguredStack() { return ItemStack.EMPTY; }
		};
		assertFalse(fresh.captureLibraryRemoval().residentRequired());

		fresh.installRawLibrarianSnapshot(new CompoundTag());
		assertTrue(fresh.captureLibraryRemoval().residentRequired(),
			"retained malformed data must never be mistaken for an empty item-placed Core");
	}

	@Test
    void freshCoreNeedsNoFrequencyAndReclaimsItsIdentityAfterRemoval() {
        MinecraftServer server = allocate(DedicatedServer.class);
        ServerCore first = new ServerCore(server);
        ServerCore moved = new ServerCore(server);
        try {
            assertTrue(first.claimItemConfiguration(server, LibraryCoreItemData.unconfigured()));
            var recovered = LibraryCoreItemData.read(first.asConfiguredStack(new ItemStack(Items.STONE))).orElseThrow();
            assertEquals(LibraryCoreItemData.identityOnly(first.memberId()), recovered);
            assertFalse(moved.claimItemConfiguration(server, recovered), "Do not duplicate a live library");
            first.releaseLibraryIdentityClaim();
            assertTrue(moved.claimItemConfiguration(server, recovered));
            assertEquals(first.memberId(), moved.memberId());
        } finally {
            first.releaseLibraryIdentityClaim();
            moved.releaseLibraryIdentityClaim();
        }
    }

	// Restore an actual historical save, not a runtime route for attaching a production member.
	private static void loadHistoricalBinding(LibraryBlockEntity core, ClusterBinding binding) {
		var tag = new CompoundTag(); core.write(tag, REGISTRIES, false);
		patternData(tag).put("BindingState", binding.save()); core.read(tag, REGISTRIES, false);
	}

	private static ClusterBinding frozenBinding(LibraryBlockEntity core) {
		var tag = new CompoundTag(); core.write(tag, REGISTRIES, false);
		return ClusterBinding.tryLoad(patternData(tag).getCompound("BindingState")).orElse(null);
	}

	@Test
	void allHistoricalNetworksMustPermitEditingAndThePolicyDoesNotMutateItsSource() {
		var first = UUID.randomUUID(); var second = UUID.randomUUID();
		var historical = new ClusterBinding(UUID.randomUUID(), 1, null,
			List.of(new LogisticsBinding(first), new LogisticsBinding(second)));
		var tag = new CompoundTag(); tag.put("BindingState", historical.save());
		tag.putBoolean("BindingStateInvalid", false);
		var policy = LibraryEditRestriction.read(tag).orElseThrow();
		assertFalse(policy.mayEdit(first::equals));
		assertFalse(policy.mayEdit(second::equals));
		assertTrue(policy.mayEdit(Set.of(first, second)::contains));
		tag.remove("BindingState"); var copy = new CompoundTag(); policy.write(copy);
		assertEquals(historical.save(), copy.getCompound("BindingState"));
	}

	@Test
	void unclaimedDuplicateCannotServeItsCopiedLibraryIdentity() {
		MinecraftServer server = allocate(DedicatedServer.class);
		LibraryBlockEntity source = core();
		UUID preserved = source.memberId();
		loadHistoricalBinding(source, binding(source, UUID.randomUUID(), 1));
		LibraryCoreItemData itemData = LibraryCoreItemData.configured(
			CommunicationFrequency.of("Library-A"), 4, preserved, 2);
		assertTrue(source.claimItemConfiguration(server, itemData));
		CompoundTag saved = new CompoundTag();
		source.write(saved, REGISTRIES, false);
		ServerCore duplicate = new ServerCore(server);
		duplicate.read(saved, REGISTRIES, false);
		assertEquals(preserved, duplicate.memberId());
		assertFalse(duplicate.claimItemConfiguration(server, itemData));
		assertFalse(duplicate.libraryIdentityReady());
		assertEquals(frozenBinding(source), frozenBinding(duplicate));
		assertTrue(ClusterMemberIndex.members(server, frozenBinding(source).clusterId(),
			ClusterMemberType.PATTERN_CORE).isEmpty());
		source.releaseLibraryIdentityClaim();
	}

	@Test
	void clientPacketStartsFreshAndCannotRetainServerArchiveOrStaleExtensions() {
		var source = seededCore();
		var before = authoritativeSnapshot(source);
		var packet = before.copy();
		packet.putString("AdvancedGoggleTarget", "stale target");
		packet.putString("PrivateMemory", "must not leave the server");
		source.write(packet, REGISTRIES, true);
		assertEquals(Set.of("LibraryId", "ClientState", "NormalGoggles"), packet.getAllKeys());
		assertEquals(before, authoritativeSnapshot(source));
		var target = core(); target.read(packet, REGISTRIES, true);
		assertEquals(source.memberId(), target.memberId());
		assertEquals(RecipeLibraryClientView.load(packet.getCompound("ClientState")).orElseThrow(), target.clientState());
	}

	@Test
	void librarianProjectionAndBudgetUseCapturedTypeLevelAndPlainName() {
		LibraryBlockEntity core = core();
		core.installLibrarianSnapshot(capturedLibrarian("minecraft:swamp", 4, "\"Ada\"", false));
		CompoundTag packet = new CompoundTag();
		core.write(packet, REGISTRIES, true);
		RecipeLibraryClientView state = RecipeLibraryClientView.load(packet.getCompound("ClientState"))
			.orElseThrow();

		assertAll(
			() -> assertTrue(state.renderLibrarian()),
			() -> assertEquals(ResourceLocation.withDefaultNamespace("swamp"), state.villagerType()),
			() -> assertEquals(4, state.librarianLevel()),
			() -> assertEquals("Ada", state.customName()),
			() -> assertEquals(4, core.librarianLevel()),
			() -> assertEquals(64, LibraryBlockEntity.baseBudget(4)));
	}

	@Test
	void clientStateCodecCoversDefaultsAboveDefaultsMaximaAndEveryUpperBoundary() {
		RecipeLibraryClientView defaults = new RecipeLibraryClientView(false,
			ResourceLocation.withDefaultNamespace("plains"), 1, null,
			LibraryStructureScanner.State.UNFORMED, false, 0, 0, 0, 0, 1);
		RecipeLibraryClientView aboveDefaults = new RecipeLibraryClientView(true,
			ResourceLocation.withDefaultNamespace("desert"), 3, "Curator",
			LibraryStructureScanner.State.VALID, true, 65, 65, 65, 601, 66);
		RecipeLibraryClientView maxima = new RecipeLibraryClientView(true,
			ResourceLocation.withDefaultNamespace("swamp"), 5, "x".repeat(64),
			LibraryStructureScanner.State.PARTIAL, false, 1024, 1024, 1024, 10000, 1024);

		assertAll(
			() -> assertEquals(defaults, RecipeLibraryClientView.load(defaults.save()).orElseThrow()),
			() -> assertEquals(aboveDefaults,
				RecipeLibraryClientView.load(aboveDefaults.save()).orElseThrow()),
			() -> assertEquals(maxima, RecipeLibraryClientView.load(maxima.save()).orElseThrow()));
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
			assertTrue(RecipeLibraryClientView.load(invalid).isEmpty(), key);
		}
		CompoundTag budget = maxima.save();
		budget.putInt("SearchBudget", 10001);
		assertTrue(RecipeLibraryClientView.load(budget).isEmpty());
		for (int level : List.of(0, 6)) {
			CompoundTag invalid = maxima.save();
			invalid.putInt("LibrarianLevel", level);
			assertTrue(RecipeLibraryClientView.load(invalid).isEmpty());
		}
		CompoundTag name = maxima.save();
		name.putString("CustomName", "x".repeat(65));
		assertTrue(RecipeLibraryClientView.load(name).isEmpty());
		CompoundTag wrongType = defaults.save();
		wrongType.putString("LogicalMembers", "65");
		assertTrue(RecipeLibraryClientView.load(wrongType).isEmpty());
		CompoundTag extraKey = defaults.save();
		extraKey.putBoolean("Debug", true);
		assertTrue(RecipeLibraryClientView.load(extraKey).isEmpty());
		for (String key : List.of("RenderLibrarian", "PendingSafeRelease")) {
			CompoundTag nonCanonicalBoolean = defaults.save();
			nonCanonicalBoolean.putByte(key, (byte) 2);
			assertTrue(RecipeLibraryClientView.load(nonCanonicalBoolean).isEmpty(), key);
		}
		CompoundTag unknownVillagerType = defaults.save();
		unknownVillagerType.putString("VillagerType", "create_biotech:not_a_villager_type");
		assertTrue(RecipeLibraryClientView.load(unknownVillagerType).isEmpty());
	}

	@Test
	void fullMetadataAndNeoForgeStateRoundTripThroughRealBlockEntityApis() {
		LibraryBlockEntity source = seededCore();
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

		LibraryBlockEntity restored = core();
		restored.loadWithComponents(saved, REGISTRIES);

		assertAll(
			() -> assertEquals(source.memberId(), restored.memberId()),
			() -> assertEquals(frozenBinding(source), frozenBinding(restored)),
			() -> assertEquals(source.structureSnapshot(), restored.structureSnapshot()),
			() -> assertEquals(diskIndex(source),
				diskIndex(restored)),
			() -> assertEquals("persistent-data",
				restored.getPersistentData().getString("PatternReviewMarker")),
			() -> assertEquals("attachment-data", restored.getData(TEST_ATTACHMENT)));
	}

	@Test
	void validClientIdentityReplacementPreservesAllServerOnlyState() {
		LibraryBlockEntity target = seededCore();
		UUID transmitted = UUID.randomUUID();
		RecipeLibraryClientView projected = new RecipeLibraryClientView(true,
			ResourceLocation.withDefaultNamespace("taiga"), 5, "Client name",
			LibraryStructureScanner.State.PARTIAL, true, 65, 4, 3, 601, 5);
		CompoundTag packet = new CompoundTag();
		packet.putUUID("LibraryId", transmitted);
		packet.put("ClientState", projected.save());
		packet.put("NormalGoggles", com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleState.EMPTY.save());
		ClusterBinding binding = frozenBinding(target);
		ItemStack snapshot = target.snapshot();
		LibraryStructureScanner.Snapshot structure = target.structureSnapshot();
		CompoundTag index = diskIndex(target);

		target.read(packet, REGISTRIES, true);

		assertAll(
			() -> assertEquals(transmitted, target.memberId()),
			() -> assertEquals(projected, target.clientState()),
			() -> assertEquals(projected.residentView(), target.libraryResidentView()),
			() -> assertEquals(binding, frozenBinding(target)),
			() -> assertTrue(ItemStack.isSameItemSameComponents(snapshot, target.snapshot())),
			() -> assertSame(structure, target.structureSnapshot()),
			() -> assertEquals(index, diskIndex(target)));
	}

	@Test
	void clientDiagnosticsUseAcceptedProjectionWithoutRebuildingFromAbsentServerResidentsOrShelves()
		throws ReflectiveOperationException {
		// Inert client-side Level only: this is a branch regression, not rendered-player verification.
		var level = allocate(net.minecraft.client.multiplayer.ClientLevel.class);
		var clientSide = Level.class.getDeclaredField("isClientSide");
		clientSide.setAccessible(true); clientSide.setBoolean(level, true);
		var target = core(); target.setLevel(level);
		var view = new RecipeLibraryClientView(true, ResourceLocation.withDefaultNamespace("taiga"),
			5, "Accepted client", LibraryStructureScanner.State.PARTIAL, true, 65, 4, 3, 601, 5);
		var packet = new CompoundTag(); packet.putUUID("LibraryId", UUID.randomUUID());
		packet.put("ClientState", view.save());
		packet.put("NormalGoggles", com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleState.EMPTY.save());
		try {
			target.read(packet, REGISTRIES, true);
			assertTrue(target.getLevel().isClientSide);
			assertFalse(target.hasSnapshot()); assertNull(target.structureSnapshot());
			assertEquals(view.residentView(), target.libraryResidentView());
			assertEquals(LibraryStructureScanner.State.PARTIAL, target.structureState());
			assertEquals(601, target.searchBudget()); assertEquals(5, target.queueCount());
			var diagnostics = target.browserDiagnostics();
			assertEquals(LibraryStructureScanner.State.PARTIAL, diagnostics.structure());
			assertEquals(4, diagnostics.ordinaryShelves()); assertEquals(3, diagnostics.chiseledShelves());
			assertEquals(601, diagnostics.searchBudget()); assertEquals(5, diagnostics.replyQueues());
		} finally { target.setLevel(null); }
	}

	@Test
	void rejectedLibraryPacketsPreserveBothDiagnosticsAndAuthoritativeState() {
		var core = seededCore();
		var view = new RecipeLibraryClientView(true, ResourceLocation.withDefaultNamespace("swamp"),
			4, "Curator", LibraryStructureScanner.State.VALID, false, 3, 1, 1, 64, 2);
		var normal = new com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleState(
			List.of(new com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleState.Row("state", List.of("READY"))));
		var target = new com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleTarget(
			new SpaceAddress(Level.OVERWORLD, null, BlockPos.ZERO), core.memberId(), 7, UUID.randomUUID());
		var valid = new CompoundTag(); valid.putUUID("LibraryId", core.memberId());
		valid.put("ClientState", view.save()); valid.put("NormalGoggles", normal.save());
		valid.put("AdvancedGoggleTarget", target.save()); core.read(valid, REGISTRIES, true);
		var before = authoritativeSnapshot(core); var identity = core.memberId();
		assertEquals(Optional.of(target), core.advancedGoggleMetadata().clientTarget());
		for (String kind : List.of("extra_root", "bad_id", "wrong_view_type", "bad_view")) {
			var invalid = valid.copy(); invalid.remove("AdvancedGoggleTarget");
			invalid.put("NormalGoggles", com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleState.EMPTY.save());
			switch (kind) {
				case "extra_root" -> invalid.putBoolean("Forbidden", true);
				case "bad_id" -> invalid.putString("LibraryId", "not UUID");
				case "wrong_view_type" -> invalid.putString("ClientState", "not compound");
				case "bad_view" -> invalid.put("ClientState", new CompoundTag());
				default -> throw new AssertionError(kind);
			}
			core.read(invalid, REGISTRIES, true);
			assertAll(kind, () -> assertEquals(identity, core.memberId()),
				() -> assertEquals(view, core.clientState()), () -> assertEquals(normal, core.normalGoggles()),
				() -> assertEquals(Optional.of(target), core.advancedGoggleMetadata().clientTarget()),
				() -> assertEquals(before, authoritativeSnapshot(core)));
		}
	}

	@Test
	void malformedClientIdentityRejectsPacketAtomically() {
		LibraryBlockEntity target = seededCore();
		CompoundTag valid = new CompoundTag();
		valid.putUUID("LibraryId", UUID.randomUUID());
		valid.put("ClientState", new RecipeLibraryClientView(true,
			ResourceLocation.withDefaultNamespace("jungle"), 2, "Ignored",
			LibraryStructureScanner.State.VALID, false, 3, 1, 1, 16, 2).save());
		valid.putString("LibraryId", "not-an-nbt-uuid");
		CompoundTag before = authoritativeSnapshot(target);
		UUID identity = target.memberId();
		RecipeLibraryClientView clientState = target.clientState();

		target.read(valid, REGISTRIES, true);

		assertAll(
			() -> assertEquals(identity, target.memberId()),
			() -> assertEquals(clientState, target.clientState()),
			() -> assertEquals(before, authoritativeSnapshot(target)));
	}

	@Test
	void serverStateRoundTripsAndCorruptChildrenAreIsolatedLosslessly() {
		LibraryBlockEntity source = seededCore();
		CompoundTag saved = new CompoundTag();
		source.write(saved, REGISTRIES, false);
		assertEquals(Set.of("PatternLibrary"), saved.getAllKeys());
		assertEquals(Set.of("LibraryId", "BindingState", "BindingStateInvalid", "ServerState"),
			patternData(saved).getAllKeys());
		assertEquals(Set.of("LibrarianSnapshotBox", "PendingSafeRelease", "StructureState",
			"StructureSnapshot", "PageTopologies", "PatternIndex", "RoutingConfiguration",
			"MovementClaimToken"),
			serverState(saved).getAllKeys());
		assertFalse(saved.contains("ClientState"));

		LibraryBlockEntity restored = core();
		restored.read(saved, REGISTRIES, false);
		CompoundTag roundTripped = new CompoundTag();
		restored.write(roundTripped, REGISTRIES, false);
		assertEquals(saved, roundTripped);

		LibraryStructureScanner.Snapshot retained = restored.structureSnapshot();
		CompoundTag corruptStructure = saved.copy();
		serverState(corruptStructure).put("StructureSnapshot", new CompoundTag());
		restored.read(corruptStructure, REGISTRIES, false);
		assertEquals(retained, restored.structureSnapshot());

		CompoundTag corruptIndexChild = saved.copy();
		serverState(corruptIndexChild).getCompound("PatternIndex").getCompound("RetiredProductionSearch")
			.getList("Queries", Tag.TAG_COMPOUND).getCompound(0).remove("QueryId");
		LibraryBlockEntity isolated = core();
		isolated.read(corruptIndexChild, REGISTRIES, false);
		assertAll(
			() -> assertEquals(frozenBinding(source), frozenBinding(isolated)),
			() -> assertEquals(source.structureSnapshot(), isolated.structureSnapshot()),
			() -> assertEquals(1, archivedCount(isolated, "Queries")),
			() -> assertEquals(1, archivedCount(isolated, "Replies")));

		CompoundTag corruptBox = saved.copy();
		CompoundTag raw = new CompoundTag();
		raw.putString("RecoveryMarker", "retain-this-exact-tag");
		serverState(corruptBox).put("LibrarianSnapshotBox", raw);
		LibraryBlockEntity recovery = core();
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
		LibraryBlockEntity unknownRecovery = core();
		unknownRecovery.read(unknownType, REGISTRIES, false);
		CompoundTag unknownResaved = new CompoundTag();
		unknownRecovery.write(unknownResaved, REGISTRIES, false);
		assertTrue(unknownRecovery.pendingSafeRelease());
		assertEquals(unknownRaw, serverState(unknownResaved)
			.getCompound("LibrarianSnapshotBox"));
	}

	@Test
	void savingIntoAnExistingTagReplacesTheApplicationEnvelopeWithoutMergingRetiredState() {
		var target = seededCore();
		var reused = new CompoundTag();
		target.write(reused, REGISTRIES, false);
		assertTrue(patternData(reused).contains("BindingState"));
		assertTrue(serverState(reused).contains("LibrarianSnapshotBox"));
		assertTrue(serverState(reused).contains("StructureSnapshot"));
		serverState(reused).getCompound("PatternIndex").putString("ObsoleteField", "must disappear");
		reused.putString("OtherBehaviour", "must remain");
		var replacement = new CompoundTag();
		core().write(replacement, REGISTRIES, false);
		target.read(replacement, REGISTRIES, false);
		var expected = new CompoundTag();
		target.write(expected, REGISTRIES, false);
		expected.putString("OtherBehaviour", "must remain");
		target.write(reused, REGISTRIES, false);
		assertEquals(expected, reused);
		var restored = core();
		restored.read(reused, REGISTRIES, false);
		assertEquals(target.memberId(), restored.memberId(), "New envelope remains accepted by strict load");
		assertNull(frozenBinding(restored));
	}

	@Test
	void realLibraryRoutingConfigurationIsRequiredAndRoundTripsInTheCurrentSchema() {
		MinecraftServer server = allocate(DedicatedServer.class);
		ServerCore source = new ServerCore(server);
		assertTrue(source.claimItemConfiguration(server, LibraryCoreItemData.configured(
			CommunicationFrequency.of("27"), 9, UUID.randomUUID(), 1)));
		try {
			assertEquals(new ProductionLibraryRouting(
				CommunicationFrequency.of("27"), 9, 1), source.routingConfiguration());

			CompoundTag saved = new CompoundTag();
			source.write(saved, REGISTRIES, false);
			assertEquals(Set.of("PendingSafeRelease", "StructureState", "PageTopologies",
				"PatternIndex", "RoutingConfiguration", "MovementClaimToken"),
				serverState(saved).getAllKeys());

			LibraryBlockEntity restored = core();
			restored.read(saved, REGISTRIES, false);
			assertEquals(source.routingConfiguration(), restored.routingConfiguration());

			CompoundTag missingCurrentField = saved.copy();
			serverState(missingCurrentField).remove("RoutingConfiguration");
			CompoundTag before = authoritativeSnapshot(restored);
			restored.read(missingCurrentField, REGISTRIES, false);
			assertEquals(before, authoritativeSnapshot(restored),
				"old/default-only Library routing schema must not be accepted");
		} finally {
			source.releaseLibraryIdentityClaim();
		}
	}

	@Test
	void invalidServerStateEnvelopeIsRejectedWithoutPartialMutation() {
		LibraryBlockEntity target = seededCore();
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
		LibraryBlockEntity core = core();
		core.installLibrarianSnapshot(capturedLibrarian("minecraft:desert", 3, "\"Archivist\"", true));
		loadHistoricalBinding(core, binding(core, UUID.randomUUID(), 7));
		PatternPageKey key = new PatternPageKey(new RecipeSourceLocation(SHELF_ADDRESS.dimension(), SHELF_ADDRESS.subLevelId(), SHELF_ADDRESS.localPos()), 0, 0);
		core.libraryIndex().rebuildPageOrder(List.of(key));
		core.libraryIndex().tickFingerprintChecks(ignored -> rawJson,
			new PatternJsonParser(REGISTRIES), 1);
		seedHistory(core, true);
		CompoundTag client = new CompoundTag();
		core.write(client, REGISTRIES, true);

		Set<String> keys = new HashSet<>();
		List<String> strings = new ArrayList<>();
		collect(client, keys, strings);
		assertEquals(Set.of("LibraryId", "ClientState", "NormalGoggles"), client.getAllKeys());
		for (String forbidden : List.of("BindingState", "BindingStateInvalid", "LibrarianSnapshotBox",
			"UUID", "Offers", "Recipes", "Brain", "Inventory", "ServerState", "PatternIndex",
			"PageOrder", "Fingerprint", "Cache", "RetiredProductionSearch", "Queries", "Replies", "Cursor", "NoAI",
			"CapturedEntity"))
			assertFalse(keys.contains(forbidden), forbidden);
		assertFalse(strings.stream().anyMatch(value -> value.contains(rawJson)));
	}

	@Test
	void malformedOrMixedRetiredSearchArchiveCannotReplaceTheLoadedLibrary() {
		for (boolean mixed : List.of(false, true)) {
			var saved = seededCore().saveWithFullMetadata(REGISTRIES);
			var index = serverState(saved).getCompound("PatternIndex");
			assertTrue(index.contains("RetiredProductionSearch", Tag.TAG_COMPOUND));
			if (mixed) index.put("Queries", new ListTag());
			else index.putString("RetiredProductionSearch", "not a compound");
			var target = seededCore();
			var before = authoritativeSnapshot(target);
			target.loadWithComponents(saved, REGISTRIES);
			assertEquals(before, authoritativeSnapshot(target));
		}
	}

	@Test void freshLibraryHasNoProductionQueueAndUnknownHistoricalRecordsStayPrivateAfterReindex() {
		var core = core();
		var disk = core.saveWithFullMetadata(REGISTRIES);
		var index = serverState(disk).getCompound("PatternIndex");
		assertFalse(index.contains("Queries"), "A new Library must not create a production query queue");
		assertFalse(index.contains("RetiredProductionSearch"));
		var old = new CompoundTag(); old.putString("FutureField", "unknown order");
		var requests = new ListTag(); requests.add(old);
		index.put("Queries", requests); index.putString("Replies", "untouched even if malformed");
		index.putLong("Generation", 7L);
		core.loadWithComponents(disk, REGISTRIES);
		old.putString("FutureField", "changed caller input");
		var pages = core.libraryIndex();
		pages.rebuildPageOrder(List.of(new PatternPageKey(new RecipeSourceLocation(SHELF_ADDRESS.dimension(), SHELF_ADDRESS.subLevelId(), SHELF_ADDRESS.localPos()), 0, 0)));
		var saved = serverState(core.saveWithFullMetadata(REGISTRIES)).getCompound("PatternIndex");
		assertFalse(saved.contains("Queries")); assertFalse(saved.contains("Replies"));
		var frozen = saved.getCompound("RetiredProductionSearch");
		assertEquals(7L, frozen.getLong("Generation")); assertNotEquals(7L, saved.getLong("Generation"));
		assertEquals("unknown order", frozen.getList("Queries", Tag.TAG_COMPOUND).getCompound(0).getString("FutureField"));
		assertEquals("untouched even if malformed", frozen.getString("Replies"));
		var restored = core(); restored.loadWithComponents(core.saveWithFullMetadata(REGISTRIES), REGISTRIES);
		assertEquals(frozen, serverState(restored.saveWithFullMetadata(REGISTRIES)).getCompound("PatternIndex").getCompound("RetiredProductionSearch"));
		assertFalse(restored.getUpdateTag(REGISTRIES).toString().contains("unknown order"));
	}

	private static CompoundTag diskIndex(LibraryBlockEntity core) {
		return serverState(core.saveWithFullMetadata(REGISTRIES)).getCompound("PatternIndex");
	}

	private static int archivedCount(LibraryBlockEntity core, String key) {
		return diskIndex(core).getCompound("RetiredProductionSearch").getList(key, Tag.TAG_COMPOUND).size();
	}

	private static void flattenForLegacyFixture(CompoundTag index) {
		var history = index.getCompound("RetiredProductionSearch");
		index.remove("RetiredProductionSearch");
		for (String key : List.of("Queries", "Replies", "ReplyRequesterCursor"))
			if (history.contains(key)) index.put(key, history.get(key));
	}

	private static void seedHistory(LibraryBlockEntity core, boolean readyReply) {
		var historical = new com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.ProductionLibraryQueries(core.libraryIndex());
		historical.enqueue(query(Items.IRON_INGOT));
		if (readyReply) { historical.advanceSearch(1); historical.enqueue(query(Items.GOLD_INGOT)); }
		var disk = core.saveWithFullMetadata(REGISTRIES);
		serverState(disk).put("PatternIndex", ProductionLibraryIndexArchive.save(historical, REGISTRIES));
		core.loadWithComponents(disk, REGISTRIES);
	}

	private static LibraryBlockEntity seededCore() {
		LibraryBlockEntity core = core();
		core.installLibrarianSnapshot(capturedLibrarian("minecraft:swamp", 4, "\"Server name\"", true));
		loadHistoricalBinding(core, binding(core, UUID.randomUUID(), 3));
		core.applyStructureScan(new LibraryStructureScanner.ScanResult(
			LibraryStructureScanner.State.VALID, coreOnlySnapshot()));
		seedHistory(core, true);
		return core;
	}

	private static ReadyCore coreWithRetainedWork(LibraryStructureScanner.ScanResult valid,
		BlockPos shelf) {
		ReadyCore core = new ReadyCore();
		core.applyStructureScan(valid, Map.of(shelf, 0), 0);
		seedHistory(core, true);
		return core;
	}

	private static CompoundTag zeroMaskCurrentState() {
		BlockPos shelf = new BlockPos(1, 0, 0);
		LibraryStructureScanner.ScanResult valid = new LibraryStructureScanner.ScanResult(
			LibraryStructureScanner.State.VALID, snapshotWithChiseledShelf());
		ReadyCore source = new ReadyCore(new SpaceAddress(Level.OVERWORLD, null, BlockPos.ZERO));
		source.applyStructureScan(valid, Map.of(shelf, 0), 0);
		seedHistory(source, true);
		return source.saveWithFullMetadata(REGISTRIES);
	}

	private static LibraryStructureScanner.View scannerView(
		Map<BlockPos, LibraryStructureScanner.MemberKind> members) {
		return new LibraryStructureScanner.View() {
			@Override public boolean isLoaded(BlockPos pos) { return true; }
			@Override public boolean sameSpace(BlockPos first, BlockPos second) { return true; }
			@Override public LibraryStructureScanner.MemberKind memberAt(BlockPos pos) {
				return members.getOrDefault(pos, LibraryStructureScanner.MemberKind.NONE);
			}
		};
	}

	private static CompoundTag authoritativeSnapshot(LibraryBlockEntity core) {
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

	private static boolean topologyRefreshPending(LibraryBlockEntity core) {
		return core.diagnosticTopologyRefreshPending();
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

	private static ClusterBinding binding(LibraryBlockEntity core, UUID clusterId,
		long revision) {
		return new ClusterBinding(clusterId, revision,
			new ClusterAuthority(ClusterMemberType.PATTERN_CORE, core.memberId()),
			List.of(new LogisticsBinding(UUID.randomUUID())));
	}

	private static LibraryStructureScanner.Snapshot coreOnlySnapshot() {
		return new LibraryStructureScanner.Snapshot(
			List.of(BlockPos.ZERO), List.of(), List.of(), BlockPos.ZERO, BlockPos.ZERO);
	}

	private static LibraryStructureScanner.Snapshot snapshotWithChiseledShelf() {
		BlockPos shelf = new BlockPos(1, 0, 0);
		return new LibraryStructureScanner.Snapshot(
			List.of(BlockPos.ZERO, shelf), List.of(), List.of(shelf), BlockPos.ZERO, shelf);
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

	private static LibraryBlockEntity core() {
		bootstrap();
		return new LibraryBlockEntity(BlockEntityType.FURNACE, BlockPos.ZERO,
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

	private static final class SyncTrackingCore extends LibraryBlockEntity {
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

	private static final class ReadyCore extends LibraryBlockEntity {
		private boolean shelfEntitiesReadable = true;
		private final SpaceAddress currentSpace;

		private ReadyCore() {
			this(new SpaceAddress(Level.OVERWORLD, SPACE, BlockPos.ZERO));
		}

		private ReadyCore(SpaceAddress currentSpace) {
			super(BlockEntityType.FURNACE, BlockPos.ZERO, Blocks.FURNACE.defaultBlockState());
			this.currentSpace = currentSpace;
			bootstrap();
		}

		@Override
		boolean indexAccessReady() {
			return structureState() == LibraryStructureScanner.State.VALID
				&& !topologyRefreshPending(this);
		}

		@Override
		boolean shelfEntityReadable(BlockPos ignored) {
			return shelfEntitiesReadable;
		}

		@Override
		SpaceAddress currentLibrarySpace() {
			return currentSpace;
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<MinecraftServer, LibraryMovementLeases>
		loadedMovementLeaseData() throws ReflectiveOperationException {
		Field field = LibraryMovementLeases.class.getDeclaredField("LOADED");
		field.setAccessible(true);
		return (Map<MinecraftServer, LibraryMovementLeases>) field.get(null);
	}

	private static final class ServerCore extends LibraryBlockEntity {
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

	private static final class InstallProbe
		implements LibraryResident.CaptureAccess {
		private final LibraryResident.InstallResult result;
		private final ClearMode mode;
		private int inspections;
		private int clears;
		private boolean clearedBeforeSync;
		private ItemStack clearedStack = ItemStack.EMPTY;

		private InstallProbe(LibraryResident.InstallResult result,
			ClearMode mode) {
			this.result = result;
			this.mode = mode;
		}

		private static InstallProbe success() {
			return new InstallProbe(LibraryResident.InstallResult.SUCCESS,
				ClearMode.SUCCESS);
		}

		private static InstallProbe noOpClear() {
			return new InstallProbe(LibraryResident.InstallResult.SUCCESS,
				ClearMode.NO_OP);
		}

		private static InstallProbe partialClearFailure() {
			return new InstallProbe(LibraryResident.InstallResult.SUCCESS,
				ClearMode.PARTIAL_FAILURE);
		}

		private static InstallProbe throwingClear() {
			return new InstallProbe(LibraryResident.InstallResult.SUCCESS,
				ClearMode.THROW_CLEAR);
		}

		private static InstallProbe throwingSync() {
			return new InstallProbe(LibraryResident.InstallResult.SUCCESS,
				ClearMode.THROW_SYNC);
		}

		private static InstallProbe rejected(
			LibraryResident.InstallResult result) {
			return new InstallProbe(result, ClearMode.SUCCESS);
		}

		@Override
		public boolean isBox(ItemStack stack) {
			return result != LibraryResident.InstallResult.NOT_BOX;
		}

		@Override
		public boolean hasCapturedEntity(ItemStack stack) {
			return result != LibraryResident.InstallResult.EMPTY_BOX
				&& CapturedEntityBoxHelper.hasCapturedEntity(stack);
		}

		@Override
		public LibraryResident.InstallResult inspect(ItemStack copied) {
			inspections++;
			return result;
		}

		@Override
		public boolean clearCapturedEntity(ItemStack held) {
			clears++;
			clearedStack = held;
			return switch (mode) {
				case SUCCESS, THROW_SYNC -> {
					CapturedEntityBoxHelper.clearCapturedEntity(held);
					yield true;
				}
				case NO_OP -> true;
				case PARTIAL_FAILURE -> {
					CapturedEntityBoxHelper.clearCapturedEntity(held);
					yield false;
				}
				case THROW_CLEAR -> {
					CapturedEntityBoxHelper.clearCapturedEntity(held);
					throw new IllegalStateException("clear failed after partial mutation");
				}
			};
		}

		public void synchronize() {
			clearedBeforeSync = !CapturedEntityBoxHelper.hasCapturedEntity(clearedStack);
			if (mode == ClearMode.THROW_SYNC)
				throw new IllegalStateException("sendData failed");
		}

		private enum ClearMode { SUCCESS, NO_OP, PARTIAL_FAILURE, THROW_CLEAR, THROW_SYNC }
	}
}
