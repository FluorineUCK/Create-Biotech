package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ClusterSerialLibraryProgramTest {
    static final ResourceLimits COORDINATOR = new ResourceLimits(256, 65536, 8);
    static ProgramDocument load(String role) throws Exception {
        try (var input = ClusterSerialLibraryProgramTest.class.getClassLoader().getResourceAsStream(
            "assets/create_biotech/biocomputing/examples/cluster_serial_library_" + role + ".bcp")) {
            assertNotNull(input, "The joint application must be an editable public example");
            return ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(input.readAllBytes()).strip()), LibraryReadDriverTest.ARCHIVE);
        }
    }
    @Test void publicCallerHasAnEditableReadOnlyTerminalBoundToItsActualCompletedReply() throws Exception {
        var caller = load("caller");
        var layout = caller.terminal().orElseThrow();
        assertTrue(layout.readOnly());
        var reply = new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(42)));
        var roots = new DataValue.RecordValue(Map.of("output", new DataValue.RecordValue(Map.of(
            "reply", reply, "success", new DataValue.BooleanValue(true))), "memory", new DataValue.RecordValue(Map.of())));
        var node = (com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition.Text)layout.nodes().get("reply");
        assertEquals(reply, node.value().resolve(roots), "Ordinary output binding, not a dedicated recipe/storage widget");
        assertEquals(caller, ClusterSerialLibraryDocuments.caller());
    }
    @Test void ordinaryWorkerCalculatesWithoutStorageOrSerialPrivilegesAndMembersFitIndependentBudgets() throws Exception {
        var coordinator = load("coordinator");
        coordinator.validate(COORDINATOR);
        var worker = load("worker");
        worker.validate(new ResourceLimits(32, 32768, 4));
        var caller = load("caller");
        caller.validate(new ResourceLimits(128, 32768, 4));
        assertEquals(new DataValue.IntegerValue(42), ((DataValue.RecordValue)evaluate(worker, "calculate",
            Map.of("input", new DataValue.RecordValue(Map.of("reading", new DataValue.IntegerValue(21))))).field("output")).field("reading"));
        assertTrue(worker.flow().orElseThrow().steps().stream().noneMatch(
            com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram::serial));
        assertThrows(ComputationException.class, () -> coordinator.validate(new ResourceLimits(32, 32768, 4)),
            "An ordinary member must not borrow the capacity resident's program budget");
    }
    @Test void callerCatalogDescriptionsMatchItsActualProgramCost() throws Exception {
        var caller = load("caller");
        var units = caller.archive().compile(COORDINATOR).usage().programUnits() + caller.flow().orElseThrow().steps().size();
        for (String locale : List.of("zh_cn", "en_us")) {
            try (var stream = getClass().getClassLoader().getResourceAsStream("assets/create_biotech/lang/" + locale + ".json")) {
                assertNotNull(stream);
                var translations = com.google.gson.JsonParser.parseString(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                var description = translations.get("create_biotech.biocomputing.editor.example.cluster_serial_library_caller.description").getAsString();
                assertTrue(description.contains(Long.toString(units)), () -> locale + " must describe the installed example's real cost " + units);
            }
        }
    }
    @Test void callerIgnoresLibraryTrafficOtherServicesAndOldReplies() throws Exception {
        var caller = load("caller");
        var fields = new LinkedHashMap<String, DataValue>(Map.of("kind", new DataValue.TextValue("cluster_log_result"),
            "target", new DataValue.TextValue("journal"), "request", new DataValue.TextValue("this-run:1"),
            "ok", new DataValue.BooleanValue(true), "value", new DataValue.IntegerValue(42), "error", DataValue.NullValue.INSTANCE));
        var frames = new ArrayList<DataValue.RecordValue>(); frames.add(new DataValue.RecordValue(fields));
        for (String name : List.of("kind", "target", "request")) {
            var wrong = new LinkedHashMap<>(fields); wrong.put(name, new DataValue.TextValue("unrelated")); frames.add(new DataValue.RecordValue(wrong));
        }
        frames.add(new DataValue.RecordValue(Map.of("library_alarm", new DataValue.IntegerValue(1))));
        for (int i = 0; i < frames.size(); i++) {
            var result = evaluate(caller, "response", Map.of("service", new DataValue.TextValue("journal"),
                "client_request", new DataValue.TextValue("this-run:1"), "received",
                com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult.receive("received", frames.get(i))));
            assertEquals(new DataValue.BooleanValue(i == 0), result.field("matched"));
        }
    }
    @Test void callerTerminalShowsTheBusinessFailureRatherThanNullOrAnEarlierReadAsTheSavedResult() throws Exception {
        var caller = load("caller");
        var earlierRead = new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(43)));
        for (var error : List.of(new DataValue.RecordValue(Map.of("code", new DataValue.TextValue("readonly"))),
            new DataValue.RecordValue(Map.of("code", new DataValue.TextValue("missing_medium"))),
            new DataValue.ErrorValue("xbus_peer_paused", "XBus peer paused"))) {
            var reply = new DataValue.RecordValue(Map.of("ok", new DataValue.BooleanValue(false), "value", earlierRead, "error", error));
            var output = evaluate(caller, "result", Map.of("received",
                com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult.receive("received", reply)));
            assertEquals(reply, output.field("reply"), "Keep the original typed diagnostic and intermediate read for inspection");
            assertEquals(new DataValue.BooleanValue(false), output.field("success"));
            var roots = new DataValue.RecordValue(Map.of("output", output, "memory", new DataValue.RecordValue(Map.of())));
            var nodes = caller.terminal().orElseThrow().nodes();
            var errorNode = (com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition.Text)nodes.get("error");
            assertEquals(error instanceof DataValue.RecordValue record ? record.field("code") : error, errorNode.value().resolve(roots));
            var valueNode = (com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition.Text)nodes.get("value");
            assertEquals(DataValue.NullValue.INSTANCE, valueNode.value().resolve(roots), "43 was read before rejection, not successfully saved");
        }
        var reply = new DataValue.RecordValue(Map.of("ok", new DataValue.BooleanValue(true), "error", DataValue.NullValue.INSTANCE,
            "value", new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(42)))));
        var output = evaluate(caller, "result", Map.of("received",
            com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult.receive("received", reply)));
        assertEquals(new DataValue.IntegerValue(42), output.field("value"));
        assertEquals(DataValue.NullValue.INSTANCE, output.field("error"));
    }
    @Test void coordinatorReturnsPausedPeerFailureRatherThanPretendingToHaveSaved() throws Exception {
        var coordinator = load("coordinator");
        var paused = com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusResult.value(null, false, true);
        var checked = evaluate(coordinator, "sent_compute", Map.of("handoff", paused));
        var reply = (DataValue.RecordValue)evaluate(coordinator, "external_reply", Map.of("service", new DataValue.TextValue("journal"),
            "client_request", new DataValue.TextValue("this-run:1"), "success", new DataValue.BooleanValue(false),
            "read_result", DataValue.NullValue.INSTANCE, "error", DataValue.NullValue.INSTANCE,
            "compute_ok", checked.field("compute_ok"), "compute_error", checked.field("compute_error"))).field("reply");
        assertEquals(new DataValue.BooleanValue(false), reply.field("ok"));
        assertEquals(paused.field("error"), reply.field("error"));
        assertEquals(DataValue.NullValue.INSTANCE, reply.field("value"));
    }
    static DataValue.RecordValue evaluate(ProgramDocument document, String entry, Map<String, DataValue> inputs) {
        var machine = new ComputationMachine(document.archive().compile(COORDINATOR).select(entry));
        machine.begin(new DataValue.RecordValue(inputs));
        for (int n = 0; machine.report().status() == ComputationMachine.Status.RUNNING; n++) {
            assertTrue(n < 1000); machine.tick();
        }
        assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status(), () -> machine.report().diagnostic().toString());
        return machine.report().output().orElseThrow();
    }
}
