package com.nobodiiiii.createbiotech.content.factorycluster.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.FactoryStateRepresentationBounds;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentIdentity;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentRole;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentStatus;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentType;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressObserver;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.LogisticsNetworkDirectory;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.LogisticsNetworkDirectoryEntry;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WritableLevelData;

class LogisticsDirectoryProductionCompositionTest {
	private static final FactoryRuntimeConfig CONFIG = new FactoryRuntimeConfig(1, 500, 4,
		32, 4, 4, 32, 64, 20, 10, 100, 3, 32, 8, 16, false);

	@Test
	void oneCapacityRefusedLiveNetworkDoesNotBlockObserverOrFollowingFactoryProgression()
		throws Exception {
		bootstrap();
		CallbackServer callback = callbackServer();
		UUID retainedNetwork = id(1);
		UUID refusedNetwork = id(FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES + 1L);
		FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		LogisticsNetworkDirectory.State fullDirectory = fullDirectory();
		journal.write("logistics_network_directory/global", fullDirectory, RegistryAccess.EMPTY);
		LogisticsAddressObserver.State retainedObserver = retainedObserver(retainedNetwork);
		journal.write("logistics_address_observer/global", retainedObserver, RegistryAccess.EMPTY);

		try (FactoryRuntimeService runtime = new FactoryRuntimeService(CONFIG)) {
			runtime.start();
			try (FactoryOrchestrationServerTestAccess production =
				FactoryOrchestrationServerTestAccess.installWithLogisticsScan(callback.server(),
					CONFIG, runtime, journal, packager -> Set.of(), () -> Set.of(refusedNetwork))) {
				assertDoesNotThrow(() -> FactoryOrchestrationServer.tick(callback.server()));
				assertEquals(20, production.radioTopologySampleTick(),
					"the post-logistics radio topology refresh must still run");
				assertEquals(ComponentStatus.OFFLINE,
					FactoryOrchestrationServer.logisticsAddressSnapshot(callback.server(),
						retainedNetwork).getFirst().status());
				assertEquals(20, journal.logisticsAddressObserver(RegistryAccess.EMPTY,
					CONFIG.logisticsAddressRetentionCapacity()).state().lastPublicationTick());
				assertFalse(journal.logisticsNetworkDirectory(RegistryAccess.EMPTY)
					.find(refusedNetwork).isPresent());
				assertEquals(fullDirectory,
					journal.logisticsNetworkDirectory(RegistryAccess.EMPTY).snapshot());
			}
		}
	}

	private static LogisticsNetworkDirectory.State fullDirectory() {
		LinkedHashMap<UUID, LogisticsNetworkDirectoryEntry> entries = new LinkedHashMap<>();
		for (long ordinal = 1;
			ordinal <= FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES; ordinal++) {
			UUID network = id(ordinal);
			entries.put(network, new LogisticsNetworkDirectoryEntry(network, ordinal,
				Optional.empty(), 0));
		}
		return new LogisticsNetworkDirectory.State(entries,
			FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES + 1L);
	}

	private static LogisticsAddressObserver.State retainedObserver(UUID network) {
		ComponentIdentity identity = new ComponentIdentity(new SpaceAddress(Level.OVERWORLD, null,
			new BlockPos(1, 64, 1)), "packager");
		LogisticsAddressComponent component = new LogisticsAddressComponent(network, identity,
			"Retained", ComponentType.PACKAGER, Set.of(ComponentRole.ORDINARY), Optional.empty(),
			0, 0, ComponentStatus.ONLINE);
		return new LogisticsAddressObserver.State(Map.of(
			new LogisticsAddressObserver.NetworkComponentKey(network, identity), component), 1, -1);
	}

	private static CallbackServer callbackServer() throws ReflectiveOperationException {
		MinecraftServer server = allocate(DedicatedServer.class);
		setInstanceField(server, "serverThread", Thread.currentThread());
		ServerLevel level = allocate(ServerLevel.class);
		setInstanceField(level, "server", server);
		ServerLevelData levelData = (ServerLevelData) Proxy.newProxyInstance(
			ServerLevelData.class.getClassLoader(),
			new Class<?>[] { ServerLevelData.class, WritableLevelData.class },
			(proxy, method, ignored) -> method.getName().equals("getGameTime") ? 20L
				: primitiveDefault(method.getReturnType()));
		setInstanceField(level, "levelData", levelData);
		setInstanceField(level, "serverLevelData", levelData);
		setInstanceField(server, "levels", Map.of(Level.OVERWORLD, level));
		return new CallbackServer(server);
	}

	@SuppressWarnings("unchecked")
	private static <T> T allocate(Class<T> type) throws ReflectiveOperationException {
		Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		unsafeField.setAccessible(true);
		return (T) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(type);
	}

	private static void setInstanceField(Object target, String name, Object value)
		throws ReflectiveOperationException {
		for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
			try {
				Field field = type.getDeclaredField(name);
				field.setAccessible(true);
				field.set(target, value);
				return;
			} catch (NoSuchFieldException ignored) {}
		}
		throw new NoSuchFieldException(name);
	}

	private static Object primitiveDefault(Class<?> type) {
		if (!type.isPrimitive()) return null;
		if (type == boolean.class) return false;
		if (type == char.class) return '\0';
		if (type == byte.class) return (byte) 0;
		if (type == short.class) return (short) 0;
		if (type == int.class) return 0;
		if (type == long.class) return 0L;
		if (type == float.class) return 0F;
		if (type == double.class) return 0D;
		return null;
	}

	private static void bootstrap() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) == null)
				type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
					.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
			net.minecraft.SharedConstants.tryDetectVersion();
			Field field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			field.setAccessible(true);
			field.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	private static UUID id(long value) { return new UUID(0, value); }

	private record CallbackServer(MinecraftServer server) {}
}
