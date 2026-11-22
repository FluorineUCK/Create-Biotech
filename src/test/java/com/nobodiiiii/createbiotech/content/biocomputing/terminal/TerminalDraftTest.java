package com.nobodiiiii.createbiotech.content.biocomputing.terminal;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ValuePath;
import java.util.*;
import org.junit.jupiter.api.Test;

class TerminalDraftTest {
    @Test void editingRepeatTemplateAndReorderingPreservesAnIsolatedValidTree() {
        var source = TerminalDefinition.empty(); var draft = new TerminalDraft(source);
        String text = draft.add(source.root(), TerminalDraft.Kind.TEXT);
        String repeat = draft.add(source.root(), TerminalDraft.Kind.REPEAT);
        var repeated = (TerminalDefinition.Repeat) draft.snapshot().nodes().get(repeat);
        String templateText = draft.add(repeated.template(), TerminalDraft.Kind.TEXT);
        draft.replace(templateText, new TerminalDefinition.Text(new TerminalBinding.Path(new ValuePath("row", List.of()).key("name"))));
        draft.reorder(repeat, -1);
        assertEquals(List.of(repeat, text), ((TerminalDefinition.Column) draft.snapshot().nodes().get(source.root())).children());
        assertEquals(1, source.nodes().size());
        var saved = draft.snapshot(); draft.remove(repeat);
        assertEquals(Set.of(source.root(), text), draft.snapshot().nodes().keySet());
        assertTrue(saved.nodes().containsKey(templateText));
        assertThrows(IllegalArgumentException.class, () -> draft.remove(source.root()));
    }
    @Test void everyKindCanBeAddedAndInvalidReplacementIsAtomic() {
        var draft = new TerminalDraft(TerminalDefinition.empty());
        for (var kind : TerminalDraft.Kind.values()) draft.add("root", kind);
        var saved = draft.snapshot();
        var children = ((TerminalDefinition.Column)saved.nodes().get("root")).children();
        assertEquals(Set.of(TerminalDraft.Kind.TEXT, TerminalDraft.Kind.ITEM, TerminalDraft.Kind.BUTTON, TerminalDraft.Kind.INPUT,
            TerminalDraft.Kind.ROW, TerminalDraft.Kind.COLUMN, TerminalDraft.Kind.GRID, TerminalDraft.Kind.REPEAT),
            children.stream().map(saved.nodes()::get).map(TerminalDraft::kind).collect(java.util.stream.Collectors.toSet()));
        assertThrows(IllegalArgumentException.class, () -> draft.replace("root", new TerminalDefinition.Row(List.of("missing"))));
        assertEquals(saved, draft.snapshot());
        assertThrows(IllegalArgumentException.class, () -> draft.add("missing", TerminalDraft.Kind.TEXT));
        assertEquals(saved, draft.snapshot());
    }
}
