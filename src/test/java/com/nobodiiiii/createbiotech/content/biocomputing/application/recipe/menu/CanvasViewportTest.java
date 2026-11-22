package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu;

import org.junit.jupiter.api.Test;
import com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport;
import static org.junit.jupiter.api.Assertions.*;

class CanvasViewportTest {
    @Test void transformationsRoundTripAfterPanAndZoom() {
        var view = new CanvasViewport();
        view.pan(13, -40); view.zoomAt(150, 60, 3);
        assertEquals(43.5, view.worldX(view.screenX(43.5)), 1e-9);
        assertEquals(-14, view.worldY(view.screenY(-14)), 1e-9);
    }
    @Test void zoomKeepsWorldPositionUnderCursor() {
        var view = new CanvasViewport(); view.pan(30, -17);
        double x = view.worldX(155), y = view.worldY(90);
        view.zoomAt(155, 90, 4);
        assertEquals(x, view.worldX(155), 1e-9);
        assertEquals(y, view.worldY(90), 1e-9);
    }
    @Test void panUsesScreenPixelsAtEveryZoom() {
        var view = new CanvasViewport(); view.zoomAt(0, 0, 5);
        double x = view.screenX(25), y = view.screenY(35);
        view.pan(15, -30);
        assertEquals(x + 15, view.screenX(25), 1e-9);
        assertEquals(y - 30, view.screenY(35), 1e-9);
    }
    @Test void zoomIsBoundedAndResetRestoresIdentity() {
        var view = new CanvasViewport();
        view.zoomAt(21, 22, Integer.MAX_VALUE);
        double maximum = view.scale(); view.zoomAt(21, 22, 1);
        assertEquals(maximum, view.scale()); assertTrue(maximum <= 4);
        view.zoomAt(21, 22, Integer.MIN_VALUE);
        assertTrue(view.scale() >= .25);
        view.reset(); assertEquals(1, view.scale()); assertEquals(20, view.screenX(20));
    }
    @Test void rejectsNonFiniteInputWithoutCorruptingState() {
        var view = new CanvasViewport();
        assertThrows(IllegalArgumentException.class, () -> view.pan(Double.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> view.zoomAt(2, Double.POSITIVE_INFINITY, 1));
        assertEquals(1, view.scale()); assertEquals(3, view.screenX(3));
    }
}
