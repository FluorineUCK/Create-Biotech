package com.nobodiiiii.createbiotech.content.factorycluster.panel.screen;

import com.nobodiiiii.createbiotech.content.biocomputing.editor.PanelEditBox;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerSlotProjection;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerSlotProjection.RemoteState;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.screen.FactoryPanelAtlas.Region;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerCardProjection.AggregateState;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.GoalTreeProjection.ExecutionState;
import net.minecraft.client.gui.Font;

class FactoryPanelAtlasBehaviorTest {
    @Test void inputSkinRetainsVanillaPaddingScrollSelectionAndHitbox() throws Exception {
        PanelEditBox box = (PanelEditBox) PanelEditBox.class.getDeclaredConstructors()[0]
            .newInstance(new HeadlessFont(), 10, 20, 18, 20, null);
        box.setValue("abcdefghijklmnop");
        assertEquals(10, box.getInnerWidth()); assertTrue(box.isBordered());
        assertEquals(10, box.getX()); assertEquals(20, box.getY());
        assertEquals(18, box.getWidth()); assertEquals(20, box.getHeight());
        assertTrue(box.isMouseOver(10, 20)); assertFalse(box.isMouseOver(28, 20));
        box.setCursorPosition(12); box.setHighlightPos(16);
        assertEquals("mnop", box.getHighlighted());
        var rendering = PanelEditBox.class.getDeclaredField("renderingText");
        rendering.setAccessible(true); rendering.setBoolean(box, true);
        assertFalse(box.isBordered()); assertEquals(10, box.getInnerWidth());
        rendering.setBoolean(box, false); assertTrue(box.isBordered());
        assertEquals("mnop", box.getHighlighted()); assertEquals(12, box.getCursorPosition());
        box.insertText("Z"); assertEquals("abcdefghijklZ", box.getValue());
    }

    @Test void typedMonitorStatusAndCardSelectionChooseSemanticAtlasRegions() {
        assertEquals(Region.STATE_IDLE, FactoryPanelAtlas.computerState(AggregateState.IDLE));
        assertEquals(Region.STATE_RUN, FactoryPanelAtlas.computerState(AggregateState.RUN));
        assertEquals(Region.STATE_SLEEP, FactoryPanelAtlas.computerState(AggregateState.SLP));
        assertEquals(Region.STATE_FAULT, FactoryPanelAtlas.computerState(AggregateState.FAULT));
        assertEquals(Region.STATE_IDLE, FactoryPanelAtlas.nodeState(ExecutionState.IDLE));
        assertEquals(Region.STATE_RUN, FactoryPanelAtlas.nodeState(ExecutionState.RUN));
        assertEquals(Region.STATE_SLEEP, FactoryPanelAtlas.nodeState(ExecutionState.SLP));
        assertEquals(Region.STATE_FAULT, FactoryPanelAtlas.nodeState(ExecutionState.FAULT));
        assertEquals(Region.CARD_NORMAL, FactoryPanelAtlas.card(false, Region.STATE_IDLE));
        assertEquals(Region.CARD_WAIT, FactoryPanelAtlas.card(false, Region.STATE_SLEEP));
        assertEquals(Region.CARD_FAULT, FactoryPanelAtlas.card(false, Region.STATE_FAULT));
        assertEquals(Region.CARD_SELECTED, FactoryPanelAtlas.card(true, Region.STATE_FAULT));
        assertEquals(Region.BUTTON_NORMAL, FactoryPanelAtlas.button(false, true));
        assertEquals(Region.BUTTON_PRESSED, FactoryPanelAtlas.button(false, false));
        assertEquals(Region.BUTTON_PRESSED, FactoryPanelAtlas.button(true, true));
        assertEquals(Region.NODE_NORMAL, FactoryPanelAtlas.node(false));
        assertEquals(Region.NODE_SELECTED, FactoryPanelAtlas.node(true));
    }

    @Test void taskStatesAndEmptySlotsHaveNonFaultPresentation() {
        assertEquals(Region.STATE_RUN, FactoryPanelAtlas.taskState(
            com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalState.RUNNABLE));
        assertEquals(Region.STATE_SLEEP, FactoryPanelAtlas.taskState(
            com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalState.WAITING));
        assertEquals(Region.STATE_IDLE, FactoryPanelAtlas.taskState(
            com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalState.TERMINAL));
        assertEquals(Region.STATE_IDLE, FactoryPanelAtlas.slotState(
            com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerSlotProjection.empty(0)));
    }

    @Test void occupiedSlotsShowWaitingRunningTerminalAndRemoteFaultWithoutLosingSelection() {
        ProcessState[] states = {ProcessState.RUNNABLE, ProcessState.WAITING, ProcessState.TERMINAL};
        Region[] expected = {Region.STATE_RUN, Region.STATE_SLEEP, Region.STATE_IDLE};
        for (int i = 0; i < states.length; i++) {
            for (RemoteState remote : new RemoteState[] {RemoteState.NONE, RemoteState.FAULT}) {
                var slot = ComputerSlotProjection.occupied(0, new UUID(0, 1), ProcessKind.GOAL,
                    new UUID(0, 2), "iron", states[i], states[i] == ProcessState.WAITING
                        ? Optional.of(WaitReason.MATERIAL) : Optional.empty(), remote, false);
                Region actual = FactoryPanelAtlas.slotState(slot);
                assertEquals(remote == RemoteState.FAULT ? Region.STATE_FAULT : expected[i], actual);
                assertEquals(Region.CARD_SELECTED, FactoryPanelAtlas.card(true, actual));
            }
        }
    }

    private static final class HeadlessFont extends Font {
        HeadlessFont() { super(ignored -> null, false); }
        @Override public int width(String text) { return text.length(); }
        @Override public String plainSubstrByWidth(String text, int width) {
            return text.substring(0, Math.min(text.length(), Math.max(0, width)));
        }
        @Override public String plainSubstrByWidth(String text, int width, boolean reverse) {
            return reverse ? text.substring(Math.max(0, text.length() - Math.max(0, width)))
                : plainSubstrByWidth(text, width);
        }
    }
}
