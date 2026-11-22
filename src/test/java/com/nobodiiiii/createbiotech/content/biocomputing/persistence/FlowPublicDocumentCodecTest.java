package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowPublicDocumentCodecTest {
    static ProgramArchive archive() {
        return new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of("counter", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(0))), List.of()), Map.of());
    }
    static ProgramDocument document(FlowDocumentBody body) { return new ProgramDocument(archive(), Map.of(), Optional.empty(), Optional.of(body)); }
    static DataValue.RecordValue downgrade(DataValue.RecordValue encoded) {
        var fields = new LinkedHashMap<>(encoded.fields()); fields.put("version", new DataValue.IntegerValue(23)); return new DataValue.RecordValue(fields);
    }
    static void assertVersionAndRejectDowngrade(ProgramDocument document) {
        var encoded = ProgramDocumentCodec.encode(document);
        assertEquals(new DataValue.IntegerValue(24), encoded.field("version"));
        assertEquals(document, ProgramDocumentCodec.decode(encoded));
        assertThrows(ArchiveException.class, () -> ProgramDocumentCodec.decode(downgrade(encoded)));
    }
    @Test void detachedDefinitionPublicStatementsCannotHideFromNewSchemaGate() {
        var definition = List.<FlowProgram.Step>of(new FlowProgram.Procedure("definition", new ModuleDefinition.Key("increment", 1), Map.of(), Map.of(), Map.of()),
            new FlowProgram.Shared("change", "counter", new FlowInput.Literal(new DataValue.IntegerValue(1)), true), new FlowProgram.EndProcedure("end"));
        var canvas = new FlowBlockLayout(FlowBlockLayout.DEFAULT.main(), List.of(new FlowBlockLayout.Stack(new ProgramDocument.Position(280, 16), definition)));
        var body = new FlowDocumentBody(Map.of(), List.of(), new DataValue.RecordValue(Map.of()), canvas, Map.of(), FlowProgram.Execution.REGISTER_V1);
        assertVersionAndRejectDowngrade(document(body));
    }
    @Test void parkedPublicStatementAndInlinePublicReporterCannotHideFromNewSchemaGate() {
        var statement = List.<FlowProgram.Step>of(new FlowProgram.Shared("parked", "counter", new FlowInput.Literal(new DataValue.IntegerValue(1)), true));
        var canvas = new FlowBlockLayout(FlowBlockLayout.DEFAULT.main(), List.of(new FlowBlockLayout.Stack(new ProgramDocument.Position(280, 16), statement)));
        assertVersionAndRejectDowngrade(document(new FlowDocumentBody(Map.of(), List.of(), new DataValue.RecordValue(Map.of()), canvas, Map.of(), FlowProgram.Execution.REGISTER_V1)));
        var reporter = new ComputationGraph(Map.of(), Map.of("read", new ComputationGraph.ReadMemory("counter")), Map.of("value", "read"));
        var body = new FlowDocumentBody(Map.of(), List.of(new FlowProgram.Evaluate("evaluate", "inline", new FlowCallBindings(Map.of(), Map.of("value", "result")))),
            new DataValue.RecordValue(Map.of()), FlowBlockLayout.DEFAULT, Map.of("evaluate", reporter), FlowProgram.Execution.REGISTER_V1);
        assertVersionAndRejectDowngrade(document(body));
    }
    @Test void declaringCellsAndKeepingOldManualTransactionsDoesNotBumpDocumentSemantics() {
        var body = new FlowDocumentBody(Map.of(), List.of(new FlowProgram.Memory("begin", FlowProgram.MemoryKind.BEGIN, "", ""),
            new FlowProgram.Memory("end", FlowProgram.MemoryKind.ROLLBACK, "", "")), new DataValue.RecordValue(Map.of()),
            FlowBlockLayout.DEFAULT, Map.of(), FlowProgram.Execution.REGISTER_V1);
        var document = document(body); var encoded = ProgramDocumentCodec.encode(document);
        assertEquals(new DataValue.IntegerValue(23), encoded.field("version"));
        assertEquals(document, ProgramDocumentCodec.decode(encoded));
    }
}
