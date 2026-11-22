package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ValueEncodingOwnerTest {
    static final ArchiveLimits ARCHIVE = new ArchiveLimits(100000, 1000000, 2000000);
    static final ComputerHostLimits BASE = NativeLibraryOwnersTest.limits("cleric", 4);
    static final ComputerHostLimits LIMITS = new ComputerHostLimits(new ResourceLimits(4096, 2000000, 1),
        ARCHIVE, BASE.mailbox(), BASE.pins(), BASE.serial(), BASE.taskSlots(), true, false);
    static final Map<String, DataType> INPUTS = Map.of("input", DataType.Scalar.NBT, "capacity", DataType.Scalar.INTEGER);
    static final String SOURCE = "REG result NBT\nVALUE.ENCODE result input capacity\nRET";

    static FlowProgram program() {
        var key = new ModuleDefinition.Key("encode_value", 1);
        return new FlowProgram(INPUTS, List.of(
            new FlowProgram.Invoke("call", key, new FlowCallBindings(Map.of("input", "input", "capacity", "capacity"), Map.of("result", "out"))),
            new FlowProgram.Procedure("definition", key, INPUTS, Map.of("result", DataType.Scalar.NBT), Map.of("result", "result"),
                FlowBlockRules.NONE, Optional.of(new AssemblySource(SOURCE))), new FlowProgram.EndProcedure("end"))).withAssembly();
    }

    static FlowRuntime.Checkpoint run(DataValue input, long capacity, boolean reload) {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(program(), new DataValue.RecordValue(Map.of(
            "input", input, "capacity", new DataValue.IntegerValue(capacity))))));
        var document = ProgramDocumentWire.decode(ProgramDocumentWire.encode(workspace.snapshot(), ARCHIVE), ARCHIVE);
        var host = new ComputerBlockHost(() -> {});
        host.installProgram(PreparedProgram.prepare(document, LIMITS), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true);
        host.setPaused(false, LIMITS, true);
        int restored = 0;
        for (int tick = 0; tick < 150000; tick++) {
            host.tick(LIMITS, true);
            var saved = host.flowSnapshot().orElseThrow();
            var state = saved.state();
            if (state.status() == FlowRuntime.Status.COMPLETE || state.status() == FlowRuntime.Status.FAILED) return state;
            if (state.assembly().orElseThrow().build().isPresent()) {
                assertFalse(state.locals().fields().containsKey("out"), "No partial result is published");
                if (reload && restored < 3 && tick == (restored + 1) * 3000) {
                    var cold = new ComputerBlockHost(() -> {}); cold.read(host.write());
                    cold.setPaused(false, LIMITS, true);
                    assertEquals(saved, cold.flowSnapshot().orElseThrow());
                    host = cold; restored++;
                }
            }
        }
        throw new AssertionError("Encoding did not finish");
    }

    @Test void publicEncodingPreservesNativeTypesChargesRecoveryAndPublishesOnlyCompleteBytes() {
        var input = new DataValue.NbtValue(10, new DataValue.RecordValue(Map.of(
            "text", new DataValue.NbtValue(8, new DataValue.TextValue("仓库".repeat(1100))),
            "float_bits", new DataValue.NbtValue(5, new DataValue.IntegerValue(0x7fc01234)))));
        var limits = new SerialRecordCodec.Limits(32768, 2000000, ARCHIVE);
        byte[] expected = SerialRecordCodec.encode(new DataValue.RecordValue(Map.of("value", input)), limits);
        assertTrue(expected.length > 4096);
        var normal = run(input, 32768, false); var cold = run(input, 32768, true);
        assertEquals(FlowRuntime.Status.COMPLETE, normal.status(), normal.diagnostic().toString());
        assertEquals(FlowRuntime.Status.COMPLETE, cold.status(), cold.diagnostic().toString());
        assertEquals(normal.locals(), cold.locals()); assertTrue(cold.work() > normal.work(), "Cold rebuilding consumes actual CPU cycles");
        var result = (DataValue.NbtValue) normal.locals().field("out"); assertEquals(7, result.tagId());
        var bytes = ((DataValue.ListValue) result.value()).values(); assertTrue(bytes.size() > 4096);
        var stream = new SerialDecoding.Input() {
            public int size() { return bytes.size(); }
            public int byteAt(int index) { return (int)((DataValue.IntegerValue)bytes.get(index)).value() & 255; }
        };
        // Compound field order is not a byte-identity contract. Use the actual Library parser,
        // which also preserves raw NaN bits instead of round-tripping through Java float tags.
        var decoded = SerialDecoding.begin(stream, false, limits);
        for (int step = 0; !decoded.done(); step++) { assertTrue(step < 150000); decoded = decoded.advance(); }
        assertNull(decoded.decoded().error());
        assertEquals(new DataValue.RecordValue(Map.of("value", input)), decoded.decoded().value());
        assertEquals(input, normal.locals().field("input"));
        var families = ProcedureAssembler.assemble(new AssemblySource(SOURCE), INPUTS, Map.of("result", DataType.Scalar.NBT), Map.of("result", "result"), 65536, 4096).program().instructionFamilies();
        assertTrue(families.contains(InstructionFamily.ISA_1A)); assertFalse(families.contains(InstructionFamily.ISA_1B));
        assertThrows(IllegalArgumentException.class, () -> ProcedureAssembler.assemble(new AssemblySource(10, SOURCE), INPUTS, Map.of(), Map.of(), 65536, 4096));
        var shortBuffer = run(input, 8, false); assertEquals(FlowRuntime.Status.FAILED, shortBuffer.status());
        assertEquals(ComputationException.Code.INVALID_ARGUMENT, shortBuffer.diagnostic().orElseThrow().code());
        var noRam = run(input, 10000000, false); assertEquals(FlowRuntime.Status.FAILED, noRam.status());
        assertEquals(ComputationException.Code.MEMORY_LIMIT, noRam.diagnostic().orElseThrow().code());
    }
}
