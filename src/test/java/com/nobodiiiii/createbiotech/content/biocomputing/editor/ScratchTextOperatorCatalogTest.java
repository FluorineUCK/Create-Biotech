package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowBlockPalette.Category.*;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.TextOperation;
import java.util.*;
import org.junit.jupiter.api.Test;

class ScratchTextOperatorCatalogTest {
    @Test void plainOperatorsFlyoutContainsTheFourScratchTextBlocksTogetherAndCanvasUsesTheirStyle() {
        var expected = List.of(TextOperation.CONCAT, TextOperation.LETTER_OF, TextOperation.LENGTH,
            TextOperation.CONTAINS_IGNORE_CASE);
        var operations = FlowBlockCatalog.search(Map.of(), OPERATORS, "");
        var text = operations.stream().filter(e -> e.value() instanceof InlineGraphEdits.TextOperator).toList();
        assertEquals(expected, text.stream().map(e -> ((InlineGraphEdits.TextOperator)e.value()).operation()).toList());
        int firstText = operations.indexOf(text.getFirst());
        assertEquals("not_boolean", operations.get(firstText - 1).id(),
            "The basic text blocks follow the boolean operators rather than advanced math");
        assertEquals(text, operations.subList(firstText, firstText + text.size()));
        assertFalse(FlowBlockCatalog.search(Map.of(), DATA, "").stream()
            .anyMatch(e -> e.value() instanceof InlineGraphEdits.TextOperator));
        assertTrue(FlowBlockCatalog.search(Map.of(), DATA, "").stream().anyMatch(e -> e.id().equals("template:text_next")),
            "The advanced text driver stays with structured data");
        for (var operation : expected) {
            var args = new LinkedHashMap<String, String>();
            var nodes = new LinkedHashMap<String, ComputationGraph.Node>();
            operation.inputs().forEach((name, type) -> {
                args.put(name, name);
                nodes.put(name, new ComputationGraph.Constant(InlineGraphEdits.defaultValue(type)));
            });
            nodes.put("root", new ComputationGraph.Text(operation, args));
            var graph = new ComputationGraph(Map.of(), nodes, Map.of("value", "root"));
            var tile = InlineGraphView.layout(graph, String::length);
            assertEquals(OPERATORS, tile.category(), operation.name());
            assertEquals(operation.outputType(operation.inputs(), "root") == DataType.Scalar.BOOLEAN, tile.bool(), operation.name());
        }
    }
}
