package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowControlInputsHostTest {
    static final ComputerHostLimits LIMITS = new ComputerHostLimits(new ResourceLimits(500,100000,1), new ArchiveLimits(100000,1000000,1000000));
    static ComputationGraph binary(ScalarOperation operation,long right) {
        return new ComputationGraph(Map.of("n",DataType.Scalar.INTEGER),Map.of("n",new ComputationGraph.Input("n"),
            "k",new ComputationGraph.Constant(new DataValue.IntegerValue(right)),"result",new ComputationGraph.Scalar(operation,Map.of("left","n","right","k"))),Map.of("value","result"));
    }
    static ProgramArchive expressions() {
        var entries=new LinkedHashMap<String,ProgramDefinition.Entry>();
        entries.put("done",new ProgramDefinition.Entry(binary(ScalarOperation.EQUAL_INTEGER,3),List.of(),List.of()));
        entries.put("increment",new ProgramDefinition.Entry(binary(ScalarOperation.ADD_INTEGER,1),List.of(),List.of()));
        entries.put("count",new ProgramDefinition.Entry(binary(ScalarOperation.ADD_INTEGER,0),List.of(),List.of()));
        entries.put("bad",new ProgramDefinition.Entry(binary(ScalarOperation.DIVIDE_INTEGER,0),List.of(),List.of()));
        var empty=new ComputationGraph(Map.of(),Map.of(),Map.of());
        return new ProgramArchive(new ProgramDefinition(empty,Map.of(),List.of(),List.of(),entries),Map.of());
    }
    static FlowProgram.Evaluate increment(String id,String target) { return new FlowProgram.Evaluate(id,"increment",new FlowCallBindings(Map.of("n",target),Map.of("value",target))); }
    static FlowInput.Expression done() { return new FlowInput.Expression("done","value",Map.of("n","counter")); }
    static DataValue.RecordValue initial() { return new DataValue.RecordValue(Map.of("counter",new DataValue.IntegerValue(0),"count",new DataValue.IntegerValue(4),"n",new DataValue.IntegerValue(999))); }
    static FlowProgram program(List<FlowProgram.Step> steps) {
        return new FlowProgram(((DataType.RecordType)DataType.typeOf(initial())).fields(),steps,expressions());
    }
    @Test void untilReevaluatesItsExplicitInputAndIfDoesNotRunTheUnselectedGraph() {
        var p=program(List.of(new FlowProgram.Until("until",done()),increment("tick","counter"),new FlowProgram.EndUntil("end_until"),
            new FlowProgram.If("if",done()),increment("chosen","counter"),new FlowProgram.Otherwise("else"),
            new FlowProgram.Evaluate("unused","bad",new FlowCallBindings(Map.of("n","counter"),Map.of("value","counter"))),new FlowProgram.EndIf("end_if")));
        var state=restoreEveryStep(p);
        assertEquals(new DataValue.IntegerValue(4),state.locals().field("counter"));
        assertEquals(new DataValue.IntegerValue(999),state.locals().field("n"));
    }
    @Test void repeatCapturesTheCountOnceEvenWhenItsExplicitSourceChangesInsideTheBody() {
        var p=program(List.of(new FlowProgram.Repeat("repeat",new FlowInput.Expression("count","value",Map.of("n","count"))),
            increment("counter_inc","counter"),increment("count_inc","count"),new FlowProgram.EndRepeat("end")));
        var state=restoreEveryStep(p);
        assertEquals(new DataValue.IntegerValue(4),state.locals().field("counter"));
        assertEquals(new DataValue.IntegerValue(8),state.locals().field("count"));
    }
    @Test void newBindingsRequireTheirOwnVersionAndCompleteExplicitParameters() {
        var p=program(List.of(new FlowProgram.If("if",done()),new FlowProgram.EndIf("end")));
        var document=new ProgramDocument(p.expressions(),Map.of(),Optional.empty(),Optional.of(new FlowDocumentBody(p.inputs(),p.steps(),initial())));
        var encoded=ProgramDocumentCodec.encode(document);
        assertEquals(new DataValue.IntegerValue(19),encoded.field("version"));
        assertEquals(document,ProgramDocumentCodec.decode(encoded));
        var old=new LinkedHashMap<>(encoded.fields()); old.put("version",new DataValue.IntegerValue(18));
        assertThrows(ArchiveException.class,()->ProgramDocumentCodec.decode(new DataValue.RecordValue(old)));
        for(var args:List.of(Map.<String,String>of(),Map.of("wrong","counter"))) {
            var invalid=program(List.of(new FlowProgram.If("if",new FlowInput.Expression("done","value",args)),new FlowProgram.EndIf("end")));
            assertThrows(ComputationException.class,()->invalid.validate(LIMITS.hardware()));
        }
    }
    static FlowRuntime.Checkpoint restoreEveryStep(FlowProgram program) {
        var host=new ComputerBlockHost(()->{}); host.installFlow(program,initial(),LIMITS,true); boolean computing=false;
        for(int i=0;host.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE;i++) {
            assertTrue(i<1000); var prior=host.flowSnapshot().orElseThrow().state(); host.step(LIMITS,true);
            var snapshot=host.flowSnapshot().orElseThrow(); var state=snapshot.state();
            assertEquals(prior.work()+1,state.work()); assertNotEquals(FlowRuntime.Status.FAILED,state.status(),()->state.diagnostic().toString());
            computing |= state.status()==FlowRuntime.Status.COMPUTING;
            var raw=host.write(); host.unload(); assertEquals(raw,host.write());
            var restored=new ComputerBlockHost(()->{}); restored.read(raw); restored.tick(LIMITS,true);
            assertEquals(snapshot,restored.flowSnapshot().orElseThrow());
            var value=(DataValue.RecordValue)ValueNbtCodec.decode(FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(program,state),LIMITS.archive()),LIMITS.archive());
            assertEquals(new DataValue.IntegerValue(17),value.field("version"));
            var downgraded=new LinkedHashMap<>(value.fields()); downgraded.put("version",new DataValue.IntegerValue(16));
            assertThrows(ArchiveException.class,()->FlowNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(downgraded),LIMITS.archive()),LIMITS.archive()));
            host=restored;
        }
        assertTrue(computing); return host.flowSnapshot().orElseThrow().state();
    }
}
