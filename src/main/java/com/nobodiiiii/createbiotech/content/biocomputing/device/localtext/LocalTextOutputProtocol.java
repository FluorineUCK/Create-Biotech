package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Opt-in device extension. Frames cannot select their audience, identity, or delivery channel. */
final class LocalTextOutputProtocol {
    private static final int VERSION = 1, MAX_REQUEST_CHARS = 128;
    private static final Set<String> FIELDS = Set.of("kind", "version", "target", "request", "operation", "arguments");
    private static final Set<String> SPEAK = Set.of("device", "session", "revision", "text");
    private LocalTextOutputProtocol() {}
    record Plan(LocalTextOutputState.State next, DataValue.RecordValue reply, Optional<String> utterance) {}

    static Optional<Plan> plan(LocalTextOutputKind kind, LocalTextOutputState.State before, UUID source, DataValue.RecordValue address,
            DataValue.RecordValue frame, int bytes, long tick, int gap, boolean budget) {
        var fields = frame.fields();
        if (!text(kind.command()).equals(fields.get("kind")) || !address.equals(fields.get("target"))
            || !(fields.get("request") instanceof DataValue.TextValue request) || request.value().isEmpty()
            || request.value().length() > MAX_REQUEST_CHARS) return Optional.empty();

        boolean authorized = before.controller().filter(id -> id.equals(source)).isPresent();
        String code = "ok", utterance = null;
        if (before.controller().isEmpty()) code = "closed";
        else if (!authorized) code = "denied";
        else if (!fields.keySet().equals(FIELDS)) code = "invalid_command";
        else if (!number(VERSION).equals(frame.field("version"))) code = "unsupported_version";
        else if (!(frame.field("operation") instanceof DataValue.TextValue operation)
            || !(frame.field("arguments") instanceof DataValue.RecordValue args)) code = "invalid_command";
        else {
            if (operation.value().equals("status")) {
                if (!args.fields().isEmpty()) code = "invalid_command";
            } else if (operation.value().equals(kind.operation)) {
                try {
                    code = args.fields().keySet().equals(SPEAK) && args.field("text") instanceof DataValue.TextValue
                        ? checkToken(before, args) : "invalid_command";
                    if (code.equals("ok")) {
                        String line = ((DataValue.TextValue)args.field("text")).value();
                        code = kind.checkText(line, bytes);
                        if (code.equals("ok")) {
                            if (!before.canAdmitAt(tick, gap)) code = kind.rateLimit();
                            else if (!budget) code = kind.budgetExhausted();
                            else { code = "accepted"; utterance = line; }
                        }
                    }
                } catch (IllegalArgumentException malformed) { code = "invalid_command"; }
            } else code = "unsupported_operation";
        }
        var next = utterance == null ? before : LocalTextOutputState.accept(before, tick);
        var reply = new LinkedHashMap<String, DataValue>();
        reply.put("kind", text(kind.reply())); reply.put("version", number(VERSION));
        reply.put("target", address); reply.put("request", request); reply.put("code", text(code));
        if (authorized) reply.put("state", new DataValue.RecordValue(Map.of("device", text(next.device().toString()),
            "session", text(next.session().toString()), "revision", number(next.revision()), "last", text(next.last()))));
        return Optional.of(new Plan(next, new DataValue.RecordValue(reply), Optional.ofNullable(utterance)));
    }

    static String checkToken(LocalTextOutputState.State state, DataValue.RecordValue args) {
        if (!(args.fields().get("revision") instanceof DataValue.IntegerValue version) || version.value() < 0)
            return "invalid_command";
        var device = uuid(args.field("device")); var session = uuid(args.field("session"));
        if (!state.device().equals(device)) return "device_changed";
        if (!state.session().equals(session)) return "stale_session";
        if (state.revision() != version.value()) return "changed";
        if (state.pending()) return "busy";
        return state.revision() == Long.MAX_VALUE ? "version_exhausted" : "ok";
    }

    private static UUID uuid(DataValue value) {
        if (!(value instanceof DataValue.TextValue text) || text.value().length() != 36)
            throw new IllegalArgumentException("invalid_command");
        var id = UUID.fromString(text.value());
        if (!id.toString().equals(text.value())) throw new IllegalArgumentException("invalid_command");
        return id;
    }

    private static DataValue.TextValue text(String s) { return new DataValue.TextValue(s); }
    private static DataValue.IntegerValue number(long n) { return new DataValue.IntegerValue(n); }
}
