package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.BiocomputingWorldGameTests.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ProgramDefinition;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingComputerArchiveGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void missingProductionChildSurvivesRealReconstructionWithoutSharingOrLosingPrograms(GameTestHelper helper) {
        verifyDamagedProductionKeepsPrivateComputation(helper, data -> data.remove("ExternalListeners"));
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void malformedProductionRuntimeSurvivesRealReconstructionWhilePrivateProgramsResume(GameTestHelper helper) {
        verifyDamagedProductionKeepsPrivateComputation(helper,
            data -> data.getCompound("OrchestrationRuntime").putString("UnknownRuntimeChild", "keep evidence"));
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void wrongHistoricalEngineTagCannotDisablePhysicalResident(GameTestHelper helper) {
        verifyDamagedProductionKeepsPrivateComputation(helper, data -> data.putString("OrchestrationRuntime", "opaque"));
    }

    static CompoundTag historicalData(CompoundTag saved) {
        var data = saved.getCompound("ComputerData");
        data.putInt("Version", 5);
        var runtime = new CompoundTag();
        runtime.putString("History", "inert V5 bytes");
        data.put("OrchestrationRuntime", runtime);
        data.put("ExternalListeners", new CompoundTag());
        return data;
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void takingHistoricalResidentChangesOnlyOccupantAndDoesNotRestoreIt(GameTestHelper helper) {
        var original = computer(helper, helper.absolutePos(new BlockPos(2,2,2)), VillagerProfession.CLERIC, 2);
        var saved = original.saveWithFullMetadata(helper.getLevel().registryAccess());
        var expected = historicalData(saved).copy();
        var current = reload(helper, original, saved);
        helper.assertFalse(current.residentSnapshot().isEmpty(), "V5 resident physically available");
        current.clearResidentSource();
        expected.remove("Resident");
        var next = current.saveWithFullMetadata(helper.getLevel().registryAccess());
        helper.assertValueEqual(next.get("ComputerData"), expected, "Only the physical occupant was removed");
        current = reload(helper, current, next);
        helper.assertFalse(current.hasResidentSource(), "Removed resident cannot reappear");
        helper.assertValueEqual(current.computerId(), original.computerId(), "Same physical machine");
        helper.succeed();
    }

    private static void verifyDamagedProductionKeepsPrivateComputation(GameTestHelper helper,
        java.util.function.Consumer<CompoundTag> damage) {
        var a = computer(helper, helper.absolutePos(new BlockPos(2,2,2)), VillagerProfession.LIBRARIAN, 1);
        var b = computer(helper, helper.absolutePos(new BlockPos(5,2,2)), VillagerProfession.FLETCHER, 2);
        var restored = new ArrayList<ComputerBlockEntity>();
        for (var original : List.of(a, b)) {
            var base = counter().program();
            long initial = original == a ? 0 : 10;
            var program = new ProgramArchive(new ProgramDefinition(base.graph(), Map.of("count",
                new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(initial))), base.writes()), Map.of());
            var host = host(original); host.install(program, ComputerRuntime.MemoryPolicy.RESET);
            host.setPaused(true); host.begin(new DataValue.RecordValue(Map.of()));
            var expected = host.snapshot().orElseThrow(); var hardware = host.hardware(); var resident = original.residentSnapshot();
            var saved = original.saveWithFullMetadata(helper.getLevel().registryAccess());
            damage.accept(historicalData(saved));
            var current = original;
            for (int pass = 0; pass < 2; pass++) {
                current = reload(helper, current, saved);
                helper.assertValueEqual(current.computerId(), original.computerId(), "Physical identity survives opaque production damage");
                var next = current.saveWithFullMetadata(helper.getLevel().registryAccess());
                helper.assertValueEqual(next.get("ComputerData"), saved.get("ComputerData"), "Do not repair or lose historical children");
                helper.assertTrue(host(current).communicationIdentity().isPresent(),
                    "Authorized observation restores the lazy durable host without executing it");
                helper.assertValueEqual(host(current).snapshot().orElseThrow(), expected, "Private program, RAM and paused continuation survive");
                helper.assertValueEqual(host(current).hardware(), hardware, "Original resident hardware limits retained");
                helper.assertTrue(ItemStack.matches(resident, current.residentSnapshot()), "Same physical resident snapshot");
                saved = next;
            }
            restored.add(current); host(current).setPaused(false);
        }
        helper.assertTrue(host(restored.get(0)).hardware().limits().hardware().workPerSlice()
            < host(restored.get(1)).hardware().limits().hardware().workPerSlice(), "Keep heterogeneous members, not a pooled budget");
        helper.runAfterDelay(10, () -> {
            helper.assertValueEqual(memory(host(restored.get(0)), "count"), new DataValue.IntegerValue(1), "First private counter resumes once");
            helper.assertValueEqual(memory(host(restored.get(1)), "count"), new DataValue.IntegerValue(11), "Second private counter keeps its distinct initial value");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void unknownEnvelopeKeepsItsPhysicalSourceWithoutInstallingItsProductionState(GameTestHelper helper) {
        var original = computer(helper, helper.absolutePos(new BlockPos(2,2,2)), VillagerProfession.CLERIC, 2);
        var saved = original.saveWithFullMetadata(helper.getLevel().registryAccess());
        saved.getCompound("ComputerData").putString("FutureUnknown", "preserve");
        var current = reload(helper, original, saved);
        helper.assertTrue(current.computerId().isEmpty(), "Unknown format does not grant authority");
        helper.assertTrue(current.residentSnapshot().isEmpty() && current.hasResidentSource(), "Opaque source is not an installed resident or an empty slot");
        helper.assertValueEqual(current.rawResidentTag(), saved.getCompound("ComputerData").get("Resident"), "Exact source remains recoverable");
        helper.assertValueEqual(current.saveWithFullMetadata(helper.getLevel().registryAccess()).get("ComputerData"),
            saved.get("ComputerData"), "Whole unknown envelope is lossless");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 160)
    public static void ordinaryComputerKeepsOldRuntimeEvidenceWithoutFormingOrStartingAWorker(GameTestHelper helper) {
        var origin = helper.absolutePos(new BlockPos(1, 1, 1));
        var original = computer(helper, origin.offset(1, 1, 1), VillagerProfession.LIBRARIAN, 1);
        var host = host(original); host.install(counter(), ComputerRuntime.MemoryPolicy.RESET);
        host.setPaused(true); host.begin(new DataValue.RecordValue(Map.of()));
        var program = host.snapshot().orElseThrow(); var hardware = host.hardware();
        for (int x = 0; x < 5; x++) for (int y = 0; y < 4; y++) for (int z = 0; z < 3; z++)
            if (x == 0 || x == 4 || y == 0 || y == 3 || z == 0 || z == 2)
                helper.getLevel().setBlock(origin.offset(x, y, z),
                    com.nobodiiiii.createbiotech.registry.CBBlocks.COMPUTER_CASING.get().defaultBlockState(), 3);
        helper.getLevel().setBlock(origin.offset(0, 1, 1),
            com.nobodiiiii.createbiotech.registry.CBBlocks.MATERIAL_REQUEST_PORT.get().defaultBlockState(), 3);
        var current = new ComputerBlockEntity[1]; var rejected = new CompoundTag[1];
        helper.startSequence().thenIdle(40).thenExecute(() -> {
            var server = helper.getLevel().getServer();
            helper.assertTrue(com.nobodiiiii.createbiotech.CreateBiotech.class.getResource(
                    "/com/nobodiiiii/createbiotech/content/factorycluster/runtime/FactoryOrchestrationServer.class") == null,
                "A real server must not install the retired orchestration runtime");
            helper.assertTrue(com.nobodiiiii.createbiotech.CreateBiotech.class.getResource(
                    "/com/nobodiiiii/createbiotech/content/factorycluster/createbridge/NativePackageObservation.class") == null,
                "No legacy native packager observer is registered");
            helper.assertTrue(!original.saveWithoutMetadata(helper.getLevel().registryAccess()).getCompound("ComputerData").contains("OrchestrationRuntime"),
                "A normal Computer never scans or joins the retired production assembly");
        })
            .thenExecute(() -> {
                var saved = original.saveWithFullMetadata(helper.getLevel().registryAccess());
                var raw = historicalData(saved).getCompound("OrchestrationRuntime");
                raw.putString("UnknownRuntimeChild", "keep evidence"); rejected[0] = raw.copy();
                current[0] = reload(helper, original, saved);
            }).thenIdle(25).thenExecute(() -> {
                helper.assertValueEqual(current[0].saveWithFullMetadata(helper.getLevel().registryAccess())
                    .getCompound("ComputerData").get("OrchestrationRuntime"), rejected[0],
                    "Automatic engine snapshot installation must not erase the rejected archive");
                helper.assertTrue(host(current[0]).communicationIdentity().isPresent(), "Restore only the independent computation host");
                helper.assertValueEqual(host(current[0]).snapshot().orElseThrow(), program, "General program remains untouched");
                helper.assertValueEqual(host(current[0]).hardware(), hardware, "Resident hardware remains untouched");
                host(current[0]).setPaused(false);
            }).thenIdle(10).thenExecute(() -> {
                helper.assertValueEqual(memory(host(current[0]), "count"), new DataValue.IntegerValue(1),
                    "Independent computation still resumes once despite rejected production state");
            }).thenSucceed();
    }

    private static ComputerBlockEntity reload(GameTestHelper helper, ComputerBlockEntity original, CompoundTag saved) {
        var pos = original.getBlockPos(); var state = original.getBlockState();
        helper.getLevel().removeBlockEntity(pos);
        var restored = (ComputerBlockEntity) Objects.requireNonNull(BlockEntity.loadStatic(pos, state, saved, helper.getLevel().registryAccess()));
        helper.getLevel().setBlockEntity(restored); return restored;
    }
}
