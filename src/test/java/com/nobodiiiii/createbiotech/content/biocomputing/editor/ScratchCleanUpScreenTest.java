package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.click;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.api.*;

class ScratchCleanUpScreenTest {
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { environment.client(); }
    @AfterEach void restore() throws Exception { environment.restore(); }

    @Test void cleanUpSeparatesRecoveredNestedScriptAndDefinitionWithoutChangingTheProgram() throws Exception {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var count = new FlowInput.Literal(new DataValue.IntegerValue(2));
        var definition = new FlowProgram.Procedure("define", new ModuleDefinition.Key("report", 1),
            Map.of(), Map.of(), Map.of(), FlowBlockRules.NONE);
        var originalSteps = List.<FlowProgram.Step>of(new FlowProgram.Repeat("outer", count),
            new FlowProgram.Repeat("middle", count), new FlowProgram.Repeat("inner", count),
            new FlowProgram.Sleep("sleep", count), new FlowProgram.EndRepeat("end_inner"),
            new FlowProgram.EndRepeat("end_middle"), new FlowProgram.EndRepeat("end_outer"),
            definition, new FlowProgram.EndProcedure("end_define"));
        var installed = new FlowProgram(Map.of(), originalSteps, workspace.snapshot().archive(),
            FlowProgram.Execution.REGISTER_V1);
        var recovered = FlowDocumentBody.fromProgram(installed, new DataValue.RecordValue(Map.of()));
        var literal = new ComputationGraph(Map.of(), Map.of("text", new ComputationGraph.Constant(new DataValue.TextValue("keep"))),
            Map.of("value", "text"));
        var parked = new ScratchValueLayout.Reporter(new ProgramDocument.Position(300, 54), literal, Map.of(),
            Map.of("text", new DataValue.TextValue("fallback")));
        recovered = recovered.edited(recovered.inputs(), recovered.steps(), recovered.initial(),
            recovered.canvas().withValues(recovered.canvas().values().withParked(List.of(parked))), recovered.inline());
        workspace.setFlow(Optional.of(recovered));
        var before = workspace.snapshot();
        var screen = FlowCallScreenTest.editor(workspace, 640);
        var canvas = (FlowBlockCanvas)get(screen, "canvas");
        var loop = block(canvas, "outer");
        var oldDefinition = block(canvas, "define");
        assertTrue(loop.x() + loop.width() > oldDefinition.x() && oldDefinition.y() + oldDefinition.height() > loop.y(),
            "The recovered definition really overlaps the widened nested loop");
        assertEquals(before, workspace.snapshot(), "Opening does not rearrange a saved draft");

        double x = (int)get(canvas, "x") + 145, y = (int)get(canvas, "y") + 5;
        assertTrue(rightClick(screen, x, y), "Blank canvas opens its menu without a clipboard item");
        click(screen, "clean_up_blocks");
        var after = workspace.snapshot();
        var body = after.flow().orElseThrow();
        var separated = block(canvas, "define");
        var footer = block(canvas, "end_outer");
        var moved = body.canvas().values().parked().getFirst();
        assertTrue(separated.y() > footer.y() + footer.height(), "Definition is below the entire loop, not just the header");
        assertTrue(moved.position().y() > separated.y() + separated.height(), "Loose values do not cover the definition");
        assertEquals(loop.x(), separated.x());
        assertEquals(separated.x(), moved.position().x());
        assertEquals(parked.at(moved.position()), moved, "Only the parked value's position changed");
        assertEquals(installed, body.program(after.archive()));
        assertEquals(after, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(after)));

        assertTrue(rightClick(screen, x, y));
        click(screen, "clean_up_blocks");
        assertEquals(after, workspace.snapshot(), "A second cleanup is a no-op");
        click(screen, "undo");
        assertEquals(before, workspace.snapshot(), "All position changes undo together");
        click(screen, "redo");
        assertEquals(after, workspace.snapshot());
    }

    private static boolean rightClick(ComputerFlowScreen screen, double x, double y) throws Exception {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        var old = minecraft.options;
        var options = allocate(net.minecraft.client.Options.class);
        set(options, "touchscreen", net.minecraft.client.OptionInstance.createBoolean("test.touchscreen", false));
        set(options, "keyPickItem", new net.minecraft.client.KeyMapping("test.pick",
            com.mojang.blaze3d.platform.InputConstants.Type.MOUSE, 2, "test"));
        try {
            set(minecraft, "options", options);
            return screen.mouseClicked(x, y, 1);
        } finally {
            set(minecraft, "options", old);
        }
    }
    private static FlowBlockCanvas.Block block(FlowBlockCanvas canvas, String source) {
        return canvas.blocks().stream().filter(row -> row.step().sourceId().equals(source)).findFirst().orElseThrow();
    }
}
