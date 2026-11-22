package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;

/** Documented, opt-in device records, on the same physical grant as narration. */
final class SpeakerPlaybackProtocol {
    private static final String COMMAND = "speaker_media_command", REPLY = "speaker_media_reply";
    private static final DataValue.IntegerValue VERSION = new DataValue.IntegerValue(1);
    private static final Set<String> COMMAND_KEYS = Set.of("kind", "version", "target", "request", "operation", "arguments");
    private static final Set<String> PLAY_KEYS = Set.of("device", "session", "revision", "reference");
    private static final Set<String> STOP_KEYS = Set.of("device", "session", "revision");

    enum Action { NONE, PLAY, STOP }
    record Command(DataValue.TextValue request, String code, boolean authorized, Action action, SpeakerMediaPlaybackSource.Reference reference) {
        DataValue.RecordValue reply(DataValue.RecordValue target, LocalTextOutputState.State state, String outcome, String playback) {
            var fields = new LinkedHashMap<String, DataValue>(Map.of("kind", text(REPLY), "version", VERSION,
                "target", target, "request", request, "code", text(outcome)));
            if (authorized) fields.put("state", new DataValue.RecordValue(Map.of("device", text(state.device().toString()),
                "session", text(state.session().toString()), "revision", new DataValue.IntegerValue(state.revision()), "playback", text(playback))));
            return new DataValue.RecordValue(fields);
        }
    }

    static boolean usesMediaProtocol(DataValue.RecordValue frame) { return text(COMMAND).equals(frame.fields().get("kind")); }

    static Command decode(DataValue.RecordValue frame, DataValue.RecordValue target, LocalTextOutputState.State state, UUID source) {
        if (!target.equals(frame.fields().get("target"))
            || !(frame.fields().get("request") instanceof DataValue.TextValue request) || request.value().isEmpty() || request.value().length() > 128)
            return null;
        boolean allowed = state.controller().filter(id -> id.equals(source)).isPresent();
        if (!allowed) return error(request, state.controller().isEmpty() ? "closed" : "denied", false);
        if (!frame.fields().keySet().equals(COMMAND_KEYS)) return error(request, "invalid_command", true);
        if (!VERSION.equals(frame.field("version"))) return error(request, "unsupported_version", true);
        if (!(frame.field("operation") instanceof DataValue.TextValue action) || !(frame.field("arguments") instanceof DataValue.RecordValue args))
            return error(request, "invalid_command", true);
        if ("status".equals(action.value())) return error(request, args.fields().isEmpty() ? "ok" : "invalid_command", true);
        if (!Set.of("play", "stop").contains(action.value())) return error(request, "unsupported_operation", true);
        boolean play = action.value().equals("play");
        if (!args.fields().keySet().equals(play ? PLAY_KEYS : STOP_KEYS)) return error(request, "invalid_command", true);
        try {
            String code = LocalTextOutputProtocol.checkToken(state, args);
            if (!code.equals("ok")) return error(request, code, true);
        } catch (IllegalArgumentException malformed) { return error(request, "invalid_command", true); }
        try {
            var reference = play ? SpeakerMediaPlaybackSource.Reference.decode(args.field("reference")) : null;
            return new Command(request, "accepted", true, play ? Action.PLAY : Action.STOP, reference);
        } catch (IllegalArgumentException invalid) { return error(request, "invalid_media_reference", true); }
    }

    private static Command error(DataValue.TextValue request, String code, boolean authorized) {
        return new Command(request, code, authorized, Action.NONE, null);
    }
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
}
