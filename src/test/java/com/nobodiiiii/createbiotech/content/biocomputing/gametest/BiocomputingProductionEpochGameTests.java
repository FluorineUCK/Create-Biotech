package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.BiocomputingWorldGameTests.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionLayout.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.*;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerShellMaterial;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingProductionEpochGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void invalidFrozenPanelLayoutIsRetainedButDoesNotDisableTheGenericComputer(GameTestHelper helper) {
        var pos = helper.absolutePos(new BlockPos(2,2,2));
        var original = computer(helper,pos,VillagerProfession.FLETCHER,2);
        var computer = host(original); computer.install(counter(),ComputerRuntime.MemoryPolicy.RESET);
        computer.setPaused(true); computer.begin(new DataValue.RecordValue(Map.of()));
        var expected = computer.snapshot().orElseThrow(); var hardware = computer.hardware();
        var layout = new ProductionLayout(new Bounds(pos.getX()-1,pos.getY()-1,pos.getZ()-1,pos.getX()+2,pos.getY()+2,pos.getZ()+2),
            List.of(new Member(original.computerId().orElseThrow(),new Address(helper.getLevel().dimension(),null,pos),
                ProductionResourceProfile.forProfession(ProductionProfession.FLETCHER,2,0).orElseThrow())),
            List.of(new Panel(UUID.randomUUID(),new Address(helper.getLevel().dimension(),null,pos.west()),Direction.WEST,ComputerShellMaterial.OPAQUE)));
        var invalid = ProductionEpochCodec.save(ProductionEpoch.freeze(UUID.randomUUID(),UUID.randomUUID(),layout));
        var panels = invalid.getList("Panels",Tag.TAG_COMPOUND); var second = panels.getCompound(0).copy();
        second.putUUID("PanelId",UUID.randomUUID()); panels.add(second);
        var saved = original.saveWithFullMetadata(helper.getLevel().registryAccess());
        BiocomputingComputerArchiveGameTests.historicalData(saved).put("Epoch",invalid);
        var current = original;
        for (int pass = 0; pass < 2; pass++) {
            var state = current.getBlockState(); helper.getLevel().removeBlockEntity(pos);
            current = (ComputerBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(pos,state,saved,helper.getLevel().registryAccess()));
            helper.getLevel().setBlockEntity(current);
            helper.assertValueEqual(current.computerId(),original.computerId(), "Opaque historical data cannot disable the case identity");
            var next = current.saveWithFullMetadata(helper.getLevel().registryAccess());
            helper.assertValueEqual(next.get("ComputerData"),saved.get("ComputerData"), "Preserve raw invalid epoch and unrelated fields");
            helper.assertTrue(host(current).communicationIdentity().isPresent(), "Restore the durable generic host without executing");
            helper.assertValueEqual(host(current).snapshot().orElseThrow(),expected, "Private RAM, program and paused event survive");
            helper.assertValueEqual(host(current).hardware(),hardware, "Fletcher hardware remains independent from production diagnosis");
            saved = next;
        }
        var restored = host(current); restored.setPaused(false);
        helper.runAfterDelay(10,() -> {
            helper.assertValueEqual(memory(restored,"count"),new DataValue.IntegerValue(1), "Generic event resumes once despite invalid production data");
            helper.succeed();
        });
    }
}
