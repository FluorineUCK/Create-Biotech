package com.nobodiiiii.createbiotech.content.biocomputing.device.filter;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Versioned serial records for a single fixed adjacent filter slot. */
final class VirtualFilterProtocol {
    private static final Set<String> FIELDS = Set.of("kind", "version", "target", "request", "operation", "arguments");
    private static final int VERSION = 1, MAX_REQUEST_CHARS = 128;
    private VirtualFilterProtocol() {}

    record Plan(VirtualFilterSlot next, DataValue.RecordValue reply) {}

    static Optional<Plan> plan(VirtualFilterSlot before, UUID target, UUID session, UUID controller, UUID source,
            DataValue.RecordValue address, DataValue.RecordValue frame, int limit, VirtualFilterRule.Registry registry) {
        return plan(before, target, session, controller, source, address, frame, limit, registry, "ready");
    }

    static Optional<Plan> plan(VirtualFilterSlot before, UUID target, UUID session, UUID controller, UUID source,
            DataValue.RecordValue address, DataValue.RecordValue frame, int limit, VirtualFilterRule.Registry registry, String targetStatus) {
        return plan(before, target, session, controller, source, address, frame, limit, registry, targetStatus, "ready");
    }

    static Optional<Plan> plan(VirtualFilterSlot before, UUID target, UUID session, UUID controller, UUID source,
            DataValue.RecordValue address, DataValue.RecordValue frame, int limit, VirtualFilterRule.Registry registry,
            String targetStatus, String writeStatus) {
        if (!text("virtual_filter_command").equals(frame.fields().get("kind")) || !address.equals(frame.fields().get("target"))
            || !(frame.fields().get("request") instanceof DataValue.TextValue request)
            || request.value().isEmpty() || request.value().length() > MAX_REQUEST_CHARS) return Optional.empty();
        boolean authorized = controller != null && controller.equals(source);
        var next = before;
        String code;
        if (!authorized) code = controller == null ? "closed" : "denied";
        else if (!frame.fields().keySet().equals(FIELDS)) code = "invalid_command";
        else if (!number(VERSION).equals(frame.field("version"))) code = "unsupported_version";
        else if (!(frame.field("operation") instanceof DataValue.TextValue operation)
            || !(frame.field("arguments") instanceof DataValue.RecordValue args)) code = "invalid_command";
        else if (!targetStatus.equals("ready")) code = targetStatus;
        else if (operation.value().equals("status")) code = args.fields().isEmpty() ? "ok" : "invalid_command";
        else if (operation.value().equals("verify") || operation.value().equals("set") || operation.value().equals("clear")) {
            boolean verify = operation.value().equals("verify");
            boolean set = operation.value().equals("set");
            if (!args.fields().keySet().equals(set || verify ? Set.of("state", "rule") : Set.of("state"))
                || !(args.field("state") instanceof DataValue.RecordValue token)
                || !(token.fields().get("revision") instanceof DataValue.IntegerValue revision) || revision.value() < 0)
                code = "invalid_command";
            else if (!text(target.toString()).equals(token.fields().get("target_id"))) code = "target_changed";
            else if (!text(session.toString()).equals(token.fields().get("session"))) code = "stale_session";
            else if (verify) code = verifyRule(before, revision.value(), args.field("rule"), registry);
            else if (!before.nativeOccupied() && !writeStatus.equals("ready")) code = writeStatus;
            else {
                var change = set
                    ? args.field("rule") instanceof DataValue.RecordValue rule ? before.set(revision.value(), rule, limit, registry)
                        : new VirtualFilterSlot.Change(before, "invalid_filter_rule")
                    : before.clear(revision.value());
                next = change.slot();
                code = change.code();
            }
        } else code = "unsupported_operation";
        var fields = new LinkedHashMap<String, DataValue>();
        fields.put("kind", text("virtual_filter_reply"));
        fields.put("version", number(VERSION));
        fields.put("target", address);
        fields.put("request", request);
        fields.put("code", text(code));
        if (authorized && targetStatus.equals("ready")) fields.put("state", state(next, target, session));
        return Optional.of(new Plan(next, record(fields)));
    }

    private static String verifyRule(VirtualFilterSlot live, long expected, DataValue value, VirtualFilterRule.Registry registry) {
        if (!(value instanceof DataValue.RecordValue rule)) return "invalid_filter_rule";
        if (live.nativeOccupied()) return "filter_slot_occupied";
        if (live.version() != expected) return "version_conflict";
        if (live.data().filter(rule::equals).isEmpty()) return "rule_mismatch";
        return live.rule().orElseThrow().referencesAvailable(registry) ? "verified" : "unavailable_filter_matcher";
    }

    private static DataValue.RecordValue state(VirtualFilterSlot slot, UUID target, UUID session) {
        return record(Map.of("target_id", text(target.toString()), "session", text(session.toString()), "revision", number(slot.version()),
            "native_occupied", new DataValue.BooleanValue(slot.nativeOccupied()),
            "has_virtual_rule", new DataValue.BooleanValue(slot.data().isPresent())));
    }

    static DataValue.RecordValue record(Map<String, DataValue> fields) { return new DataValue.RecordValue(fields); }
    static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    static DataValue.IntegerValue number(long value) { return new DataValue.IntegerValue(value); }
}
