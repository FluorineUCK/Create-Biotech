package com.nobodiiiii.createbiotech.content.biocomputing.device.display;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;
import org.junit.jupiter.api.Test;

class TextOutputProtocolTest {
    private final UUID source = UUID.randomUUID();
    private final DataValue.RecordValue target = record("x", number(3), "y", number(2), "z", number(1));
    private final TextOutputState.Limits limits = new TextOutputState.Limits(8, 256);

    @Test void authorizedVersionedReplacementPublishesOnlyAnAcceptedTextBuffer() {
        var state = TextOutputState.grant(TextOutputState.closed(UUID.randomUUID(), UUID.randomUUID()), Optional.of(source), UUID.randomUUID());
        var query = TextOutputProtocol.plan(state, source, target, command("status", Map.of()), limits).orElseThrow();
        assertSame(state, query.next());
        var args = new LinkedHashMap<>(((DataValue.RecordValue)query.reply().field("state")).fields());
        args.keySet().retainAll(Set.of("device", "session", "revision"));
        args.put("lines", new DataValue.ListValue(List.of(text("生产线 A"), text("Ready"))));
        var plan = TextOutputProtocol.plan(state, source, target, command("replace", args), limits).orElseThrow();
        assertEquals(text("accepted"), plan.reply().field("code"));
        assertEquals(List.of("生产线 A", "Ready"), plan.next().lines());
        assertEquals(List.of(), state.lines());
        assertFalse(plan.reply().fields().containsKey("displayed"));
        assertEquals(text("changed"), TextOutputProtocol.plan(plan.next(), source, target, command("replace", args), limits).orElseThrow().reply().field("code"));
    }
    @Test void savedPageSurvivesReloadButOldCommandsCannotReplaceIt() {
        var initial = TextOutputState.grant(TextOutputState.closed(UUID.randomUUID(), UUID.randomUUID()), Optional.of(source), UUID.randomUUID());
        var page = TextOutputState.replace(initial, source, initial.device(), initial.session(), initial.revision(), List.of("你好"), limits).state();
        var loaded = TextOutputState.load(TextOutputState.save(page), UUID.randomUUID());
        assertEquals(page.lines(), loaded.lines());
        assertEquals(page.revision(), loaded.revision());
        assertEquals("stale_session", TextOutputState.replace(loaded, source, page.device(), page.session(), page.revision(), List.of("old"), limits).code());
        var revoked = TextOutputState.grant(loaded, Optional.empty(), UUID.randomUUID());
        assertEquals(page.lines(), revoked.lines());
        assertEquals("closed", TextOutputState.replace(revoked, source, revoked.device(), revoked.session(), revoked.revision(), List.of(), limits).code());
        var malformed = TextOutputState.save(page); malformed.putInt("Version", 99);
        assertThrows(IllegalArgumentException.class, () -> TextOutputState.load(malformed, UUID.randomUUID()));
    }
    @Test void capacityIsUtf8BoundedAndUnauthorizedQueriesCannotLeakTokens() {
        var state = TextOutputState.grant(TextOutputState.closed(UUID.randomUUID(), UUID.randomUUID()), Optional.of(source), UUID.randomUUID());
        var denied = TextOutputProtocol.plan(state, UUID.randomUUID(), target, command("status", Map.of()), limits).orElseThrow();
        assertEquals(text("denied"), denied.reply().field("code"));
        assertFalse(denied.reply().fields().containsKey("state")); assertSame(state, denied.next());
        assertEquals("text_capacity", TextOutputState.replace(state, source, state.device(), state.session(), state.revision(), List.of("你好"), new TextOutputState.Limits(1, 5)).code());
        var accepted = TextOutputState.replace(state, source, state.device(), state.session(), state.revision(), List.of("你好"), new TextOutputState.Limits(1, 6));
        assertEquals("accepted", accepted.code()); assertEquals(6, accepted.state().bytes());
        assertEquals("text_capacity", TextOutputState.replace(state, source, state.device(), state.session(), state.revision(), List.of("", ""), new TextOutputState.Limits(1, 6)).code());
        var args = new LinkedHashMap<>(((DataValue.RecordValue)TextOutputProtocol.plan(state, source, target, command("status", Map.of()), limits).orElseThrow().reply().field("state")).fields());
        args.keySet().retainAll(Set.of("device", "session", "revision"));
        args.put("lines", new DataValue.ListValue(List.of(number(1))));
        assertSame(state, TextOutputProtocol.plan(state, source, target, command("replace", args), limits).orElseThrow().next());
        args.put("lines", new DataValue.ListValue(List.of(text("a\nb"))));
        var invalidText = TextOutputProtocol.plan(state, source, target, command("replace", args), limits).orElseThrow();
        assertSame(state, invalidText.next());
        assertEquals(text("invalid_text"), invalidText.reply().field("code"));
    }
    private DataValue.RecordValue command(String operation, Map<String, DataValue> arguments) {
        return record("kind", text("text_output_command"), "version", number(1), "target", target,
            "request", text("test:1"), "operation", text(operation), "arguments", new DataValue.RecordValue(arguments));
    }
    static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    static DataValue.IntegerValue number(long value) { return new DataValue.IntegerValue(value); }
    static DataValue.RecordValue record(Object... pairs) {
        var fields = new LinkedHashMap<String, DataValue>();
        for (int i = 0; i < pairs.length; i += 2) fields.put((String)pairs[i], (DataValue)pairs[i + 1]);
        return new DataValue.RecordValue(fields);
    }
}
