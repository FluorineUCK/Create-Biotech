package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins;
import java.util.*;
import org.junit.jupiter.api.Test;

class SimpleIoPersistenceTest {
    @Test void legacyElectricalOutputsSaturateWithoutRescalingOrEditingLocalsAndNewSavesAreStrict() {
        for (long level : new long[]{0, 12, 15, 16, 70, 100}) {
            var p = program();
            var input = new DataValue.RecordValue(Map.of("level", new DataValue.IntegerValue(level)));
            var flow = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), p, input, LIMITS);
            flow.advanceRound(Map.of());
            var root = new LinkedHashMap<>(((DataValue.RecordValue)FlowNbtCodec.value(new FlowNbtCodec.Snapshot(p, flow.checkpoint()))).fields());
            root.put("version", new DataValue.IntegerValue(4));
            var state = new LinkedHashMap<>(((DataValue.RecordValue)root.get("state")).fields());
            state.keySet().removeAll(Set.of("operation", "repeats", "input_invocation", "calls", "entry"));
            state.put("signal_outputs", new DataValue.RecordValue(Map.of("p0", new DataValue.IntegerValue(level))));
            root.put("state", new DataValue.RecordValue(state));
            var legacy = ValueNbtCodec.encode(new DataValue.RecordValue(root), ARCHIVE);
            var original = legacy.copy();
            var decoded = FlowNbtCodec.decode(legacy, ARCHIVE);
            assertEquals(Map.of("p0", (int)Math.min(level, 15)), decoded.state().signals().outputs());
            assertEquals(input, decoded.state().locals());
            assertEquals(original, legacy, "Loading cannot mutate the input archive");
            var current = (DataValue.RecordValue)FlowNbtCodec.value(decoded);
            assertEquals(new DataValue.IntegerValue(20), current.field("version"));
            assertEquals(decoded, FlowNbtCodec.decode(FlowNbtCodec.encode(decoded, ARCHIVE), ARCHIVE));
            var bad = new LinkedHashMap<>(((DataValue.RecordValue)current.field("state")).fields());
            bad.put("signal_outputs", new DataValue.RecordValue(Map.of("p0", new DataValue.IntegerValue(16))));
            var currentFields = new LinkedHashMap<>(current.fields()); currentFields.put("state", new DataValue.RecordValue(bad));
            assertThrows(ArchiveException.class, () -> FlowNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(currentFields), ARCHIVE), ARCHIVE));
        }
    }
    private static final ResourceLimits LIMITS = new ResourceLimits(100, 100000, 100);
    private static final ArchiveLimits ARCHIVE = new ArchiveLimits(100000, 1000000, 1000000);
    private static FlowProgram program() {
        return new FlowProgram(Map.of("level", DataType.Scalar.INTEGER), List.of(new FlowProgram.SignalWrite("write", "p0", "level"),
            new FlowProgram.SignalRead("read", "p0", "value")));
    }
    private static DataValue.RecordValue inputs() { return new DataValue.RecordValue(Map.of("level", new DataValue.IntegerValue(0))); }
    @Test void debuggerDistinguishesOutputZeroFromAnInputPinWithoutDrivingIt() {
        var p=program(); var f=new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), p, inputs(), LIMITS);
        f.advanceRound(Map.of());
        var before=f.checkpoint();
        var saved=new FlowBlockHost.Saved(p,inputs(),before);
        var status=ComputerStatusView.flow(Optional.of(saved),true,Optional.empty());
        assertEquals(new DataValue.RecordValue(Map.of("p0",new DataValue.IntegerValue(0))),status.field("signal_outputs"));
        assertEquals(before,f.checkpoint());
        f.advanceRound(Map.of("p0",0));
        status=ComputerStatusView.flow(Optional.of(new FlowBlockHost.Saved(p,inputs(),f.checkpoint())),true,Optional.empty());
        assertEquals(new DataValue.RecordValue(Map.of()),status.field("signal_outputs"));
    }
    @Test void portableDocumentsAndSnapshotsPreserveSignalsAndRejectDowngradeAndUnknownFields() {
        var p=program(); var document=new ProgramDocument(p.expressions(),Map.of(),Optional.empty(),Optional.of(new FlowDocumentBody(p.inputs(),p.steps(),inputs())));
        var data=ProgramDocumentCodec.encode(document);
        assertEquals(new DataValue.IntegerValue(6),data.field("version"));
        assertEquals(document,ProgramDocumentCodec.decode(data));
        for(long version:new long[]{3,4,5}) {
            var fields=new LinkedHashMap<>(data.fields()); fields.put("version",new DataValue.IntegerValue(version));
            assertThrows(ArchiveException.class,()->ProgramDocumentCodec.decode(new DataValue.RecordValue(fields)));
        }
        var f=new FlowRuntime(UUID.randomUUID(),UUID.randomUUID(),p,inputs(),LIMITS); f.advanceRound(Map.of());
        var saved=new FlowBlockHost.Saved(p,inputs(),f.checkpoint());
        assertEquals(saved,FlowBlockNbtCodec.decode(FlowBlockNbtCodec.encode(saved,ARCHIVE),ARCHIVE));
        var snapshot=(DataValue.RecordValue)FlowNbtCodec.value(new FlowNbtCodec.Snapshot(p,f.checkpoint()));
        for(long version:new long[]{1,2,3}) {
            var fields=new LinkedHashMap<>(snapshot.fields()); fields.put("version",new DataValue.IntegerValue(version));
            assertThrows(ArchiveException.class,()->FlowNbtCodec.readValue(new DataValue.RecordValue(fields)));
        }
        var fields=new LinkedHashMap<>(snapshot.fields());
        var state=new LinkedHashMap<>(((DataValue.RecordValue)snapshot.field("state")).fields()); state.remove("signal_outputs");
        fields.put("state",new DataValue.RecordValue(state));
        assertThrows(ArchiveException.class,()->FlowNbtCodec.readValue(new DataValue.RecordValue(fields)));
        state.put("signal_outputs",new DataValue.RecordValue(Map.of("p0",new DataValue.IntegerValue(Long.MAX_VALUE))));
        fields.put("state",new DataValue.RecordValue(state));
        assertThrows(ArchiveException.class,()->FlowNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(fields),ARCHIVE),ARCHIVE));
        assertThrows(FlowPins.Unavailable.class,()->FlowPins.validate(p,new HardwarePins(List.of("x0", "x1"))),"Portable documents do not invent signal pins on XBus-only hardware");
    }
}
