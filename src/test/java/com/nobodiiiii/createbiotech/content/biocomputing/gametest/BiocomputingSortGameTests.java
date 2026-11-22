package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingSortGameTests {
    @GameTest(template = "empty", timeoutTicks = 120)
    public static void actualComputerReloadsDuringUnicodeSortAndCommitsOnlyWhenFinished(GameTestHelper helper) {
        var level=helper.getLevel();var pos=helper.absolutePos(new BlockPos(2,2,2));
        var block=BiocomputingWorldGameTests.computer(helper,pos);var host=BiocomputingWorldGameTests.host(block);
        String prefix="\uD83D\uDE00".repeat(20);
        var c=new DataValue.TextValue(prefix+"c");var a=new DataValue.TextValue(prefix+"a");var b=new DataValue.TextValue(prefix+"b");
        var source=new DataValue.ListValue(List.of(c,a,b));var empty=new DataValue.ListValue(List.of());
        var key=new ModuleDefinition.Key("public_identity",1);
        var module=new ComputationGraph(Map.of("item",DataType.Scalar.TEXT),Map.of("item",new ComputationGraph.Input("item")),Map.of("key","item"));
        var graph=new ComputationGraph(Map.of("items",new DataType.ListType(DataType.Scalar.TEXT)),Map.of(
            "items",new ComputationGraph.Input("items"),"sorted",new ComputationGraph.Sort("items",key,"item",Map.of(),"key",false)),Map.of("result","sorted"));
        var archive=new ProgramArchive(new ProgramDefinition(graph,Map.of("sorted",new ProgramDefinition.Cell(new DataType.ListType(DataType.Scalar.TEXT),empty)),
            List.of(new ProgramDefinition.Write("sorted","result"))),Map.of(key,new ModuleDefinition(key,module)));
        var limits=new ArchiveLimits(10000,100000,1000000);
        host.install(ProgramEditorWire.decode(ProgramEditorWire.encode(archive,limits),limits),ComputerRuntime.MemoryPolicy.RESET);
        host.begin(new DataValue.RecordValue(Map.of("items",source)));host.setPaused(true);
        boolean insideComparison=false;
        for(int i=0;i<100&&!insideComparison;i++) {
            host.step();
            var state=host.snapshot().orElseThrow().machine();
            helper.assertValueEqual(state.status(),ComputationMachine.Status.RUNNING,"Sort stays running until all keys and comparisons finish");
            for(var frame:state.frames()) if(frame instanceof MachineCheckpoint.SortState sort && sort.progress().isPresent())
                insideComparison=((DataValue.IntegerValue)sort.progress().orElseThrow().field("offset")).value()>0;
        }
        helper.assertTrue(insideComparison,"Save after a code point was compared, not just after creating the frame");
        helper.assertValueEqual(BiocomputingWorldGameTests.memory(host,"sorted"),empty,"Partial sorting must not write RAM");
        var before=host.snapshot().orElseThrow();var state=block.getBlockState();var saved=block.saveWithFullMetadata(level.registryAccess());
        level.removeBlockEntity(pos);
        var replacement=(ComputerBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(pos,state,saved,level.registryAccess()));
        level.setBlockEntity(replacement);var restored=BiocomputingWorldGameTests.host(replacement);restored.document();
        helper.assertValueEqual(restored.snapshot().orElseThrow(),before,"Actual block NBT load preserves all progress without executing work");
        helper.runAfterDelay(8,()->{
            helper.assertValueEqual(restored.snapshot().orElseThrow().machine(),before.machine(),"Real ticks respect the saved pause state");
            restored.setPaused(false);
            helper.runAfterDelay(45,()->{
                var result=restored.snapshot().orElseThrow().machine();
                helper.assertValueEqual(result.status(),ComputationMachine.Status.SUCCEEDED,"Real ticks finish the restored sorting event");
                helper.assertValueEqual(BiocomputingWorldGameTests.memory(restored,"sorted"),new DataValue.ListValue(List.of(a,b,c)),"Public pure identity subgraph orders text values");
                helper.assertValueEqual(source,new DataValue.ListValue(List.of(c,a,b)),"Original frozen source remains unchanged");
                helper.succeed();
            });
        });
    }
}
