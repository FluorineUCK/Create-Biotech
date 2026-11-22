package com.nobodiiiii.createbiotech.content.biocomputing.device.terminal;

import com.nobodiiiii.createbiotech.content.biocomputing.editor.EditorValueFormat;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec;
import java.util.LinkedHashMap;
import java.util.Map;
import static com.nobodiiiii.createbiotech.content.biocomputing.device.terminal.TerminalUiState.*;

/** CC transport of the same terminal page and event queue; does not depend on CC classes. */
final class TerminalCcProtocol {
    private TerminalCcProtocol() {}
    record Claim(State next, Map<String, Object> event) {}

    static Map<String, Object> status(State state, int caller) {
        authorize(state, caller);
        var result = new LinkedHashMap<String, Object>();
        result.put("device", state.device().toString());
        result.put("session", state.session().toString());
        result.put("revision", Long.toString(state.revision()));
        result.put("published", state.page().isPresent());
        result.put("event_count", state.events().size());
        if (!state.events().isEmpty()) result.put("next_event", Long.toString(state.events().getFirst().sequence()));
        return Map.copyOf(result);
    }

    static Update publish(State state, int caller, String device, String session, String revision,
            String layout, String roots, Limits limits) {
        token(state, caller, device, session, revision);
        // Reject large Lua arguments before entering the parser; the shared validator then checks actual encoded size.
        if ((long)layout.length() + roots.length() > limits.page().maxEncodedBytes())
            return new Update(state, "view_capacity");
        try {
            return TerminalUiState.publish(state, EditorValueFormat.parse(EditorValueFormat.Kind.JSON, layout),
                EditorValueFormat.parse(EditorValueFormat.Kind.JSON, roots), limits);
        } catch (IllegalArgumentException invalid) { return new Update(state, "invalid_view"); }
    }

    static Claim take(State state, int caller, String device, String session, String revision, String sequence) {
        token(state, caller, device, session, revision);
        if (sequence.isEmpty() || sequence.length() > 19) throw new IllegalArgumentException("invalid_command");
        if (state.events().isEmpty()) throw new IllegalArgumentException("no_events");
        var event = state.events().getFirst();
        if (!Long.toString(event.sequence()).equals(sequence)) throw new IllegalArgumentException("event_changed");
        var result = new LinkedHashMap<String, Object>();
        result.put("sequence", Long.toString(event.sequence()));
        result.put("layout_revision", Long.toString(event.layoutRevision()));
        result.put("kind", event.kind());
        result.put("instance", event.instance());
        result.put("name", event.name());
        try {
            result.put("values_json", EditorValueFormat.format(event.values()));
        } catch (IllegalArgumentException notJson) {
            // A page retained from a serial publisher can contain native NBT or other portable non-JSON values.
            result.put("values_archive_snbt", ValueNbtCodec.encode(event.values(), PAGE_FORMAT.values()).toString());
        }
        return new Claim(withoutHead(state), Map.copyOf(result));
    }

    private static void authorize(State state, int caller) {
        if (caller < 0 || state.ccPublisher().orElse(-1) != caller) throw new IllegalArgumentException("access_denied");
    }

    private static void token(State state, int caller, String device, String session, String revision) {
        authorize(state, caller);
        if (!state.device().toString().equals(device)) throw new IllegalArgumentException("device_changed");
        if (!state.session().toString().equals(session)) throw new IllegalArgumentException("stale_session");
        if (!Long.toString(state.revision()).equals(revision)) throw new IllegalArgumentException("changed");
    }
}
