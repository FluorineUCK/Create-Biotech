package com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.GoalObservationState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.InventoryIdentity;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationScope;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationService;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.WorldFact;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationSavedData;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ProductionEffectCommitterTest {
    @BeforeAll static void bootstrap() throws Exception {
        Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (type.getMethod("get").invoke(null) == null)
            type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
                .invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        field.setAccessible(true); field.setBoolean(null, true);
        Items.AIR.getDefaultInstance();
    }

    @Test void actualSavedDataPublicationSurvivesArchiveReloadInTheOriginalNamespace() throws Exception {
        var access = new Access();
        var publication = publication(10, "waiting");
        var owner = new ProductionEffectCommitter(access);
        assertTrue(owner.commit(publication));
        assertEquals(List.of("work", "thread", "persist:panel_publication/" + id(10)), access.events);
        var archive = access.journal.save(new CompoundTag(), RegistryAccess.EMPTY);
        var restored = reload(archive);
        assertEquals(publication, restored.read("panel_publication/" + publication.intentId(),
            ProductionEffect.PanelSnapshotPublication.class, RegistryAccess.EMPTY).orElseThrow());
        assertEquals(archive, restored.save(new CompoundTag(), RegistryAccess.EMPTY));
        assertEquals("panel_publication/" + id(10), ProductionEffectCommitter.publicationKey(id(10)));
    }

    @Test void actualStockReaderPublishesActualInventoryNotPromisesWithTheExactEnvelope() {
        var access = new Access(); access.actual=3; access.promises=99;
        access.evidence=networkEvidence(id(1), id(5));
        var before=access.evidence;
        var request=sample(id(5));
        assertTrue(new ProductionEffectCommitter(access).commit(request));
        assertEquals(List.of("work", "thread", "observation", "observe", "publish"), access.events);
        assertEquals(1, access.stockReads);
        assertEquals(before.scope(), access.observedScope);
        assertEquals(List.of(new WorldFact.StockObservation(request.clusterId(), request.fence().epochId(),
            request.generation(), request.requestId(), request.route(), Map.of("minecraft:iron_ingot", 3L))), access.facts);
        assertEquals(before, access.evidence, "reading cannot advance Goal credit or edit evidence");
        assertThrows(UnsupportedOperationException.class, () -> access.facts.getFirst().quantities().clear());
    }

    @Test void checkedCreateAndOutputExecutorsReceiveTheOriginalRequestAndTheirResultIsPreserved() {
        var access=new Access(); var owner=new ProductionEffectCommitter(access);
        var create=new ProductionEffect.CreateDispatch(id(31), id(1), 11,
            new ProductionEffect.TargetFence(id(2), id(3), 7, 9), id(4), new CreateRouteKey(id(5), "input"),
            List.of(new OrderedIngredient(new StackKey(new ItemStack(Items.IRON_INGOT)), 2)));
        var output=new ProductionEffect.OutputPackageRequest(id(32), id(1), 11,
            create.fence(), id(4), create.route(), new StackKey(new ItemStack(Items.GOLD_INGOT)));
        access.operationResult=true;
        assertTrue(owner.commit(create)); assertSame(create, access.seenCreate);
        assertTrue(owner.commit(output)); assertSame(output, access.seenOutput);
        assertEquals(List.of("work", "thread", "create", "work", "thread", "output"), access.events);
        access.operationResult=false;
        assertFalse(owner.commit(create)); assertFalse(owner.commit(output));
        var failure=new IllegalStateException("checked executor unavailable"); access.operationFailure=failure;
        assertSame(failure, assertThrows(IllegalStateException.class, () -> owner.commit(create)));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> owner.commit(output)));
    }

    @Test void stoppedWrongThreadAndReservedEffectsNeverReadPersistOrInvokeExecutors() {
        var access=new Access(); var owner=new ProductionEffectCommitter(access);
        var envelope=publication(10, "waiting"); var fence=envelope.fence();
        List<ProductionEffect> effects=List.of(envelope, sample(id(5)),
            new ProductionEffect.CreateDispatch(id(20), id(1), 11, fence, id(4), new CreateRouteKey(id(5), "input"),
                List.of(new OrderedIngredient(new StackKey(new ItemStack(Items.IRON_INGOT)), 2))),
            new ProductionEffect.OutputPackageRequest(id(21), id(1), 11, fence, id(4),
                new CreateRouteKey(id(5), "output"), new StackKey(new ItemStack(Items.GOLD_INGOT))),
            new ProductionEffect.LibraryQuery(id(22), id(1), 11, fence, id(23), id(24)),
            new ProductionEffect.RadioSend(id(25), id(1), 11, fence, id(26), "payload"),
            new ProductionEffect.AgentTicketOffer(id(27), id(1), 11, fence, id(28), id(29)));
        var before=access.journal.save(new CompoundTag(), RegistryAccess.EMPTY);
        access.work=false;
        for (var effect : effects) assertFalse(owner.commit(effect));
        assertEquals(java.util.Collections.nCopies(7, "work"), access.events);
        access.work=true; access.thread=false; access.events.clear();
        for (var effect : effects) assertFalse(owner.commit(effect));
        assertEquals(14, access.events.size());
        for (int i=0;i<14;i+=2) assertEquals(List.of("work", "thread"), access.events.subList(i,i+2));
        access.thread=true; access.events.clear();
        for (var effect : effects.subList(4,7)) assertFalse(owner.commit(effect));
        assertEquals(List.of("work","thread","work","thread","work","thread"), access.events);
        assertEquals(0, access.stockReads); assertTrue(access.facts.isEmpty());
        assertNull(access.seenCreate); assertNull(access.seenOutput);
        assertEquals(before, access.journal.save(new CompoundTag(), RegistryAccess.EMPTY));
        assertTrue(owner.commit(envelope), "gate state is live, not captured at construction");
    }

    @Test void missingWrongClusterOrWrongNetworkEvidenceIsRejectedBeforeWorldReading() {
        var access=new Access(); var owner=new ProductionEffectCommitter(access);
        var request=sample(id(5));
        assertFalse(owner.commit(request));
        access.evidence=networkEvidence(id(99), id(5)); assertFalse(owner.commit(request));
        access.evidence=networkEvidence(id(1), id(99)); assertFalse(owner.commit(request));
        assertEquals(0, access.stockReads); assertNull(access.observedScope); assertTrue(access.facts.isEmpty());
        assertEquals(List.of("work","thread","observation","work","thread","observation",
            "work","thread","observation"), access.events);
    }

    @Test void unavailableStockIsNotZeroAndQueueRejectionAndFreshSamplingArePreserved() {
        var access=new Access(); var owner=new ProductionEffectCommitter(access);
        access.evidence=networkEvidence(id(1),id(5)); var request=sample(id(5));
        access.loaded=false; access.actual=3; access.promises=99;
        assertFalse(owner.commit(request)); assertTrue(access.facts.isEmpty());
        access.loaded=true; access.actual=0;
        assertTrue(owner.commit(request));
        assertEquals(Map.of("minecraft:iron_ingot",0L), access.facts.getLast().quantities());
        access.publicationAccepted=false; access.actual=3;
        assertFalse(owner.commit(request), "an unaccepted fact must not report effect success");
        assertEquals(Map.of("minecraft:iron_ingot",3L), access.facts.getLast().quantities());
        access.publicationAccepted=true; access.actual=4;
        assertTrue(owner.commit(request));
        assertEquals(Map.of("minecraft:iron_ingot",4L), access.facts.getLast().quantities());
        assertEquals(4,access.stockReads);
        assertEquals(0, access.evidence.creditedCount());
    }

    @Test void attachedInventoryUsesExactIdentityWithoutInventingANetworkConstraint() {
        var access=new Access(); var owner=new ProductionEffectCommitter(access);
        var address=new SpaceAddress(Level.OVERWORLD,null,new BlockPos(2,64,0));
        var identity=new InventoryIdentity(new SpaceAddress(Level.OVERWORLD,null,new BlockPos(3,64,0)),
            Direction.WEST,ResourceLocation.parse("minecraft:chest"),Optional.empty(),id(600),0);
        var scope=new StockObservationScope.AttachedInventory(address,identity,
            new StackKey(new ItemStack(Items.IRON_INGOT)),5);
        access.evidence=new GoalObservationState(id(4),id(1),scope,0,5,0,0,Optional.empty());
        access.attachment=Optional.of(identity); access.actual=3;
        assertTrue(owner.commit(sample(id(999))));
        assertEquals(scope,access.observedScope);
        assertEquals(Map.of("minecraft:iron_ingot",3L),access.facts.getLast().quantities());
        access.attachment=Optional.of(new InventoryIdentity(identity.inventoryAddress(),identity.accessFace(),
            identity.blockId(),identity.blockEntityTypeId(),identity.attachmentInstanceId(),1));
        assertFalse(owner.commit(sample(id(999))),"same location with a replaced binding is not the same inventory");
        access.loaded=false; access.attachment=Optional.empty();
        assertFalse(owner.commit(sample(id(999))));
        assertEquals(1,access.facts.size()); assertEquals(3,access.stockReads);
    }

    @Test void sameIdReplacementOtherNamespacesAndActualJournalFailureRemainAtomic() throws Exception {
        var access=new Access(); var owner=new ProductionEffectCommitter(access);
        var first=publication(10,"waiting"); var next=publication(10,"running");
        access.journal.write("other_application/protected",publication(99,"protected"),RegistryAccess.EMPTY);
        assertTrue(owner.commit(first)); assertTrue(owner.commit(next));
        var archive=access.journal.save(new CompoundTag(),RegistryAccess.EMPTY);
        var restored=reload(archive);
        assertEquals(next,restored.read(ProductionEffectCommitter.publicationKey(id(10)),
            ProductionEffect.PanelSnapshotPublication.class,RegistryAccess.EMPTY).orElseThrow());
        assertEquals(publication(99,"protected"),restored.read("other_application/protected",
            ProductionEffect.PanelSnapshotPublication.class,RegistryAccess.EMPTY).orElseThrow());
        var damaged=archive.copy(); damaged.putInt("Version",-1);
        access.journal=reload(damaged); assertFalse(access.journal.persistenceValid());
        var before=access.journal.save(new CompoundTag(),RegistryAccess.EMPTY);
        assertThrows(IllegalStateException.class,()->owner.commit(first));
        assertEquals(before,access.journal.save(new CompoundTag(),RegistryAccess.EMPTY));
        access.journal=restored;
        assertEquals(next,access.journal.read(ProductionEffectCommitter.publicationKey(id(10)),
            ProductionEffect.PanelSnapshotPublication.class,RegistryAccess.EMPTY).orElseThrow());
    }

    @Test void nullInputsAndReaderFailureDoNotProduceSuccessfulEffects() {
        assertThrows(NullPointerException.class,()->new ProductionEffectCommitter(null));
        assertThrows(NullPointerException.class,()->ProductionEffectCommitter.publicationKey(null));
        var access=new Access(); var owner=new ProductionEffectCommitter(access);
        assertThrows(NullPointerException.class,()->owner.commit(null)); assertTrue(access.events.isEmpty());
        access.evidence=networkEvidence(id(1),id(5));
        var failure=new IllegalStateException("reader unavailable"); access.readerFailure=failure;
        assertSame(failure,assertThrows(IllegalStateException.class,()->owner.commit(sample(id(5)))));
        assertTrue(access.facts.isEmpty());
    }

    private static GoalObservationState networkEvidence(UUID cluster, UUID network) {
        return new GoalObservationState(id(4), cluster,
            new StockObservationScope.NetworkSummary(network, new StackKey(new ItemStack(Items.IRON_INGOT)), 5),
            0, 5, 0, 0, Optional.empty());
    }

    private static ProductionEffect.StockSampleRequest sample(UUID network) {
        return new ProductionEffect.StockSampleRequest(id(11), id(1), 11,
            new ProductionEffect.TargetFence(id(2), id(3), 7, 9), id(4), new CreateRouteKey(network, "input"));
    }

    private static ProductionEffect.PanelSnapshotPublication publication(long intent, String state) {
        return new ProductionEffect.PanelSnapshotPublication(id(intent), id(1), 11,
            new ProductionEffect.TargetFence(id(2), id(3), 7, 9),
            List.of(new ProductionEffect.PanelRow(id(4), 5, state)));
    }

    private static FactoryOrchestrationSavedData reload(CompoundTag archive) throws Exception {
        var method = FactoryOrchestrationSavedData.class.getDeclaredMethod("load",
            CompoundTag.class, HolderLookup.Provider.class);
        method.setAccessible(true);
        return (FactoryOrchestrationSavedData) method.invoke(null, archive, RegistryAccess.EMPTY);
    }
    private static UUID id(long value) { return new UUID(0, value); }

    private static final class Access implements ProductionEffectCommitter.Access {
        boolean work=true, thread=true;
        FactoryOrchestrationSavedData journal=new FactoryOrchestrationSavedData();
        final List<String> events=new ArrayList<>();
        GoalObservationState evidence;
        long actual, promises;
        int stockReads;
        StockObservationScope observedScope;
        final List<WorldFact.StockObservation> facts=new ArrayList<>();
        boolean operationResult;
        boolean loaded=true, publicationAccepted=true;
        Optional<InventoryIdentity> attachment=Optional.empty();
        RuntimeException operationFailure;
        RuntimeException readerFailure;
        ProductionEffect.CreateDispatch seenCreate;
        ProductionEffect.OutputPackageRequest seenOutput;
        final StockObservationService observations=new StockObservationService(() -> thread,
            new StockObservationService.StockReader() {
                public StockObservationService.NetworkSnapshot readNetwork(UUID network, StackKey item) {
                    stockReads++; if(readerFailure!=null)throw readerFailure;
                    return new StockObservationService.NetworkSnapshot(loaded, actual, promises);
                }
                public StockObservationService.AttachedSnapshot readAttached(SpaceAddress address, StackKey item) {
                    stockReads++; if(readerFailure!=null)throw readerFailure;
                    return new StockObservationService.AttachedSnapshot(loaded,attachment,actual,promises);
                }
            });
        public boolean acceptsWork() { events.add("work"); return work; }
        public boolean isServerThread() { events.add("thread"); return thread; }
        public Optional<GoalObservationState> observation(UUID requestId) {
            events.add("observation"); return Optional.ofNullable(evidence).filter(value -> value.goalId().equals(requestId));
        }
        public StockObservationService.Observation observe(StockObservationScope scope) {
            events.add("observe"); observedScope=scope; return observations.observe(scope);
        }
        public boolean publish(WorldFact.StockObservation fact) { events.add("publish"); facts.add(fact); return publicationAccepted; }
        public boolean create(ProductionEffect.CreateDispatch request) {
            events.add("create"); seenCreate=request;
            if (operationFailure!=null) throw operationFailure;
            return operationResult;
        }
        public boolean output(ProductionEffect.OutputPackageRequest request) {
            events.add("output"); seenOutput=request;
            if (operationFailure!=null) throw operationFailure;
            return operationResult;
        }
        public void persist(String key, ProductionEffect.PanelSnapshotPublication publication) {
            events.add("persist:" + key); journal.write(key, publication, RegistryAccess.EMPTY);
        }
    }
}
