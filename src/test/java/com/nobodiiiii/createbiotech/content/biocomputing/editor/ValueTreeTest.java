package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ValueTreeTest {
    @Test void typedPathKeepsEmptyPunctuationAndNumericKeysDistinctFromListIndices() {
        var leaf = new DataValue.IntegerValue(42);
        var value = new DataValue.RecordValue(Map.of("snapshot", new DataValue.MapValue(Map.of("a.b[0]:\"",
            new DataValue.RecordValue(Map.of("", new DataValue.ListValue(List.of(new DataValue.RecordValue(Map.of("0", leaf))))))))));
        var path = new ValuePath("snapshot", List.of()).key("a.b[0]:\"").key("").index(0).key("0");
        assertSame(leaf, path.resolve(value));
        assertEquals("$[\"snapshot\"][\"a.b[0]:\\\"\"][\"\"][0][\"0\"]", path.display());
        assertThrows(IllegalArgumentException.class, () -> new ValuePath.Index(-1));
        assertThrows(RuntimeException.class, () -> path.key("missing").resolve(value));
    }

    @Test void displayedPathEscapesFormattingAndControlsWithoutChangingResolution() {
        String input = "in§";
        String key = "a§cb\t\r\\u00a7";
        var leaf = new DataValue.TextValue("§");
        var values = new DataValue.RecordValue(Map.of(input, new DataValue.MapValue(Map.of(key, leaf))));
        var path = new ValuePath(input, List.of()).key(key);

        assertEquals("$[\"in\\u00a7\"][\"a\\u00a7cb\\t\\r\\\\u00a7\"]", path.display());
        assertSame(leaf, path.resolve(values));
        assertEquals(input, path.input());
        assertEquals(key, ((ValuePath.Key)path.steps().getFirst()).name());
    }

    @Test void expandsOnlyRequestedBranchesAndPaginatesWithoutDroppingRowsOrChangingValues() {
        var children = new LinkedHashMap<String, DataValue>();
        children.put("a", DataValue.NullValue.INSTANCE);
        children.put("b", new DataValue.ErrorValue("target_unloaded", "still an error"));
        children.put("c", new DataValue.ListValue(List.of()));
        var inputs = new DataValue.RecordValue(Map.of("in", new DataValue.RecordValue(children)));
        var tree = new ValueTree(inputs); var root = new ValuePath("in", List.of());
        assertEquals(1, tree.page(0, 2).rows().size());
        tree.toggle(root);
        assertEquals(List.of(root, root.key("a")), tree.page(0, 2).rows().stream().map(ValueTree.Row::path).toList());
        assertEquals(List.of(root.key("b"), root.key("c")), tree.page(1, 2).rows().stream().map(ValueTree.Row::path).toList());
        assertEquals(1, tree.page(Integer.MAX_VALUE, 2).index());
        assertFalse(tree.page(1, 2).rows().getFirst().expandable());
        tree.toggle(root.key("b"));
        assertSame(inputs, tree.inputs());
        assertEquals(new DataValue.ErrorValue("target_unloaded", "still an error"), root.key("b").resolve(inputs));
        tree.toggle(root); assertEquals(1, tree.page(999, 2).rows().size());
        assertEquals(0, tree.page(999, 2).index());
    }

    @Test void deepAndWideContainersDoNotRequireRecursiveRenderingOrAnEagerFlattenedCopy() {
        DataValue deep = new DataValue.TextValue("leaf");
        for (int i = 0; i < 3000; i++) deep = new DataValue.ListValue(List.of(deep));
        var tree = new ValueTree(new DataValue.RecordValue(Map.of("deep", deep)));
        assertSame(deep, tree.page(0, 8).rows().getFirst().value());
        tree.toggle(new ValuePath("deep", List.of()));
        assertEquals(2, tree.page(0, 8).rows().size());
        var wide = new ValueTree(new DataValue.RecordValue(Map.of("items", new DataValue.ListValue(
            java.util.stream.IntStream.range(0, 10000).mapToObj(i -> (DataValue)new DataValue.IntegerValue(i)).toList()))));
        wide.toggle(new ValuePath("items", List.of()));
        var last = wide.page(Integer.MAX_VALUE, 10);
        assertEquals(1001, last.count());
        assertEquals(1, last.rows().size());
        assertEquals(new DataValue.IntegerValue(9999), last.rows().getFirst().value());
    }
}
