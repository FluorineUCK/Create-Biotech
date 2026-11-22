package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowForeverPersistenceTest {
    @Test void initialAndReenteredLoopRequireNewSnapshotOwnerAndDocumentEvenBeforeFirstBackEdge() {
        var limits = new ResourceLimits(1000, 100000, 1); var archive = new ArchiveLimits(100000, 1000000, 1000000);
        var empty = new DataValue.RecordValue(Map.of());
        var program = new FlowProgram(Map.of(), List.of(new FlowProgram.Forever("loop"), new FlowProgram.EndForever("end")));
        var document = new ProgramDocument(program.expressions(), Map.of(), Optional.empty(), Optional.of(new FlowDocumentBody(Map.of(), program.steps(), empty)));
        var encoded = ProgramDocumentCodec.encode(document);
        assertEquals(new DataValue.IntegerValue(7), encoded.field("version")); assertEquals(document, ProgramDocumentCodec.decode(encoded));
        for (int version = 3; version < 7; version++) {
            var fields = new LinkedHashMap<>(encoded.fields()); fields.put("version", new DataValue.IntegerValue(version));
            assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class,
                () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(fields))).code());
        }
        var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program, empty, limits);
        for (int i = 0; i < 5; i++) {
            var data = FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(program, runtime.checkpoint()), archive);
            var fields = new LinkedHashMap<>(((DataValue.RecordValue)ValueNbtCodec.decode(data, archive)).fields());
            assertEquals(new DataValue.IntegerValue(6), fields.get("version"));
            fields.put("version", new DataValue.IntegerValue(5));
            assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class,
                () -> FlowNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(fields), archive), archive)).code());
            var saved = new FlowBlockHost.Saved(program, empty, runtime.checkpoint());
            var owner = FlowBlockNbtCodec.encode(saved, archive);
            assertEquals(5, owner.getInt(FlowBlockNbtCodec.MARKER)); assertEquals(saved, FlowBlockNbtCodec.decode(owner, archive));
            owner.putInt(FlowBlockNbtCodec.MARKER, 4);
            assertThrows(ArchiveException.class, () -> FlowBlockNbtCodec.decode(owner, archive));
            runtime.advance(1);
        }
    }
}
