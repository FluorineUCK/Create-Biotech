package com.nobodiiiii.createbiotech.content.biocomputing.network;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.network.XBusCableRouteTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusExchange.Status.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class XBusCompletedPayloadTest {
    @TempDir Path directory;

    private void persist(XBusCableNetworkTest.Fixture f) {
        net.minecraft.SharedConstants.tryDetectVersion();
        f.history.save(directory.resolve(XBusHistorySavedData.DATA_NAME + ".dat").toFile(), null);
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
    }
    private static void round(XBusCableNetworkTest.Fixture f, boolean cohort) {
        var links = cohort ? f.links : List.of(f.links.getFirst());
        var capture = XBusCableNetwork.capture(links, SCAN, f::resolve);
        assertEquals("ready", capture.code());
        var pins = cohort ? List.of(f.pin(f.a), f.pin(f.b), f.pin(f.c)) : List.of(f.pin(f.a), f.pin(f.b));
        assertTrue(capture.network().orElseThrow().beginRound(pins, false).inspection().rejection().isEmpty());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void onlyDurableCompletionReleasesPayloadsAndMixedNewerSavesRemainUsable(boolean cohort) {
        var f = new XBusCableNetworkTest.Fixture();
        var links = cohort ? f.links : List.of(f.links.getFirst());
        assertEquals(EXCHANGED, XBusCableNetwork.capture(links, SCAN, f::resolve).network().orElseThrow()
            .exchange(f.pin(f.a), f.pin(f.b)));
        var reader = f.peers.get(f.b.target().position());
        var withPayload = reader.host.write();
        var completed = reader.host.flowSnapshot().orElseThrow();
        round(f, cohort);
        assertEquals(completed, reader.host.flowSnapshot().orElseThrow(), "No deletion before a successful disk checkpoint");
        persist(f);
        var observation = XBusCableNetwork.capture(links, SCAN, f::resolve).network().orElseThrow();
        assertTrue(observation.inspect().rejection().isEmpty());
        assertEquals(completed, reader.host.flowSnapshot().orElseThrow(), "Read-only inspection cannot reclaim memory");
        round(f, cohort);
        var cleaned = reader.host.flowSnapshot().orElseThrow();
        assertTrue(cleaned.receipts().isEmpty(), "A completed payload must not stay tied to the lifetime of the main program");
        assertEquals(completed.state(), cleaned.state());
        assertEquals(completed.tasks(), cleaned.tasks());
        assertEquals(completed.memory(), cleaned.memory());
        assertEquals(completed.control().retireOperations(completed.receipts(), completed.state().computer()), cleaned.control());
        // One newer Computer save still contains its old receipt; the other has reclaimed it.
        reader.host.read(withPayload); reader.host.tick(HOST, true);
        round(f, cohort);
        assertTrue(reader.host.flowSnapshot().orElseThrow().receipts().isEmpty());
        assertEquals(completed.state().flow(), reader.host.flowSnapshot().orElseThrow().state().flow());
    }

    @Test void aSingleLongRunningIsaProgramDoesNotAccumulateCompletedPayloadsAcrossCheckpoints() {
        var f = new XBusCableNetworkTest.Fixture();
        var writer = f.peers.get(f.a.target().position()); var reader = f.peers.get(f.b.target().position());
        for (var peer : List.of(writer, reader)) {
            boolean sending = peer == writer;
            peer.host.reset(HOST, true);
            var program = new FlowProgram(sending ? Map.of("packet", PACKET) : Map.of(), List.of(
                new FlowProgram.Forever("loop"), sending ? new FlowProgram.Write("send", "x0", "packet")
                    : new FlowProgram.Read("read", "x1", "packet", PACKET), new FlowProgram.EndForever("repeat"))).withAssembly();
            peer.host.installFlow(program, sending ? INPUTS : EMPTY, HOST, true);
            peer.host.setPaused(false, HOST, true);
        }
        var writerId = writer.host.flowSnapshot().orElseThrow().state().flow();
        var readerId = reader.host.flowSnapshot().orElseThrow().state().flow();
        for (int transfer = 0; transfer < 24; transfer++) {
            for (int tick = 0; tick < 40; tick++) {
                writer.host.tick(HOST, true); reader.host.tick(HOST, true);
                if (writer.host.flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.WAITING_WRITE
                    && reader.host.flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.WAITING_READ) break;
            }
            assertEquals(EXCHANGED, XBusCableNetwork.capture(List.of(f.links.getFirst()), SCAN, f::resolve)
                .network().orElseThrow().exchange(f.pin(f.a), f.pin(f.b)));
            persist(f); round(f, false);
            assertTrue(writer.host.flowSnapshot().orElseThrow().receipts().isEmpty());
            assertTrue(reader.host.flowSnapshot().orElseThrow().receipts().isEmpty());
            assertEquals(writerId, writer.host.flowSnapshot().orElseThrow().state().flow());
            assertEquals(readerId, reader.host.flowSnapshot().orElseThrow().state().flow());
            assertEquals(INPUTS, reader.host.flowSnapshot().orElseThrow().state().locals());
        }
    }

    @Test void interruptedReclamationRetainsProgramStateAndCanFinishOnTheNextRound() {
        var f = new XBusCableNetworkTest.Fixture();
        assertEquals(EXCHANGED, XBusCableNetwork.capture(f.links, SCAN, f::resolve).network().orElseThrow()
            .exchange(f.pin(f.a), f.pin(f.b)));
        persist(f);
        var states = f.peers.values().stream().map(peer -> peer.host.flowSnapshot().orElseThrow().state()).toList();
        f.peers.values().forEach(peer -> peer.onDirty = () -> { throw new IllegalStateException("reclamation save notification"); });
        assertThrows(IllegalStateException.class, () -> round(f, true));
        assertEquals(states, f.peers.values().stream().map(peer -> peer.host.flowSnapshot().orElseThrow().state()).toList());
        f.peers.values().forEach(peer -> peer.onDirty = () -> {});
        round(f, true);
        assertTrue(f.peers.values().stream().allMatch(peer -> peer.host.flowSnapshot().orElseThrow().receipts().isEmpty()));
        assertEquals(states, f.peers.values().stream().map(peer -> peer.host.flowSnapshot().orElseThrow().state()).toList());
    }

    @Test void reclamationCallbacksCannotBypassARevokedPortGrant() {
        var f = new XBusCableNetworkTest.Fixture();
        var links = List.of(f.links.getFirst());
        assertEquals(EXCHANGED, XBusCableNetwork.capture(links, SCAN, f::resolve).network().orElseThrow()
            .exchange(f.pin(f.a), f.pin(f.b)));
        persist(f);
        var writer = f.peers.get(f.a.target().position()); var reader = f.peers.get(f.b.target().position());
        var state = reader.host.flowSnapshot().orElseThrow().state();
        var captured = XBusCableNetwork.capture(links, SCAN, f::resolve).network().orElseThrow();
        writer.onDirty = () -> reader.open = false;
        var result = captured.beginRound(List.of(f.pin(f.a), f.pin(f.b)), true);
        assertEquals(Optional.of(CLOSED), result.inspection().rejection());
        assertTrue(result.writer().isEmpty(), "Retirement is not authorization for a subsequent handoff");
        assertEquals(state, reader.host.flowSnapshot().orElseThrow().state());
        writer.onDirty = () -> {}; reader.open = true;
        round(f, false);
        assertTrue(reader.host.flowSnapshot().orElseThrow().receipts().isEmpty());
    }

    @Test void reclaimingBroadcastPayloadDoesNotClaimOrEraseACompletedChildResult() {
        var f = new XBusCableNetworkTest.Fixture();
        var limits = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(
            HOST.hardware(), HOST.archive(), HOST.mailbox(), HOST.pins(), HOST.serial(), 2, HOST.isa1a(), HOST.isa1b());
        var writer = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(() -> {});
        var reader = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(() -> {});
        var key = new com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition.Key("receive", 1);
        writer.installFlow(new FlowProgram(Map.of("packet", PACKET), List.of(new FlowProgram.Write("send", "x0", "packet")))
            .withAssembly(), INPUTS, limits, true);
        reader.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("main", "x0", "root", PACKET),
            new FlowProgram.Procedure("receive", key, Map.of(), Map.of("packet", PACKET), Map.of("packet", "packet")),
            new FlowProgram.Read("child", "x0", "packet", PACKET), new FlowProgram.EndProcedure("return"))).withAssembly(), EMPTY, limits, true);
        writer.setPaused(false, limits, true); reader.setPaused(false, limits, true);
        var child = reader.startFlowTask(key, EMPTY, limits, true);
        for (int tick = 0; tick < 8; tick++) { writer.tick(limits, true); reader.tick(limits, true); }
        var hosts = List.of(writer, reader);
        java.util.function.Supplier<XBusAccess.Network> network = () -> XBusAccess.network(hosts.stream().map(host ->
            new XBusAccess(host, XBusPortState.of(host.flowSnapshot().orElseThrow(), limits.pins(), "x0").orElseThrow(),
                () -> limits, Optional::empty).historical(f.history, () -> 10, () -> 100)).toList());
        var pins = hosts.stream().map(host -> new XBusNetwork.Pin(host.flowSnapshot().orElseThrow().state().computer(), "x0")).toList();
        assertEquals(EXCHANGED, network.get().exchange(pins.getFirst(), pins.getLast()));
        for (int tick = 0; tick < 8; tick++) reader.tick(limits, true);
        assertEquals(FlowRuntime.Status.COMPLETE, reader.flowTask(child).orElseThrow().status());
        var tasks = reader.flowSnapshot().orElseThrow().tasks();
        persist(f);
        assertTrue(network.get().beginRound(pins, false).inspection().rejection().isEmpty());
        assertTrue(reader.flowSnapshot().orElseThrow().receipts().isEmpty());
        assertEquals(tasks, reader.flowSnapshot().orElseThrow().tasks(), "Completed results remain charged and explicitly claimable");
        assertEquals(INPUTS, reader.claimFlowTask(child, limits, true).locals());
    }

    @Test void aReclaimedOwnerStillFencesAnOldPeerIfTheDimensionJournalIsMissing() {
        var f = new XBusCableNetworkTest.Fixture();
        var links = List.of(f.links.getFirst());
        var reader = f.peers.get(f.b.target().position()); var beforeRead = reader.host.write();
        assertEquals(EXCHANGED, XBusCableNetwork.capture(links, SCAN, f::resolve).network().orElseThrow()
            .exchange(f.pin(f.a), f.pin(f.b)));
        persist(f); round(f, false);
        var writer = f.peers.get(f.a.target().position()); var reclaimed = writer.host.write();
        writer.host.read(reclaimed); writer.host.tick(HOST, true);
        f.history = new XBusHistorySavedData(); f.peers.values().forEach(peer -> peer.history = f.history);
        reader.host.read(beforeRead); reader.host.tick(HOST, true);
        var before = f.snapshots();
        var capture = XBusCableNetwork.capture(links, SCAN, f::resolve);
        assertEquals("retired", capture.code());
        assertTrue(capture.network().isEmpty());
        assertEquals(before, f.snapshots(), "Local saved watermarks, not discarded payloads, forbid replay");
    }

    @Test void aLaterCompletionCannotAdvanceLocalWatermarksAcrossAnUnconfirmedReceipt() {
        var f = new XBusCableNetworkTest.Fixture(); var links = List.of(f.links.getFirst());
        var writer = f.peers.get(f.a.target().position()); var reader = f.peers.get(f.b.target().position());
        for (var peer : List.of(writer, reader)) {
            boolean sending = peer == writer; peer.host.reset(HOST, true);
            peer.host.installFlow(new FlowProgram(sending ? Map.of("packet", PACKET) : Map.of(), List.of(
                sending ? new FlowProgram.Write("first", "x0", "packet") : new FlowProgram.Read("first", "x1", "packet", PACKET),
                sending ? new FlowProgram.Write("second", "x0", "packet") : new FlowProgram.Read("second", "x1", "packet", PACKET)))
                .withAssembly(), sending ? INPUTS : EMPTY, HOST, true);
            peer.host.setPaused(false, HOST, true); peer.host.tick(HOST, true);
        }
        writer.onDirty = () -> { throw new IllegalStateException("first notification failed"); };
        assertThrows(IllegalStateException.class, () -> XBusCableNetwork.capture(links, SCAN, f::resolve).network().orElseThrow()
            .exchange(f.pin(f.a), f.pin(f.b)));
        writer.onDirty = () -> {};
        for (int tick = 0; tick < 8; tick++) { writer.host.tick(HOST, true); reader.host.tick(HOST, true); }
        assertEquals(EXCHANGED, XBusCableNetwork.capture(links, SCAN, f::resolve).network().orElseThrow()
            .exchange(f.pin(f.a), f.pin(f.b)));
        var owners = List.of(writer.host.flowSnapshot().orElseThrow(), reader.host.flowSnapshot().orElseThrow());
        assertEquals(2, owners.getFirst().receipts().size());
        persist(f); round(f, false);
        assertEquals(owners, List.of(writer.host.flowSnapshot().orElseThrow(), reader.host.flowSnapshot().orElseThrow()),
            "Keep the newer payload too if its retirement watermark would erase an unresolved earlier position");
        assertTrue(f.history.certifyCompleted(owners.getFirst().receipts().getFirst(), owners));
        persist(f); round(f, false);
        assertTrue(writer.host.flowSnapshot().orElseThrow().receipts().isEmpty());
        assertTrue(reader.host.flowSnapshot().orElseThrow().receipts().isEmpty());
    }
}
