package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowSleepPersistenceTest {
    @Test void namedSleepKeepsItsCanonicalEncodingRatherThanAcceptingAnAlias() {
        var encoded = (DataValue.RecordValue)FlowProgramCodec.encode(program());
        assertEquals(encoded, FlowProgramCodec.encode(FlowProgramCodec.decode(encoded)));
        var step = (DataValue.RecordValue)((DataValue.ListValue)encoded.field("steps")).values().getFirst();
        assertEquals(new DataValue.TextValue("sleep"), step.field("kind"));
        assertEquals(new DataValue.TextValue("ticks"), step.field("ticks"));
        var alias = new LinkedHashMap<>(step.fields());
        alias.put("kind", new DataValue.TextValue("sleep_value"));
        alias.put("ticks", new DataValue.RecordValue(Map.of("kind", new DataValue.TextValue("local"), "name", new DataValue.TextValue("ticks"))));
        var changed = new LinkedHashMap<>(encoded.fields());
        changed.put("steps", new DataValue.ListValue(List.of(new DataValue.RecordValue(alias))));
        assertThrows(ArchiveException.class, () -> FlowProgramCodec.decode(new DataValue.RecordValue(changed)));
    }

    private static final ResourceLimits LIMITS = new ResourceLimits(100, 100000, 100);
    private static final ArchiveLimits ARCHIVE = new ArchiveLimits(100000, 1000000, 1000000);
    private static FlowProgram program() { return new FlowProgram(Map.of("ticks", DataType.Scalar.INTEGER), List.of(new FlowProgram.Sleep("delay", "ticks"))); }
    private static DataValue.RecordValue inputs(long n) { return new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(n))); }
    @Test void sleepDocumentsNeedANewVersionButReadingNeverInstallsOrExecutesThem() {
        var program = program();
        var document = new ProgramDocument(program.expressions(), Map.of(), Optional.empty(), Optional.of(new FlowDocumentBody(program.inputs(), program.steps(), inputs(3))));
        var encoded = ProgramDocumentCodec.encode(document);
        assertEquals(new DataValue.IntegerValue(5), encoded.field("version"));
        assertEquals(document, ProgramDocumentCodec.decode(encoded));
        var fields = new LinkedHashMap<>(encoded.fields()); fields.put("version", new DataValue.IntegerValue(4));
        assertThrows(ArchiveException.class, () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(fields)));
        var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program, inputs(3), LIMITS);
        runtime.advance(100); runtime.elapseTick();
        var value = (DataValue.RecordValue)FlowNbtCodec.value(new FlowNbtCodec.Snapshot(program, runtime.checkpoint()));
        assertEquals(new DataValue.IntegerValue(3), value.field("version"));
        var restored = FlowNbtCodec.decode(FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(program, runtime.checkpoint()), ARCHIVE), ARCHIVE);
        assertEquals(runtime.checkpoint(), restored.state()); assertEquals(2, restored.state().sleepTicks());
        var old = new LinkedHashMap<>(value.fields()); old.put("version", new DataValue.IntegerValue(2));
        assertThrows(ArchiveException.class, () -> FlowNbtCodec.readValue(new DataValue.RecordValue(old)));
    }
}
