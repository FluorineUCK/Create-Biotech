package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.ProductionRuntimeServices;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryCoreItemData;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternPageKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternJsonParser;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberIndex;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.factorycluster.LogisticsBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.ProductionRecipeCatalog;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationSavedData;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer.DurableProductionLibrary;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServerTestAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;

class LibraryPublicationTest {
	private static final FactoryRuntimeConfig CONFIG = new FactoryRuntimeConfig(1, 500, 4,
		32, 4, 4, 32, 64, 20, 10, 100, 3, 32, 16);
	private static final SpaceAddress ADDRESS = new SpaceAddress(Level.OVERWORLD, null, BlockPos.ZERO);

	@BeforeAll
	static void bootstrap() throws ReflectiveOperationException {
		LibraryBlockEntityTest.bootstrapMinecraft();
        net.minecraft.world.item.Items.AIR.getDefaultInstance();
        net.minecraft.core.registries.BuiltInRegistries.ITEM.getOrCreateTag(net.minecraft.tags.TagKey.create(
            net.minecraft.core.registries.Registries.ITEM, net.minecraft.resources.ResourceLocation.parse("c:nuggets/gold")));
		Class<?> loading = Class.forName("net.neoforged.fml.loading.LoadingModList");
		if (loading.getMethod("get").invoke(null) == null)
			loading.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
				.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
		net.minecraft.SharedConstants.tryDetectVersion();
		field(net.minecraft.server.Bootstrap.class, "isBootstrapped").setBoolean(null, true);
	}

	@Test
	void corePublicationUsesDirectoryRevisionRatherThanBindingRevision() throws Exception {
		try (Fixture fixture = new Fixture(true)) {
			fixture.core.publishRuntimeCatalog(true);
			DurableProductionLibrary published = fixture.registration();
			assertEquals(7, published.bindingRevision());
			assertEquals(0, published.directoryRevision());
			assertEquals(0L, fixture.workerRevision(),
				"the Core must not pre-publish binding revision 7 into the directory revision stream");
			assertEquals(published, fixture.journal.read(fixture.key(), DurableProductionLibrary.class,
				RegistryAccess.EMPTY).orElseThrow());
			assertSame(published.catalog(), fixture.workerCatalog());
		}
	}

	@Test
	void unchangedCatalogDoesNotRepublishAndLoadTransitionsKeepOneRevisionStream() throws Exception {
		try (Fixture fixture = new Fixture(true)) {
			fixture.core.publishRuntimeCatalog(true);
			DurableProductionLibrary first = fixture.registration();
			fixture.core.publishRuntimeCatalog(true);
			assertSame(first, fixture.registration(), "unchanged publication must retain the directory entry");
			assertSame(first.catalog(), fixture.workerCatalog(), "unchanged Core ticks must not overwrite the worker snapshot");

			fixture.core.publishRuntimeCatalog(false);
			assertEquals(1, fixture.registration().directoryRevision());
			assertEquals(1L, fixture.workerRevision());
			assertFalse(fixture.workerCatalog().loaded());
			fixture.core.publishRuntimeCatalog(true);
			assertEquals(2, fixture.registration().directoryRevision());
			assertEquals(2L, fixture.workerRevision());
			assertTrue(fixture.workerCatalog().loaded());
		}
	}

	@Test
	void incompleteIndexPublishesUnavailableCatalogInsteadOfRetainingSearchableState() throws Exception {
		try (Fixture fixture = new Fixture(true)) {
			fixture.core.publishRuntimeCatalog(true);
			assertTrue(fixture.registration().catalog().loaded());
			fixture.core.libraryIndex().rebuildPageOrder(List.of(new PatternPageKey(new RecipeSourceLocation(ADDRESS.dimension(), ADDRESS.subLevelId(), ADDRESS.localPos()), 0, 0)));

			fixture.core.publishRuntimeCatalog(true);

			assertFalse(fixture.registration().catalog().loaded(),
				"an indexing Core must journal the same unavailable catalog that the worker sees");
			assertEquals(1, fixture.registration().directoryRevision());
			assertEquals(1L, fixture.workerRevision());
			assertFalse(fixture.workerCatalog().loaded());
			assertSame(fixture.registration().catalog(), fixture.workerCatalog());
		}
	}

	@Test
	void rejectedJournalWriteDoesNotLeakCatalogOrAccessIntoWorker() throws Exception {
		try (Fixture fixture = new Fixture(true)) {
			field(FactoryOrchestrationSavedData.class, "persistenceValid").setBoolean(fixture.journal, false);
			assertThrows(IllegalStateException.class, () -> fixture.core.publishRuntimeCatalog(true));
			assertTrue(FactoryOrchestrationServer.productionLibraryRegistration(fixture.server,
				fixture.core.memberId()).isEmpty());
			assertTrue(fixture.workerMap("libraryCatalogs").isEmpty());
			assertTrue(fixture.workerMap("productionLibraryAccess").isEmpty());
		}
	}

	@Test
	void coreCannotPublishWorkerStateWithoutTheJournalOwningRuntime() throws Exception {
		try (Fixture fixture = new Fixture(false)) {
			fixture.core.publishRuntimeCatalog(true);
			assertTrue(fixture.workerMap("libraryCatalogs").isEmpty());
			assertTrue(fixture.workerMap("productionLibraryAccess").isEmpty());
		}
	}

	private static final class Fixture implements AutoCloseable {
		private final MinecraftServer server = allocate(DedicatedServer.class);
		private final FactoryRuntimeService worker = new FactoryRuntimeService(CONFIG);
		private final FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		private final TestCore core;

		private Fixture(boolean install) throws ReflectiveOperationException {
			field(MinecraftServer.class, "serverThread").set(server, Thread.currentThread());
			core = new TestCore(server);
			assertTrue(core.claimItemConfiguration(server, LibraryCoreItemData.configured(
				CommunicationFrequency.of("17"), 0, core.memberId(), 0)));
			core.commitClusterBinding(new ClusterBinding(UUID.randomUUID(), 7,
				new ClusterAuthority(ClusterMemberType.PATTERN_CORE, core.memberId()),
				List.of(new LogisticsBinding(UUID.randomUUID()))));
			worker.start();
			// Both old and new production paths must target the same real worker, not a mock.
			workers().put(server, worker);
			if (install)
				FactoryOrchestrationServerTestAccess.install(server, CONFIG, worker, journal, ignored -> Set.of());
		}

		private String key() { return "production_library/" + core.memberId(); }
		private void installPattern() {
			PatternPageKey key = new PatternPageKey(new RecipeSourceLocation(ADDRESS.dimension(), ADDRESS.subLevelId(), ADDRESS.localPos()), 0, 0);
			core.libraryIndex().rebuildPageOrder(List.of(key));
			core.libraryIndex().tickFingerprintChecks(ignored ->
				"{\"version\":2,\"type\":\"standard\",\"inputs\":[{\"item\":\"minecraft:iron_nugget\",\"count\":1}],"
					+ "\"outputs\":[{\"item\":\"minecraft:iron_ingot\",\"count\":1}],\"address\":\"test\"}",
				new PatternJsonParser(RegistryAccess.EMPTY), 1);
			assertEquals(1, com.nobodiiiii.createbiotech.content.biocomputing.application.production.ProductionLibrarySession.catalog(core.memberId(), 0, core.libraryIndex(), true).patterns().size());
		}
		private DurableProductionLibrary registration() {
			return FactoryOrchestrationServer.productionLibraryRegistration(server, core.memberId()).orElseThrow();
		}
		private ProductionRecipeCatalog workerCatalog() throws ReflectiveOperationException {
			return (ProductionRecipeCatalog) workerMap("libraryCatalogs").get(core.memberId());
		}
		private long workerRevision() throws ReflectiveOperationException {
			Object access = workerMap("productionLibraryAccess").get(core.memberId());
			return field(access.getClass(), "bindingRevision").getLong(access);
		}
		private Map<?, ?> workerMap(String name) throws ReflectiveOperationException {
			return (Map<?, ?>) field(FactoryRuntimeService.class, name).get(worker);
		}
		@Override public void close() {
			FactoryOrchestrationServer.stop(server);
			ProductionRuntimeServices.close(server);
			ClusterMemberIndex.unregister(server, core);
			core.releaseLibraryIdentityClaim();
			worker.close();
		}
	}

	private static final class TestCore extends LibraryBlockEntity {
		private final MinecraftServer server;
		private TestCore(MinecraftServer server) {
			super(BlockEntityType.FURNACE, BlockPos.ZERO, Blocks.FURNACE.defaultBlockState());
			this.server = server;
		}
		@Override MinecraftServer server() { return server; }
		@Override public SpaceAddress memberAddress() { return ADDRESS; }
		@Override boolean queryAccessReady() { return true; }
	}

	@SuppressWarnings("unchecked")
	private static Map<MinecraftServer, FactoryRuntimeService> workers() throws ReflectiveOperationException {
		return (Map<MinecraftServer, FactoryRuntimeService>) field(ProductionRuntimeServices.class,
			"SERVICES").get(null);
	}
	private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}
	private static <T> T allocate(Class<T> type) throws ReflectiveOperationException {
		return type.cast(((sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null))
			.allocateInstance(type));
	}
}
