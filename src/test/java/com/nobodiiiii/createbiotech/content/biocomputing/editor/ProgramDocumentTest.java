package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProgramDocumentTest {
    private static final ArchiveLimits LIMITS = new ArchiveLimits(10_000, 100_000, 1_000_000);

    @Test void entryDraftsShareDeclarationsButKeepEffectsAndLayoutsIndependent() {
        var workspace = new ProgramWorkspace();
        String rootNode = workspace.root().add(new ComputationGraph.Constant(new DataValue.IntegerValue(1)), 11, 12);
        workspace.root().output("root_out", rootNode);
        var message = workspace.createEntry("message");
        var timer = workspace.createEntry("timer");
        String messageNode = message.add(new ComputationGraph.Constant(new DataValue.IntegerValue(2)), 21, 22);
        message.output("message_out", messageNode); message.setWrite("count", "message_out");
        message.addSend("message_out", "message_out");
        String timerNode = timer.add(new ComputationGraph.Constant(new DataValue.IntegerValue(3)), 31, 32);
        timer.output("timer_out", timerNode); timer.setWrite("count", "timer_out");

        workspace.root().putCell("count", DataType.Scalar.INTEGER, new DataValue.IntegerValue(0));
        assertEquals(workspace.root().cells(), timer.cells());
        message.putCell("shared", DataType.Scalar.TEXT, new DataValue.TextValue("yes"));
        assertTrue(workspace.root().cells().containsKey("shared"));
        assertEquals(List.of(new ProgramDefinition.Send("message_out", "message_out")), message.sends());
        assertTrue(timer.sends().isEmpty()); assertTrue(workspace.root().writes().isEmpty());

        var document = workspace.snapshot();
        assertEquals(List.of("message", "timer"), workspace.entryNames());
        assertEquals(new ProgramDocument.Position(21, 22),
            document.positions().get(ProgramDocument.entryGraphId("message")).get(messageNode));
        var reopened = new ProgramWorkspace(document);
        assertEquals(message.outputs(), reopened.entry("message").outputs());
        assertEquals(message.writes(), reopened.entry("message").writes());
        assertEquals(message.sends(), reopened.entry("message").sends());
        assertEquals(timer.outputs(), reopened.entry("timer").outputs());
    }

    @Test void documentRejectsAmbiguousOrUnknownEntryLayouts() {
        var workspace = new ProgramWorkspace();
        String root = workspace.root().add(new ComputationGraph.Constant(new DataValue.IntegerValue(1)), 0, 0);
        workspace.root().output("out", root);
        var event = workspace.createEntry("module:1:x:1");
        String node = event.add(new ComputationGraph.Constant(new DataValue.IntegerValue(2)), 0, 0);
        event.output("out", node);
        var document = workspace.snapshot();
        assertNotEquals(ProgramDocument.entryGraphId("module:1:x:1"),
            ProgramDocument.graphId(new ModuleDefinition.Key("x", 1)));
        assertThrows(IllegalArgumentException.class, () -> new ProgramDocument(document.archive(),
            Map.of("entry:missing", Map.of())));
    }

    @Test void moduleReachabilityValidationStartsFromEveryEventEntry() {
        var workspace = new ProgramWorkspace();
        String root = workspace.root().add(new ComputationGraph.Constant(new DataValue.IntegerValue(1)), 0, 0);
        workspace.root().output("out", root);
        var moduleKey = workspace.createModule("event_only");
        var event = workspace.createEntry("message");
        String call = event.add(new ComputationGraph.Call(moduleKey, Map.of()), 0, 0);
        event.output("out", call);
        assertThrows(IllegalStateException.class, workspace::snapshot);
    }

    @Test void workspaceKeepsRootAndModuleLayoutsAndPreservesLoadedRevision() {
        var workspace = new ProgramWorkspace();
        String root = workspace.root().add(new ComputationGraph.Constant(new DataValue.IntegerValue(4)), 11, 12);
        workspace.root().output("out", root);
        var key = workspace.createModule("double");
        var module = workspace.graph(key);
        module.putInput("x", DataType.Scalar.INTEGER);
        String x = module.add(new ComputationGraph.Input("x"), 21, 22);
        String sum = module.add(new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "left", "right"), 31, 32);
        module.connect(sum, "left", x); module.connect(sum, "right", x); module.output("value", sum);
        var document = workspace.snapshot();
        assertEquals(new ProgramDocument.Position(11, 12), document.positions().get(ProgramDocument.graphId(null)).get(root));
        assertEquals(new ProgramDocument.Position(31, 32), document.positions().get(ProgramDocument.graphId(key)).get(sum));
        assertEquals(List.of(key), new ProgramWorkspace(document).keys());
        assertThrows(IllegalArgumentException.class, () -> workspace.createModule("double"));
    }

    @Test void workspacesAndInstalledArchivesRemainIsolated() {
        var original = executable(1);
        var first = new ProgramWorkspace(ProgramDocument.of(original));
        var second = new ProgramWorkspace(ProgramDocument.of(original));
        first.root().configure("n", new ComputationGraph.Constant(new DataValue.IntegerValue(9)));
        assertEquals(original, second.snapshot().archive());
        assertEquals(new DataValue.IntegerValue(1), ((ComputationGraph.Constant) original.program().graph().nodes().get("n")).value());
    }

    @Test void documentCodecAndWireRoundTripLayoutsWithoutEmbeddingThemInProgram() {
        var key = new ModuleDefinition.Key("a:1", 7);
        var archive = new ProgramArchive(executable(2).program(), Map.of(key,
            new ModuleDefinition(key, new ComputationGraph(Map.of(), Map.of("m", new ComputationGraph.Constant(new DataValue.IntegerValue(3))), Map.of("out", "m")))));
        var positions = Map.of(ProgramDocument.graphId(null), Map.of("n", new ProgramDocument.Position(-1.25, 8.5)),
            ProgramDocument.graphId(key), Map.of("m", new ProgramDocument.Position(4, 5)));
        var document = new ProgramDocument(archive, positions);
        assertEquals(document, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        assertEquals(document, ProgramDocumentWire.decode(ProgramDocumentWire.encode(document, LIMITS), LIMITS));
        assertNotEquals(ProgramDocument.graphId(key), ProgramDocument.graphId(new ModuleDefinition.Key("a", 17)));
    }

    @Test void rejectsBadCoordinatesMalformedSchemaAndWireBounds() {
        assertThrows(IllegalArgumentException.class, () -> new ProgramDocument.Position(Double.NaN, 0));
        var encoded = ProgramDocumentCodec.encode(ProgramDocument.of(executable(1)));
        var fields = new LinkedHashMap<>(encoded.fields());
        fields.put("program", new DataValue.TextValue("forged"));
        assertThrows(ArchiveException.class, () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(fields)));
        assertThrows(IllegalArgumentException.class, () -> ProgramDocumentWire.decode(new byte[ProgramEditorWire.MAX_BYTES + 1], LIMITS));
    }

    private static ProgramArchive executable(long value) {
        return new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),
            Map.of("n", new ComputationGraph.Constant(new DataValue.IntegerValue(value))), Map.of("out", "n")), Map.of(), List.of()), Map.of());
    }

    @Test void editingInternalGraphChangesOnlyNewInstallationAndRetainsOldModuleRevision() {
        var workspace=new ProgramWorkspace();var key=workspace.createModule("local");
        var child=workspace.graph(key);String value=child.add(new ComputationGraph.Constant(new DataValue.IntegerValue(1)),20,20);child.output("answer",value);
        String call=workspace.root().add(new ComputationGraph.Call(key,Map.of()),30,30);workspace.root().output("result",call);
        var saved=workspace.snapshot();var isolated=new ProgramWorkspace(saved);
        var installed=saved.archive().compile(ProgramDraftTest.ROOM);
        child.configure(value,new ComputationGraph.Constant(new DataValue.IntegerValue(9)));
        assertEquals(saved,isolated.snapshot());assertEquals(List.of(key),workspace.keys());
        var before=new ComputationMachine(installed);var after=new ComputationMachine(workspace.snapshot().archive().compile(ProgramDraftTest.ROOM));
        before.begin(new DataValue.RecordValue(Map.of()));after.begin(new DataValue.RecordValue(Map.of()));before.tick();after.tick();
        assertEquals(new DataValue.IntegerValue(1),((DataValue.RecordValue)before.report().output().orElseThrow().field("result")).field("answer"));
        assertEquals(new DataValue.IntegerValue(9),((DataValue.RecordValue)after.report().output().orElseThrow().field("result")).field("answer"));
    }

	@Test void workspaceNavigationPreservesRootSendsAndModulesCannotAddThem() {
		var workspace = new ProgramWorkspace();
		String endpoint = workspace.root().add(new ComputationGraph.Constant(new DataValue.TextValue("factory:input")), 0, 0);
		String payload = workspace.root().add(new ComputationGraph.Constant(new DataValue.RecordValue(Map.of())), 20, 0);
		workspace.root().output("endpoint", endpoint); workspace.root().output("payload", payload);
		workspace.root().addSend("endpoint", "payload");
		var key = workspace.createModule("identity");
		var module = workspace.graph(key);
		String value = module.add(new ComputationGraph.Constant(new DataValue.IntegerValue(1)), 0, 0);
		module.output("value", value);
		assertThrows(IllegalStateException.class, () -> module.addSend("endpoint", "payload"));
		var reopened = new ProgramWorkspace(workspace.snapshot());
		assertEquals(List.of(new ProgramDefinition.Send("endpoint", "payload")), reopened.root().sends());
		assertTrue(reopened.graph(key).sends().isEmpty());
		assertEquals(workspace.snapshot(), reopened.snapshot());
	}
}
