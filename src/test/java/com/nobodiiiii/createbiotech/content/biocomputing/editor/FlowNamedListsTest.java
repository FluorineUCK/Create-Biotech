package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlowNamedListsTest {
    @Test void specialPositionKeepsTheNumericShadowAndFollowsTheNewListTarget() {
        var type = new DataType.ListType(DataType.Scalar.INTEGER);
        var block = FlowNamedLists.scopedPrototype(ListOperation.REMOVE, "edit", "a", type, true, Set.of());
        var graph = block.graph();
        var target = FlowNamedLists.target(block.step(), graph, type).orElseThrow();
        String field = target.indexNode();
        var number = new DataValue.IntegerValue(8);
        var bindings = FlowInlineValues.bindings(block.step());
        var withNumber = InlineGraphEdits.replace(graph, bindings, field, new InlineGraphEdits.Literal(number));
        var chosen = ScratchListPositions.choose(withNumber.graph(), withNumber.bindings(), Map.of(), field, ComputationGraph.ListPosition.Choice.RANDOM);
        assertEquals(number, chosen.shadows().get(field));
        var changed = FlowNamedLists.chooseScope(block.step(), chosen.graph(), target, "b", type, true, Set.of(),
            chosen.shadows(), new ProgramDocument.Position(0, 0));
        var socket = ScratchListPositions.sockets(changed.graph()).get(field);
        assertEquals(new ComputationGraph.ReadMemory("b"), changed.graph().nodes().get(socket.source()));
        assertInstanceOf(ComputationGraph.ListPosition.class, changed.graph().nodes().get(field));
        var wrapped = ScratchListPositions.placeReporter(changed.graph(), FlowInlineValues.bindings(changed.step()), chosen.shadows(),
            field, new InlineGraphEdits.Operator(ScalarOperation.ADD_INTEGER));
        var addition = assertInstanceOf(ComputationGraph.Scalar.class, wrapped.graph().nodes().get(field));
        assertEquals(new ComputationGraph.Constant(number), wrapped.graph().nodes().get(addition.arguments().get("left")));
        assertTrue(wrapped.graph().nodes().values().stream().noneMatch(ComputationGraph.ListPosition.class::isInstance));
        var carried = new ScratchValueLayout.Reporter(new ProgramDocument.Position(0, 0),
            new ComputationGraph(Map.of("n", DataType.Scalar.INTEGER), Map.of("v", new ComputationGraph.Input("n")), Map.of("value", "v")),
            Map.of("n", "n"), Map.of());
        var attached = FlowReporterEdits.attach(changed.graph(), FlowInlineValues.bindings(changed.step()), chosen.shadows(), field, carried);
        var restored = FlowReporterEdits.restore(attached.graph(), attached.bindings(), attached.shadows(), field, new DataValue.IntegerValue(1));
        assertEquals(changed.graph().nodes().get(field), restored.graph().nodes().get(field));
        var numeric = ScratchListPositions.choose(restored.graph(), restored.bindings(), restored.shadows(), field, null);
        assertEquals(new ComputationGraph.Constant(number), numeric.graph().nodes().get(field));
    }
    @Test void clearUsesTheSameTypedListSlotOnEitherScope() {
        var type = new DataType.ListType(DataType.Scalar.INTEGER);
        var initial = new DataValue.ListValue(List.of(new DataValue.IntegerValue(19)));
        for (boolean shared : List.of(false, true)) {
            var draft = new FlowDraft(FlowDocumentBody.emptySoftwareAssembly());
            draft.declareList("items", type.element());
            draft.replaceInitial("items", initial);
            var workspace = new ProgramWorkspace();
            workspace.root().putCell("items", type, initial);
            var clear = FlowNamedLists.scopedPrototype(ListOperation.SLICE, draft.nextId(), "items", type, shared,
                draft.snapshot().inputs().keySet());
            var target = FlowNamedLists.target(clear.step(), clear.graph(), type).orElseThrow();
            assertEquals(shared, target.shared());
            assertNull(target.itemNode());
            assertNull(target.indexNode());
            draft.place(List.of(clear.step()), new FlowDraft.Socket(null, 0), new ProgramDocument.Position(0, 0),
                Map.of(clear.step().sourceId(), clear.graph()));
            workspace.setFlow(Optional.of(draft.snapshot()));
            var loaded = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot()));
            var body = loaded.flow().orElseThrow();
            var limits = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1024, 64_000, 1),
                new ArchiveLimits(100_000, 1_000_000, 1_000_000), MailboxLimits.DEFAULT, HardwarePins.DEFAULT, SerialHardware.NONE, 3));
            var owner = new ComputerBlockHost(() -> {});
            owner.installFlow(body.program(loaded.archive()), body.initial(), limits, true);
            for (int i = 0; i < 24; i++) owner.step(limits, true);
            var saved = owner.flowSnapshot().orElseThrow();
            assertEquals(FlowRuntime.Status.COMPLETE, saved.state().status(), saved.state().diagnostic().toString());
            var empty = new DataValue.ListValue(List.of());
            assertEquals(shared ? initial : empty, saved.state().locals().field("items"));
            assertEquals(shared ? empty : initial, saved.memory().cells().field("items"));
        }
    }
    @Test void sameNamedPrivateAndPublicListsStaySeparateAcrossScopeChangeUndoAndInstall() {
        var draft = new FlowDraft(FlowDocumentBody.emptySoftwareAssembly());
        var type = new DataType.ListType(DataType.Scalar.INTEGER);
        draft.declareList("items", type.element());
        var workspace = new ProgramWorkspace();
        workspace.root().putCell("items", type, new DataValue.ListValue(List.of(new DataValue.IntegerValue(5))));
        var block = FlowNamedLists.prototype(ListOperation.INSERT, draft.nextId(), "items", type, draft.snapshot().inputs().keySet());
        var target = FlowNamedLists.target(block.step(), block.graph()).orElseThrow();
        var value = InlineGraphEdits.replace(block.graph(), FlowInlineValues.bindings(block.step()), target.itemNode(),
            new InlineGraphEdits.Literal(new DataValue.IntegerValue(7)));
        draft.place(List.of(block.step()), new FlowDraft.Socket(null, 0), new ProgramDocument.Position(0, 0),
            Map.of(block.step().sourceId(), value.graph()));
        var shared = FlowNamedLists.chooseScope(block.step(), value.graph(), target, "items", type, true,
            draft.snapshot().inputs().keySet(), Map.of(), new ProgramDocument.Position(0, 0));
        assertTrue(shared.parked().isEmpty(), "Same-type target selection must not detach the item");
        draft.editInline(shared.step(), shared.graph(), draft.snapshot().canvas().values());
        draft.undo();
        assertInstanceOf(FlowProgram.Evaluate.class, draft.find(block.step().sourceId()).orElseThrow());
        draft.redo();
        workspace.setFlow(Optional.of(draft.snapshot()));
        var loaded = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot()));
        assertEquals(workspace.snapshot(), loaded);
        var body = loaded.flow().orElseThrow();
        var limits = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1024, 64_000, 1),
            new ArchiveLimits(100_000, 1_000_000, 1_000_000), MailboxLimits.DEFAULT, HardwarePins.DEFAULT, SerialHardware.NONE, 3));
        var owner = new ComputerBlockHost(() -> {});
        owner.installFlow(body.program(loaded.archive()), body.initial(), limits, true);
        for (int i = 0; i < 30; i++) owner.step(limits, true);
        var snapshot = owner.flowSnapshot().orElseThrow();
        assertEquals(FlowRuntime.Status.COMPLETE, snapshot.state().status(), snapshot.state().diagnostic().toString());
        assertEquals(new DataValue.ListValue(List.of()), snapshot.state().locals().field("items"));
        assertEquals(new DataValue.ListValue(List.of(new DataValue.IntegerValue(7), new DataValue.IntegerValue(5))),
            snapshot.memory().cells().field("items"));
        var result = ((FlowProgram.Shared) shared.step()).listResult().orElseThrow();
        assertEquals(new DataValue.TextValue("committed"),
            ((DataValue.RecordValue)snapshot.state().locals().field(result)).field("code"));
    }
    @Test void changingElementTypeParksTheEditedValueInsteadOfDroppingIt() {
        var number = new DataType.ListType(DataType.Scalar.INTEGER);
        var text = new DataType.ListType(DataType.Scalar.TEXT);
        var block = FlowNamedLists.append("add", "numbers", number, Set.of("numbers", "words"));
        var old = FlowNamedLists.target(block.step(), block.graph()).orElseThrow();
        var edit = InlineGraphEdits.replace(block.graph(), block.step().bindings().orElseThrow(), old.itemNode(),
            new InlineGraphEdits.Literal(new DataValue.IntegerValue(27)));
        var switched = FlowNamedLists.choose(block.step(), edit.graph(), old, "words", text, Map.of(),
            new ProgramDocument.Position(12, 34));
        var changed = switched.block();
        var selected = FlowNamedLists.target(changed.step(), changed.graph()).orElseThrow();
        assertEquals("words", selected.local());
        assertEquals(text, selected.type());
        assertEquals(new ComputationGraph.Constant(new DataValue.TextValue("")), changed.graph().nodes().get(selected.itemNode()));
        var parked = switched.parked().orElseThrow().graph();
        assertEquals(new ComputationGraph.Constant(new DataValue.IntegerValue(27)), parked.nodes().get(parked.outputs().get("value")));
    }

    @Test void createsTypedEmptyListsAndAddsToTheExplicitTargetAfterUndoAndReopen() {
        var draft = new FlowDraft(FlowDocumentBody.emptySoftwareAssembly());
        var element = DataType.Scalar.INTEGER;
        var type = new DataType.ListType(element);
        draft.declareList("pending", element);
        assertThrows(IllegalArgumentException.class, () -> draft.declareList("pending", DataType.Scalar.TEXT));
        draft.declareList("done", element);
        assertEquals(type, draft.snapshot().inputs().get("done"));
        draft.undo();
        assertFalse(draft.snapshot().inputs().containsKey("done"));
        draft.redo();

        var block = FlowNamedLists.append(draft.nextId(), "pending", type, draft.snapshot().inputs().keySet());
        var target = FlowNamedLists.target(block.step(), block.graph()).orElseThrow();
        var retargeted = FlowNamedLists.retarget(block.step(), target, "done");
        draft.place(List.of(retargeted), new FlowDraft.Socket(null, 0), new ProgramDocument.Position(0, 0),
            Map.of(retargeted.sourceId(), block.graph()));
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(draft.snapshot()));
        var loaded = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot()));
        assertEquals(workspace.snapshot(), loaded);
        var body = loaded.flow().orElseThrow();
        var limits = new ResourceLimits(512, 64_000, 1);
        var run = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), body.program(loaded.archive()), body.initial(), limits);
        run.load();
        for (int i = 0; i < 40 && run.checkpoint().status() == FlowRuntime.Status.READY; i++) run.advance(1);
        var state = run.checkpoint();
        assertEquals(FlowRuntime.Status.COMPLETE, state.status(), state.diagnostic().toString());
        assertEquals(new DataValue.ListValue(List.of()), state.locals().field("pending"));
        assertEquals(new DataValue.ListValue(List.of(new DataValue.IntegerValue(0))), state.locals().field("done"));
    }
}
