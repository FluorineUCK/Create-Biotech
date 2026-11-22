package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.Screen;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScratchMainSignaturePreviewTest {
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();

    @BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception {
        environment.client();
        var client = net.minecraft.client.Minecraft.getInstance();
        var options = allocate(net.minecraft.client.Options.class);
        set(options, "touchscreen", net.minecraft.client.OptionInstance.createBoolean("test.touchscreen", false));
        set(options, "keyPickItem", new net.minecraft.client.KeyMapping("test.pick",
            com.mojang.blaze3d.platform.InputConstants.Type.MOUSE, 2, "test"));
        set(options, "keyInventory", new net.minecraft.client.KeyMapping("test.inventory", org.lwjgl.glfw.GLFW.GLFW_KEY_E, "test"));
        set(client, "options", options);
        set(client, "font", new ComputerProgramScreenTest.Metrics() {
            @Override public int width(String value) { return super.width(value) * 6; }
            @Override public String plainSubstrByWidth(String value, int width, boolean reverse) {
                int length = Math.clamp(width / 6, 0, value.length()); return reverse ? value.substring(value.length() - length) : value.substring(0, length);
            }
            @Override public String plainSubstrByWidth(String value, int width) {
                return plainSubstrByWidth(value, width, false);
            }
        });
    }
    @AfterEach void restoreClient() throws Exception { environment.restore(); }

    @ParameterizedTest
    @ValueSource(ints = {320, 640})
    void anOverflowingSignatureCanBeDraggedToBothEndsWithoutEditingTheDraft(int width) throws Exception {
        var fixture = openDefinition(width);
        var form = fixture.form();
        var fields = boxes(form);
        fields.getFirst().setValue("weather_station_".repeat(8));
        fields.get(1).setValue("123");
        fields.get(2).setValue("same_prefix_".repeat(7) + "threshold");

        int left = (int) get(form, "leftPos") + 8;
        int right = (int) get(form, "leftPos") + (int) get(form, "imageWidth") - 8;
        int y = (int) get(form, "topPos") + 65;
        int maximum = 14 + fields.getFirst().getValue().length() * 6 + (" @ 123".length() * 6)
            + fields.get(2).getValue().length() * 6 + 14 + 4 + 1 - (right - left);
        assertTrue(maximum > 0);

        assertTrue(form.mouseClicked(left + 4, y, 0), "The visible thumb at the left edge must capture the press");
        assertTrue(form.mouseDragged(right + 12, y + 32, 0, right - left + 8, 32));
        assertEquals(maximum, get(form, "previewOffset"), "The tail and last parameter can be reached in one drag");
        assertTrue(form.mouseReleased(right + 12, y + 32, 0));
        form.mouseDragged(left + 4, y, 0, left - right, 0);
        assertEquals(maximum, get(form, "previewOffset"), "A release outside the track ends capture");

        assertTrue(form.mouseClicked(right - 4, y, 0));
        assertTrue(form.mouseDragged(left - 10, y, 0, left - right - 6, 0));
        assertEquals(0, get(form, "previewOffset"), "Dragging back reveals the start of the name");
        assertTrue(form.mouseReleased(left - 10, y, 0));

        assertTrue(form.mouseClicked((left + right) / 2, y, 0), "Clicking the track moves to the requested section");
        int midpoint = (int) get(form, "previewOffset");
        assertTrue(0 < midpoint && midpoint < maximum);
        assertTrue(form.mouseReleased((left + right) / 2, y, 0));
        assertTrue(form.mouseScrolled(left + 4, y - 15, 0, -1));
        assertTrue((int) get(form, "previewOffset") > midpoint, "Wheel and drag share the same offset");

        assertEquals("123", boxes(form).get(1).getValue());
        click(form, "cancel");
        assertEquals(fixture.before(), fixture.workspace().snapshot(), "Preview scrolling must not publish edits");
    }

    @Test
    void aFittingSignatureDoesNotScrollFromThePreviewGutter() throws Exception {
        var form = openDefinition(320).form();
        int x = (int) get(form, "leftPos") + 12, y = (int) get(form, "topPos") + 65;
        form.mouseClicked(x, y, 0);
        form.mouseDragged(x + 10, y, 0, 10, 0);
        assertEquals(0, get(form, "previewOffset"));
    }

    @Test void directBooleanAndTextInsertionKeepTheirTypesAndOnlyApplyOnConfirm() throws Exception {
        var fixture=openDefinition(320);
        var form=fixture.form();
        click(form,"procedure_add_boolean");
        assertEquals(fixture.before(),fixture.workspace().snapshot());
        click(form,"procedure_add_text");
        assertEquals(fixture.before(),fixture.workspace().snapshot());
        click(form,"procedure_apply");
        var stored=FlowProcedureTools.definitions(fixture.workspace().flow().orElseThrow()).getFirst();
        assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.BOOLEAN, stored.inputs().get("condition1"));
        assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.TEXT, stored.inputs().get("text1"));
    }

    private static Fixture openDefinition(int width) throws Exception {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var before = workspace.snapshot();
        var editor = FlowCallScreenTest.editor(workspace, width);
        var navigation = new AtomicReference<Screen>();
        set(editor, "navigator", (Consumer<Screen>) navigation::set);
        click(editor, "category_custom");
        click(editor, "define_procedure");
        var form = assertInstanceOf(ComputerFlowProcedureScreen.class, navigation.get());
        FlowProcedureScreenTest.initializeForm(form, width);
        return new Fixture(workspace, before, form);
    }

    private record Fixture(ProgramWorkspace workspace, ProgramDocument before, ComputerFlowProcedureScreen form) {}
}

