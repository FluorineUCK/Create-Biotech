package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.LibrarySegmentedPumpLifecycleTest.*;

/** Resource lifetime only: real published ISA/XBus; explicit peers replace serial/mechanical latency. */
class LibrarySegmentedReadLifecycleTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void downloadLargerThanTheFirstLedgerDoesNotRetainEveryRangeReceipt(boolean wrongOffset) throws Exception {
        var limits = StockApplicationDriverDocuments.hardware();
        var contextType = new DataType.RecordType(Map.of("r", NativeLibraryReadDocuments.ROW, "s", DataType.Scalar.ANY, "d", DataType.Scalar.ANY));
        var document = LibrarySegmentedWriteDocuments.build("step_read_stream");
        System.out.println("STEP_READ_COST " + document.flow().orElseThrow().program(document.archive()).usage(new ResourceLimits(4096,65536,8)));
        int chunk = Math.toIntExact(((DataValue.IntegerValue)document.flow().orElseThrow().initial().field("c")).value());
        int total = 70 * chunk;
        var context = record("r", record("o", "download", "d", DataValue.NullValue.INSTANCE, "e", "download", "l", false), "s", 1,
            "d", record("bytes", total, "format", "typed_record_v1", "kind", "data", "version", 1));
        var caller = install(peer(Map.of("context", contextType), record("context", context), false, """
            TYPE Result RECORD ok BOOLEAN value ANY
            REG result Result
            REG value ANY
            REG done BOOLEAN
            REG count INTEGER
            REG one INTEGER
            CONST count 0
            CONST one 1
            XBUS.WRITE x0 context
            next:
            XBUS.READ result x0
            FIELD done result ok
            JFALSE done finished
            FIELD value result value
            IS_NULL done value
            JFALSE done received
            finished:
            RET
            received:
            ADD_INTEGER count count one
            JMP next
            """), limits);
        var bytes = new DataValue.NbtValue(7, new DataValue.ListValue(Collections.nCopies(chunk, new DataValue.IntegerValue(42))));
        var adapter = install(peer(Map.of("bytes", DataType.Scalar.NBT, "total", DataType.Scalar.INTEGER), record("bytes", bytes, "total", total), true, """
            TYPE Row RECORD o TEXT d ANY e TEXT l BOOLEAN
            TYPE Context RECORD r Row s ANY d ANY
            TYPE Result RECORD ok BOOLEAN value ANY
            TYPE Chunk RECORD offset ANY total INTEGER eof BOOLEAN bytes NBT
            TYPE Reply RECORD sequence ANY code TEXT value Chunk
            REG context Context
            REG range ANY
            REG offset ANY
            REG value Chunk
            REG reply Reply
            REG result Result
            REG flag BOOLEAN
            REG ok BOOLEAN
            REG seq ANY
            REG code TEXT
            CONST flag false
            CONST ok true
            CONST code "read_chunk"
            XBUS.READ context x0
            FIELD range context d
            FIELD offset range offset
            OFFSET_FIXTURE
            FIELD seq context s
            RECORD.MAKE value offset offset total total eof flag bytes bytes
            RECORD.MAKE reply sequence seq code code value value
            RECORD.MAKE result ok ok value reply
            XBUS.WRITE x0 result
            """.replace("OFFSET_FIXTURE", wrongOffset ? "CONST offset 1" : "")), limits);
        var stream = install(document, limits);
        var verifierDocument = LibrarySegmentedWriteDocuments.build("step_read_verify");
        System.out.println("STEP_VERIFY_COST " + verifierDocument.flow().orElseThrow().program(verifierDocument.archive()).usage(new ResourceLimits(4096,65536,8)));
        var verifier = install(verifierDocument, limits);
        var relayDocument = LibrarySegmentedWriteDocuments.build("range_reply");
        System.out.println("RANGE_REPLY_COST " + relayDocument.flow().orElseThrow().program(relayDocument.archive()).usage(new ResourceLimits(4096,65536,8)));
        var relay = install(relayDocument, limits);
        long peak = 0; int resourceWait = 0, deliveries = 0; boolean restored = false;
        for (int tick = 0; tick < 5000; tick++) {
            for (var host : List.of(caller, verifier, stream, relay, adapter)) {
                host.tick(tick, limits, true, Map.of());
                assertTrue(host.loaded(), host.diagnostic().toString());
                var saved = host.flowSnapshot().orElseThrow();
                assertNotEquals(FlowRuntime.Status.FAILED, saved.state().status(), saved.state().diagnostic().toString());
            }
            var a = stream.exchange("x0", limits, true, verifier, "x1", limits, true);
            boolean receiving = caller.flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.WAITING_READ;
            var c = verifier.exchange("x0", limits, true, caller, "x0", limits, true);
            if (receiving && c == XBusExchange.Status.EXCHANGED) deliveries++;
            var b = stream.exchange("x1", limits, true, relay, "x0", limits, true);
            relay.exchange("x1", limits, true, adapter, "x0", limits, true);
            if (!wrongOffset && deliveries == 10 && !restored) {
                caller = NativeLibraryOwnersTest.reload(caller, limits, null);
                verifier = NativeLibraryOwnersTest.reload(verifier, limits, null);
                stream = NativeLibraryOwnersTest.reload(stream, limits, null);
                relay = NativeLibraryOwnersTest.reload(relay, limits, null);
                adapter = NativeLibraryOwnersTest.reload(adapter, limits, null);
                restored = true;
            }
            peak = Math.max(peak, stream.flowSnapshot().orElseThrow().receipts().stream().mapToLong(r -> r.value().storageUnits()).sum());
            resourceWait = a == XBusExchange.Status.MEMORY_LIMIT || b == XBusExchange.Status.MEMORY_LIMIT || c == XBusExchange.Status.MEMORY_LIMIT ? resourceWait + 1 : 0;
            assertTrue(resourceWait < 100, "Download resource wait; stream receipt units=" + peak + "; verifier receipt units="
                + verifier.flowSnapshot().orElseThrow().receipts().stream().mapToLong(r -> r.value().storageUnits()).sum()
                + "; stream/verifier=" + a + "; adapter=" + b + "; verifier/caller=" + c);
            if (caller.flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.COMPLETE) {
                assertEquals(wrongOffset ? record("ok", false, "value", "invalid_read_range") : record("ok", true, "value", DataValue.NullValue.INSTANCE), caller.flowSnapshot().orElseThrow().state().locals().field("result"));
                assertEquals(wrongOffset ? 1 : 71, deliveries, "Exactly the accepted ranges and final outcome; no unvalidated data or replay");
                if (!wrongOffset) assertTrue(restored);
                System.out.println("READ_LIFETIME total=" + total + " ticks=" + tick + " peakReceiptUnits=" + peak);
                return;
            }
        }
        fail("Download did not finish; peak receipt units=" + peak);
    }
}
