package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.*;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.CoordinationAssociation;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.*;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.RuntimeNbt;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingProductionResourceGameTests {
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void nativeResidentsYieldCopiedProductionFactsWithoutMutatingEntities(GameTestHelper helper) {
        var level = helper.getLevel();
        var villager = EntityType.VILLAGER.create(level);
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.CLERIC).setLevel(2));
        var before = villager.saveWithoutId(new CompoundTag());
        var profile = ProductionResidentProfiles.fromEntity(villager, 64).orElseThrow();
        helper.assertValueEqual(profile, ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 2, 0).orElseThrow(), "Exact adult profession and level");
        helper.assertValueEqual(villager.saveWithoutId(new CompoundTag()), before, "Reading the profile does not alter resident NBT");
        helper.assertValueEqual(ProductionResourceProfileCodecs.load(ProductionResourceProfileCodecs.save(profile)).orElseThrow(), profile, "Native profile NBT round trip");
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.LIBRARIAN).setLevel(5));
        helper.assertValueEqual(profile.processSlots(), 3, "The captured value does not follow later world changes");
        helper.assertValueEqual(ProductionResidentProfiles.fromEntity(villager, 64).orElseThrow().processSlots(), 16, "Explicit new read observes new resident facts");
        villager.setAge(-24000);
        helper.assertTrue(ProductionResidentProfiles.fromEntity(villager, 64).isEmpty(), "Child villager is not admitted as a production worker");
        var trader = EntityType.WANDERING_TRADER.create(level);
        var traderBefore = trader.saveWithoutId(new CompoundTag());
        var traderProfile = ProductionResidentProfiles.fromEntity(trader, 64).orElseThrow();
        helper.assertValueEqual(traderProfile.processSlots(), 2, "Historical trader slots");
        helper.assertValueEqual(traderProfile.listenerRangeBonus(), 64, "Explicit configured range input");
        helper.assertTrue(traderProfile.supports(ProductionCapability.EXTENDED_RADIO), "Historical production listener capability");
        helper.assertValueEqual(trader.saveWithoutId(new CompoundTag()), traderBefore, "Trader read is non-mutating");
        var zombie = EntityType.ZOMBIE_VILLAGER.create(level);
        helper.assertValueEqual(ProductionResidentProfiles.fromEntity(zombie, 64).orElseThrow().processSlots(), 0, "Zombie retains zero production slots, not zero generic compute resources");
        zombie.setBaby(true);
        helper.assertTrue(ProductionResidentProfiles.fromEntity(zombie, 64).isEmpty(), "Child zombie is rejected");
        helper.assertTrue(ProductionResidentProfiles.fromEntity(EntityType.COW.create(level), 64).isEmpty(), "Unsupported entity is not guessed into a profession");
        helper.assertTrue(ProductionResidentProfiles.forVillagerProfession(null, 1, 0).isEmpty(), "Missing profession remains unsupported");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void actualServiceRestoresNewProfileAndRetainsAuthoritativeQualificationChecks(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var villager = EntityType.VILLAGER.create(helper.getLevel());
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.CLERIC).setLevel(1));
        var profile = ProductionResidentProfiles.fromEntity(villager, 0).orElseThrow();
        UUID cluster = UUID.randomUUID(), epoch = UUID.randomUUID(), computer = UUID.randomUUID(), process = UUID.randomUUID();
        UUID goal = UUID.randomUUID(), pattern = UUID.randomUUID(), library = UUID.randomUUID();
        var association = new CoordinationAssociation(process, library, pattern, 7);
        CompoundTag saved;
        try (var service = new FactoryRuntimeService(FactoryRuntimeConfig.fromServerConfig())) {
            service.start();
            service.restoreSnapshot(state(cluster, epoch, computer, process, goal, pattern, profile, true, ProcessState.RUNNABLE));
            helper.assertTrue(!service.coordinationSnapshot(cluster).authorizes(cluster, library, pattern, 7), "A profile and process alone are not an exact lease");
            helper.assertTrue(!service.associateCoordinationProcess(cluster, new CoordinationAssociation(process, library, UUID.randomUUID(), 7)), "Goal pattern is rechecked by the real service");
            helper.assertTrue(service.associateCoordinationProcess(cluster, association), "Actual authoritative association is accepted");
            var snapshot = service.snapshot(cluster).orElseThrow();
            saved = RuntimeNbt.save(snapshot, registries);
            var typed = CurrentSchemaStateNbt.save(snapshot, registries);
            helper.assertTrue(typed.toString().contains("factorycluster.computer.ComputerProfile")
                && typed.toString().contains("factorycluster.computer.ComputerProfession")
                && typed.toString().contains("factorycluster.computer.ProcessCapability"), "Nested saved profiles keep exact historical type identities");
            helper.assertValueEqual(CurrentSchemaStateNbt.load(typed, registries, ClusterEngineState.class).orElseThrow(), snapshot, "Real registry typed state restore");
        }
        var restored = RuntimeNbt.load(saved, registries).orElseThrow();
        helper.assertValueEqual(restored.member(computer).profile(), profile, "Member profile is owned by the new application");
        helper.assertValueEqual(restored.processes().get(process).owningComputer(), profile, "Process profile matches the same canonical value");
        try (var service = new FactoryRuntimeService(FactoryRuntimeConfig.fromServerConfig())) {
            service.start(); service.restoreSnapshot(restored);
            var proof = service.coordinationSnapshot(cluster);
            helper.assertTrue(proof.authorizes(cluster, library, pattern, 7), "Exact authority survives persisted service rebuild");
            helper.assertTrue(!proof.authorizes(cluster, library, pattern, 8), "Old proof cannot authorize a new catalog generation");
            helper.assertTrue(!proof.authorizes(UUID.randomUUID(), library, pattern, 7), "Owner is not erased by the profile migration");
            var unloaded = state(cluster, epoch, computer, process, goal, pattern, profile, false, ProcessState.RUNNABLE);
            service.restoreSnapshot(unloaded.withCoordinationAssociation(association));
            helper.assertTrue(!service.coordinationSnapshot(cluster).authorizes(cluster, library, pattern, 7), "Unloaded member cannot activate saved qualification");
            helper.assertTrue(!service.associateCoordinationProcess(cluster, association), "Actual association gate also rejects unloaded member");
            var waiting = state(cluster, epoch, computer, process, goal, pattern, profile, true, ProcessState.WAITING);
            service.restoreSnapshot(waiting.withCoordinationAssociation(association));
            helper.assertTrue(!service.coordinationSnapshot(cluster).authorizes(cluster, library, pattern, 7), "Waiting process cannot activate saved qualification");
            helper.assertTrue(!service.associateCoordinationProcess(cluster, association), "Actual association gate also rejects waiting process");
            helper.assertTrue(service.snapshot(cluster).orElseThrow().pendingIntents().isEmpty(), "Restoring a resource profile does not submit world actions");
        }
        helper.succeed();
    }

    private static ClusterEngineState state(UUID cluster, UUID epoch, UUID computer, UUID processId, UUID goalId, UUID pattern,
            ProductionResourceProfile profile, boolean loaded, ProcessState processState) {
        var goal = new GoalRecord(goalId, cluster, new GoalSubmission(goalId, pattern, 1, InputMode.BULK),
            new CreateRouteKey(new UUID(0, 1), "resource_probe"), new PolicySnapshot(1, 0, false), GoalState.RUNNABLE, null);
        var process = new ProcessRecord(processId, goalId, computer, 0, profile, ProcessKind.PATTERN_PRIORITY_LEASE,
            processState, processState == ProcessState.WAITING ? WaitReason.PROCESS : null, 0, Map.of());
        return new ClusterEngineState(cluster, epoch, 1, 1, 0, 0, Map.of(goalId, goal), Map.of(goalId, 0L),
            Map.of(goalId, ProcessKind.PATTERN_PRIORITY_LEASE), Map.of(processId, process),
            List.of(new ClusterEngineState.MemberSnapshot(computer, profile, loaded, 1, 1)), Map.of(), Set.of(), 0, null);
    }
}
