package com.nobodiiiii.createbiotech.content.biocomputing.device.redstone;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;
import org.junit.jupiter.api.Test;

class RedstoneOutputProtocolTest {
    private final UUID source = UUID.randomUUID();
    private final DataValue.RecordValue target = record("x", integer(3), "y", integer(2), "z", integer(1));
    private final RedstoneOutputState.State state = RedstoneOutputState.grant(
        RedstoneOutputState.closed(UUID.randomUUID(), UUID.randomUUID()), Optional.of(source), UUID.randomUUID());

    @Test void statusThenVersionedSetAreOrdinaryRecordsAndPlanningDoesNotApplyPower() {
        var status = RedstoneOutputProtocol.plan(state, source, target, command("status", Map.of())).orElseThrow();
        assertSame(state, status.next());
        assertEquals(text("ok"), status.reply().field("code"));
        var token = (DataValue.RecordValue)status.reply().field("state");
        var args = new LinkedHashMap<>(token.fields()); args.keySet().retainAll(Set.of("device", "session", "revision"));
        args.put("level", integer(13));
        var set = RedstoneOutputProtocol.plan(state, source, target, command("set", args)).orElseThrow();
        assertEquals(text("applied"), set.reply().field("code"));
        assertEquals(text("test:1"), set.reply().field("request"));
        assertEquals(13, set.next().applied()); assertEquals(0, state.applied());
        assertEquals(text("changed"), RedstoneOutputProtocol.plan(set.next(), source, target, command("set", args)).orElseThrow().reply().field("code"));
    }

    @Test void ungrantedSourceCannotForgeAuthorityOrReadDeviceTokens() {
        var fields = new LinkedHashMap<>(command("status", Map.of()).fields()); fields.put("source", text(source.toString()));
        var denied = RedstoneOutputProtocol.plan(state, UUID.randomUUID(), target, new DataValue.RecordValue(fields)).orElseThrow();
        assertEquals(text("denied"), denied.reply().field("code")); assertFalse(denied.reply().fields().containsKey("state"));
        assertSame(state, denied.next());
        assertEquals(text("invalid_command"), RedstoneOutputProtocol.plan(state, source, target, new DataValue.RecordValue(fields)).orElseThrow().reply().field("code"));
        assertTrue(RedstoneOutputProtocol.plan(state, source, record("x", integer(9)), command("status", Map.of())).isEmpty());
    }

    @Test void oldSessionsUnknownProtocolsAndBadTypesNeverPublishAnOutput() {
        var args = new LinkedHashMap<String, DataValue>(Map.of("device", text(state.device().toString()), "session", text(state.session().toString()),
            "revision", integer(state.revision()), "level", integer(15)));
        var loaded = RedstoneOutputState.load(RedstoneOutputState.save(state), UUID.randomUUID());
        assertEquals(text("stale_session"), RedstoneOutputProtocol.plan(loaded, source, target, command("set", args)).orElseThrow().reply().field("code"));
        for (DataValue value : List.of(integer(Long.MAX_VALUE), integer(-1), integer(16), text("15"))) {
            args.put("level", value); var rejected = RedstoneOutputProtocol.plan(state, source, target, command("set", args)).orElseThrow();
            assertSame(state, rejected.next()); assertNotEquals(text("applied"), rejected.reply().field("code"));
        }
        var unknown = new LinkedHashMap<>(command("status", Map.of()).fields()); unknown.put("version", integer(2));
        var rejected = RedstoneOutputProtocol.plan(state, source, target, new DataValue.RecordValue(unknown)).orElseThrow();
        assertSame(state, rejected.next()); assertEquals(text("unsupported_version"), rejected.reply().field("code"));
    }

    private DataValue.RecordValue command(String operation, Map<String, DataValue> arguments) {
        return record("kind", text("redstone_command"), "version", integer(1), "target", target,
            "request", text("test:1"), "operation", text(operation), "arguments", new DataValue.RecordValue(arguments));
    }
    static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
    static DataValue.RecordValue record(Object... pairs) {
        var fields = new LinkedHashMap<String, DataValue>();
        for (int i = 0; i < pairs.length; i += 2) fields.put((String)pairs[i], (DataValue)pairs[i + 1]);
        return new DataValue.RecordValue(fields);
    }
}
