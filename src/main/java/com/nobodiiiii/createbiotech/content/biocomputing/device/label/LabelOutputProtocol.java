package com.nobodiiiii.createbiotech.content.biocomputing.device.label;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Opt-in typed serial extension; the source is verified physical metadata, never command data. */
public final class LabelOutputProtocol {
    private LabelOutputProtocol() {}
    public static final int VERSION = 1;
    private static final int MAX_REQUEST_CHARS = 128;
    private static final Set<String> FIELDS = Set.of("kind", "version", "target", "request", "operation", "arguments");
    private static final Set<String> TOKENS = Set.of("device", "session", "revision");
    private static final Set<String> REPLACE = Set.of("device", "session", "revision", "text");

    private static final Set<String> REPLACE_AT = Set.of("device", "session", "revision", "text", "offset");

    public record Plan(LabelOutputState.State next, DataValue.RecordValue reply) {}

    public static Optional<Plan> plan(LabelOutputState.State before, UUID source, DataValue.RecordValue address,
            DataValue.RecordValue frame, int byteLimit) {
        if (!text("label_output_command").equals(frame.fields().get("kind")) || !address.equals(frame.fields().get("target"))
            || !(frame.fields().get("request") instanceof DataValue.TextValue request)
            || request.value().isEmpty() || request.value().length() > MAX_REQUEST_CHARS) return Optional.empty();

        boolean authorized = before.controller().filter(id -> id.equals(source)).isPresent();
        String code = "ok";
        var next = before;
        if (before.controller().isEmpty()) code = "closed";
        else if (!authorized) code = "denied";
        else if (!frame.fields().keySet().equals(FIELDS)) code = "invalid_command";
        else if (!integer(VERSION).equals(frame.field("version"))) code = "unsupported_version";
        else if (!(frame.field("operation") instanceof DataValue.TextValue operation)
            || !(frame.field("arguments") instanceof DataValue.RecordValue arguments)) code = "invalid_command";
        else {
            switch (operation.value()) {
                case "status" -> { if (!arguments.fields().isEmpty()) code = "invalid_command"; }
                case "replace", "clear" -> {
                    boolean clear = operation.value().equals("clear");
                    try {
                        boolean positioned = !clear && arguments.fields().containsKey("offset");
                        if (!arguments.fields().keySet().equals(clear ? TOKENS : positioned ? REPLACE_AT : REPLACE)
                            || !(arguments.field("revision") instanceof DataValue.IntegerValue revision) || revision.value() < 0)
                            throw new IllegalArgumentException("invalid_command");
                        String label = clear ? "" : literal(arguments.field("text"));
                        UUID device = uuid(arguments.field("device")), session = uuid(arguments.field("session"));
                        if (!before.device().equals(device)) code = "device_changed";
                        else if (!before.session().equals(session)) code = "stale_session";
                        else if (before.revision() != revision.value()) code = "changed";
                        else if (before.revision() == Long.MAX_VALUE) code = "version_exhausted";
                        else if (LabelOutputState.literalBytes(label) > byteLimit) code = "label_capacity";
                        else {
                            var offset = positioned ? LabelOffset.parse(arguments.field("offset")) : LabelOffset.ZERO;
                            next = new LabelOutputState.State(device, session, revision.value() + 1, before.controller(), label,
                                label.isEmpty() ? LabelOffset.ZERO : offset);
                            code = "accepted";
                        }
                    } catch (IllegalArgumentException malformed) {
                        code = Set.of("invalid_label", "label_capacity", "invalid_offset").contains(malformed.getMessage())
                            ? malformed.getMessage() : "invalid_command";
                    }
                }
                default -> code = "unsupported_operation";
            }
        }
        var reply = new LinkedHashMap<String, DataValue>();
        reply.put("kind", text("label_output_reply"));
        reply.put("version", integer(VERSION));
        reply.put("target", address);
        reply.put("request", request);
        reply.put("code", text(code));
        if (authorized) reply.put("state", summary(next));
        return Optional.of(new Plan(next, new DataValue.RecordValue(reply)));
    }

    private static DataValue.RecordValue summary(LabelOutputState.State state) {
        return new DataValue.RecordValue(Map.of("device", text(state.device().toString()), "session", text(state.session().toString()),
            "revision", integer(state.revision()), "bytes", integer(state.bytes()), "offset", state.offset().value()));
    }

    private static UUID uuid(DataValue value) {
        String serialized = literal(value);
        if (serialized.length() != 36) throw new IllegalArgumentException("invalid_command");
        var id = UUID.fromString(serialized);
        if (!id.toString().equals(serialized)) throw new IllegalArgumentException("invalid_command");
        return id;
    }

    private static String literal(DataValue value) {
        if (value instanceof DataValue.TextValue text) return text.value();
        throw new IllegalArgumentException("invalid_command");
    }

    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
}
