package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.BiocomputingWorldGameTests.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingTopologyPublicationGameTests {
    @GameTest(template = "empty", timeoutTicks = 240)
    public static void breakingAndRepairingTheProductionShellDoesNotStopPrivatePrograms(GameTestHelper helper) {
        var origin=helper.absolutePos(new BlockPos(1,1,1));
        var a=computer(helper,origin.offset(1,1,1),VillagerProfession.LIBRARIAN,1);
        var b=computer(helper,origin.offset(3,1,1),VillagerProfession.FLETCHER,2);
        var computers=List.of(a,b);
        for (int i=0;i<computers.size();i++) {
            var base=counter().program();
            var program=new ProgramArchive(new ProgramDefinition(base.graph(),Map.of("count",
                new ProgramDefinition.Cell(DataType.Scalar.INTEGER,new DataValue.IntegerValue(i*10L))),base.writes()),Map.of());
            helper.assertValueEqual(host(computers.get(i)).install(program,ComputerRuntime.MemoryPolicy.RESET),
                ComputerRuntime.InstallResult.APPLIED,"Install independent counters");
        }
        var hardware=computers.stream().map(c -> host(c).hardware()).toList();
        var profiles=computers.stream().map(c -> c.production().installedProfile().orElseThrow()).toList();
        var structureIds=new ArrayList<UUID>();
        var shell=CBBlocks.COMPUTER_CASING.get().defaultBlockState();
        for (int x=0;x<5;x++) for (int y=0;y<4;y++) for (int z=0;z<3;z++)
            if (x==0 || x==4 || y==0 || y==3 || z==0 || z==2)
                helper.getLevel().setBlock(origin.offset(x,y,z),shell,Block.UPDATE_ALL);
        var broken=origin.offset(2,1,0);
        helper.startSequence().thenWaitUntil(() -> {
            for (var c : computers) helper.assertTrue(c.production().structureOnline(),"Wait for actual formed production shell: "+c.production().availabilityReason());
        }).thenExecute(() -> {
            computers.forEach(c -> structureIds.add(c.production().computerStructureMemberId().orElseThrow()));
            for (var c : computers) helper.assertTrue(c.production().hasIndexedShellMembership() && c.production().radioTopologyEligible(),
                "Each real member is indexed and eligible before the shell removal callback");
            helper.getLevel().setBlock(broken,Blocks.AIR.defaultBlockState(),Block.UPDATE_ALL);
            for (var c : computers) helper.assertTrue(!c.production().radioTopologyEligible(),
                "Physical removal fences each indexed member immediately, before another scan tick");
            var generations=computers.stream().map(c -> c.production().radioEndpointGeneration()).toList();
            com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer.ProductionComputer
                .shellRemovalBegins(helper.getLevel(),broken);
            for (int i=0;i<computers.size();i++) {
                var c=computers.get(i);
                helper.assertValueEqual(c.production().radioEndpointGeneration(),generations.get(i),
                    "A repeated teardown callback does not advance an already closed fence");
                c.production().ensureRadioEndpointGeneration(generations.get(i)+1);
                helper.assertTrue(!c.production().radioTopologyEligible(),
                    "A router generation floor cannot reopen a physically broken shell");
            }
        }).thenWaitUntil(() -> {
            for (var c : computers) helper.assertTrue(!c.production().structureOnline() && !c.production().coordinatorOnline(),"Broken shell withdraws production availability");
        }).thenExecute(() -> computers.forEach(c -> host(c).begin(new DataValue.RecordValue(Map.of()))))
        .thenWaitUntil(() -> {
            helper.assertValueEqual(memory(host(a),"count"),new DataValue.IntegerValue(1),"Local librarian event runs while production is offline");
            helper.assertValueEqual(memory(host(b),"count"),new DataValue.IntegerValue(11),"Local fletcher uses its own RAM while production is offline");
        }).thenExecute(() -> helper.getLevel().setBlock(broken,shell,Block.UPDATE_ALL)).thenWaitUntil(() -> {
            for (var c : computers) helper.assertTrue(c.production().structureOnline(),"Repair republishes through the actual transaction: "+c.production().availabilityReason());
        }).thenExecute(() -> {
            for (int i=0;i<computers.size();i++) {
                var c=computers.get(i);
                helper.assertTrue(c.production().hasIndexedShellMembership() && c.production().radioTopologyEligible(),
                    "Accepted repaired structure restores the real shell registration and radio eligibility");
                helper.assertValueEqual(c.production().computerStructureMemberId().orElseThrow(),structureIds.get(i),"Keep the same production structure identity");
                helper.assertValueEqual(host(c).hardware(),hardware.get(i),"Retain distinct hardware rather than pooling it");
                helper.assertValueEqual(c.production().installedProfile().orElseThrow(),profiles.get(i),"Keep the original member profile");
                host(c).begin(new DataValue.RecordValue(Map.of()));
            }
        }).thenWaitUntil(() -> {
            helper.assertValueEqual(memory(host(a),"count"),new DataValue.IntegerValue(2),"First program continues after repair");
            helper.assertValueEqual(memory(host(b),"count"),new DataValue.IntegerValue(12),"Second program continues independently");
        }).thenSucceed();
    }
}
