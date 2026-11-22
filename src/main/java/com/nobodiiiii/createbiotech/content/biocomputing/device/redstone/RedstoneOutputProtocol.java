package com.nobodiiiii.createbiotech.content.biocomputing.device.redstone;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;

/** Opt-in redstone hardware protocol. Ordinary serial frames and other device extensions are unrestricted. */
public final class RedstoneOutputProtocol {
    private RedstoneOutputProtocol() {}
    public record Plan(RedstoneOutputState.State next, DataValue.RecordValue reply) {}
    public static final int VERSION = 1;
    public static final int MAX_REQUEST_CHARS = 128;
    private static final Set<String> FIELDS = Set.of("kind", "version", "target", "request", "operation", "arguments");

    /** Pure preparation; source is verified physical metadata, NEVER a field in the command. */
    public static Optional<Plan> plan(RedstoneOutputState.State before, UUID source,
            DataValue.RecordValue address, DataValue.RecordValue frame) {
        if (!text("redstone_command").equals(frame.fields().get("kind")) || !address.equals(frame.fields().get("target")))
            return Optional.empty();
        if (!(frame.fields().get("request") instanceof DataValue.TextValue request)
            || request.value().isEmpty() || request.value().length() > MAX_REQUEST_CHARS) return Optional.empty();
        String code = "ok"; var next = before;
        boolean authorized = before.controller().filter(id -> id.equals(source)).isPresent();
        if (before.controller().isEmpty()) code = "closed";
        else if (!authorized) code = "denied";
        else if (!frame.fields().keySet().equals(FIELDS)) code = "invalid_command";
        else if (!integer(VERSION).equals(frame.field("version"))) code = "unsupported_version";
        else if (!(frame.field("operation") instanceof DataValue.TextValue operation)
            || !(frame.field("arguments") instanceof DataValue.RecordValue arguments)) code = "invalid_command";
        else try {
            switch (operation.value()) {
                case "status" -> { if (!arguments.fields().isEmpty()) code = "invalid_command"; }
                case "set" -> {
                    if (!arguments.fields().keySet().equals(Set.of("device", "session", "revision", "level")))
                        throw new IllegalArgumentException("invalid_command");
                    UUID device = uuid(arguments.field("device")), session = uuid(arguments.field("session"));
                    if (!(arguments.field("revision") instanceof DataValue.IntegerValue revision) || revision.value() < 0
                        || !(arguments.field("level") instanceof DataValue.IntegerValue level)) throw new IllegalArgumentException("invalid_command");
                    if (level.value() < 0 || level.value() > 15) code = "out_of_range";
                    else {
                        var update = RedstoneOutputState.set(before, source, device, session, revision.value(), (int)level.value());
                        code = update.code(); next = update.state();
                    }
                }
                default -> code = "unsupported_operation";
            }
        } catch (IllegalArgumentException malformed) { code = "invalid_command"; }
        var reply = new LinkedHashMap<String, DataValue>();
        reply.put("kind", text("redstone_reply")); reply.put("version", integer(VERSION)); reply.put("target", address);
        reply.put("request", request); reply.put("code", text(code));
        // Refusals never disclose a device session or its expected value to an ungranted source.
        if (authorized) reply.put("state", state(next));
        return Optional.of(new Plan(next, new DataValue.RecordValue(reply)));
    }
    public static DataValue.RecordValue state(RedstoneOutputState.State state) {
        return new DataValue.RecordValue(Map.of("device", text(state.device().toString()), "session", text(state.session().toString()),
            "revision", integer(state.revision()), "desired", integer(state.desired()), "applied", integer(state.applied())));
    }
    private static UUID uuid(DataValue value) {
        if (!(value instanceof DataValue.TextValue text)) throw new IllegalArgumentException("invalid_command");
        var id = UUID.fromString(text.value());
        if (!id.toString().equals(text.value())) throw new IllegalArgumentException("invalid_command");
        return id;
    }
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
}
