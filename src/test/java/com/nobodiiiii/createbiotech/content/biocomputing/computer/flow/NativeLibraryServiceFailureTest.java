package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.XBusHistorySavedData;
import java.util.*;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class NativeLibraryServiceFailureTest {
    @Test void serialChildFaultDoesNotBecomeAClaimedSuccessOrAnAutomaticResend() {
        var limits = NativeLibraryOwnersTest.limits("cleric", 5);
        var serial = NativeLibraryOwnersTest.device(limits); serial.configure("s0", Direction.EAST, true);
        var document = NativeLibraryServiceDocuments.build("b"); var body = document.flow().orElseThrow();
        var service = new ComputerBlockHost(() -> {}, p -> {}, serial);
        // Deliberately invalid child Sleep; the main gate is entered with an actual waiting sender.
        service.installFlow(body.program(document.archive()), record("interval", -1), limits, true);
        var caller = new ComputerBlockHost(() -> {});
        caller.installFlow(new FlowProgram(Map.of("packet", PACKET), List.of(
            new FlowProgram.Write("send", "x0", "packet"), new FlowProgram.Read("receive", "x0", "outcome", OUTCOME))).withAssembly(),
            record("packet", NativeLibraryFailureTest.packet()), limits, true);
        caller.setPaused(false, limits, true);
        for (int tick = 0; tick < 40 && caller.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.WAITING_WRITE; tick++) caller.tick(limits, true);
        assertEquals(FlowRuntime.Status.WAITING_WRITE, caller.flowSnapshot().orElseThrow().state().status());
        service.setPaused(false, limits, true);
        var history = new XBusHistorySavedData();
        for (int tick = 0; tick < 1000; tick++) {
            caller.tick(limits, true);
            service.tick(limits, true, Map.of("x0", NativeLibraryStartupTest.status(service, "x0", limits, caller, "x0", limits)));
            NativeLibraryServiceSerialTest.exchange(caller, "x0", limits, service, "x0", limits, history);
            if (service.flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.FAILED) break;
        }
        var state = service.flowSnapshot().orElseThrow();
        assertEquals(FlowRuntime.Status.FAILED, state.state().status());
        assertEquals("require_service_result", state.state().diagnostic().orElseThrow().location());
        var outcome = (DataValue.RecordValue) state.state().locals().field("service_result");
        assertEquals(new DataValue.BooleanValue(false), outcome.field("ok"));
        assertEquals("task_failed_invalid_argument", ((DataValue.ErrorValue)outcome.field("error")).code());
        assertTrue(state.tasks().contexts().isEmpty()); assertTrue(state.receipts().isEmpty());
        assertEquals(0, state.tasks().slots().entries().getFirst().incarnation());
        assertEquals("queued", serial.status("s0").transmit(), "The one admitted physical send is not silently cancelled or repeated");
        var before = service.write();
        service = NativeLibraryOwnersTest.reload(service, limits, serial);
        for (int tick = 0; tick < 50; tick++) service.tick(limits, true, Map.of("x0", 15));
        assertEquals(before, service.write());
        assertEquals("queued", serial.status("s0").transmit());
        assertEquals(FlowRuntime.Status.WAITING_READ, caller.flowSnapshot().orElseThrow().state().status());
    }

    @Test void missingVersionStopsThePreparationServiceAndPreservesTheChildDiagnosticAcrossReload() {
        var limits = NativeLibraryOwnersTest.limits("cleric", 5);
        var service = NativeLibraryOwnersTest.install(NativeLibraryServiceDocuments.build("d"), limits, null);
        var caller = new ComputerBlockHost(() -> {});
        var malformed = record("context", record("r", record("o", "write", "d", null, "e", "accepted", "l", true),
            "t", record("x", 54, "y", 64, "z", 2), "s", 0, "v", null, "w", "new value"));
        caller.installFlow(new FlowProgram(Map.of("context", NativeLibraryWriteDocuments.PREPARATION), List.of(
            new FlowProgram.Write("send", "x0", "context"), new FlowProgram.Read("receive", "x0", "reply", PACKET))).withAssembly(),
            malformed, limits, true);
        caller.setPaused(false, limits, true); service.setPaused(false, limits, true);
        var identity = service.flowSnapshot().orElseThrow().state().flow();
        var history = new XBusHistorySavedData();
        for (int tick = 0; tick < 300; tick++) {
            caller.tick(limits, true);
            service.tick(limits, true, Map.of("x0", NativeLibraryStartupTest.status(service, "x0", limits, caller, "x0", limits)));
            NativeLibraryServiceSerialTest.exchange(caller, "x0", limits, service, "x0", limits, history);
            assertTrue(service.loaded(), service.diagnostic().toString());
            if (service.flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.FAILED) break;
        }
        var saved = service.flowSnapshot().orElseThrow();
        assertEquals(FlowRuntime.Status.FAILED, saved.state().status(), "Claiming a failed child must not silently resume the service loop");
        assertEquals("require_service_result", saved.state().diagnostic().orElseThrow().location());
        assertEquals(identity, saved.state().flow());
        var outcome = (DataValue.RecordValue) saved.state().locals().field("service_result");
        assertEquals(new DataValue.BooleanValue(false), outcome.field("ok"));
        assertEquals("task_failed_type_mismatch", assertInstanceOf(DataValue.ErrorValue.class, outcome.field("error")).code());
        assertSame(DataValue.NullValue.INSTANCE, outcome.field("value"));
        assertTrue(saved.tasks().contexts().isEmpty());
        assertEquals(1, saved.tasks().slots().entries().size());
        assertEquals(0, saved.tasks().slots().entries().getFirst().incarnation());
        assertTrue(saved.tasks().slots().entries().getFirst().retired());
        assertTrue(saved.receipts().isEmpty(), "Claim still releases payload receipts through the existing retirement mechanism");
        assertEquals(FlowRuntime.Status.WAITING_READ, caller.flowSnapshot().orElseThrow().state().status(), "No fabricated successful reply");
        var before = service.write();
        service = NativeLibraryOwnersTest.reload(service, limits, null);
        for (int i = 0; i < 50; i++) service.tick(limits, true, Map.of("x0", 15));
        assertEquals(before, service.write(), "Error and result remain visible; no new task or retry even when another sender is present");
    }
}
