package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.api.*;

class ComputerXBusEndpointTest {
    @BeforeAll static void setup() throws Exception { ComputerBehaviourTest.bootstrap(); }

    @Test void realComputersRendezvousThroughTheSameContractAsADelegatingEndpoint() {
        var pair = ready(); CommunicationEndpoint left = new Proxy(pair.writer()); CommunicationEndpoint right = new Proxy(pair.reader());
        var a = left.xbusPort("writer", "x0").orElseThrow(); var b = right.xbusPort("reader", "x1").orElseThrow();
        assertEquals(XBusExchange.Status.EXCHANGED, left.exchangeXBus("writer", a, right, "reader", b));
        assertEquals(ComputerFlowHostTest.INPUTS, pair.reader().flowSnapshot().orElseThrow().state().locals());
        assertEquals(FlowRuntime.Status.COMPLETE, pair.writer().flowSnapshot().orElseThrow().state().status());
        assertEquals(FlowRuntime.Status.COMPLETE, pair.reader().flowSnapshot().orElseThrow().state().status());
        assertEquals(XBusExchange.Status.STALE, left.exchangeXBus("writer", a, right, "reader", b));
    }

    record Proxy(CommunicationEndpoint delegate) implements CommunicationEndpoint {
        @Override public Optional<EndpointConfiguration> advertisement() { return delegate.advertisement(); }
        @Override public Optional<XBusPortState> xbusPort(String endpoint, String pin) { return delegate.xbusPort(endpoint, pin); }
        @Override public Optional<XBusAccess> xbusAccess(String endpoint, XBusPortState expected) { return delegate.xbusAccess(endpoint, expected); }
    }

    @Test void twoAliasesOfOneComputerCannotCreateASecondRendezvousOwner() {
        var pair = ready(); CommunicationEndpoint left = new Proxy(pair.writer()); CommunicationEndpoint right = new Proxy(pair.writer());
        var observed = left.xbusPort("writer", "x0").orElseThrow(); var before = pair.writer().flowSnapshot();
        assertEquals(XBusExchange.Status.STALE, left.exchangeXBus("writer", observed, right, "writer", observed));
        assertEquals(before, pair.writer().flowSnapshot());
        assertTrue(pair.reader().flowSnapshot().orElseThrow().receipts().isEmpty());
    }

    @Test void layeredRouteGuardsAreRevalidatedAndCannotDropEarlierAuthority() {
        var pair = ready();
        var a = pair.writer().xbusAccess("writer", pair.writer().xbusPort("writer", "x0").orElseThrow()).orElseThrow();
        var b = pair.reader().xbusAccess("reader", pair.reader().xbusPort("reader", "x1").orElseThrow()).orElseThrow();
        var first = new java.util.concurrent.atomic.AtomicReference<>(Optional.<XBusExchange.Status>empty());
        var second = new java.util.concurrent.atomic.AtomicReference<>(Optional.<XBusExchange.Status>empty());
        var route = b.guarded(first::get).guarded(second::get);
        var beforeA = pair.writer().flowSnapshot(); var beforeB = pair.reader().flowSnapshot();
        first.set(Optional.of(XBusExchange.Status.CLOSED));
        assertEquals(XBusExchange.Status.CLOSED, a.exchange(route));
        first.set(Optional.empty()); second.set(Optional.of(XBusExchange.Status.UNAVAILABLE));
        assertEquals(XBusExchange.Status.UNAVAILABLE, a.exchange(route));
        assertEquals(beforeA, pair.writer().flowSnapshot()); assertEquals(beforeB, pair.reader().flowSnapshot());
        second.set(Optional.empty()); pair.reader().disableEndpoint();
        assertEquals(XBusExchange.Status.CLOSED, a.exchange(route), "Route approval cannot replace the member grant");
        pair.reader().configureEndpoint(new EndpointConfiguration("reader", true));
        assertEquals(XBusExchange.Status.EXCHANGED, a.exchange(route));
    }

    @Test void capturedAccessChecksBothOwnersAndNeverReusesAResetContinuation() {
        for (boolean left : List.of(false, true)) for (String fault : List.of("closed", "renamed", "unavailable", "paused", "reset")) {
            var pair = ready(); var source = pair.writer(); var target = pair.reader();
            var a = source.xbusAccess("writer", source.xbusPort("writer", "x0").orElseThrow()).orElseThrow();
            var b = target.xbusAccess("reader", target.xbusPort("reader", "x1").orElseThrow()).orElseThrow();
            var changed = left ? source : target;
            switch (fault) {
                case "closed" -> changed.disableEndpoint();
                case "renamed" -> changed.configureEndpoint(new EndpointConfiguration("new_name", true));
                case "unavailable" -> { if (left) pair.writerBlock().available = false; else pair.readerBlock().available = false; }
                case "paused" -> changed.setPaused(true);
                case "reset" -> { changed.reset(); changed.setPaused(false); changed.tick(); }
            }
            var beforeA = source.flowSnapshot(); var beforeB = target.flowSnapshot();
            var expected = switch (fault) {
                case "closed", "renamed" -> XBusExchange.Status.CLOSED;
                case "unavailable" -> XBusExchange.Status.UNAVAILABLE;
                default -> XBusExchange.Status.STALE;
            };
            assertEquals(expected, a.exchange(b), fault + " left=" + left);
            assertEquals(beforeA, source.flowSnapshot()); assertEquals(beforeB, target.flowSnapshot());
        }
    }

    @Test void laterPeerCallbacksCannotBypassTheRealComputersFinalHardwareOrGrantProof() {
        for (String fault : List.of("closed", "unavailable", "hardware")) {
            var pair = ready();
            var a = pair.writer().xbusAccess("writer", pair.writer().xbusPort("writer", "x0").orElseThrow()).orElseThrow();
            var b = pair.reader().xbusAccess("reader", pair.reader().xbusPort("reader", "x1").orElseThrow()).orElseThrow().guarded(() -> {
                if (fault.equals("closed")) pair.writer().disableEndpoint();
                else if (fault.equals("unavailable")) pair.writerBlock().available = false;
                else {
                    var old = pair.writerBlock().selection;
                    pair.writerBlock().selection = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwareSelection(
                        com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwareSelection.Status.NO_RESIDENT,
                        Optional.empty(), "", old.limits());
                }
                return Optional.empty();
            });
            var beforeA = pair.writer().flowSnapshot(); var beforeB = pair.reader().flowSnapshot();
            assertEquals(fault.equals("closed") ? XBusExchange.Status.CLOSED : XBusExchange.Status.UNAVAILABLE, a.exchange(b), fault);
            assertEquals(beforeA, pair.writer().flowSnapshot()); assertEquals(beforeB, pair.reader().flowSnapshot());
        }
    }

    @Test void aThirdNetworkParticipantCannotRevokeAnEarlierRealOwnerAfterItsFinalProof() {
        for (String phase : List.of("guard", "limits", "route")) {
            var pair = ready(); var third = ready().writer();
            var a = pair.writer().xbusAccess("writer", pair.writer().xbusPort("writer", "x0").orElseThrow()).orElseThrow();
            var b = pair.reader().xbusAccess("reader", pair.reader().xbusPort("reader", "x1").orElseThrow()).orElseThrow();
            var c = third.xbusAccess("writer", third.xbusPort("writer", "x0").orElseThrow()).orElseThrow();
            if (phase.equals("guard")) c = c.guarded(() -> { pair.writer().disableEndpoint(); return Optional.empty(); });
            else if (phase.equals("route")) c = c.withPhysicalRoute(() -> { pair.writer().disableEndpoint(); return Optional.empty(); }, Optional::empty);
            else {
                var host = new ComputerBlockHost(() -> {});
                ComputerXBusExchangeTest.start(host, ComputerFlowHostTest.writer(), ComputerFlowHostTest.INPUTS);
                c = new XBusAccess(host, XBusPortState.of(host.flowSnapshot().orElseThrow(), ComputerFlowHostTest.LIMITS.pins(), "x0").orElseThrow(),
                    () -> { pair.writer().disableEndpoint(); return ComputerFlowHostTest.LIMITS; }, Optional::empty);
            }
            var beforeA = pair.writer().flowSnapshot(); var beforeB = pair.reader().flowSnapshot();
            var network = XBusAccess.network(List.of(a, b, c));
            var left = new XBusNetwork.Pin(beforeA.orElseThrow().state().computer(), "x0");
            var right = new XBusNetwork.Pin(beforeB.orElseThrow().state().computer(), "x1");
            assertEquals(XBusExchange.Status.CLOSED, network.exchange(left, right), phase);
            assertEquals(beforeA, pair.writer().flowSnapshot()); assertEquals(beforeB, pair.reader().flowSnapshot());
        }
    }

    @Test void finalHardwareProofAndCommitUseTheSameBudgetDespiteAnIntermediateConfiguration() {
        var pair = ready(); var original = pair.writerBlock().selection;
        var a = pair.writer().xbusAccess("writer", pair.writer().xbusPort("writer", "x0").orElseThrow()).orElseThrow();
        var b = pair.reader().xbusAccess("reader", pair.reader().xbusPort("reader", "x1").orElseThrow()).orElseThrow().guarded(() -> {
            pair.writerBlock().selection = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwareSelection(
                original.status(), original.resident(), original.profile(),
                new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(
                    new com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits(1, 1, 1), original.limits().archive()));
            return Optional.empty();
        }).withPhysicalRoute(() -> { pair.writerBlock().selection = original; return Optional.empty(); }, Optional::empty);
        assertEquals(XBusExchange.Status.EXCHANGED, a.exchange(b), "Commit must use exactly the original budget validated at the boundary");
        assertEquals(ComputerFlowHostTest.INPUTS, pair.reader().flowSnapshot().orElseThrow().state().locals());
    }

    @Test void matchingPausedObservationsRemainPausedAndWrongPhysicalPinsRemainStale() {
        var pair = ready(); pair.reader().setPaused(true);
        var left = pair.writer().xbusPort("writer", "x0").orElseThrow(); var right = pair.reader().xbusPort("reader", "x1").orElseThrow();
        CommunicationEndpoint writer = new Proxy(pair.writer());
        assertEquals(XBusExchange.Status.PAUSED, writer.exchangeXBus("writer", left, new Proxy(pair.reader()), "reader", right));
        var forged = new XBusPortState(left.computer(), left.flow(), "x9", left.cursor(), left.mode(), left.source());
        assertEquals(XBusExchange.Status.STALE, writer.exchangeXBus("writer", forged, new Proxy(pair.reader()), "reader", right));
        assertTrue(pair.writer().flowSnapshot().orElseThrow().receipts().isEmpty());
    }

    @Test void ordinaryMessageEndpointCannotPretendItsInboxIsAnXBusReader() {
        var pair = ready(); var calls = new java.util.concurrent.atomic.AtomicInteger();
        CommunicationEndpoint queue = new CommunicationEndpoint() {
            @Override public Optional<EndpointConfiguration> advertisement() { return Optional.of(new EndpointConfiguration("queue", true)); }
            @Override public com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.AdmissionStatus receive(
                    com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MessageEnvelope message) {
                calls.incrementAndGet(); return com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.AdmissionStatus.ACCEPTED;
            }
        };
        var before = pair.writer().flowSnapshot(); var expected = pair.writer().xbusPort("writer", "x0").orElseThrow();
        assertEquals(XBusExchange.Status.UNSUPPORTED, pair.writer().exchangeXBus("writer", expected, queue, "queue", expected));
        assertEquals(0, calls.get()); assertEquals(before, pair.writer().flowSnapshot());
    }

    @Test void readableWaitThroughAnEndpointDoesNotConsumeUntilItsActualRead() {
        var pair = ready(); var reader = pair.reader(); reader.reset();
        reader.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.AwaitReadable("wait", "x1"),
            new FlowProgram.Read("read", "x1", "packet", ComputerFlowHostTest.PACKET))), ComputerXBusExchangeTest.EMPTY);
        reader.setPaused(false); reader.tick();
        CommunicationEndpoint a = new Proxy(pair.writer()); CommunicationEndpoint b = new Proxy(reader);
        assertEquals(XBusExchange.Status.READABLE, a.exchangeXBus("writer", a.xbusPort("writer", "x0").orElseThrow(), b,
            "reader", b.xbusPort("reader", "x1").orElseThrow()));
        assertEquals(FlowRuntime.Status.WAITING_WRITE, pair.writer().flowSnapshot().orElseThrow().state().status());
        assertTrue(reader.flowSnapshot().orElseThrow().receipts().isEmpty());
        assertEquals(ComputerXBusExchangeTest.EMPTY, reader.flowSnapshot().orElseThrow().state().locals());
        reader.tick();
        assertEquals(XBusExchange.Status.EXCHANGED, b.exchangeXBus("reader", b.xbusPort("reader", "x1").orElseThrow(), a,
            "writer", a.xbusPort("writer", "x0").orElseThrow()));
        assertEquals(ComputerFlowHostTest.INPUTS, reader.flowSnapshot().orElseThrow().state().locals());
    }

    record Pair(ComputerBehaviourTest.Harness writerBlock, ComputerBehaviourTest.Harness readerBlock,
            ComputerBehaviour writer, ComputerBehaviour reader) {}
    static Pair ready() {
        var wb = new ComputerBehaviourTest.Harness(); var rb = new ComputerBehaviourTest.Harness();
        var writer = wb.getBehaviour(ComputerBehaviour.TYPE); var reader = rb.getBehaviour(ComputerBehaviour.TYPE);
        writer.installFlow(ComputerFlowHostTest.writer(), ComputerFlowHostTest.INPUTS);
        reader.installFlow(ComputerXBusExchangeTest.reader(), ComputerXBusExchangeTest.EMPTY);
        writer.configureEndpoint(new EndpointConfiguration("writer", true)); reader.configureEndpoint(new EndpointConfiguration("reader", true));
        writer.setPaused(false); reader.setPaused(false); writer.tick(); reader.tick();
        return new Pair(wb, rb, writer, reader);
    }
}
