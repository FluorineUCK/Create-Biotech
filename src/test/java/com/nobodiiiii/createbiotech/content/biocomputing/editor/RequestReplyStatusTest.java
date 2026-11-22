package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.RequestReplyFixtures.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RequestReplyStatusTest {
    @Test void generalDebuggerShowsNamedPendingAndStagedWithoutExposingCorrelationIds() {
        var runtime = new ComputerRuntime(UUID.randomUUID(), client(), HARDWARE);
        runtime.begin(input("query", 7));
        var staging = requests(runtime);
        assertEquals(Set.of("pending", "staged", "handling", "memory_units"), staging.fields().keySet());
        assertEquals(new DataValue.RecordValue(Map.of()), staging.field("pending"));
        assertEquals(new DataValue.RecordValue(Map.of("lookup", new DataValue.TextValue("result"))), staging.field("staged"));
        assertEquals(DataValue.NullValue.INSTANCE, staging.field("handling"));
        assertEquals(new DataValue.IntegerValue(runtime.snapshot().rpc().storageUnits()), staging.field("memory_units"));
        finish(runtime);
        assertEquals(staging.field("staged"), requests(runtime).field("pending"));
        assertEquals(staging.field("pending"), requests(runtime).field("staged"));
        runtime.reset(); assertEquals(new DataValue.IntegerValue(0), requests(runtime).field("memory_units"));
    }

    @Test void replyOutboxSummaryUsesFrozenReturnNameRatherThanMissingGraphEndpointOutput() {
        var client = new ComputerRuntime(UUID.randomUUID(), client(), HARDWARE);
        var server = new ComputerRuntime(UUID.randomUUID(), server(), HARDWARE);
        client.begin(input("query", 7)); finish(client);
        assertEquals(AdmissionStatus.ACCEPTED, server.receive(request(client.id(), client.outbound().orElseThrow(), "literal return")));
        server.step(); finish(server);
        var status = communication(server);
        assertEquals(new DataValue.TextValue("literal return"), status.field("next_endpoint"));
        assertEquals(new DataValue.BooleanValue(true), status.field("target_valid"));
        assertEquals(new DataValue.IntegerValue(0), status.field("pending_requests"));
        assertEquals(new DataValue.IntegerValue(server.snapshot().rpc().storageUnits()), status.field("rpc_units"));
        assertEquals(new DataValue.TextValue("request"), requests(server).field("handling"));
    }

    @Test void boundedCommunicationSummaryDoesNotCopyLongPendingNames() {
        var shortNames = new ComputerRuntime(UUID.randomUUID(), client(), HARDWARE);
        var original = client(); var definition = original.program();
        var extended = new ProgramArchive(new ProgramDefinition(definition.graph(), definition.cells(), definition.writes(),
            List.of(ProgramDefinition.Send.request("target", "payload", "long_" + "x".repeat(2000), "result")), definition.entries()), original.modules());
        var longNames = new ComputerRuntime(UUID.randomUUID(), extended, HARDWARE);
        shortNames.begin(input("query", 1)); longNames.begin(input("query", 1)); finish(shortNames); finish(longNames);
        assertEquals(new DataValue.IntegerValue(1), communication(longNames).field("pending_requests"));
        assertEquals(communication(shortNames).storageUnits(), communication(longNames).storageUnits());
    }

    private static DataValue.RecordValue requests(ComputerRuntime runtime) { return (DataValue.RecordValue) ComputerStatusView.of(runtime.snapshot()).field("requests"); }
    private static DataValue.RecordValue communication(ComputerRuntime runtime) {
        return (DataValue.RecordValue) CommunicationStatusView.of(Optional.of(runtime.snapshot()), EventEntryFixtures.LIMITS,
            true, Optional.empty(), Optional.empty()).field("communication");
    }
}
