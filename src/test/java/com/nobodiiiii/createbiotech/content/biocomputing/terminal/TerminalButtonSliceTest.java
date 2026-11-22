package com.nobodiiiii.createbiotech.content.biocomputing.terminal;

import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ValuePath;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException;
import java.util.*;
import org.junit.jupiter.api.Test;

class TerminalButtonSliceTest {
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
    private static DataValue.RecordValue record(Map<String, DataValue> fields) { return new DataValue.RecordValue(fields); }
    private static DataValue.RecordValue literal(DataValue value) {
        return record(Map.of("kind", text("literal"), "value", value));
    }

    @Test void literalSchemaTwoButtonRendersAndResolvesIndependentTypedFields() {
        var node = record(Map.of("kind", text("button"),
            "title", literal(text("Add one")), "enabled", literal(new DataValue.BooleanValue(true)),
            "entry", text("click"), "inputs", record(Map.of("kind", text("path"), "input", text("row"),
                "steps", new DataValue.ListValue(List.of())))));
        var archive = record(Map.of("version", integer(2), "root", text("button"),
            "nodes", record(Map.of("button", node))));

        var definition = TerminalCodec.decode(archive);
        var button = assertInstanceOf(TerminalDefinition.Button.class, definition.nodes().get("button"));
        assertEquals("click", button.entry());
        var row = record(Map.of("amount", integer(7)));
        var roots = record(Map.of("memory", record(Map.of()), "output", DataValue.NullValue.INSTANCE, "row", row));
        var view = TerminalProjection.project(definition, roots, Map.of(), 4, 8);
        var display = view.button().orElseThrow();
        assertEquals("Add one", display.title());
        assertTrue(display.enabled());
        assertEquals(row, display.inputs());
        assertEquals(new TerminalInteraction.Invocation("click", row),
            TerminalInteraction.resolve(definition, roots, "6:button"));
        assertEquals(definition, TerminalCodec.decode(TerminalCodec.encode(definition)));
    }

    @Test void schemaOneRejectsButtonAndEveryButtonBindingReportsItsOwnWrongType() {
        var schemaTwo = record(Map.of("version", integer(2), "root", text("button"), "nodes", record(Map.of("button",
            record(Map.of("kind", text("button"), "title", literal(text("Run")),
                "enabled", literal(new DataValue.BooleanValue(true)), "entry", text("click"),
                "inputs", literal(record(Map.of()))))))));
        var schemaOneFields = new LinkedHashMap<>(schemaTwo.fields()); schemaOneFields.put("version", integer(1));
        assertEquals(ArchiveException.Code.INVALID_FORMAT,
            assertThrows(ArchiveException.class, () -> TerminalCodec.decode(record(schemaOneFields))).code());

        var bad = new TerminalDefinition("button", Map.of("button", new TerminalDefinition.Button(
            new TerminalBinding.Literal(integer(1)), new TerminalBinding.Literal(text("yes")), "click",
            new TerminalBinding.Literal(new DataValue.ListValue(List.of())))));
        var view = TerminalProjection.project(bad, record(Map.of()), Map.of(), 1, 2);
        assertEquals("invalid_button_title", view.problem().orElseThrow().code());
        assertThrows(IllegalArgumentException.class, () -> TerminalInteraction.resolve(bad, record(Map.of()), "6:button"));
    }

    @Test void nestedRepeatPathUsesAbsoluteRowsAndFrozenRootsWithoutTraversingOtherRows() {
        var button = new TerminalDefinition.Button(new TerminalBinding.Literal(text("Use")),
            new TerminalBinding.Literal(new DataValue.BooleanValue(true)), "click",
            new TerminalBinding.Path(new ValuePath("row", List.of())));
        var definition = new TerminalDefinition("root", Map.of(
            "root", new TerminalDefinition.Repeat(new TerminalBinding.Path(new ValuePath("memory", List.of()).key("rows")), "button"),
            "button", button));
        var first = record(Map.of("amount", integer(1))); var second = record(Map.of("amount", integer(2)));
        var source = new ArrayList<DataValue>(List.of(first, second));
        var roots = record(Map.of("memory", record(Map.of("rows", new DataValue.ListValue(source))), "output", DataValue.NullValue.INSTANCE));
        var invocation = TerminalInteraction.resolve(definition, roots, "4:root/6:button[1]");
        source.set(1, record(Map.of("amount", integer(99))));
        assertEquals(second, invocation.inputs());
        assertThrows(IllegalArgumentException.class, () -> TerminalInteraction.resolve(definition, roots, "4:root/6:button[2]"));
        assertThrows(IllegalArgumentException.class, () -> TerminalInteraction.resolve(definition, roots, "4:root/5:wrong[0]"));
        assertThrows(IllegalArgumentException.class, () -> TerminalInteraction.resolve(definition, roots, "2147483647:x"));
    }

    @Test void nestedRepeatAcceptsLengthPrefixedSpecialIds() {
        var button = new TerminalDefinition.Button(new TerminalBinding.Literal(text("Use")), new TerminalBinding.Literal(new DataValue.BooleanValue(true)),
            "click", new TerminalBinding.Path(new ValuePath("row", List.of())));
        var rows = new DataValue.ListValue(List.of(record(Map.of("amount", integer(3)))));
        var definition = new TerminalDefinition("r/o[t]", Map.of("r/o[t]", new TerminalDefinition.Repeat(new TerminalBinding.Literal(rows), "b/u[t]"), "b/u[t]", button));
        assertEquals(record(Map.of("amount", integer(3))), TerminalInteraction.resolve(definition, record(Map.of()), "6:r/o[t]/6:b/u[t][0]").inputs());
    }
}
