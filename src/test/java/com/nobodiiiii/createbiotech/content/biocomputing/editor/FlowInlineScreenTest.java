package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.api.*;
import net.minecraft.network.chat.Component;

class FlowInlineScreenTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(FlowProgram.Compilation.class)
    void sampledReporterRetainsOnlyItsOwnPriorReadingAcrossLoopAndOwnerReload(FlowProgram.Compilation policy) {
        var zero = new DataValue.IntegerValue(0);
        var one = new DataValue.IntegerValue(1);
        var input = Map.<String,DataType>of("x", DataType.Scalar.INTEGER);
        var edge = new ComputationGraph(input, Map.of(
            "x", new ComputationGraph.Input("x"), "threshold", new ComputationGraph.Constant(one),
            "test", new ComputationGraph.Scalar(ScalarOperation.SAMPLE_RISING_INTEGER, Map.of("current","x","threshold","threshold"))
        ), Map.of("value","test"));
        var increment = new ComputationGraph(input, Map.of(
            "x", new ComputationGraph.Input("x"), "one", new ComputationGraph.Constant(one),
            "sum", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER,"x","one")
        ), Map.of("value","sum"));
        var work = new ProgramWorkspace();
        work.setFlow(Optional.of(new FlowDocumentBody(Map.of("reading", DataType.Scalar.INTEGER, "hits", DataType.Scalar.INTEGER),
            List.of(new FlowProgram.Repeat("loop", new FlowInput.Literal(new DataValue.IntegerValue(4))),
                new FlowProgram.Evaluate("sample","expr",new FlowCallBindings(Map.of("x","reading"),Map.of("value","pulse"))),
                new FlowProgram.If("if","pulse"),
                new FlowProgram.Evaluate("count","expr",new FlowCallBindings(Map.of("x","hits"),Map.of("value","hits"))),
                new FlowProgram.EndIf("endif"),
                new FlowProgram.Evaluate("advance","expr",new FlowCallBindings(Map.of("x","reading"),Map.of("value","reading"))),
                new FlowProgram.EndRepeat("end")),
            new DataValue.RecordValue(Map.of("reading",zero,"hits",zero)), FlowBlockLayout.DEFAULT,
            Map.of("sample",edge,"count",increment,"advance",increment), FlowProgram.Execution.REGISTER_V1, policy)));
        var document = work.snapshot();
        assertEquals(document, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        var locals = run(document);
        assertEquals(new DataValue.IntegerValue(4), locals.field("reading"));
        assertEquals(one, locals.field("hits"), "Only 1 -> 2 crosses the > 1 boundary");
        assertOwnerRecovery(document);
    }

    @Test void mathFunctionDropdownKeepsItsNestedInputThroughSwitchUndoAndSave() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var editor = FlowCallScreenTest.editor(workspace, 640);
        click(editor, "category_variables"); click(editor, "add_assign");
        FlowPaletteScreenTest.search(editor).setValue("mathop");
        dropReporter(editor, "inline_mathop", slot(editor, "0", 0));
        FlowPaletteScreenTest.search(editor).setValue("add_decimal");
        var plus = FlowBlockCatalog.label(new InlineGraphEdits.Operator(ScalarOperation.ADD_DECIMAL));
        var prototype = editor.children().stream().filter(child ->
            child instanceof com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton button && button.getMessage().equals(plus))
            .map(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::cast).findFirst().orElseThrow();
        var target = slot(editor, "0.0", 0);
        editor.mouseClicked(prototype.getX() + 8, prototype.getY() + 8, 0);
        editor.mouseDragged(target.x() + 8, target.y() + 8, 0, 40, 0);
        editor.mouseReleased(target.x() + 8, target.y() + 8, 0);
        setLiteral(editor, slot(editor, "0.0", 0), "30.0");
        setLiteral(editor, slot(editor, "0.0", 0), "60.0");
        var abs = workspace.snapshot();
        var selector = ((FlowBlockCanvas)get(editor, "canvas")).inlineSlots().stream()
            .filter(s -> s.label().contains("▾") && !s.target()).findFirst().orElseThrow();
        editor.mouseClicked(selector.x() + 10, selector.y() + selector.height() / 2, 0);
        click(editor, "mathop_sin_degrees");
        var changed = workspace.snapshot();
        assertEquals(new DataValue.DecimalValue(1), run(changed).field("block_1_value"));
        click(editor, "undo"); assertEquals(abs, workspace.snapshot());
        click(editor, "redo"); assertEquals(changed, workspace.snapshot());
        var restored = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(changed));
        assertEquals(changed, restored);
        assertEquals(new DataValue.DecimalValue(1), run(restored).field("block_1_value"));
        var body = restored.flow().orElseThrow();
        var image = FlowXBus.image(body.program(restored.archive()));
        assertEquals(Set.of(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.InstructionFamily.ISA_1), image.instructionFamilies());
    }

    @Test void sampledEdgeReporterRemembersNothingOnFirstExecutionAndTogglesInPlace() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptySoftwareAssembly()));
        var editor = FlowCallScreenTest.editor(workspace, 640);
        click(editor, "category_variables"); click(editor, "add_assign");
        dropReporter(editor, "inline_sample_rising_integer", slot(editor, "0", 0));
        setLiteral(editor, slot(editor, "0", 0), "7");
        var before = workspace.snapshot();
        var canvas = (FlowBlockCanvas)get(editor,"canvas");
        var label = net.minecraft.network.chat.Component.translatable(
            "create_biotech.biocomputing.flow_editor.inline_sample_rising_integer").getString();
        var reporter = canvas.inlineSlots().stream().filter(s -> s.label().startsWith(label)).findFirst().orElseThrow();
        editor.mouseClicked(reporter.x() + 6, reporter.y() + reporter.height() / 2, 1);
        click(editor, "edge_to_falling");
        var changed = workspace.snapshot();
        assertEquals(changed, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(changed)));
        var graph = changed.flow().orElseThrow().inline().values().iterator().next();
        var sample = assertInstanceOf(ComputationGraph.Scalar.class, graph.nodes().get(graph.outputs().get("value")));
        assertEquals(ScalarOperation.SAMPLE_FALLING_INTEGER, sample.operation());
        assertEquals(new ComputationGraph.Constant(new DataValue.IntegerValue(7)), graph.nodes().get(sample.arguments().get("current")));
        assertEquals(new DataValue.BooleanValue(false), run(changed).field("block_1_value"));
        click(editor, "undo");
        assertEquals(before, workspace.snapshot());
    }

    @Test void numericEdgeReporterCombinesWithLogicAndTogglesWithoutLosingItsOperands() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptySoftwareAssembly()));
        var editor = FlowCallScreenTest.editor(workspace, 640);
        click(editor, "category_variables"); click(editor, "add_assign");
        dropReporter(editor, "inline_or_boolean", slot(editor, "0", 0));
        dropReporter(editor, "inline_edge_rising_integer", slot(editor, "false", 0));
        setLiteral(editor, slot(editor, "0", 1), "2");
        var before = workspace.snapshot();
        assertEquals(new DataValue.BooleanValue(true), run(before).field("block_1_value"));
        var reporter = ((FlowBlockCanvas)get(editor,"canvas")).inlineSlots().stream()
            .filter(s -> s.label().startsWith(net.minecraft.network.chat.Component.translatable("create_biotech.biocomputing.flow_editor.inline_edge_rising_integer").getString())).findFirst().orElseThrow();
        editor.mouseClicked(reporter.x() + 6, reporter.y() + reporter.height() / 2, 1);
        click(editor, "edge_to_falling");
        var edited = workspace.snapshot();
        var reopened = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(edited));
        assertEquals(edited, reopened);
        assertEquals(new DataValue.BooleanValue(false), run(reopened).field("block_1_value"));
        click(editor, "undo");
        assertEquals(before, workspace.snapshot());
    }


    @Test void blankCanvasPanUsesTheFinalPointerEvenWhenDragEventsAreCoalesced() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        var editor = FlowCallScreenTest.editor(workspace, 640);
        click(editor, "category_variables"); click(editor, "add_assign");
        var canvas = (FlowBlockCanvas)get(editor, "canvas"); var before = workspace.snapshot();
        double x = (int)get(canvas,"x") + 3, y = (int)get(canvas,"y") + 3;
        for (boolean intermediate : List.of(false, true)) {
            canvas.resetView(); double originalX = slot(editor,"0",0).x();
            assertTrue(editor.mouseClicked(x,y,0));
            if (intermediate) assertTrue(editor.mouseDragged(x-10,y,0,-10,0));
            assertTrue(editor.mouseReleased(x-40,y,0));
            assertEquals(originalX-40, slot(editor,"0",0).x(), "The release position must finish, not lose or double-count, the pan");
            assertEquals(before,workspace.snapshot(), "Panning must not edit the program or canvas stack positions");
        }
    }

    @Test void compactAssignmentLeavesADragSurfaceAndControlShellCoversItsWidestChild() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        var editor = FlowCallScreenTest.editor(workspace, 640);
        click(editor, "category_variables"); click(editor, "add_assign");
        var canvas = (FlowBlockCanvas)get(editor, "canvas");
        var target = canvas.inlineSlots().stream().filter(FlowBlockCanvas.Slot::target).findFirst().orElseThrow();
        var value = slot(editor, "0", 0);
        assertEquals(target.y() + target.height()/2, value.y() + value.height()/2,
            "A simple assignment belongs on one line, not a title plus a second expression row");
        assertTrue(target.x() + target.width() <= value.x(), "Only the variable chip should rename the target");
        assertNotNull(canvas.blockAt(value.x() + value.width() + 4, value.y() + value.height()/2));
        assertNull(canvas.inlineHit(value.x() + value.width() + 4, value.y() + value.height()/2),
            "The remaining block surface is a drag handle, not an oversized rename field");

        // Long nested contents must resize both the opening and closing bar, not escape the C shell.
        var body = workspace.flow().orElseThrow();
        var nested = body.edited(body.inputs(), List.of(
            new FlowProgram.Until("loop", new FlowInput.Literal(new DataValue.BooleanValue(false))),
            body.steps().getFirst(), new FlowProgram.EndUntil("end")), body.initial(), body.canvas(), body.inline());
        var draft = new FlowDraft(nested);
        var wide = new FlowBlockCanvas(draft, () -> {}); wide.bounds(0, 0, 1600, 500);
        var steps = wide.blocks(); var child = steps.get(1);
        double insideChildRight = child.x() + child.width() - 3;
        assertEquals("loop", wide.blockAt(insideChildRight, steps.getFirst().y() + 8).step().sourceId());
        assertEquals("end", wide.blockAt(insideChildRight, steps.getLast().y() + 5).step().sourceId());
    }

    @Test void infixExpressionStartsWithAnEditableOperandAndKeepsNestedDropTargets() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        var editor = FlowCallScreenTest.editor(workspace, 640);
        click(editor, "category_variables"); click(editor, "add_assign");
        dropReporter(editor, "inline_subtract_integer", slot(editor, "0", 0));
        var canvas = (FlowBlockCanvas)get(editor, "canvas");
        var sum = slot(editor, "−", 0);
        var left = canvas.inlineHit(sum.x() + 8, sum.y() + sum.height() / 2);
        assertNotNull(left);
        assertEquals("0", left.label(), "Read and edit the expression left-to-right, not operator-first");
        setLiteral(editor, left, "7");
        dropReporter(editor, "inline_multiply_integer", slot(editor, "0", 0));
        setLiteral(editor, slot(editor, "0", 0), "2");
        setLiteral(editor, slot(editor, "0", 0), "3");
        assertEquals(new DataValue.IntegerValue(1), run(workspace.snapshot()).field("block_1_value"));
    }

    @Test void waitUntilIsASingleStatementWithAnEditableBooleanSocket() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var editor = FlowCallScreenTest.editor(workspace,640);
        FlowPaletteScreenTest.search(editor).setValue("wait_until");
        var wait = button(editor,"add_wait_until"); var canvas=get(editor,"canvas");
        int x=(int)get(canvas,"x")+24,y=(int)get(canvas,"y")+50;
        editor.mouseClicked(wait.getX()+8,wait.getY()+8,0); editor.mouseDragged(x,y,0,100,20); editor.mouseReleased(x,y,0);
        var first=workspace.snapshot();
        assertInstanceOf(FlowProgram.WaitUntil.class, first.flow().orElseThrow().steps().getFirst());
        assertEquals(1,first.flow().orElseThrow().steps().size());
        dropReporter(editor,"inline_less_integer",slot(editor,"false",0));
        setLiteral(editor,slot(editor,"0",0),"2"); setLiteral(editor,slot(editor,"0",0),"3");
        var document=workspace.snapshot();
        assertEquals(new DataValue.IntegerValue(41),ProgramDocumentCodec.encode(document).field("version"));
        assertEquals(document,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        assertEquals(new DataValue.RecordValue(Map.of()), run(document)); // A true condition never enters the sleep path.
        for(int i=0;i<3;i++) click(editor,"undo");
        assertEquals(first,workspace.snapshot());
    }

    @Test void parkingAStoppedTailClearsNeighboringStartupScriptWithoutMovingIt() throws Exception {
        var tail = new FlowProgram.WaitUntil("tail", new FlowInput.Literal(new DataValue.BooleanValue(true)));
        var boot = new FlowBlockLayout.Stack(new ProgramDocument.Position(26, 100), List.of(
            FlowProgram.Procedure.startupHat("boot", new ModuleDefinition.Key("boot", 1)),
            new FlowProgram.WaitUntil("boot_wait", new FlowInput.Literal(new DataValue.BooleanValue(true))),
            new FlowProgram.EndProcedure("boot_end")));
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(new FlowDocumentBody(
            Map.of(),List.of(tail),new DataValue.RecordValue(Map.of()),
            FlowBlockLayout.DEFAULT.arranged(FlowBlockLayout.DEFAULT.main(), List.of(boot)),
            Map.of(),FlowProgram.Execution.REGISTER_V1)));
        var editor=FlowCallScreenTest.editor(workspace,640); var before=workspace.snapshot();
        FlowPaletteScreenTest.search(editor).setValue("stop_script");
        var palette=button(editor,"add_stop_script"); var canvas=(FlowBlockCanvas)get(editor,"canvas");
        int x=(int)get(canvas,"x")+24,y=(int)get(canvas,"y")+50;
        editor.mouseClicked(palette.getX()+8,palette.getY()+8,0);
        editor.mouseDragged(x,y,0,100,20); editor.mouseReleased(x,y,0);

        var after=workspace.snapshot();
        var stacks=after.flow().orElseThrow().canvas().detached();
        assertEquals(boot,stacks.getFirst());
        assertEquals(List.of(tail),stacks.getLast().steps());
        var rows=canvas.blocks();
        var bottom=rows.stream().filter(row -> "boot".equals(row.stack()) && row.height()>0)
            .mapToDouble(row -> row.y()+row.height()).max().orElseThrow();
        assertTrue(stacks.getLast().position().y()-12 > bottom, "Keep the detached block and its caption clear of the neighboring script");
        click(editor,"undo"); assertEquals(before,workspace.snapshot());
        click(editor,"redo"); assertEquals(after,workspace.snapshot());
    }
    @Test void sensingTimerCanSnapIntoAValueSlotAndSurviveDocumentRoundTrip() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var editor = FlowCallScreenTest.editor(workspace, 640);
        click(editor, "category_variables"); click(editor, "add_assign");
        var target = slot(editor, "0", 0);
        FlowPaletteScreenTest.search(editor).setValue("timer_read");
        var timer = button(editor, "inline_timer");
        editor.mouseClicked(timer.getX()+8, timer.getY()+8, 0);
        editor.mouseDragged(target.x()+8, target.y()+8, 0, 40, 0);
        editor.mouseReleased(target.x()+8, target.y()+8, 0);
        var draft = workspace.snapshot();
        assertEquals(draft, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(draft)));
        assertTrue(draft.flow().orElseThrow().inline().values().stream().flatMap(g -> g.nodes().values().stream())
            .anyMatch(ComputationGraph.Timer.class::isInstance));
    }

    @Test void changingStopOthersToStopAllDetachesTheTailAndUndoRestoresTheConnection() throws Exception {
        var stop = new FlowProgram.StopScript("stop", FlowProgram.StopMode.OTHERS);
        var tail = new FlowProgram.WaitUntil("tail", new FlowInput.Literal(new DataValue.BooleanValue(true)));
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(new FlowDocumentBody(
            Map.of(),List.of(stop, tail),new DataValue.RecordValue(Map.of()),FlowBlockLayout.DEFAULT,Map.of(),FlowProgram.Execution.REGISTER_V1)));
        var editor = FlowCallScreenTest.editor(workspace,640); var before = workspace.snapshot();
        var canvas = (FlowBlockCanvas)get(editor,"canvas"); var block = canvas.blocks().getFirst();
        var x = (int)get(canvas,"x") + block.x() + block.width() - 12;
        var y = (int)get(canvas,"y") + block.y() + block.height() / 2;
        assertTrue(canvas.joints().stream().anyMatch(joint -> joint.socket().stack()==null && joint.socket().index()==1));
        assertEquals(stop,canvas.stopMenuAt(x,y));
        assertTrue(editor.mouseClicked(x,y,0));
        click(editor,"stop_mode_all");
        var changed = workspace.snapshot(); var body = changed.flow().orElseThrow();
        assertEquals(List.of(new FlowProgram.StopScript("stop", FlowProgram.StopMode.ALL)),body.steps());
        assertEquals(List.of(tail),body.canvas().detached().getFirst().steps());
        assertFalse(canvas.joints().stream().anyMatch(joint -> joint.socket().stack()==null && joint.socket().index()==1));
        var encoded = ProgramDocumentCodec.encode(changed);
        assertEquals(new DataValue.IntegerValue(43),encoded.field("version"));
        assertEquals(changed,ProgramDocumentCodec.decode(encoded));
        click(editor,"undo"); assertEquals(before,workspace.snapshot());
    }
    @Test void snappingStopScriptParksTheTailAndLeavesNoBottomSocket() throws Exception {
        var tail = List.<FlowProgram.Step>of(
            new FlowProgram.WaitUntil("first", new FlowInput.Literal(new DataValue.BooleanValue(true))),
            new FlowProgram.WaitUntil("second", new FlowInput.Literal(new DataValue.BooleanValue(true))));
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(new FlowDocumentBody(
            Map.of(),tail,new DataValue.RecordValue(Map.of()),FlowBlockLayout.DEFAULT,Map.of(),FlowProgram.Execution.REGISTER_V1)));
        var editor = FlowCallScreenTest.editor(workspace,640); var before = workspace.snapshot();
        FlowPaletteScreenTest.search(editor).setValue("stop_script");
        var stop = button(editor,"add_stop_script"); var canvas=(FlowBlockCanvas)get(editor,"canvas");
        int x=(int)get(canvas,"x")+24,y=(int)get(canvas,"y")+50;
        editor.mouseClicked(stop.getX()+8,stop.getY()+8,0); editor.mouseDragged(x,y,0,100,20); editor.mouseReleased(x,y,0);
        var document=workspace.snapshot(); var body=document.flow().orElseThrow();
        assertInstanceOf(FlowProgram.StopScript.class, body.steps().getFirst());
        assertEquals(1,body.steps().size());
        assertEquals(tail, body.canvas().detached().getFirst().steps());
        assertFalse(canvas.joints().stream().anyMatch(joint -> joint.socket().stack()==null && joint.socket().index()==1));
        assertEquals(new DataValue.IntegerValue(42),ProgramDocumentCodec.encode(document).field("version"));
        assertEquals(document,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        click(editor,"undo"); assertEquals(before,workspace.snapshot());
    }

    @Test void draggedSleepAcceptsAnInlineExpressionAndRestoresItsCapturedDelay() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptySoftwareAssembly()));
        var editor = FlowCallScreenTest.editor(workspace, 640);
        click(editor, "category_control");
        var palette = button(editor, "add_sleep");
        var canvas = (FlowBlockCanvas)get(editor, "canvas");
        double x = (int)get(canvas, "x") + 24, y = (int)get(canvas, "y") + 16 + FlowBlockCanvas.HAT + 10;
        editor.mouseClicked(palette.getX() + 8, palette.getY() + 8, 0);
        editor.mouseDragged(x, y, 0, 40, 40); editor.mouseReleased(x, y, 0);
        assertEquals(1, workspace.flow().orElseThrow().steps().size());
        assertTrue(workspace.flow().orElseThrow().inputs().isEmpty(), "Waiting must not require a magic ticks variable");
        assertEquals(FlowProgram.WaitUnit.SECONDS, ((FlowProgram.Sleep)workspace.flow().orElseThrow().steps().getFirst()).displayedUnit());
        ScratchInlineWaitParityTest.chooseUnit(editor, slot(editor, "1", 0), FlowProgram.WaitUnit.TICKS);
        dropReporter(editor, "inline_add_integer", slot(editor, "1", 0));
        // Inserting an operator preserves the existing 1 as one operand.
        setLiteral(editor, slot(editor, "0", 0), "2");
        assertFalse((boolean)get(editor, "parameters"));
        var document = workspace.snapshot();
        var encoded = (DataValue.RecordValue)ProgramDocumentCodec.encode(document);
        assertEquals(new DataValue.IntegerValue(51), encoded.field("version"));
        assertEquals(document, ProgramDocumentCodec.decode(encoded));
        var downgraded = new LinkedHashMap<>(encoded.fields());
        downgraded.put("version", new DataValue.IntegerValue(33));
        assertThrows(com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException.class,
            () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(downgraded)));
        var body = document.flow().orElseThrow();
        var limits = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(
            new ResourceLimits(200, 100000, 100), SubgraphWorkspaceTest.LIMITS);
        var host = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(() -> {});
        host.installFlow(body.program(document.archive()), body.initial(), limits, true);
        for (int steps = 0; host.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.SLEEPING; steps++) {
            assertTrue(steps < 20, "Step through the expression instructions before admitting Sleep");
            host.step(limits, true);
        }
        var sleeping = host.flowSnapshot().orElseThrow();
        assertEquals(FlowRuntime.Status.SLEEPING, sleeping.state().status());
        assertEquals(3, sleeping.state().sleepTicks());
        assertTrue(sleeping.state().work() > 1, "Expression evaluation is charged, not hidden inside Sleep");
        var restored = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(() -> {});
        restored.read(host.write()); restored.tick(limits, true);
        assertEquals(sleeping, restored.flowSnapshot().orElseThrow(), "Paused reload cannot consume time or recompute the expression");
        for (int remaining = 2; remaining >= 0; remaining--) {
            restored.step(limits, true);
            assertEquals(remaining, restored.flowSnapshot().orElseThrow().state().sleepTicks());
        }
        var awake = restored.flowSnapshot().orElseThrow().state();
        assertEquals(FlowRuntime.Status.READY, awake.status());
        assertEquals(sleeping.state().work(), awake.work(), "Elapsed ticks never re-evaluate the amount");
        restored.step(limits, true); // The original unit-aware ISA releases its temporary after Sleep.
        var complete = restored.flowSnapshot().orElseThrow().state();
        assertEquals(FlowRuntime.Status.COMPLETE, complete.status());
        assertEquals(awake.work() + 1, complete.work(), "Only the existing charged register release runs after waking");
    }

    @Test void variablePickerDistinguishesScopeAndRejectsInvalidTypesWithoutChangingDraft() throws Exception {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("n", DataType.Scalar.INTEGER), List.of(),
            new DataValue.RecordValue(Map.of("n", new DataValue.IntegerValue(2))),
            FlowBlockLayout.DEFAULT, Map.of(), FlowProgram.Execution.REGISTER_V1)));
        workspace.root().putCell("n", DataType.Scalar.INTEGER, new DataValue.IntegerValue(12));
        workspace.root().putCell("text", DataType.Scalar.TEXT, new DataValue.TextValue("not a number"));
        var editor = FlowCallScreenTest.editor(workspace, 640);
        click(editor, "category_variables"); click(editor, "add_assign");
        dropReporter(editor, "inline_add_integer", slot(editor, "0", 0));
        dropPrivateVariable(editor, "n", slot(editor, "0", 0));
        click(editor, "category_operators");
        var search = (net.minecraft.client.gui.components.EditBox)get(editor, "catalogSearch");
        editor.setFocused(search);
        var before = workspace.snapshot();
        var variable = slot(editor, "n", 0);
        editor.mouseClicked(variable.x() + 3, variable.y() + 3, 0);
        editor.mouseReleased(variable.x() + 3, variable.y() + 3, 0);
        assertTrue(editor.charTyped('x', 0));
        assertEquals("", search.getValue(), "Picker input must not leak into the covered search box");
        pickVariable(editor, new InlineGraphEdits.PublicVariable("text", List.of(), DataType.Scalar.TEXT));
        assertEquals(before, workspace.snapshot(), "An invalid nested operand must not change the draft");
        assertNotNull(get(editor, "variableSlot"));
        pickVariable(editor, new InlineGraphEdits.PublicVariable("n", List.of(), DataType.Scalar.INTEGER));
        assertNull(get(editor, "variableSlot"));
        var nodes = workspace.flow().orElseThrow().inline().values().iterator().next().nodes().values();
        assertTrue(nodes.stream().anyMatch(node -> node instanceof ComputationGraph.ReadMemory read && read.cell().equals("n")));
        assertTrue(nodes.stream().noneMatch(node -> node instanceof ComputationGraph.Input), "Same-name public variable is not a private argument");
        click(editor, "undo"); assertEquals(before, workspace.snapshot());
    }

    private static void pickVariable(ComputerFlowScreen editor, InlineGraphEdits.Source source) {
        var label = FlowBlockCatalog.label(source);
        var choice = editor.children().stream()
            .filter(c -> c instanceof com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton b && b.getMessage().equals(label))
            .map(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::cast).findFirst().orElseThrow();
        editor.mouseClicked(choice.getX() + 3, choice.getY() + 3, 0);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void clickingVariableOffersScopedChoicesWithoutLeavingCanvas(int width) throws Exception {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(
            Map.of("first", DataType.Scalar.INTEGER, "second", DataType.Scalar.INTEGER), List.of(),
            new DataValue.RecordValue(Map.of("first", new DataValue.IntegerValue(2), "second", new DataValue.IntegerValue(9))),
            FlowBlockLayout.DEFAULT, Map.of(), FlowProgram.Execution.REGISTER_V1)));
        var editor = FlowCallScreenTest.editor(workspace, width);
        click(editor, "category_variables"); click(editor, "add_assign");
        dropPrivateVariable(editor, "first", slot(editor, "0", 0));
        click(editor, "category_operators");
        var before = workspace.snapshot();
        var variable = slot(editor, "first", 0);
        editor.mouseClicked(variable.x() + 3, variable.y() + 3, 0);
        editor.mouseReleased(variable.x() + 3, variable.y() + 3, 0);
        var label = FlowBlockCatalog.label(new InlineGraphEdits.Variable("second", List.of(), DataType.Scalar.INTEGER));
        var choice = editor.children().stream()
            .filter(c -> c instanceof com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton b && b.getMessage().equals(label))
            .map(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::cast).findFirst().orElseThrow();
        assertEquals(before, workspace.snapshot(), "Opening the picker does not edit or execute");
        bounds(editor);
        editor.mouseClicked(choice.getX() + 3, choice.getY() + 3, 0);
        assertFalse((boolean)get(editor, "parameters"));
        assertEquals(new DataValue.IntegerValue(9), run(workspace.snapshot()).field("block_1_value"));
        click(editor, "undo"); assertEquals(before, workspace.snapshot());
        variable = slot(editor, "first", 0);
        editor.mouseClicked(variable.x() + 3, variable.y() + 3, 0);
        editor.mouseReleased(variable.x() + 3, variable.y() + 3, 0);
        editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0);
        assertEquals(before, workspace.snapshot());
        assertNull(get(editor, "variableSlot"));
    }

    @Test void leavingLiteralCommitsValidInputButKeepsInvalidInputUntilCorrectedOrCancelled() throws Exception {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var editor = FlowCallScreenTest.editor(workspace, 640);
        click(editor, "category_variables");
        click(editor, "add_assign");
        var before = workspace.snapshot();
        var value = slot(editor, "0", 0);
        editor.mouseClicked(value.x() + 3, value.y() + 3, 0);
        var field = (net.minecraft.client.gui.components.EditBox)get(editor, "inlineField");
        field.setValue("7");
        var category = button(editor, "category_operators");
        editor.mouseClicked(category.getX() + 3, category.getY() + 3, 0);
        assertNull(get(editor, "inlineField"));
        assertEquals(new DataValue.IntegerValue(7), run(workspace.snapshot()).field("block_1_value"));
        assertEquals(FlowBlockPalette.Category.OPERATORS, get(editor, "category"));
        click(editor, "undo");
        assertEquals(before, workspace.snapshot(), "Leaving a value creates one undoable edit");
        click(editor, "redo");
        var committed = workspace.snapshot();
        value = slot(editor, "7", 0);
        editor.mouseClicked(value.x() + 3, value.y() + 3, 0);
        field = (net.minecraft.client.gui.components.EditBox)get(editor, "inlineField");
        field.setValue("not an integer");
        category = button(editor, "category_variables");
        editor.mouseClicked(category.getX() + 3, category.getY() + 3, 0);
        assertSame(field, get(editor, "inlineField"));
        assertEquals("not an integer", field.getValue());
        assertEquals(committed, workspace.snapshot());
        assertEquals(FlowBlockPalette.Category.OPERATORS, get(editor, "category"));
        editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0);
        assertNull(get(editor, "inlineField"));
        assertEquals(committed, workspace.snapshot(), "Escape still explicitly cancels the edit");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "320,list", "426,list", "640,list", "320,record", "426,record", "640,record"
    })
    void constructorMembersDoNotCoverSearchAndClosingRestoresNormalLayout(int width, String kind) throws Exception {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var editor = FlowCallScreenTest.editor(workspace, width);
        click(editor, "category_variables");
        click(editor, "add_assign");
        String reporter = "inline_" + kind + "_make";
        dropReporter(editor, reporter, slot(editor, "0", 0));
        var before = workspace.snapshot();
        var normalSearch = (net.minecraft.client.gui.components.EditBox) get(editor, "catalogSearch");
        int normalWidth = normalSearch.getWidth();
        var head = slot(editor, net.minecraft.network.chat.Component.translatable(
            "create_biotech.biocomputing.flow_editor." + reporter).getString(), 0);
        editor.mouseClicked(head.x() + 3, head.y() + 3, 0);
        editor.mouseReleased(head.x() + 3, head.y() + 3, 0);
        bounds(editor);
        var search = (net.minecraft.client.gui.components.EditBox) get(editor, "catalogSearch");
        var members = button(editor, "construction_members");
        assertTrue(search.getWidth() > 0);
        assertTrue(search.getX() + search.getWidth() < members.getX(),
            "Search and the member heading need separate hit and drawing regions");
        // EditBox mouse clicks poll native Shift state; exercise text routing without a GLFW window.
        editor.setFocused(search);
        assertTrue(editor.charTyped('x', 0));
        assertEquals("x", search.getValue());
        assertEquals("x", get(editor, "searchQuery"));
        assertEquals(before, workspace.snapshot(), "Searching must not change the program");
        search.setValue("");
        click(editor, "construction_add");
        bounds(editor);
        click(editor, "construction_cancel");
        bounds(editor);
        assertEquals(normalWidth, ((net.minecraft.client.gui.components.EditBox) get(editor, "catalogSearch")).getWidth());
        assertEquals(before, workspace.snapshot());
    }

    @Test void modalConstructorRoutesOrdinaryFocusedTextKeysWithoutMutatingUnderlyingHistory() throws Exception {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var editor = FlowCallScreenTest.editor(workspace, 640);
        click(editor, "category_variables");
        click(editor, "add_assign");
        dropReporter(editor, "inline_record_make", slot(editor, "0", 0));
        var before = workspace.snapshot();
        var head = slot(editor, net.minecraft.network.chat.Component.translatable(
            "create_biotech.biocomputing.flow_editor.inline_record_make").getString(), 0);
        editor.mouseClicked(head.x() + 3, head.y() + 3, 0);
        editor.mouseReleased(head.x() + 3, head.y() + 3, 0);
        var key = boxes(editor).stream().filter(box -> box.getMessage().getContents()
            instanceof net.minecraft.network.chat.contents.TranslatableContents text
            && text.getKey().endsWith("construction_key")).findFirst().orElseThrow();
        var routed = new RoutedTextBox((net.minecraft.client.gui.Font) get(editor, "font"));
        editor.setFocused(routed);
        assertTrue(editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE, 0, 0));
        assertTrue(editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE, 0, 0));
        assertTrue(editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_A, 0, org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL));
        assertEquals(List.of(org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE, org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE,
            org.lwjgl.glfw.GLFW.GLFW_KEY_A), routed.keys);
        assertEquals(List.of(0, 0, org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL), routed.modifiers);
        // Native physical modifier state is intentionally not simulated by this route-only text box.
        assertTrue(editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_Z, 0, org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL));
        assertEquals(before, workspace.snapshot(), "Unhandled focused history key must not mutate the base");
        editor.setFocused(null);
        for (int blocked : List.of(org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE, org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE,
                org.lwjgl.glfw.GLFW.GLFW_KEY_Z)) {
            assertTrue(editor.keyPressed(blocked, 0, org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL));
        }
        button(editor, "undo").onClick(0, 0);
        assertEquals(before, workspace.snapshot());
        editor.setFocused(key);
        assertTrue(key.isFocused());
        key.moveCursorToEnd(false);
        assertTrue(editor.charTyped('X', 0));
        assertEquals("fieldX", key.getValue());
        key.setValue("renamed");
        assertEquals(before, workspace.snapshot(), "Real record key responder only edits the temporary draft");
        click(editor, "construction_apply");
        assertEquals(new DataValue.RecordValue(Map.of("renamed", new DataValue.IntegerValue(0))),
            run(workspace.snapshot()).field("block_1_value"));
        click(editor, "undo");
        assertEquals(before, workspace.snapshot());
    }

    private static final class RoutedTextBox extends net.minecraft.client.gui.components.EditBox {
        private final List<Integer> keys = new ArrayList<>();
        private final List<Integer> modifiers = new ArrayList<>();

        private RoutedTextBox(net.minecraft.client.gui.Font font) {
            super(font, 0, 0, 100, 18, net.minecraft.network.chat.Component.literal("ordinary text"));
        }

        @Override public boolean keyPressed(int key, int scan, int modifiers) {
            keys.add(key);
            this.modifiers.add(modifiers);
            return key != org.lwjgl.glfw.GLFW.GLFW_KEY_Z;
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "undo", "redo", "initials", "documents", "add_assign", "define_procedure",
        "declare_private", "declare_public", "declare_cell", "drag_assign", "keyboard_undo", "keyboard_delete"
    })
    void constructorTemporaryDraftIsModalForUnderlyingCommandsAndHistory(String action) throws Exception {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        // Disabled widgets legitimately fall through the real container handlers: supply their normal key bindings.
        var options = allocate(net.minecraft.client.Options.class);
        set(options, "touchscreen", net.minecraft.client.OptionInstance.createBoolean("test.touchscreen", false));
        set(options, "keyPickItem", new net.minecraft.client.KeyMapping("test.pick", com.mojang.blaze3d.platform.InputConstants.Type.MOUSE, 2, "test"));
        set(options, "keyInventory", new net.minecraft.client.KeyMapping("test.inventory", org.lwjgl.glfw.GLFW.GLFW_KEY_E, "test"));
        set(net.minecraft.client.Minecraft.getInstance(), "options", options);
        var editor = FlowCallScreenTest.editor(workspace, 640);
        click(editor, "category_variables");
        click(editor, "add_assign");
        dropReporter(editor, "inline_list_make", slot(editor, "0", 0));
        setLiteral(editor, slot(editor, "0", 0), "7");
        setLiteral(editor, slot(editor, "7", 0), "8");
        click(editor, "undo");
        var before = workspace.snapshot();
        openListMembers(editor);
        click(editor, "construction_add");
        if (action.equals("keyboard_undo") || action.equals("keyboard_delete")) {
            editor.keyPressed(action.equals("keyboard_undo") ? org.lwjgl.glfw.GLFW.GLFW_KEY_Z
                : org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE, 0, 2);
        } else {
            if (action.equals("define_procedure")) click(editor, "category_custom");
            if (action.startsWith("declare_")) {
                click(editor, action.equals("declare_cell") ? "category_storage" : "category_variables");
            }
            var control = button(editor, action.equals("drag_assign") ? "add_assign" : action);
            editor.mouseClicked(control.getX() + 8, control.getY() + 8, 0);
            if (action.equals("drag_assign")) {
                editor.mouseDragged(420, 150, 0, 100, 20);
                editor.mouseReleased(420, 150, 0);
            }
            // Also verify the real callback cannot mutate the base even if invoked directly.
            control.onClick(0, 0);
            assertFalse(control.active, "Underlying command must be visibly disabled in a modal draft");
        }
        assertEquals(before, workspace.snapshot(), "Temporary edit must not mutate its captured base");
        assertFalse((boolean) get(editor, "initials"));
        assertFalse((boolean) get(editor, "parameters"));
        assertNotNull(get(editor, "construction"));
        click(editor, "construction_cancel");
        assertEquals(before, workspace.snapshot());
        openListMembers(editor);
        click(editor, "construction_add");
        click(editor, "construction_apply");
        var applied = workspace.snapshot();
        assertEquals(before.flow().orElseThrow().steps(), applied.flow().orElseThrow().steps());
        assertEquals(new DataValue.ListValue(List.of(new DataValue.IntegerValue(7), new DataValue.IntegerValue(0))),
            run(applied).field("block_1_value"));
        click(editor, "undo");
        assertEquals(before, workspace.snapshot(), "Apply contributes exactly one existing history entry");
        click(editor, "redo");
        assertEquals(applied, workspace.snapshot());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2, 3})
    void nestedConstructorCallbacksKeepChildAndParentDraftsSeparateAndReorderRemoveWork(int choices) throws Exception {
        boolean applyChild = (choices & 1) != 0;
        boolean applyParent = (choices & 2) != 0;
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var editor = FlowCallScreenTest.editor(workspace, 640);
        click(editor, "category_variables");
        click(editor, "add_assign");
        dropReporter(editor, "inline_list_make", slot(editor, "0", 0));
        setLiteral(editor, slot(editor, "0", 0), "7");
        var before = workspace.snapshot();
        openListMembers(editor);
        click(editor, "construction_add");
        click(editor, "construction_up");
        assertEquals(List.of(new DataValue.IntegerValue(0), new DataValue.IntegerValue(7)),
            constructorConstants(editor));
        click(editor, "construction_previous");
        click(editor, "construction_down");
        assertEquals(List.of(new DataValue.IntegerValue(7), new DataValue.IntegerValue(0)),
            constructorConstants(editor));
        click(editor, "construction_remove");
        assertEquals(List.of(new DataValue.IntegerValue(0)), constructorConstants(editor));
        var member = constructorMember(editor);
        editor.mouseClicked(member.getX() + 3, member.getY() + 3, 0);
        ((net.minecraft.client.gui.components.EditBox) get(editor, "inlineField")).setValue("9");
        editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0);
        member = constructorMember(editor);
        dropReporter(editor, "inline_list_make", new FlowBlockCanvas.Slot("unused", "unused", "0",
            member.getX(), member.getY(), member.getWidth(), member.getHeight(), false));
        member = constructorMember(editor);
        editor.mouseClicked(member.getX() + 3, member.getY() + 3, 0);
        var focused = (List<FlowBlockCanvas.Slot>) get(editor, "constructionFocusSlots");
        var nested = focused.getFirst();
        editor.mouseClicked(nested.x() + 3, nested.y() + 3, 0);
        assertEquals(1, ((Deque<?>) get(editor, "constructionParents")).size());
        click(editor, "construction_add");
        assertEquals(before, workspace.snapshot());
        if (applyChild) click(editor, "construction_apply");
        else editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0);
        assertEquals(0, ((Deque<?>) get(editor, "constructionParents")).size());
        assertEquals(before, workspace.snapshot(), "Child Apply/Cancel must not publish the parent");
        var parent = (InlineGraphEdits.ConstructorDraft) get(editor, "construction");
        var nestedList = (ComputationGraph.MakeList) parent.node(0);
        assertEquals(applyChild ? 2 : 1, nestedList.elements().size(), "Child Cancel leaves parent unchanged");
        if (!applyParent) {
            click(editor, "construction_cancel");
            assertEquals(before, workspace.snapshot(), "Parent Cancel discards child-applied changes too");
        } else {
            click(editor, "construction_apply");
            var applied = workspace.snapshot();
            var inner = new DataValue.ListValue(applyChild
                ? List.of(new DataValue.IntegerValue(9), new DataValue.IntegerValue(0))
                : List.of(new DataValue.IntegerValue(9)));
            assertEquals(new DataValue.ListValue(List.of(inner)), run(applied).field("block_1_value"));
            click(editor, "undo");
            assertEquals(before, workspace.snapshot(), "Nested Apply is one outer history commit");
            click(editor, "redo");
            assertEquals(applied, workspace.snapshot());
        }
    }

    static com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton constructorMember(ComputerFlowScreen editor) throws Exception {
        var members = (Map<com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton, Integer>) get(editor, "constructionSlots");
        return members.keySet().iterator().next();
    }

    static List<DataValue> constructorConstants(ComputerFlowScreen editor) throws Exception {
        var form = (InlineGraphEdits.ConstructorDraft) get(editor, "construction");
        var values = new ArrayList<DataValue>();
        for (int row = 0; row < form.rows().size(); row++) {
            values.add(((ComputationGraph.Constant) form.node(row)).value());
        }
        return values;
    }

    @Test void enabledExtensionReporterCanBeUsedAsAListMemberWithoutLeakingAnUnappliedEdit() throws Exception {
        var workspace = new ProgramWorkspace();
        var setup = new FlowDraft(FlowDocumentBody.emptyAssembly());
        setup.setExtension("create_biotech:text", 1L);
        workspace.setFlow(Optional.of(setup.snapshot()));
        var editor = FlowCallScreenTest.editor(workspace, 640);
        click(editor, "category_variables");
        click(editor, "add_assign");
        dropReporter(editor, "inline_list_make", slot(editor, "0", 0));
        var before = workspace.snapshot();

        for (boolean apply : List.of(false, true)) {
            openListMembers(editor);
            click(editor, "category_extensions");
            var reporter = button(editor, "extension_text_label");
            var member = constructorMember(editor);
            editor.mouseClicked(reporter.getX() + 8, reporter.getY() + 8, 0);
            editor.mouseDragged(member.getX() + 8, member.getY() + 8, 0, 40, 0);
            editor.mouseReleased(member.getX() + 8, member.getY() + 8, 0);
            assertEquals(before, workspace.snapshot(), "Neither the block nor its fixed implementation is published before Apply");
            click(editor, apply ? "construction_apply" : "construction_cancel");
            if (!apply) assertEquals(before, workspace.snapshot());
        }
        var applied = workspace.snapshot();
        var restored = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(applied));
        assertEquals(applied, restored);
        assertEquals(new DataValue.ListValue(List.of(new DataValue.TextValue("Status: ready"))),
            run(restored).field("block_1_value"));
        click(editor, "undo");
        assertEquals(before, workspace.snapshot(), "Apply and its fixed implementation are one edit");
    }

    @Test void parkingAnExtensionPredicateKeepsItsHexagonWithoutLinkingHardwareCode() throws Exception {
        var workspace = new ProgramWorkspace();
        var setup = new FlowDraft(FlowDocumentBody.emptyAssembly());
        setup.setExtension("create_biotech:signals", 1L);
        workspace.setFlow(Optional.of(setup.snapshot()));
        var before = workspace.snapshot();
        var editor = FlowCallScreenTest.editor(workspace, 640);
        click(editor, "category_sensing");
        click(editor, "sensing_link");
        FlowPaletteScreenTest.search(editor).setValue("xbus_busy");
        var prototype = button(editor, "extension_xbus_busy");
        var canvas = (FlowBlockCanvas)get(editor, "canvas");
        int x = (int)get(canvas, "x") + 60, y = (int)get(canvas, "y") + 100;
        editor.mouseClicked(prototype.getX() + 8, prototype.getY() + 8, 0);
        editor.mouseDragged(x, y, 0, 40, 0);
        editor.mouseReleased(x, y, 0);
        var parked = workspace.snapshot();
        var body = parked.flow().orElseThrow();
        assertEquals(before.flow().orElseThrow().program(before.archive()), body.program(parked.archive()));
        var graph = body.canvas().values().parked().getFirst().graph();
        assertTrue(InlineGraphView.layout(graph, s -> s.length() * 6).bool(),
            "The declared BOOLEAN shape is visible even without an executable template");
    }

    @Test void anExtensionInAParkedRecordLinksOnlyWhenTheRecordIsConnected() throws Exception {
        var workspace = new ProgramWorkspace();
        var setup = new FlowDraft(FlowDocumentBody.emptyAssembly());
        setup.setExtension("create_biotech:text", 1L);
        workspace.setFlow(Optional.of(setup.snapshot()));
        var editor = FlowCallScreenTest.editor(workspace, 640);
        click(editor, "category_variables");
        click(editor, "add_assign");
        var canvas = (FlowBlockCanvas)get(editor, "canvas");
        int x = (int)get(canvas, "x") + 65, y = (int)get(canvas, "y") + 100;
        dropReporter(editor, "inline_record_make", new FlowBlockCanvas.Slot("unused", "unused", "0", x, y, 40, 16, false));
        var before = workspace.snapshot();
        var parked = before.flow().orElseThrow().canvas().values().parked().getFirst();
        var hit = canvas.parkedSlotHit(x + 10, y + 12);
        assertNotNull(hit);
        assertTrue(parked.isRoot(hit.slot().node()));
        var root = hit.slot();
        editor.mouseClicked(root.x() + 3, root.y() + 3, 0);
        editor.mouseReleased(root.x() + 3, root.y() + 3, 0);
        click(editor, "category_extensions");
        var prototype = button(editor, "extension_text_label");
        var member = constructorMember(editor);
        editor.mouseClicked(prototype.getX() + 8, prototype.getY() + 8, 0);
        editor.mouseDragged(member.getX() + 8, member.getY() + 8, 0, 40, 0);
        editor.mouseReleased(member.getX() + 8, member.getY() + 8, 0);
        click(editor, "construction_apply");

        var changed = workspace.snapshot();
        var reopened = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(changed));
        assertEquals(changed, reopened);
        var body = reopened.flow().orElseThrow();
        assertTrue(body.canvas().values().parked().getFirst().graph().nodes().values().stream()
            .anyMatch(ComputationGraph.ProcedureValue.class::isInstance));
        assertEquals(before.flow().orElseThrow().program(before.archive()), body.program(reopened.archive()),
            "A parked extension must not change code or hardware requirements");
        assertEquals(new DataValue.IntegerValue(0), run(reopened).field("block_1_value"));

        var restoredWorkspace = new ProgramWorkspace(reopened);
        var restoredEditor = FlowCallScreenTest.editor(restoredWorkspace, 640);
        var sink = slot(restoredEditor, "0", 0);
        restoredEditor.mouseClicked(x + 10, y + 12, 0);
        restoredEditor.mouseDragged(sink.x() + 8, sink.y() + 8, 0, 40, 0);
        restoredEditor.mouseReleased(sink.x() + 8, sink.y() + 8, 0);
        var attached = restoredWorkspace.snapshot();
        assertTrue(attached.flow().orElseThrow().canvas().values().parked().isEmpty());
        attached.validateExtensions();
        assertEquals(new DataValue.RecordValue(Map.of("field", new DataValue.TextValue("Status: ready"))),
            run(attached).field("block_1_value"));
        click(restoredEditor, "undo");
        assertEquals(reopened, restoredWorkspace.snapshot());
    }

    static void openListMembers(ComputerFlowScreen editor) throws Exception {
        var head = slot(editor, net.minecraft.network.chat.Component.translatable(
            "create_biotech.biocomputing.flow_editor.inline_list_make").getString(), 0);
        editor.mouseClicked(head.x() + 3, head.y() + 3, 0);
        editor.mouseReleased(head.x() + 3, head.y() + 3, 0);
    }

    @Test void constructorMembersBeyondTheCanvasTileBudgetRemainEditableAndReopen() throws Exception {
        var nodes = new LinkedHashMap<String, ComputationGraph.Node>();var children = new ArrayList<String>();
        for (int i = 0; i < 300; i++) {String id = "v_" + i;children.add(id);nodes.put(id, new ComputationGraph.Constant(new DataValue.IntegerValue(i)));}
        nodes.put("value", new ComputationGraph.MakeList(children));
        var graph = new ComputationGraph(Map.of(), nodes, Map.of("value", "value"));
        var workspace = new ProgramWorkspace();workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(), List.of(new FlowProgram.Evaluate("build", "unused", new FlowCallBindings(Map.of(), Map.of("value", "payload")))), new DataValue.RecordValue(Map.of()), FlowBlockLayout.DEFAULT, Map.of("build", graph), FlowProgram.Execution.REGISTER_V1)));
        var editor = FlowCallScreenTest.editor(workspace, 640);
        var head = slot(editor, net.minecraft.network.chat.Component.translatable("create_biotech.biocomputing.flow_editor.inline_list_make").getString(), 0);
        editor.mouseClicked(head.x() + 3, head.y() + 3, 0);
        editor.mouseReleased(head.x() + 3, head.y() + 3, 0);
        for (int i = 0; i < 299; i++) click(editor, "construction_next");
        var slots = (Map<com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton, Integer>)get(editor, "constructionSlots");
        assertEquals(List.of(299), new ArrayList<>(slots.values()));
        var value = slots.keySet().iterator().next();editor.mouseClicked(value.getX() + 3, value.getY() + 3, 0);
        boxes(editor).stream().filter(b -> b.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t && t.getKey().endsWith("inline_value")).findFirst().orElseThrow().setValue("999");editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0);
        click(editor, "construction_apply");
        var saved = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot()));
        var list = (ComputationGraph.MakeList)saved.flow().orElseThrow().inline().get("build").nodes().get("value");assertEquals(300, list.elements().size());assertEquals(new ComputationGraph.Constant(new DataValue.IntegerValue(999)), saved.flow().orElseThrow().inline().get("build").nodes().get(list.elements().getLast()));
    }
    @Test void recordFormRenamesWithoutReplacingValueChildRejectsDuplicateAndDragsLiveValue() throws Exception {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("x", DataType.Scalar.INTEGER), List.of(), new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(17))), FlowBlockLayout.DEFAULT, Map.of(), FlowProgram.Execution.REGISTER_V1)));
        var editor = FlowCallScreenTest.editor(workspace, 640);
        click(editor, "category_variables");
        click(editor, "add_assign");
        dropReporter(editor, "inline_record_make", slot(editor, "0", 0));
        var head = slot(editor, net.minecraft.network.chat.Component.translatable("create_biotech.biocomputing.flow_editor.inline_record_make").getString(), 0);
        editor.mouseClicked(head.x() + 3, head.y() + 3, 0);
        editor.mouseReleased(head.x() + 3, head.y() + 3, 0);
        click(editor, "construction_add");
        var before = workspace.snapshot();
        var keys = boxes(editor).stream().filter(b->b.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t && t.getKey().endsWith("construction_key")).toList();
        assertEquals(1, keys.size());keys.getFirst().setValue("field");click(editor, "construction_apply");assertEquals(before, workspace.snapshot());
        keys = boxes(editor).stream().filter(b->b.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t && t.getKey().endsWith("construction_key")).toList();keys.getFirst().setValue("b");click(editor, "construction_previous");
        keys = boxes(editor).stream().filter(b->b.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t && t.getKey().endsWith("construction_key")).toList();keys.getFirst().setValue("a");
        click(editor, "category_variables");
        var slots = (Map<com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton, Integer>)get(editor, "constructionSlots");
        var member = slots.keySet().iterator().next();
        dropPrivateVariable(editor, "x", new FlowBlockCanvas.Slot("unused", "unused", "0", member.getX(), member.getY(), member.getWidth(), member.getHeight(), false));
        click(editor, "construction_apply");
        var graph = workspace.flow().orElseThrow().inline().values().iterator().next();
        var record = (ComputationGraph.MakeRecord)graph.nodes().get("value");
        assertEquals(List.of("a", "b"), new ArrayList<>(record.fields().keySet()));
        assertEquals(new DataValue.IntegerValue(17), ((DataValue.RecordValue)run(workspace.snapshot()).field("block_1_value")).field("a"));
    }
    @Test void scratchListConstructorFormIsVariadicCancellableAndUsesExistingUndo() throws Exception {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var editor = FlowCallScreenTest.editor(workspace, 640);
        click(editor, "category_variables");
        click(editor, "add_assign");
        assertTrue(FlowBlockCatalog.entries(Map.of()).stream().anyMatch(e->e.id().equals("list_make")));
        dropReporter(editor, "inline_list_make", slot(editor, "0", 0));
        var before = workspace.snapshot();
        var head = slot(editor, net.minecraft.network.chat.Component.translatable("create_biotech.biocomputing.flow_editor.inline_list_make").getString(), 0);
        editor.mouseClicked(head.x() + 3, head.y() + 3, 0);
        editor.mouseReleased(head.x() + 3, head.y() + 3, 0);
        click(editor, "construction_add");
        click(editor, "construction_add");
        click(editor, "construction_cancel");
        assertEquals(before, workspace.snapshot());
        head = slot(editor, net.minecraft.network.chat.Component.translatable("create_biotech.biocomputing.flow_editor.inline_list_make").getString(), 0);
        editor.mouseClicked(head.x() + 3, head.y() + 3, 0);
        editor.mouseReleased(head.x() + 3, head.y() + 3, 0);
        click(editor, "construction_add");
        click(editor, "construction_add");
        click(editor, "construction_apply");
        var changed = workspace.snapshot();
        var graph = changed.flow().orElseThrow().inline().values().iterator().next();
        assertEquals(3, ((ComputationGraph.MakeList)graph.nodes().get("value")).elements().size());
        click(editor, "undo");
        assertEquals(before, workspace.snapshot());
        click(editor, "redo");
        assertEquals(changed, workspace.snapshot());
        var body = changed.flow().orElseThrow();
        var limits = ParserHardwareFixture.withIsa1a(new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(new ResourceLimits(200, 100000, 1), SubgraphWorkspaceTest.LIMITS));
        var host = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()-> {
        });
        host.installFlow(body.program(changed.archive()), body.initial(), limits, true);
        for (int i = 0;host.flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.READY;i++) {
            assertTrue(i<30);
            var beforeState = host.flowSnapshot().orElseThrow();
            var restored = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()-> {
            });
            restored.read(host.write());
            restored.tick(limits, true);
            assertEquals(beforeState, restored.flowSnapshot().orElseThrow());
            host = restored;
            host.step(limits, true);
        }
        assertEquals(run(changed), host.flowSnapshot().orElseThrow().state().locals());
    }
    @Test void scratchCatalogOffersListLengthAndTagPredicatesAsEditableNativeReporters() throws Exception {
        var entries=FlowBlockCatalog.entries(Map.of());
        for(String id:List.of("list_get","list_length","is_null","is_error")) assertTrue(entries.stream().anyMatch(e->e.id().equals(id)),id);
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("items",new DataType.ListType(DataType.Scalar.INTEGER)),List.of(),
            new DataValue.RecordValue(Map.of("items",new DataValue.ListValue(List.of(new DataValue.IntegerValue(7),new DataValue.IntegerValue(42))))),FlowBlockLayout.DEFAULT,Map.of(),FlowProgram.Execution.REGISTER_V1)));
        var editor=FlowCallScreenTest.editor(workspace,640);click(editor,"category_variables");click(editor,"add_assign");
        dropReporter(editor,"inline_list_length",slot(editor,"0",0));
        dropReporter(editor,"inline_private",slot(editor,"[]",0));
        var document=workspace.snapshot();assertEquals(document,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        var body=document.flow().orElseThrow();var limits=new ResourceLimits(1000,100000,1);
        var runtime=new FlowRuntime(UUID.randomUUID(),UUID.randomUUID(),body.program(document.archive()),body.initial(),limits);
        for(int i=0;runtime.checkpoint().status()==FlowRuntime.Status.READY;i++){assertTrue(i<20);runtime.advance(1);}
        assertEquals(FlowRuntime.Status.COMPLETE,runtime.checkpoint().status());assertEquals(new DataValue.IntegerValue(2),runtime.checkpoint().locals().field("block_1_value"));
    }

    @Test void scratchTextLengthIsAnInlineReporterAndInstallsIntoTheMeteredRegisterBackend() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var editor = FlowCallScreenTest.editor(workspace, 640); click(editor, "category_variables"); click(editor, "add_assign");
        dropReporter(editor, "inline_text_length", slot(editor, "0", 0));
        setLiteral(editor, slot(editor, "\"\"", 0), "测😀abc");
        var document = workspace.snapshot(); assertTrue(workspace.entryNames().isEmpty());
        assertEquals(document, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        var body = document.flow().orElseThrow();
        var host = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(() -> {});
        var limits = ParserHardwareFixture.withIsa1a(new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(new ResourceLimits(1000, 100000, 1), SubgraphWorkspaceTest.LIMITS));
        host.installFlow(body.program(document.archive()), body.initial(), limits, true);
        for (int i = 0; i < 20 && host.flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.READY; i++) host.step(limits, true);
        var done = host.flowSnapshot().orElseThrow().state();
        assertEquals(FlowRuntime.Status.COMPLETE, done.status()); assertEquals(8, done.work());
        assertEquals(new DataValue.IntegerValue(5), done.locals().field("block_1_value"));
        assertTrue(done.assembly().isPresent()); assertTrue(done.expression().isEmpty());
    }
    @Test void scratchListItemSlotIsOneBasedAndOnlyLowersOnce() throws Exception {
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("items",new DataType.ListType(DataType.Scalar.INTEGER),"slot",DataType.Scalar.INTEGER),List.of(),
            new DataValue.RecordValue(Map.of("items",new DataValue.ListValue(List.of(new DataValue.IntegerValue(7),new DataValue.IntegerValue(42))),"slot",new DataValue.IntegerValue(2))),FlowBlockLayout.DEFAULT,Map.of(),FlowProgram.Execution.REGISTER_V1)));
        var editor=FlowCallScreenTest.editor(workspace,640);click(editor,"category_variables");click(editor,"add_assign");
        dropReporter(editor,"inline_list_get",slot(editor,"0",0));dropPrivateVariable(editor,"items",slot(editor,"[]",0));
        dropPrivateVariable(editor,"slot",slot(editor,"1",0));
        var document=workspace.snapshot();assertEquals(document,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        assertEquals(1,document.flow().orElseThrow().inline().values().stream().flatMap(g->g.nodes().values().stream()).filter(n->n instanceof ComputationGraph.Scalar s && s.operation()==ScalarOperation.SUBTRACT_INTEGER).count());
        assertEquals(new DataValue.IntegerValue(42),run(document).field("block_1_value"));
    }
    @Test void untilExposesAnEditableBooleanSlotWithoutANamedGraph() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        var editor = FlowCallScreenTest.editor(workspace,640); var until=button(editor,"add_until");
        var canvas=get(editor,"canvas"); int x=(int)get(canvas,"x")+24,y=(int)get(canvas,"y")+50;
        editor.mouseClicked(until.getX()+8,until.getY()+8,0); editor.mouseDragged(x,y,0,100,20); editor.mouseReleased(x,y,0);
        setLiteral(editor,slot(editor,"false",0),"true");
        var document=workspace.snapshot(); assertTrue(workspace.entryNames().isEmpty());
        assertEquals(new DataValue.RecordValue(Map.of()),run(document));
        assertEquals(document,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
    }
    @Test void aComparisonCanReplaceABooleanPlaceholderInsideTheUntilSlot() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        var editor = FlowCallScreenTest.editor(workspace,640); var until=button(editor,"add_until");
        var canvas=get(editor,"canvas"); int x=(int)get(canvas,"x")+24,y=(int)get(canvas,"y")+50;
        editor.mouseClicked(until.getX()+8,until.getY()+8,0); editor.mouseDragged(x,y,0,100,20); editor.mouseReleased(x,y,0);
        var before=workspace.snapshot(); dropReporter(editor,"inline_less_integer",slot(editor,"false",0));
        setLiteral(editor,slot(editor,"0",0),"2"); setLiteral(editor,slot(editor,"0",0),"3");
        var document=workspace.snapshot(); assertEquals(new DataValue.RecordValue(Map.of()),run(document));
        assertEquals(new DataValue.IntegerValue(19),ProgramDocumentCodec.encode(document).field("version"));
        assertOwnerRecovery(document);
        for(int i=0;i<3;i++) click(editor,"undo"); assertEquals(before,workspace.snapshot());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"if", "repeat"})
    void otherControlSlotsUseTheSamePublicNestedValuePath(String kind) throws Exception {
        var workspace=new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        var editor=FlowCallScreenTest.editor(workspace,640); var prototype=button(editor,"add_"+kind);
        var canvas=get(editor,"canvas"); int x=(int)get(canvas,"x")+24,y=(int)get(canvas,"y")+50;
        editor.mouseClicked(prototype.getX()+8,prototype.getY()+8,0); editor.mouseDragged(x,y,0,100,20); editor.mouseReleased(x,y,0);
        boolean branch=kind.equals("if"); var before=workspace.snapshot();
        if(branch) {
            dropReporter(editor,"inline_add_integer",slot(editor,"false",0));
            assertEquals(before,workspace.snapshot(),"An integer must not silently become a condition");
        }
        dropReporter(editor,branch?"inline_less_integer":"inline_add_integer",slot(editor,branch?"false":"10",0));
        setLiteral(editor,slot(editor,branch?"0":"10",0),"2"); setLiteral(editor,slot(editor,"0",0),"3");
        var document=workspace.snapshot(); assertTrue(workspace.entryNames().isEmpty());
        assertEquals(document,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        assertOwnerRecovery(document);
    }
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { environment.client(); }
    @AfterEach void restoreClient() throws Exception { environment.restore(); }

    @Test void aValueAssignmentCanBeDraggedWithoutCreatingOrNamingAnotherGraph() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        var screen = FlowCallScreenTest.editor(workspace, 640);
        click(screen, "category_variables"); var assign = button(screen, "add_assign");
        var canvas = get(screen, "canvas"); double x = (int)get(canvas, "x") + 24, y = (int)get(canvas, "y") + 50;
        var before = workspace.snapshot();
        screen.mouseClicked(assign.getX() + 10, assign.getY() + 10, 0);
        screen.mouseDragged(x, y, 0, 100, 20);
        assertEquals(before, workspace.snapshot());
        screen.mouseReleased(x, y, 0);
        var document = workspace.snapshot(); var body = document.flow().orElseThrow();
        assertEquals(1, body.steps().size());
        assertTrue(workspace.entryNames().isEmpty(), "The player need not create a named expression");
        assertFalse((boolean)get(screen, "parameters")); assertFalse((boolean)get(screen, "waiting"));
        var limits = new ResourceLimits(200, 100000, 1); document.validate(limits);
        var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), body.program(document.archive()), body.initial(), limits);
        runtime.load();
        for (int i = 0; i < 100 && runtime.checkpoint().status() != FlowRuntime.Status.COMPLETE; i++) runtime.advanceRound(Map.of());
        assertEquals(FlowRuntime.Status.COMPLETE, runtime.checkpoint().status());
        assertEquals(new DataValue.IntegerValue(0), runtime.checkpoint().locals().field("block_1_value"));
        assertEquals(document, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        click(screen, "undo"); assertEquals(before, workspace.snapshot());
        click(screen, "redo"); assertEquals(document, workspace.snapshot());
    }

    @Test void literalsAreEditedAtTheirSlotAndCanBeUndoneOrCancelled() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        var screen = FlowCallScreenTest.editor(workspace, 640); click(screen, "category_variables"); click(screen, "add_assign");
        var literal = slot(screen, "0", 0);
        screen.mouseClicked(literal.x()+8, literal.y()+literal.height()/2, 0); screen.mouseReleased(literal.x()+8, literal.y()+literal.height()/2, 0);
        assertEquals(1, boxes(screen).size(), "A constant slot should accept text in place, not open a form");
        assertFalse((boolean)get(screen, "parameters"));
        boxes(screen).getFirst().setValue("7"); screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0);
        var document = workspace.snapshot();
        assertEquals(new DataValue.IntegerValue(7), run(document).field("block_1_value"));
        click(screen, "undo"); assertEquals(new DataValue.IntegerValue(0), run(workspace.snapshot()).field("block_1_value"));
        click(screen, "redo"); assertEquals(document, workspace.snapshot());
        literal = slot(screen, "7", 0);
        screen.mouseClicked(literal.x()+8, literal.y()+literal.height()/2, 0); boxes(screen).getFirst().setValue("9");
        screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0); assertEquals(document, workspace.snapshot());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void coveredLiteralSelectsAndDragsTheVisibleStackWithoutEditingTheLowerValue(int width) throws Exception {
        var workspace = overlappingAssignments(width);
        var editor = FlowCallScreenTest.editor(workspace, width);
        var covered = slot(editor, "0", 0);
        double x = covered.x() + 8, y = covered.y() + covered.height() / 2;
        var before = workspace.snapshot();
        assertTrue(editor.mouseClicked(x, y, 0));
        assertTrue(boxes(editor).isEmpty(), "The opaque upper body must hide the lower value editor");
        assertTrue(editor.mouseDragged(x + 50, y - 30, 0, 50, -30));
        assertEquals(before, workspace.snapshot(), "Dragging is still only a preview");
        assertTrue(editor.mouseReleased(x + 50, y - 30, 0));
        var after = workspace.flow().orElseThrow();
        assertEquals(before.flow().orElseThrow().steps(), after.steps());
        assertEquals(before.flow().orElseThrow().inline(), after.inline());
        var origin = before.flow().orElseThrow().canvas().detached().getFirst().position();
        assertEquals(new ProgramDocument.Position(origin.x()+50, origin.y()-30), after.canvas().detached().getFirst().position());
        assertEquals("block_2", after.canvas().detached().getFirst().steps().getFirst().sourceId());
        click(editor, "undo"); assertEquals(before, workspace.snapshot());
        // Removing the cover must expose the ordinary editable lower slot again.
        click(editor, "remove");
        setLiteral(editor, slot(editor, "0", 0), "7");
        assertEquals(new DataValue.IntegerValue(7), run(workspace.snapshot()).field("block_1_value"));
        assertFalse((boolean)get(editor, "waiting"));
    }

    private static ProgramWorkspace overlappingAssignments(int width) throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var editor = FlowCallScreenTest.editor(workspace, width);
        click(editor, "category_variables"); click(editor, "add_assign"); click(editor, "add_assign");
        var draft = new FlowDraft(workspace.flow().orElseThrow());
        // Align the cover's blank trailing strip over the lower operand, independently of row layout.
        var canvas = (FlowBlockCanvas)get(editor, "canvas"); var lower = slot(editor, "0", 0);
        var upper = canvas.blocks().get(1);
        double worldX = lower.x()+8-(int)get(canvas,"x"), worldY = lower.y()+lower.height()/2-(int)get(canvas,"y");
        draft.drop("block_2", null, new ProgramDocument.Position(worldX-upper.width()+4, worldY-upper.height()/2.0));
        workspace.setFlow(Optional.of(draft.snapshot())); return workspace;
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void aReporterCannotBeDroppedThroughAnOpaqueParkedBlock(int width) throws Exception {
        var workspace = overlappingAssignments(width);
        var editor = FlowCallScreenTest.editor(workspace, width);
        var before = workspace.snapshot();
        dropReporter(editor, "inline_add_integer", slot(editor, "0", 0));
        assertEquals(before, workspace.snapshot(), "A covered literal is not a drop target");
        assertFalse(button(editor, "undo").active, "An ignored drop creates no edit history");
        assertTrue(boxes(editor).isEmpty());
    }

    @Test void aBlockWithoutValueSlotsAlsoOccludesAfterPanningAndTheExposedSlotStillWorks() throws Exception {
        var workspace = overlappingAssignments(640);
        var draft = new FlowDraft(workspace.flow().orElseThrow());
        draft.remove("block_2");
        var probe = FlowCallScreenTest.editor(workspace,640);
        var probeCanvas = (FlowBlockCanvas)get(probe,"canvas"); var lower = slot(probe,"0",0);
        double worldX = lower.x()+8-(int)get(probeCanvas,"x"), worldY = lower.y()+lower.height()/2-(int)get(probeCanvas,"y");
        draft.place(List.of(new FlowProgram.Forever("cover"), new FlowProgram.EndForever("cover_end")),
            null, new ProgramDocument.Position(worldX-FlowBlockCanvas.WIDTH+4, worldY-FlowBlockCanvas.ROW/2.0));
        workspace.setFlow(Optional.of(draft.snapshot()));
        var editor = FlowCallScreenTest.editor(workspace, 640);
        var covered = slot(editor, "0", 0);
        // The headless fixture has no GLFW window for modifier polling. Apply the
        // canvas's wheel transform, then exercise actual screen clicks and drops.
        ((FlowBlockCanvas)get(editor, "canvas")).scroll(covered.x(), covered.y(), 0, 1, false);
        covered = slot(editor, "0", 0);
        var before = workspace.snapshot();
        editor.mouseClicked(covered.x() + 8, covered.y() + covered.height() / 2, 0);
        editor.mouseReleased(covered.x() + 8, covered.y() + covered.height() / 2, 0);
        assertTrue(boxes(editor).isEmpty(), "A slotless cover still owns its entire visible body");
        assertEquals(before, workspace.snapshot());
        dropReporter(editor, "inline_add_integer", covered);
        assertEquals(before, workspace.snapshot());
        click(editor, "remove"); // The click selected the visible cover, not the lower assignment.
        assertTrue(workspace.flow().orElseThrow().canvas().detached().isEmpty());
        dropReporter(editor, "inline_add_integer", slot(editor, "0", 0));
        setLiteral(editor, slot(editor, "0", 0), "7");
        setLiteral(editor, slot(editor, "0", 0), "2");
        assertEquals(new DataValue.IntegerValue(9), run(workspace.snapshot()).field("block_1_value"));
        assertEquals(workspace.snapshot(), ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot())));
    }
    @Test void nestedArithmeticUsesExplicitLocalsWithoutAnotherCanvas() throws Exception {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("counter", DataType.Scalar.INTEGER), List.of(),
            new DataValue.RecordValue(Map.of("counter", new DataValue.IntegerValue(5))))));
        var screen = FlowCallScreenTest.editor(workspace, 640); click(screen, "category_variables"); click(screen, "add_assign");
        var before = workspace.snapshot();
        dropReporter(screen, "inline_add_integer", slot(screen, "0", 0));
        dropReporter(screen, "inline_multiply_integer", slot(screen, "0", 1));
        // A declared local is a value block, not an implicit argument-name match.
        click(screen,"category_variables"); int pages = 0;
        var privateLabel = FlowBlockCatalog.label(new InlineGraphEdits.Variable("counter", List.of(), DataType.Scalar.INTEGER));
        while (screen.children().stream().noneMatch(child -> child instanceof com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton b && b.getMessage().equals(privateLabel))) { assertTrue(pages++ < 20); click(screen, "palette_next"); }
        var variable = screen.children().stream().filter(child -> child instanceof com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton b && b.getMessage().equals(privateLabel))
            .map(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::cast).findFirst().orElseThrow();
        var left = slot(screen, "0", 0); screen.mouseClicked(variable.getX()+8, variable.getY()+8,0); screen.mouseReleased(left.x()+8,left.y()+left.height()/2,0);
        setLiteral(screen, slot(screen, "0", 0), "2"); setLiteral(screen, slot(screen, "0", 0), "3");
        var document = workspace.snapshot(); assertEquals(new DataValue.IntegerValue(11), run(document).field("block_1_value"));
        assertEquals(new DataValue.IntegerValue(5), run(document).field("counter"));
        assertTrue(workspace.entryNames().isEmpty()); assertFalse((boolean)get(screen,"parameters"));
        var reopened = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)); assertEquals(document, reopened);
        assertEquals(run(document), run(reopened));
        assertOwnerRecovery(document);
        for(int i=0;i<5;i++) click(screen,"undo"); assertEquals(before,workspace.snapshot());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void assignmentTargetCanBeEditedInPlaceAtEverySupportedWidth(int width) throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(new FlowDocumentBody(
            Map.of("counter",DataType.Scalar.INTEGER),List.of(),new DataValue.RecordValue(Map.of("counter",new DataValue.IntegerValue(5))))));
        var editor = FlowCallScreenTest.editor(workspace,width); click(editor,"category_variables"); click(editor,"add_assign");
        var target = ((FlowBlockCanvas)get(editor,"canvas")).inlineSlots().stream().filter(FlowBlockCanvas.Slot::target).findFirst().orElseThrow();
        editor.mouseClicked(target.x()+8,target.y()+8,0);
        assertEquals(1,boxes(editor).size()); bounds(editor);
        boxes(editor).getFirst().setValue("counter"); editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER,0,0);
        assertEquals(new DataValue.IntegerValue(0),run(workspace.snapshot()).field("counter"));
        assertFalse(run(workspace.snapshot()).fields().containsKey("block_1_value"));
        click(editor,"undo"); assertEquals(new DataValue.IntegerValue(5),run(workspace.snapshot()).field("counter"));
    }

    @Test void incompatibleNestedValueDoesNotChangeTheDraftOrItsUndoHistory() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        var editor = FlowCallScreenTest.editor(workspace,640); click(editor,"category_variables"); click(editor,"add_assign");
        var assignment = workspace.snapshot(); dropReporter(editor,"inline_add_integer",slot(editor,"0",0));
        var before = workspace.snapshot();
        dropReporter(editor,"inline_and_boolean",slot(editor,"0",0));
        assertEquals(before,workspace.snapshot());
        click(editor,"undo"); assertEquals(assignment,workspace.snapshot());
    }

    @Test void parkedGraphsRemainEditableButDoNotExecuteOrChargeProgramCapacity() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        var editor = FlowCallScreenTest.editor(workspace,640); click(editor,"category_variables"); click(editor,"add_assign");
        var original = workspace.flow().orElseThrow(); var draft = new FlowDraft(original);
        draft.drop("block_1",null,new ProgramDocument.Position(350,80));
        assertEquals(original.inline(),draft.snapshot().inline());
        var archive=workspace.snapshot().archive(); assertEquals(archive,draft.snapshot().program(archive).expressions());
        var parked = new ProgramDocument(archive,Map.of(),Optional.empty(),Optional.of(draft.snapshot()));
        assertEquals(parked,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(parked)));
        assertFalse(run(parked).fields().containsKey("block_1_value"));
        draft.remove("block_1"); assertTrue(draft.snapshot().inline().isEmpty());
        draft.undo(); assertEquals(parked.flow().orElseThrow(),draft.snapshot());
        draft.undo(); assertEquals(original,draft.snapshot());
        var encoded=ProgramDocumentCodec.encode(workspace.snapshot()); assertEquals(new DataValue.IntegerValue(18),encoded.field("version"));
        var downgraded = new LinkedHashMap<>(encoded.fields()); downgraded.put("version",new DataValue.IntegerValue(17));
        assertThrows(com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException.class,
            () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(downgraded)));
    }

    static void assertOwnerRecovery(ProgramDocument document) {
        var limits = ParserHardwareFixture.withIsa1a(new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(new ResourceLimits(200,100000,1),
            new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(100000,1000000,1000000)));
        var body=document.flow().orElseThrow(); var host=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(() -> {});
        host.installFlow(body.program(document.archive()),body.initial(),limits,true);
        boolean computing=false; int checkpoints=0;
        for(int i=0;host.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE;i++) {
            assertTrue(i<1000); host.step(limits,true); var snapshot=host.flowSnapshot().orElseThrow();
            checkpoints++; computing |= snapshot.state().status()==FlowRuntime.Status.COMPUTING;
            var restored=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(() -> {});
            restored.read(host.write()); restored.tick(limits,true);
            assertEquals(snapshot,restored.flowSnapshot().orElseThrow(),"Paused owner reload must not consume work"); host=restored;
        }
        if (body.execution() == FlowProgram.Execution.REGISTER_V1) {
            assertTrue(checkpoints > 1, "Resume the ISA program from multiple real checkpoints");
            assertTrue(host.flowSnapshot().orElseThrow().state().assembly().isPresent());
        } else assertTrue(computing);
        assertEquals(run(document),host.flowSnapshot().orElseThrow().state().locals());
    }
    @Test void displayedCapacityIncludesInlineGraphsAndTheirConstants() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        var editor = FlowCallScreenTest.editor(workspace, 640); click(editor, "category_variables"); click(editor, "add_assign");
        dropReporter(editor, "inline_add_integer", slot(editor, "0", 0));
        var document = workspace.snapshot(); var body = document.flow().orElseThrow();
        var parent = screen(); var menu = readyMenu(parent); parent.importDocument(document);
        var usage = body.program(document.archive()).expressions().compile(menu.response().orElseThrow().hardware()).usage();
        var label = (net.minecraft.network.chat.contents.TranslatableContents)parent.resourceStatus().getContents();
        assertEquals(usage.programUnits() + body.steps().size(), ((Number)label.getArgs()[0]).longValue());
        assertEquals(usage.constantUnits() + usage.initialMemoryUnits(), ((Number)label.getArgs()[2]).longValue());
    }

    @Test void closingCancelsReporterDragRatherThanApplyingADelayedRelease() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        var editor = FlowCallScreenTest.editor(workspace, 640); click(editor, "category_variables"); click(editor, "add_assign");
        var before = workspace.snapshot(); var value = slot(editor, "0", 0); click(editor,"category_operators"); var add = button(editor, "inline_add_integer");
        editor.mouseClicked(add.getX()+8,add.getY()+8,0);
        editor.onClose();
        var moved = slot(editor,"0",0); editor.mouseReleased(moved.x()+8,moved.y()+8,0);
        assertEquals(before,workspace.snapshot());
    }
    @Test void parameterCopiesFromDefinitionHatOnlyIntoItsOwnBodyAndRunsInOperandOrder() throws Exception {
        var key = new ModuleDefinition.Key("offset", 1);
        var number = DataType.Scalar.INTEGER;
        var procedure = new FlowProgram.Procedure("definition", key, Map.of("n", number), Map.of("result", number), Map.of("result", "out"));
        var draft = new FlowDraft(FlowDocumentBody.emptySoftwareAssembly());
        draft.initial(new DataValue.RecordValue(Map.of("n", new DataValue.IntegerValue(9))));
        draft.insertRepeat(null, new FlowInput.Literal(new DataValue.IntegerValue(0)));
        draft.append(new FlowProgram.Invoke("call", key, new FlowCallBindings(Map.of("n", "n"), Map.of("result", "answer"))));
        var graph = new ComputationGraph(Map.of(), Map.of("value", new ComputationGraph.Constant(new DataValue.IntegerValue(0))), Map.of("value", "value"));
        draft.place(List.of(procedure, new FlowProgram.Evaluate("calc", "calc", Optional.of(new FlowCallBindings(Map.of(), Map.of("value", "out")))),
            new FlowProgram.EndProcedure("definition_end")), null, new ProgramDocument.Position(32, 134), Map.of("calc", graph));
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(draft.snapshot()));
        var screen = FlowCallScreenTest.editor(workspace, 640);
        set(screen, "height", 376); screen.init();
        var canvas = (FlowBlockCanvas)get(screen, "canvas");
        var hat = canvas.blocks().stream().filter(b -> b.step().sourceId().equals("definition")).findFirst().orElseThrow();
        var font = net.minecraft.client.Minecraft.getInstance().font;
        double y = (int)get(canvas, "y") + hat.y() + 19;
        // Aim at the painted chip, independently of the hit-test implementation.
        var preview = ScratchSignaturePreview.layout(font, "offset @ 1",
            List.of(new ScratchSignaturePreview.Parameter("n", false)));
        double x = (int)get(canvas, "x") + hat.x() + 10
            + font.width(Component.translatable("create_biotech.biocomputing.flow_editor.procedure_define_caption"))
            + preview.chips().getFirst().x() + 6;
        var before = workspace.snapshot();
        var outside = slot(screen, "0", 0);
        screen.mouseClicked(x, y, 0); screen.mouseDragged(outside.x()+8, outside.y()+8, 0, 50, 0); screen.mouseReleased(outside.x()+8, outside.y()+8, 0);
        assertEquals(before, workspace.snapshot(), "A same-named main-task variable is not this parameter");
        var body = slot(screen, "0", 1);
        FlowPaletteScreenTest.search(screen).setValue("subtract_integer");
        var subtract = button(screen, "inline_subtract_integer");
        screen.mouseClicked(subtract.getX()+8, subtract.getY()+8, 0); screen.mouseReleased(body.x()+8, body.y()+8, 0);
        var view = InlineGraphView.layout(workspace.flow().orElseThrow().inline().get("calc"), font::width);
        assertTrue(view.children().getFirst().x() + view.children().getFirst().tile().width() < view.labelX());
        assertTrue(view.labelX() < view.children().getLast().x(), "The glyph sits between the two real drop targets");
        var left = slot(screen, "0", 1);
        screen.mouseClicked(x, y, 0); screen.mouseDragged(left.x()+8, left.y()+8, 0, 50, 0); screen.mouseReleased(left.x()+8, left.y()+8, 0);
        assertEquals(Map.of("n", "n"), ((FlowProgram.Evaluate)workspace.flow().orElseThrow().canvas().detached().getFirst().steps().get(1))
            .bindings().orElseThrow().arguments());
        var attached = workspace.snapshot();
        var operand = slot(screen, "n", 0);
        screen.mouseClicked(operand.x()+8, operand.y()+8, 0);
        screen.mouseDragged(outside.x()+8, outside.y()+8, 0, 50, 0);
        screen.mouseReleased(outside.x()+8, outside.y()+8, 0);
        assertEquals(attached, workspace.snapshot(), "Moving an already-connected parameter must not rebind it to the main task");

        double parkX = (int)get(canvas, "x") + (int)get(canvas, "width") - 55;
        double parkY = (int)get(canvas, "y") + 32;
        assertNull(canvas.blockAt(parkX, parkY));
        screen.mouseClicked(x, y, 0);
        screen.mouseDragged(parkX, parkY, 0, 50, 0);
        screen.mouseReleased(parkX, parkY, 0);
        var parked = workspace.snapshot();
        assertEquals(1, parked.flow().orElseThrow().canvas().values().parked().size());
        var reopened = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(parked));
        assertEquals(parked, reopened);
        var reopenedWorkspace = new ProgramWorkspace(reopened);
        var restoredScreen = FlowCallScreenTest.editor(reopenedWorkspace, 640);
        set(restoredScreen, "height", 376); restoredScreen.init();
        var restoredCanvas = (FlowBlockCanvas)get(restoredScreen, "canvas");
        var mainSlot = slot(restoredScreen, "0", 0);
        double fromX = (int)get(restoredCanvas, "x") + 3 + parked.flow().orElseThrow().canvas().values().parked().getFirst().position().x();
        double fromY = (int)get(restoredCanvas, "y") + 8 + parked.flow().orElseThrow().canvas().values().parked().getFirst().position().y();
        restoredScreen.mouseClicked(fromX, fromY, 0);
        restoredScreen.mouseDragged(mainSlot.x()+8, mainSlot.y()+8, 0, 40, 0);
        restoredScreen.mouseReleased(mainSlot.x()+8, mainSlot.y()+8, 0);
        assertEquals(reopened, reopenedWorkspace.snapshot(), "A cold reopen must not turn the copy into an unscoped same-name variable");
        var right = slot(restoredScreen, "0", 1);
        restoredScreen.mouseClicked(fromX, fromY, 0);
        restoredScreen.mouseDragged(right.x()+8, right.y()+8, 0, 40, 0);
        restoredScreen.mouseReleased(right.x()+8, right.y()+8, 0);
        assertTrue(reopenedWorkspace.flow().orElseThrow().canvas().values().parked().isEmpty());
        assertEquals(new DataValue.IntegerValue(0), run(reopenedWorkspace.snapshot()).field("answer"));

        click(screen, "undo"); assertEquals(attached, workspace.snapshot());
        setLiteral(screen, slot(screen, "0", 1), "3");
        var document = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot()));
        assertEquals(new DataValue.IntegerValue(6), run(document).field("answer"));
        assertEquals(Map.of("n", number), ((FlowProgram.Procedure)document.flow().orElseThrow().canvas().detached().getFirst().steps().getFirst()).inputs());
    }
    static FlowBlockCanvas.Slot slot(ComputerFlowScreen screen, String label, int index) throws Exception {
        return ((FlowBlockCanvas)get(screen,"canvas")).inlineSlots().stream().filter(s -> !s.target() && s.label().equals(label)).skip(index).findFirst().orElseThrow();
    }
    static void dropPrivateVariable(ComputerFlowScreen screen,String name,FlowBlockCanvas.Slot slot) throws Exception {
        click(screen,"category_variables");
        var reporters=(Map<com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton,InlineGraphEdits.Source>)get(screen,"reporters");
        for(int page=0;reporters.values().stream().noneMatch(v->v instanceof InlineGraphEdits.Variable variable && variable.name().equals(name));page++) {
            assertTrue(page<10);click(screen,"palette_next");reporters=(Map<com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton,InlineGraphEdits.Source>)get(screen,"reporters");
        }
        var reporter=reporters.entrySet().stream().filter(e->e.getValue() instanceof InlineGraphEdits.Variable v && v.name().equals(name)).findFirst().orElseThrow().getKey();
        screen.mouseClicked(reporter.getX()+8,reporter.getY()+8,0);screen.mouseDragged(slot.x()+8,slot.y()+slot.height()/2,0,40,0);screen.mouseReleased(slot.x()+8,slot.y()+slot.height()/2,0);
    }
    static void setLiteral(ComputerFlowScreen screen, FlowBlockCanvas.Slot slot, String value) {
        screen.mouseClicked(slot.x()+8,slot.y()+slot.height()/2,0); screen.mouseReleased(slot.x()+8,slot.y()+slot.height()/2,0); boxes(screen).getFirst().setValue(value);
        screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER,0,0);
    }
    static void dropReporter(ComputerFlowScreen screen, String key, FlowBlockCanvas.Slot slot) throws Exception {
        click(screen, key.equals("inline_private") ? "category_variables" : key.startsWith("inline_list_") || key.equals("inline_record_make") || key.equals("inline_is_null") || key.equals("inline_is_error") ? "category_data" : "category_operators");
        int pages = 0;
        while (screen.children().stream().noneMatch(child -> child instanceof com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton b
                && b.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                && t.getKey().equals("create_biotech.biocomputing.flow_editor."+key))) { assertTrue(pages++ < 20); assertTrue(button(screen,"palette_next").active); click(screen,"palette_next"); }
        var block=button(screen,key); screen.mouseClicked(block.getX()+8,block.getY()+8,0);
        screen.mouseDragged(slot.x()+8,slot.y()+slot.height()/2,0,40,0);
        screen.mouseReleased(slot.x()+8,slot.y()+slot.height()/2,0);
    }
    static DataValue.RecordValue run(ProgramDocument document) {
        var body = document.flow().orElseThrow(); var limits = new ResourceLimits(200, 100000, 1);
        var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), body.program(document.archive()), body.initial(), limits); runtime.load();
        for (int i = 0; i < 1000 && runtime.checkpoint().status() != FlowRuntime.Status.COMPLETE; i++) runtime.advanceRound(Map.of());
        assertEquals(FlowRuntime.Status.COMPLETE, runtime.checkpoint().status()); return runtime.checkpoint().locals();
    }
}
