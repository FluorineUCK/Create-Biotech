package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class JoinedWaitUnitTest {
    static final ResourceLimits CPU = new ResourceLimits(1024, 51200, 64);
    static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());

    @Test void nominalUnitsAreLoweredToIsaAndLegacyLocalWaitStillWorks() {
        var i = new DataValue.IntegerValue(2);
        check(i, FlowProgram.WaitUnit.TICKS, 2);
        check(i, FlowProgram.WaitUnit.SECONDS, 40);
        check(i, FlowProgram.WaitUnit.MINUTES, 2400);
        check(new DataValue.DecimalValue(0.051), FlowProgram.WaitUnit.SECONDS, 2);
        check(new DataValue.DecimalValue(-0.001), FlowProgram.WaitUnit.SECONDS, -1);
        check(new DataValue.IntegerValue(Long.MAX_VALUE), FlowProgram.WaitUnit.MINUTES, -1);
        check(new DataValue.IntegerValue(0), FlowProgram.WaitUnit.SECONDS, 0);
        var p = new FlowProgram(Map.of("duration", DataType.Scalar.INTEGER),
            List.of(new FlowProgram.Sleep("wait", "duration")));
        var flow = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), p,
            new DataValue.RecordValue(Map.of("duration", i)), CPU);
        flow.advance(64);
        assertEquals(2, flow.checkpoint().sleepTicks());
        assertEquals(FlowRuntime.Status.SLEEPING, flow.checkpoint().status());
        assertNotNull(FlowRuntime.restore(p, flow.checkpoint(), CPU));
    }

    @Test void standaloneWaitHasCanonicalAndStrictSchemas() {
        var step = new FlowProgram.Sleep("wait", new FlowInput.Literal(new DataValue.DecimalValue(0.125)),
            Optional.of(FlowProgram.WaitUnit.SECONDS));
        var p = new FlowProgram(Map.of(), List.of(step)).withAssembly();
        var doc = new ProgramDocument(p.expressions(), Map.of(), Optional.empty(),
            Optional.of(FlowDocumentBody.fromProgram(p, EMPTY)));
        var document = ProgramDocumentCodec.encode(doc);
        assertEquals(new DataValue.IntegerValue(51), field(document, "version"));
        assertEquals(doc, ProgramDocumentCodec.decode(document));
        assertThrows(ArchiveException.class, () -> ProgramDocumentCodec.decode(edit(document, "version", new DataValue.IntegerValue(35))));
        var hw = StockApplicationDriverDocuments.hardware();
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(p, EMPTY, hw, true);
        var owner = (CompoundTag)host.write();
        assertEquals(52, owner.getInt(FlowBlockNbtCodec.MARKER));
        assertEquals(p, FlowBlockNbtCodec.decode(owner, hw.archive()).program());
        var data = ValueNbtCodec.decode(owner.getCompound("Data"), hw.archive());
        var snap = field(data, "flow");
        assertEquals(new DataValue.IntegerValue(54), field(snap, "version"));
        var bad = owner.copy();
        bad.putInt(FlowBlockNbtCodec.MARKER, 25);
        assertThrows(ArchiveException.class, () -> FlowBlockNbtCodec.decode(bad, hw.archive()));
        var badFlow = owner.copy();
        badFlow.put("Data", ValueNbtCodec.encode(edit(data,"flow",edit(snap,"version",new DataValue.IntegerValue(26))), hw.archive()));
        assertThrows(ArchiveException.class, () -> FlowBlockNbtCodec.decode(badFlow, hw.archive()));
    }

    private static void check(DataValue value, FlowProgram.WaitUnit unit, long expected) {
        var step = new FlowProgram.Sleep("wait", new FlowInput.Local("duration"), Optional.of(unit));
        for (var mode : FlowProgram.Compilation.values()) {
            var base = new FlowProgram(Map.of("duration", DataType.typeOf(value)), List.of(step)).withAssembly();
            var p = new FlowProgram(base.inputs(), base.steps(), base.expressions(), base.execution(), mode);
            var flow = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), p,
                new DataValue.RecordValue(Map.of("duration", value)), CPU);
            flow.advance(64);
            var state = flow.checkpoint();
            assertEquals(expected < 0 ? FlowRuntime.Status.FAILED : expected == 0
                ? FlowRuntime.Status.COMPLETE : FlowRuntime.Status.SLEEPING, state.status(), mode.toString());
            assertEquals(Math.max(0, expected), state.sleepTicks(), mode.toString());
        }
    }

    private static DataValue field(DataValue value, String name) {
        return assertInstanceOf(DataValue.RecordValue.class, value).field(name);
    }

    private static DataValue.RecordValue edit(DataValue value, String name, DataValue replacement) {
        var copy = new LinkedHashMap<>(assertInstanceOf(DataValue.RecordValue.class, value).fields());
        copy.put(name, replacement);
        return new DataValue.RecordValue(copy);
    }
}
