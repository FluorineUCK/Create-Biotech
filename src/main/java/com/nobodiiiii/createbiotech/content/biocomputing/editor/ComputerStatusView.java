package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.PeriodicSchedule;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.RpcState;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterCalls;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import java.util.*;

/** Deliberately omits private frame values, resident program, ids, and all world references. */
public final class ComputerStatusView {
    private ComputerStatusView() {}
    public static DataValue.RecordValue flow(Optional<com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowBlockHost.Saved> saved,
        boolean loaded, Optional<com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost.Diagnostic> failure) {
        return flow(saved, loaded, failure, false);
    }
    public static DataValue.RecordValue flow(Optional<com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowBlockHost.Saved> saved,
        boolean loaded, Optional<com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost.Diagnostic> failure, boolean resourceResetAvailable) {
        var fields = new LinkedHashMap<String, DataValue>();
        fields.put("resource_reset_available", new DataValue.BooleanValue(!loaded && resourceResetAvailable));
        fields.put("mode", new DataValue.TextValue("flow")); fields.put("available", new DataValue.BooleanValue(loaded));
        fields.put("phase", new DataValue.TextValue("UNAVAILABLE"));
        saved.ifPresent(value -> {
            var state = value.state();
            if (loaded && value.program().usesMonitor())
                fields.put("program_monitors", com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowBlockHost.monitorValue(value.monitors()));
            if (loaded && value.program().usesPrivateMonitors())
                fields.put("private_monitors", com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowBlockHost.monitorValue(value.privateMonitors().visible(state)));
            fields.put("phase", new DataValue.TextValue(state.cancelled() ? "CANCELLED" : state.status().name()));
            fields.put("execution", new DataValue.TextValue(value.program().execution().name().toLowerCase(Locale.ROOT)));
            state.assembly().ifPresent(registers -> {
                fields.put("pc", new DataValue.IntegerValue(registers.pc()));
                fields.put("cycles", new DataValue.IntegerValue(state.work()));
                registers.calls().ifPresent(calls -> fields.put("call_stack", callStack(value.program().steps(), calls.frames(), state.cursor())));
            });
            fields.put("paused", new DataValue.BooleanValue(state.paused()));
            fields.put("cursor", new DataValue.IntegerValue(state.cursor())); fields.put("work", new DataValue.IntegerValue(state.work()));
            if (state.status() == com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime.Status.WAITING_TASK)
                fields.put("wait_reason", new DataValue.TextValue(value.tasks().slots().entries().stream()
                    .anyMatch(slot -> slot.waiter().filter(state.flow()::equals).isPresent()) ? "waiting_broadcast" : "waiting_task"));
            if (state.status() == com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime.Status.WAITING_ANSWER)
                fields.put("wait_reason", new DataValue.TextValue("waiting_answer"));
            if (state.sleepTicks() > 0) fields.put("sleep_ticks", new DataValue.IntegerValue(state.sleepTicks()));
            fields.put("source", state.cursor() < value.program().steps().size()
                ? new DataValue.TextValue(value.program().steps().get(state.cursor()).sourceId()) : DataValue.NullValue.INSTANCE);
            // UI projection is not checkpoint mutation and never participates in execution validation.
            if(loaded || value.program().compilation()!=com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Compilation.SOFTWARE_V1)
                fields.put("locals", loaded && value.program().compilation()==com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Compilation.SOFTWARE_V1 && state.assembly().isPresent()
                ? value.program().displayLocals(state.assembly().orElseThrow()) : LocalReferences.describeRoots(state.locals()));
            if (value.program().usesMemoryAssembly()) {
                fields.put("memory", value.memory().cells());
                fields.put("memory_units", new DataValue.IntegerValue(value.memory().storageUnits()));
                fields.put("transactions", new DataValue.IntegerValue(value.memory().owners().size()));
            }
            if (com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.hasSignals(value.program().steps()))
                fields.put("signal_outputs", state.signals().value());
            fields.put("diagnostic", state.diagnostic().<DataValue>map(ComputerStatusView::diagnostic).orElse(DataValue.NullValue.INSTANCE));
        });
        failure.ifPresent(error -> fields.put("diagnostic", new DataValue.RecordValue(Map.of(
            "code", new DataValue.TextValue(error.code().name()), "message", new DataValue.TextValue(error.message())))));
        return new DataValue.RecordValue(fields);
    }
    /** Images retain the checked source ordinal for every instruction. The next frame's
     * caller (or the active cursor for the innermost frame) is inside this frame's actual
     * callee, even when several reporter calls originate in one source block. */
    static DataValue.ListValue callStack(List<FlowProgram.Step> steps, List<RegisterCalls.Frame> frames, int active) {
        var visible = new ArrayList<DataValue>(frames.size());
        for (int i = 0; i < frames.size(); i++) {
            int caller = frames.get(i).source();
            int callee = i + 1 < frames.size() ? frames.get(i + 1).source() : active;
            visible.add(callSite(steps, caller, callee));
        }
        return new DataValue.ListValue(visible);
    }
    private static DataValue callSite(List<FlowProgram.Step> steps, int caller, int callee) {
        if (caller < 0 || caller >= steps.size() || callee < 0 || callee >= steps.size())
            return DataValue.NullValue.INSTANCE;
        for (int at = callee; at >= 0; at--) {
            var step = steps.get(at);
            if (step instanceof FlowProgram.Procedure definition) {
                var key = definition.key();
                return new DataValue.RecordValue(Map.of("source", new DataValue.TextValue(steps.get(caller).sourceId()),
                    "procedure", new DataValue.TextValue(key.id()), "revision", new DataValue.IntegerValue(key.revision())));
            }
            if (at != callee && step instanceof FlowProgram.EndProcedure) break;
        }
        return DataValue.NullValue.INSTANCE;
    }
    public static DataValue.RecordValue of(ComputerRuntime.Snapshot snapshot) {
        var machine = snapshot.machine(); var fields = new LinkedHashMap<String, DataValue>();
        fields.put("memory", machine.memory()); fields.put("phase", new DataValue.TextValue(machine.status().name()));
        fields.put("entry", snapshot.activeEntry().<DataValue>map(DataValue.TextValue::new).orElse(DataValue.NullValue.INSTANCE));
        fields.put("paused", new DataValue.BooleanValue(machine.paused())); fields.put("work", new DataValue.IntegerValue(machine.workUnits()));
        fields.put("arena", new DataValue.IntegerValue(machine.arenaUnits()));
        fields.put("output", machine.output().<DataValue>map(value -> value).orElse(DataValue.NullValue.INSTANCE));
        fields.put("diagnostic", machine.diagnostic().<DataValue>map(ComputerStatusView::diagnostic).orElse(DataValue.NullValue.INSTANCE));
        fields.put("installation_diagnostic", snapshot.installationDiagnostic().<DataValue>map(ComputerStatusView::diagnostic).orElse(DataValue.NullValue.INSTANCE));
        fields.put("pending", new DataValue.BooleanValue(snapshot.pending().isPresent()));
        fields.put("periodic", snapshot.periodic().<DataValue>map(value -> schedule(value, false)).orElse(DataValue.NullValue.INSTANCE));
        var rpc = snapshot.rpc();
        fields.put("requests", new DataValue.RecordValue(Map.of("pending", pendingNames(rpc.pending()),
            "staged", pendingNames(rpc.staged()),
            "handling", rpc.request().isPresent() ? new DataValue.TextValue("request")
                : rpc.reply().isPresent() ? new DataValue.TextValue("reply") : DataValue.NullValue.INSTANCE,
            "memory_units", new DataValue.IntegerValue(rpc.storageUnits()))));
        return new DataValue.RecordValue(fields);
    }
    private static DataValue.RecordValue pendingNames(Map<String, RpcState.Pending> pending) {
        var fields = new LinkedHashMap<String, DataValue>();
        pending.forEach((slot, value) -> fields.put(slot, new DataValue.TextValue(value.replyEntry())));
        return new DataValue.RecordValue(fields);
    }
    public static DataValue.RecordValue periodic(ComputerRuntime.Snapshot snapshot) { return periodic(snapshot.periodic()); }
    public static DataValue.RecordValue periodic(Optional<PeriodicSchedule> schedule) {
        return new DataValue.RecordValue(Map.of("periodic", schedule.<DataValue>map(value -> schedule(value, true)).orElse(DataValue.NullValue.INSTANCE)));
    }
    private static DataValue.RecordValue schedule(PeriodicSchedule schedule, boolean includeInputs) {
        var fields = new LinkedHashMap<String, DataValue>();
        fields.put("interval_ticks", new DataValue.IntegerValue(schedule.intervalTicks()));
        fields.put("remaining_ticks", new DataValue.IntegerValue(schedule.remainingTicks()));
        fields.put("enabled", new DataValue.BooleanValue(schedule.enabled()));
        fields.put("input_units", new DataValue.IntegerValue(schedule.inputs().storageUnits()));
        if (includeInputs) fields.put("inputs", schedule.inputs());
        return new DataValue.RecordValue(fields);
    }
    private static DataValue.RecordValue diagnostic(ComputationMachine.Diagnostic diagnostic) {
        return new DataValue.RecordValue(Map.of("code", new DataValue.TextValue(diagnostic.code().name()),
            "location", new DataValue.TextValue(diagnostic.location()), "message", new DataValue.TextValue(diagnostic.message())));
    }
}
