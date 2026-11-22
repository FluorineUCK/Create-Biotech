package com.nobodiiiii.createbiotech.content.biocomputing.terminal;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition;
import java.util.Objects;

/** Explicit event dispatch kind; a name never implicitly changes from an old event into an async task. */
public sealed interface TerminalTarget {
    record Event(String entry) implements TerminalTarget {
        public Event { if (entry == null || entry.isBlank()) throw new IllegalArgumentException("Missing terminal event entry"); }
    }
    record Task(ModuleDefinition.Key procedure) implements TerminalTarget {
        public Task { Objects.requireNonNull(procedure); }
    }
    default String eventEntry() {
        if (this instanceof Event event) return event.entry();
        throw new IllegalStateException("A task target is not a legacy event entry");
    }
}
