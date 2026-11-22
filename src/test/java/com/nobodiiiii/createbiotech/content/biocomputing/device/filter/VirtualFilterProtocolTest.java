package com.nobodiiiii.createbiotech.content.biocomputing.device.filter;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VirtualFilterProtocolTest {
    @Test void statusCredentialsCannotBeUsedByAnotherSourceOrOnAReplacementTarget() {
        var owner = UUID.randomUUID();
        var session = UUID.randomUUID();
        var target = UUID.randomUUID();
        var address = record(Map.of("x", number(0), "y", number(1), "z", number(2)));
        var before = VirtualFilterSlot.empty(VirtualFilterRule.Domain.ITEM, false);
        var status = VirtualFilterProtocol.plan(before, target, session, owner, owner, address,
            frame(address, "status", record(Map.of())), 4, (d, k, id) -> true).orElseThrow();
        var token = (DataValue.RecordValue) status.reply().field("state");
        var rule = record(Map.of("domain", text("item"), "mode", text("allow"),
            "matchers", new DataValue.ListValue(List.of(record(Map.of("kind", text("id"), "name", text("minecraft:iron_ingot")))))));
        var set = frame(address, "set", record(Map.of("state", token, "rule", rule)));
        var denied = VirtualFilterProtocol.plan(before, target, session, owner, UUID.randomUUID(), address, set, 4, (d, k, id) -> true).orElseThrow();
        assertEquals(text("denied"), denied.reply().field("code"));
        assertFalse(denied.reply().fields().containsKey("state"));
        var replaced = VirtualFilterProtocol.plan(before, UUID.randomUUID(), session, owner, owner, address, set, 4, (d, k, id) -> true).orElseThrow();
        assertEquals(text("target_changed"), replaced.reply().field("code"));
        var stale = VirtualFilterProtocol.plan(before, target, UUID.randomUUID(), owner, owner, address, set, 4, (d, k, id) -> true).orElseThrow();
        assertEquals(text("stale_session"), stale.reply().field("code"));
        var occupied = VirtualFilterProtocol.plan(before.nativeChanged(true), target, session, owner, owner, address, set, 4, (d, k, id) -> true).orElseThrow();
        assertEquals(text("filter_slot_occupied"), occupied.reply().field("code"));
        var accepted = VirtualFilterProtocol.plan(before, target, session, owner, owner, address, set, 4, (d, k, id) -> true).orElseThrow();
        assertEquals(text("applied"), accepted.reply().field("code"));
        assertEquals(rule, accepted.next().data().orElseThrow());
        assertTrue(before.data().isEmpty(), "Preparing the serial reply must not change the target");
        var replay = VirtualFilterProtocol.plan(accepted.next(), target, session, owner, owner, address, set, 4, (d, k, id) -> true).orElseThrow();
        assertEquals(text("version_conflict"), replay.reply().field("code"));
    }

    @Test void verifiesLiveRuleWithoutMutatingOrLeakingIt() {
        var owner = UUID.randomUUID();
        var session = UUID.randomUUID();
        var target = UUID.randomUUID();
        var address = record(Map.of("x", number(0), "y", number(1), "z", number(2)));
        var empty = VirtualFilterSlot.empty(VirtualFilterRule.Domain.ITEM, false);
        var rule = record(Map.of("domain", text("item"), "mode", text("allow"),
            "matchers", new DataValue.ListValue(List.of(record(Map.of("kind", text("id"), "name", text("minecraft:iron_ingot")))))));
        var replacement = record(Map.of("domain", text("item"), "mode", text("deny"), "matchers", rule.field("matchers")));
        VirtualFilterRule.Registry registry = (d, k, id) -> true;
        var initial = VirtualFilterProtocol.plan(empty, target, session, owner, owner, address,
            frame(address, "status", record(Map.of())), 4, registry).orElseThrow().reply().field("state");
        var applied = VirtualFilterProtocol.plan(empty, target, session, owner, owner, address,
            frame(address, "set", record(Map.of("state", initial, "rule", rule))), 4, registry).orElseThrow();
        var live = applied.next();
        var token = applied.reply().field("state");
        var verify = frame(address, "verify", record(Map.of("state", token, "rule", rule)));

        var match = VirtualFilterProtocol.plan(live, target, session, owner, owner, address, verify, 4, registry).orElseThrow();
        assertEquals(text("verified"), match.reply().field("code"));
        assertEquals(token, match.reply().field("state"));
        assertSame(live, match.next());
        assertFalse(match.reply().fields().containsKey("rule"));

        var wrong = VirtualFilterProtocol.plan(live, target, session, owner, owner, address,
            frame(address, "verify", record(Map.of("state", token, "rule", replacement))), 4, registry).orElseThrow();
        assertEquals(text("rule_mismatch"), wrong.reply().field("code"));
        assertSame(live, wrong.next());

        var interveningEdit = live.set(live.version(), replacement, 4, registry).slot();
        assertEquals(text("version_conflict"), VirtualFilterProtocol.plan(interveningEdit, target, session, owner, owner,
            address, verify, 4, registry).orElseThrow().reply().field("code"));
        assertEquals(text("filter_slot_occupied"), VirtualFilterProtocol.plan(live.nativeChanged(true), target, session, owner,
            owner, address, verify, 4, registry).orElseThrow().reply().field("code"));
        assertEquals(text("target_changed"), VirtualFilterProtocol.plan(live, UUID.randomUUID(), session, owner, owner,
            address, verify, 4, registry).orElseThrow().reply().field("code"));
        assertEquals(text("stale_session"), VirtualFilterProtocol.plan(live, target, UUID.randomUUID(), owner, owner,
            address, verify, 4, registry).orElseThrow().reply().field("code"));
        assertEquals(text("unavailable_filter_matcher"), VirtualFilterProtocol.plan(live, target, session, owner, owner,
            address, verify, 4, (d, k, id) -> false).orElseThrow().reply().field("code"));

        var denied = VirtualFilterProtocol.plan(live, target, session, owner, UUID.randomUUID(), address, verify, 4, registry)
            .orElseThrow();
        assertEquals(text("denied"), denied.reply().field("code"));
        assertFalse(denied.reply().fields().containsKey("state"));
        assertSame(live, denied.next());
    }

    private static DataValue.RecordValue frame(DataValue.RecordValue address, String op, DataValue.RecordValue args) {
        return record(Map.of("kind", text("virtual_filter_command"), "version", number(1), "target", address,
            "request", text("one"), "operation", text(op), "arguments", args));
    }
    private static DataValue.RecordValue record(Map<String, DataValue> values) { return new DataValue.RecordValue(values); }
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static DataValue.IntegerValue number(long value) { return new DataValue.IntegerValue(value); }
}
