package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveData.obj;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.RpcState;
import java.util.*;
import org.junit.jupiter.api.Test;

class RequestReplyComputerArchiveTest {
    @Test void schemaSixCarriesRpcAndLiteralHistoricalFiveDecodesEmptyRpc() {
        var runtime = new ComputerRuntime(UUID.randomUUID(), RequestReplyFixtures.client(), RequestReplyFixtures.HARDWARE);
        runtime.begin(RequestReplyFixtures.input("query", 3));
        var encoded = (DataValue.RecordValue)ValueNbtCodec.decode(ComputerNbtCodec.encode(runtime.snapshot(), EventEntryFixtures.ARCHIVE), EventEntryFixtures.ARCHIVE);
        assertEquals(new DataValue.IntegerValue(10), encoded.field("version"));
        assertEquals(runtime.snapshot().rpc().value(), encoded.field("rpc"));
        var emptyList = new DataValue.ListValue(List.of()); var nil = DataValue.NullValue.INSTANCE;
        // Independent historical wire literal: no current computer/program/mailbox encoder participates.
        var historical = obj("version", 5, "id", "00000000-0000-0000-0000-000000000123",
            "program", obj("version", 3, "graph", obj("inputs", obj(), "nodes", obj("one", obj("kind", "constant", "value", 1)), "outputs", obj("out", "one")),
                "cells", obj(), "writes", emptyList, "sends", emptyList, "entries", obj(), "modules", emptyList),
            "machine", obj("memory", obj(), "status", "IDLE", "paused", false, "work", 0, "arena", 0, "output", nil, "diagnostic", nil, "frames", emptyList),
            "pending", nil, "installationDiagnostic", nil, "periodic", nil, "delivery", nil,
            "mailbox", obj("version", 1, "queued", emptyList, "receipts", emptyList), "nextSequence", 0, "activeEntry", nil);
        var decoded = ComputerNbtCodec.decode(ValueNbtCodec.encode(historical, EventEntryFixtures.ARCHIVE), EventEntryFixtures.ARCHIVE);
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000123"), decoded.id());
        assertEquals(RpcState.empty(), decoded.rpc()); assertTrue(decoded.activeEntry().isEmpty());
        var restored = ComputerRuntime.restore(decoded, RequestReplyFixtures.HARDWARE); restored.load(); restored.begin(new DataValue.RecordValue(Map.of())); RequestReplyFixtures.finish(restored);
        assertEquals(new DataValue.IntegerValue(1), restored.report().output().orElseThrow().field("out"));
    }
}
