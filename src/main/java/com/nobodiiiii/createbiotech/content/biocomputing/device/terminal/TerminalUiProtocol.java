package com.nobodiiiii.createbiotech.content.biocomputing.device.terminal;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static com.nobodiiiii.createbiotech.content.biocomputing.device.terminal.TerminalUiState.*;

/** Typed serial extension; no command can impersonate the transport's verified sender or invoke a program. */
final class TerminalUiProtocol {
    private static final int VERSION = 1, MAX_REQUEST_CHARS = 128;
    private static final Set<String> FIELDS = Set.of("kind", "version", "target", "request", "operation", "arguments");
    private static final Set<String> PUBLISH = Set.of("state", "layout", "roots");
    private static final Set<String> TAKE = Set.of("state", "sequence");
    private TerminalUiProtocol() {}
    record Plan(State next, DataValue.RecordValue reply) {}

    /** Pure preparation; apply next only if consumption and the bounded wire reply commit together. */
    static Optional<Plan> plan(State before, UUID source, DataValue.RecordValue address,
            DataValue.RecordValue frame, Limits limits) {
        if (!text("terminal_ui_command").equals(frame.fields().get("kind")) || !address.equals(frame.fields().get("target"))
            || !(frame.fields().get("request") instanceof DataValue.TextValue request)
            || request.value().isEmpty() || request.value().length() > MAX_REQUEST_CHARS) return Optional.empty();
        var next = before;
        DataValue.RecordValue event = null;
        boolean authorized = before.controller().filter(id -> id.equals(source)).isPresent();
        String code;
        if (before.controller().isEmpty()) code = "closed";
        else if (!authorized) code = "denied";
        else if (!frame.fields().keySet().equals(FIELDS)) code = "invalid_command";
        else if (!number(VERSION).equals(frame.field("version"))) code = "unsupported_version";
        else if (!(frame.field("operation") instanceof DataValue.TextValue operation)
            || !(frame.field("arguments") instanceof DataValue.RecordValue args)) code = "invalid_command";
        else {
            code = switch (operation.value()) {
                case "status" -> args.fields().isEmpty() ? "ok" : "invalid_command";
                case "publish", "take_event" -> {
                    boolean write = operation.value().equals("publish");
                    if (!args.fields().keySet().equals(write ? PUBLISH : TAKE)) yield "invalid_command";
                    var token = token(before, args.field("state"));
                    if (!token.equals("ok")) yield token;
                    if (write) {
                        var result = publish(before, args.field("layout"), args.field("roots"), limits);
                        next = result.state();
                        yield result.code();
                    }
                    if (!(args.field("sequence") instanceof DataValue.IntegerValue sequence) || sequence.value() < 1)
                        yield "invalid_command";
                    if (before.events().isEmpty()) yield "no_events";
                    var head = before.events().getFirst();
                    if (head.sequence() != sequence.value()) yield "event_changed";
                    event = head.encode();
                    next = withoutHead(before);
                    yield "event";
                }
                default -> "unsupported_operation";
            };
        }
        var reply = new LinkedHashMap<String, DataValue>();
        reply.put("kind", text("terminal_ui_reply"));
        reply.put("version", number(VERSION));
        reply.put("target", address);
        reply.put("request", request);
        reply.put("code", text(code));
        if (authorized) reply.put("state", state(next));
        if (event != null) reply.put("event", event);
        return Optional.of(new Plan(next, record(reply)));
    }

    private static String token(State before, DataValue value) {
        if (!(value instanceof DataValue.RecordValue args)) return "invalid_command";
        // The ordinary driver can forward the returned state whole. Other fields are advisory, never authoritative.
        var device = uuid(args.fields().get("device"));
        var session = uuid(args.fields().get("session"));
        if (device == null || session == null || !(args.fields().get("revision") instanceof DataValue.IntegerValue revision)
            || revision.value() < 0) return "invalid_command";
        if (!before.device().equals(device)) return "device_changed";
        if (!before.session().equals(session)) return "stale_session";
        if (before.revision() != revision.value()) return "changed";
        return "ok";
    }
    static DataValue.RecordValue state(State current) {
        return record(Map.of("device", text(current.device().toString()), "session", text(current.session().toString()),
            "revision", number(current.revision()), "published", new DataValue.BooleanValue(current.page().isPresent()),
            "event_count", number(current.events().size()),
            "next_event", current.events().isEmpty() ? DataValue.NullValue.INSTANCE : number(current.events().getFirst().sequence())));
    }
    private static UUID uuid(DataValue value) {
        if (!(value instanceof DataValue.TextValue text) || text.value().length() != 36) return null;
        try {
            var id = UUID.fromString(text.value());
            return id.toString().equals(text.value()) ? id : null;
        } catch (IllegalArgumentException invalid) { return null; }
    }
}
