package com.nobodiiiii.createbiotech.content.biocomputing.device.music;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;

/** Opt-in serial extension. Payloads never select their own sender or an arbitrary sound/target block. */
public final class MusicOutputProtocol {
    private MusicOutputProtocol() {}
    public static final int VERSION = 1, MAX_REQUEST_CHARS = 128;
    private static final Set<String> FIELDS = Set.of("kind", "version", "target", "request", "operation", "arguments");
    public record Plan(MusicOutputState.State next, DataValue.RecordValue reply) {}
    public static Optional<Plan> plan(MusicOutputState.State before, UUID source, DataValue.RecordValue address, DataValue.RecordValue frame) {
        if (!text("music_output_command").equals(frame.fields().get("kind")) || !address.equals(frame.fields().get("target"))) return Optional.empty();
        if (!(frame.fields().get("request") instanceof DataValue.TextValue request) || request.value().isEmpty()
            || request.value().length() > MAX_REQUEST_CHARS) return Optional.empty();
        var next = before; String code = "ok";
        boolean authorized = before.controller().filter(id -> id.equals(source)).isPresent();
        if (before.controller().isEmpty()) code = "closed";
        else if (!authorized) code = "denied";
        else if (!frame.fields().keySet().equals(FIELDS)) code = "invalid_command";
        else if (!number(VERSION).equals(frame.field("version"))) code = "unsupported_version";
        else if (!(frame.field("operation") instanceof DataValue.TextValue operation)
            || !(frame.field("arguments") instanceof DataValue.RecordValue arguments)) code = "invalid_command";
        else try {
            switch (operation.value()) {
                case "status" -> { if (!arguments.fields().isEmpty()) code = "invalid_command"; }
                case "play" -> {
                    if (!arguments.fields().keySet().equals(Set.of("device", "session", "revision", "instrument", "note")))
                        throw new IllegalArgumentException("invalid_command");
                    if (!(arguments.field("revision") instanceof DataValue.IntegerValue revision) || revision.value() < 0)
                        throw new IllegalArgumentException("invalid_command");
                    UUID device = uuid(arguments.field("device")), session = uuid(arguments.field("session"));
                    if (!(arguments.field("instrument") instanceof DataValue.TextValue instrument)
                        || !(arguments.field("note") instanceof DataValue.IntegerValue pitch)) throw new IllegalArgumentException("invalid_note");
                    var note = MusicOutputState.Note.parse(instrument.value(), pitch.value());
                    var update = MusicOutputState.accept(before, source, device, session, revision.value(), note);
                    code = update.code(); next = update.state();
                }
                default -> code = "unsupported_operation";
            }
        } catch (IllegalArgumentException invalid) { code = "invalid_note".equals(invalid.getMessage()) ? "invalid_note" : "invalid_command"; }
        var fields = new LinkedHashMap<String, DataValue>();
        fields.put("kind", text("music_output_reply")); fields.put("version", number(VERSION)); fields.put("target", address);
        fields.put("request", request); fields.put("code", text(code));
        if (authorized) fields.put("state", state(next));
        return Optional.of(new Plan(next, new DataValue.RecordValue(fields)));
    }
    public static DataValue.RecordValue state(MusicOutputState.State state) {
        return new DataValue.RecordValue(Map.of("device", text(state.device().toString()), "session", text(state.session().toString()),
            "revision", number(state.revision()), "pending", new DataValue.BooleanValue(state.pending().isPresent()), "last", text(state.last())));
    }
    private static UUID uuid(DataValue value) {
        if (!(value instanceof DataValue.TextValue text)) throw new IllegalArgumentException("invalid_command");
        UUID id = UUID.fromString(text.value()); if (!id.toString().equals(text.value())) throw new IllegalArgumentException("invalid_command");
        return id;
    }
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static DataValue.IntegerValue number(long value) { return new DataValue.IntegerValue(value); }
}
