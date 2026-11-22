package com.nobodiiiii.createbiotech.content.biocomputing.application.production.dispatch;

import static org.junit.jupiter.api.Assertions.*;
import java.io.DataInputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationSavedData;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentInputPhase;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.NativeOrderLedger;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RecoveryLedger;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.CreateDispatchCommand;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.CreateLogisticsGateway;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.item.Items;

class ProductionSafeRetryStoreTest {
    @BeforeAll static void bootstrap() throws Exception {
        Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (type.getMethod("get").invoke(null) == null)
            type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
                .invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var boot = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        boot.setAccessible(true); boot.setBoolean(null, true); Items.AIR.getDefaultInstance();
    }
    @Test void actualLegacyProofLoadsUnderNewOwnerAndKeepsExactHistoricalEncoding() throws Exception {
        CompoundTag original = archive("legacy-proof.nbt");
        SafeCreateRetryProof proof = CurrentSchemaStateNbt.load(original, RegistryAccess.EMPTY,
            SafeCreateRetryProof.class).orElseThrow(() -> new AssertionError("new owner must decode the actual old proof"));
        assertEquals(original, CurrentSchemaStateNbt.save(proof, RegistryAccess.EMPTY),
            "including old binary names, schema, fields and item components");
    }
    @Test void actualLegacyJournalColdRecoveryOwnsOneProofWithoutChangingAnyEntry() throws Exception {
        CompoundTag original = archive("legacy-journal.nbt");
        var journal = cold(original);
        var store = new ProductionSafeRetryStore(journal, RegistryAccess.EMPTY);
        store.recover();
        var proof = CurrentSchemaStateNbt.load(archive("legacy-proof.nbt"), RegistryAccess.EMPTY,
            SafeCreateRetryProof.class).orElseThrow();
        assertEquals(proof, store.get(proof.intent().runId()), "new owner must install the exact old proof");
        assertEquals(1, store.size());
        assertEquals(original, journal.save(new CompoundTag(), RegistryAccess.EMPTY));
    }
    @Test void changedSafeDiagnosisPersistsLatestWithoutReplacingExactAuthority() throws Exception {
        var journal = cold(archive("legacy-journal.nbt"));
        var store = new ProductionSafeRetryStore(journal, RegistryAccess.EMPTY);
        var prior = proof();
        store.recover();
        for (var reason : List.of(CreateLogisticsGateway.Rejection.SOURCE_SNAPSHOT_MISMATCH,
            CreateLogisticsGateway.Rejection.NATIVE_DISPATCH_REJECTED)) {
            var artifact = rejected(prior.command(), reason);
            journal.inputCommitStore(RegistryAccess.EMPTY).persist(artifact);
            var latest = SafeCreateRetryProof.from(prior.intent(), artifact).orElseThrow();
            store.persistAndInstall(prior.intent(), artifact, latest);
            store.persistAndInstall(prior.intent(), artifact, latest);
            assertEquals(1, store.size());
            assertTrue(prior.sameAuthority(store.get(prior.intent().runId())));
            assertEquals(reason, store.get(prior.intent().runId()).rejection());
            var coldJournal = cold(journal.save(new CompoundTag(), RegistryAccess.EMPTY));
            var coldStore = new ProductionSafeRetryStore(coldJournal, RegistryAccess.EMPTY);
            coldStore.recover();
            assertEquals(latest, coldStore.get(prior.intent().runId()));
        }
    }
    @Test void bootstrapUpdatesStaleDiagnosisFromActualGatewayThenIsIdempotent() throws Exception {
        var journal = cold(archive("legacy-journal.nbt"));
        var prior = proof();
        var artifact = rejected(prior.command(), CreateLogisticsGateway.Rejection.NATIVE_DISPATCH_REJECTED);
        journal.inputCommitStore(RegistryAccess.EMPTY).persist(artifact);
        var store = new ProductionSafeRetryStore(journal, RegistryAccess.EMPTY);
        store.recover();
        var latest = SafeCreateRetryProof.from(prior.intent(), artifact).orElseThrow();
        assertEquals(latest, store.get(prior.intent().runId()));
        assertEquals(latest, journal.read(key(prior), SafeCreateRetryProof.class, RegistryAccess.EMPTY).orElseThrow());
        CompoundTag once = journal.save(new CompoundTag(), RegistryAccess.EMPTY);
        store.recover();
        assertEquals(1, store.size());
        assertEquals(once, journal.save(new CompoundTag(), RegistryAccess.EMPTY));
    }
    @Test void competingProviderAuthorityFailsBeforeAnyJournalWriteOrLiveInstall() throws Exception {
        var journal = cold(archive("legacy-journal.nbt"));
        var store = new ProductionSafeRetryStore(journal, RegistryAccess.EMPTY);
        store.recover();
        var prior = proof();
        var changed = withAgent(prior.command(), UUID.randomUUID());
        var artifact = rejected(changed, prior.rejection());
        var conflict = SafeCreateRetryProof.from(prior.intent(), artifact).orElseThrow();
        CompoundTag before = journal.save(new CompoundTag(), RegistryAccess.EMPTY);
        assertThrows(IllegalStateException.class, () -> store.installLatest(conflict));
        assertThrows(IllegalStateException.class, () -> store.persistAndInstall(prior.intent(), artifact, conflict));
        assertEquals(prior, store.get(prior.intent().runId()));
        assertEquals(1, store.size());
        assertEquals(before, journal.save(new CompoundTag(), RegistryAccess.EMPTY));
    }
    @Test void exactTypedManifestIdentityAndOverflowRejectWithoutAuthority() throws Exception {
        var prior = proof();
        var artifact = rejected(prior.command(), prior.rejection());
        var plain = new StackKey(Items.IRON_INGOT.getDefaultInstance());
        assertNotEquals(plain, prior.command().manifest().keySet().iterator().next());
        var mismatch = withIngredients(prior.intent(), List.of(new OrderedIngredient(plain, 3)));
        assertTrue(SafeCreateRetryProof.from(mismatch, artifact).isEmpty());
        var exact = prior.command().manifest().keySet().iterator().next();
        var overflow = withIngredients(prior.intent(), List.of(new OrderedIngredient(exact, Long.MAX_VALUE),
            new OrderedIngredient(exact, 1)));
        assertTrue(SafeCreateRetryProof.from(overflow, artifact).isEmpty());
        assertTrue(SafeCreateRetryProof.from(prior.intent(),
            rejected(prior.command().withIntentId(UUID.randomUUID()), prior.rejection())).isEmpty());
        assertTrue(SafeCreateRetryProof.from(prior.intent(),
            rejected(prior.command().withFence(18), prior.rejection())).isEmpty());
        assertTrue(SafeCreateRetryProof.from(prior.intent(),
            rejected(prior.command().withSourceInventorySnapshotGeneration(18), prior.rejection())).isEmpty());
    }
    @Test void onlyThreeSafeRejectionsCanBecomeProofAndAttemptingNeverDoes() throws Exception {
        var prior = proof();
        for (var reason : CreateLogisticsGateway.Rejection.values()) {
            if (reason == CreateLogisticsGateway.Rejection.NONE) continue;
            boolean safe = Set.of(CreateLogisticsGateway.Rejection.PACKAGER_UNLOADED,
                CreateLogisticsGateway.Rejection.SOURCE_SNAPSHOT_MISMATCH,
                CreateLogisticsGateway.Rejection.NATIVE_DISPATCH_REJECTED).contains(reason);
            var artifact = rejected(prior.command(), reason);
            assertEquals(safe, SafeCreateRetryProof.from(prior.intent(), artifact).isPresent(), reason.name());
            assertEquals(safe, SafeCreateRetryProof.repair(prior.intent(), artifact, Optional.empty(),
                new RecoveryLedger.State(Map.of(), Map.of(), Map.of(), Map.of())).isPresent(), reason.name());
            if (!safe) assertThrows(IllegalArgumentException.class,
                () -> new SafeCreateRetryProof(prior.intent(), prior.command(), reason));
        }
        assertTrue(SafeCreateRetryProof.from(prior.intent(),
            CreateLogisticsGateway.PersistedCommit.attempting(prior.command())).isEmpty());
    }
    @Test void missingGatewayAndWrongRunKeyFailClosedWithoutPublishingOrDeletingProof() throws Exception {
        var prior = proof();
        for (boolean wrongKey : List.of(false, true)) {
            var journal = cold(archive("legacy-journal.nbt"));
            if (wrongKey) {
                journal.remove(key(prior));
                journal.write(ProductionSafeRetryStore.JOURNAL_PREFIX + UUID.randomUUID(), prior, RegistryAccess.EMPTY);
            } else journal.remove("input_commit/" + prior.intent().intentId());
            CompoundTag before = journal.save(new CompoundTag(), RegistryAccess.EMPTY);
            var store = new ProductionSafeRetryStore(journal, RegistryAccess.EMPTY);
            var failure = assertThrows(IllegalStateException.class, store::recover);
            assertTrue(failure.getMessage().contains(wrongKey ? "key differs from Run" : "no gateway artifact"));
            assertEquals(0, store.size());
            assertEquals(before, journal.save(new CompoundTag(), RegistryAccess.EMPTY));
        }
    }
    @Test void gatewayCommandAndManifestMismatchCannotRestoreHistoricalAuthority() throws Exception {
        var prior = proof();
        for (boolean manifestMismatch : List.of(false, true)) {
            var journal = cold(archive("legacy-journal.nbt"));
            var c = prior.command();
            var changed = manifestMismatch
                ? new CreateDispatchCommand(c.intentId(), c.agentId(), c.agentResetGeneration(), c.fence(),
                    c.runId(), c.outputObservationEpoch(), c.stageIndex(), c.route(),
                    c.sourceInventorySnapshotGeneration(), Map.of(new StackKey(Items.GOLD_INGOT.getDefaultInstance()), 3L))
                : withAgent(c, UUID.randomUUID());
            journal.inputCommitStore(RegistryAccess.EMPTY).persist(rejected(changed, prior.rejection()));
            CompoundTag before = journal.save(new CompoundTag(), RegistryAccess.EMPTY);
            var store = new ProductionSafeRetryStore(journal, RegistryAccess.EMPTY);
            assertThrows(IllegalStateException.class, store::recover);
            assertEquals(0, store.size());
            assertEquals(before, journal.save(new CompoundTag(), RegistryAccess.EMPTY));
        }
    }
    @Test void uncertainAndAttemptingArtifactsRetireRetryButPreserveTheActualGatewayEvidence() throws Exception {
        var prior = proof();
        for (var artifact : List.of(CreateLogisticsGateway.PersistedCommit.attempting(prior.command()),
            rejected(prior.command(), CreateLogisticsGateway.Rejection.NATIVE_DISPATCH_UNCERTAIN),
            rejected(prior.command(), CreateLogisticsGateway.Rejection.DURABLE_STORE_UNAVAILABLE))) {
            var journal = cold(archive("legacy-journal.nbt"));
            journal.inputCommitStore(RegistryAccess.EMPTY).persist(artifact);
            var store = new ProductionSafeRetryStore(journal, RegistryAccess.EMPTY);
            store.recover();
            assertEquals(0, store.size());
            assertTrue(journal.read(key(prior), SafeCreateRetryProof.class, RegistryAccess.EMPTY).isEmpty());
            assertEquals(artifact, journal.inputCommitStore(RegistryAccess.EMPTY).load(prior.intent().intentId()).orElseThrow());
        }
    }
    @Test void actualGatewaySuccessIsExactlyOnceAndCannotBeRestoredAsSafeRetry() throws Exception {
        var journal = cold(archive("legacy-journal.nbt"));
        var prior = proof(); var c = prior.command(); int[] attempts = {0};
        var gateway = new CreateLogisticsGateway(ignored -> new CreateLogisticsGateway.CommitContext(
            c.agentId(), c.agentResetGeneration(), c.fence(), c.route(), true,
            c.sourceInventorySnapshotGeneration(), AgentInputPhase.DISPATCH_INTENT, c), ignored -> {
                attempts[0]++;
                return new CreateLogisticsGateway.NativeDispatchFact(true,
                    List.of(new NativeOrderLedger.NativeOrderIdentity(21, Set.of(0))));
            }, () -> true, journal.inputCommitStore(RegistryAccess.EMPTY));
        assertTrue(gateway.commit(c).committed()); assertTrue(gateway.commit(c).committed());
        assertEquals(1, attempts[0]);
        var artifact = journal.inputCommitStore(RegistryAccess.EMPTY).load(c.intentId()).orElseThrow();
        assertTrue(artifact.provesMaterialCommit());
        assertTrue(SafeCreateRetryProof.from(prior.intent(), artifact).isEmpty());
        var store = new ProductionSafeRetryStore(cold(journal.save(new CompoundTag(), RegistryAccess.EMPTY)), RegistryAccess.EMPTY);
        store.recover();
        assertEquals(0, store.size());
    }
    @Test void failedJournalPersistenceCannotPublishNewDiagnostic() throws Exception {
        var prior = proof();
        var invalid = cold(new CompoundTag());
        assertFalse(invalid.persistenceValid());
        var store = new ProductionSafeRetryStore(invalid, RegistryAccess.EMPTY);
        store.installLatest(prior);
        var artifact = rejected(prior.command(), CreateLogisticsGateway.Rejection.NATIVE_DISPATCH_REJECTED);
        var latest = SafeCreateRetryProof.from(prior.intent(), artifact).orElseThrow();
        assertThrows(IllegalStateException.class, () -> store.persistAndInstall(prior.intent(), artifact, latest));
        assertEquals(prior, store.get(prior.intent().runId()));
        assertEquals(1, store.size());
    }
    @Test void codecRemainsStrictAboutHistoricalNamesFieldsAndRejectionValues() throws Exception {
        for (int mutation = 0; mutation < 5; mutation++) {
            CompoundTag tag = archive("legacy-proof.nbt");
            if (mutation == 0) tag.putString("Type", SafeCreateRetryProof.class.getName());
            if (mutation == 1) tag.getCompound("Value").putString("Type", SafeCreateRetryProof.class.getName());
            if (mutation == 2) tag.getCompound("Value").putInt("foreign", 1);
            if (mutation == 3) tag.getCompound("Value").remove("command");
            if (mutation == 4) tag.getCompound("Value").getCompound("rejection").putString("Value", "NONE");
            assertTrue(CurrentSchemaStateNbt.load(tag, RegistryAccess.EMPTY, SafeCreateRetryProof.class).isEmpty(),
                "malformed or foreign encoding " + mutation);
        }
    }
    @Test void conditionalRemovalDoesNotDropNewerDiagnosisAndBatchRetirementPreservesCommitArtifact() throws Exception {
        var journal = cold(archive("legacy-journal.nbt"));
        var store = new ProductionSafeRetryStore(journal, RegistryAccess.EMPTY);
        var prior = proof(); store.recover();
        var latest = new SafeCreateRetryProof(prior.intent(), prior.command(), CreateLogisticsGateway.Rejection.NATIVE_DISPATCH_REJECTED);
        store.installLatest(latest);
        assertFalse(store.remove(prior.intent().runId(), prior));
        assertEquals(latest, store.get(prior.intent().runId()));
        assertTrue(store.remove(prior.intent().runId(), latest));
        assertFalse(store.containsKey(prior.intent().runId()));
        journal.writeBatchAndRemoveActiveRunArtifacts(Map.of(), prior.intent().runId(), RegistryAccess.EMPTY);
        var retired = new ProductionSafeRetryStore(cold(journal.save(new CompoundTag(), RegistryAccess.EMPTY)), RegistryAccess.EMPTY);
        retired.recover(); assertEquals(0, retired.size());
        assertTrue(journal.inputCommitStore(RegistryAccess.EMPTY).load(prior.intent().intentId()).isPresent());
    }
    @Test void realHostOwnsNoShadowRetryMapAndRestoresOnlyAtOriginalBootstrapPoint() throws Exception {
        String source = Files.readString(Path.of("src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/FactoryOrchestrationServer.java"));
        assertTrue(source.contains("private final ProductionSafeRetryStore safeCreateRetries;"));
        assertTrue(source.contains("this.safeCreateRetries = new ProductionSafeRetryStore(journal, registryAccess);"));
        assertFalse(source.contains("record SafeCreateRetry"));
        assertFalse(source.contains("Map<UUID, SafeCreateRetry"));
        assertFalse(source.contains("persistSafeCreateRetry("));
        assertFalse(source.contains("requireCompatibleSafeCreateRetry("));
        assertFalse(source.contains("installLatestSafeCreateRetry("));
        assertFalse(source.contains("persistAndInstallSafeCreateRetry("));
        int plans = source.indexOf("planRepository.recover();");
        int retries = source.indexOf("safeCreateRetries.recover();");
        int libraries = source.indexOf("List<DurableProductionLibrary> recoveredLibraries");
        assertTrue(plans >= 0 && retries > plans && libraries > retries);
        assertEquals(1, source.split("safeCreateRetries\\.recover\\(\\);", -1).length - 1);
        assertEquals(5, source.split("safeCreateRetries\\.persistAndInstall\\(", -1).length - 1);
        assertTrue(source.contains("return SafeCreateRetryProof.repair(intent, persisted, input, recoveryState);"));
    }
    private static SafeCreateRetryProof proof() throws Exception {
        return CurrentSchemaStateNbt.load(archive("legacy-proof.nbt"), RegistryAccess.EMPTY, SafeCreateRetryProof.class).orElseThrow();
    }
    private static String key(SafeCreateRetryProof proof) {
        return ProductionSafeRetryStore.JOURNAL_PREFIX + proof.intent().runId();
    }
    private static CreateLogisticsGateway.PersistedCommit rejected(CreateDispatchCommand command, CreateLogisticsGateway.Rejection reason) {
        return CreateLogisticsGateway.PersistedCommit.completed(command, CreateLogisticsGateway.CommitResult.rejected(command, reason));
    }
    private static CreateDispatchCommand withAgent(CreateDispatchCommand c, UUID agent) {
        return new CreateDispatchCommand(c.intentId(), agent, c.agentResetGeneration(), c.fence(), c.runId(),
            c.outputObservationEpoch(), c.stageIndex(), c.route(), c.sourceInventorySnapshotGeneration(), c.manifest());
    }
    private static ProductionEffect.CreateDispatch withIngredients(ProductionEffect.CreateDispatch i, List<OrderedIngredient> ingredients) {
        return new ProductionEffect.CreateDispatch(i.intentId(), i.clusterId(), i.generation(), i.fence(),
            i.processId(), i.runId(), i.route(), ingredients);
    }
    private static FactoryOrchestrationSavedData cold(CompoundTag archive) throws Exception {
        var load = FactoryOrchestrationSavedData.class.getDeclaredMethod("load", CompoundTag.class,
            HolderLookup.Provider.class);
        load.setAccessible(true);
        return (FactoryOrchestrationSavedData) load.invoke(null, archive, RegistryAccess.EMPTY);
    }
    private static CompoundTag archive(String name) throws Exception {
        try (var input = new DataInputStream(ProductionSafeRetryStoreTest.class.getResourceAsStream(name))) {
            return NbtIo.read(input);
        }
    }
}
