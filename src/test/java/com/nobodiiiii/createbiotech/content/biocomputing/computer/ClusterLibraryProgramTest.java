package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.RequestReplyFixtures.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.ClusterLibraryPrograms.obj;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.gametest.ClusterLibraryPrograms;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ClusterLibraryProgramTest {
    @Test void exportReferenceProgramsForPlayerVerification() throws Exception {
        var directory = Path.of("build", "generated", "biocomputing-examples");
        Files.createDirectories(directory);
        for (var entry : Map.of("cluster_library_caller", ClusterLibraryPrograms.caller(),
                "cluster_library_worker", ClusterLibraryPrograms.worker()).entrySet()) {
            var workspace = new ProgramWorkspace(ProgramDocument.of(entry.getValue()));
            var document = workspace.snapshot();
            var bytes = ProgramDocumentWire.encode(document, EventEntryFixtures.ARCHIVE);
            assertEquals(document, ProgramDocumentWire.decode(bytes, EventEntryFixtures.ARCHIVE));
            document.archive().compile(new ResourceLimits(32, 32768, 4));
            var shipped = ProgramDocumentWire.decode(Base64.getDecoder().decode(Files.readString(
                Path.of("docs", "examples", "biocomputing", entry.getKey() + ".bcp")).strip()), EventEntryFixtures.ARCHIVE);
            assertEquals(entry.getValue(), shipped.archive(), "The player package must match the tested public program");
            assertEquals(shipped, new ProgramWorkspace(shipped).snapshot(), "Import keeps guards, fixed dependencies and layout");
            Files.writeString(directory.resolve(entry.getKey() + ".bcp"), Base64.getEncoder().encodeToString(bytes));
        }
    }
    @Test void statErrorIsReturnedWithoutReadingNullFieldsOrIssuingPut() {
        var archive = ClusterLibraryPrograms.worker();
        var compiled = archive.compile(new ResourceLimits(32, 32768, 4));
        assertEquals(32, compiled.usage().programUnits());
        var caller = new ComputerRuntime(UUID.randomUUID(), ClusterLibraryPrograms.caller(), HARDWARE);
        caller.begin(obj("value", new DataValue.IntegerValue(7))); finish(caller);
        var worker = new ComputerRuntime(UUID.randomUUID(), archive, HARDWARE);
        worker.receive(request(caller.id(), caller.outbound().orElseThrow(), "return")); worker.step(); finish(worker);
        var child = worker.outbound().orElseThrow(); acknowledge(worker);
        assertEquals(obj("op", "stat", "volume", "日志", "entry", "joint_reading"), child.payload());
        worker.receive(new MessageEnvelope(UUID.randomUUID(), 0, UUID.randomUUID(), 0,
            obj("code", "missing_volume", "data", DataValue.NullValue.INSTANCE),
            new MessageProtocol.Reply(((OutboundProtocol.Request)child.protocol()).correlation())));
        worker.step(); finish(worker);
        var response = worker.outbound().orElseThrow();
        assertInstanceOf(OutboundProtocol.Reply.class, response.protocol());
        assertEquals("return", response.endpoint());
        assertEquals(obj("code", "missing_volume", "data", DataValue.NullValue.INSTANCE,
            "value", obj("value", new DataValue.IntegerValue(8))), response.payload());
        assertTrue(worker.snapshot().rpc().pending().isEmpty());
    }
}
