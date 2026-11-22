package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProgramDraftTest {
    @Test void standaloneDraftSnapshotPreservesImportedEventEntries() {
        var eventGraph = new ComputationGraph(Map.of(),
            Map.of("event", number(8)), Map.of("event_out", "event"));
        var archive = new ProgramArchive(new ProgramDefinition(
            new ComputationGraph(Map.of(), Map.of("root", number(1)), Map.of("root_out", "root")),
            Map.of("count", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(0))),
            List.of(), List.of(), Map.of("message", new ProgramDefinition.Entry(eventGraph,
                List.of(new ProgramDefinition.Write("count", "event_out")),
                List.of(new ProgramDefinition.Send("event_out", "event_out"))))), Map.of());

        var draft = new ProgramDraft(archive);
        String added = draft.add(number(2), 40, 50);
        draft.output("added", added);

        assertEquals(archive.program().entries(), draft.snapshot().program().entries());
    }
    @org.junit.jupiter.api.Test void invalidWriteReplacementLeavesExistingBindingIntact() {
        var draft=new ProgramDraft(); draft.setWrite("count","out");
        assertThrows(RuntimeException.class,()->draft.setWrite("count"," "));
        assertEquals(java.util.List.of(new ProgramDefinition.Write("count","out")),draft.writes());
    }
    static final ResourceLimits ROOM = new ResourceLimits(100, 100_000, 100);
    static ComputationGraph.Constant number(long n) { return new ComputationGraph.Constant(new DataValue.IntegerValue(n)); }
    static ComputationGraph.Binary add() { return new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "left", "right"); }

    @Test void namedCanvasConnectionsCompileAndExecuteWithoutRecipeSemantics() {
        var draft = new ProgramDraft();
        String a = draft.add(number(2), 10, 20), b = draft.add(number(3), 10, 80);
        String sum = draft.add(add(), 160, 20);
        draft.connect(sum, "left", a); draft.connect(sum, "right", b);
        String record = draft.add(new ComputationGraph.MakeRecord(Map.of("total", "value")), 300, 20);
        draft.connect(record, "total", sum); draft.output("result", record);
        var installed = draft.snapshot().compile(ROOM);
        assertEquals(DataType.Scalar.INTEGER, installed.root().nodeTypes().get(sum));
        var machine = new ComputationMachine(installed);
        machine.begin(new DataValue.RecordValue(Map.of())); machine.tick();
        var result = (DataValue.RecordValue) machine.report().output().orElseThrow().field("result");
        assertEquals(new DataValue.IntegerValue(5), result.field("total"));
    }

    @Test void missingPortsAndDeletedSourcesAreExplicitRatherThanDiscardedByCompiler() {
        var draft = new ProgramDraft();
        String a = draft.add(number(2), 0, 0), sum = draft.add(add(), 0, 0);
        draft.output("result", sum); draft.connect(sum, "left", a);
        assertThrows(IllegalStateException.class, draft::snapshot);
        draft.connect(sum, "right", a); draft.snapshot();
        draft.disconnect(sum, "right"); assertThrows(IllegalStateException.class, draft::snapshot);
        draft.connect(sum, "right", a); draft.remove(a);
        assertTrue(draft.node(sum).connections().isEmpty());
        assertThrows(IllegalStateException.class, draft::snapshot);
    }

    @Test void compilerStillRejectsCyclesTypesAndHardwareCapacity() {
        var draft = new ProgramDraft(); String sum = draft.add(add(), 0, 0);
        draft.connect(sum, "left", sum); draft.connect(sum, "right", sum); draft.output("out", sum);
        assertThrows(ComputationException.class, () -> draft.snapshot().compile(ROOM));
        String text = draft.add(new ComputationGraph.Constant(new DataValue.TextValue("not a number")), 0, 0);
        draft.connect(sum, "left", text); draft.connect(sum, "right", text);
        assertThrows(ComputationException.class, () -> draft.snapshot().compile(ROOM));
        draft.configure(text, number(4));
        assertThrows(ComputationException.class, () -> draft.snapshot().compile(new ResourceLimits(1, 1000, 1)));
    }

    @Test void editingLoadedArchivePreservesModulesMemoryAndOriginalSnapshot() {
        var key = new ModuleDefinition.Key("identity", 2);
        var module = new ModuleDefinition(key, new ComputationGraph(Map.of("x", DataType.Scalar.INTEGER),
            Map.of("x", new ComputationGraph.Input("x")), Map.of("value", "x")));
        var original = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),
            Map.of("n1", number(7), "call", new ComputationGraph.Call(key, Map.of("x", "n1"))), Map.of("out", "call")),
            Map.of("remembered", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(0))), List.of()), Map.of(key, module));
        var draft = new ProgramDraft(original);
        assertEquals(original, draft.snapshot());
        String next = draft.add(number(9), 0, 0); assertNotEquals("n1", next);
        draft.connect("call", "x", next); draft.moveBy(next, 10, 20);
        assertEquals(7, ((DataValue.IntegerValue)((ComputationGraph.Constant)original.program().graph().nodes().get("n1")).value()).value());
        assertEquals(original.modules(), draft.snapshot().modules());
        assertEquals(original.program().cells(), draft.snapshot().program().cells());
        assertThrows(IllegalArgumentException.class, () -> draft.moveBy(next, Double.NaN, 0));
    }

    @Test void iterationSourceCannotCollideWithCapturedPortNamedSource() {
        var loop = new ComputationGraph.Iterate(ComputationGraph.Iteration.MAP, "items", new ModuleDefinition.Key("m", 1),
            "item", Map.of("source", "capture"), "out");
        var ports = NodePorts.bindings(loop);
        assertEquals(Map.of("source", "items", "capture:source", "capture"), ports);
        assertEquals(loop, NodePorts.bind(loop, ports));
    }

    @Test void declarationsAndMemoryBindingsRemainExplicitWhenTheirDeclarationsAreRemoved() {
        var draft = new ProgramDraft();
        draft.putInput("amount", DataType.Scalar.INTEGER);
        draft.putCell("count", DataType.Scalar.INTEGER, new DataValue.IntegerValue(0));
        String input = draft.add(new ComputationGraph.Input("amount"), 0, 0);
        String memory = draft.add(new ComputationGraph.ReadMemory("count"), 0, 40);
        String sum = draft.add(add(), 100, 0);
        draft.connect(sum, "left", input); draft.connect(sum, "right", memory);
        draft.output("next", sum); draft.setWrite("count", "next");
        assertEquals(List.of(memory), draft.readers("count"));
        assertEquals(List.of("next"), draft.writers("count"));
        assertDoesNotThrow(() -> draft.snapshot().compile(ROOM));
        draft.removeInput("amount");
        assertThrows(ComputationException.class, () -> draft.snapshot().compile(ROOM));
        draft.putInput("amount", DataType.Scalar.INTEGER);
        draft.removeCell("count");
        assertEquals(List.of(new ProgramDefinition.Write("count", "next")), draft.writes());
        assertThrows(ComputationException.class, () -> draft.snapshot().compile(ROOM));
        draft.setWrite("count", null);
        assertTrue(draft.writes().isEmpty());
    }

    @Test void rejectsInvalidDeclarationsAndModuleDraftMemory() {
        var draft = new ProgramDraft();
        assertThrows(RuntimeException.class, () -> draft.putInput(" ", DataType.Scalar.INTEGER));
        assertThrows(NullPointerException.class, () -> draft.putInput("x", null));
        assertThrows(ComputationException.class,
            () -> draft.putCell("x", DataType.Scalar.INTEGER, new DataValue.TextValue("bad")));
    }

	@Test void draftSendEditsAreImmutableAndSurviveSnapshots() {
		var draft = new ProgramDraft();
		String endpoint = draft.add(new ComputationGraph.Constant(new DataValue.TextValue("factory:input")), 0, 0);
		String payload = draft.add(new ComputationGraph.Constant(new DataValue.RecordValue(Map.of("amount", new DataValue.IntegerValue(2)))), 20, 0);
		draft.output("endpoint", endpoint); draft.output("payload", payload);
		draft.addSend("endpoint", "payload");
		assertEquals(List.of(new ProgramDefinition.Send("endpoint", "payload")), draft.snapshot().program().sends());
		assertThrows(UnsupportedOperationException.class, () -> draft.sends().clear());
		draft.removeSend(0);
		assertTrue(draft.sends().isEmpty());
		assertThrows(IndexOutOfBoundsException.class, () -> draft.removeSend(0));
	}

	@Test void replacingSendPreservesItsIndexAndWorkspaceWireRoundTrip() {
		var workspace = new ProgramWorkspace(); var draft = workspace.root();
		String endpoint = draft.add(new ComputationGraph.Constant(new DataValue.TextValue("factory:input")), 0, 0);
		String otherEndpoint = draft.add(new ComputationGraph.Constant(new DataValue.TextValue("factory:other")), 20, 0);
		String payload = draft.add(new ComputationGraph.Constant(new DataValue.RecordValue(Map.of())), 40, 0);
		draft.output("endpoint", endpoint); draft.output("other_endpoint", otherEndpoint); draft.output("payload", payload);
		draft.addSend("endpoint", "payload"); draft.addSend("endpoint", "payload");
		draft.setSend(0, "other_endpoint", "payload");
		assertEquals(List.of(new ProgramDefinition.Send("other_endpoint", "payload"),
			new ProgramDefinition.Send("endpoint", "payload")), draft.sends());
		var limits = new ArchiveLimits(1_000, 100_000, 1_000_000);
		var restored = new ProgramWorkspace(ProgramDocumentWire.decode(ProgramDocumentWire.encode(workspace.snapshot(), limits), limits));
		assertEquals(draft.sends(), restored.root().sends());
	}

	@Test void setSendValidatesBeforeMutationAndPureSubgraphsRejectEffects() {
		var draft = new ProgramDraft();
		draft.addSend("endpoint", "payload");
		assertThrows(ComputationException.class, () -> draft.setSend(0, null, "payload"));
		assertEquals(List.of(new ProgramDefinition.Send("endpoint", "payload")), draft.sends());
		var workspace = new ProgramWorkspace();
		var module = workspace.graph(workspace.createModule("pure"));
		assertThrows(IllegalStateException.class, () -> module.setSend(0, "endpoint", "payload"));
	}

	@Test void typedSendEditsPreserveRpcFieldsAndLegacySetterPreservesMode() {
		var draft = new ProgramDraft();
		var request = ProgramDefinition.Send.request("endpoint", "payload", "answer", "reply");
		draft.addSend(request);
		draft.setSend(0, "other_endpoint", "other_payload");
		assertEquals(ProgramDefinition.Send.request("other_endpoint", "other_payload", "answer", "reply"), draft.sends().getFirst());
		var reply = ProgramDefinition.Send.reply("payload");
		draft.setSend(0, reply);
		assertEquals(reply, draft.sends().getFirst());
	}

	@Test void typedSendValidationOccursBeforeMutationAndSubgraphsRejectEveryEffect() {
		var draft = new ProgramDraft();
		draft.addSend(ProgramDefinition.Send.request("endpoint", "payload", "slot", "reply"));
		assertThrows(ComputationException.class, () -> draft.setSend(0,
			new ProgramDefinition.Send("endpoint", "payload", ProgramDefinition.Dispatch.REQUEST, "", "reply")));
		assertEquals(ProgramDefinition.Send.request("endpoint", "payload", "slot", "reply"), draft.sends().getFirst());
		var workspace = new ProgramWorkspace();
		var module = workspace.graph(workspace.createModule("pure"));
		assertThrows(IllegalStateException.class, () -> module.addSend(ProgramDefinition.Send.reply("payload")));
		assertThrows(IllegalStateException.class, () -> module.setSend(0, ProgramDefinition.Send.reply("payload")));
	}
}
