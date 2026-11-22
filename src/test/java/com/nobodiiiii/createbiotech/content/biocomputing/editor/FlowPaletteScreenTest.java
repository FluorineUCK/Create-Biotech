package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import org.junit.jupiter.api.Test;

class FlowPaletteScreenTest {
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @org.junit.jupiter.api.BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @org.junit.jupiter.api.BeforeEach void client() throws Exception { environment.client(); }
    @org.junit.jupiter.api.AfterEach void restoreClient() throws Exception { environment.restore(); }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"repeat", "atomic", "if", "if_else"})
    void dragFromCategoryCreatesAnAtomicControlGroupWithoutOpeningAFormOrInstalling(String kind) throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var screen = FlowCallScreenTest.editor(workspace, 640);
        click(screen, kind.equals("atomic") ? "category_storage" : "category_control");
        if (kind.equals("atomic")) click(screen, "advanced_off");
        var repeat = button(screen, "add_" + kind);
        double fromX = repeat.getX() + 10, fromY = repeat.getY() + 10;
        var canvas = get(screen, "canvas");
        double x = (int)get(canvas, "x") + 24, y = (int)get(canvas, "y") + 50;
        var before = workspace.snapshot();
        assertTrue(screen.mouseClicked(fromX, fromY, 0));
        assertFalse((boolean)get(screen, "parameters"));
        screen.mouseDragged(x, y, 0, x - fromX, y - fromY);
        assertEquals(before, workspace.snapshot(), "Taking a prototype only previews a new stack");
        assertTrue(screen.mouseReleased(x, y, 0));
        var created = workspace.snapshot(); var steps = created.flow().orElseThrow().steps();
        assertEquals(kind.equals("if_else") ? 3 : 2, steps.size());
        if (kind.equals("if") || kind.equals("if_else")) {
            assertEquals(new FlowInput.Literal(new DataValue.BooleanValue(false)),
                assertInstanceOf(FlowProgram.If.class, steps.getFirst()).condition());
            assertInstanceOf(FlowProgram.EndIf.class, steps.getLast());
            assertEquals(kind.equals("if_else"), FlowBranches.compile(steps).hasOtherwise(0));
        } else if (kind.equals("atomic")) {
            assertInstanceOf(FlowProgram.Atomic.class, steps.getFirst()); assertInstanceOf(FlowProgram.EndAtomic.class, steps.getLast());
        } else {
            assertEquals(new FlowInput.Literal(new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.IntegerValue(10)),
                assertInstanceOf(FlowProgram.Repeat.class, steps.getFirst()).count());
            assertInstanceOf(FlowProgram.EndRepeat.class, steps.getLast());
        }
        assertTrue(created.flow().orElseThrow().canvas().detached().isEmpty());
        assertFalse((boolean)get(screen, "parameters")); assertFalse((boolean)get(screen, "waiting"));
        assertEquals(created, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(created)));
        click(screen, "undo"); assertEquals(before, workspace.snapshot());
        click(screen, "redo"); assertEquals(created, workspace.snapshot());
    }
    private static java.util.concurrent.atomic.AtomicReference<net.minecraft.client.gui.screens.Screen> edgeNavigation(ComputerFlowScreen editor) throws Exception {
        var result = new java.util.concurrent.atomic.AtomicReference<net.minecraft.client.gui.screens.Screen>();
        set(editor, "navigator", (java.util.function.Consumer<net.minecraft.client.gui.screens.Screen>) result::set);
        return result;
    }

    private static ComputerFlowScreen openedDefinition(java.util.concurrent.atomic.AtomicReference<net.minecraft.client.gui.screens.Screen> opened) throws Exception {
        var definition = assertInstanceOf(ComputerFlowScreen.class, opened.get());
        initialize(definition, 640);
        return definition;
    }

    @Test void extensionHatAttachesAScriptAndFiresOnlyOnFalseToTrueIncludingAfterRestore() throws Exception {
        var draft = new FlowDraft(FlowDocumentBody.emptyAssembly());
        draft.setExtension("create_biotech:signals", 1L);
        var zero = new DataValue.IntegerValue(0);
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of("hits", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, zero)), List.of(), List.of()), Map.of());
        var workspace = new ProgramWorkspace(new ProgramDocument(archive, Map.of(), Optional.empty(), Optional.of(draft.snapshot())));
        var screen = FlowCallScreenTest.editor(workspace, 640);
        set(screen, "height", 360); screen.init();
        var canvas = (FlowBlockCanvas)get(screen, "canvas");
        search(screen).setValue("when_signal");
        var hat = button(screen, "extension_when_signal");
        int x = (int)get(canvas,"x") + 30, y = (int)get(canvas,"y") + 78;
        screen.mouseClicked(hat.getX()+8, hat.getY()+8, 0);
        screen.mouseDragged(x, y, 0, 40, 0); screen.mouseReleased(x,y,0);
        var flow = workspace.flow().orElseThrow();
        assertTrue(flow.steps().isEmpty(), "An event must never snap to the main stack");
        var stack = flow.canvas().detached().stream().filter(s -> s.steps().stream().anyMatch(FlowProgram.Watch.class::isInstance))
            .findFirst().orElseThrow();
        var watch = (FlowProgram.Watch)stack.steps().get(1);
        var visibleHat = canvas.blocks().stream().filter(block -> block.step().equals(watch)).findFirst().orElseThrow();
        assertTrue(visibleHat.width() < FlowBlockCanvas.WIDTH, "A short extension hat must not reserve a full statement width");
        var tooltip = screen.typeTooltip((int)(int)get(canvas,"x") + (int)visibleHat.x() + 8,
            (int)(int)get(canvas,"y") + (int)visibleHat.y() + 18, false);
        assertTrue(tooltip.getString().contains(net.minecraft.network.chat.Component.translatable(
            "create_biotech.biocomputing.flow_editor.extension_when_signal_help").getString()));
        assertTrue(canvas.inlineSlots().stream().filter(slot -> slot.call().equals(watch.sourceId())).allMatch(slot -> slot.label().equals("1")));
        assertEquals(1, canvas.inlineSlots().stream().filter(slot -> slot.call().equals(watch.sourceId())).count());
        search(screen).setValue("public_add");
        dragToJoint(screen, canvas, button(screen, "add_public_add"), canvas.joints().stream()
            .filter(j -> stack.steps().getFirst().sourceId().equals(j.socket().stack()) && j.socket().index() == 2).findFirst().orElseThrow());
        var document = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot()));
        var body = document.flow().orElseThrow();
        var limits = new ComputerHostLimits(new ResourceLimits(512, 65_536, 16), SubgraphWorkspaceTest.LIMITS,
            MailboxLimits.DEFAULT, HardwarePins.DEFAULT, SerialHardware.NONE, 3);
        document.validate(limits.hardware(), limits.pins());
        var original = body.inline().get(watch.sourceId());
        var predicate = original.nodes().entrySet().stream().filter(e -> e.getValue() instanceof ComputationGraph.ProcedureValue)
            .findFirst().orElseThrow().getKey();
        var forged = new LinkedHashMap<>(body.inline());
        forged.put(watch.sourceId(), new ComputationGraph(Map.of(), original.nodes(), Map.of("value", predicate)));
        var malformed = new ProgramDocument(document.archive(), document.positions(), document.terminal(),
            Optional.of(body.edited(body.inputs(), body.steps(), body.initial(), body.canvas(), forged)));
        assertThrows(IllegalArgumentException.class, malformed::validateExtensions, "An event predicate is not an ordinary reporter");
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(body.program(document.archive()), new DataValue.RecordValue(Map.of()), limits, true);
        host.setPaused(false, limits, true);
        for (long tick = 0; tick < 20; tick++) host.tick(tick, limits, true, Map.of("p0",15));
        assertEquals(zero, host.flowSnapshot().orElseThrow().memory().cells().field("hits"), "Initial high only arms");
        for (long tick = 20; tick < 40; tick++) host.tick(tick, limits, true, Map.of("p0",0));
        var restored = new ComputerBlockHost(() -> {});
        restored.read(host.write());
        for (long tick = 40; tick < 80; tick++) restored.tick(tick, limits, true, Map.of("p0",15));
        var firstHit = new DataValue.IntegerValue(1);
        assertEquals(firstHit, restored.flowSnapshot().orElseThrow().memory().cells().field("hits"));

        for (long tick = 80; tick < 100; tick++) restored.tick(tick, limits, true, Map.of());
        var afterOutage = new ComputerBlockHost(() -> {});
        afterOutage.read(restored.write());
        for (long tick = 100; tick < 120; tick++) afterOutage.tick(tick, limits, true, Map.of("p0",15));
        assertEquals(firstHit, afterOutage.flowSnapshot().orElseThrow().memory().cells().field("hits"),
            "An unavailable sample and a restart must not manufacture a second rising edge");
        for (long tick = 120; tick < 140; tick++) afterOutage.tick(tick, limits, true, Map.of("p0",0));
        for (long tick = 140; tick < 160; tick++) afterOutage.tick(tick, limits, true, Map.of("p0",15));
        assertEquals(new DataValue.IntegerValue(2), afterOutage.flowSnapshot().orElseThrow().memory().cells().field("hits"),
            "An actual low-to-high edge must still fire after signal recovery");
    }

    @Test void anEdgeThatFindsItsOwnScriptAsleepIsLoggedWithoutReplayingAfterRestore() throws Exception {
        var draft = new FlowDraft(FlowDocumentBody.emptyAssembly());
        draft.setExtension("create_biotech:signals", 1L);
        var zero = new DataValue.IntegerValue(0);
        var one = new DataValue.IntegerValue(1);
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of("hits", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, zero)), List.of(), List.of()), Map.of());
        var workspace = new ProgramWorkspace(new ProgramDocument(archive, Map.of(), Optional.empty(), Optional.of(draft.snapshot())));
        var screen = FlowCallScreenTest.editor(workspace, 640);
        set(screen, "height", 520);
        screen.init();
        var canvas = (FlowBlockCanvas)get(screen, "canvas");
        search(screen).setValue("when_signal");
        var hat = button(screen, "extension_when_signal");
        int x = (int)get(canvas, "x") + 30, y = (int)get(canvas, "y") + 78;
        screen.mouseClicked(hat.getX() + 8, hat.getY() + 8, 0);
        screen.mouseDragged(x, y, 0, 40, 0);
        screen.mouseReleased(x, y, 0);
        var root = workspace.flow().orElseThrow().canvas().detached().getFirst().steps().getFirst().sourceId();
        search(screen).setValue("public_add");
        dragToJoint(screen, canvas, button(screen, "add_public_add"), canvas.joints().stream()
            .filter(j -> root.equals(j.socket().stack()) && j.socket().index() == 2).findFirst().orElseThrow());
        search(screen).setValue("add_sleep");
        dragToJoint(screen, canvas, button(screen, "add_sleep"), canvas.joints().stream()
            .filter(j -> root.equals(j.socket().stack()) && j.socket().index() == 3).findFirst().orElseThrow());
        var script = workspace.flow().orElseThrow().canvas().detached().getFirst().steps();
        var wait = assertInstanceOf(FlowProgram.Sleep.class, script.get(3));
        var amount = canvas.inlineSlots().stream().filter(slot -> slot.call().equals(wait.sourceId())).findFirst().orElseThrow();
        ScratchInlineWaitParityTest.chooseUnit(screen, amount, FlowProgram.WaitUnit.TICKS);
        FlowInlineScreenTest.setLiteral(screen, canvas.inlineSlots().stream()
            .filter(slot -> slot.call().equals(wait.sourceId())).findFirst().orElseThrow(), "200");

        var document = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot()));
        var program = document.flow().orElseThrow().program(document.archive());
        var watched = program.watches().values().iterator().next().handler().orElseThrow();
        var limits = new ComputerHostLimits(new ResourceLimits(512, 65_536, 16), SubgraphWorkspaceTest.LIMITS,
            MailboxLimits.DEFAULT, HardwarePins.DEFAULT, SerialHardware.NONE, 3);
        document.validate(limits.hardware(), limits.pins());
        var host = new ComputerBlockHost(() -> {});
        var recent = new com.nobodiiiii.createbiotech.content.biocomputing.diagnostics.RecentEventLog(() -> 0, () -> 4);
        host.onLaunchRefused((code, handler) -> recent.append(
            com.nobodiiiii.createbiotech.content.biocomputing.diagnostics.RecentEventLog.Kind.ERROR, code, handler));
        host.installFlow(program, new DataValue.RecordValue(Map.of()), limits, true);
        host.setPaused(false, limits, true);
        signalTicks(host, limits, 0, 24, 0);
        signalTicks(host, limits, 24, 48, 15);
        var first = host.flowSnapshot().orElseThrow();
        var activation = first.tasks().contexts().stream().filter(s -> s.entry().filter(watched::equals).isPresent()).findFirst().orElseThrow();
        assertEquals(FlowRuntime.Status.SLEEPING, activation.status());
        assertEquals(one, first.memory().cells().field("hits"));

        signalTicks(host, limits, 48, 72, 0);
        signalTicks(host, limits, 72, 96, 15);
        var busy = host.flowSnapshot().orElseThrow();
        assertEquals(List.of(activation.flow()), busy.tasks().contexts().stream()
            .filter(s -> s.entry().filter(watched::equals).isPresent()).map(FlowRuntime.Checkpoint::flow).toList());
        assertEquals(one, busy.memory().cells().field("hits"));
        var entries = assertInstanceOf(DataValue.ListValue.class, recent.value().field("entries")).values();
        assertEquals(1, entries.size(), "One refused edge must produce one diagnostic");
        var entry = assertInstanceOf(DataValue.RecordValue.class, entries.getFirst());
        assertEquals(new DataValue.TextValue("handler_already_running"), entry.field("code"));
        assertEquals(new DataValue.TextValue(watched.id()), entry.field("subject"));
        assertTrue(busy.tasks().contexts().stream().anyMatch(task -> task.flow().equals(activation.flow()) && task.sleepTicks() > 0));

        var restored = new ComputerBlockHost(() -> {});
        restored.read(host.write());
        restored.onLaunchRefused((code, handler) -> fail("Unexpected second refusal after restore: " + code + ":" + handler));
        signalTicks(restored, limits, 96, 320, 15);
        var settled = restored.flowSnapshot().orElseThrow();
        assertTrue(restored.loaded(), () -> restored.diagnostic().toString());
        assertEquals(1, settled.tasks().contexts().size(), "Only the listener remains after the handler has finished");
        assertEquals(one, settled.memory().cells().field("hits"), "The missed edge was not queued for a free slot");
        assertEquals(first.state().flow(), settled.state().flow(), "The main program was not reset");

        signalTicks(restored, limits, 320, 344, 0);
        signalTicks(restored, limits, 344, 368, 15);
        var fresh = restored.flowSnapshot().orElseThrow();
        assertEquals(new DataValue.IntegerValue(2), fresh.memory().cells().field("hits"),
            "A genuinely new edge still starts a fresh handler");
    }

    private static void signalTicks(ComputerBlockHost host, ComputerHostLimits limits, long from, long until, int level) {
        for (long tick = from; tick < until; tick++) host.tick(tick, limits, true, Map.of("p0", level));
    }


    @Test void statementsSnapDirectlyToAComposedEdgeHatAndStayAttachedAfterReopening() throws Exception {
        var draft = new FlowDraft(FlowDocumentBody.emptyAssembly());
        draft.append(new FlowProgram.Forever("long_loop"));
        for (int i = 0; i < 6; i++) draft.append(new FlowProgram.Repeat("nested_" + i, new FlowInput.Literal(new DataValue.IntegerValue(1))));
        for (int i = 0; i < 6; i++) draft.append(new FlowProgram.EndRepeat("end_nested_" + i));
        draft.append(new FlowProgram.EndForever("long_loop_end"));
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(draft.snapshot()));
        var screen = FlowCallScreenTest.editor(workspace, 640);
        var original = workspace.snapshot();
        var opened = edgeNavigation(screen);
        click(screen, "category_events");
        click(screen, "add_watch");
        var definition = openedDefinition(opened);
        click(definition, "watch_apply_definition");
        assertEquals(original, workspace.snapshot(), "an incomplete definition creates no hat");
        assertSame(definition, opened.get());
        click(definition, "cancel");
        assertEquals(original, workspace.snapshot(), "cancel publishes nothing");
        click(screen, "add_watch");
        definition = openedDefinition(opened);
        var preview = FlowInlineScreenTest.slot(definition, "false", 0);
        var rising = button(definition, "watch_add_rising");
        var falling = button(definition, "watch_add_falling");
        assertTrue(rising.getY() + rising.getHeight() <= preview.y(), "the waveform controls precede the editable condition");
        assertTrue(button(definition, "category_operators").getY() >= preview.y() + preview.height(), "the block palette is below the condition");
        assertEquals(rising.getY(), falling.getY());
        click(definition, "watch_add_rising");
        var single = ((FlowDraft)get(definition, "draft")).snapshot();
        click(definition, "watch_number_integer");
        click(definition, "watch_add_falling");
        var combined = ((FlowDraft)get(definition, "draft")).snapshot();
        click(definition, "undo");
        assertEquals(single, ((FlowDraft)get(definition, "draft")).snapshot());
        click(definition, "redo");
        assertEquals(combined, ((FlowDraft)get(definition, "draft")).snapshot());
        var definitionCanvas = (FlowBlockCanvas)get(definition, "canvas");
        var stagedGraph = ((FlowDraft)get(definition, "draft")).snapshot().inline().values().iterator().next();
        assertEquals(ScalarOperation.OR_BOOLEAN,
            assertInstanceOf(ComputationGraph.Scalar.class, stagedGraph.nodes().get(stagedGraph.outputs().get("value"))).operation());
        assertTrue(stagedGraph.nodes().values().stream().anyMatch(node -> node instanceof ComputationGraph.Scalar scalar
            && scalar.operation() == ScalarOperation.SAMPLE_FALLING_DECIMAL));
        assertFalse(definitionCanvas.inlineSlots().isEmpty());
        assertEquals(original, workspace.snapshot(), "condition editing remains isolated before confirm");
        click(definition, "watch_apply_definition");
        assertSame(screen, opened.get());
        initialize(screen, 640);
        var canvas = (FlowBlockCanvas)get(screen, "canvas");
        int cx = (int)get(canvas, "x"), cy = (int)get(canvas, "y");
        var root = workspace.flow().orElseThrow().canvas().detached().getFirst().steps().getFirst().sourceId();
        canvas.focus(root);
        var generated = canvas.blocks().stream().filter(b -> b.step() instanceof FlowProgram.Watch).findFirst().orElseThrow();
        var loopFoot = canvas.blocks().stream().filter(b -> b.step().sourceId().equals("long_loop_end")).findFirst().orElseThrow();
        assertTrue(generated.y() > loopFoot.y() + loopFoot.height(), "the new hat and its connector must not cover the existing loop");
        assertTrue(canvas.inlineSlots().stream().noneMatch(s -> s.call().equals(generated.step().sourceId())),
            "the confirmed condition is not editable on the main canvas");
        var edgeGraph = workspace.flow().orElseThrow().inline().values().iterator().next();
        assertEquals(2, edgeGraph.nodes().values().stream().filter(node -> node instanceof ComputationGraph.Scalar scalar
            && scalar.operation().retainedEdge()).count());
        assertEquals(1, edgeGraph.nodes().values().stream().filter(node -> node instanceof ComputationGraph.Scalar scalar
            && scalar.operation().fallingEdge()).count());
        var before = workspace.snapshot();
        var connector = canvas.joints().stream().filter(j -> root.equals(j.socket().stack()) && j.socket().index() == 2)
            .findFirst().orElseThrow();
        click(screen, "category_control");
        var repeat = button(screen, "add_repeat");
        var viewport = (com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport)get(canvas, "viewport");
        double x = cx + viewport.screenX(connector.x()) + 8, y = cy + viewport.screenY(connector.y()) + 10;
        assertTrue(canvas.contains(x, y), "drop point is on the visible canvas");
        screen.mouseClicked(repeat.getX() + 10, repeat.getY() + 10, 0);
        screen.mouseDragged(x, y, 0, 30, 30);
        screen.mouseReleased(x, y, 0);
        var edited = workspace.snapshot();
        assertNotEquals(before, edited, ((net.minecraft.network.chat.Component)get(screen, "notice")).getString());
        var loaded = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(edited));
        var flow = loaded.flow().orElseThrow();
        assertEquals(edited, loaded);
        assertEquals(1, flow.canvas().detached().size(), "no separate handler definition is needed in the editor");
        assertEquals(draft.snapshot().steps(), flow.steps(), "the callback is not part of the main script");
        assertInstanceOf(FlowProgram.Repeat.class, flow.canvas().detached().getFirst().steps().get(2));
        var program = flow.program(loaded.archive());
        var handler = program.watches().values().iterator().next().handler().orElseThrow();
        assertTrue(program.steps().stream().anyMatch(step -> step instanceof FlowProgram.Procedure p && p.key().equals(handler)));
        program.validate(new ResourceLimits(1000, 100_000, 12));
        click(screen, "undo"); assertEquals(before, workspace.snapshot());
        click(screen, "redo"); assertEquals(edited, workspace.snapshot());

        // The generated hat reopens its private condition editor, not the attached script.
        var hat = canvas.blocks().stream().filter(block -> block.step() instanceof FlowProgram.Watch).findFirst().orElseThrow();
        screen.mouseClicked(cx + viewport.screenX(hat.x()) + 12, cy + viewport.screenY(hat.y()) + 8, 1);
        click(screen, "watch_edit_definition");
        definition = openedDefinition(opened);
        assertEquals(2, ((FlowBlockCanvas)get(definition, "canvas")).inlineSlots().stream().filter(slot ->
            edgeGraph.nodes().get(slot.node()) instanceof ComputationGraph.Scalar scalar && scalar.operation().retainedEdge()).count());
        click(definition, "watch_apply_definition");
        assertEquals(edited, workspace.snapshot(), "reopening and confirming an unchanged condition leaves the script intact");
    }


    @Test void attachedEdgeBodyRunsOncePerCrossingOfAnOrdinaryPublicNumber() {
        var integer = DataType.Scalar.INTEGER;
        var zero = new DataValue.IntegerValue(0); var one = new DataValue.IntegerValue(1);
        var monitor = new ModuleDefinition.Key("monitor", 1);
        var setHigh = new ModuleDefinition.Key("set_high", 1);
        var setLow = new ModuleDefinition.Key("set_low", 1);
        var graph = new ComputationGraph(Map.of(), Map.of(
            "sample", new ComputationGraph.ReadMemory("signal"), "threshold", new ComputationGraph.Constant(one),
            "rising", new ComputationGraph.Scalar(ScalarOperation.SAMPLE_RISING_INTEGER,
                Map.of("current", "sample", "threshold", "threshold")),
            "falling", new ComputationGraph.Scalar(ScalarOperation.SAMPLE_FALLING_INTEGER,
                Map.of("current", "sample", "threshold", "threshold")),
            "either", new ComputationGraph.Scalar(ScalarOperation.OR_BOOLEAN, Map.of("left", "rising", "right", "falling"))),
            Map.of("value", "either"));
        var empty = new DataValue.RecordValue(Map.of());
        var scratch = new FlowDocumentBody(Map.of(), List.of(), empty, new FlowBlockLayout(new ProgramDocument.Position(16, 16), List.of(
            new FlowBlockLayout.Stack(new ProgramDocument.Position(30, 70), List.of(
                new FlowProgram.Procedure("monitor", monitor, Map.of(), Map.of(), Map.of()),
                new FlowProgram.Watch("condition", Optional.empty(), new FlowInput.Expression("condition", "value", Map.of())),
                new FlowProgram.Shared("count", "hits", new FlowInput.Literal(one), true),
                new FlowProgram.EndProcedure("monitor_end"))),
            edgeWriter(setHigh, new DataValue.IntegerValue(2)), edgeWriter(setLow, one))), Map.of("condition", graph), FlowProgram.Execution.REGISTER_V1);
        var archive = new ProgramArchive(new ProgramDefinition(
            new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of("signal", new ProgramDefinition.Cell(integer, one), "hits", new ProgramDefinition.Cell(integer, zero)), List.of(), List.of()), Map.of());
        var loaded = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(new ProgramDocument(archive, Map.of(), Optional.empty(), Optional.of(scratch))));
        var program = loaded.flow().orElseThrow().program(loaded.archive());
        var limits = new ComputerHostLimits(new ResourceLimits(1000, 100_000, 12), new ArchiveLimits(100_000, 1_000_000, 1_000_000),
            MailboxLimits.DEFAULT, HardwarePins.DEFAULT, SerialHardware.NONE, 4);
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(program, empty, limits, true);
        host.setPaused(false, limits, true);
        for (long tick = 0; tick < 20; tick++) host.tick(tick, limits, true, Map.of());
        assertEquals(zero, host.flowSnapshot().orElseThrow().memory().cells().field("hits"));
        assertThrows(ComputationException.class, () -> host.startFlowTask(monitor, empty, limits, true),
            "A software monitor cannot be started a second time as an ordinary task");
        var firstWrite = host.startFlowTask(setHigh, empty, limits, true);
        for (long tick = 20; tick < 40; tick++) host.tick(tick, limits, true, Map.of());
        assertEquals(one, host.flowSnapshot().orElseThrow().memory().cells().field("hits"));
        host.claimFlowTask(firstWrite, limits, true);
        var restored = new ComputerBlockHost(() -> {});
        restored.read(host.write()); restored.tick(40, limits, true, Map.of());
        assertTrue(restored.loaded(), () -> restored.diagnostic().toString());
        var secondWrite = restored.startFlowTask(setLow, empty, limits, true);
        restored.setPaused(false, limits, true);
        for (long tick = 41; tick < 61; tick++) restored.tick(tick, limits, true, Map.of());
        restored.claimFlowTask(secondWrite, limits, true);
        assertEquals(one, restored.flowSnapshot().orElseThrow().memory().cells().field("signal"));
        assertEquals(new DataValue.IntegerValue(2), restored.flowSnapshot().orElseThrow().memory().cells().field("hits"));
    }


    private static FlowBlockLayout.Stack edgeWriter(ModuleDefinition.Key key, DataValue value) {
        return new FlowBlockLayout.Stack(new ProgramDocument.Position(10, 200), List.of(
            new FlowProgram.Procedure(key.id(), key, Map.of(), Map.of(), Map.of()),
            new FlowProgram.Shared(key.id() + "_write", "signal", new FlowInput.Literal(value), false),
            new FlowProgram.EndProcedure(key.id() + "_end")));
    }


    @Test void aReadonlySignalReporterSnapsIntoAnEdgeDefinitionAndRunsTheAttachedScript() throws Exception {
        var draft = new FlowDraft(FlowDocumentBody.emptyAssembly());
        draft.setExtension("create_biotech:signals", 1L);
        var pin = new ModuleDefinition.Key("cb_extension:create_biotech:signals:simple_level", 1);
        var zero = new DataValue.IntegerValue(0);
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of("hits", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, zero)), List.of(), List.of()), Map.of());
        var workspace = new ProgramWorkspace(new ProgramDocument(archive, Map.of(), Optional.empty(), Optional.of(draft.snapshot())));
        var screen = FlowCallScreenTest.editor(workspace, 640);
        var opened = edgeNavigation(screen);
        var original = workspace.snapshot();
        click(screen, "category_events");
        click(screen, "add_watch");
        var editor = openedDefinition(opened);
        FlowInlineScreenTest.dropReporter(editor, "inline_sample_rising_integer", FlowInlineScreenTest.slot(editor, "false", 0));
        click(editor, "category_extensions");
        var reporter = button(editor, "extension_simple_level");
        var slot = FlowInlineScreenTest.slot(editor, "0", 0);
        editor.mouseClicked(reporter.getX() + 8, reporter.getY() + 8, 0);
        editor.mouseDragged(slot.x() + 8, slot.y() + slot.height() / 2, 0, 40, 0);
        editor.mouseReleased(slot.x() + 8, slot.y() + slot.height() / 2, 0);
        assertEquals(original, workspace.snapshot(), "the extension binding is not published before confirming");
        click(editor, "watch_apply_definition");
        assertSame(screen, opened.get());
        initialize(screen, 640);
        var hat = workspace.flow().orElseThrow().canvas().detached().stream()
            .filter(stack -> stack.steps().stream().anyMatch(FlowProgram.Watch.class::isInstance)).findFirst().orElseThrow();
        var graph = workspace.flow().orElseThrow().inline().values().iterator().next();
        var edge = assertInstanceOf(ComputationGraph.Scalar.class, graph.nodes().get(graph.outputs().get("value")));
        var read = assertInstanceOf(ComputationGraph.ProcedureValue.class, graph.nodes().get(edge.arguments().get("current")));
        assertEquals(pin, read.procedure());
        var canvas = (FlowBlockCanvas)get(screen, "canvas");
        var id = hat.steps().getFirst().sourceId();
        canvas.focus(id);
        var joint = canvas.joints().stream().filter(j -> id.equals(j.socket().stack()) && j.socket().index() == 2).findFirst().orElseThrow();
        var viewport = (com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport)get(canvas, "viewport");
        double x = (int)get(canvas, "x") + viewport.screenX(joint.x()) + 8;
        double y = (int)get(canvas, "y") + viewport.screenY(joint.y()) + 10;
        search(screen).setValue("public_add");
        var add = button(screen, "add_public_add");
        screen.mouseClicked(add.getX() + 8, add.getY() + 8, 0);
        screen.mouseDragged(x, y, 0, 40, 0);
        screen.mouseReleased(x, y, 0);
        var saved = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot()));
        var program = saved.flow().orElseThrow().program(saved.archive());
        var limits = new ComputerHostLimits(new ResourceLimits(512, 65_536, 16), SubgraphWorkspaceTest.LIMITS,
            MailboxLimits.DEFAULT, HardwarePins.DEFAULT, SerialHardware.NONE, 3);
        saved.validate(limits.hardware(), limits.pins());
        assertThrows(IllegalArgumentException.class, () -> saved.validate(limits.hardware(), new HardwarePins(List.of(), List.of())));
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(program, new DataValue.RecordValue(Map.of()), limits, true);
        host.setPaused(false, limits, true);
        for (long tick = 0; tick < 30; tick++) host.tick(tick, limits, true, Map.of("p0", 0));
        assertEquals(zero, host.flowSnapshot().orElseThrow().memory().cells().field("hits"));
        for (long tick = 30; tick < 40; tick++) host.tick(tick, limits, true, Map.of());
        var beforeReconnect = new ComputerBlockHost(() -> {});
        beforeReconnect.read(host.write());
        beforeReconnect.tick(40, limits, true, Map.of());
        assertTrue(beforeReconnect.loaded(), () -> beforeReconnect.diagnostic().toString());
        for (long tick = 41; tick < 70; tick++) beforeReconnect.tick(tick, limits, true, Map.of("p0", 15));
        assertEquals(new DataValue.IntegerValue(1), beforeReconnect.flowSnapshot().orElseThrow().memory().cells().field("hits"));
        for (long tick = 70; tick < 90; tick++) beforeReconnect.tick(tick, limits, true, Map.of());
        for (long tick = 90; tick < 120; tick++) beforeReconnect.tick(tick, limits, true, Map.of("p0", 15));
        assertEquals(new DataValue.IntegerValue(1), beforeReconnect.flowSnapshot().orElseThrow().memory().cells().field("hits"),
            "Unavailable then high again is not a new rising edge");
        var writer = new FlowDraft(saved.flow().orElseThrow());
        writer.append(new FlowProgram.SignalRead("sample", "p0", "level"));
        writer.append(new FlowProgram.SignalWrite("drive", "p1", "level"));
        var denied = assertThrows(ComputationException.class, () -> writer.snapshot().program(archive).validate(limits.hardware()));
        assertTrue(denied.getMessage().contains("output arbitration"));
    }




    @Test void tryLaunchUsesTheSelectedCustomHandlerAndKeepsDraftUndoAndRoundTrip() throws Exception {
        var entry = new ModuleDefinition.Key("alarm", 1);
        var body = FlowDocumentBody.emptyAssembly();
        var stack = new FlowBlockLayout.Stack(new ProgramDocument.Position(280, 16),
            List.of(new FlowProgram.Procedure("alarm_def", entry, Map.of(), Map.of(), Map.of()),
                new FlowProgram.EndProcedure("alarm_end")));
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(body.edited(body.inputs(), body.steps(), body.initial(),
            body.canvas().arranged(body.canvas().main(), List.of(stack)), body.inline())));
        var screen = FlowCallScreenTest.editor(workspace, 640);
        search(screen).setValue("try_launch");
        var launch = button(screen, "add_try_launch");
        double fromX = launch.getX() + 10, fromY = launch.getY() + 10;
        var canvas = get(screen, "canvas");
        double x = (int)get(canvas, "x") + 24, y = (int)get(canvas, "y") + 50;
        var before = workspace.snapshot();
        screen.mouseClicked(fromX, fromY, 0);
        screen.mouseDragged(x, y, 0, x - fromX, y - fromY);
        assertEquals(before, workspace.snapshot());
        screen.mouseReleased(x, y, 0);
        var edited = workspace.snapshot();
        var step = assertInstanceOf(FlowProgram.TryLaunch.class, edited.flow().orElseThrow().steps().getFirst());
        assertEquals(entry, step.procedure());
        var encoded = ProgramDocumentCodec.encode(edited);
        assertEquals(new DataValue.IntegerValue(48), encoded.field("version"));
        assertEquals(edited, ProgramDocumentCodec.decode(encoded));
        var oldFields = new java.util.LinkedHashMap<>(encoded.fields());
        oldFields.put("version", new DataValue.IntegerValue(47));
        assertThrows(com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException.class,
            () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(oldFields)));
        // The source worktree wrote extension-enabled handlers as v48. Mainline writes them as v56.
        var withExtension = workspace.flow().orElseThrow();
        var sourceWorkspace = new ProgramWorkspace();
        sourceWorkspace.setFlow(Optional.of(withExtension.edited(withExtension.inputs(), withExtension.steps(), withExtension.initial(),
            withExtension.canvas().withExtensions(Map.of("create_biotech:signals", 1L)), withExtension.inline())));
        var mixed = sourceWorkspace.snapshot();
        var mixedFields = new java.util.LinkedHashMap<>(ProgramDocumentCodec.encode(mixed).fields());
        assertEquals(new DataValue.IntegerValue(56), mixedFields.get("version"));
        mixedFields.put("version", new DataValue.IntegerValue(48));
        assertEquals(mixed, ProgramDocumentCodec.decode(new DataValue.RecordValue(mixedFields)));
        click(screen, "undo");
        assertEquals(before, workspace.snapshot());
    }

    @Test void intentCategoriesAndSearchFindExistingValuesWithoutChangingTheDraft() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(new FlowDocumentBody(
            Map.of("source", new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.RecordType(Map.of("reading", com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.INTEGER))), List.of(),
            new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.RecordValue(Map.of("source",new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.RecordValue(Map.of("reading",new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.IntegerValue(21))))))));
        var screen = FlowCallScreenTest.editor(workspace,640); var before = workspace.snapshot();
        click(screen,"category_variables"); assertNotNull(button(screen,"add_assign"));
        click(screen,"category_data");
        var fieldLabel = FlowBlockCatalog.label(new InlineGraphEdits.Variable("source", List.of("reading"), DataType.Scalar.ANY));
        boolean foundField = false;
        do {
            bounds(screen);
            foundField |= screen.children().stream().anyMatch(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget w
                && w.getMessage().equals(fieldLabel));
            if (!button(screen, "palette_next").active) break;
            click(screen, "palette_next");
        } while (true);
        assertTrue(foundField, "Fields must remain reachable when the data catalog spans several pages");
        var search = search(screen); search.setValue("serial_status");
        assertNotNull(button(screen,"add_serial_status"));
        assertSame(search,search(screen),"Typing must not recreate the focused search field");
        assertEquals(before,workspace.snapshot()); assertFalse((boolean)get(screen,"waiting"));
    }
    @Test void cancelAndDropOutsideDoNotCreateWhileTwoParkedCopiesKeepIndependentIdentities() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        var screen = FlowCallScreenTest.editor(workspace, 640); sensing(screen,"link");
        var prototype = button(screen, "add_serial_status");
        double fromX = prototype.getX() + 10, fromY = prototype.getY() + 10;
        var canvas = get(screen, "canvas"); double x = (int)get(canvas, "x") + 260, y = (int)get(canvas, "y") + 40;
        var before = workspace.snapshot();
        screen.mouseClicked(fromX, fromY, 0); screen.mouseDragged(x, y, 0, x - fromX, y - fromY);
        assertTrue(screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0));
        screen.mouseReleased(x, y, 0); assertEquals(before, workspace.snapshot());
        screen.mouseClicked(fromX, fromY, 0); screen.mouseDragged(x, y, 0, x - fromX, y - fromY);
        screen.mouseReleased(fromX, fromY, 0); assertEquals(before, workspace.snapshot());
        for (int i = 0; i < 2; i++) {
            screen.mouseClicked(fromX, fromY, 0); screen.mouseReleased(x, y + i * 45, 0);
        }
        var doc = workspace.snapshot(); var body = doc.flow().orElseThrow();
        assertTrue(body.steps().isEmpty()); assertEquals(2, body.canvas().detached().size());
        var first = body.canvas().detached().getFirst().steps().getFirst(); var last = body.canvas().detached().getLast().steps().getFirst();
        assertNotEquals(first.sourceId(), last.sourceId());
        assertNotEquals(((FlowProgram.SerialStatus)first).local(), ((FlowProgram.SerialStatus)last).local());
        assertEquals(doc, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(doc)));
        assertFalse((boolean)get(screen, "waiting"));
        click(screen, "undo"); assertEquals(1, workspace.flow().orElseThrow().canvas().detached().size());
        click(screen, "undo"); assertEquals(before, workspace.snapshot());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void everyCategoryAndPrototypeIsReachableWithoutOverlappingOnSmallScreens(int width) throws Exception {
        var draft = new FlowDraft(FlowDocumentBody.emptyAssembly());
        draft.initial(new DataValue.RecordValue(Map.of("items",new DataValue.ListValue(List.of(new DataValue.IntegerValue(0))))));
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(draft.snapshot()));
        var screen = FlowCallScreenTest.editor(workspace, width); var before = workspace.snapshot();
        var seen = new HashSet<String>();
        for (String category : List.of("events", "control", "operators", "variables", "storage", "data", "sensing_input", "sensing_link", "sensing_output", "custom")) {
            if(category.startsWith("sensing_")) sensing(screen,category.substring(8));
            else click(screen, "category_" + category);
            if (category.equals("storage")) click(screen, "advanced_off");
            do {
                bounds(screen);
                var visible = new HashSet<String>();
                for (var child : screen.children()) if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget
                    && widget.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                    && t.getKey().startsWith("create_biotech.biocomputing.flow_editor.add_")) assertTrue(visible.add(t.getKey()));
                seen.addAll(visible); // A final partial jump can overlap the previous viewport.
                if (!button(screen, "palette_next").active) break;
                click(screen, "palette_next");
            } while (true);
        }
        var expected = FlowBlockCatalog.entries(Map.of()).stream().filter(e -> e.command() != null)
            .map(e -> "create_biotech.biocomputing.flow_editor.add_" + e.id()).collect(java.util.stream.Collectors.toSet());
        assertEquals(expected, seen); assertEquals(before, workspace.snapshot());
    }
    @Test void aNewCanvasGestureCannotResumeAnInterruptedPaletteCreation() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        var screen = FlowCallScreenTest.editor(workspace, 640); var before = workspace.snapshot();
        var repeat = button(screen, "add_repeat"); screen.mouseClicked(repeat.getX() + 10, repeat.getY() + 10, 0);
        // A mouse-up can be lost while changing window focus. The next press starts a new gesture.
        var canvas = get(screen, "canvas"); double x = (int)get(canvas, "x") + 24, y = (int)get(canvas, "y") + 24;
        screen.mouseClicked(x, y, 0); screen.mouseReleased(x, y, 0);
        assertFalse((boolean)get(screen, "parameters"), "A main-hat click must not reopen an old prototype form");
        assertEquals(before, workspace.snapshot());
    }
    static net.minecraft.client.gui.components.EditBox search(ComputerFlowScreen screen) {
        return screen.children().stream().filter(net.minecraft.client.gui.components.EditBox.class::isInstance)
            .map(net.minecraft.client.gui.components.EditBox.class::cast)
            .filter(b -> b.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t && t.getKey().endsWith(".catalog_search")).findFirst().orElseThrow();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"en_us", "zh_cn"})
    void searchResultsRemainOrdinaryDraggableValuesAndHaveLocalizedDescriptions(String locale) throws Exception {
        var old = net.minecraft.locale.Language.getInstance();
        try {
            net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(ComputerUiTranslationsTest.load(locale)));
            var type = new DataType.RecordType(Map.of("reading",DataType.Scalar.INTEGER));
            var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("source",type),List.of(),
                new DataValue.RecordValue(Map.of("source",new DataValue.RecordValue(Map.of("reading",new DataValue.IntegerValue(21))))))));
            var screen = FlowCallScreenTest.editor(workspace,320); click(screen,"add_assign");
            var before = workspace.snapshot(); var ids = new HashSet<String>();
            for (var entry : FlowBlockCatalog.entries(workspace.flow().orElseThrow().inputs())) {
                assertTrue(ids.add(entry.id())); assertFalse(entry.help().getString().contains("create_biotech."));
                assertTrue(FlowBlockCatalog.search(workspace.flow().orElseThrow().inputs(),List.of(),FlowBlockPalette.Category.EVENTS,entry.id(),true).contains(entry));
            }
            for (String query : List.of("field", "字段", "SOURCE.READING")) {
                // Headless EditBox mouse positioning requires a native GLFW window; focus uses the public Screen API.
                var search=search(screen); screen.setFocused(search); search.setValue(query);
                assertSame(search,search(screen)); assertSame(search,screen.getFocused()); bounds(screen);
                assertEquals(before,workspace.snapshot());
                var results = FlowBlockCatalog.search(workspace.flow().orElseThrow().inputs(),FlowBlockPalette.Category.EVENTS,query);
                assertEquals(1, results.stream().filter(e -> e.value() instanceof InlineGraphEdits.Variable variable
                    && variable.name().equals("source") && variable.path().equals(List.of("reading"))).count());
                if (query.equals("SOURCE.READING")) assertEquals(1, results.size());
                else {
                    assertTrue(results.stream().allMatch(e -> e.group().equals("fields")));
                    assertTrue(results.stream().anyMatch(e -> e.value() instanceof InlineGraphEdits.RecordConstruction),
                        "Field searches also include the public record constructor, not only existing fields");
                }
            }
            var reporter = screen.children().stream().filter(c -> c instanceof com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton b && b.getMessage().equals(FlowBlockCatalog.label(new InlineGraphEdits.Variable("source", List.of("reading"), DataType.Scalar.ANY))))
                .map(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::cast).findFirst().orElseThrow();
            var slot = FlowInlineScreenTest.slot(screen,"0",0);
            screen.mouseClicked(reporter.getX()+5,reporter.getY()+5,0); screen.mouseReleased(slot.x()+8,slot.y()+slot.height()/2,0);
            assertEquals(new DataValue.IntegerValue(21),FlowInlineScreenTest.run(workspace.snapshot()).field("block_1_value"));
            click(screen,"undo"); assertEquals(before,workspace.snapshot());
            var query=search(screen); screen.setFocused(query); query.setValue("not-a-block");
            assertFalse(button(screen,"palette_next").active); assertEquals(before,workspace.snapshot());
            screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE,0,0); assertEquals("",search(screen).getValue());
            assertEquals(before,workspace.snapshot()); sensing(screen,"input"); bounds(screen);
            assertFalse(button(screen,"palette_next").active); assertEquals(before,workspace.snapshot());
        } finally { net.minecraft.locale.Language.inject(old); }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 640})
    void sensingLibraryDiscoversDisabledExtensionsInContextAndReturnsToTheSameGroup(int width) throws Exception {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.emptySoftwareAssembly()));
        var editor = FlowCallScreenTest.editor(workspace, width);
        var before = workspace.snapshot();
        var opened = new java.util.concurrent.atomic.AtomicReference<net.minecraft.client.gui.screens.Screen>();
        set(editor, "navigator", (java.util.function.Consumer<net.minecraft.client.gui.screens.Screen>) opened::set);
        click(editor, "category_sensing");
        click(editor, "sensing_link");
        assertTrue(visibleButton(editor, "sensing_examples").isPresent(), "Do not replace the existing examples shortcut");
        bounds(editor);
        assertTrue(visibleButton(editor, "extension_xbus_busy").isEmpty());

        click(editor, "sensing_extensions");
        var library = opened.get();
        FlowProcedureScreenTest.initializeForm(library, width);
        bounds(library);
        assertEquals(before, workspace.snapshot(), "Discovery does not enable or install anything");
        var search = library.children().stream().filter(net.minecraft.client.gui.components.EditBox.class::isInstance)
            .map(net.minecraft.client.gui.components.EditBox.class::cast).findFirst().orElseThrow();
        search.setValue("create_biotech:text @ 1");
        assertTrue(visibleButton(library, "extension_enable").isEmpty(), "Text tools have no communication block");
        click(library, "extension_browse_all");
        assertEquals("create_biotech:text @ 1", search.getValue());
        assertTrue(button(library, "extension_enable").active, "Other extensions remain discoverable with the same query");
        click(library, "extension_browse_group");
        assertTrue(visibleButton(library, "extension_enable").isEmpty());
        search.setValue("create_biotech:signals @ 1");
        click(library, "extension_enable");
        click(library, "back");
        assertSame(editor, opened.get());
        initialize(editor, width);
        assertFalse(button(editor, "sensing_link").active, "Return to the selected Sensing group, not Extensions");
        assertEquals(Map.of("create_biotech:signals", 1L), workspace.flow().orElseThrow().canvas().extensions());
        assertTrue(((List<?>)get(editor, "catalogEntries")).stream().map(FlowBlockCatalog.Entry.class::cast)
            .anyMatch(entry -> entry.id().equals("extension:cb_extension:create_biotech:signals:xbus_busy")));
        assertTrue(workspace.flow().orElseThrow().steps().isEmpty());
        click(editor, "undo");
        assertEquals(before, workspace.snapshot());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={320,426,640})
    void extensionsAreOptInAndTheirLibraryDoesNotInstallAProgram(int width) throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptySoftwareAssembly()));
        var editor = FlowCallScreenTest.editor(workspace, width);
        var before = workspace.snapshot();
        click(editor, "category_extensions");
        assertTrue(visibleButton(editor, "extension_library").isPresent());
        assertTrue(visibleButton(editor, "extension_reader_status").isEmpty());
        assertEquals(before, workspace.snapshot());
        var opened = new java.util.concurrent.atomic.AtomicReference<net.minecraft.client.gui.screens.Screen>();
        set(editor, "navigator", (java.util.function.Consumer<net.minecraft.client.gui.screens.Screen>) opened::set);
        click(editor, "extension_library");
        var library = opened.get(); FlowProcedureScreenTest.initializeForm(library, width); bounds(library);
        boxes(library).getFirst().setValue("create_biotech:reader");
        assertEquals(before, workspace.snapshot(), "Selecting a particular extension is not enabling it");
        click(library, "extension_enable");
        assertEquals(Map.of("create_biotech:reader", 1L), workspace.flow().orElseThrow().canvas().extensions());
        assertTrue(workspace.flow().orElseThrow().steps().isEmpty());
        click(library, "back"); initialize(editor, width);
        var block = button(editor, "extension_reader_status");
        var canvas = (FlowBlockCanvas)get(editor, "canvas");
        var socket = canvas.joints().getFirst();
        double x = (int)get(canvas, "x") + socket.x() + 8, y = (int)get(canvas, "y") + socket.y() + 10;
        editor.mouseClicked(block.getX()+8, block.getY()+8, 0);
        editor.mouseDragged(x, y, 0, 40, 0); editor.mouseReleased(x, y, 0);
        var compiled = workspace.snapshot();
        var call = assertInstanceOf(FlowProgram.Invoke.class, compiled.flow().orElseThrow().steps().getFirst());
        assertEquals("cb_extension:create_biotech:reader:status", call.procedure().id());
        assertEquals(Set.of("x","y","z"), compiled.flow().orElseThrow().inline().get(call.sourceId()).outputs().keySet());
        var extensionStack = compiled.flow().orElseThrow().canvas().detached().getFirst();
        var extensionSource = extensionStack.steps().getFirst().sourceId();
        assertEquals(List.of(call.sourceId()), canvas.blocks().stream().map(b -> b.step().sourceId()).toList(),
            "A catalog command must not draw a fixed definition over the player's script");
        assertTrue(canvas.joints().stream().noneMatch(j -> extensionSource.equals(j.socket().stack())),
            "There must be no invisible snap targets in the fixed implementation");
        var limits = new ResourceLimits(256, 65536, 8);
        var serial = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.SerialHardware(Map.of("s0",
            new com.nobodiiiii.createbiotech.content.biocomputing.hardware.SerialHardware.Port(128, 4096, 4, 16384)));
        var target = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(limits, SubgraphWorkspaceTest.LIMITS,
            com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits.DEFAULT,
            com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins.DEFAULT, serial, 1, true, true);
        assertDoesNotThrow(() -> com.nobodiiiii.createbiotech.content.biocomputing.computer.PreparedProgram.prepare(compiled, target));
        assertEquals(compiled, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(compiled)));
        var forged = new FlowDraft(compiled.flow().orElseThrow());
        var original = FlowProcedureTools.definitions(forged.snapshot()).getFirst();
        var replacement = new FlowProgram.Procedure(original.sourceId(), original.key(), original.inputs(),
            original.outputs(), original.returns(), original.rules(), Optional.of(
                new com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource("RET")));
        forged.replace(original.sourceId(), replacement);
        var forgedDocument = new ProgramDocument(compiled.archive(), compiled.positions(), compiled.terminal(), Optional.of(forged.snapshot()));
        assertTrue(assertThrows(IllegalArgumentException.class, () ->
            com.nobodiiiii.createbiotech.content.biocomputing.computer.PreparedProgram.prepare(forgedDocument, target)).getMessage().contains("source differs"));

        var missing = new ModuleDefinition.Key("cb_extension:addon:reader:status", 8);
        forged.replace(original.sourceId(), new FlowProgram.Procedure(original.sourceId(), missing, original.inputs(),
            original.outputs(), original.returns(), original.rules(), original.assembly()));
        forged.replace(call.sourceId(), new FlowProgram.Invoke(call.sourceId(), missing, call.bindings(), call.operands()));
        forged.setExtension("addon:reader", 8L);
        var portable = new ProgramDocument(compiled.archive(), compiled.positions(), compiled.terminal(), Optional.of(forged.snapshot()));
        var unavailable = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(portable));
        assertEquals(portable, unavailable, "Missing add-ons must not prevent reading the original draft");
        assertTrue(assertThrows(IllegalArgumentException.class, () ->
            com.nobodiiiii.createbiotech.content.biocomputing.computer.PreparedProgram.prepare(unavailable, target)).getMessage().contains("Missing Scratch extension"));

        click(editor, "extension_library"); library = opened.get(); FlowProcedureScreenTest.initializeForm(library, width);
        boxes(library).getFirst().setValue("create_biotech:reader");
        click(library, "extension_disable");
        var disabled = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot()));
        assertEquals(compiled.flow().orElseThrow().steps(), disabled.flow().orElseThrow().steps());
        assertEquals(compiled.flow().orElseThrow().inline(), disabled.flow().orElseThrow().inline());
        assertTrue(assertThrows(IllegalArgumentException.class, () ->
            com.nobodiiiii.createbiotech.content.biocomputing.computer.PreparedProgram.prepare(disabled, target)).getMessage().contains("not enabled"));
        click(library, "extension_find_uses");
        var references = opened.get();
        assertNotSame(library, references);
        FlowProcedureScreenTest.initializeForm(references, 426);
        bounds(references);
        click(references, "procedure_reference_call");
        assertSame(editor, opened.get());
        assertEquals(call.sourceId(), canvas.selected());
        assertEquals(disabled, workspace.snapshot(), "Locating a disabled block doesn't edit the draft");
        initialize(editor, width);
        assertTrue(visibleButton(editor, "extension_reader_status").isEmpty());
        click(editor, "undo");
        assertEquals(compiled, workspace.snapshot());
        assertFalse((boolean)get(editor, "waiting"));
    }


    private static Optional<com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton> visibleButton(net.minecraft.client.gui.screens.Screen screen, String key) {
        return screen.children().stream().filter(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::isInstance).map(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::cast)
            .filter(b -> b.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t && t.getKey().equals("create_biotech.biocomputing.flow_editor." + key)).findFirst();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void anEnabledExtensionPredicateSnapsIntoAConditionAndKeepsItsFixedBinding(int width) throws Exception {
        var draft = new FlowDraft(FlowDocumentBody.emptyAssembly());
        draft.setExtension("create_biotech:signals", 1L);
        draft.append(new FlowProgram.If("if", new FlowInput.Literal(new DataValue.BooleanValue(false))));
        draft.append(new FlowProgram.EndIf("end"));
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(draft.snapshot()));
        var screen = FlowCallScreenTest.editor(workspace, width);
        var before = workspace.snapshot();
        click(screen, "category_extensions");
        var block = button(screen, "extension_xbus_busy");
        var slot = FlowInlineScreenTest.slot(screen, "false", 0);
        var integer = button(screen, "extension_simple_level");
        screen.mouseClicked(integer.getX()+8, integer.getY()+8, 0);
        screen.mouseReleased(slot.x()+8, slot.y()+slot.height()/2, 0);
        assertEquals(before, workspace.snapshot(), "A wrong-type drop must not leave a hidden fixed definition");
        assertFalse(button(screen, "undo").active);
        screen.mouseClicked(block.getX()+8, block.getY()+8, 0);
        screen.mouseDragged(slot.x()+8, slot.y()+slot.height()/2, 0, 40, 0);
        screen.mouseReleased(slot.x()+8, slot.y()+slot.height()/2, 0);
        var edited = workspace.snapshot();
        assertNotEquals(before, edited);
        var roundTrip = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(edited));
        var graph = roundTrip.flow().orElseThrow().inline().get("if");
        var call = assertInstanceOf(ComputationGraph.ProcedureValue.class, graph.nodes().get(graph.outputs().get("value")));
        assertEquals("cb_extension:create_biotech:signals:xbus_busy", call.procedure().id());
        assertEquals(block.getMessage().getString(), InlineGraphView.layout(graph, String::length).label(),
            "Dropped reporters retain their translated labels, not internal extension identities");
        assertTrue(((FlowBlockCanvas)get(screen, "canvas")).blocks().stream().noneMatch(row -> row.step() instanceof FlowProgram.Procedure));
        var limits = new ResourceLimits(512, 65536, 8);
        roundTrip.validate(limits, com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins.DEFAULT);
        var disabled = new FlowDraft(roundTrip.flow().orElseThrow());
        disabled.setExtension("create_biotech:signals", null);
        var unavailable = new ProgramDocument(roundTrip.archive(), roundTrip.positions(), roundTrip.terminal(), Optional.of(disabled.snapshot()));
        assertEquals(unavailable, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(unavailable)));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> unavailable.validate(limits, com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins.DEFAULT))
            .getMessage().contains("not enabled"));
        var image = FlowXBus.image(roundTrip.flow().orElseThrow().program(roundTrip.archive()));
        assertTrue(image.code().stream().anyMatch(row -> row.instruction() instanceof
            com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterDevices.Instruction op && op.pin().equals("x0")));
        assertThrows(IllegalArgumentException.class, () -> roundTrip.validate(limits, new com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins(List.of(), List.of())));
        click(screen, "undo"); assertEquals(before, workspace.snapshot());
        click(screen, "redo"); assertEquals(edited, workspace.snapshot());
    }

    @Test void aTextExtensionReporterNestsInAScratchSlotAndReturnsTypedText() throws Exception {
        var text = DataType.Scalar.TEXT;
        var placeholder = new DataValue.TextValue("unset");
        var draft = new FlowDraft(FlowDocumentBody.emptyAssembly());
        draft.setExtension("create_biotech:text", 1L);
        draft.append(new FlowProgram.Shared("write", "message", new FlowInput.Literal(placeholder), false));
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of("message", new ProgramDefinition.Cell(text, placeholder)), List.of(), List.of()), Map.of());
        var workspace = new ProgramWorkspace(new ProgramDocument(archive, Map.of(), Optional.empty(), Optional.of(draft.snapshot())));
        var screen = FlowCallScreenTest.editor(workspace, 640);
        click(screen, "category_extensions");
        search(screen).setValue("text_label");
        var reporter = button(screen, "extension_text_label");
        var slot = FlowInlineScreenTest.slot(screen, "\"unset\"", 0);
        assertTrue(screen.mouseClicked(reporter.getX() + 8, reporter.getY() + 8, 0));
        screen.mouseDragged(slot.x() + 8, slot.y() + slot.height() / 2, 0, 40, 0);
        assertTrue(screen.mouseReleased(slot.x() + 8, slot.y() + slot.height() / 2, 0));
        FlowInlineScreenTest.setLiteral(screen, FlowInlineScreenTest.slot(screen, "\"ready\"", 0), "active");
        var document = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot()));
        var program = document.flow().orElseThrow().program(document.archive());
        var hardware = new ResourceLimits(512, 65_536, 4);
        var ordinary = new ComputerHostLimits(hardware, SubgraphWorkspaceTest.LIMITS);
        var parser = new ComputerHostLimits(hardware, ordinary.archive(), ordinary.mailbox(), ordinary.pins(), ordinary.serial(), 1, true);
        var ordinaryHost = new ComputerBlockHost(() -> {});
        document.validate(hardware, parser.pins());
        var inputs = new DataValue.RecordValue(Map.of());
        var unsupported = assertThrows(FlowPins.Unavailable.class, () -> ordinaryHost.installFlow(program, inputs, ordinary, true));
        assertTrue(unsupported.getMessage().contains("ISA-1a"), unsupported.getMessage());
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(program, inputs, parser, true);
        host.setPaused(false, parser, true);
        boolean restored = false;
        for (int tick = 0; tick < 100; tick++) {
            host.tick(tick, parser, true, Map.of());
            var state = host.flowSnapshot().orElseThrow();
            if (state.state().assembly().orElseThrow().build().isPresent()) {
                assertEquals(placeholder, state.memory().cells().field("message"), "Never publish a partial string");
                if (!restored) {
                    var reopened = new ComputerBlockHost(() -> {});
                    reopened.read(host.write());
                    reopened.tick(parser, true);
                    assertTrue(reopened.loaded());
                    host = reopened;
                    restored = true;
                }
            }
            if (state.state().status() == FlowRuntime.Status.COMPLETE) break;
        }
        var result = host.flowSnapshot().orElseThrow();
        assertTrue(restored, "Text construction should take more than one tick and survive a save mid-call");
        assertEquals(FlowRuntime.Status.COMPLETE, result.state().status(), result.state().diagnostic().toString());
        assertEquals(new DataValue.TextValue("Status: active"), result.memory().cells().field("message"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void extensionMouthSnapsAndRunsOnlyTheSelectedBody(boolean twoBodies) throws Exception {
        var draft = new FlowDraft(FlowDocumentBody.emptyAssembly());
        draft.setExtension("create_biotech:signals", 1L);
        var zero = new DataValue.IntegerValue(0); var one = new DataValue.IntegerValue(1);
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of("hits", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, zero)), List.of(), List.of()), Map.of());
        var workspace = new ProgramWorkspace(new ProgramDocument(archive, Map.of(), Optional.empty(), Optional.of(draft.snapshot())));
        var screen = FlowCallScreenTest.editor(workspace, 640);
        set(screen, "height", 360);
        screen.init();
        var canvas = (FlowBlockCanvas)get(screen, "canvas");
        int x = (int)get(canvas,"x"), y = (int)get(canvas,"y");
        click(screen, "category_extensions");
        String command = twoBodies ? "if_signal_else" : "if_signal";
        search(screen).setValue(command);
        var block = button(screen, "extension_" + command);
        var main = canvas.joints().getFirst();
        screen.mouseClicked(block.getX()+8, block.getY()+8, 0);
        screen.mouseDragged(x+main.x()+8, y+main.y()+10, 0, 40, 0);
        screen.mouseReleased(x+main.x()+8, y+main.y()+10, 0);
        var placed = workspace.snapshot();
        var placedFlow = placed.flow().orElseThrow();
        assertInstanceOf(FlowProgram.If.class, placedFlow.steps().getFirst());
        assertEquals(new DataValue.IntegerValue(1),
            placedFlow.inline().values().iterator().next().nodes().values().stream()
                .filter(ComputationGraph.Constant.class::isInstance).map(ComputationGraph.Constant.class::cast).findFirst().orElseThrow().value());
        assertInstanceOf(FlowProgram.EndIf.class, placedFlow.steps().getLast());
        assertEquals(twoBodies, placedFlow.steps().stream().anyMatch(FlowProgram.Otherwise.class::isInstance));
        var cavity = canvas.joints().stream().filter(j -> j.socket().index() == 1 && j.opening() > 0).findFirst().orElseThrow();
        click(screen, "undo"); assertTrue(workspace.flow().orElseThrow().steps().isEmpty());
        click(screen, "redo"); assertEquals(placed, workspace.snapshot());
        search(screen).setValue("public_add");
        var add = button(screen, "add_public_add");
        screen.mouseClicked(add.getX()+8, add.getY()+8, 0);
        screen.mouseDragged(x+cavity.x()+8, y+cavity.y()+10, 0, 40, 0);
        screen.mouseReleased(x+cavity.x()+8, y+cavity.y()+10, 0);
        if (twoBodies) {
            var alternate = canvas.joints().stream().filter(j -> j.socket().index() == 3 && j.opening() > 0).findFirst().orElseThrow();
            add = button(screen, "add_public_add");
            screen.mouseClicked(add.getX()+8, add.getY()+8, 0);
            screen.mouseDragged(x+alternate.x()+8, y+alternate.y()+10, 0, 40, 0);
            screen.mouseReleased(x+alternate.x()+8, y+alternate.y()+10, 0);
            var second = assertInstanceOf(FlowProgram.Shared.class, workspace.flow().orElseThrow().steps().get(3));
            var literal = canvas.inlineSlots().stream().filter(slot -> slot.call().equals(second.sourceId()) && slot.label().equals("1"))
                .findFirst().orElseThrow();
            FlowInlineScreenTest.setLiteral(screen, literal, "10");
        }
        var document = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot()));
        var savedSteps = document.flow().orElseThrow().steps();
        assertEquals(twoBodies ? 5 : 3, savedSteps.size());
        assertInstanceOf(FlowProgram.Shared.class, savedSteps.get(1));
        var limits = new ComputerHostLimits(new ResourceLimits(512, 65_536, 16), SubgraphWorkspaceTest.LIMITS,
            MailboxLimits.DEFAULT, HardwarePins.DEFAULT, SerialHardware.NONE, 1);
        document.validate(limits.hardware(), limits.pins());
        if (twoBodies) {
            var body = document.flow().orElseThrow();
            var removed = body.steps().stream().filter(s -> !(s instanceof FlowProgram.Otherwise)).toList();
            var invalid = new ProgramDocument(document.archive(), document.positions(), document.terminal(),
                Optional.of(body.edited(body.inputs(), removed, body.initial(), body.canvas(), body.inline())));
            assertThrows(IllegalArgumentException.class, invalid::validateExtensions, "the fixed two-mouth block must have an else connector");
            // The first use of a shared archive entry must not authorize all later uses.
            var lowered = body.program(document.archive());
            var reused = new ArrayList<>(lowered.steps().subList(0, body.steps().size()));
            reused.add(new FlowProgram.If("reuse", ((FlowProgram.If)reused.getFirst()).condition()));
            reused.add(new FlowProgram.Otherwise("reuse_else"));
            reused.add(new FlowProgram.EndIf("reuse_end"));
            new ProgramDocument(lowered.expressions(), Map.of(), Optional.empty(), Optional.of(
                body.edited(body.inputs(), reused, body.initial(), body.canvas(), Map.of()))).validateExtensions();
            reused.removeIf(step -> step.sourceId().equals("reuse_else"));
            var wrongReuse = new ProgramDocument(lowered.expressions(), Map.of(), Optional.empty(), Optional.of(
                body.edited(body.inputs(), reused, body.initial(), body.canvas(), Map.of())));
            assertThrows(IllegalArgumentException.class, wrongReuse::validateExtensions);
            var fixed = FlowProcedureTools.definitions(body).getFirst();
            var bare = new FlowProgram.Invoke("bare_condition", fixed.key(),
                new FlowCallBindings(Map.of("threshold", "threshold"), Map.of()));
            var direct = new ProgramDocument(document.archive(), document.positions(), document.terminal(),
                Optional.of(body.edited(body.inputs(), List.of(bare), body.initial(), body.canvas(), Map.of())));
            var reopened = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(direct));
            assertEquals(direct, reopened, "an invalid draft must remain editable");
            assertThrows(IllegalArgumentException.class, reopened::validateExtensions, "a condition cannot be invoked as a statement");
        }
        for (int signal : List.of(0, 15)) {
            var host = new ComputerBlockHost(() -> {});
            host.installFlow(document.flow().orElseThrow().program(document.archive()), new DataValue.RecordValue(Map.of()), limits, true);
            host.setPaused(false, limits, true);
            for (long tick = 0; tick < 8; tick++) host.tick(tick, limits, true, Map.of("p0", signal));
            assertEquals(signal == 15 ? one : twoBodies ? new DataValue.IntegerValue(10) : zero,
                host.flowSnapshot().orElseThrow().memory().cells().field("hits"));
        }
    }

    @Test void extensionLoopRechecksTheSignalAndLeavesOnlyWhenThePredicateStopsMatching() throws Exception {
        var draft = new FlowDraft(FlowDocumentBody.emptyAssembly());
        draft.setExtension("create_biotech:signals", 1L);
        var zero = new DataValue.IntegerValue(0);
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of("hits", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, zero)), List.of(), List.of()), Map.of());
        var workspace = new ProgramWorkspace(new ProgramDocument(archive, Map.of(), Optional.empty(), Optional.of(draft.snapshot())));
        var screen = FlowCallScreenTest.editor(workspace, 640);
        set(screen, "height", 360);
        screen.init();
        var canvas = (FlowBlockCanvas)get(screen, "canvas");
        search(screen).setValue("while_signal");
        var loop = button(screen, "extension_while_signal");
        dragToJoint(screen, canvas, loop, canvas.joints().getFirst());
        var placed = workspace.flow().orElseThrow();
        assertInstanceOf(FlowProgram.Until.class, placed.steps().getFirst());
        assertInstanceOf(FlowProgram.EndUntil.class, placed.steps().getLast());
        assertFalse(canvas.inlineSlots().isEmpty(), "The threshold remains an editable slot");
        assertTrue(canvas.inlineSlots().stream().allMatch(slot -> slot.label().equals("1")),
            "The automatically negated condition and the fixed predicate are not editable slots");
        search(screen).setValue("public_add");
        dragToJoint(screen, canvas, button(screen, "add_public_add"), canvas.joints().stream()
            .filter(joint -> joint.socket().index() == 1 && joint.opening() > 0).findFirst().orElseThrow());
        var document = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot()));
        var flow = document.flow().orElseThrow();
        assertInstanceOf(FlowProgram.Shared.class, flow.steps().get(1));
        var limits = new ComputerHostLimits(new ResourceLimits(512, 65_536, 16), SubgraphWorkspaceTest.LIMITS,
            MailboxLimits.DEFAULT, HardwarePins.DEFAULT, SerialHardware.NONE, 1);
        document.validate(limits.hardware(), limits.pins());
        assertThrows(IllegalArgumentException.class, () -> document.validate(limits.hardware(), new HardwarePins(List.of(), List.of())));
        var until = (FlowProgram.Until) flow.steps().getFirst();
        var wrongWrapper = flow.edited(flow.inputs(), List.of(new FlowProgram.If(until.sourceId(), until.condition()),
            flow.steps().get(1), new FlowProgram.EndIf(flow.steps().getLast().sourceId())), flow.initial(), flow.canvas(), flow.inline());
        var wrongDocument = new ProgramDocument(document.archive(), document.positions(), document.terminal(), Optional.of(wrongWrapper));
        assertThrows(IllegalArgumentException.class, wrongDocument::validateExtensions);
        for (int signal : List.of(0, 15)) {
            var host = new ComputerBlockHost(() -> {});
            host.installFlow(flow.program(document.archive()), new DataValue.RecordValue(Map.of()), limits, true);
            host.setPaused(false, limits, true);
            for (long tick = 0; tick < 8; tick++) host.tick(tick, limits, true, Map.of("p0", signal));
            var before = (DataValue.IntegerValue)host.flowSnapshot().orElseThrow().memory().cells().field("hits");
            if (signal == 0) assertEquals(zero, before, "A false first predicate must skip the entire body");
            else assertTrue(before.value() > 1, "An active signal must allow more than one iteration");
            var restored = new ComputerBlockHost(() -> {});
            restored.read(host.write());
            for (long tick = 8; tick < 16; tick++) {
                host.tick(tick, limits, true, Map.of("p0", 0));
                restored.tick(tick, limits, true, Map.of("p0", 0));
                assertEquals(host.flowSnapshot(), restored.flowSnapshot(), "Saving inside the loop must preserve the continuation");
            }
            var stopped = restored.flowSnapshot().orElseThrow();
            assertEquals(FlowRuntime.Status.COMPLETE, stopped.state().status());
            for (long tick = 16; tick < 24; tick++) restored.tick(tick, limits, true, Map.of("p0", 15));
            assertEquals(stopped.memory(), restored.flowSnapshot().orElseThrow().memory(), "Raising p0 again does not re-arm a completed script");
        }
    }

    private static void dragToJoint(ComputerFlowScreen screen, FlowBlockCanvas canvas,
                                    net.minecraft.client.gui.components.AbstractWidget block, FlowBlockCanvas.Joint joint) throws Exception {
        double x = (int)get(canvas,"x") + joint.x() + 8, y = (int)get(canvas,"y") + joint.y() + 10;
        screen.mouseClicked(block.getX()+8, block.getY()+8, 0);
        screen.mouseDragged(x, y, 0, 40, 0);
        screen.mouseReleased(x, y, 0);
    }
}
