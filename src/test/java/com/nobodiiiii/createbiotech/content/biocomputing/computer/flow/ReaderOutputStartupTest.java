package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ReaderOutputDocumentTest;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.network.XBusAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Player-order starts with actual paused-pin rejection, not simultaneous fixture activation. */
class ReaderOutputStartupTest {
    @Test void downstreamComputersCanBeStartedIndividuallyBeforeTheExplicitSamplingTrigger() throws Exception {
        var limits = new ArrayList<ComputerHostLimits>();
        var owners = new ArrayList<ComputerBlockHost>();
        SerialDevice serial = NativeLibraryOwnersTest.device(NativeLibraryOwnersTest.limits("cleric", 5));
        for (int i = 0; i < 5; i++) {
            var l = NativeLibraryOwnersTest.limits("cleric", 5);
            limits.add(l);
            owners.add(NativeLibraryOwnersTest.install(ReaderOutputDocumentTest.load("" + (char)('a' + i)), l, i == 1 ? serial : null));
        }
        int[] order = {2, 1, 3, 4}; // C, B, D, E; A remains paused until the player requests a sample.
        for (int tick = 0; tick < 800; tick++) {
            if (tick % 200 == 0) {
                int member = order[tick / 200];
                owners.get(member).setPaused(false, limits.get(member), true);
            }
            var observations = groups(owners, limits);
            var signals = List.of(Map.<String,Integer>of(),
                Map.of("x0", observations.get(0).statusCode(NativeLibraryStartupTest.pin(owners.get(1), "x0"))),
                Map.of("x0", observations.get(1).statusCode(NativeLibraryStartupTest.pin(owners.get(2), "x0"))),
                Map.of("x0", observations.get(2).statusCode(NativeLibraryStartupTest.pin(owners.get(3), "x0"))),
                Map.<String,Integer>of());
            for (int i = 0; i < 5; i++) owners.get(i).tick(tick, limits.get(i), true, signals.get(i));
            // Re-capture after execution, as real arbitration must observe the newly waiting operation.
            var networks = groups(owners, limits);
            int[][] members = {{0,1,4}, {1,2}, {0,3}, {3,4}};
            String[][] pins = {{"x0","x0","x0"}, {"x1","x0"}, {"x1","x0"}, {"x1","x1"}};
            for (int g = 0; g < members.length; g++)
                for (int j = 0; j < members[g].length; j++)
                    networks.get(g).rejectPaused(NativeLibraryStartupTest.pin(owners.get(members[g][j]), pins[g][j]));
            for (int i = 0; i < 5; i++) {
                var state = owners.get(i).flowSnapshot().orElseThrow().state();
                assertNotEquals(FlowRuntime.Status.FAILED, state.status(), "tick="+tick+" member="+(char)('a'+i)+" "+state.diagnostic());
            }
            assertTrue(owners.get(0).flowSnapshot().orElseThrow().state().paused());
            assertEquals(0, owners.get(0).flowSnapshot().orElseThrow().state().work());
            assertEquals("idle", serial.status("s0").transmit());
            assertEquals(0, serial.status("s0").receive().frames());
        }
        assertEquals(FlowRuntime.Status.WAITING_READ, owners.get(4).flowSnapshot().orElseThrow().state().status());
        assertTrue(owners.get(3).flowSnapshot().orElseThrow().state().work() > 0);
    }

    static List<XBusAccess.Network> groups(List<ComputerBlockHost> o, List<ComputerHostLimits> l) {
        return List.of(
            XBusAccess.network(List.of(NativeLibraryStartupTest.access(o.get(0),"x0",l.get(0)), NativeLibraryStartupTest.access(o.get(1),"x0",l.get(1)), NativeLibraryStartupTest.access(o.get(4),"x0",l.get(4)))),
            NativeLibraryStartupTest.network(o.get(1),"x1",l.get(1),o.get(2),"x0",l.get(2)),
            NativeLibraryStartupTest.network(o.get(0),"x1",l.get(0),o.get(3),"x0",l.get(3)),
            NativeLibraryStartupTest.network(o.get(3),"x1",l.get(3),o.get(4),"x1",l.get(4)));
    }
}
