package com.nobodiiiii.createbiotech.content.biocomputing.terminal;

import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.TerminalSubmit;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ValuePath;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Contract tests for the schema-three, server-owned input control. */
class TerminalInputModelTest {
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
    private static DataValue.RecordValue record(Map<String, DataValue> fields) { return new DataValue.RecordValue(fields); }
    private static TerminalBinding literal(DataValue value) { return new TerminalBinding.Literal(value); }

    @Test void schemaThreeInputResolvesTypedDisplayAndCopiesFrozenInputsBeforeOverridingTheNamedArgument() {
        var frozen = record(Map.of("query", text("old"), "category", text("all")));
        var input = new TerminalDefinition.Input(literal(text("search")), literal(new DataValue.BooleanValue(true)),
            "submit", "query", literal(frozen));
        var definition = new TerminalDefinition("input", Map.of("input", input));
        var roots = record(Map.of("memory", record(Map.of()), "output", DataValue.NullValue.INSTANCE));

        var view = TerminalProjection.project(definition, roots, Map.of(), 4, 8);
        var resolved = view.input().orElseThrow();
        assertEquals("search", resolved.text());
        assertTrue(resolved.enabled());
        assertEquals(frozen, resolved.inputs());

        var invocation = TerminalInteraction.resolveSubmit(definition, roots, "5:input", "new");
        assertEquals("submit", invocation.entry());
        assertEquals(record(Map.of("query", text("new"), "category", text("all"))), invocation.inputs());
        assertEquals(text("old"), frozen.field("query"), "the published record is never mutated");
        assertEquals(definition, TerminalCodec.decode(TerminalCodec.encode(definition)));
    }

    @Test void oldSchemasRejectInputAndBadTypedBindingsProduceAProblemBeforeSubmission() {
        var node = record(Map.of("kind", text("input"),
            "initial", record(Map.of("kind", text("literal"), "value", text(""))),
            "enabled", record(Map.of("kind", text("literal"), "value", new DataValue.BooleanValue(true))),
            "entry", text("submit"), "argument", text("query"),
            "inputs", record(Map.of("kind", text("literal"), "value", record(Map.of())))));
        for (long version : List.of(1L, 2L)) {
            var archive = record(Map.of("version", integer(version), "root", text("input"), "nodes", record(Map.of("input", node))));
            assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class, () -> TerminalCodec.decode(archive)).code());
        }

        var bad = new TerminalDefinition("input", Map.of("input", new TerminalDefinition.Input(
            literal(integer(1)), literal(text("yes")), "submit", "query", literal(new DataValue.ListValue(List.of())))));
        var view = TerminalProjection.project(bad, record(Map.of()), Map.of(), 1, 2);
        assertTrue(view.problem().orElseThrow().code().startsWith("invalid_input_"));
        assertThrows(IllegalArgumentException.class, () -> TerminalInteraction.resolveSubmit(bad, record(Map.of()), "5:input", "new"));
    }

    @Test void everyInputBindingIsTypeCheckedEvenWhenSubmittedTextWouldReplaceTheInitialValue() {
        for (String slot : List.of("initial", "enabled", "inputs")) {
            var input = new TerminalDefinition.Input(literal(slot.equals("initial") ? integer(1) : text("initial")),
                literal(slot.equals("enabled") ? text("true") : new DataValue.BooleanValue(true)), "submit", "query",
                literal(slot.equals("inputs") ? new DataValue.ListValue(List.of()) : record(Map.of())));
            var definition = new TerminalDefinition("input", Map.of("input", input));
            assertEquals("invalid_input_" + slot, TerminalProjection.project(definition, record(Map.of()), Map.of(), 1, 2).problem().orElseThrow().code());
            assertThrows(IllegalArgumentException.class, () -> TerminalInteraction.resolveSubmit(definition, record(Map.of()), "5:input", "replacement"));
        }
    }

    @Test void genuinelyNestedRepeatsResolveTheInnerSelectedRowWithoutLeakingTheOuterOrFirstRow() {
        var input = new TerminalDefinition.Input(new TerminalBinding.Path(new ValuePath("row", List.of()).key("query")),
            literal(new DataValue.BooleanValue(true)), "submit", "query", new TerminalBinding.Path(new ValuePath("row", List.of())));
        var definition = new TerminalDefinition("outer", Map.of(
            "outer", new TerminalDefinition.Repeat(new TerminalBinding.Path(new ValuePath("memory", List.of()).key("groups")), "inner"),
            "inner", new TerminalDefinition.Repeat(new TerminalBinding.Path(new ValuePath("row", List.of()).key("rows")), "input"),
            "input", input));
        var first = record(Map.of("query", text("first"), "category", text("wrong")));
        var selected = record(Map.of("query", text("old"), "category", text("selected")));
        var groups = new DataValue.ListValue(List.of(
            record(Map.of("rows", new DataValue.ListValue(List.of(first)))),
            record(Map.of("rows", new DataValue.ListValue(List.of(first, selected))))));
        var roots = record(Map.of("memory", record(Map.of("groups", groups))));
        assertEquals(record(Map.of("query", text("new"), "category", text("selected"))),
            TerminalInteraction.resolveSubmit(definition, roots, "5:outer/5:inner[1]/5:input[1]", "new").inputs());
        assertEquals(text("old"), selected.field("query"));
    }

    @Test void repeatSubmissionUsesTheSelectedRowAndTheClosedPayloadRejectsExtraFields() {
        var input = new TerminalDefinition.Input(literal(text("")), literal(new DataValue.BooleanValue(true)), "submit", "query",
            new TerminalBinding.Path(new ValuePath("row", List.of())));
        var definition = new TerminalDefinition("root", Map.of(
            "root", new TerminalDefinition.Repeat(new TerminalBinding.Path(new ValuePath("memory", List.of()).key("rows")), "input"),
            "input", input));
        var rows = new DataValue.ListValue(List.of(record(Map.of("query", text("first"), "category", text("one"))),
            record(Map.of("query", text("second"), "category", text("two")))));
        var roots = record(Map.of("memory", record(Map.of("rows", rows))));
        assertEquals(record(Map.of("query", text("selected"), "category", text("two"))),
            TerminalInteraction.resolveSubmit(definition, roots, "4:root/5:input[1]", "selected").inputs());

        var submit = new TerminalSubmit(7, "4:root/5:input[1]", "selected");
        assertEquals(submit, TerminalSubmit.decode(submit.encode()));
        var extra = new LinkedHashMap<>(submit.encode().fields()); extra.put("entry", text("forged"));
        assertThrows(IllegalArgumentException.class, () -> TerminalSubmit.decode(record(extra)));
    }
}
