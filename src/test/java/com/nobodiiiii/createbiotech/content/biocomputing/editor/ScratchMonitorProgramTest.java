package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import java.util.*;
import org.junit.jupiter.api.Test;

class ScratchMonitorProgramTest {
    @Test void aPrivateMonitorNeverFollowsAnotherTaskWithTheSameVariableName() {
        var integer = DataType.Scalar.INTEGER;
        var key = new ModuleDefinition.Key("worker", 1);
        var inputs = Map.<String, DataType>of("reading", integer, "delay", integer);
        var program = new FlowProgram(inputs, List.of(
            privateMonitor("main_show", "reading", true),
            new FlowProgram.Sleep("main_wait", "delay"),
            new FlowProgram.Procedure("definition", key, inputs, Map.of(), Map.of()),
            privateMonitor("child_show", "reading", true),
            new FlowProgram.Sleep("child_wait", "delay"),
            privateMonitor("child_hide", "reading", false),
            new FlowProgram.EndProcedure("end")
        )).withAssembly();
        var limits = new ComputerHostLimits(new ResourceLimits(512, 65_536, 16), SubgraphWorkspaceTest.LIMITS,
            com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits.DEFAULT,
            com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins.DEFAULT,
            com.nobodiiiii.createbiotech.content.biocomputing.hardware.SerialHardware.NONE, 4);
        var main = new DataValue.RecordValue(Map.of("reading", new DataValue.IntegerValue(5), "delay", new DataValue.IntegerValue(80)));
        var child = new DataValue.RecordValue(Map.of("reading", new DataValue.IntegerValue(8), "delay", new DataValue.IntegerValue(80)));
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(program, main, limits, true);
        host.step(limits, true);
        var id = host.startFlowTask(key, child, limits, true);
        assertEquals(Set.of("reading"), projectedMain(host));
        assertEquals(Set.of(), projectedTask(host, id), "The main watch must not select a child's same-named local");
        host.setPaused(false, limits, true);
        for (int tick = 0; tick < 4; tick++) host.tick(tick, limits, true, Map.of());
        assertEquals(Set.of("reading"), projectedTask(host, id));
        var restored = new ComputerBlockHost(() -> {});
        restored.read(host.write());
        restored.tick(4, limits, true, Map.of());
        assertEquals(Set.of("reading"), projectedMain(restored));
        assertEquals(Set.of("reading"), projectedTask(restored, id));
        for (int tick = 5; tick < 100; tick++) restored.tick(tick, limits, true, Map.of());
        assertTrue(restored.flowSnapshot().orElseThrow().monitors().isEmpty());
        assertEquals(Set.of("reading"), projectedMain(restored));
        assertTrue(projectedTask(restored, id).isEmpty());
    }

    @Test void privateMonitorIsReleasedOnReturnAndDoesNotReappearOnTheNextCall() {
        var key = new ModuleDefinition.Key("helper", 1);
        var types = Map.<String, DataType>of("reading", DataType.Scalar.INTEGER);
        var mainArgs = new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowCallBindings(
            Map.of("reading", "reading"), Map.of());
        var program = new FlowProgram(types, List.of(
            new FlowProgram.Invoke("call_one", key, mainArgs),
            new FlowProgram.Invoke("call_two", key, mainArgs),
            new FlowProgram.Procedure("definition", key, types, Map.of(), Map.of()),
            privateMonitor("show_in_helper", "reading", true),
            new FlowProgram.EndProcedure("end")
        )).withAssembly();
        var limits = new ComputerHostLimits(new ResourceLimits(512, 65_536, 16), SubgraphWorkspaceTest.LIMITS);
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(program, new DataValue.RecordValue(Map.of("reading", new DataValue.IntegerValue(5))), limits, true);
        boolean wasCalling = false;
        int entries = 0, returns = 0;
        var selected = new HashSet<Integer>();
        for (int i = 0; i < 80; i++) {
            host.step(limits, true);
            var saved = host.flowSnapshot().orElseThrow();
            boolean calling = saved.state().assembly().orElseThrow().calls().orElseThrow().frames().size() == 1;
            if (calling && !wasCalling) {
                entries++;
                assertTrue(projectedMain(host).isEmpty(), "Entering a fresh call must not inherit a watch");
            }
            if (!calling && wasCalling) {
                returns++;
                assertTrue(projectedMain(host).isEmpty(), "Returning must not expose a same-named caller local");
                assertTrue(saved.privateMonitors().watches().isEmpty(), "Returned frame must not hold resources");
            }
            if (calling && projectedMain(host).contains("reading")) selected.add(entries);
            wasCalling = calling;
            if (saved.state().status().name().equals("COMPLETE")) break;
        }
        assertEquals(2, entries);
        assertEquals(2, returns);
        assertEquals(Set.of(1, 2), selected);
    }

    private static FlowProgram.Step privateMonitor(String id, String local, boolean show) {
        var blank = (DataValue.RecordValue) com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowProgramCodec.encode(
            new FlowProgram(Map.of(), List.of()));
        var fields = new LinkedHashMap<>(blank.fields());
        fields.put("steps", new DataValue.ListValue(List.of(new DataValue.RecordValue(Map.of(
            "kind", new DataValue.TextValue("monitor_private"), "source", new DataValue.TextValue(id),
            "cell", new DataValue.TextValue(local), "visible", new DataValue.BooleanValue(show))))));
        return com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowProgramCodec.decode(
            new DataValue.RecordValue(fields)).steps().getFirst();
    }

    private static Set<String> projectedMain(ComputerBlockHost host) {
        return watchNames(ComputerStatusView.flow(host.flowSnapshot(), true, Optional.empty()));
    }
    private static Set<String> projectedTask(ComputerBlockHost host, UUID id) {
        return watchNames((DataValue.RecordValue)TaskDebugView.task(host.flowSnapshot().orElseThrow(), id).orElseThrow().field("debug_task"));
    }
    private static Set<String> watchNames(DataValue.RecordValue view) {
        if (!(view.fields().get("private_monitors") instanceof DataValue.ListValue list)) return Set.of();
        var names = new HashSet<String>();
        list.values().forEach(value -> names.add(((DataValue.TextValue)value).value()));
        return names;
    }

}
