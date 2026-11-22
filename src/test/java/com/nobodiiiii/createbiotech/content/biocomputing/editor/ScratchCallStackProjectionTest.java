package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScratchCallStackProjectionTest {
    @Test void nativeSavedReporter() throws Exception {
        var hardware = StockApplicationDriverDocuments.hardware();
        try (var stream = ScratchCallStackProjectionTest.class.getResourceAsStream("/biocomputing/compatibility/scratch-player-stepup-paused.nbt")) {
            assertNotNull(stream);
            var saved = NbtIo.readCompressed(stream, NbtAccounter.create(512*1024));
            var host = new ComputerBlockHost(() -> {});
            host.read(saved.getCompound("Owner")); host.tick(hardware,true);
            var state = host.flowSnapshot().orElseThrow().state();
            assertTrue(state.paused()); assertEquals(135,state.sleepTicks()); assertEquals(9,state.work());
            assertStack(host, frame("block_2","stepup",1));
            assertEquals(state,host.flowSnapshot().orElseThrow().state());
        }
    }

    @Test void pinnedRevisionsRemainDistinctInEveryCompilationPolicy() {
        for (var policy : FlowProgram.Compilation.values()) siblingReportersUnderTheSameCaller(policy);
    }

    private static void siblingReportersUnderTheSameCaller(FlowProgram.Compilation policy) {
        var hardware = StockApplicationDriverDocuments.hardware();
        var outer=new ModuleDefinition.Key("outer", 3);
        var first=new ModuleDefinition.Key("operand",1); var second=new ModuleDefinition.Key("operand",2);
        var ints=Map.<String, DataType>of("seed",DataType.Scalar.INTEGER);
        var result=Map.<String, DataType>of("result",DataType.Scalar.INTEGER);
        var graph=new ComputationGraph(ints,Map.of(
            "seed",new ComputationGraph.Input("seed"),
            "a",new ComputationGraph.ProcedureValue(first,"result",Map.of("seed","seed")),
            "b",new ComputationGraph.ProcedureValue(second,"result",Map.of("seed","seed")),
            "sum",new ComputationGraph.Scalar(ScalarOperation.ADD_INTEGER,Map.of("left","a","right","b"))),Map.of("result","sum"));
        var blank=new ComputationGraph(Map.of(),Map.of(),Map.of());
        var archive=new ProgramArchive(new ProgramDefinition(blank,Map.of(),List.of(),List.of(),
            Map.of("sum",new ProgramDefinition.Entry(graph,List.of(),List.of()))),Map.of());
        var program=new FlowProgram(ints,List.of(
            new FlowProgram.Invoke("outer_call",outer,new FlowCallBindings(Map.of("seed","seed"),Map.of("result","total"))),
            new FlowProgram.Procedure("outer_def",outer,ints,result,Map.of("result","combined")),
            new FlowProgram.Evaluate("one_reporter_slot","sum",new FlowCallBindings(Map.of("seed","seed"),Map.of("result","combined"))),
            new FlowProgram.EndProcedure("outer_end"),
            new FlowProgram.Procedure("first_def",first,ints,result,Map.of("result","seed")),
            new FlowProgram.Sleep("first_wait","seed"), new FlowProgram.EndProcedure("first_end"),
            new FlowProgram.Procedure("second_def",second,ints,result,Map.of("result","seed")),
            new FlowProgram.Sleep("second_wait","seed"), new FlowProgram.EndProcedure("second_end")),archive,FlowProgram.Execution.REGISTER_V1,policy);
        var host=new ComputerBlockHost(() -> {});
        host.installFlow(program,new DataValue.RecordValue(Map.of("seed",new DataValue.IntegerValue(2))),hardware,true);
        var seen=new LinkedHashSet<String>();
        for(int i=0;i<80;i++) {
            var state=host.flowSnapshot().orElseThrow().state();
            if (state.status()==FlowRuntime.Status.COMPLETE) break;
            assertNotEquals(FlowRuntime.Status.FAILED,state.status(),state::toString);
            if (state.status()==FlowRuntime.Status.SLEEPING) {
                var source=(DataValue.TextValue)ComputerStatusView.flow(host.flowSnapshot(), true, Optional.empty()).field("source");
                if(seen.add(source.value())){
                    long revision=switch(source.value()){case "first_wait" -> 1; case "second_wait" -> 2; default -> throw new AssertionError(source);};
                    assertStack(host,frame("outer_call","outer",3),frame("one_reporter_slot","operand",revision));
                }
            }
            host.step(hardware,true);
        }
        assertEquals(Set.of("first_wait","second_wait"),seen);
        assertStack(host);
        assertEquals(new DataValue.IntegerValue(4),host.flowSnapshot().orElseThrow().state().locals().field("total"));
    }

    static DataValue frame(String source,String name,long revision) {
        return new DataValue.RecordValue(Map.of("source",new DataValue.TextValue(source),
            "procedure",new DataValue.TextValue(name),"revision",new DataValue.IntegerValue(revision)));
    }

    static void assertStack(ComputerBlockHost host, DataValue... frames) {
        var status=ComputerStatusView.flow(host.flowSnapshot(),host.loaded(),host.diagnostic());
        var stack=assertInstanceOf(DataValue.ListValue.class,status.field("call_stack"));
        assertEquals(List.of(frames),stack.values());
    }
}
