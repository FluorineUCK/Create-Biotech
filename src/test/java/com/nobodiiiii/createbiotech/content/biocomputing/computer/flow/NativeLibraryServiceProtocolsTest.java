package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.NativeLibraryFiniteFailureTest.reply;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryServiceDocuments;
import com.nobodiiiii.createbiotech.content.biocomputing.network.XBusHistorySavedData;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult;
import java.util.*;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the candidate task drivers, not the earlier synchronous public procedures. */
class NativeLibraryServiceProtocolsTest {
    static final class Rig extends NativeLibraryFiniteFailureTest.Rig {
        final XBusHistorySavedData history = new XBusHistorySavedData();
        Rig() {
            super(false);
            b = NativeLibraryOwnersTest.install(NativeLibraryServiceDocuments.build("b"), lb, wireB);
            c = NativeLibraryOwnersTest.install(NativeLibraryServiceDocuments.build("c"), lc, null);
            b.setPaused(false, lb, true); c.setPaused(false, lc, true);
        }
        @Override void cpu() {
            tick++;
            a.tick(tick, la, true, samples(a)); b.tick(tick, lb, true, samples(b)); c.tick(tick, lc, true, samples(c));
            NativeLibraryServiceSerialTest.exchange(a, "x0", la, b, "x0", lb, history);
            NativeLibraryServiceSerialTest.exchange(b, "x1", lb, c, "x0", lc, history);
            for (var host : List.of(a, b, c)) {
                assertTrue(host.loaded(), host.diagnostic().toString());
                var saved = host.flowSnapshot().orElseThrow();
                assertNotEquals(FlowRuntime.Status.FAILED, saved.state().status(), saved.state().diagnostic().toString());
                for (var child : saved.tasks().contexts()) assertNotEquals(FlowRuntime.Status.FAILED, child.status(), child.diagnostic().toString());
            }
        }
        Optional<FlowRuntime.Checkpoint> activeB() { return b.flowSnapshot().orElseThrow().tasks().contexts().stream().findFirst(); }
        long retiredMatches() {
            var tasks = c.flowSnapshot().orElseThrow().tasks();
            if (tasks.slots().entries().isEmpty()) return 0;
            var slot = tasks.slots().entries().getFirst(); return slot.incarnation() + (slot.retired() ? 1 : 0);
        }
        void finishAndClaimService() {
            until(() -> complete() && b.flowSnapshot().orElseThrow().tasks().contexts().isEmpty(), 3000);
            var saved = b.flowSnapshot().orElseThrow();
            assertTrue(saved.receipts().isEmpty());
            var result = (DataValue.RecordValue)saved.state().locals().field("service_result");
            assertEquals(new DataValue.BooleanValue(true), result.field("ok"), "A business rejection is a completed service, not a failed task");
            assertEquals(record(), result.field("value"), "No redundant payload result is retained after XBus reply");
        }
    }

    @Test void closedSendReturnsTheOriginalBusinessFailureWithoutLaunchingTheMatcher() {
        var r = new Rig(); r.wireB.configure("s0", Direction.EAST, false);
        r.finishAndClaimService();
        assertEquals(record("ok", false, "value", SerialResult.send("closed")), r.outcome());
        assertEquals(0, r.retiredMatches()); assertEquals(0, r.outgoing);
    }

    @Test void allMissingFieldsAndStaleIdentitiesAreStillRejectedBeforeARealMatchingReply() {
        var r = new Rig(); r.awaitCommand();
        var noise = new ArrayList<DataValue>(); noise.add(record("ordinary", "record"));
        for (String field : List.of("kind", "target", "request", "code", "sequence", "value")) {
            var fields = new LinkedHashMap<>(reply().fields()); fields.remove(field); noise.add(new DataValue.RecordValue(fields));
        }
        for (String field : List.of("kind", "target", "request")) {
            var fields = new LinkedHashMap<>(reply().fields());
            fields.put(field, field.equals("target") ? record("x", 99, "y", 0, "z", 0) : new DataValue.TextValue("stale"));
            noise.add(new DataValue.RecordValue(fields));
        }
        for (int i = 0; i < noise.size(); i++) {
            var frame = noise.get(i); long expected = i + 1;
            r.send(frame); r.until(() -> r.retiredMatches() == expected, 2500);
            assertFalse(r.complete()); assertEquals(1, r.outgoing);
            var child = r.activeB().orElseThrow();
            assertEquals(new DataValue.BooleanValue(false), ((DataValue.RecordValue)child.locals().field("match")).field("m"));
            assertEquals(frame, ((DataValue.RecordValue)child.locals().field("received")).field("value"));
        }
        r.send(reply()); r.finishAndClaimService();
        assertEquals(record("ok", true, "value", reply()), r.outcome());
        assertEquals(11, r.retiredMatches()); assertEquals(1, r.outgoing);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void aMatchedReplyWinsDisconnectionButANonmatchReturnsTheLineFailureAfterItsSleep(boolean matching) {
        var r = new Rig(); r.awaitCommand(); var frame = matching ? reply() : record("ordinary", "record");
        r.send(frame); r.until(() -> r.retiredMatches() == 1, 2500);
        r.connected = false; r.networkTick();
        r.until(() -> r.activeB().map(s -> s.status() == FlowRuntime.Status.SLEEPING && (matching
            || s.locals().fields().get("serial") instanceof DataValue.RecordValue line
                && !line.field("connection").equals(new DataValue.TextValue("ready")))).orElse(false), 1000);
        var sleeping = r.activeB().orElseThrow(); int start = r.tick; long remaining = sleeping.sleepTicks();
        assertTrue(remaining > 0); assertFalse(r.complete());
        DataValue expected = matching ? frame : sleeping.locals().field("serial");
        r.finishAndClaimService();
        assertTrue(r.tick - start >= remaining); assertEquals(record("ok", matching, "value", expected), r.outcome());
        assertEquals(1, r.outgoing);
    }

    @Test void nativeFrameDecodeFailureKeepsTheFrameAndTheSpecificErrorWithoutRetry() {
        var r = new Rig(); r.awaitCommand();
        r.send(new DataValue.NbtValue(10, record("foreign", new DataValue.NbtValue(8, new DataValue.TextValue("native")))));
        r.until(() -> r.activeB().map(s -> s.status() == FlowRuntime.Status.SLEEPING
            && s.locals().fields().get("received") instanceof DataValue.RecordValue received
            && !received.field("status").equals(new DataValue.TextValue("empty"))).orElse(false), 2500);
        var child = r.activeB().orElseThrow(); var error = child.locals().field("received");
        int start = r.tick; long remaining = child.sleepTicks();
        r.finishAndClaimService();
        assertEquals(record("ok", false, "value", error), r.outcome());
        assertTrue(r.tick - start >= remaining); assertEquals(1, r.wireB.status("s0").receive().frames());
        for (int tick = 0; tick < 50; tick++) r.step();
        assertEquals(1, r.outgoing); assertEquals(1, r.wireB.status("s0").receive().frames());
        assertEquals(0, r.retiredMatches());
    }
}
