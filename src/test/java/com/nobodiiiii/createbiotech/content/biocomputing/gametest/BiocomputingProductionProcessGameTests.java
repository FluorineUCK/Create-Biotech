package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.*;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalSubmission;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.PolicySnapshot;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.*;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.RuntimeNbt;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingProductionProcessGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void restoredDurableChildAckRequiresTheLiveParentLifecycle(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        UUID owner = UUID.randomUUID(), epoch = UUID.randomUUID(), computer = UUID.randomUUID();
        UUID processId = UUID.randomUUID(), goalId = UUID.randomUUID(), patternId = UUID.randomUUID();
        var route = new CreateRouteKey(UUID.randomUUID(), "material_lifecycle");
        var profile = ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 1, 0).orElseThrow();
        var parent = new ProcessRecord(processId, goalId, computer, 0, profile, ProcessKind.GOAL,
            ProcessState.RUNNABLE, null, 0, Map.of());
        var iron = new StackKey(Items.IRON_INGOT.getDefaultInstance());
        var request = RemoteExpansionRequest.capture(registries, goalId, processId, 0, 1, patternId, 2,
            UUID.randomUUID(), 0, InputMode.BULK, 2,
            List.of(new RemoteExpansionRequest.StageInput(0, route, Map.of(iron, 1L))), Map.of(iron, 1L));
        var revision = RemoteExpansionRevision.compute(request);
        var lease = new RemoteProcessLease(UUID.randomUUID(), goalId, processId, owner, UUID.randomUUID(), 1, 1,
            ProductionCapability.ALCHEMIST, request, 1);
        var goal = new GoalRecord(goalId, owner, new GoalSubmission(goalId, patternId, 1, InputMode.BULK),
            route, new PolicySnapshot(1, 0, false), GoalState.RUNNABLE, null);
        var original = new ClusterEngineState(owner, epoch, 1, 1, 0, 0, Map.of(goalId, goal), Map.of(goalId, 0L),
            Map.of(goalId, ProcessKind.GOAL), Map.of(processId, parent),
            List.of(new ClusterEngineState.MemberSnapshot(computer, profile, true, 1, 1)), Map.of(), Set.of(), 0, null);
        ClusterEngineState durable;
        try (var service = new FactoryRuntimeService(FactoryRuntimeConfig.fromServerConfig())) {
            service.start(); service.restoreSnapshot(original);
            helper.assertTrue(!service.remoteExpansionMaterialAuthorized(lease, revision), "Missing ACK cannot authorize");
            helper.assertTrue(service.installRemoteChildRevision(lease, revision), "Install exact current ACK");
            durable = service.snapshot(owner).orElseThrow();
        }
        var acknowledged = durable.processes().get(processId);
        for (String scenario : List.of("waiting", "parent_generation", "parent_terminal", "goal_terminal")) {
            boolean waiting = scenario.equals("waiting");
            var nextParent = new ProcessRecord(processId, goalId, computer, 0, profile, ProcessKind.GOAL,
                waiting ? ProcessState.WAITING : scenario.equals("parent_terminal") ? ProcessState.TERMINAL : ProcessState.RUNNABLE,
                waiting ? WaitReason.PARENT_ACK : null, scenario.equals("parent_generation") ? 1 : 0,
                acknowledged.childRevisionStates());
            var nextGoal = new GoalRecord(goalId, owner, goal.submission(), route, goal.policy(),
                waiting ? GoalState.WAITING : scenario.equals("goal_terminal") ? GoalState.TERMINAL : GoalState.RUNNABLE,
                waiting ? WaitReason.PARENT_ACK : null);
            var next = new ClusterEngineState(owner, epoch, durable.generation() + 1, 1, 0, 0,
                Map.of(goalId, nextGoal), Map.of(goalId, 0L), Map.of(goalId, ProcessKind.GOAL), Map.of(processId, nextParent),
                durable.members(), Map.of(), Set.of(), 0, null);
            var saved = RuntimeNbt.save(next, registries);
            var restoredState = RuntimeNbt.load(saved, registries).orElseThrow();
            helper.assertValueEqual(restoredState, next, "Current lifecycle and old ACK survive save: " + scenario);
            helper.assertValueEqual(CurrentSchemaStateNbt.load(CurrentSchemaStateNbt.save(next, registries), registries,
                ClusterEngineState.class).orElseThrow(), next, "Typed historical schema remains unchanged: " + scenario);
            try (var restored = new FactoryRuntimeService(FactoryRuntimeConfig.fromServerConfig())) {
                restored.start(); restored.restoreSnapshot(restoredState);
                helper.assertValueEqual(restored.remoteExpansionMaterialAuthorized(lease, revision), waiting,
                    "Standalone material check revalidates lifecycle: " + scenario);
                helper.assertValueEqual(restored.installRemoteChildRevision(lease, revision), waiting,
                    "Installation shares the same current source guard: " + scenario);
                helper.assertValueEqual(restored.snapshot(owner).orElseThrow(), next, "Rejection or exact replay is read-only: " + scenario);
                helper.assertTrue(restored.snapshot(owner).orElseThrow().pendingIntents().isEmpty(), "Checks emit no world writes");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void actualServiceRejectsUnsaveableChildWithoutDiscardingDurableAcknowledgements(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        UUID owner = UUID.randomUUID(), epoch = UUID.randomUUID(), computer = UUID.randomUUID(), processId = UUID.randomUUID();
        UUID goalId = UUID.randomUUID(), patternId = UUID.randomUUID(), receiver = UUID.randomUUID();
        var route = new CreateRouteKey(UUID.randomUUID(), "child_capacity");
        var profile = ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 1, 0).orElseThrow();
        var parent = new ProcessRecord(processId, goalId, computer, 0, profile, ProcessKind.GOAL, ProcessState.RUNNABLE, null, 0, Map.of());
        var iron = new StackKey(Items.IRON_INGOT.getDefaultInstance());
        var stages = List.of(new RemoteExpansionRequest.StageInput(0, route, Map.of(iron, 1L)));
        var request = RemoteExpansionRequest.capture(registries, goalId, processId, 0, 1, patternId, 2,
            UUID.randomUUID(), 0, InputMode.BULK, 2, stages, Map.of(iron, 1L));
        var accepted = RemoteExpansionRevision.compute(request);
        parent = parent.acceptChildRevision(accepted.childRevision()).parent();
        for (int i = 0; i < ProcessRecord.MAX_CHILD_REVISIONS - 1; i++) {
            parent = parent.acceptChildRevision(new ChildExecutionRevision(new UUID(0, i), 0, 0,
                InputMode.BULK, InputMode.PARTIAL, 8, 3, 5)).parent();
        }
        var overflowRequest = RemoteExpansionRequest.capture(registries, goalId, processId, 0, 1, patternId, 2,
            UUID.randomUUID(), 0, InputMode.BULK, 2, stages, Map.of(iron, 1L));
        var overflow = RemoteExpansionRevision.compute(overflowRequest);
        helper.assertTrue(!parent.childRevisionStates().containsKey(overflow.childRevision().childProcessId()), "Overflow is a new child, not an update");
        var lease = new RemoteProcessLease(UUID.randomUUID(), goalId, processId, owner, receiver, 1, 1,
            ProductionCapability.ALCHEMIST, request, 1);
        var overflowLease = new RemoteProcessLease(UUID.randomUUID(), goalId, processId, owner, receiver, 1, 1,
            ProductionCapability.ALCHEMIST, overflowRequest, 1);
        var goal = new GoalRecord(goalId, owner, new GoalSubmission(goalId, patternId, 1, InputMode.BULK),
            route, new PolicySnapshot(1, 0, false), GoalState.RUNNABLE, null);
        var original = new ClusterEngineState(owner, epoch, 1, 1, 0, 0, Map.of(goalId, goal), Map.of(goalId, 0L),
            Map.of(goalId, ProcessKind.GOAL), Map.of(processId, parent),
            List.of(new ClusterEngineState.MemberSnapshot(computer, profile, true, 1, 1)), Map.of(), Set.of(), 0, null);
        CompoundTag saved;
        try (var service = new FactoryRuntimeService(FactoryRuntimeConfig.fromServerConfig())) {
            service.start(); service.restoreSnapshot(original);
            helper.assertTrue(!service.installRemoteChildRevision(overflowLease, overflow), "Actual admission rejects the 257th child");
            helper.assertValueEqual(service.snapshot(owner).orElseThrow(), original, "Rejected child cannot partially replace source state");
            helper.assertTrue(!service.remoteExpansionMaterialAuthorized(lease, accepted), "Receipt without durable acknowledgement has no boundary permit");
            helper.assertTrue(service.installRemoteChildRevision(lease, accepted), "Existing child can be acknowledged at capacity");
            helper.assertTrue(service.remoteExpansionMaterialAuthorized(lease, accepted), "Exact current durable acknowledgement authorizes existing boundary");
            var durable = service.snapshot(owner).orElseThrow();
            saved = RuntimeNbt.save(durable, registries);
            var typed = CurrentSchemaStateNbt.save(durable, registries);
            helper.assertTrue(typed.toString().contains("factorycluster.orchestration.model.ProcessRecord")
                && typed.toString().contains("factorycluster.orchestration.model.ChildExecutionRevision"), "Nested process and child identities remain historical");
            helper.assertValueEqual(CurrentSchemaStateNbt.load(typed, registries, ClusterEngineState.class).orElseThrow(), durable, "Full child table typed restore");
        }
        try (var restored = new FactoryRuntimeService(FactoryRuntimeConfig.fromServerConfig())) {
            restored.start(); restored.restoreSnapshot(RuntimeNbt.load(saved, registries).orElseThrow());
            var before = restored.snapshot(owner).orElseThrow();
            helper.assertValueEqual(before.processes().get(processId).childRevisionStates().size(), ProcessRecord.MAX_CHILD_REVISIONS, "Full bounded table reloads");
            helper.assertTrue(restored.installRemoteChildRevision(lease, accepted), "Exact replay remains idempotent after service reconstruction");
            helper.assertValueEqual(restored.snapshot(owner).orElseThrow(), before, "Exact durable replay does not replace state");
            helper.assertTrue(restored.remoteExpansionMaterialAuthorized(lease, accepted), "Exact durable acknowledgement survives restore");
            helper.assertTrue(!restored.installRemoteChildRevision(overflowLease, overflow), "Restoring does not erase the capacity boundary");
            helper.assertTrue(!restored.remoteExpansionMaterialAuthorized(overflowLease, overflow), "Rejected new child cannot gain boundary authority");
            helper.assertTrue(restored.snapshot(owner).orElseThrow().pendingIntents().isEmpty(), "This protocol validation emits no world mutations");
        }
        helper.succeed();
    }
}
