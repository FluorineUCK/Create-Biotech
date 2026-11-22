package com.nobodiiiii.createbiotech.content.biocomputing.device.label;

import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LabelOutputProtocolTest {
    private final UUID source = UUID.randomUUID();
    private final DataValue.RecordValue address = new DataValue.RecordValue(Map.of("x", number(1), "y", number(2), "z", number(3)));

    @Test void authorizedLabelSurvivesReloadButRevocationClearsIt() {
        var granted = LabelOutputState.grant(LabelOutputState.closed(UUID.randomUUID(), UUID.randomUUID()),
            Optional.of(source), UUID.randomUUID());
        var frame = replace(granted, "原料就绪");
        var accepted = LabelOutputProtocol.plan(granted, source, address, frame, 96).orElseThrow();
        assertEquals(text("accepted"), accepted.reply().field("code"));
        assertEquals("原料就绪", accepted.next().label());

        var restored = LabelOutputState.load(LabelOutputState.save(accepted.next()), UUID.randomUUID());
        assertEquals("原料就绪", restored.label());
        var stale = LabelOutputProtocol.plan(restored, source, address, frame, 96).orElseThrow();
        assertEquals(text("stale_session"), stale.reply().field("code"));
        assertSame(restored, stale.next());

        var revoked = LabelOutputState.grant(restored, Optional.empty(), UUID.randomUUID());
        assertEquals("", revoked.label());
        assertEquals("", LabelOutputState.load(LabelOutputState.save(revoked), UUID.randomUUID()).label());
        var closed = LabelOutputProtocol.plan(revoked, source, address, replace(restored, "迟到的请求"), 96).orElseThrow();
        assertEquals(text("closed"), closed.reply().field("code"));
        assertFalse(closed.reply().fields().containsKey("state"));
        assertSame(revoked, closed.next());
    }

    @Test void sharedWireAndInvalidRequestsNeverChangeTheExistingLabel() {
        var grant = LabelOutputState.grant(LabelOutputState.closed(UUID.randomUUID(), UUID.randomUUID()),
            Optional.of(source), UUID.randomUUID());
        var before = LabelOutputProtocol.plan(grant, source, address, replace(grant, "Keep"), 96).orElseThrow().next();
        var unrelated = new DataValue.RecordValue(Map.of("unrelated", text("payload")));
        assertTrue(LabelOutputProtocol.plan(before, source, address, unrelated, 96).isEmpty());

        var denied = LabelOutputProtocol.plan(before, UUID.randomUUID(), address, replace(before, "Spoofed"), 96).orElseThrow();
        assertEquals(text("denied"), denied.reply().field("code"));
        assertFalse(denied.reply().fields().containsKey("state"));
        assertSame(before, denied.next());

        var missingText = command("replace", tokens(before));
        assertRejected(before, missingText, 96, "invalid_command");
        assertRejected(before, replace(before, "more\nthan one line"), 96, "invalid_label");
        assertRejected(before, replace(before, "§aLegacy color"), 96, "invalid_label");
        assertRejected(before, replace(before, "空".repeat(33)), 96, "label_capacity");
        assertRejected(before, replace(before, "字".repeat(86)), LabelOutputState.FORMAT_MAX_BYTES, "label_capacity");

        var clear = command("clear", tokens(before));
        var cleared = LabelOutputProtocol.plan(before, source, address, clear, 96).orElseThrow();
        assertEquals(text("accepted"), cleared.reply().field("code"));
        assertEquals("", cleared.next().label());
        assertRejected(cleared.next(), clear, 96, "changed");
    }

    @Test void offsetIsAtomicBoundedAndCompatibleWithOldLabels() {
        var grant = LabelOutputState.grant(LabelOutputState.closed(UUID.randomUUID(), UUID.randomUUID()),
            Optional.of(source), UUID.randomUUID());
        var args = new java.util.LinkedHashMap<>(tokens(grant));
        args.put("text", text("READY"));
        var offset = new DataValue.RecordValue(Map.of("x", new DataValue.DecimalValue(-1.5), "y", number(8), "z", number(0)));
        args.put("offset", offset);
        var plan = LabelOutputProtocol.plan(grant, source, address, command("replace", args), 96).orElseThrow();
        assertEquals(text("accepted"), plan.reply().field("code"));
        var saved = LabelOutputState.save(plan.next());
        assertEquals(-1.5, saved.getCompound("Offset").getDouble("x"));
        var restored = LabelOutputState.load(saved, UUID.randomUUID());
        assertEquals(saved.get("Offset"), LabelOutputState.save(restored).get("Offset"));
        assertRejected(restored, command("replace", args), 96, "stale_session");
        args.putAll(tokens(restored));
        for (var bad : java.util.List.of(text("1"), new DataValue.RecordValue(Map.of("x", number(0))),
                new DataValue.RecordValue(Map.of("x", number(0), "y", number(-9), "z", number(0))))) {
            args.put("offset", bad);
            assertRejected(restored, command("replace", args), 96, "invalid_offset");
        }
        var reset = LabelOutputProtocol.plan(restored, source, address, replace(restored, "READY"), 96).orElseThrow();
        assertEquals(text("accepted"), reset.reply().field("code"));
        assertEquals(0, LabelOutputState.save(reset.next()).getCompound("Offset").getDouble("x"));
        var legacy = saved.copy();
        legacy.remove("Offset"); legacy.putInt("Version", 1);
        assertEquals(reset.next().offset(), LabelOutputState.load(legacy, UUID.randomUUID()).offset());
        saved.getCompound("Offset").putDouble("z", Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> LabelOutputState.load(saved, UUID.randomUUID()));
    }

    private void assertRejected(LabelOutputState.State before, DataValue.RecordValue frame, int limit, String code) {
        var rejected = LabelOutputProtocol.plan(before, source, address, frame, limit).orElseThrow();
        assertSame(before, rejected.next());
        assertEquals(text(code), rejected.reply().field("code"));
    }

    private Map<String, DataValue> tokens(LabelOutputState.State state) {
        return Map.of("device", text(state.device().toString()), "session", text(state.session().toString()),
            "revision", number(state.revision()));
    }

    private DataValue.RecordValue replace(LabelOutputState.State state, String label) {
        var args = new java.util.LinkedHashMap<>(tokens(state));
        args.put("text", text(label));
        return command("replace", args);
    }

    private DataValue.RecordValue command(String operation, Map<String, DataValue> args) {
        return new DataValue.RecordValue(Map.of("kind", text("label_output_command"), "version", number(1), "target", address,
            "request", text("label:1"), "operation", text(operation), "arguments", new DataValue.RecordValue(args)));
    }

    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static DataValue.IntegerValue number(long value) { return new DataValue.IntegerValue(value); }
}
