package com.nobodiiiii.createbiotech.content.biocomputing.hardware;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ComputerHardwareViewCodecTest {
    @Test void selectedTaskCapacityReachesThePublicHardwareViewWithoutExposingTaskContents() {
        var selected = new HardwareSelection(HardwareSelection.Status.READY, Optional.of(RESIDENT), "control",
            new ComputerHostLimits(new ResourceLimits(128, 32768, 4), LIMITS,
                com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits.DEFAULT,
                HardwarePins.DEFAULT, SerialHardware.NONE, 6));
        var view = ComputerHardwareView.from(selected);
        var data = (DataValue.RecordValue)ComputerHardwareViewCodec.value(view);
        assertEquals(new DataValue.IntegerValue(6), ((DataValue.RecordValue)data.field("effective")).field("task_slots"));
        assertEquals(view, ComputerHardwareViewCodec.read(data));
    }
    @Test void serialSummaryRejectsMissingInvalidOrPrivateFieldsAndDoesNotInventDefaults() {
        var value = encoded(); var effective = (DataValue.RecordValue)value.field("effective");
        var port = new DataValue.RecordValue(Map.of("bits_per_tick", new DataValue.IntegerValue(32),
            "frame_bytes", new DataValue.IntegerValue(1024), "receive_frames", new DataValue.IntegerValue(2), "receive_bytes", new DataValue.IntegerValue(2048)));
        var invalid = List.of(replace(port, "frame_bytes", new DataValue.IntegerValue(Long.MAX_VALUE)),
            replace(port, "bits_per_tick", new DataValue.IntegerValue(0)),
            replace(port, "receive_frames", new DataValue.IntegerValue(0)),
            replace(port, "receive_bytes", new DataValue.IntegerValue(1)),
            replace(port, "buffer", new DataValue.ListValue(List.of())),
            replace(port, "enabled", new DataValue.BooleanValue(true)));
        for (var p : invalid) assertThrows(IllegalArgumentException.class, () -> ComputerHardwareViewCodec.read(
            replace(value, "effective", replace(effective, "serial", new DataValue.RecordValue(Map.of("s0", p))))));
        for (String name : List.of("x0", "p0", " ", "s\n0")) assertThrows(IllegalArgumentException.class,
            () -> ComputerHardwareViewCodec.read(replace(value, "effective", replace(effective, "serial", new DataValue.RecordValue(Map.of(name, port))))));
        var missing = new LinkedHashMap<>(effective.fields()); missing.remove("serial");
        assertThrows(IllegalArgumentException.class, () -> ComputerHardwareViewCodec.read(replace(value, "effective", new DataValue.RecordValue(missing))));
        assertTrue(ComputerHardwareViewCodec.read(value).effective().orElseThrow().serial().ports().isEmpty());
        var mutable = new LinkedHashMap<String, SerialHardware.Port>(); mutable.put("s0", new SerialHardware.Port(8, 128, 1, 128));
        var frozen = new SerialHardware(mutable); mutable.clear(); assertEquals(1, frozen.ports().size());
        assertThrows(UnsupportedOperationException.class, () -> frozen.ports().clear());
    }
    @Test void serialHardwareSurvivesThePublicClusterMenuEnvelopeWithoutPortGrantsOrBuffers() {
        var serial = new SerialHardware(Map.of("s0", new SerialHardware.Port(32, 1024, 2, 2048, Optional.of(new SerialHardware.IndependentDuplex(512,256)))));
        var selected = new HardwareSelection(HardwareSelection.Status.READY, Optional.of(RESIDENT), "program",
            new ComputerHostLimits(new ResourceLimits(64, 16384, 2), LIMITS,
                com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits.DEFAULT, HardwarePins.DEFAULT, serial));
        var summary = ComputerHardwareView.from(selected);
        assertEquals(serial, summary.effective().orElseThrow().serial());
        var candidate = new ClusterControlView.Candidate(BlockPos.ZERO, Optional.of(UUID.randomUUID()), List.of(), true, true, true, "ready", Optional.of(summary));
        var view = new ClusterControlView(ClusterConfiguration.empty(), List.of(candidate), "ready", 0, 0);
        var decoded = ClusterControlWire.decodeView(ClusterControlWire.encodeView(view, LIMITS), LIMITS);
        assertEquals(view, decoded); assertTrue(decoded.configuration().ports().isEmpty());
        var effective = (DataValue.RecordValue)((DataValue.RecordValue)ComputerHardwareViewCodec.value(summary)).field("effective");
        var ports = (DataValue.RecordValue)effective.field("serial");
        assertEquals(Set.of("s0"), ports.fields().keySet());
        assertEquals(Set.of("bits_per_tick", "frame_bytes", "receive_frames", "receive_bytes", "duplex","tx_frames","tx_bytes"), ((DataValue.RecordValue)ports.field("s0")).fields().keySet());
    }
    @Test void projectionPreservesTheSuppliersOptionalIdentityWithoutInventingResidentRequirements() {
        var selected = new HardwareSelection(HardwareSelection.Status.READY, Optional.empty(), "test",
            new ComputerHostLimits(new ResourceLimits(10, 2048, 2), new ArchiveLimits(100, 1024, 65536)));
        var view = ComputerHardwareView.from(selected);
        assertTrue(view.resident().isEmpty());
        assertEquals(selected.limits().hardware(), view.effective().orElseThrow().resources());
        assertEquals(view, ComputerHardwareViewCodec.read(ComputerHardwareViewCodec.value(view)));
    }
    private static final ArchiveLimits LIMITS = new ArchiveLimits(512, 16384, 65536);
    private static final ResidentHardwareIdentity RESIDENT = new ResidentHardwareIdentity(ResourceLocation.parse("minecraft:villager"), ResourceLocation.parse("example:custom_profession"), 4);
    private static ComputerHardwareView ready() {
        return new ComputerHardwareView(HardwareSelection.Status.READY, Optional.of(RESIDENT), "program",
            Optional.of(new ComputerHardwareView.Effective(new ResourceLimits(192, 49152, 6), new HardwarePins(List.of("x0", "data"), List.of("p0")))));
    }
    private static DataValue.RecordValue encoded() { return (DataValue.RecordValue)ComputerHardwareViewCodec.value(ready()); }
    private static DataValue.RecordValue replace(DataValue.RecordValue record, String key, DataValue value) {
        var fields = new LinkedHashMap<>(record.fields()); fields.put(key, value); return new DataValue.RecordValue(fields);
    }

    @Test void nonemptyHardwareSurvivesRealMenuEnvelopeWithoutExposingPrivateFields() {
        var candidate = new ClusterControlView.Candidate(BlockPos.ZERO, Optional.of(UUID.randomUUID()), List.of("port"), true, true, true, "ready", Optional.of(ready()));
        var view = new ClusterControlView(ClusterConfiguration.empty(), List.of(candidate), "ready", 0, 0);
        assertEquals(view, ClusterControlWire.decodeView(ClusterControlWire.encodeView(view, LIMITS), LIMITS));
        assertEquals(Set.of("status", "profile", "resident", "effective"), encoded().fields().keySet());
        assertEquals(Set.of("type", "profession", "level"), ((DataValue.RecordValue)encoded().field("resident")).fields().keySet());
        assertEquals(Set.of("program", "memory", "work", "register_slots", "task_slots", "isa_1a", "isa_1b", "xbus", "simple_io", "serial"), ((DataValue.RecordValue)encoded().field("effective")).fields().keySet());
        assertThrows(IllegalArgumentException.class, () -> ClusterControlWire.encodeView(view, new ArchiveLimits(1, 1, 1)));
        var root = (DataValue.RecordValue)ProgramEditorWire.decodeValue(ClusterControlWire.encodeView(view, LIMITS), LIMITS);
        var oldCandidate = new LinkedHashMap<>(((DataValue.RecordValue)((DataValue.ListValue)root.field("candidates")).values().getFirst()).fields());
        oldCandidate.remove("hardware");
        var old = replace(root, "candidates", new DataValue.ListValue(List.of(new DataValue.RecordValue(oldCandidate))));
        assertThrows(IllegalArgumentException.class, () -> ClusterControlWire.decodeView(ProgramEditorWire.encodeValue(old, LIMITS), LIMITS));
    }
    @Test void unavailableStatusesNeverProjectTheirFallbackServerLimits() {
        for (var status : HardwareSelection.Status.values()) if (status != HardwareSelection.Status.READY) {
            var selection = new HardwareSelection(status, Optional.of(RESIDENT), "program", new ComputerHostLimits(new ResourceLimits(999, 888, 777), LIMITS));
            var view = ComputerHardwareView.from(selection);
            assertTrue(view.effective().isEmpty()); assertEquals(Optional.of(RESIDENT), view.resident());
            assertEquals(view, ComputerHardwareViewCodec.read(ComputerHardwareViewCodec.value(view)));
        }
    }
    @Test void strictFieldsStatusAndPresenceRejectPartialOrMisleadingSummaries() {
        var value = encoded();
        var mutations = new ArrayList<DataValue>();
        mutations.add(replace(value, "status", new DataValue.TextValue("UNKNOWN")));
        mutations.add(replace(value, "status", new DataValue.TextValue("DISABLED")));
        mutations.add(replace(value, "resident", new DataValue.TextValue("not_an_identity")));
        mutations.add(replace(value, "effective", DataValue.NullValue.INSTANCE));
        mutations.add(replace(value, "ram", new DataValue.RecordValue(Map.of())));
        var missing = new LinkedHashMap<>(value.fields()); missing.remove("profile"); mutations.add(new DataValue.RecordValue(missing));
        for (var mutation : mutations) assertThrows(IllegalArgumentException.class, () -> ComputerHardwareViewCodec.read(mutation));
    }
    @Test void malformedNumbersIdsAndPinInventoriesAreRejected() {
        var value = encoded(); var effective = (DataValue.RecordValue)value.field("effective");
        var invalid = new ArrayList<DataValue.RecordValue>();
        invalid.add(replace(effective, "program", new DataValue.IntegerValue(-1)));
        invalid.add(replace(effective, "memory", new DataValue.TextValue("1024")));
        invalid.add(replace(effective, "work", new DataValue.IntegerValue(0)));
        invalid.add(replace(effective, "xbus", new DataValue.ListValue(List.of(new DataValue.TextValue("p0")))));
        invalid.add(replace(effective, "xbus", new DataValue.ListValue(List.of(new DataValue.TextValue("x0"), new DataValue.TextValue("x0")))));
        invalid.add(replace(effective, "xbus", new DataValue.ListValue(List.of(new DataValue.TextValue("x".repeat(HardwarePins.MAX_NAME + 1))))));
        invalid.add(replace(effective, "xbus", new DataValue.ListValue(java.util.stream.IntStream.rangeClosed(0, HardwarePins.MAX_PINS).<DataValue>mapToObj(i -> new DataValue.TextValue("x" + i)).toList())));
        for (var e : invalid) assertThrows(IllegalArgumentException.class, () -> ComputerHardwareViewCodec.read(replace(value, "effective", e)));
        var resident = (DataValue.RecordValue)value.field("resident");
        assertThrows(IllegalArgumentException.class, () -> ComputerHardwareViewCodec.read(replace(value, "resident", replace(resident, "level", new DataValue.IntegerValue(Long.MAX_VALUE)))));
        assertThrows(IllegalArgumentException.class, () -> ComputerHardwareViewCodec.read(replace(value, "resident", replace(resident, "type", new DataValue.TextValue("villager")))));
    }
    @Test void noResidentAndEmptyPinsAreDifferentAndRoundTripWithoutDefaults() {
        var absent = new ComputerHardwareView(HardwareSelection.Status.NO_RESIDENT, Optional.empty(), "", Optional.empty());
        assertEquals(absent, ComputerHardwareViewCodec.read(ComputerHardwareViewCodec.value(absent)));
        var noPins = replace((DataValue.RecordValue)encoded().field("effective"), "xbus", new DataValue.ListValue(List.of()));
        var read = ComputerHardwareViewCodec.read(replace(encoded(), "effective", noPins));
        assertTrue(read.effective().orElseThrow().pins().xbus().isEmpty());
        assertEquals(List.of("p0"), read.effective().orElseThrow().pins().simpleIo());
    }
}
