package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.BiocomputingWorldGameTests.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResidentView;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingResidentOwnershipGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void missingLegacyProductionProfileDoesNotDisableIndependentResidentsOrTheirPrograms(GameTestHelper helper) {
        var a = computer(helper, helper.absolutePos(new BlockPos(2,2,2)), VillagerProfession.LIBRARIAN, 1);
        var b = computer(helper, helper.absolutePos(new BlockPos(5,2,2)), VillagerProfession.FLETCHER, 2);
        for (var computer : java.util.List.of(a,b)) {
            var host = host(computer);
            host.install(counter(), ComputerRuntime.MemoryPolicy.RESET);
            host.setPaused(true); host.begin(new DataValue.RecordValue(Map.of()));
            var before = host.snapshot().orElseThrow();
            var hardware = host.hardware(); var resident = computer.residentSnapshot();
            var saved = computer.saveWithoutMetadata(helper.getLevel().registryAccess());
            BiocomputingComputerArchiveGameTests.historicalData(saved).remove("Profile");
            computer.loadWithComponents(saved, helper.getLevel().registryAccess());
            helper.assertTrue(net.minecraft.world.item.ItemStack.matches(resident, computer.residentSnapshot()), "Physical resident unchanged");
            helper.assertValueEqual(host.hardware(), hardware, "Each original resident still selects its own configured hardware");
            var view = ComputerResidentView.load(computer.getUpdateTag(helper.getLevel().registryAccess())
                .getCompound("ComputerClientState")).orElseThrow();
            helper.assertTrue(view.renderResident(), "Physical residents remain visible without production Profile");
            var expectedView = ComputerResidentView.fromSnapshot(resident, helper.getLevel().registryAccess());
            helper.assertValueEqual(view, expectedView, "Actual client packet retains this resident's profession and level");
            helper.assertValueEqual(computer.residentView(), expectedView, "Actual renderer's neutral host exposes the accepted physical projection");
            helper.assertValueEqual(computer.saveWithoutMetadata(helper.getLevel().registryAccess()).get("ComputerData"),
                saved.get("ComputerData"), "Never fabricate a missing production profile");
            var displayPacket = computer.getUpdateTag(helper.getLevel().registryAccess());
            computer.handleUpdateTag(displayPacket, helper.getLevel().registryAccess());
            helper.assertValueEqual(displayPacket.getAllKeys(), java.util.Set.of("ComputerClientState"), "Only resident projection is shared");
            var invalidPacket = displayPacket.copy();
            invalidPacket.putString("Undeclared", "reject before any display extension");
            invalidPacket.put("AdvancedGoggleTarget", new net.minecraft.nbt.CompoundTag());
            computer.handleUpdateTag(invalidPacket, helper.getLevel().registryAccess());
            helper.assertValueEqual(computer.residentView(), expectedView, "Invalid packet preserves the neutral resident view");
            helper.assertTrue(host.communicationIdentity().isPresent(), "Old production Profile must not gate independent computation");
            helper.assertValueEqual(host.snapshot().orElseThrow(), before, "Paused program and private RAM survive production-only damage");
            host.setPaused(false);
        }
        helper.assertTrue(host(a).hardware().limits().hardware().workPerSlice() < host(b).hardware().limits().hardware().workPerSlice(), "Retain actual mixed hardware differences");
        helper.runAfterDelay(10, () -> {
            helper.assertValueEqual(memory(host(a), "count"), new DataValue.IntegerValue(1), "First independent program resumes once");
            helper.assertValueEqual(memory(host(b), "count"), new DataValue.IntegerValue(1), "Second independent program resumes once");
            helper.assertTrue(!a.saveWithoutMetadata(helper.getLevel().registryAccess()).getCompound("ComputerData").contains("Profile") && !b.saveWithoutMetadata(helper.getLevel().registryAccess()).getCompound("ComputerData").contains("Profile"), "Computation does not silently repair production state");
            helper.succeed();
        });
    }
}
