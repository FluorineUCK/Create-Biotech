package com.nobodiiiii.createbiotech.content.biocomputing.application.production.dispatch;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.*;

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
final class DispatchFixture implements AutoCloseable {
    static final class SaveFailure extends RuntimeException {}
    final UUID cluster = id(1), receiverCluster = id(2), goal = id(3), sourceId = id(4), receiverId = id(5);
    final ProductionResourceProfile sourceProfile;
    final ProductionResourceProfile receiverProfile = ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 1, 0).orElseThrow();
    final ProcessRecord source;
    final FrozenProductionPlan initialPlan = plan();
    FrozenProductionPlan livePlan = initialPlan;
    ProductionOutputContract.Snapshot liveOutput;
    final FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
    final FactoryRuntimeService runtime;
    final ClusterEngineState sourceState;
    final RadioEndpoint sourceEndpoint = endpoint(cluster, sourceId, 0, 8, Set.of());
    final RadioEndpoint receiverEndpoint = endpoint(receiverCluster, receiverId, 8, 9, Set.of(ProductionCapability.ALCHEMIST));
    RadioRouter router;
    DurableRadioOutbox outbox;
    ProductionRemoteExpansion owner;
    ProductionLocalExpansion local;
    boolean fail;
    boolean sourceEndpointLoaded = true;
    int commits, planPublications, replays;
    long tick;
    DispatchFixture() { this(false, false, 1); }
    DispatchFixture(boolean exhausted, boolean sameClusterReceiver, int cadence) {
        sourceProfile = ProductionResourceProfile.forProfession(exhausted ? ProductionProfession.NITWIT : ProductionProfession.CLERIC, exhausted ? 1 : 5, 0).orElseThrow();
        source = new ProcessRecord(id(6), goal, sourceId, 0, sourceProfile, ProcessKind.GOAL, ProcessState.RUNNABLE, null, 4, Map.of());
        runtime = new FactoryRuntimeService(new FactoryRuntimeConfig(cadence, 1_000, 4, cadence > 1 ? 2 : 32, 4, 4, 32, 64, 20, 10, 100, 3, 32, 16));
        GoalRecord record = new GoalRecord(goal, cluster, new GoalSubmission(goal, initialPlan.patternId(), 1, InputMode.EXACT),
            initialPlan.deliveryRoute(), new PolicySnapshot(1, 0, false), GoalState.RUNNABLE, null);
        sourceState = new ClusterEngineState(cluster, id(10), 7, 1, 0, 0,
            Map.of(goal, record), Map.of(goal, 0L), Map.of(goal, ProcessKind.GOAL), Map.of(source.processId(), source),
            sameClusterReceiver ? List.of(new ClusterEngineState.MemberSnapshot(sourceId, sourceProfile, true, 8, 8),
                new ClusterEngineState.MemberSnapshot(receiverId, receiverProfile, true, 9, 9))
                : List.of(new ClusterEngineState.MemberSnapshot(sourceId, sourceProfile, true, 8, 8)), Map.of(), Set.of(), 0, null);
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
                public RadioEndpoint endpoint(RadioEndpoint.Key key) { return key.equals(sourceEndpoint.key()) ? (sourceEndpointLoaded ? sourceEndpoint : null) : key.equals(receiverEndpoint.key()) ? receiverEndpoint : null; }
                public List<RadioEndpoint> endpoints() { return List.of(sourceEndpoint, receiverEndpoint); }
                public boolean linkPossible(RadioEndpoint source, RadioEndpoint target, CommunicationFrequency frequency) { return true; }
                public List<RadioEndpoint.Hop> relays(RadioEndpoint source, RadioEndpoint target, CommunicationFrequency frequency) { return List.of(); }
                public void publishPlan(FrozenProductionPlan plan) { livePlan = plan; planPublications++; }
                public void enqueueMaterialReplay(UUID runId) { replays++; }
            });
        owner.restore();
        local = new ProductionLocalExpansion(RemoteExpansionTestRegistries.provider(), runtime, 32,
            new ProductionLocalExpansion.Persistence() {
                public Optional<ProductionLocalExpansion.Lifecycle> load() { return journal.read(ProductionLocalExpansion.LIFECYCLE_KEY, ProductionLocalExpansion.Lifecycle.class, RemoteExpansionTestRegistries.provider()); }
                public void commit(ProductionLocalExpansion.Lifecycle lifecycle, Optional<FrozenProductionPlan> plan) {
                    if (fail) throw new SaveFailure();
                    LinkedHashMap<String, Object> writes = new LinkedHashMap<>();
                    writes.put(ProductionLocalExpansion.LIFECYCLE_KEY, lifecycle);
                    plan.ifPresent(value -> writes.put("production_plan/" + value.goalId(), value));
                    journal.writeBatch(writes, RemoteExpansionTestRegistries.provider());
                }
                public Optional<ProductionOutputContract.Snapshot> output(UUID runId) { return Optional.empty(); }
            }, new ProductionLocalExpansion.Evidence() {
                public FrozenProductionPlan plan(UUID goalId) { return livePlan; }
                public ProductionOutputContract.Snapshot output(UUID runId) { return liveOutput; }
                public Set<UUID> remoteChildIds() { return owner.childIds(); }
                public void publishPlan(FrozenProductionPlan plan) { livePlan = plan; }
            });
        local.restore();
    }
    RemoteComputationExchange begin() { return owner.begin(livePlan, livePlan.runs().currentRun(), source, sourceState).exchange().orElseThrow(); }
    Optional<RemoteComputationLifecycleState> durable() { return journal.read(ProductionRemoteExpansion.LIFECYCLE_KEY, RemoteComputationLifecycleState.class, RemoteExpansionTestRegistries.provider()); }
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

    static UUID id(long value) { return new UUID(0, value); }
    public void close() { runtime.close(); }
}
