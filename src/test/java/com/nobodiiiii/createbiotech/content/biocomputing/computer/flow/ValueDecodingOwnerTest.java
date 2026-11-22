package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ValueDecodingOwnerTest {
    static final DataType PAYLOAD = new DataType.RecordType(Map.of("count", DataType.Scalar.INTEGER, "native", DataType.Scalar.NBT));
    static final DataType RESULT = new DataType.RecordType(Map.of("code", DataType.Scalar.TEXT, "value", new DataType.OptionalType(PAYLOAD)));
    static final String TYPES = "TYPE Payload RECORD count INTEGER native NBT\nTYPE Maybe OPTIONAL Payload\nTYPE Result RECORD code TEXT value Maybe\nREG result Result\n";
    static FlowProgram program(boolean roundtrip) {
        var key = new ModuleDefinition.Key("decode_value", 1);
        var inputs = Map.of("input", roundtrip ? PAYLOAD : DataType.Scalar.NBT, "workspace", DataType.Scalar.INTEGER);
        String source = TYPES + (roundtrip ? "REG bytes NBT\nREG capacity INTEGER\nCONST capacity 16384\nVALUE.ENCODE bytes input capacity\nVALUE.DECODE result bytes workspace\nRET" : "VALUE.DECODE result input workspace\nRET");
        return new FlowProgram(inputs, List.of(
            new FlowProgram.Invoke("call", key, new FlowCallBindings(Map.of("input", "input", "workspace", "workspace"), Map.of("result", "out"))),
            new FlowProgram.Procedure("definition", key, inputs, Map.of("result", RESULT), Map.of("result", "result"), FlowBlockRules.NONE,
                Optional.of(new AssemblySource(source))), new FlowProgram.EndProcedure("end"))).withAssembly();
    }
    static FlowRuntime.Checkpoint run(DataValue input, long workspaceUnits, boolean roundtrip, boolean reload) {
        var limits = ValueEncodingOwnerTest.LIMITS; var archive = ValueEncodingOwnerTest.ARCHIVE;
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(program(roundtrip),
            new DataValue.RecordValue(Map.of("input", input, "workspace", new DataValue.IntegerValue(workspaceUnits))))));
        var document = ProgramDocumentWire.decode(ProgramDocumentWire.encode(workspace.snapshot(), archive), archive);
        var host = new ComputerBlockHost(() -> {});
        host.installProgram(PreparedProgram.prepare(document, limits), ComputerRuntime.MemoryPolicy.RESET, limits, true); host.setPaused(false, limits, true);
        int decodingTicks = 0, restored = 0;
        for (int tick = 0; tick < 200000; tick++) {
            host.tick(limits, true); var saved = host.flowSnapshot().orElseThrow(); var state = saved.state();
            if (state.status() == FlowRuntime.Status.COMPLETE || state.status() == FlowRuntime.Status.FAILED) {
                if (reload) assertEquals(2, restored); return state;
            }
            var progress = state.assembly().orElseThrow().build();
            if (progress.isPresent() && new DataValue.TextValue("value_decode").equals(progress.orElseThrow().value().fields().get("kind"))) {
                assertFalse(state.locals().fields().containsKey("out")); decodingTicks++;
                if (reload && restored < 2 && decodingTicks == (restored + 1) * 400) {
                    var cold = new ComputerBlockHost(() -> {}); cold.read(host.write()); cold.setPaused(false, limits, true);
                    assertEquals(saved, cold.flowSnapshot().orElseThrow()); host = cold; restored++;
                }
            }
        }
        throw new AssertionError("Decoder did not finish");
    }
    static DataValue.NbtValue wire(DataValue value) {
        byte[] bytes = SerialRecordCodec.encode(new DataValue.RecordValue(Map.of("value", value)),
            new SerialRecordCodec.Limits(16384, 2000000, ValueEncodingOwnerTest.ARCHIVE));
        var values = new ArrayList<DataValue>(); for (byte b : bytes) values.add(new DataValue.IntegerValue(b));
        return new DataValue.NbtValue(7, new DataValue.ListValue(values));
    }
    static DataValue.RecordValue result(FlowRuntime.Checkpoint state) {
        assertEquals(FlowRuntime.Status.COMPLETE, state.status(), state.diagnostic().toString());
        return (DataValue.RecordValue)state.locals().field("out");
    }
    @Test void publicRoundtripRestoresConcreteTypesAndReturnsMalformedOrMismatchedInputAsData() {
        var input = new DataValue.RecordValue(Map.of("count", new DataValue.IntegerValue(37), "native",
            new DataValue.NbtValue(10, new DataValue.RecordValue(Map.of("text", new DataValue.NbtValue(8, new DataValue.TextValue("物料".repeat(1100))),
                "bits", new DataValue.NbtValue(6, new DataValue.IntegerValue(0x7ff8000000000123L)))))));
        var normal = run(input, 65536, true, false); var cold = run(input, 65536, true, true);
        assertEquals(new DataValue.RecordValue(Map.of("code", new DataValue.TextValue("ok"), "value", input)), result(normal));
        assertEquals(result(normal), result(cold)); assertTrue(cold.work() > normal.work()); assertEquals(input, cold.locals().field("input"));
        var families = program(true).compile(ValueEncodingOwnerTest.LIMITS.hardware()).assembly().orElseThrow().instructionFamilies();
        assertTrue(families.contains(InstructionFamily.ISA_1A)); assertFalse(families.contains(InstructionFamily.ISA_1B));
        var invalid = new DataValue.NbtValue(7, new DataValue.ListValue(List.of(new DataValue.IntegerValue(3))));
        for (var sample : Map.of("invalid_record", invalid, "type_mismatch", wire(new DataValue.IntegerValue(9)),
            "invalid_bytes", new DataValue.NbtValue(8, new DataValue.TextValue("not bytes"))).entrySet()) {
            var value = result(run(sample.getValue(), 65536, false, false));
            assertEquals(new DataValue.TextValue(sample.getKey()), value.field("code")); assertEquals(DataValue.NullValue.INSTANCE, value.field("value"));
        }
        assertEquals(new DataValue.TextValue("decode_limit"), result(run(wire(input), 4, false, false)).field("code"));
        var exhausted = run(wire(input), 2000000, false, false); assertEquals(FlowRuntime.Status.FAILED, exhausted.status());
        assertEquals(ComputationException.Code.MEMORY_LIMIT, exhausted.diagnostic().orElseThrow().code());
        assertThrows(IllegalArgumentException.class, () -> ProcedureAssembler.assemble(new AssemblySource(11, TYPES + "VALUE.DECODE result input workspace\nRET"),
            Map.of("input", DataType.Scalar.NBT, "workspace", DataType.Scalar.INTEGER), Map.of("result", RESULT), Map.of("result", "result"), 65536, 4096));
    }
}
