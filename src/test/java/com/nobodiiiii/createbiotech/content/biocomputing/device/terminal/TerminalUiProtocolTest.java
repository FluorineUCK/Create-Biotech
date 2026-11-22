package com.nobodiiiii.createbiotech.content.biocomputing.device.terminal;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerminalUiProtocolTest {
    private static final TerminalUiState.Limits LIMITS = new TerminalUiState.Limits(12, 1, 32,
        new SerialRecordCodec.Limits(4096, 32768, new ArchiveLimits(256, 2048, 4096)),
        new SerialRecordCodec.Limits(1024, 8192, new ArchiveLimits(96, 256, 512)));
    private static final DataValue.RecordValue ADDRESS = record(Map.of("x", number(2), "y", number(4), "z", number(6)));
    private final UUID owner = UUID.randomUUID();

    @Test void publishesNewControlsAndReturnsExactlyTheEventFromTheViewedLayout() {
        var initial = open();
        var status = plan(initial, "status", Map.of());
        var published = publish(initial, status.reply(), button("open"));
        assertEquals(text("published"), published.reply().field("code"));
        assertSame(initial, status.next());
        var first = published.next();
        assertEquals(1, first.revision());
        var shown = first.page().orElseThrow();
        var view = TerminalProjection.project(shown.layout(), shown.roots(), Map.of(), 4, 12);
        var control = view.children().getFirst();
        assertEquals("open", control.button().orElseThrow().title());
        var click = TerminalUiState.interact(first, first.session(), first.revision(), control.instance(), Optional.empty(), LIMITS);
        assertEquals("queued", click.code());

        var second = publish(click.state(), published.reply(), input()).next();
        assertNotEquals(shown.layout(), second.page().orElseThrow().layout(), "Program actually replaces controls");
        assertEquals("stale_view", TerminalUiState.interact(second, first.session(), first.revision(),
            control.instance(), Optional.empty(), LIMITS).code());
        var input = TerminalProjection.project(second.page().orElseThrow().layout(), second.page().orElseThrow().roots(),
            Map.of(), 4, 12).children().getFirst();
        assertEquals("events_full", TerminalUiState.interact(second, second.session(), second.revision(),
            input.instance(), Optional.of("value"), LIMITS).code(), "Does not silently discard a previously accepted click");

        var now = plan(second, "status", Map.of()).reply();
        var claim = token(now);
        claim.put("sequence", state(now).field("next_event"));
        var prepared = plan(second, "take_event", claim);
        assertEquals(text("event"), prepared.reply().field("code"));
        var event = (DataValue.RecordValue)prepared.reply().field("event");
        assertEquals(text("open"), event.field("name"));
        assertEquals(number(1), event.field("layout_revision"));
        assertEquals(record(Map.of("id", number(42))), event.field("values"));
        assertEquals(1, second.events().size(), "Preparation alone must not consume the head");
        assertTrue(prepared.next().events().isEmpty());

        var typed = TerminalUiState.interact(prepared.next(), second.session(), second.revision(),
            input.instance(), Optional.of("answer"), LIMITS);
        assertEquals("queued", typed.code());
        assertEquals(text("event_changed"), plan(typed.state(), "take_event", claim).reply().field("code"));
        var secondClaim = token(plan(typed.state(), "status", Map.of()).reply());
        secondClaim.put("sequence", number(2));
        var submitted = (DataValue.RecordValue)plan(typed.state(), "take_event", secondClaim).reply().field("event");
        assertEquals(text("submit"), submitted.field("name"));
        assertEquals(record(Map.of("answer", text("answer"))), submitted.field("values"));
    }

    @Test void rejectsUnauthorizedOversizedAndTaskLayoutsAndNeverRestoresClicks() {
        var before = open();
        var query = plan(before, "status", Map.of()).reply();
        var hostile = publishArgs(query, button("open"), record(Map.of("blob", text("x".repeat(4096)))));
        var denied = TerminalUiProtocol.plan(before, UUID.randomUUID(), ADDRESS, command("publish", hostile), LIMITS).orElseThrow();
        assertEquals(text("denied"), denied.reply().field("code"));
        assertFalse(denied.reply().fields().containsKey("state"));
        var tooBig = plan(before, "publish", hostile);
        assertEquals(text("view_capacity"), tooBig.reply().field("code"));
        assertSame(before, tooBig.next());
        var task = new TerminalDefinition("root", Map.of("root", new TerminalDefinition.Button(literal(text("start")),
            literal(new DataValue.BooleanValue(true)), new TerminalTarget.Task(new ModuleDefinition.Key("worker", 1)), literal(record(Map.of())))));
        assertEquals(text("invalid_view"), publish(before, query, task).reply().field("code"));

        var current = publish(before, query, input()).next();
        String instance = "4:root/5:input";
        assertEquals("invalid_interaction", TerminalUiState.interact(current, current.session(), current.revision(),
            instance, Optional.empty(), LIMITS).code());
        assertEquals("input_capacity", TerminalUiState.interact(current, current.session(), current.revision(),
            instance, Optional.of("x".repeat(33)), LIMITS).code());
        var pending = TerminalUiState.interact(current, current.session(), current.revision(), instance, Optional.of("answer"), LIMITS).state();
        assertEquals(1, pending.events().size());
        var restored = TerminalUiState.load(TerminalUiState.save(pending), UUID.randomUUID());
        assertEquals(current.page(), restored.page());
        assertTrue(restored.events().isEmpty(), "Transient input events are never replayed on load");
        assertEquals("stale_view", TerminalUiState.interact(restored, current.session(), current.revision(),
            instance, Optional.of("answer"), LIMITS).code());
        assertEquals(text("stale_session"), publish(restored, plan(pending, "status", Map.of()).reply(), button("new")).reply().field("code"));
        var revoked = TerminalUiState.grant(pending, Optional.empty(), UUID.randomUUID());
        assertTrue(revoked.events().isEmpty());
        assertEquals(pending.page(), revoked.page());
        assertEquals(text("closed"), plan(revoked, "status", Map.of()).reply().field("code"));
    }

    private TerminalUiState.State open() {
        return TerminalUiState.grant(TerminalUiState.closed(UUID.randomUUID(), UUID.randomUUID()), Optional.of(owner), UUID.randomUUID());
    }
    private TerminalUiProtocol.Plan publish(TerminalUiState.State state, DataValue.RecordValue reply, TerminalDefinition layout) {
        return plan(state, "publish", publishArgs(reply, layout, record(Map.of())));
    }
    private static Map<String, DataValue> publishArgs(DataValue.RecordValue reply, TerminalDefinition layout, DataValue.RecordValue roots) {
        var args = token(reply); args.put("layout", TerminalCodec.encode(layout)); args.put("roots", roots); return args;
    }
    private TerminalUiProtocol.Plan plan(TerminalUiState.State state, String operation, Map<String, DataValue> args) {
        return TerminalUiProtocol.plan(state, owner, ADDRESS, command(operation, args), LIMITS).orElseThrow();
    }
    private static DataValue.RecordValue command(String operation, Map<String, DataValue> args) {
        return record(Map.of("kind", text("terminal_ui_command"), "version", number(1), "target", ADDRESS,
            "request", text("fixture"), "operation", text(operation), "arguments", record(args)));
    }
    private static LinkedHashMap<String, DataValue> token(DataValue.RecordValue reply) {
        return new LinkedHashMap<>(Map.of("state", state(reply)));
    }
    private static DataValue.RecordValue state(DataValue.RecordValue reply) {
        return (DataValue.RecordValue)reply.field("state");
    }
    private static TerminalDefinition button(String name) {
        return new TerminalDefinition("root", Map.of("root", new TerminalDefinition.Column(java.util.List.of("btn")),
            "btn", new TerminalDefinition.Button(literal(text(name)), literal(new DataValue.BooleanValue(true)),
                name, literal(record(Map.of("id", number(42)))))));
    }
    private static TerminalDefinition input() {
        return new TerminalDefinition("root", Map.of("root", new TerminalDefinition.Row(java.util.List.of("input")),
            "input", new TerminalDefinition.Input(literal(text("")), literal(new DataValue.BooleanValue(true)),
                "submit", "answer", literal(record(Map.of())))));
    }
    private static TerminalBinding.Literal literal(DataValue value) { return new TerminalBinding.Literal(value); }
    private static DataValue.RecordValue record(Map<String, DataValue> fields) { return new DataValue.RecordValue(fields); }
    private static DataValue.IntegerValue number(long value) { return new DataValue.IntegerValue(value); }
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
}
