package com.nobodiiiii.createbiotech.content.biocomputing.computer;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerFlowHostTest.LIMITS;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import net.minecraft.nbt.CompoundTag;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComputerAssemblyDataTest {
    static final DataType.RecordType ITEM = new DataType.RecordType(Map.of("name", DataType.Scalar.TEXT));
    static final DataType.RecordType SAMPLE = new DataType.RecordType(Map.of("items", new DataType.ListType(ITEM)));

    @Test void fieldImageIdentityDoesNotReplaceDistinctNativeUtf16KeysWithTheSamePlaceholder() {
        String a = "\ud800", b = "\ud801";
        var values = new DataValue.RecordValue(Map.of("m", new DataValue.MapValue(Map.of(a, new DataValue.IntegerValue(1), b, new DataValue.IntegerValue(2)))));
        var first = new ComputerBlockHost(() -> {}); var second = new ComputerBlockHost(() -> {});
        first.installFlow(fieldProgram(a), values, LIMITS, true); second.installFlow(fieldProgram(b), values, LIMITS, true);
        assertNotEquals(first.flowSnapshot().orElseThrow().state().assembly().orElseThrow().image(), second.flowSnapshot().orElseThrow().state().assembly().orElseThrow().image());
    }
    private static FlowProgram fieldProgram(String key) {
        var type = new DataType.MapType(DataType.Scalar.INTEGER);
        var graph = new ComputationGraph(Map.of("m", type), Map.of("m", new ComputationGraph.Input("m"), "value", new ComputationGraph.Field("m", key)), Map.of("value", "value"));
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of(), List.of(),
            Map.of("get", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
        return new FlowProgram(Map.of("m", type), List.of(new FlowProgram.Evaluate("get", "get", new FlowCallBindings(Map.of("m", "m"), Map.of("value", "value")))), archive).withAssembly();
    }

    @Test void compoundConstantsUseFullIdentityAndKeepNativeTagBitsAcrossSave() {
        var first = new DataValue.RecordValue(Map.of("raw", new DataValue.NbtValue(10, new DataValue.RecordValue(Map.of(
            "float", new DataValue.NbtValue(5, new DataValue.IntegerValue(0x7fc01234)),
            "array", new DataValue.NbtValue(7, new DataValue.ListValue(List.of(new DataValue.IntegerValue(-1)))))))));
        var second = new DataValue.RecordValue(Map.of("raw", new DataValue.NbtValue(10, new DataValue.RecordValue(Map.of(
            "float", new DataValue.NbtValue(5, new DataValue.IntegerValue(0x7fc01235)),
            "array", new DataValue.NbtValue(7, new DataValue.ListValue(List.of(new DataValue.IntegerValue(-1)))))))));
        var a = new ComputerBlockHost(() -> {}); var b = new ComputerBlockHost(() -> {});
        a.installFlow(constantProgram(first), new DataValue.RecordValue(Map.of()), LIMITS, true);
        b.installFlow(constantProgram(second), new DataValue.RecordValue(Map.of()), LIMITS, true);
        assertNotEquals(a.flowSnapshot().orElseThrow().state().assembly().orElseThrow().image(), b.flowSnapshot().orElseThrow().state().assembly().orElseThrow().image());
        a.step(LIMITS, true); var original = a.flowSnapshot().orElseThrow();
        var restored = new ComputerBlockHost(() -> {}); restored.read(a.write()); restored.tick(LIMITS, true);
        assertEquals(original, restored.flowSnapshot().orElseThrow()); restored.step(LIMITS, true);
        assertEquals(first, restored.flowSnapshot().orElseThrow().state().locals().field("value"));
        assertThrows(UnsupportedOperationException.class, () -> first.fields().put("changed", DataValue.NullValue.INSTANCE));
    }

    @Test void computersShareNeitherTextProgressNorTheirHardwareBudget() {
        var slow = new ComputerBlockHost(() -> {}); var fast = new ComputerBlockHost(() -> {});
        var high = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1000, 100000, 4), LIMITS.archive()));
        slow.installFlow(lengthProgram(), sample("abcdef"), LIMITS, true); fast.installFlow(lengthProgram(), sample("abcdef"), high, true);
        slow.setPaused(false, LIMITS, true); fast.setPaused(false, high, true);
        for (int i = 0; i < 3; i++) { slow.tick(LIMITS, true); fast.tick(high, true); }
        assertEquals(3, slow.flowSnapshot().orElseThrow().state().work());
        assertEquals(FlowRuntime.Status.READY, slow.flowSnapshot().orElseThrow().state().status());
        assertEquals(12, fast.flowSnapshot().orElseThrow().state().work());
        assertEquals(FlowRuntime.Status.COMPLETE, fast.flowSnapshot().orElseThrow().state().status());
        var other = fast.write(); slow.setPaused(true, LIMITS, true); slow.tick(LIMITS, true); assertEquals(other, fast.write());
    }

    @Test void scanAllocationFailureIsAtomicDurableAndDoesNotPublishAPartialResult() {
        var host = new ComputerBlockHost(() -> {}); var source = lengthProgram(); host.installFlow(source, sample("abc"), LIMITS, true);
        for (int i = 0; i < 4; i++) host.step(LIMITS, true);
        var before = host.flowSnapshot().orElseThrow(); var raw = host.write();
        long memory = before.state().controlUnits() + before.initialInputs().storageUnits() + before.control().storageUnits() + source.usage(LIMITS.hardware()).constantUnits();
        var small = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1000, memory, 1), LIMITS.archive()));
        host.step(small, true); var failed = host.flowSnapshot().orElseThrow();
        assertEquals(FlowRuntime.Status.FAILED, failed.state().status());
        assertEquals(ComputationException.Code.MEMORY_LIMIT, failed.state().diagnostic().orElseThrow().code());
        assertEquals(before.state().assembly(), failed.state().assembly());
        assertEquals(before.state().work() + 1, failed.state().work());
        assertFalse(failed.state().locals().fields().containsKey("length"));
        var restored = new ComputerBlockHost(() -> {}); restored.read(host.write()); restored.tick(LIMITS, true);
        assertEquals(failed, restored.flowSnapshot().orElseThrow());
        restored.tick(LIMITS, true); assertEquals(failed, restored.flowSnapshot().orElseThrow());
        // A separate owner with insufficient room even for the retained state is suspended, not failed.
        var shrunk = new ComputerBlockHost(() -> {}); shrunk.read(raw);
        shrunk.tick(ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1000, memory - 1, 1), LIMITS.archive())), true);
        assertFalse(shrunk.loaded()); assertEquals(raw, shrunk.write());
        shrunk.tick(LIMITS, true); assertEquals(before, shrunk.flowSnapshot().orElseThrow());
        shrunk.step(LIMITS, true); assertEquals(before.state().work() + 1, shrunk.flowSnapshot().orElseThrow().state().work());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"owner", "flow", "data", "offset", "instruction"})
    void invalidOrDowngradedDataContinuationsRetainTheRawSave(String mutation) {
        var host = new ComputerBlockHost(() -> {}); host.installFlow(lengthProgram(), sample("😀a"), LIMITS, true);
        for (int i = 0; i < 5; i++) host.step(LIMITS, true);
        var raw = (CompoundTag)host.write(); var owner = (DataValue.RecordValue)ValueNbtCodec.decode(raw.getCompound("Data"), LIMITS.archive());
        var flow = record(owner, "flow"); var state = record(flow, "state"); var assembly = record(state, "assembly");
        switch (mutation) {
            case "owner" -> raw.putInt(FlowBlockNbtCodec.MARKER, 20);
            case "flow" -> flow = patch(flow, "version", new DataValue.IntegerValue(22));
            case "data" -> assembly = patch(assembly, "data_version", new DataValue.IntegerValue(2));
            case "offset" -> assembly = patch(assembly, "scan", patch(record(assembly, "scan"), "offset", new DataValue.IntegerValue(1)));
            case "instruction" -> assembly = patch(assembly, "pc", new DataValue.IntegerValue(3));
        }
        raw.put("Data", ValueNbtCodec.encode(patch(owner, "flow", patch(flow, "state", patch(state, "assembly", assembly))), LIMITS.archive()));
        var restored = new ComputerBlockHost(() -> {}); restored.read(raw); restored.tick(LIMITS, true);
        assertFalse(restored.loaded()); assertEquals(raw, restored.write());
    }

    static FlowProgram constantProgram(DataValue value) {
        var graph = new ComputationGraph(Map.of(), Map.of("value", new ComputationGraph.Constant(value)), Map.of("value", "value"));
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of(), List.of(),
            Map.of("copy", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
        return new FlowProgram(Map.of(), List.of(new FlowProgram.Evaluate("copy", "copy", new FlowCallBindings(Map.of(), Map.of("value", "value")))), archive).withAssembly();
    }

    @Test void corruptScanCannotUndercountAnAlreadyConsumedAsciiPrefix() {
        var host = new ComputerBlockHost(() -> {}); host.installFlow(lengthProgram(), sample("abcdef"), LIMITS, true);
        for (int i = 0; i < 6; i++) host.step(LIMITS, true);
        var raw = (CompoundTag)host.write(); var owner = (DataValue.RecordValue)ValueNbtCodec.decode(raw.getCompound("Data"), LIMITS.archive());
        var flow = record(owner, "flow"); var state = record(flow, "state"); var assembly = record(state, "assembly");
        var scan = record(assembly, "scan"); assertEquals(new DataValue.IntegerValue(2), scan.field("offset"));
        assembly = patch(assembly, "scan", patch(scan, "points", new DataValue.IntegerValue(1)));
        state = patch(patch(state, "work", new DataValue.IntegerValue(5)), "operation", new DataValue.IntegerValue(5));
        raw.put("Data", ValueNbtCodec.encode(patch(owner, "flow", patch(flow, "state", patch(state, "assembly", assembly))), LIMITS.archive()));
        var restored = new ComputerBlockHost(() -> {}); restored.read(raw); restored.tick(LIMITS, true);
        assertFalse(restored.loaded(), "Two consumed ASCII code points cannot claim only one cycle of progress");
        assertEquals(raw, restored.write(), "Keep the invalid save; no fallback or restart");
    }
    private static DataValue.RecordValue record(DataValue.RecordValue parent, String name) { return (DataValue.RecordValue)parent.field(name); }
    private static DataValue.RecordValue patch(DataValue.RecordValue parent, String name, DataValue value) {
        var fields = new LinkedHashMap<>(parent.fields()); fields.put(name, value); return new DataValue.RecordValue(fields);
    }

    @Test void structuredInputLengthScansOneCodePointPerCycleAndRestoresWithoutPublishingPartialResults() {
        var input = sample("测😀abc");
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(lengthProgram(), input, LIMITS, true);
        int samePcSteps = 0;
        for (int i = 0; i < 30 && host.flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.READY; i++) {
            var before = host.flowSnapshot().orElseThrow();
            var raw = host.write();
            var restored = new ComputerBlockHost(() -> {}); restored.read(raw); restored.tick(LIMITS, true);
            assertTrue(restored.loaded()); assertEquals(before, restored.flowSnapshot().orElseThrow());
            assertEquals(raw, restored.write()); host = restored;
            host.tick(LIMITS, true); assertEquals(before, host.flowSnapshot().orElseThrow(), "Paused tick does not scan");
            host.step(LIMITS, true);
            var next = host.flowSnapshot().orElseThrow();
            assertEquals(before.state().work() + 1, next.state().work());
            assertEquals(input, next.initialInputs()); assertEquals(input.field("sample"), next.state().locals().field("sample"));
            assertTrue(next.state().expression().isEmpty(), "Never delegate to the graph machine");
            if (next.state().assembly().orElseThrow().pc() == before.state().assembly().orElseThrow().pc()) {
                samePcSteps++; assertFalse(next.state().locals().fields().containsKey("length"));
            }
        }
        var done = host.flowSnapshot().orElseThrow().state();
        assertEquals(FlowRuntime.Status.COMPLETE, done.status());
        assertEquals(new DataValue.IntegerValue(5), done.locals().field("length"));
        assertEquals(5, samePcSteps, "One scan microstep for each Unicode code point, not UTF-16 unit");
        assertEquals(11, done.work(), "Four projections, five scan steps, one finish and one result binding");
    }
    static DataValue.RecordValue sample(String text) {
        return new DataValue.RecordValue(Map.of("sample", new DataValue.RecordValue(Map.of("items",
            new DataValue.ListValue(List.of(new DataValue.RecordValue(Map.of("name", new DataValue.TextValue(text)))))))));
    }
    static FlowProgram lengthProgram() {
        var graph = new ComputationGraph(Map.of("source", SAMPLE), Map.of(
            "source", new ComputationGraph.Input("source"),
            "items", new ComputationGraph.Field("source", "items"),
            "first", new ComputationGraph.Index("items", 0),
            "name", new ComputationGraph.Field("first", "name"),
            "length", new ComputationGraph.Text(TextOperation.LENGTH, Map.of("text", "name"))), Map.of("value", "length"));
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of(), List.of(),
            Map.of("measure", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
        return new FlowProgram(Map.of("sample", SAMPLE), List.of(new FlowProgram.Evaluate("measure", "measure",
            new FlowCallBindings(Map.of("source", "sample"), Map.of("value", "length")))), archive).withAssembly();
    }
}
