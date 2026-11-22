package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusFlowTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowArchiveTest.ARCHIVE;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class XBusResultArchiveTest {
    @Test void explicitResultSurvivesDocumentOwnerAndEveryWaitCompletionBoundary() {
        for (var step : List.of(new FlowProgram.Read("read", "x0", "result", PACKET, true),
                new FlowProgram.Write("write", "x0", "packet", Optional.of("result")),
                new FlowProgram.AwaitReadable("wait", "x0", Optional.of("result")))) {
            var initial = new DataValue.RecordValue(Map.of("packet", packet(12)));
            var program = new FlowProgram(Map.of("packet", PACKET), List.of(step));
            var document = new ProgramDocument(program.expressions(), Map.of(), Optional.empty(),
                Optional.of(new FlowDocumentBody(program.inputs(), program.steps(), initial)));
            var encoded = ProgramDocumentCodec.encode(document);
            assertEquals(new DataValue.IntegerValue(10), encoded.field("version"));
            assertEquals(document, ProgramDocumentCodec.decode(encoded));
            assertThrows(ArchiveException.class, () -> ProgramDocumentCodec.decode(version(encoded, 9)));
            var flow = runtime(program, initial);
            for (int boundary = 0; boundary < 3; boundary++) {
                var saved = new FlowBlockHost.Saved(program, initial, flow.checkpoint());
                var nbt = FlowBlockNbtCodec.encode(saved, ARCHIVE);
                assertEquals(10, nbt.getInt(FlowBlockNbtCodec.MARKER));
                assertEquals(saved, FlowBlockNbtCodec.decode(nbt, ARCHIVE));
                var oldOwner = nbt.copy(); oldOwner.putInt(FlowBlockNbtCodec.MARKER, 9);
                assertThrows(ArchiveException.class, () -> FlowBlockNbtCodec.decode(oldOwner, ARCHIVE));
                var raw = (DataValue.RecordValue)ValueNbtCodec.decode(FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(program, flow.checkpoint()), ARCHIVE), ARCHIVE);
                assertEquals(new DataValue.IntegerValue(11), raw.field("version"));
                assertThrows(ArchiveException.class, () -> FlowNbtCodec.decode(ValueNbtCodec.encode(version(raw, 10), ARCHIVE), ARCHIVE));
                var copy = FlowBlockNbtCodec.decode(nbt, ARCHIVE);
                flow = FlowRuntime.restore(copy.program(), copy.state(), LIMITS); flow.load();
                if (boundary == 0) flow.advance(1);
                if (boundary == 1) assertTrue(flow.rejectPausedXBus());
            }
            assertEquals(FlowRuntime.Status.COMPLETE, flow.checkpoint().status());
        }
    }
    private static DataValue.RecordValue version(DataValue.RecordValue value, long version) {
        var fields = new LinkedHashMap<>(value.fields()); fields.put("version", new DataValue.IntegerValue(version));
        return new DataValue.RecordValue(fields);
    }
}
