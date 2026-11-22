package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.*;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.*;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.*;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.RuntimeNbt;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingPriorityAuthorityGameTests {
    @GameTest(template = "empty", timeoutTicks = 120)
    public static void actualWorkerUnloadSaveAndReloadRequalifiesGoalLessProductionLease(GameTestHelper helper) {
        var ids = new Ids(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var profile = ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 1, 0).orElseThrow();
        var process = new ProcessRecord(ids.process, UUID.randomUUID(), ids.computer, 0, profile,
            ProcessKind.PATTERN_PRIORITY_LEASE, ProcessState.RUNNABLE, null, 0, Map.of());
        var state = new ClusterEngineState(ids.owner, ids.epoch, 1, 0, 0, 0, Map.of(), Map.of(), Map.of(),
            Map.of(ids.process, process), List.of(new ClusterEngineState.MemberSnapshot(ids.computer, profile, true, 1, 1)),
            Map.of(), Set.of(), 0, null).withCoordinationAssociation(new CoordinationAssociation(ids.process, ids.library, ids.pattern, 7));
        var service = new FactoryRuntimeService(FactoryRuntimeConfig.fromServerConfig());
        try {
            service.start(); service.restoreSnapshot(state);
            helper.assertTrue(service.snapshot(ids.owner).orElseThrow().goals().isEmpty(), "Search reservations need not own a production Goal");
            helper.assertTrue(authorized(service, ids), "Exact loaded reservation qualifies through the actual service");
            helper.assertTrue(service.publishFact(new WorldFact.MemberLoadState(ids.owner, ids.epoch, state.generation(), ids.computer,
                profile, false, 2, 2)), "Real member unload fact accepted");
            awaitTransition(helper, service, ids, false, 0);
        } catch (RuntimeException | Error failure) { service.close(); throw failure; }
    }

    private static void awaitTransition(GameTestHelper helper, FactoryRuntimeService service, Ids ids, boolean reloading, int elapsed) {
        helper.runAfterDelay(1, () -> {
            try {
                var state = service.snapshot(ids.owner).orElseThrow();
                var member = state.member(ids.computer); var process = state.processes().get(ids.process);
                var expected = reloading ? ProcessState.RUNNABLE : ProcessState.WAITING;
                if (member.loaded() != reloading || process == null || process.state() != expected) {
                    helper.assertTrue(elapsed < 45, "Actual worker must apply member transition and reservation availability");
                    awaitTransition(helper, service, ids, reloading, elapsed + 1); return;
                }
                helper.assertTrue(state.pendingIntents().isEmpty(), "Priority qualifications do not produce world writes");
                if (reloading) {
                    var authority = service.coordinationSnapshot(ids.owner);
                    helper.assertTrue(authorized(service, ids), "Loaded runnable reservation requalifies after service reconstruction");
                    helper.assertTrue(!authority.authorizes(ids.owner, ids.library, ids.pattern, 8), "Catalog fence remains exact");
                    helper.assertTrue(!authority.authorizes(UUID.randomUUID(), ids.library, ids.pattern, 7), "Owner remains exact");
                    helper.assertTrue(!authority.authorizes(ids.owner, UUID.randomUUID(), ids.pattern, 7), "Library remains exact");
                    service.close(); helper.succeed(); return;
                }
                helper.assertTrue(!authorized(service, ids), "Unloaded waiting reservation has no authority despite persisted association");
                var registries = helper.getLevel().registryAccess();
                var saved = RuntimeNbt.save(state, registries); var typed = CurrentSchemaStateNbt.save(state, registries);
                helper.assertTrue(typed.toString().contains("factorycluster.orchestration.library.CoordinationAssociation"), "Historical nested association identity is retained");
                helper.assertValueEqual(CurrentSchemaStateNbt.load(typed, registries, ClusterEngineState.class).orElseThrow(), state, "Typed actual registry restore");
                service.close();
                var restored = RuntimeNbt.load(saved, registries).orElseThrow();
                var next = new FactoryRuntimeService(FactoryRuntimeConfig.fromServerConfig());
                try {
                    next.start(); next.restoreSnapshot(restored);
                    helper.assertTrue(!authorized(next, ids), "Restoration alone cannot activate the durable association");
                    helper.assertTrue(next.publishFact(new WorldFact.MemberLoadState(ids.owner, ids.epoch, restored.generation(), ids.computer,
                        restored.member(ids.computer).profile(), true, 3, 3)), "Explicit real reload fact accepted");
                    awaitTransition(helper, next, ids, true, 0);
                } catch (RuntimeException | Error failure) { next.close(); throw failure; }
            } catch (RuntimeException | Error failure) { service.close(); throw failure; }
        });
    }

    private static boolean authorized(FactoryRuntimeService service, Ids ids) {
        return service.coordinationSnapshot(ids.owner).authorizes(ids.owner, ids.library, ids.pattern, 7);
    }
    private record Ids(UUID owner, UUID epoch, UUID computer, UUID process, UUID library, UUID pattern) {}
}
