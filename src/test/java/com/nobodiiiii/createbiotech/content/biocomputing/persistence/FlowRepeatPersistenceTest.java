package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowRepeatPersistenceTest {
    @Test void countedLoopsRequireNewDocumentSnapshotAndOwnerBeforeTheyRun() {
        var limits = new ResourceLimits(1000, 100000, 1); var archive = new ArchiveLimits(100000, 1000000, 1000000);
        var initial = new DataValue.RecordValue(Map.of("n", new DataValue.IntegerValue(2)));
        var program = new FlowProgram(Map.of("n", DataType.Scalar.INTEGER),
            List.of(new FlowProgram.Repeat("loop", "n"), new FlowProgram.EndRepeat("end")));
        var document = new ProgramDocument(program.expressions(), Map.of(), Optional.empty(),
            Optional.of(new FlowDocumentBody(program.inputs(), program.steps(), initial)));
        var encoded = ProgramDocumentCodec.encode(document);
        assertEquals(new DataValue.IntegerValue(8), encoded.field("version")); assertEquals(document, ProgramDocumentCodec.decode(encoded));
        for (int version = 3; version < 8; version++) {
            var fields = new LinkedHashMap<>(encoded.fields()); fields.put("version", new DataValue.IntegerValue(version));
            assertThrows(ArchiveException.class, () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(fields)));
        }
        var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program, initial, limits);
        for (int i = 0; i < 6; i++) {
            var snapshot = new FlowNbtCodec.Snapshot(program, runtime.checkpoint());
            var value = (DataValue.RecordValue)FlowNbtCodec.value(snapshot);
            assertEquals(new DataValue.IntegerValue(7), value.field("version"));
            assertEquals(snapshot, FlowNbtCodec.decode(FlowNbtCodec.encode(snapshot, archive), archive));
            var fields = new LinkedHashMap<>(value.fields()); fields.put("version", new DataValue.IntegerValue(6));
            assertThrows(ArchiveException.class, () -> FlowNbtCodec.readValue(new DataValue.RecordValue(fields)));
            var saved = new FlowBlockHost.Saved(program, initial, runtime.checkpoint());
            var owner = FlowBlockNbtCodec.encode(saved, archive);
            assertEquals(6, owner.getInt(FlowBlockNbtCodec.MARKER)); assertEquals(saved, FlowBlockNbtCodec.decode(owner, archive));
            owner.putInt(FlowBlockNbtCodec.MARKER, 5); assertThrows(ArchiveException.class, () -> FlowBlockNbtCodec.decode(owner, archive));
            runtime.advance(1);
        }
    }
}
