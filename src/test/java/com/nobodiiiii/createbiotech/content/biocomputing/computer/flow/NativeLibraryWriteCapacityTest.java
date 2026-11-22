package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryWriteDocuments;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocument;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentWire;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowDocumentBody;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramWorkspace;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryDriveCommand;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryMutation;
import java.util.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.*;
import org.junit.jupiter.api.Test;

/** Capacity checks concern complete independently installed images, not isolated procedures. */
class NativeLibraryWriteCapacityTest {
    private static final DataValue.RecordValue VERSION = record("medium", "12345678-1234-1234-1234-123456789abc", "entry", "reading", "revision", 7);
    private static final DataValue.RecordValue STAT = record("present", true, "kind", "DATA", "value", 43, "version", VERSION);

    @Test void replacementIsAnExplicitCoordinatorInputCopiedToPreparationRatherThanPreparerConfiguration() {
        assertEquals(DataType.Scalar.ANY, NativeLibraryWriteDocuments.build("a").flow().orElseThrow().inputs().get("replacement"));
        assertEquals(DataType.Scalar.ANY, NativeLibraryWriteDocuments.PREPARATION.fields().get("w"));
        assertFalse(NativeLibraryWriteDocuments.build("d").flow().orElseThrow().inputs().containsKey("replacement"));
    }

    @Test void nativePreparationOwnerBuildsFreshStrictCommandsFromCopiedStatVersion() {
        var limits = NativeLibraryOwnersTest.limits("cleric", 5);
        var target = record("x", 7, "y", 2, "z", -4);
        var query = record("r", record("o", "stat", "d", "reading", "e", "accepted", "l", false), "t", target, "s", 2, "v", null, "w", 17);
        var write = record("r", record("o", "write", "d", "ignored-row-data", "e", "accepted", "l", false), "t", target, "s", 3, "v", STAT, "w", 909);
        var callerDocument = document(Map.of("query", NativeLibraryWriteDocuments.PREPARATION, "write", NativeLibraryWriteDocuments.PREPARATION),
            record("query", query, "write", write), List.of(
                new FlowProgram.Write("query", "x1", "query"), new FlowProgram.Read("query_reply", "x1", "query_reply", PACKET),
                new FlowProgram.Write("write", "x1", "write"), new FlowProgram.Read("write_reply", "x1", "write_reply", PACKET)));
        var caller = NativeLibraryOwnersTest.install(callerDocument, limits, null);
        var preparer = NativeLibraryOwnersTest.install(NativeLibraryWriteDocuments.build("d"), limits, null);
        caller.setPaused(false, limits, true); preparer.setPaused(false, limits, true);
        for (int tick = 0; tick < 2500 && state(caller).status() != FlowRuntime.Status.COMPLETE; tick++) {
            caller.tick(limits, true);
            preparer.tick(limits, true, Map.of("x0", NativeLibraryStartupTest.status(preparer, "x0", limits, caller, "x1", limits)));
            caller.exchange("x1", limits, true, preparer, "x0", limits, true);
            healthy(caller); healthy(preparer);
        }
        assertEquals(FlowRuntime.Status.COMPLETE, state(caller).status());
        var first = LibraryDriveCommand.decode((DataValue.RecordValue) state(caller).locals().field("query_reply"));
        var second = LibraryDriveCommand.decode((DataValue.RecordValue) state(caller).locals().field("write_reply"));
        assertEquals(target, first.target()); assertEquals(target, second.target());
        assertEquals("stat", first.operation()); assertEquals(new DataValue.TextValue("reading"), first.data());
        assertEquals(2, first.sequence()); assertEquals(3, second.sequence());
        assertNotEquals(first.request(), second.request());
        var mutation = LibraryMutation.decode((DataValue.RecordValue) second.data());
        assertEquals("plant", mutation.volume()); assertEquals("reading", mutation.entry());
        assertEquals(LibraryMutation.Version.decode(VERSION), mutation.expected());
        assertEquals(new DataValue.IntegerValue(909), mutation.replacement().orElseThrow().value());
        assertEquals(Set.of("op", "volume", "entry", "expected", "kind", "value"), ((DataValue.RecordValue) second.data()).fields().keySet());
        assertTrue(state(preparer).work() > 0);
    }

    @Test void failedStatAckReturnsRawFailureWithoutMislabelingRetainedStatAsReadResult() {
        runCoordinator(true);
    }

    @Test void coordinatorRetainsStatAcrossAckBuildsWriteAndReturnsOnlyConfirmedReadback() {
        runCoordinator(false);
    }

    private static void runCoordinator(boolean failAck) {
        var limits = NativeLibraryOwnersTest.limits("cleric", 5);
        var aDocument = NativeLibraryWriteDocuments.build("a");
        var rows = (DataValue.ListValue) aDocument.flow().orElseThrow().initial().field("commands");
        var inputs = new LinkedHashMap<String, DataType>();
        var initial = new LinkedHashMap<String, DataValue>();
        var steps = new ArrayList<FlowProgram.Step>();
        DataValue.RecordValue failure = record("sequence", 3, "code", "stale_operation", "value", record("evidence", "keep-raw"));
        DataValue.RecordValue readback = record("kind", "DATA", "value", 44, "version", record("medium", "12345678-1234-1234-1234-123456789abc", "entry", "reading", "revision", 8));
        int sequence = 0;
        for (int i = 0; i < (failAck ? 10 : rows.values().size()); i++) {
            var row = (DataValue.RecordValue) rows.values().get(i);
            if (Set.of("seek", "select", "stat", "write", "read").contains(((DataValue.TextValue) row.field("o")).value())) sequence++;
            DataValue payload = i == 8 ? STAT : i == 11 ? record("version", readback.field("version")) : i == 14 ? readback : DataValue.NullValue.INSTANCE;
            DataValue.RecordValue reply = failAck && i == 9 ? failure : record("sequence", sequence, "code", row.field("e"), "value", payload);
            String name = "response_" + i;
            inputs.put(name, OUTCOME); initial.put(name, record("ok", true, "value", reply));
            steps.add(new FlowProgram.Read("command_" + i, "x0", "command_" + i, PACKET));
            steps.add(new FlowProgram.Write(name, "x0", name));
        }
        var a = NativeLibraryOwnersTest.install(aDocument, limits, null);
        var d = NativeLibraryOwnersTest.install(NativeLibraryWriteDocuments.build("d"), limits, null);
        var responder = NativeLibraryOwnersTest.install(document(inputs, new DataValue.RecordValue(initial), steps), limits, null);
        for (var owner : List.of(a, d, responder)) owner.setPaused(false, limits, true);
        for (int tick = 0; tick < 10000 && state(a).status() != FlowRuntime.Status.COMPLETE; tick++) {
            a.tick(limits, true); responder.tick(limits, true);
            d.tick(limits, true, Map.of("x0", NativeLibraryStartupTest.status(d, "x0", limits, a, "x1", limits)));
            a.exchange("x1", limits, true, d, "x0", limits, true);
            a.exchange("x0", limits, true, responder, "x0", limits, true);
            for (var owner : List.of(a, d, responder)) healthy(owner);
        }
        assertEquals(FlowRuntime.Status.COMPLETE, state(a).status());
        assertEquals(new DataValue.BooleanValue(!failAck), state(a).locals().field("success"));
        assertEquals(failAck ? failure : DataValue.NullValue.INSTANCE, state(a).locals().field("error"));
        assertEquals(failAck ? DataValue.NullValue.INSTANCE : readback, state(a).locals().field("read_result"));
        assertEquals(FlowRuntime.Status.COMPLETE, state(responder).status(), "No unexpected command retries");
        if (!failAck) {
            var command = LibraryDriveCommand.decode((DataValue.RecordValue) state(responder).locals().field("command_10"));
            assertEquals("write", command.operation());
            assertEquals(LibraryMutation.Version.decode(VERSION), LibraryMutation.decode((DataValue.RecordValue) command.data()).expected());
            assertEquals(new DataValue.IntegerValue(44), LibraryMutation.decode((DataValue.RecordValue) command.data()).replacement().orElseThrow().value());
        }
    }

    @Test void candidateOwnersRequireLevelFiveClericParserRatherThanPooledOrOrdinaryHardware() {
        for (String role : List.of("a", "d")) {
            var doc = NativeLibraryWriteDocuments.build(role);
            for (String profession : List.of("cleric", "mason", "armorer", "librarian", "farmer", "cartographer", "fletcher")) {
                for (int level = 1; level <= 5; level++) {
                    var limits = NativeLibraryOwnersTest.limits(profession, level);
                    boolean fits = level == 5 && profession.equals("cleric");
                    if (fits) assertTrue(NativeLibraryOwnersTest.install(doc, limits, null).loaded());
                    else {
                        var error = assertThrows(FlowPins.Unavailable.class, () -> NativeLibraryOwnersTest.install(doc, limits, null), role + "/" + profession + "/" + level);
                        assertEquals(!limits.isa1a(), error.getMessage().contains("ISA-1a"), error.getMessage());
                        assertEquals(!limits.isa1b(), error.getMessage().contains("ISA-1b"), error.getMessage());
                    }
                }
            }
        }
    }

    private static ProgramDocument document(Map<String, DataType> inputs, DataValue.RecordValue initial, List<FlowProgram.Step> steps) {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(inputs, steps).withAssembly(), initial)));
        return workspace.snapshot();
    }
    private static FlowRuntime.Checkpoint state(ComputerBlockHost owner) { return owner.flowSnapshot().orElseThrow().state(); }
    private static void healthy(ComputerBlockHost owner) {
        assertNotEquals(FlowRuntime.Status.FAILED, state(owner).status(), state(owner).diagnostic().toString());
        assertTrue(owner.loaded(), owner.diagnostic().toString());
    }
    @Test void fourExplicitOwnersFitTheirIndependentUnmodifiedResidentLimits() throws Exception {
        for (String role : List.of("b", "c", "a", "d")) {
            var limits = NativeLibraryOwnersTest.limits("cleric", 5);
            ProgramDocument document = role.equals("b") || role.equals("c")
                ? NativeLibraryOwnersTest.document(role) : NativeLibraryWriteDocuments.build(role);
            document.validate(limits.hardware());
            var archive = ProgramDocumentWire.encode(document, SerialFlowHostTest.ARCHIVE);
            assertEquals(document, ProgramDocumentWire.decode(archive, SerialFlowHostTest.ARCHIVE));
            var body = document.flow().orElseThrow();
            var image = body.program(document.archive()).compile(limits.hardware()).assembly().orElseThrow();
            assertTrue(image.codeWords() <= limits.hardware().programUnits(), role + " exceeds its own code capacity");
            var owner = NativeLibraryOwnersTest.install(document, limits,
                role.equals("b") ? NativeLibraryOwnersTest.device(limits) : null);
            assertTrue(owner.loaded(), role + ": " + owner.diagnostic());
            System.out.println("write candidate " + role + " words=" + image.codeWords()
                + " constants=" + image.constantUnits() + " limits=" + limits.hardware());
            if (role.equals("a") || role.equals("d")) {
                var path = java.nio.file.Path.of(".superpowers/cache/assembly-library-write/generated/library_write_" + role + ".bcp");
                java.nio.file.Files.createDirectories(path.getParent());
                java.nio.file.Files.writeString(path, Base64.getEncoder().encodeToString(archive) + "\n");
            }
        }
    }
}
