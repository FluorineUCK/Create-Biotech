package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowProgramCodec;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowVariableMonitorTest {
    @Test void publicMonitorCostsOneCycleSurvivesReloadAndDoesNotCopyOrMutateTheCell() {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.emptySoftwareAssembly()));
        workspace.root().putCell("items", new DataType.ListType(DataType.Scalar.INTEGER), new DataValue.ListValue(List.of()));
        var document = workspace.snapshot();
        var draft = new FlowDraft(document.flow().orElseThrow());
        draft.append(monitor("show", "items", true, false));
        draft.append(monitor("hide", "items", false, false));
        var source = new ProgramDocument(document.archive(), document.positions(), document.terminal(), Optional.of(draft.snapshot()));
        assertEquals(source, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(source)));
        var limits = new ComputerHostLimits(new ResourceLimits(512, 65_536, 16), SubgraphWorkspaceTest.LIMITS,
            com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits.DEFAULT,
            com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins.DEFAULT,
            com.nobodiiiii.createbiotech.content.biocomputing.hardware.SerialHardware.NONE, 1, true);
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(draft.snapshot().program(source.archive()), draft.snapshot().initial(), limits, true);
        assertEquals(Set.of(), watches(host, "program_monitors"));
        host.step(limits, true);
        assertEquals(1L, host.flowSnapshot().orElseThrow().state().work());
        assertEquals(Set.of("items"), watches(host, "program_monitors"));
        var saved = host.write();
        var restored = new ComputerBlockHost(() -> {}); restored.read(saved); restored.tick(0, limits, true, Map.of());
        assertEquals(Set.of("items"), watches(restored, "program_monitors"));
        restored.step(limits, true);
        assertEquals(2L, restored.flowSnapshot().orElseThrow().state().work());
        assertEquals(Set.of(), watches(restored, "program_monitors"));
        assertEquals(new DataValue.ListValue(List.of()), restored.flowSnapshot().orElseThrow().memory().cells().field("items"));
        restored.read(saved); restored.reset(limits, true);
        assertEquals(Set.of(), watches(restored, "program_monitors"));
    }

    @Test void monitorMetadataConsumesRealComputerRamAndFailedPublicationKeepsItHidden() {
        String name = "long_name_".repeat(80);
        var program = new FlowProgram(Map.of(name, DataType.Scalar.INTEGER), List.of(
            monitor("show", name, true, true), monitor("hide", name, false, true))).withAssembly();
        var inputs = new DataValue.RecordValue(Map.of(name, new DataValue.IntegerValue(3)));
        var generous = new ComputerHostLimits(new ResourceLimits(512, 65_536, 16), SubgraphWorkspaceTest.LIMITS);
        long low = 0, high = generous.hardware().memoryUnits();
        while (low < high) {
            long middle = (low + high) / 2;
            var probe = new ComputerBlockHost(() -> {});
            try { probe.installFlow(program, inputs, generous.withMemoryUnits(middle), true); high = middle; }
            catch (ComputerBlockHost.HostException failure) { low = middle + 1; }
            catch (ComputationException failure) { assertEquals(ComputationException.Code.MEMORY_LIMIT, failure.code()); low = middle + 1; }
        }
        var limited = generous.withMemoryUnits(low + 128);
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(program, inputs, limited, true);
        host.step(limited, true);
        var state = host.flowSnapshot().orElseThrow();
        assertTrue(state.privateMonitors().watches().isEmpty(), "Rejected metadata must never become visible");
        assertEquals(ComputationException.Code.MEMORY_LIMIT, state.state().diagnostic().orElseThrow().code());
        assertEquals(1, state.state().work(), "The rejected attempt still pays one instruction cycle");
        assertEquals(0, state.state().assembly().orElseThrow().pc(), "Failed publication must not advance past the monitor");
    }

    @Test void monitorCannotRunInsideAnAtomicUpdateOrWithAnUnknownLocal() {
        var limits = new ResourceLimits(512, 65_536, 16);
        var atomic = new FlowProgram(Map.of("value", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.Atomic("begin", "result"), monitor("show", "value", true, true),
            new FlowProgram.EndAtomic("end"))).withAssembly();
        var error = assertThrows(ComputationException.class, () -> atomic.validate(limits));
        assertEquals(ComputationException.Code.INVALID_GRAPH, error.code());
        var missing = new FlowProgram(Map.of(), List.of(monitor("show", "absent", true, true))).withAssembly();
        assertEquals(ComputationException.Code.MISSING_FIELD,
            assertThrows(ComputationException.class, () -> missing.validate(limits)).code());
    }

    @Test void monitorProjectionUsesTheSelectedCompilationLocalMapping() {
        var base = new FlowProgram(Map.of("reading", DataType.Scalar.INTEGER), List.of(
            monitor("show", "reading", true, true), monitor("hide", "reading", false, true)));
        for (var compilation : FlowProgram.Compilation.values()) {
            var program = new FlowProgram(base.inputs(), base.steps(), base.expressions(), FlowProgram.Execution.REGISTER_V1, compilation);
            var limits = new ComputerHostLimits(new ResourceLimits(512, 65_536, 16), SubgraphWorkspaceTest.LIMITS);
            var host = new ComputerBlockHost(() -> {});
            host.installFlow(program, new DataValue.RecordValue(Map.of("reading", new DataValue.IntegerValue(5))), limits, true);
            host.step(limits, true);
            var view = ComputerStatusView.flow(host.flowSnapshot(), true, Optional.empty());
            assertEquals(Set.of("reading"), watches(host, "private_monitors"), compilation.toString());
            assertEquals(new DataValue.IntegerValue(5), ((DataValue.RecordValue)view.field("locals")).field("reading"), compilation.toString());
        }
    }

    @Test void monitorOwnerCannotBeReinterpretedAsAnOlderCheckpoint() {
        var program = new FlowProgram(Map.of("value", DataType.Scalar.INTEGER), List.of(
            monitor("show", "value", true, true), monitor("hide", "value", false, true))).withAssembly();
        var limits = new ComputerHostLimits(new ResourceLimits(512, 65_536, 16), SubgraphWorkspaceTest.LIMITS);
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(program, new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(3))), limits, true);
        host.step(limits, true);
        var saved = host.flowSnapshot().orElseThrow();
        var encoded = com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowBlockNbtCodec.encode(saved, limits.archive());
        assertEquals(55, encoded.getInt(com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowBlockNbtCodec.MARKER));
        encoded.putInt(com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowBlockNbtCodec.MARKER, 48);
        assertThrows(com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException.class,
            () -> com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowBlockNbtCodec.decode(encoded, limits.archive()));
    }

    static FlowProgram.Step monitor(String id, String name, boolean show, boolean local) {
        var empty = (DataValue.RecordValue)FlowProgramCodec.encode(new FlowProgram(Map.of(), List.of()));
        var fields = new LinkedHashMap<>(empty.fields());
        fields.put("steps", new DataValue.ListValue(List.of(new DataValue.RecordValue(Map.of(
            "kind", new DataValue.TextValue(local ? "monitor_private" : "monitor"), "source", new DataValue.TextValue(id),
            "cell", new DataValue.TextValue(name), "visible", new DataValue.BooleanValue(show))))));
        return FlowProgramCodec.decode(new DataValue.RecordValue(fields)).steps().getFirst();
    }
    static Set<String> watches(ComputerBlockHost host, String field) {
        var state = ComputerStatusView.flow(host.flowSnapshot(), true, Optional.empty());
        var result = new HashSet<String>();
        if (state.fields().get(field) instanceof DataValue.ListValue values)
            values.values().forEach(v -> result.add(((DataValue.TextValue)v).value()));
        return result;
    }
}
