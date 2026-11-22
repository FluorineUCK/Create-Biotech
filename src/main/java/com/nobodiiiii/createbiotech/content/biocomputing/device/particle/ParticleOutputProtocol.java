package com.nobodiiiii.createbiotech.content.biocomputing.device.particle;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.resources.ResourceLocation;

/** Pure preparation: neither incoming frame nor device, budget, or world changes during validation. */
final class ParticleOutputProtocol {
    private static final int VERSION = 1, MAX_REQUEST_CHARS = 128;
    private static final Set<String> FIELDS = Set.of("kind", "version", "target", "request", "operation", "arguments");
    private static final Set<String> EMIT = Set.of("device", "session", "revision", "burst");
    private static final Set<String> PARAM_ERRORS = Set.of("invalid_particle_parameters", "particle_count_limit", "particle_range_limit",
        "particle_speed_limit", "invalid_particle_kind", "particle_not_allowed", "unknown_particle", "unsupported_particle_parameters");
    private ParticleOutputProtocol() {}
    record Plan(ParticleOutputState.State next, DataValue.RecordValue reply, Optional<ParticleOutputBurst> burst) {}

    static Optional<Plan> plan(ParticleOutputState.State before, UUID source, DataValue.RecordValue address, DataValue.RecordValue frame,
            ParticleOutputBurst.Limits limits, int budget, Function<ResourceLocation, Optional<ParticleType<?>>> registry) {
        var fields = frame.fields();
        if (!text("particle_output_command").equals(fields.get("kind")) || !address.equals(fields.get("target"))
            || !(fields.get("request") instanceof DataValue.TextValue request) || request.value().isEmpty()
            || request.value().length() > MAX_REQUEST_CHARS) return Optional.empty();

        boolean authorized = before.controller().filter(id -> id.equals(source)).isPresent();
        String code = "ok";
        ParticleOutputBurst burst = null;
        if (before.controller().isEmpty()) code = "closed";
        else if (!authorized) code = "denied";
        else if (!fields.keySet().equals(FIELDS)) code = "invalid_command";
        else if (!integer(VERSION).equals(frame.field("version"))) code = "unsupported_version";
        else if (!(frame.field("operation") instanceof DataValue.TextValue operation)
            || !(frame.field("arguments") instanceof DataValue.RecordValue args)) code = "invalid_command";
        else {
            switch (operation.value()) {
                case "status" -> { if (!args.fields().isEmpty()) code = "invalid_command"; }
                case "emit" -> {
                    try {
                        code = checkToken(before, args);
                        if (code.equals("ok")) {
                            var decoded = ParticleOutputBurst.decode((DataValue.RecordValue)args.field("burst"), limits, registry);
                            if (decoded.count() > budget) code = "particle_budget_exhausted";
                            else { code = "accepted"; burst = decoded; }
                        }
                    } catch (IllegalArgumentException malformed) {
                        code = malformed.getMessage() != null && PARAM_ERRORS.contains(malformed.getMessage())
                            ? malformed.getMessage() : "invalid_command";
                    }
                }
                default -> code = "unsupported_operation";
            }
        }
        var next = burst == null ? before : ParticleOutputState.accept(before);
        var reply = new LinkedHashMap<String, DataValue>();
        reply.put("kind", text("particle_output_reply")); reply.put("version", integer(VERSION));
        reply.put("target", address); reply.put("request", request); reply.put("code", text(code));
        if (authorized) reply.put("state", new DataValue.RecordValue(Map.of("device", text(next.device().toString()),
            "session", text(next.session().toString()), "revision", integer(next.revision()), "last", text(next.last()))));
        return Optional.of(new Plan(next, new DataValue.RecordValue(reply), Optional.ofNullable(burst)));
    }

    private static String checkToken(ParticleOutputState.State state, DataValue.RecordValue args) {
        if (!args.fields().keySet().equals(EMIT) || !(args.field("revision") instanceof DataValue.IntegerValue version)
            || version.value() < 0 || !(args.field("burst") instanceof DataValue.RecordValue))
            return "invalid_command";
        UUID device = uuid(args.field("device")), session = uuid(args.field("session"));
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

    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
}
