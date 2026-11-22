package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowProcedureDocumentTest {
    static final ModuleDefinition.Key KEY = new ModuleDefinition.Key("delay_increment", 1);
    static FlowProgram.Procedure definition() {
        return new FlowProgram.Procedure("definition", KEY, Map.of("value", DataType.Scalar.INTEGER),
            Map.of("result", DataType.Scalar.INTEGER), Map.of("result", "value"));
    }
    @Test void aSeparateDefinitionHatCompilesOnceWhileOrdinaryParkedBlocksStayInert() {
        var initial = new DataValue.RecordValue(Map.of("seed", new DataValue.IntegerValue(41), "value", new DataValue.IntegerValue(999)));
        var graph = new ComputationGraph(Map.of("p", DataType.Scalar.INTEGER), Map.of("p", new ComputationGraph.Input("p"),
            "one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)), "sum", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER,"p","one")), Map.of("value","sum"));
        var canvas = new FlowBlockLayout(new ProgramDocument.Position(16,16), List.of(
            new FlowBlockLayout.Stack(new ProgramDocument.Position(280,16), List.of(definition(), new FlowProgram.Sleep("sleep","value"),
                new FlowProgram.Evaluate("increment","inline",new FlowCallBindings(Map.of("p","value"),Map.of("value","value"))), new FlowProgram.EndProcedure("end"))),
            new FlowBlockLayout.Stack(new ProgramDocument.Position(16,180), List.of(new FlowProgram.Write("parked","missing_pin","missing_value")))));
        var body = new FlowDocumentBody(((DataType.RecordType)DataType.typeOf(initial)).fields(),
            List.of(new FlowProgram.Invoke("call",KEY,new FlowCallBindings(Map.of("value","seed"),Map.of("result","answer")))), initial, canvas, Map.of("increment",graph));
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(body)); var document = workspace.snapshot();
        var encoded = ProgramDocumentCodec.encode(document);
        assertEquals(new DataValue.IntegerValue(20), encoded.field("version"));
        assertEquals(document, ProgramDocumentCodec.decode(encoded));
        var old = new LinkedHashMap<>(encoded.fields()); old.put("version",new DataValue.IntegerValue(19));
        assertThrows(ArchiveException.class,()->ProgramDocumentCodec.decode(new DataValue.RecordValue(old)));
        var program = body.program(document.archive());
        assertEquals(1, program.steps().stream().filter(FlowProgram.Procedure.class::isInstance).count());
        assertTrue(program.steps().stream().noneMatch(s->s.sourceId().equals("parked")));
        var runtime = new FlowRuntime(UUID.randomUUID(),UUID.randomUUID(),program,initial,new ResourceLimits(200,100000,1));
        for(int i=0;runtime.checkpoint().status()!=FlowRuntime.Status.COMPLETE;i++) {
            assertTrue(i<120); runtime.elapseTick(); runtime.advance(1);
            assertNotEquals(FlowRuntime.Status.FAILED,runtime.checkpoint().status(),()->runtime.checkpoint().diagnostic().toString());
        }
        assertEquals(new DataValue.IntegerValue(42),runtime.checkpoint().locals().field("answer"));
        assertEquals(new DataValue.IntegerValue(999),runtime.checkpoint().locals().field("value"));
        assertEquals(initial,body.initial());
    }
}
