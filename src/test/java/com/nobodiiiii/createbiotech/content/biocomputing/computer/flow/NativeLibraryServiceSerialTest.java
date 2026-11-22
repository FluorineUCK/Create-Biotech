package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryServiceDocuments;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/** Real typed serial encoding/decoding and XBus service tasks; the responder is a protocol fixture. */
class NativeLibraryServiceSerialTest {
    static XBusAccess access(ComputerBlockHost host, String pin, ComputerHostLimits limits, XBusHistorySavedData history) {
        return NativeLibraryStartupTest.access(host, pin, limits).historical(history,
            () -> NativeLibraryOwnersTest.CONFIG.xbusHistoryComputerLimit(false),
            () -> NativeLibraryOwnersTest.CONFIG.xbusHistoryClaimLimit(false));
    }
    static void exchange(ComputerBlockHost a, String ap, ComputerHostLimits la,
                         ComputerBlockHost b, String bp, ComputerHostLimits lb, XBusHistorySavedData history) {
        var result = access(a, ap, la, history).exchange(access(b, bp, lb, history));
        assertFalse(Set.of(XBusExchange.Status.UNAVAILABLE, XBusExchange.Status.RECOVERY_REQUIRED,
            XBusExchange.Status.HISTORY_CONFLICT, XBusExchange.Status.MEMORY_LIMIT, XBusExchange.Status.ARCHIVE_LIMIT).contains(result), result.toString());
    }
    static long units(FlowBlockHost.Saved saved, ComputerHostLimits limits) {
        var main = FlowRuntime.restore(saved.program(), saved.state(), limits.hardware());
        long total = main.retainedUnits() + saved.initialInputs().storageUnits() + saved.control().storageUnits()
            + saved.pinUnits() + saved.tasks().metadataUnits() + saved.memory().storageUnits()
            + saved.receipts().stream().mapToLong(r -> r.value().storageUnits()).sum();
        for (var task : saved.tasks().contexts()) total += FlowRuntime.restore(saved.program(), task, limits.hardware()).retainedUnits() - main.sharedUnits();
        return total;
    }

    @Test void finiteSerialServiceReusesItsTaskAcrossRequestsAndRestoresAnActiveChildCodec() {
        var la = NativeLibraryOwnersTest.limits("cleric", 5);
        var lb = NativeLibraryOwnersTest.limits("cleric", 5);
        var lc = NativeLibraryOwnersTest.limits("cleric", 5);
        var serialB = NativeLibraryOwnersTest.device(lb); serialB.configure("s0", Direction.EAST, true);
        var serialL = NativeLibraryOwnersTest.device(lb); serialL.configure("s0", Direction.WEST, true);
        var b = NativeLibraryOwnersTest.install(NativeLibraryServiceDocuments.build("b"), lb, serialB);
        var c = NativeLibraryOwnersTest.install(NativeLibraryServiceDocuments.build("c"), lc, null);
        var a = new ComputerBlockHost(() -> {});
        var call = new ModuleDefinition.Key("request", 1);
        a.installFlow(new FlowProgram(Map.of(), List.of(
            new FlowProgram.Procedure("request", call, Map.of("packet", PACKET), Map.of("outcome", OUTCOME), Map.of("outcome", "outcome")),
            new FlowProgram.Write("send", "x0", "packet"), new FlowProgram.Read("receive", "x0", "outcome", OUTCOME),
            new FlowProgram.EndProcedure("return"))).withAssembly(), record(), la, true);
        a.setPaused(false, la, true); b.setPaused(false, lb, true); c.setPaused(false, lc, true); a.tick(la, true);
        var bIdentity = b.flowSnapshot().orElseThrow().state().flow();
        var cIdentity = c.flowSnapshot().orElseThrow().state().flow();
        var posB = new BlockPos(0, 0, 0); var cable = new BlockPos(1, 0, 0); var posL = new BlockPos(2, 0, 0);
        var devices = Map.of(posB, serialB, posL, serialL);
        var world = new CableDiscovery.World() {
            public boolean loaded(BlockPos pos) { return true; }
            public CableDiscovery.Cell cell(BlockPos pos) {
                return new CableDiscovery.Cell(pos.equals(cable) ? CableDiscovery.Kind.CABLE : devices.containsKey(pos) ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY, null);
            }
        };
        var serial = new SerialNetwork(); var history = new XBusHistorySavedData();
        int requests = 0; boolean restored = false; long peakB = 0, peakC = 0;
        var receivedIds = new HashSet<DataValue>();
        for (int round = 0; round < 12; round++) {
            var packet = record("kind", "library_command", "target", record("x", 54, "y", 64, "z", 2),
                "request", "00000000-0000-0000-0000-000000000001:" + round, "operation", "status", "sequence", 0, "data", null);
            var reply = record("kind", "library_reply", "target", packet.field("target"), "request", packet.field("request"),
                "code", "idle", "sequence", round, "value", record("remaining", 0, "slot", 2, "track", record("x", 1, "y", 0, "z", 0)));
            var task = a.startFlowTask(call, record("packet", packet), la, true);
            boolean finished = false;
            for (int tick = 0; tick < 4000; tick++) {
                a.tick(la, true);
                b.tick(lb, true, Map.of("x0", NativeLibraryStartupTest.status(b, "x0", lb, a, "x0", la)));
                c.tick(lc, true, Map.of("x0", NativeLibraryStartupTest.status(c, "x0", lc, b, "x1", lb)));
                exchange(a, "x0", la, b, "x0", lb, history); exchange(b, "x1", lb, c, "x0", lc, history);
                serial.tick(world, devices, NativeLibraryOwnersTest.CONFIG.communication(false), NativeLibraryOwnersTest.CONFIG.serialMedium(false));
                if (serialL.status("s0").receive().frames() > 0) {
                    var received = serialL.prepareReceive("s0");
                    assertEquals(packet, received.result().field("value"));
                    assertTrue(receivedIds.add(packet.field("request")), "No implicit resend");
                    requests++; assertTrue(received.publish()); assertEquals("queued", serialL.send("s0", reply));
                }
                for (var host : List.of(a, b, c)) assertTrue(host.loaded(), host.diagnostic().toString());
                var bs = b.flowSnapshot().orElseThrow(); var cs = c.flowSnapshot().orElseThrow();
                peakB = Math.max(peakB, units(bs, lb)); peakC = Math.max(peakC, units(cs, lc));
                assertTrue(peakB <= lb.hardware().memoryUnits()); assertTrue(peakC <= lc.hardware().memoryUnits());
                assertTrue(bs.receipts().size() <= 4); assertTrue(cs.receipts().size() <= 2);
                if (!restored && bs.tasks().contexts().stream().anyMatch(s -> s.assembly().flatMap(r -> r.serial()).filter(p -> p.receiving()).isPresent())) {
                    b = NativeLibraryOwnersTest.reload(b, lb, serialB); c = NativeLibraryOwnersTest.reload(c, lc, null);
                    a = NativeLibraryOwnersTest.reload(a, la, null);
                    var data = history.save(new CompoundTag(), RegistryAccess.EMPTY);
                    history = XBusHistorySavedData.load(data, RegistryAccess.EMPTY); restored = true;
                }
                if (a.flowTask(task).orElseThrow().status() == FlowRuntime.Status.COMPLETE
                    && bs.tasks().contexts().isEmpty() && cs.tasks().contexts().isEmpty()
                    && bs.state().status() == FlowRuntime.Status.SLEEPING && cs.state().status() == FlowRuntime.Status.SLEEPING) {
                    finished = true; break;
                }
            }
            assertTrue(finished, "Request must finish without resetting a service owner");
            assertEquals(record("outcome", record("ok", true, "value", reply)), a.claimFlowTask(task, la, true).locals());
            assertTrue(b.flowSnapshot().orElseThrow().receipts().isEmpty()); assertTrue(c.flowSnapshot().orElseThrow().receipts().isEmpty());
            assertEquals(bIdentity, b.flowSnapshot().orElseThrow().state().flow()); assertEquals(cIdentity, c.flowSnapshot().orElseThrow().state().flow());
            assertEquals(round, b.flowSnapshot().orElseThrow().tasks().slots().entries().getFirst().incarnation());
        }
        assertTrue(restored); assertEquals(12, requests);
        System.out.println("12 finite serial transactions, active child RX restore, no replay; RAM peaks B=" + peakB + " C=" + peakC);
    }
}
