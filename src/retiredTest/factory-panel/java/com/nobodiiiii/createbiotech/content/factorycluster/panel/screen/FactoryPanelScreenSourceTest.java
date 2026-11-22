package com.nobodiiiii.createbiotech.content.factorycluster.panel.screen;

import com.nobodiiiii.createbiotech.content.biocomputing.editor.PanelEditBox;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class FactoryPanelScreenSourceTest {
    static final Path SOURCE = Path.of("src/main/java/com/nobodiiiii/createbiotech/content/factorycluster/panel/screen");

    @Test void primaryViewRenderPathsUseAtlasNotRuntimeFilledPanels() throws Exception {
        for (String name : List.of("FactoryPanelScreen", "TasksView", "ComputerMonitorView",
                "NewTaskView", "CommunicationNetworkView", "LogisticsNetworkView")) {
            String source = Files.readString(SOURCE.resolve(name + ".java"));
            assertTrue(source.contains("FactoryPanelAtlas.blit(graphics,"), name + " must actually render atlas regions");
            assertFalse(source.contains(".fill("), name + " primary rectangles must be resource-owned");
            assertFalse(source.matches("(?s).*FactoryPanelScreenPalette\\.(panel|button|row|scrollbar)\\(.*"), name);
        }
    }

    @Test void skinPreservesWindowNavigationScrollAndCardGeometry() throws Exception {
        String shell = Files.readString(SOURCE.resolve("FactoryPanelScreen.java"));
        assertTrue(shell.contains("Region.WINDOW, leftPos, topPos, WIDTH, HEIGHT"));
        assertTrue(shell.contains("Region.TITLE, leftPos, topPos, WIDTH, HEADER_HEIGHT"));
		assertTrue(shell.contains("new CreateTextButton(font, leftPos + x, topPos + y,"));
		assertTrue(shell.contains("tab.visible = view != FactoryPanelView.NETWORK_LOGISTICS || isNetwork(selectedView)"));
		assertTrue(shell.contains("new CreateTextButton(font, absoluteLeft(x),"));
		assertTrue(shell.contains("absoluteTop(y), width, height, label, action)"));
        assertTrue(shell.contains("Region.SCROLL_TRACK, left, geometry.trackTop(),"));
        assertTrue(shell.contains("right - left, geometry.trackBottom() - geometry.trackTop()"));
        assertTrue(shell.contains("Region.SCROLL_THUMB, left, geometry.thumbTop(state.scrollOffset(viewport)),"));
        assertTrue(shell.contains("right - left, geometry.thumbLength()"));
        String monitor = Files.readString(SOURCE.resolve("ComputerMonitorView.java"));
        assertTrue(monitor.contains("FactoryPanelAtlas.computerState(card.aggregateState())"));
        assertTrue(monitor.contains("FactoryPanelAtlas.node(false)"));
        assertTrue(monitor.contains("left, y, right - left, FactoryPanelScreen.ROW_HEIGHT - 2"));
        String form = Files.readString(SOURCE.resolve("NewTaskView.java"));
        assertTrue(form.contains("Region.POPUP, screen.absoluteLeft(10),"));
    }

    @Test void taskAndSlotCardsRenderTheirExistingTypedState() throws Exception {
        String tasks = Files.readString(SOURCE.resolve("TasksView.java"));
        String monitor = Files.readString(SOURCE.resolve("ComputerMonitorView.java"));
        assertTrue(tasks.contains("FactoryPanelAtlas.taskState(task.state())"));
        assertTrue(monitor.contains("FactoryPanelAtlas.slotState(slot)"));
    }

    @Test void allEditableInputsReplaceOnlyVanillaBackgroundNotEditingGeometry() throws Exception {
        for (String name : List.of("NewTaskView", "CommunicationNetworkView", "LogisticsNetworkView")) {
            String source = Files.readString(SOURCE.resolve(name + ".java"));
            assertTrue(source.contains("new PanelEditBox("), name);
            assertFalse(source.contains("new EditBox("), name);
        }
        Path wrapper = Path.of("src/main/java/com/nobodiiiii/createbiotech/content/biocomputing/editor/PanelEditBox.java");
        assertTrue(Files.isRegularFile(wrapper));
        String source = Files.readString(wrapper);
        assertTrue(source.contains("extends EditBox"));
        assertTrue(source.contains("textures/gui/factory_panel_monitor.png"));
        assertTrue(source.contains("getX(), getY(), getWidth(), getHeight(), 304, 0, 160, 20, 512, 512"));
        assertTrue(source.contains("super.renderWidget(graphics, mouseX, mouseY, partialTick)"));
        assertFalse(source.contains("setBordered(false)"), "private bordered flag controls vanilla text/click padding");
        assertFalse(source.contains("setX(")); assertFalse(source.contains("setY("));
    }

    @Test void actionablePanelViewButtonsAreRegisteredCreateWidgetsNotManualHitRegions() throws Exception {
        for (String name : List.of("TasksView", "NewTaskView", "CommunicationNetworkView",
                "LogisticsNetworkView")) {
            String source = Files.readString(SOURCE.resolve(name + ".java"));
            assertTrue(source.contains("CreateTextButton"), name + " must own real Create buttons");
            assertFalse(source.contains("FactoryPanelAtlas.button("),
                name + " must not paint fake buttons");
        }
        String shell = Files.readString(SOURCE.resolve("FactoryPanelScreen.java"));
        assertTrue(shell.contains("registerViewButton("));
		String communication = Files.readString(SOURCE.resolve("CommunicationNetworkView.java"));
		assertFalse(communication.contains("Region.NAV_SELECTED"));
		assertFalse(communication.contains("Region.NAV_NORMAL"));
		assertTrue(communication.contains("pageButtons"));
	}

	@Test void createTextButtonStretchesEveryNativeStateAcrossItsActualWidgetBounds() throws Exception {
		String source = Files.readString(Path.of(
			"src/main/java/com/nobodiiiii/createbiotech/foundation/gui/CreateTextButton.java"));
		assertTrue(source.contains("protected void drawBg(GuiGraphics graphics, AllGuiTextures button)"));
		assertTrue(source.contains("getWidth() - 2 * CORNER"));
		assertTrue(source.contains("getHeight() - 2 * CORNER"));
    }
}
