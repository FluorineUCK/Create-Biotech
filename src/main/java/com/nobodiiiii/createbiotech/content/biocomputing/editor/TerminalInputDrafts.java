package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalInteraction;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** One current edit per instance; observations invalidate stale contexts, including off-page edits. */
final class TerminalInputDrafts {
    private final Map<String, Draft> values = new HashMap<>();
    private TerminalDefinition layout;

    String value(String node, String instance, DataValue.RecordValue inputs, String initial) {
        var draft = values.get(instance);
        if (draft == null) return initial;
        if (!draft.node().equals(node) || !draft.inputs().equals(inputs)) {
            values.remove(instance);
            return initial;
        }
        return draft.text();
    }
    void update(String node, String instance, DataValue.RecordValue inputs, String text) {
        values.put(instance, new Draft(node, inputs, text));
    }
    void observe(TerminalDefinition definition, DataValue.RecordValue roots) {
        if (!Objects.equals(layout, definition)) values.clear();
        layout = definition;
        // Resolve only instances that actually have edits, not every row of a Repeat.
        values.entrySet().removeIf(entry -> {
            try {
                return !entry.getValue().inputs().equals(TerminalInteraction.resolveInput(definition, roots, entry.getKey()).inputs());
            } catch (IllegalArgumentException invalid) {
                return true;
            }
        });
    }
    void clear() { values.clear(); layout = null; }
    private record Draft(String node, DataValue.RecordValue inputs, String text) {}
}
