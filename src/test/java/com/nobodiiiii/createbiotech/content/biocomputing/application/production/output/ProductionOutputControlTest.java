package com.nobodiiiii.createbiotech.content.biocomputing.application.production.output;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.core.component.DataComponents;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt;

class ProductionOutputControlTest {
    @BeforeAll static void bootstrap() throws Exception {
        Class<?> type=Class.forName("net.neoforged.fml.loading.LoadingModList");
        if(type.getMethod("get").invoke(null)==null)
            type.getMethod("of",List.class,List.class,List.class,List.class,Map.class)
                .invoke(null,List.of(),List.of(),List.of(),List.of(),Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var field=net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        field.setAccessible(true);field.setBoolean(null,true);net.minecraft.world.item.Items.AIR.getDefaultInstance();
    }
    @Test void actualHistoricalFrozenPolicyLoadsAsNewOwnerWithoutChangingItsNbtOrComponents() throws Exception {
        var original=archive("policy");
        var policy=CurrentSchemaStateNbt.load(original,RegistryAccess.EMPTY,ProductionOutputPolicy.class).orElseThrow();
        assertEquals(1,policy.requiredOutputs().size());
        assertEquals(1,policy.primaryOutput().orElseThrow().stack().get(DataComponents.MAX_STACK_SIZE));
        assertEquals(original,CurrentSchemaStateNbt.save(policy,RegistryAccess.EMPTY));
        assertTrue(original.toString().contains("factorycluster.agent.AgentOutputPolicy"));
        assertFalse(original.toString().contains("production.output.ProductionOutputPolicy"));
    }
    @Test void legacySealedConfirmedAndReceivedStatesRestoreWithoutTrustingOldProcessLeases() throws Exception {
        var policy=CurrentSchemaStateNbt.load(archive("policy"),RegistryAccess.EMPTY,ProductionOutputPolicy.class).orElseThrow();
        for(String name:List.of("sealed","confirmed","received")) {
            var original=archive(name);
            var snapshot=CurrentSchemaStateNbt.load(original,RegistryAccess.EMPTY,ProductionOutputContract.Snapshot.class).orElseThrow();
            var restored=ProductionOutputContract.restore(snapshot,policy,Optional.empty(),new ProductionOutputContract.Store() {
                public void persist(ProductionOutputContract.Snapshot state) {throw new AssertionError("no eager persistence");}
                public void persistLedger(ProductionOutputLedger ledger) {throw new AssertionError("no new world proof");}
            });
            assertEquals(snapshot,restored.snapshot());assertEquals(original,CurrentSchemaStateNbt.save(restored.snapshot(),RegistryAccess.EMPTY));
            if(name.equals("sealed")) {
                assertEquals(1,restored.pendingOutputCount());
                assertTrue(restored.nextDispatch().instruction().isEmpty());
                assertEquals(snapshot.dispatchIntent(),restored.snapshot().dispatchIntent());
                assertFalse(restored.inputDispatchAllowed());
            }
            if(name.equals("confirmed"))assertEquals(0,restored.pendingOutputCount());
            if(name.equals("received")) {
                assertEquals(1,snapshot.lateWarehouseMaterial().size());assertEquals(1,snapshot.acceptedAgentReceipts().size());
                assertEquals(ProductionOutputContract.Milestone.OUTPUT_RECEIVED,restored.milestone());
            }
        }
    }
    @Test void completeLegacyJournalAndOpaqueGatewayEvidenceLoadThroughActualStorageWithoutReencodingLoss() throws Exception {
        var original=archive("journal");
        var type=com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationSavedData.class;
        var load=type.getDeclaredMethod("load",CompoundTag.class,net.minecraft.core.HolderLookup.Provider.class);
        load.setAccessible(true);
        var cold=(com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationSavedData)
            load.invoke(null,original,RegistryAccess.EMPTY);
        var instruction=CurrentSchemaStateNbt.load(archive("instruction"),RegistryAccess.EMPTY,
            ProductionOutputContract.DispatchInstruction.class).orElseThrow();
        var run=instruction.runId();
        var policy=cold.read("output_policy/"+run,ProductionOutputPolicy.class,RegistryAccess.EMPTY).orElseThrow();
        var snapshot=cold.read("output_contract/"+run,ProductionOutputContract.Snapshot.class,RegistryAccess.EMPTY).orElseThrow();
        var ledger=cold.read("output_ledger/"+run,ProductionOutputLedger.class,RegistryAccess.EMPTY).orElseThrow();
        var commit=cold.outputCommitStore(RegistryAccess.EMPTY).load(instruction.intentId()).orElseThrow();
        assertEquals(instruction,commit.instruction());assertTrue(commit.result().orElseThrow().operation().isPresent());
        assertEquals(instruction,commit.result().orElseThrow().confirmationReceipt().orElseThrow().instruction());
        assertEquals(ProductionOutputContract.Milestone.OUTPUT_RECEIVED,snapshot.milestone());
        assertEquals(ledger.dispatchedEvents(),snapshot.dispatchedEvents());
        assertEquals(archive("policy"),CurrentSchemaStateNbt.save(policy,RegistryAccess.EMPTY));
        assertEquals(archive("received"),CurrentSchemaStateNbt.save(snapshot,RegistryAccess.EMPTY));
        assertEquals(archive("ledger"),CurrentSchemaStateNbt.save(ledger,RegistryAccess.EMPTY));
        assertTrue(cold.persistenceValid());assertEquals(original,cold.save(new CompoundTag(),RegistryAccess.EMPTY));
        assertTrue(cold.read("unrelated/output-control",ProductionOutputContract.ProcessLease.class,RegistryAccess.EMPTY).isPresent());
    }
    @Test void historicalOpaqueCommitReplaysWithoutRecapturingOrMovingAndConfirmsExactlyOnce() throws Exception {
        var sealed=CurrentSchemaStateNbt.load(archive("sealed"),RegistryAccess.EMPTY,
            ProductionOutputContract.Snapshot.class).orElseThrow();
        var policy=CurrentSchemaStateNbt.load(archive("policy"),RegistryAccess.EMPTY,
            ProductionOutputPolicy.class).orElseThrow();
        var persisted=new java.util.ArrayList<ProductionOutputContract.Snapshot>();
        var contract=ProductionOutputContract.restore(sealed,policy,Optional.empty(),new ProductionOutputContract.Store() {
            public void persist(ProductionOutputContract.Snapshot state) {persisted.add(state);}
            public void persistLedger(ProductionOutputLedger ledger) {fail("confirmation is not drain proof");}
        });
        var gateway=com.nobodiiiii.createbiotech.content.factorycluster.createbridge.OutputLogisticsGateway.create(
            () -> true,new com.nobodiiiii.createbiotech.content.factorycluster.createbridge.OutputLogisticsGateway.Authority() {
                public ProductionOutputContract.Snapshot captureContract() {throw new AssertionError("durable replay needs no recapture");}
                public com.nobodiiiii.createbiotech.content.factorycluster.createbridge.OutputLogisticsGateway.ReadinessState captureReadiness(
                    com.nobodiiiii.createbiotech.content.factorycluster.createbridge.OutputLogisticsGateway.ReadinessRequest request) {throw new AssertionError();}
                public com.nobodiiiii.createbiotech.content.factorycluster.createbridge.OutputLogisticsGateway.DrainState captureDrain(
                    com.nobodiiiii.createbiotech.content.factorycluster.createbridge.OutputLogisticsGateway.DrainRequest request) {throw new AssertionError();}
            },ignored -> {throw new AssertionError("cold commit must not move world items again");},
            loadJournal(archive("journal")).outputCommitStore(RegistryAccess.EMPTY));
        var instruction=sealed.dispatchIntent().orElseThrow();
        var receipt=gateway.commit(instruction).confirmationReceipt().orElseThrow();
        assertTrue(contract.confirmDispatch(receipt));assertFalse(contract.confirmDispatch(receipt));
        assertEquals(1,persisted.size());assertEquals(0,contract.pendingOutputCount());
        assertEquals(archive("confirmed"),CurrentSchemaStateNbt.save(contract.snapshot(),RegistryAccess.EMPTY));
        assertEquals(instruction,gateway.commit(instruction).confirmationReceipt().orElseThrow().instruction());
    }
    @Test void durableEventLedgerRecoversStaleSealedStateWithoutResendOrEagerPersistence() throws Exception {
        var sealed=CurrentSchemaStateNbt.load(archive("sealed"),RegistryAccess.EMPTY,
            ProductionOutputContract.Snapshot.class).orElseThrow();
        var ledger=CurrentSchemaStateNbt.load(archive("ledger"),RegistryAccess.EMPTY,
            ProductionOutputLedger.class).orElseThrow();
        var policy=CurrentSchemaStateNbt.load(archive("policy"),RegistryAccess.EMPTY,
            ProductionOutputPolicy.class).orElseThrow();
        var contract=ProductionOutputContract.restore(sealed,policy,Optional.of(ledger),noWrites());
        assertEquals(ProductionOutputContract.Milestone.OUTPUT_DISPATCHED,contract.milestone());
        assertEquals(0,contract.pendingOutputCount());assertTrue(contract.nextDispatch().instruction().isEmpty());
        assertEquals(ledger.dispatchedEvents(),contract.snapshot().dispatchedEvents());
        assertEquals(ledger.dispatchedOutputs(),contract.snapshot().dispatchedOutputs());
        assertEquals(ledger.creditedOutputs(),contract.snapshot().creditedOutputs());
        assertTrue(contract.snapshot().dispatchIntent().isEmpty());assertTrue(contract.snapshot().agentReleased());
    }
    @Test void historicalLedgerCannotCrossTheFrozenPolicyGeneration() throws Exception {
        var sealed=CurrentSchemaStateNbt.load(archive("sealed"),RegistryAccess.EMPTY,
            ProductionOutputContract.Snapshot.class).orElseThrow();
        var ledger=CurrentSchemaStateNbt.load(archive("ledger"),RegistryAccess.EMPTY,
            ProductionOutputLedger.class).orElseThrow();
        var policy=CurrentSchemaStateNbt.load(archive("policy"),RegistryAccess.EMPTY,
            ProductionOutputPolicy.class).orElseThrow();
        var foreign=new ProductionOutputLedger(ledger.runId(),ledger.goalId(),ledger.fence(),
            ledger.outputObservationEpoch(),ledger.outputPolicyGeneration()+1,ledger.returnWarehouseRoute(),
            ledger.dispatchedEvents(),ledger.dispatchedOutputs(),ledger.creditedOutputs(),ledger.sealFact());
        assertThrows(IllegalArgumentException.class,() -> ProductionOutputContract.restore(sealed,policy,
            Optional.of(foreign),noWrites()));
    }
    @Test void ledgerRejectsAggregateOrCreditRewritesAndNonPositivePhysicalCounts() throws Exception {
        var ledger=CurrentSchemaStateNbt.load(archive("ledger"),RegistryAccess.EMPTY,
            ProductionOutputLedger.class).orElseThrow();
        assertThrows(IllegalArgumentException.class,() -> new ProductionOutputLedger(ledger.runId(),ledger.goalId(),
            ledger.fence(),ledger.outputObservationEpoch(),ledger.outputPolicyGeneration(),ledger.returnWarehouseRoute(),
            ledger.dispatchedEvents(),Map.of(),ledger.creditedOutputs(),ledger.sealFact()));
        assertThrows(IllegalArgumentException.class,() -> new ProductionOutputLedger(ledger.runId(),ledger.goalId(),
            ledger.fence(),ledger.outputObservationEpoch(),ledger.outputPolicyGeneration(),ledger.returnWarehouseRoute(),
            ledger.dispatchedEvents(),ledger.dispatchedOutputs(),Map.of(),ledger.sealFact()));
        var event=ledger.dispatchedEvents().values().iterator().next();
        assertThrows(IllegalArgumentException.class,() -> new ProductionOutputLedger.DispatchedEvent(event.eventId(),
            event.nativeOperationId(),event.destination(),event.stack(),0,event.credited()));
        assertThrows(UnsupportedOperationException.class,() -> ledger.dispatchedEvents().clear());
    }
    @Test void relocationDoesNotAcceptNewBinaryNamesOrBroadenRecordReflection() throws Exception {
        var tag=archive("policy");tag.putString("Type",ProductionOutputPolicy.class.getName());
        assertTrue(CurrentSchemaStateNbt.load(tag,RegistryAccess.EMPTY,ProductionOutputPolicy.class).isEmpty());
        tag=archive("policy");tag.getCompound("Value").putString("Type",ProductionOutputContract.class.getName());
        assertTrue(CurrentSchemaStateNbt.load(tag,RegistryAccess.EMPTY,ProductionOutputPolicy.class).isEmpty());
        tag=archive("policy");tag.getCompound("Value").putString("foreign","world-object");
        assertTrue(CurrentSchemaStateNbt.load(tag,RegistryAccess.EMPTY,ProductionOutputPolicy.class).isEmpty());
        tag=archive("policy");assertTrue(tag.getCompound("Value").contains("generation"));
        tag.getCompound("Value").remove("generation");
        assertTrue(CurrentSchemaStateNbt.load(tag,RegistryAccess.EMPTY,ProductionOutputPolicy.class).isEmpty());
    }
    @Test void actualGatewayAuthorityAndHostConsumersUseNewOwnerAndOldOwnersAreAbsent() throws Exception {
        var authority=com.nobodiiiii.createbiotech.content.factorycluster.createbridge.OutputLogisticsGateway.Authority.class;
        assertEquals(ProductionOutputContract.Snapshot.class,authority.getMethod("captureContract").getReturnType());
        for(String old:List.of("AgentOutputPolicy","AgentOutputContract","OutputDispatchLedger"))
            assertThrows(ClassNotFoundException.class,() -> Class.forName(
                "com.nobodiiiii.createbiotech.content.factorycluster.agent."+old));
        var host=java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/FactoryOrchestrationServer.java"));
        assertTrue(host.contains("new ProductionOutputContract("));assertTrue(host.contains("ProductionOutputContract.restore("));
        assertTrue(host.contains("OutputPolicyState.class"));assertTrue(host.contains("ProductionOutputLedger.class"));
        assertEquals(ProductionOutputPolicy.class,
            com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer.OutputPolicyState.class
                .getMethod("policy").getReturnType());
        assertFalse(host.contains("AgentOutputContract"));assertFalse(host.contains("OutputDispatchLedger"));
    }
    static ProductionOutputContract.Store noWrites() {
        return new ProductionOutputContract.Store() {
            public void persist(ProductionOutputContract.Snapshot state) {throw new AssertionError("no eager persistence");}
            public void persistLedger(ProductionOutputLedger ledger) {throw new AssertionError("no fabricated drain proof");}
        };
    }
    static com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationSavedData loadJournal(CompoundTag tag) throws Exception {
        var type=com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationSavedData.class;
        var load=type.getDeclaredMethod("load",CompoundTag.class,net.minecraft.core.HolderLookup.Provider.class);
        load.setAccessible(true);return (com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationSavedData)
            load.invoke(null,tag,RegistryAccess.EMPTY);
    }
    static CompoundTag archive(String name) throws Exception {
        var path="/com/nobodiiiii/createbiotech/content/biocomputing/application/production/output/legacy-"+name+".nbt";
        try(var input=ProductionOutputControlTest.class.getResourceAsStream(path)) {
            assertNotNull(input,path);return NbtIo.read(new java.io.DataInputStream(input));
        }
    }
}
