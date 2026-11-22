package com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationScope;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.*;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.*;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationSavedData;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.RemoteExpansionProtocol.*;

/** Real runtime worker, journal codec, router and outbox; ports supply facts, never lifecycle policy. */
final class RemoteExpansionFixture implements AutoCloseable {
    static final class SaveFailure extends RuntimeException {}
    final UUID cluster = id(1), receiverCluster = id(2), goal = id(3), sourceId = id(4), receiverId = id(5);
    final ProductionResourceProfile sourceProfile = ProductionResourceProfile.forProfession(ProductionProfession.NITWIT, 1, 0).orElseThrow();
    final ProductionResourceProfile receiverProfile = ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 1, 0).orElseThrow();
    final ProcessRecord source = new ProcessRecord(id(6), goal, sourceId, 0, sourceProfile, ProcessKind.GOAL, ProcessState.RUNNABLE, null, 4, Map.of());
    final FrozenProductionPlan initialPlan = plan();
    FrozenProductionPlan livePlan = initialPlan;
    ProductionOutputContract.Snapshot liveOutput;
    final FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
    final FactoryRuntimeService runtime = new FactoryRuntimeService(new FactoryRuntimeConfig(1, 1_000, 4, 32, 4, 4, 32, 64, 20, 10, 100, 3, 32, 16));
    final ClusterEngineState sourceState;
    final RadioEndpoint sourceEndpoint = endpoint(cluster, sourceId, 0, 8, Set.of());
    final RadioEndpoint receiverEndpoint = endpoint(receiverCluster, receiverId, 8, 9, Set.of(ProductionCapability.ALCHEMIST));
    RadioRouter router;
    DurableRadioOutbox outbox;
    ProductionRemoteExpansion owner;
    boolean fail;
    int commits, planPublications, replays;
    long tick;
    RemoteExpansionFixture() {
        GoalRecord record = new GoalRecord(goal, cluster, new GoalSubmission(goal, initialPlan.patternId(), 1, InputMode.EXACT),
            initialPlan.deliveryRoute(), new PolicySnapshot(1, 0, false), GoalState.RUNNABLE, null);
        sourceState = new ClusterEngineState(cluster, id(10), 7, 1, 0, 0,
            Map.of(goal, record), Map.of(goal, 0L), Map.of(goal, ProcessKind.GOAL), Map.of(source.processId(), source),
            List.of(new ClusterEngineState.MemberSnapshot(sourceId, sourceProfile, true, 8, 8)), Map.of(), Set.of(), 0, null);
        ClusterEngineState receiverState = new ClusterEngineState(receiverCluster, id(11), 7, 1, 0, 0,
            Map.of(), Map.of(), Map.of(), Map.of(), List.of(new ClusterEngineState.MemberSnapshot(receiverId, receiverProfile, true, 9, 9)), Map.of(), Set.of(), 0, null);
        runtime.start(); runtime.restoreSnapshot(sourceState); runtime.restoreSnapshot(receiverState); reload();
    }
    void reload() {
        router = new RadioRouter(16, 48, 32, 32, state -> {}, new RadioRouter.LeaseProtocolStateStore() {
            public Optional<RadioRouter.LeaseProtocolState> load() { return journal.read("radio/leases", RadioRouter.LeaseProtocolState.class, RemoteExpansionTestRegistries.provider()); }
            public void persist(RadioRouter.LeaseProtocolState state) { journal.write("radio/leases", state, RemoteExpansionTestRegistries.provider()); }
        });
        router.register(sourceEndpoint); router.register(receiverEndpoint);
        outbox = new DurableRadioOutbox(32, new DurableRadioOutbox.StateStore() {
            public Optional<DurableRadioOutbox.State> load() { return journal.read("radio/outbox", DurableRadioOutbox.State.class, RemoteExpansionTestRegistries.provider()); }
            public void persist(DurableRadioOutbox.State state) { journal.write("radio/outbox", state, RemoteExpansionTestRegistries.provider()); }
        });
        owner = new ProductionRemoteExpansion(RemoteExpansionTestRegistries.provider(), runtime, 32, router, outbox,
            new ProductionRemoteExpansion.Persistence() {
                public Optional<RemoteComputationLifecycleState> load() { return durable(); }
                public void commit(Optional<RemoteComputationLifecycleState> lifecycle, Optional<FrozenProductionPlan> plan,
                    Optional<RadioRouter.LeaseProtocolState> leases, Optional<DurableRadioOutbox.State> outbox) {
                    if (fail) throw new SaveFailure();
                    LinkedHashMap<String, Object> writes = new LinkedHashMap<>();
                    lifecycle.ifPresent(value -> writes.put(ProductionRemoteExpansion.LIFECYCLE_KEY, value));
                    plan.ifPresent(value -> writes.put("production_plan/" + value.goalId(), value));
                    leases.ifPresent(value -> writes.put("radio/leases", value));
                    outbox.ifPresent(value -> writes.put("radio/outbox", value));
                    journal.writeBatch(writes, RemoteExpansionTestRegistries.provider()); commits++;
                }
                public Optional<ProductionOutputContract.Snapshot> output(UUID runId) { return journal.read("output_contract/" + runId, ProductionOutputContract.Snapshot.class, RemoteExpansionTestRegistries.provider()); }
            }, new ProductionRemoteExpansion.Evidence() {
                public FrozenProductionPlan plan(UUID goalId) { return goal.equals(goalId) ? livePlan : null; }
                public ProductionOutputContract.Snapshot output(UUID runId) { return liveOutput; }
                public RadioEndpoint endpoint(RadioEndpoint.Key key) { return key.equals(sourceEndpoint.key()) ? sourceEndpoint : key.equals(receiverEndpoint.key()) ? receiverEndpoint : null; }
                public List<RadioEndpoint> endpoints() { return List.of(sourceEndpoint, receiverEndpoint); }
                public boolean linkPossible(RadioEndpoint source, RadioEndpoint target, CommunicationFrequency frequency) { return true; }
                public List<RadioEndpoint.Hop> relays(RadioEndpoint source, RadioEndpoint target, CommunicationFrequency frequency) { return List.of(); }
                public void publishPlan(FrozenProductionPlan plan) { livePlan = plan; planPublications++; }
                public void enqueueMaterialReplay(UUID runId) { replays++; }
            });
        owner.restore();
    }
    RemoteComputationExchange begin() { return owner.begin(livePlan, livePlan.runs().currentRun(), source, sourceState).exchange().orElseThrow(); }
    RemoteComputationExchange exchange() { return owner.exchange(initialPlan.runs().currentRun().runId()).orElseThrow(); }
    RemoteComputationJob job() { return owner.snapshot().jobs().get(exchange().runId()); }
    Optional<RemoteComputationLifecycleState> durable() { return journal.read(ProductionRemoteExpansion.LIFECYCLE_KEY, RemoteComputationLifecycleState.class, RemoteExpansionTestRegistries.provider()); }
    RadioEnvelope deliverActive() {
        owner.advanceExchanges();
        RadioEnvelope active = exchange().activeEnvelope().orElseThrow();
        List<RadioRouter.Delivery> deliveries = outbox.pump(router, ++tick);
        return deliveries.stream().map(RadioRouter.Delivery::envelope)
            .filter(delivery -> delivery.messageId().equals(active.messageId()) && delivery.payload().equals(active.payload()))
            .findFirst().orElseThrow(() -> new AssertionError("actual router did not deliver active message " + active.messageId()));
    }
    void receiveActive() { owner.receive(exchange(), deliverActive()); }
    void awaitPhase(RemoteComputationPhase phase) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            owner.advanceJobs();
            if (exchange().phase() == phase) return;
            Thread.sleep(5);
        }
        fail("receiver worker did not reach " + phase + ": " + exchange().phase());
    }
    FrozenProductionPlan plan() {
        UUID network = id(90), pattern = id(91);
        CreateRouteKey input = new CreateRouteKey(network, "input"), delivery = new CreateRouteKey(network, "warehouse");
        StackKey output = new StackKey(new ItemStack(Items.GOLD_INGOT)), ingredient = new StackKey(new ItemStack(Items.IRON_INGOT));
        ProductionRun run = new ProductionRun(id(92), goal, pattern, 0, 1, InputMode.EXACT, List.of(input), delivery, 0, Map.of(ingredient, 1L));
        SelectedProviderDescriptor provider = new SelectedProviderDescriptor(new RouteSelectionScope(id(94), id(95), output, 0, 0, id(96)), id(93), pattern, pattern.toString(), RouteCandidate.ProviderKind.PATTERN, 0, RouteCandidate.Availability.AVAILABLE);
        return new FrozenProductionPlan(goal, cluster, provider, PatternKind.STANDARD, output, 1, delivery,
            List.of(new FrozenProductionPlan.FrozenStage(0, input, Map.of(ingredient, 1L), List.of(new OrderedIngredient(ingredient, 1L)))),
            new ProductionRun.RunSequence(run, 1), new StockObservationScope.NetworkSummary(network, output, 1), 0, 1, false, false, Optional.empty());
    }
    static RadioEndpoint endpoint(UUID cluster, UUID member, int x, long generation, Set<ProductionCapability> capabilities) {
        return new RadioEndpoint(cluster, member, new SpaceAddress(Level.OVERWORLD, null, new BlockPos(x,64,0)), generation, CommunicationFrequency.of("17"), 1, capabilities);
    }

    ProductionOutputContract.Snapshot receivedOutput() {
        return new ProductionOutputContract.Snapshot(id(180), 0, id(181), initialPlan.runs().currentRun(), 0,
            ProductionOutputContract.Milestone.OUTPUT_RECEIVED, Optional.empty(), true, false, true, 0, 0, 0,
            Map.of(), Map.of(), List.of(), Map.of(), Map.of(), Set.of(), Set.of(), Set.of(), List.of(),
            Optional.empty(), -1, 0, Optional.empty(), Optional.empty(), Optional.empty(), true);
    }
    static ClusterEngineState withGeneration(ClusterEngineState state, long generation) {
        return new ClusterEngineState(state.clusterId(), state.epochId(), generation, state.nextSubmissionSequence(),
            state.nextRouteSelectionSequence(), state.memberCursor(), state.processCursor(), state.goals(),
            state.submissionSequences(), state.requestedKinds(), state.processes(), state.members(), state.pendingIntents(),
            state.uncertainIntentIds(), state.goalScanOffset(), state.goalScanCandidate(), state.routeCursor(),
            state.coordinationAssociations(), state.priorityLeaseAssociations());
    }
    static UUID id(long value) { return new UUID(0, value); }
    public void close() { runtime.close(); }
}
