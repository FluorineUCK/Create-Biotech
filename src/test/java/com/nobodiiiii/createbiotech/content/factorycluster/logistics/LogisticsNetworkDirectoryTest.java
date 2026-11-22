package com.nobodiiiii.createbiotech.content.factorycluster.logistics;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.LogisticsNetworkDirectory;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.LogisticsNetworkDirectoryEntry;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.FactoryStateRepresentationBounds;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerMonitorSnapshot;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationSavedData;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

class LogisticsNetworkDirectoryTest {
	@Test
	void aliasCanonicalizationIsIdempotentAndRejectsInvisibleOrNormalizedBlankValues() {
		assertEquals("Main Warehouse", LogisticsNetworkDirectoryEntry.canonicalAlias(
			"\u00a0Main Warehouse\u00a0"));
		String canonical = LogisticsNetworkDirectoryEntry.canonicalAlias("Ｍａｉｎ");
		assertEquals(canonical, LogisticsNetworkDirectoryEntry.canonicalAlias(canonical));
		assertThrows(IllegalArgumentException.class,
			() -> LogisticsNetworkDirectoryEntry.canonicalAlias("\u00a0"));
		assertThrows(IllegalArgumentException.class,
			() -> LogisticsNetworkDirectoryEntry.canonicalAlias("\u200b"));
		assertThrows(IllegalArgumentException.class,
			() -> LogisticsNetworkDirectoryEntry.canonicalAlias("\ufe0f"));
		assertThrows(IllegalArgumentException.class,
			() -> LogisticsNetworkDirectoryEntry.canonicalAlias("\u2065"));
		assertThrows(IllegalArgumentException.class,
			() -> LogisticsNetworkDirectoryEntry.canonicalAlias("Main\u2065"));
		assertEquals("Main", ComputerMonitorSnapshot.safeText("Main\u2065"));
	}

	@Test
	void aliasCasRejectsCanonicalSemanticNoOpWithoutRevisionOrPersistence() {
		UUID network = id(90);
		AtomicInteger writes = new AtomicInteger();
		LogisticsNetworkDirectory directory = LogisticsNetworkDirectory.fromState(
			new LogisticsNetworkDirectory.State(Map.of(network,
				new LogisticsNetworkDirectoryEntry(network, 1, Optional.of("Alpha"), 4)), 2),
			ignored -> writes.incrementAndGet());

		assertFalse(directory.compareAndSetAlias(network, 4, Optional.of("  Ａｌｐｈａ  ")));
		assertEquals(4, directory.find(network).orElseThrow().revision());
		assertEquals(Optional.of("Alpha"), directory.find(network).orElseThrow().customAlias());
		assertEquals(0, writes.get());
	}
	@Test
	void defaultLogisticsNameIsLocalizedWithoutExposingTheNetworkUuid() throws Exception {
		JsonObject english = language("en_us.json");
		JsonObject chinese = language("zh_cn.json");
		String key = "create_biotech.factory_cluster.logistics.default_name";
		UUID network = UUID.fromString("dcb9b8b0-d585-43e2-b61f-070afbcdd2a9");

		assertTrue(english.has(key));
		assertTrue(chinese.has(key));
		assertEquals("Logistics Network #1", english.get(key).getAsString().formatted(1));
		assertEquals("物流网络 #1", chinese.get(key).getAsString().formatted(1));
		assertFalse(english.get(key).getAsString().contains(network.toString()));
		assertFalse(chinese.get(key).getAsString().contains(network.toString()));
	}

	@Test
	void ordinalsPersistAndNeverReuse() {
		UUID a = id(1), b = id(2), c = id(3);
		LogisticsNetworkDirectory directory = new LogisticsNetworkDirectory();

		assertEquals(1, directory.ensure(a).ordinal());
		assertEquals(2, directory.ensure(b).ordinal());
		CompoundTag saved = directory.save();
		LogisticsNetworkDirectory loaded = LogisticsNetworkDirectory.load(saved);
		loaded.forgetObservedComponent(a);
		assertEquals(3, loaded.ensure(c).ordinal());
		Object defaultName = LogisticsNetworkDirectory.displayName(loaded.find(a).orElseThrow());
		assertTrue(defaultName.toString().contains(
			"create_biotech.factory_cluster.logistics.default_name"));
		assertEquals(1, loaded.find(a).orElseThrow().ordinal());
	}

	@Test
	void duplicateUuidIsIdempotentAndNeverConsumesAnotherOrdinal() {
		LogisticsNetworkDirectory directory = new LogisticsNetworkDirectory();
		UUID network = id(11);

		LogisticsNetworkDirectoryEntry first = directory.ensure(network);
		assertEquals(first, directory.ensure(network));
		assertEquals(2, directory.ensure(id(12)).ordinal());
	}

	@Test
	void failedPersistenceDoesNotPublishAProspectiveIdentity() {
		UUID network = id(13);
		LogisticsNetworkDirectory directory = LogisticsNetworkDirectory.fromState(
			new LogisticsNetworkDirectory.State(Map.of(), 1), ignored -> {
				throw new IllegalStateException("persistence unavailable");
			});

		assertThrows(IllegalStateException.class, () -> directory.ensure(network));
		assertTrue(directory.find(network).isEmpty());
		assertEquals(1, directory.snapshot().nextOrdinal());
		assertThrows(IllegalStateException.class, () -> directory.ensure(network));
		assertTrue(directory.find(network).isEmpty());
		assertEquals(1, directory.snapshot().nextOrdinal());
	}

	@Test
	void exactlyFullDirectoryRefusesANewIdentityWithoutPersistingOrChangingLiveState() {
		Map<UUID, LogisticsNetworkDirectoryEntry> entries = fullEntries();
		LogisticsNetworkDirectory.State initial = new LogisticsNetworkDirectory.State(entries,
			FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES + 1L);
		AtomicInteger writes = new AtomicInteger();
		LogisticsNetworkDirectory directory = LogisticsNetworkDirectory.fromState(initial,
			ignored -> writes.incrementAndGet());

		LogisticsNetworkDirectory.AllocationResult existing = directory.tryEnsure(id(1));
		LogisticsNetworkDirectory.AllocationResult refused = directory.tryEnsure(
			id(FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES + 1L));

		assertEquals(LogisticsNetworkDirectory.AllocationStatus.EXISTING, existing.status());
		assertEquals(entries.get(id(1)), existing.entry().orElseThrow());
		assertEquals(LogisticsNetworkDirectory.AllocationStatus.CAPACITY_REFUSED,
			refused.status());
		assertEquals(Optional.empty(), refused.entry());
		assertEquals(0, writes.get());
		assertEquals(initial, directory.snapshot());
	}

	@Test
	void persistenceCapacityRefusalDoesNotPublishAProspectiveIdentity() {
		UUID network = id(17);
		LogisticsNetworkDirectory directory = LogisticsNetworkDirectory.fromStateWithPersistenceResult(
			new LogisticsNetworkDirectory.State(Map.of(), 1), ignored ->
				LogisticsNetworkDirectory.PersistenceResult.CAPACITY_REFUSED);

		LogisticsNetworkDirectory.AllocationResult refused = directory.tryEnsure(network);

		assertEquals(LogisticsNetworkDirectory.AllocationStatus.PERSISTENCE_REFUSED,
			refused.status());
		assertEquals(Optional.empty(), refused.entry());
		assertTrue(directory.find(network).isEmpty());
		assertEquals(1, directory.snapshot().nextOrdinal());
	}

	@Test
	void fullValidSavedDataJournalReportsPersistenceRefusalWithoutPublishing()
		throws ReflectiveOperationException {
		FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		Map<String, CompoundTag> journalEntries = journalEntries(journal);
		CompoundTag validSingleton = CurrentSchemaStateNbt.save(
			new LogisticsNetworkDirectory.State(Map.of(), 1), RegistryAccess.EMPTY);
		for (int index = 0; index < journalCapacity(); index++)
			journalEntries.put("fixture/" + index, validSingleton.copy());
		LogisticsNetworkDirectory directory = journal.logisticsNetworkDirectory(RegistryAccess.EMPTY);
		LogisticsNetworkDirectory.State initial = directory.snapshot();

		LogisticsNetworkDirectory.AllocationResult refused = directory.tryEnsure(id(18));

		assertEquals(LogisticsNetworkDirectory.AllocationStatus.PERSISTENCE_REFUSED,
			refused.status());
		assertEquals(initial, directory.snapshot());
		assertTrue(directory.find(id(18)).isEmpty());
		assertTrue(journal.persistenceValid());
	}

	@Test
	void invalidSavedDataJournalRemainsFatalInsteadOfBecomingAllocationRefusal()
		throws ReflectiveOperationException {
		FactoryOrchestrationSavedData journal = reloadJournal(new CompoundTag());
		assertFalse(journal.persistenceValid());
		LogisticsNetworkDirectory directory = journal.logisticsNetworkDirectory(RegistryAccess.EMPTY);

		assertThrows(IllegalStateException.class, () -> directory.tryEnsure(id(19)));
		assertTrue(directory.find(id(19)).isEmpty());
		assertEquals(1, directory.snapshot().nextOrdinal());
	}

	@Test
	void savedDataRestoresTheSingleGlobalDirectoryAuthority() throws ReflectiveOperationException {
		UUID a = id(14), b = id(15), c = id(16);
		FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		LogisticsNetworkDirectory directory = journal.logisticsNetworkDirectory(RegistryAccess.EMPTY);
		directory.ensure(a);
		directory.ensure(b);

		CompoundTag saved = journal.save(new CompoundTag(), RegistryAccess.EMPTY);
		FactoryOrchestrationSavedData reloaded = reloadJournal(saved);
		LogisticsNetworkDirectory restored = reloaded.logisticsNetworkDirectory(RegistryAccess.EMPTY);
		assertEquals(1, restored.find(a).orElseThrow().ordinal());
		assertEquals(3, restored.ensure(c).ordinal());
	}

	@Test
	void refusesAllocationWhenTheNextOrdinalCannotAdvance() {
		CompoundTag encoded = new CompoundTag();
		encoded.putLong("NextOrdinal", Long.MAX_VALUE);
		encoded.put("Entries", new ListTag());
		LogisticsNetworkDirectory directory = LogisticsNetworkDirectory.load(encoded);

		assertEquals(LogisticsNetworkDirectory.AllocationStatus.CAPACITY_REFUSED,
			directory.tryEnsure(id(21)).status());
		assertTrue(directory.find(id(21)).isEmpty());
		assertEquals(Long.MAX_VALUE, directory.snapshot().nextOrdinal());
	}

	@Test
	void rejectsCollectionsPastTheSharedRepresentationBound() {
		CompoundTag encoded = new CompoundTag();
		encoded.putLong("NextOrdinal", 2);
		ListTag entries = new ListTag();
		for (int index = 0; index <= FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES;
			index++) entries.add(validEntry(id(index + 1), 1));
		encoded.put("Entries", entries);

		assertThrows(IllegalArgumentException.class, () -> LogisticsNetworkDirectory.load(encoded));
	}

	@Test
	void rejectsDuplicateOrdinalsInStateAndDirectCodec() {
		UUID first = id(25), second = id(26);
		assertThrows(IllegalArgumentException.class, () -> new LogisticsNetworkDirectory.State(Map.of(
			first, new LogisticsNetworkDirectoryEntry(first, 1, Optional.empty(), 0),
			second, new LogisticsNetworkDirectoryEntry(second, 1, Optional.empty(), 0)), 2));

		CompoundTag encoded = new CompoundTag();
		encoded.putLong("NextOrdinal", 2);
		ListTag entries = new ListTag();
		entries.add(validEntry(first, 1));
		entries.add(validEntry(second, 1));
		encoded.put("Entries", entries);
		assertThrows(IllegalArgumentException.class, () -> LogisticsNetworkDirectory.load(encoded));
	}

	@Test
	void directCodecRejectsMistypedPresentCustomAlias() {
		CompoundTag encoded = new CompoundTag();
		encoded.putLong("NextOrdinal", 2);
		CompoundTag entry = validEntry(id(27), 1);
		entry.putInt("CustomAlias", 42);
		ListTag entries = new ListTag();
		entries.add(entry);
		encoded.put("Entries", entries);

		assertThrows(IllegalArgumentException.class, () -> LogisticsNetworkDirectory.load(encoded));
	}

	@Test
	void persistenceUsesNativeUuidValuesRatherThanUuidSubstrings() {
		UUID network = UUID.fromString("f7dc36c7-aab3-40c4-854e-eb9940bae6d8");
		CompoundTag saved = new LogisticsNetworkDirectory().save();
		LogisticsNetworkDirectory directory = LogisticsNetworkDirectory.load(saved);
		directory.ensure(network);
		saved = directory.save();

		assertFalse(saved.toString().contains(network.toString()));
		CompoundTag entry = (CompoundTag) ((ListTag) saved.get("Entries")).get(0);
		assertTrue(entry.hasUUID("NetworkId"));
	}

	@Test
	void entryNormalizesAliasesAndRejectsInvalidValues() {
		assertEquals(Optional.of("Custom"), new LogisticsNetworkDirectoryEntry(id(31), 1,
			Optional.of("  Custom  "), 0).customAlias());
		assertThrows(IllegalArgumentException.class, () -> new LogisticsNetworkDirectoryEntry(id(32), 0,
			Optional.empty(), 0));
		assertThrows(IllegalArgumentException.class, () -> new LogisticsNetworkDirectoryEntry(id(33), 1,
			Optional.empty(), -1));
		assertThrows(IllegalArgumentException.class, () -> new LogisticsNetworkDirectoryEntry(id(34), 1,
			Optional.of("x".repeat(65)), 0));
	}

	private static CompoundTag validEntry(UUID network, long ordinal) {
		CompoundTag entry = new CompoundTag();
		entry.putUUID("NetworkId", network);
		entry.putLong("Ordinal", ordinal);
		entry.putLong("Revision", 0);
		return entry;
	}

	private static UUID id(long value) { return new UUID(0, value); }

	private static Map<UUID, LogisticsNetworkDirectoryEntry> fullEntries() {
		LinkedHashMap<UUID, LogisticsNetworkDirectoryEntry> entries = new LinkedHashMap<>();
		for (long ordinal = 1;
			ordinal <= FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES; ordinal++) {
			UUID network = id(ordinal);
			entries.put(network, new LogisticsNetworkDirectoryEntry(network, ordinal,
				Optional.empty(), 0));
		}
		return entries;
	}

	private static JsonObject language(String filename) throws Exception {
		return JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/create_biotech/lang",
			filename))).getAsJsonObject();
	}

	private static FactoryOrchestrationSavedData reloadJournal(CompoundTag root)
		throws ReflectiveOperationException {
		var load = FactoryOrchestrationSavedData.class.getDeclaredMethod("load", CompoundTag.class,
			net.minecraft.core.HolderLookup.Provider.class);
		load.setAccessible(true);
		return (FactoryOrchestrationSavedData) load.invoke(null, root, RegistryAccess.EMPTY);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, CompoundTag> journalEntries(FactoryOrchestrationSavedData journal)
		throws ReflectiveOperationException {
		Field entries = FactoryOrchestrationSavedData.class.getDeclaredField("entries");
		entries.setAccessible(true);
		return (Map<String, CompoundTag>) entries.get(journal);
	}

	private static int journalCapacity() throws ReflectiveOperationException {
		Field capacity = FactoryOrchestrationSavedData.class.getDeclaredField("MAX_ENTRIES");
		capacity.setAccessible(true);
		return capacity.getInt(null);
	}
}
