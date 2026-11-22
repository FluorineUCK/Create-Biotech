package com.nobodiiiii.createbiotech.content.biocomputing.device.terminal;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalBinding;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerminalCharacterViewTest {
    @Test void characterFramePreservesColumnPositionsAndReportsOverflow() {
        var page = new TerminalUiState.Page(new TerminalDefinition("root", Map.of(
            "root", new TerminalDefinition.Column(List.of("row", "tail")),
            "row", new TerminalDefinition.Row(List.of("left", "right")),
            "left", new TerminalDefinition.Text(literal("abcdefghijklmnopqrstuvwxyz")),
            "right", new TerminalDefinition.Text(literal("OK")),
            "tail", new TerminalDefinition.Text(literal("end")))), TerminalUiState.record(Map.of()));
        var first = TerminalCharacterView.render(page, 26, 1, 0, Map.of());
        assertEquals(List.of("abcdefghijk~  OK"), first.rows());
        assertEquals(2, first.totalRows());
        assertTrue(first.clipped());
        var second = TerminalCharacterView.render(page, 26, 1, 1, Map.of());
        assertEquals(List.of("end"), second.rows());
        assertFalse(second.clipped());
        assertThrows(IllegalArgumentException.class, () -> TerminalCharacterView.render(page, 999, 1, 0, Map.of()));
    }

    @Test void translatesControlsAsReadOnlyAndFlagsLossOfUnsupportedCharacters() {
        var yes = new TerminalBinding.Literal(new DataValue.BooleanValue(true));
        var args = new TerminalBinding.Literal(TerminalUiState.record(Map.of()));
        var page = new TerminalUiState.Page(new TerminalDefinition("root", Map.of(
            "root", new TerminalDefinition.Column(List.of("button", "field")),
            "button", new TerminalDefinition.Button(literal("打开"), yes, "open", args),
            "field", new TerminalDefinition.Input(literal("abc"), yes, "submit", "answer", args))), TerminalUiState.record(Map.of()));
        var view = TerminalCharacterView.render(page, 26, 5, 0, Map.of());
        assertEquals(List.of("[??]", "> abc"), view.rows());
        assertTrue(view.substituted());
    }

    private static TerminalBinding.Literal literal(String value) {
        return new TerminalBinding.Literal(new DataValue.TextValue(value));
    }
}
