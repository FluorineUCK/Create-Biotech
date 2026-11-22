package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import java.util.*;
import net.minecraft.client.gui.components.EditBox;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScratchInlineWaitParityTest {
    private final ComputerProgramScreenTest client = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void initialize() throws Exception { client.client(); }
    @AfterEach void restore() throws Exception { client.restore(); }

    @ParameterizedTest @ValueSource(ints = {320, 640})
    void changeExistingWaitAmountAndUnitIndependentlyWithoutOpeningAParameterForm(int width) throws Exception {
        var workspace = new ProgramWorkspace();
        var implicitTicks = new FlowProgram.Sleep("pause", new FlowInput.Literal(new DataValue.IntegerValue(1)));
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(), List.of(implicitTicks),
            new DataValue.RecordValue(Map.of()), FlowBlockLayout.DEFAULT, Map.of(), FlowProgram.Execution.REGISTER_V1)));
        var before = workspace.snapshot();
        var editor = FlowCallScreenTest.editor(workspace, width);
        openUnit(editor);
        assertFalse(button(editor, "wait_unit_ticks").active, "Unedited older waits still use ticks");
        assertTrue(editor.keyPressed(256, 0, 0));
        assertEquals(before, workspace.snapshot(), "Opening and dismissing the unit is not an edit");

        openUnit(editor);
        click(editor, "wait_unit_seconds");
        assertEquals(implicitTicks.ticks(), step(workspace).ticks(), "Changing units must not rescale or replace the amount");
        var seconds = workspace.snapshot();
        amount(editor, "0.125");
        var decimal = workspace.snapshot();
        assertEquals(decimal, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(decimal)));
        assertEquals(3, sleepTicks(decimal), "Fractional seconds use the existing ISA rounding rule");
        openUnit(editor); click(editor, "wait_unit_minutes");
        var minutes = workspace.snapshot();
        assertEquals(150, sleepTicks(minutes));
        assertEquals(decimal.flow().orElseThrow().inline(), minutes.flow().orElseThrow().inline(), "Changing units preserves the expression graph");
        assertFalse((boolean)get(editor, "parameters"), "Routine editing stays on the Scratch canvas");
        click(editor,"undo"); assertEquals(decimal,workspace.snapshot());
        click(editor,"undo"); assertEquals(seconds,workspace.snapshot());
        click(editor,"undo"); assertEquals(before,workspace.snapshot());
    }

    @Test void editingAnImplicitTickAmountDoesNotRequireFirstChangingTheUnit() throws Exception {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(),
            List.of(new FlowProgram.Sleep("pause", new FlowInput.Literal(new DataValue.IntegerValue(1)))),
            new DataValue.RecordValue(Map.of()), FlowBlockLayout.DEFAULT, Map.of(), FlowProgram.Execution.REGISTER_V1)));
        var editor = FlowCallScreenTest.editor(workspace, 426);
        amount(editor, "1.1");
        assertEquals(FlowProgram.WaitUnit.TICKS, step(workspace).displayedUnit());
        assertEquals(2, sleepTicks(workspace.snapshot()));
    }

    private static FlowProgram.Sleep step(ProgramWorkspace workspace) { return (FlowProgram.Sleep)workspace.flow().orElseThrow().steps().getFirst(); }
    private static FlowBlockCanvas.Slot amountSlot(ComputerFlowScreen editor) throws Exception {
        return ((FlowBlockCanvas)get(editor, "canvas")).inlineSlots().stream().filter(s -> s.call().equals("pause")).findFirst().orElseThrow();
    }
    private static void openUnit(ComputerFlowScreen editor) throws Exception {
        var amount = amountSlot(editor);
        openUnitAt(editor, amount);
        assertNotNull(button(editor, "wait_unit_minutes"), "Click directly after the amount to select the unit");
    }
    static void chooseUnit(ComputerFlowScreen editor, FlowBlockCanvas.Slot amount, FlowProgram.WaitUnit unit) {
        openUnitAt(editor, amount);
        click(editor, "wait_unit_" + unit.name().toLowerCase(Locale.ROOT));
    }
    private static void openUnitAt(ComputerFlowScreen editor, FlowBlockCanvas.Slot amount) {
        double x = amount.x() + amount.width() + 10, y = amount.y() + amount.height() / 2;
        assertTrue(editor.mouseClicked(x, y, 0));
    }
    private static void amount(ComputerFlowScreen editor, String text) throws Exception {
        var amount = amountSlot(editor);
        assertTrue(editor.mouseClicked(amount.x()+amount.width()/2, amount.y()+amount.height()/2,0));
        assertInstanceOf(EditBox.class, get(editor,"inlineField")).setValue(text);
        assertTrue(editor.keyPressed(257,0,0));
        assertNull(get(editor,"inlineField"), "An accepted number closes the editor");
    }
    private static long sleepTicks(ProgramDocument doc) {
        var body = doc.flow().orElseThrow();
        var limits = new ComputerHostLimits(new ResourceLimits(512,65_536,32), SubgraphWorkspaceTest.LIMITS);
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(body.program(doc.archive()), body.initial(), limits, true);
        host.setPaused(false, limits, true);
        host.tick(1, limits, true, Map.of());
        return host.flowSnapshot().orElseThrow().state().sleepTicks();
    }
}
