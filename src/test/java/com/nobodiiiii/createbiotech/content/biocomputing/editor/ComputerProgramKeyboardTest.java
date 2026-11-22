package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.lwjgl.glfw.GLFW;

/** Actual keyboard dispatch and widget callbacks; only the GPU outline sink is replaced. */
class ComputerProgramKeyboardTest {
    private final ComputerProgramScreenTest fixture = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { fixture.client(); }
    @AfterEach void restore() throws Exception { fixture.restore(); }

    @ParameterizedTest
    @CsvSource({"320,palette", "640,palette", "320,node", "640,node", "320,bindings", "320,declarations",
        "640,declarations", "320,modules", "640,modules", "320,entries", "640,entries"})
    void tabCyclesOnlyCurrentVisibleEnabledControlsWithoutApplyingOrInstalling(int width, String view) throws Exception {
        var screen = screen(); set(screen, "width", width); set(screen, "height", width == 320 ? 240 : 360); screen.init();
        switch (view) {
            case "palette" -> {
                button(screen, "add").onClick(0, 0);
                ((EditBox)get(screen, "paletteSearch")).setValue("integer");
            }
            case "node", "bindings" -> {
                addNode(screen, "integer");
                if (view.equals("bindings")) button(screen, "node_bindings").onClick(0, 0);
            }
            default -> button(screen, view).onClick(0, 0);
        }
        var controls = screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
            .filter(w -> w.active && w.visible).sorted(Comparator.comparingInt(AbstractWidget::getTabOrderGroup)).toList();
        assertFalse(controls.isEmpty());
        var draft = (ProgramDraft)get(screen, "draft"); var before = List.copyOf(draft.nodes());
        for (boolean forward : List.of(true, false)) {
            screen.setFocused(null);
            for (int i = 0; i <= controls.size(); i++) {
                assertTrue(screen.keyPressed(GLFW.GLFW_KEY_TAB, 0, forward ? 0 : GLFW.GLFW_MOD_SHIFT));
                var expected = controls.get(forward ? i % controls.size() : controls.size() - 1 - i % controls.size());
                assertSame(expected, screen.getFocused(), "Tab order at " + view + " / " + i);
            }
        }
        assertEquals(before, draft.nodes());
        assertFalse((boolean)get(screen, "requested"));
    }

    @ParameterizedTest @CsvSource({"320,257", "640,257", "320,32", "640,32"})
    void buttonActivationIgnoresHiddenDisabledAndRemovedControls(int width, int key) throws Exception {
        for (String condition : List.of("hidden", "disabled", "removed")) {
            var screen = screen(); set(screen, "width", width); set(screen, "height", 240); screen.init();
            addNode(screen, "integer"); var configure = button(screen, "configure");
            if (condition.equals("hidden")) configure.visible = false;
            if (condition.equals("disabled")) configure.active = false;
            if (condition.equals("removed")) screen.init();
            screen.setFocused(configure); assertTrue(screen.keyPressed(key, 0, 0));
            assertNull(((ProgramDraft)get(screen, "draft")).nodes().getFirst().definition());
            assertFalse((boolean)get(screen, "requested"));
            if (condition.equals("removed")) assertNull(screen.getFocused());
        }
    }

    @ParameterizedTest @CsvSource({"320,257", "640,257", "320,32", "640,32"})
    void keyboardConfigurationUsesExistingCallbackAndDoesNotInstall(int width, int activation) throws Exception {
        var screen = screen(); set(screen, "width", width); set(screen, "height", 240); screen.init();
        addNode(screen, "integer"); var field = (EditBox)get(screen, "parameter");
        screen.setFocused(field); field.setValue("42");
        assertTrue(screen.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0));
        assertSame(button(screen, "configure"), screen.getFocused());
        assertTrue(screen.keyPressed(activation, 0, 0));
        assertEquals(new com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph.Constant(
            new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.IntegerValue(42)),
            ((ProgramDraft)get(screen, "draft")).nodes().getFirst().definition());
        assertFalse((boolean)get(screen, "requested"));
        assertTrue(screen.getFocused() == null || screen.children().contains(screen.getFocused()));
    }

    @Test void typingAndEscapeStayLocalAndDoNotFallThroughToContainerShortcuts() throws Exception {
        var screen = screen(); addNode(screen, "text");
        var field = (EditBox)get(screen, "parameter"); screen.setFocused(field);
        for (char c : "e r".toCharArray()) {
            assertTrue(screen.keyPressed(Character.toUpperCase(c), 0, 0));
            assertTrue(screen.charTyped(c, 0));
        }
        assertEquals("e r", field.getValue());
        assertTrue(screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0));
        assertNull(get(screen, "editing")); assertNull(((ProgramDraft)get(screen, "draft")).nodes().getFirst().definition());
        assertFalse((boolean)get(screen, "requested"));
    }

    @Test void searchingAgainCannotActivateAnOldFocusedDirectoryRow() throws Exception {
        var screen = screen(); button(screen, "add").onClick(0, 0);
        var search = (EditBox)get(screen, "paletteSearch"); search.setValue("integer");
        screen.setFocused(search); screen.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0); var old = screen.getFocused();
        search.setValue("text"); screen.setFocused(old);
        screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
        assertTrue(((ProgramDraft)get(screen, "draft")).nodes().isEmpty());
        assertSame(search, screen.getFocused());
    }

    @Test void keyboardFocusHasVisibleOutlineOnlyForEligibleCurrentButtons() throws Exception {
        var screen = screen(); button(screen, "add").onClick(0, 0);
        var search = (EditBox)get(screen, "paletteSearch"); search.setValue("integer");
        screen.setFocused(search); screen.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0);
        var control = (CreateTextButton)screen.getFocused(); var graphics = new Outlines();
        screen.renderForeground(graphics, -100, -100, 0);
        assertEquals(List.of(List.of(control.getX(), control.getY(), control.getWidth(), control.getHeight(), 0xffaaccee)), graphics.values);
        graphics.values.clear(); control.active = false; screen.renderForeground(graphics, -100, -100, 0); assertTrue(graphics.values.isEmpty());
        control.active = true; control.visible = false; screen.renderForeground(graphics, -100, -100, 0); assertTrue(graphics.values.isEmpty());
        control.visible = true; search.setValue("text"); screen.setFocused(control);
        screen.renderForeground(graphics, -100, -100, 0); assertTrue(graphics.values.isEmpty());
    }

    private static CreateTextButton button(ComputerProgramScreen screen, String name) {
        return screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b -> b.getMessage().getContents() instanceof TranslatableContents t
                && t.getKey().equals("create_biotech.biocomputing.editor." + name)).findFirst().orElseThrow();
    }
    private static final class Outlines extends GuiGraphics {
        final List<List<Integer>> values = new ArrayList<>();
        Outlines() { super(net.minecraft.client.Minecraft.getInstance(), null); }
        @Override public void renderOutline(int x, int y, int w, int h, int color) { values.add(List.of(x, y, w, h, color)); }
    }
}
