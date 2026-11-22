package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FlowWaitUnitEditingTest {
    private final ComputerProgramScreenTest client = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void start() throws Exception { client.client(); }
    @AfterEach void stop() throws Exception { client.restore(); }

    @ParameterizedTest @EnumSource(FlowProgram.WaitUnit.class)
    void editingAnImportedWaitPreservesItsUnitAndUndo(FlowProgram.WaitUnit unit) throws Exception {
        var p = new FlowProgram(Map.of(), List.of(new FlowProgram.Sleep("wait",
            new FlowInput.Literal(new DataValue.IntegerValue(2)), Optional.of(unit)))).withAssembly();
        var ws = workspace(p, new DataValue.RecordValue(Map.of()));
        var before = ws.snapshot();
        var screen = FlowCallScreenTest.editor(ws, 640);
        selectWait(screen);
        click(screen, "edit");
        boxes(screen).getFirst().setValue("3");
        click(screen, "apply");
        var wait = assertInstanceOf(FlowProgram.Sleep.class, ws.flow().orElseThrow().steps().getFirst());
        assertEquals(Optional.of(unit), wait.unit(), "Editing an imported seconds/minutes block must not turn it into ticks");
        assertEquals(new FlowInput.Literal(new DataValue.IntegerValue(3)), wait.ticks());
        assertEquals(ws.snapshot(), ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(ws.snapshot())));
        click(screen, "undo"); assertEquals(before, ws.snapshot());
    }

    @Test void aDecimalVariableCanFillASecondsSlotWithoutLosingTheUnit() throws Exception {
        var p = new FlowProgram(Map.of("duration", DataType.Scalar.DECIMAL), List.of(new FlowProgram.Sleep("wait",
            new FlowInput.Literal(new DataValue.IntegerValue(1)), Optional.of(FlowProgram.WaitUnit.SECONDS)))).withAssembly();
        var ws = workspace(p, new DataValue.RecordValue(Map.of("duration", new DataValue.DecimalValue(0.125))));
        var screen = FlowCallScreenTest.editor(ws, 640);
        FlowInlineScreenTest.dropPrivateVariable(screen, "duration", FlowInlineScreenTest.slot(screen, "1", 0));
        var doc = ws.snapshot(); var body = doc.flow().orElseThrow();
        var wait = assertInstanceOf(FlowProgram.Sleep.class, body.steps().getFirst());
        assertEquals(Optional.of(FlowProgram.WaitUnit.SECONDS), wait.unit());
        assertInstanceOf(FlowInput.Expression.class, wait.ticks(), "The decimal reporter must be accepted rather than silently rejected");
        var flow = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), body.program(doc.archive()), body.initial(),
            new ResourceLimits(1024, 51200, 64));
        flow.advance(64);
        assertEquals(FlowRuntime.Status.SLEEPING, flow.checkpoint().status());
        assertEquals(3, flow.checkpoint().sleepTicks());
    }

    private static ProgramWorkspace workspace(FlowProgram p, DataValue.RecordValue initial) {
        var ws = new ProgramWorkspace(); ws.setFlow(Optional.of(FlowDocumentBody.fromProgram(p, initial))); return ws;
    }
    private static void selectWait(ComputerFlowScreen screen) throws Exception {
        var canvas = (FlowBlockCanvas)get(screen, "canvas"); canvas.focus("wait");
        var block = canvas.blocks().stream().filter(b -> b.step().sourceId().equals("wait")).findFirst().orElseThrow();
        var vp = (CanvasViewport)get(canvas, "viewport");
        double x = (int)get(canvas, "x") + vp.screenX(block.x() + 5);
        double y = (int)get(canvas, "y") + vp.screenY(block.y() + 5);
        assertTrue(screen.mouseClicked(x, y, 0)); screen.mouseReleased(x, y, 0);
        assertEquals("wait", canvas.selected());
    }
}
