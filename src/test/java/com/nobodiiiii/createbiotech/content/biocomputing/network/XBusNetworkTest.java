package com.nobodiiiii.createbiotech.content.biocomputing.network;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusExchange;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class XBusNetworkTest {
    static XBusNetwork.Pin pin(int owner) { return new XBusNetwork.Pin(new UUID(0, owner), "x0"); }
    @Test void componentViewKeepsCanonicalOrderAndCannotMutateTopology() {
        var a = pin(1); var b = pin(2); var c = pin(3); var d = pin(4);
        var network = new XBusNetwork(List.of(a, b, c, d), List.of(new XBusNetwork.Link(c, a)));
        assertEquals(List.of(List.of(a, c), List.of(b), List.of(d)), network.components());
        assertThrows(UnsupportedOperationException.class, () -> network.components().clear());
        assertThrows(UnsupportedOperationException.class, () -> network.components().getFirst().add(b));
        assertTrue(network.round(1).peek(b, Set.of(a)).isEmpty());
    }
    @Test void explicitForkSharesOneConsumptionAndPeekingNeverConsumes() {
        var a = pin(1); var b = pin(2); var r = pin(3); var s = pin(4);
        var network = new XBusNetwork(List.of(a, b, r, s), List.of(new XBusNetwork.Link(a, r),
            new XBusNetwork.Link(r, b), new XBusNetwork.Link(b, s)));
        var writers = new HashSet<>(Set.of(a, b)); var round = network.round(0x100010001L);
        var expected = round.peek(r, writers).orElseThrow();
        assertEquals(a, expected.writer(), "First independent original round selects first writer");
        assertEquals(expected.writer(), round.peek(s, writers).orElseThrow().writer());
        assertFalse(round.occupied(a));
        assertEquals(XBusExchange.Status.EXCHANGED, round.attempt(r, writers, selected -> {
            assertEquals(expected, selected); writers.remove(selected.writer()); return XBusExchange.Status.EXCHANGED;
        }));
        assertEquals(Set.of(b), writers, "Unselected offer remains at its writer");
        assertTrue(round.occupied(b));
        assertTrue(round.peek(s, writers).isEmpty(), "Busy gates even a peek across a different edge");
        assertEquals(XBusExchange.Status.NOT_READY, round.attempt(s, writers, ignored -> fail("No second transfer in same round")));
        var next = network.round(0x100010002L);
        assertEquals(b, next.peek(s, writers).orElseThrow().writer());
        assertTrue(next.peek(b, writers).isEmpty(), "Cannot read from one's own physical pin");
    }

    @Test void duplicateEdgesAliasesAndInsertionOrderDoNotCreateExtraCapacityOrPriority() {
        var a = pin(1); var r = pin(2); var b = pin(3); var alias = pin(1);
        var pins = List.of(a, r, b);
        var wires = new ArrayList<>(List.of(new XBusNetwork.Link(a, r), new XBusNetwork.Link(r, b),
            new XBusNetwork.Link(alias, r), new XBusNetwork.Link(r, alias), new XBusNetwork.Link(a, a)));
        var original = new XBusNetwork(pins, wires); Collections.reverse(wires);
        var reversed = new XBusNetwork(pins, wires);
        for (int i = 0; i < 128; i++) {
            var one = original.round(i); var two = reversed.round(i);
            assertEquals(one.peek(r, Set.of(a, b)), two.peek(r, Set.of(b, alias)));
            one.attempt(r, Set.of(a, b), ignored -> XBusExchange.Status.EXCHANGED);
            assertTrue(one.occupied(alias)); assertTrue(one.occupied(b));
        }
    }

    @Test void separateNetworksAndUnwiredPinsStayIndependent() {
        var a = pin(1); var b = pin(2); var r = pin(3); var s = pin(4); var unconnected = pin(5);
        var round = new XBusNetwork(List.of(a, b, r, s, unconnected), List.of(new XBusNetwork.Link(a, r),
            new XBusNetwork.Link(b, s))).round(123);
        assertTrue(round.peek(unconnected, Set.of(a, b)).isEmpty());
        assertEquals(a, round.peek(r, Set.of(a, b)).orElseThrow().writer());
        round.attempt(r, Set.of(a, b), ignored -> XBusExchange.Status.EXCHANGED);
        assertFalse(round.occupied(b));
        assertEquals(XBusExchange.Status.EXCHANGED, round.attempt(s, Set.of(a, b), selected -> {
            assertEquals(b, selected.writer()); return XBusExchange.Status.EXCHANGED;
        }));
    }

    @Test void malformedTopologyCannotSilentlyDropPinsOrGuessCanonicalOrder() {
        var a = pin(1); var b = pin(2);
        assertThrows(IllegalArgumentException.class, () -> new XBusNetwork(List.of(a, pin(1)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new XBusNetwork(List.of(a), List.of(new XBusNetwork.Link(a, b))));
        assertThrows(IllegalArgumentException.class, () -> new XBusNetwork.Pin(UUID.randomUUID(), " "));
        var round = new XBusNetwork(List.of(a), List.of()).round(1);
        assertThrows(IllegalArgumentException.class, () -> round.peek(b, Set.of(a)));
        assertThrows(IllegalArgumentException.class, () -> round.peek(a, Set.of(b)));
    }

    @Test void onlySuccessfulCommitOccupiesAndRejectionDoesNotTryASecondWriter() {
        var a = pin(1); var b = pin(2); var r = pin(3);
        var network = new XBusNetwork(List.of(a, b, r), List.of(new XBusNetwork.Link(a, r), new XBusNetwork.Link(b, r)));
        for (var rejection : XBusExchange.Status.values()) {
            if (rejection == XBusExchange.Status.EXCHANGED || rejection == XBusExchange.Status.PREPARED) continue;
            var round = network.round(88); var calls = new AtomicInteger();
            var selected = round.peek(r, Set.of(a, b)).orElseThrow();
            assertEquals(rejection, round.attempt(r, Set.of(a, b), pair -> { calls.incrementAndGet(); assertEquals(selected, pair); return rejection; }));
            assertEquals(1, calls.get()); assertFalse(round.occupied(r));
            assertEquals(selected, round.peek(r, Set.of(a, b)).orElseThrow());
        }
    }

    @Test void reentrantCallbackAndAmbiguousPostPublicationFailureCannotDoubleConsume() {
        var a = pin(1); var r = pin(2); var s = pin(3);
        var network = new XBusNetwork(List.of(a, r, s), List.of(new XBusNetwork.Link(a, r), new XBusNetwork.Link(a, s)));
        var round = network.round(1);
        var failure = new IllegalStateException("Dirty callback failed after commit");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> round.attempt(r, Set.of(a), pair -> {
            assertEquals(XBusExchange.Status.NOT_READY, round.attempt(s, Set.of(a), ignored -> fail("Reentrant exchange")));
            throw failure;
        })));
        assertTrue(round.occupied(s)); assertTrue(round.peek(s, Set.of(a)).isEmpty());
        var invalid = network.round(2);
        assertThrows(IllegalStateException.class, () -> invalid.attempt(r, Set.of(a), ignored -> XBusExchange.Status.PREPARED));
        assertTrue(invalid.occupied(a));
    }

    static final DataType.RecordType PACKET = new DataType.RecordType(Map.of("value", DataType.Scalar.INTEGER));
    static final ResourceLimits LIMITS = new ResourceLimits(128, 100000, 16);
    static DataValue.RecordValue packet(long value) { return new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(value))); }
    static FlowRuntime writer(int id, long value) {
        return start(id, new FlowProgram(Map.of("out", PACKET), List.of(new FlowProgram.Write("send", "x0", "out"))),
            new DataValue.RecordValue(Map.of("out", packet(value))));
    }
    static FlowRuntime reader(int id, DataType.RecordType type, boolean peek) {
        return start(id, new FlowProgram(Map.of(), List.of(peek ? new FlowProgram.AwaitReadable("peek", "x0")
            : new FlowProgram.Read("read", "x0", "in", type))), new DataValue.RecordValue(Map.of()));
    }
    static FlowRuntime start(int id, FlowProgram program, DataValue.RecordValue inputs) {
        var runtime = new FlowRuntime(pin(id).computer(), UUID.randomUUID(), program, inputs, LIMITS);
        runtime.load(); runtime.advanceRound(Map.of()); return runtime;
    }

    @Test void actualFlowReadersConsumeOnlyOneOfferAndReadableIsNotAcknowledgement() {
        var a = pin(1); var b = pin(2); var r = pin(3); var s = pin(4);
        var network = new XBusNetwork(List.of(a, b, r, s), List.of(new XBusNetwork.Link(a, r),
            new XBusNetwork.Link(b, r), new XBusNetwork.Link(r, s)));
        var first = writer(1, 11); var second = writer(2, 22); var peek = reader(4, PACKET, true); var read = reader(3, PACKET, false);
        var writers = Map.of(a, first, b, second); var round = network.round(0x100010001L);
        var firstBefore = first.checkpoint(); var secondBefore = second.checkpoint();
        assertEquals(XBusExchange.Status.READABLE, round.attempt(s, writers.keySet(), selected ->
            XBusExchange.prepare(peek, "x0", writers.get(selected.writer()), "x0").commit()));
        assertEquals(FlowRuntime.Status.COMPLETE, peek.checkpoint().status());
        assertEquals(firstBefore, first.checkpoint()); assertEquals(secondBefore, second.checkpoint());
        assertFalse(round.occupied(a));
        assertEquals(XBusExchange.Status.EXCHANGED, round.attempt(r, writers.keySet(), selected ->
            XBusExchange.prepare(read, "x0", writers.get(selected.writer()), "x0").commit()));
        assertEquals(packet(11), read.checkpoint().locals().field("in"));
        assertEquals(packet(11), first.checkpoint().locals().field("out"));
        assertEquals(FlowRuntime.Status.COMPLETE, first.checkpoint().status()); assertEquals(secondBefore, second.checkpoint());
        assertTrue(round.peek(s, Set.of(b)).isEmpty());
    }

    @Test void actualTypeFailureDoesNotAdvanceEitherFlowOrOccupyTheNetwork() {
        var a = pin(1); var r = pin(2); var writer = writer(1, 7);
        var wrong = reader(2, new DataType.RecordType(Map.of("value", DataType.Scalar.TEXT)), false);
        var beforeWrite = writer.checkpoint(); var beforeRead = wrong.checkpoint();
        var round = new XBusNetwork(List.of(a, r), List.of(new XBusNetwork.Link(a, r))).round(99);
        assertEquals(XBusExchange.Status.TYPE_MISMATCH, round.attempt(r, Set.of(a), selected -> XBusExchange.prepare(wrong, "x0", writer, "x0").commit()));
        assertEquals(beforeWrite, writer.checkpoint()); assertEquals(beforeRead, wrong.checkpoint());
        assertFalse(round.occupied(r));
    }
}
