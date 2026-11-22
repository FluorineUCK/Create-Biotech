package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.BiocomputingWorldGameTests.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingTopologyReconciliationGameTests {
    @GameTest(template = "empty", timeoutTicks = 240)
    public static void actualShellReconciliationAndReloadKeepHeterogeneousComputersIndependent(GameTestHelper helper) {
        var origin=helper.absolutePos(new BlockPos(1,1,1));
        var a=computer(helper,origin.offset(1,1,1),VillagerProfession.LIBRARIAN,1);
        var b=computer(helper,origin.offset(3,1,1),VillagerProfession.FLETCHER,2);
        var original=List.of(a,b);
        for (int i=0;i<original.size();i++) {
            var base=counter().program();
            var program=new ProgramArchive(new ProgramDefinition(base.graph(),Map.of("count",
                new ProgramDefinition.Cell(DataType.Scalar.INTEGER,new DataValue.IntegerValue(i*10L))),base.writes()),Map.of());
            var host=host(original.get(i));
            helper.assertValueEqual(host.install(program,ComputerRuntime.MemoryPolicy.RESET),ComputerRuntime.InstallResult.APPLIED,"Install each private program");
            host.setPaused(true); host.begin(new DataValue.RecordValue(Map.of()));
        }
        var snapshots=original.stream().map(c -> host(c).snapshot().orElseThrow()).toList();
        var hardware=original.stream().map(c -> host(c).hardware()).toList();
        var profiles=original.stream().map(c -> c.production().installedProfile().orElseThrow()).toList();
        var ids=original.stream().map(c -> c.computerId().orElseThrow()).toList();
        // Actual 5 x 4 x 3 physical shell; both two-block Computers touch its outside layer, the middle stays air.
        for (int x=0;x<5;x++) for (int y=0;y<4;y++) for (int z=0;z<3;z++)
            if (x==0 || x==4 || y==0 || y==3 || z==0 || z==2)
                helper.getLevel().setBlock(origin.offset(x,y,z),CBBlocks.COMPUTER_CASING.get().defaultBlockState(),Block.UPDATE_ALL);
        var restored=new ArrayList<ComputerBlockEntity>();
        helper.startSequence().thenWaitUntil(() -> {
            for (var c : original) helper.assertTrue(c.production().currentStructureSnapshot().filter(s -> s.layout().members().size()==2).isPresent(),
                "Natural world ticks reconcile both physical members: "+c.production().availabilityReason());
        }).thenExecute(() -> {
            helper.assertTrue(helper.getLevel().getBlockState(origin.offset(2,1,1)).isAir(),"Air does not invalidate the shell");
            helper.assertValueEqual(a.production().computerStructureMemberId(),b.production().computerStructureMemberId(),"One production structure identity");
            for (int i=0;i<original.size();i++) {
                var c=original.get(i);
                var structure=c.production().currentStructureSnapshot().orElseThrow();
                for (int member=0;member<original.size();member++) {
                    var memberId = ids.get(member);
                    helper.assertValueEqual(structure.layout().members().stream().filter(n -> n.computerId().equals(memberId)).findFirst().orElseThrow().profile(),profiles.get(member),"Keep each observed member profile");
                }
                var saved=c.saveWithFullMetadata(helper.getLevel().registryAccess());
                var pos=c.getBlockPos(); var state=c.getBlockState(); helper.getLevel().removeBlockEntity(pos);
                var next=(ComputerBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(pos,state,saved,helper.getLevel().registryAccess()));
                helper.getLevel().setBlockEntity(next); restored.add(next);
                helper.assertTrue(host(next).communicationIdentity().isPresent(),"Restore the durable generic host without executing");
                helper.assertValueEqual(host(next).snapshot().orElseThrow(),snapshots.get(i),"Topology did not replace private program or RAM");
                helper.assertValueEqual(host(next).hardware(),hardware.get(i),"Retain each resident hardware selection");
                helper.assertValueEqual(next.production().installedProfile().orElseThrow(),profiles.get(i),"Production profile survives actual reload");
            }
        }).thenWaitUntil(() -> {
            for (var c : restored) helper.assertTrue(c.production().structureOnline(),"Reloaded member rejoins through the real scanner: "+c.production().availabilityReason());
        }).thenExecute(() -> {
            helper.assertTrue(host(restored.get(0)).hardware().limits().hardware().workPerSlice()
                < host(restored.get(1)).hardware().limits().hardware().workPerSlice(),"Librarian and fletcher retain separate execution budgets");
            restored.forEach(c -> host(c).setPaused(false));
        }).thenIdle(10).thenExecute(() -> {
            helper.assertValueEqual(memory(host(restored.get(0)),"count"),new DataValue.IntegerValue(1),"First private event completes once");
            helper.assertValueEqual(memory(host(restored.get(1)),"count"),new DataValue.IntegerValue(11),"Second private event uses its own initial memory");
        }).thenSucceed();
    }
}
