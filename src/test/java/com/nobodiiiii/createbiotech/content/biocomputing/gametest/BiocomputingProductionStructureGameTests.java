package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.BiocomputingWorldGameTests.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionLayout.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingProductionStructureGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void malformedStructureSurvivesReconstructionWithoutDisablingPrivateComputation(GameTestHelper helper) {
        var pos = helper.absolutePos(new BlockPos(2,2,2));
        var original = computer(helper,pos,VillagerProfession.LIBRARIAN,1);
        var computer = host(original);
        helper.assertValueEqual(computer.install(counter(),ComputerRuntime.MemoryPolicy.RESET),
            ComputerRuntime.InstallResult.APPLIED, "Install the private counter");
        computer.setPaused(true); computer.begin(new DataValue.RecordValue(Map.of()));
        var expected = computer.snapshot().orElseThrow();
        var hardware = computer.hardware(); var resident = original.residentSnapshot();
        var bounds = new Bounds(pos.getX()-1,pos.getY()-1,pos.getZ()-1,pos.getX()+1,pos.getY()+1,pos.getZ()+1);
        var layout = new ProductionLayout(bounds,
            List.of(new Member(original.computerId().orElseThrow(),new Address(helper.getLevel().dimension(),null,pos),
                ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN,1,0).orElseThrow())),List.of());
        // Archive fixture only: this does not pretend that a live shell was built or scanned.
        var casing = new LinkedHashSet<BlockPos>();
        for (int x=-1; x<=1; x++) for (int y=-1; y<=1; y++) for (int z=-1; z<=1; z++)
            if (x!=0 || y!=0 || z!=0) casing.add(pos.offset(x,y,z));
        var structure = new ProductionStructure(layout,casing,ProductionStructure.chunksFor(bounds));
        var invalid = ProductionStructureCodec.saveRevision(new ProductionStructure.Revision(
            UUID.randomUUID(),7,original.computerId().orElseThrow(),structure));
        var panels = new ListTag(); panels.add(StringTag.valueOf("not a panel"));
        invalid.getCompound("Snapshot").put("Panels",panels);
        var saved = original.saveWithFullMetadata(helper.getLevel().registryAccess());
        BiocomputingComputerArchiveGameTests.historicalData(saved).put("Structure",invalid);
        var current = original;
        for (int pass=0; pass<2; pass++) {
            var state = current.getBlockState(); helper.getLevel().removeBlockEntity(pos);
            current = (ComputerBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(pos,state,saved,helper.getLevel().registryAccess()));
            helper.getLevel().setBlockEntity(current);
            helper.assertValueEqual(current.computerId(),original.computerId(), "Opaque historical data cannot disable the case identity");
            var next = current.saveWithFullMetadata(helper.getLevel().registryAccess());
            helper.assertValueEqual(next.get("ComputerData"),saved.get("ComputerData"), "Retain the exact invalid archive across both saves");
            helper.assertTrue(host(current).communicationIdentity().isPresent(), "Restore the durable generic host without executing");
            helper.assertValueEqual(host(current).snapshot().orElseThrow(),expected, "Private program, memory and paused event survive");
            helper.assertValueEqual(host(current).hardware(),hardware, "Librarian hardware is independent from production structure validation");
            helper.assertTrue(ItemStack.matches(current.residentSnapshot(),resident), "Keep the original physical resident");
            saved = next;
        }
        var restored = host(current); restored.setPaused(false);
        helper.runAfterDelay(10,() -> {
            helper.assertValueEqual(memory(restored,"count"),new DataValue.IntegerValue(1), "Resume the original generic event exactly once");
            helper.succeed();
        });
    }
}
