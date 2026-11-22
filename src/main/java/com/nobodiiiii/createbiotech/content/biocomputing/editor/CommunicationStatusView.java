package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import java.util.*;

/** Complete bounded metadata, not a partial copy of the queue, payloads or private runtime. */
public final class CommunicationStatusView {
    private CommunicationStatusView() {}

    public static DataValue.RecordValue flow(Optional<FlowBlockHost.Saved> saved,
            boolean loaded, Optional<ComputerBlockHost.Diagnostic> diagnostic) {
        var fields = new LinkedHashMap<String, DataValue>();
        fields.put("program_installed", new DataValue.BooleanValue(saved.isPresent()));
        fields.put("runtime_loaded", new DataValue.BooleanValue(loaded));
        fields.put("phase", saved.<DataValue>map(s -> text(switch (s.state().status()) {
            case READY -> "IDLE"; case COMPUTING -> "RUNNING"; case COMPLETE -> "SUCCEEDED";
            default -> s.state().status().name();
        })).orElse(nil()));
        fields.put("paused", saved.<DataValue>map(s -> new DataValue.BooleanValue(s.state().paused())).orElse(nil()));
        fields.put("host_diagnostic", diagnostic.<DataValue>map(d -> text(d.code().name())).orElse(nil()));
        return new DataValue.RecordValue(Map.of("communication", new DataValue.RecordValue(fields)));
    }

    public static DataValue.RecordValue of(Optional<ComputerRuntime.Snapshot> snapshot, ComputerHostLimits limits,
            boolean loaded, Optional<ComputerBlockHost.Diagnostic> diagnostic, Optional<EndpointTransport.Result> transport) {
        var fields = new LinkedHashMap<String, DataValue>();
        fields.put("program_installed", new DataValue.BooleanValue(snapshot.isPresent()));
        fields.put("runtime_loaded", new DataValue.BooleanValue(loaded));
        fields.put("phase", snapshot.<DataValue>map(s -> text(s.machine().status().name())).orElse(nil()));
        fields.put("paused", snapshot.<DataValue>map(s -> new DataValue.BooleanValue(s.machine().paused())).orElse(nil()));
        fields.put("inbox_count", number(snapshot.map(s -> s.mailbox().queued().size()).orElse(0)));
        fields.put("inbox_limit", number(limits.mailbox().messages()));
        fields.put("tracked_sources", number(snapshot.map(s -> s.mailbox().receipts().size()).orElse(0)));
        fields.put("source_limit", number(limits.mailbox().sources()));
        fields.put("mailbox_units", number(snapshot.map(s -> s.mailbox().storageUnits()).orElse(0L)));
        fields.put("pending_requests", number(snapshot.map(s -> s.rpc().pending().size()).orElse(0)));
        fields.put("rpc_units", number(snapshot.map(s -> s.rpc().storageUnits()).orElse(0L)));
        int remaining = snapshot.map(s -> s.delivery().map(d -> s.program().program().entry(s.activeEntry().orElse(null)).sends().size() - d.acknowledged()).orElse(0)).orElse(0);
        fields.put("outbox_remaining", number(remaining));
        DataValue target = nil(), valid = nil();
        if (snapshot.isPresent() && remaining > 0 && snapshot.orElseThrow().machine().status() == ComputationMachine.Status.SUCCEEDED) {
            var state = snapshot.orElseThrow();
            var send = state.program().program().entry(state.activeEntry().orElse(null)).sends().get(state.delivery().orElseThrow().acknowledged());
            var value = send.dispatch() == ProgramDefinition.Dispatch.REPLY
                ? text(state.rpc().request().orElseThrow().returnEndpoint())
                : (DataValue.TextValue)state.machine().output().orElseThrow().field(send.endpointOutput());
            try { new EndpointConfiguration(value.value(), true); target = value; valid = new DataValue.BooleanValue(true); }
            catch (IllegalArgumentException invalid) { valid = new DataValue.BooleanValue(false); }
        }
        fields.put("next_endpoint", target); fields.put("target_valid", valid);
        fields.put("last_transport", transport.<DataValue>map(result -> new DataValue.RecordValue(Map.of(
            "code", text(result.code()), "admission", result.admission().<DataValue>map(status -> text(status.name())).orElse(nil())))).orElse(nil()));
        fields.put("host_diagnostic", diagnostic.<DataValue>map(d -> text(d.code().name())).orElse(nil()));
        return new DataValue.RecordValue(Map.of("communication", new DataValue.RecordValue(fields)));
    }
    private static DataValue nil() { return DataValue.NullValue.INSTANCE; }
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static DataValue.IntegerValue number(long value) { return new DataValue.IntegerValue(value); }
}
