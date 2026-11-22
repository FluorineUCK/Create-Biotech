package com.nobodiiiii.createbiotech.content.biocomputing.device.settings;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class ComponentSettingsProtocol {
    private static final Set<String> KEYS = Set.of("kind", "version", "target", "request", "operation", "arguments");
    private ComponentSettingsProtocol() {}

    record Command(DataValue.TextValue request, DataValue.RecordValue frame) {}
    static Optional<Command> addressed(DataValue.RecordValue address, DataValue.RecordValue frame) {
        if (!text("component_settings_command").equals(frame.field("kind")) || !address.equals(frame.field("target"))
            || !(frame.field("request") instanceof DataValue.TextValue request) || request.value().isEmpty()
            || request.value().length() > 128) return Optional.empty();
        return Optional.of(new Command(request, frame));
    }

    static DataValue.RecordValue reply(DataValue.RecordValue address, Command command, String result, DataValue state) {
        return record(Map.of("kind", text("component_settings_reply"), "version", number(1), "target", address,
            "request", command.request(), "code", text(result), "state", state));
    }

    static String validate(Command command) {
        var frame = command.frame();
        if (!KEYS.equals(frame.fields().keySet()) || !(frame.field("arguments") instanceof DataValue.RecordValue args))
            return "invalid_command";
        if (!number(1).equals(frame.field("version"))) return "unsupported_version";
        if (text("deployer_status").equals(frame.field("operation"))) return args.fields().isEmpty() ? "ok" : "invalid_command";
        if (text("deployer_process").equals(frame.field("operation"))) return args.fields().keySet().equals(Set.of("token"))
            && args.field("token") instanceof DataValue.TextValue ? "ok" : "invalid_command";
        if (text("arm_status").equals(frame.field("operation")) || text("arm_points_nbt").equals(frame.field("operation"))
            || text("arm_prepare").equals(frame.field("operation")))
            return args.fields().isEmpty() ? "ok" : "invalid_command";
        if (text("arm_move").equals(frame.field("operation"))) return args.fields().keySet().equals(Set.of("token", "input", "output"))
            && args.field("token") instanceof DataValue.TextValue ? "ok" : "invalid_command";
        if (text("motion_status").equals(frame.field("operation"))) return args.fields().isEmpty() ? "ok" : "invalid_command";
        if (text("motion_start").equals(frame.field("operation"))) return args.fields().keySet().equals(Set.of("token", "mode", "amount", "speed"))
            && args.field("token") instanceof DataValue.TextValue ? "ok" : "invalid_command";
        if (text("status").equals(frame.field("operation"))) {
            if (args.fields().isEmpty()) return "ok";
            return args.fields().keySet().equals(Set.of("field")) && args.field("field") instanceof DataValue.TextValue field
                && !field.value().isEmpty() && field.value().length() <= 64 ? "ok" : "invalid_command";
        }
        if (text("set").equals(frame.field("operation"))) return args.fields().keySet().equals(Set.of("token", "field", "value"))
            ? "ok" : "invalid_command";
        return "unsupported_operation";
    }

    static DataValue.RecordValue snapshot(ComponentSettings.Observation actual, UUID token,
                                         Optional<DataValue.RecordValue> motion, java.util.List<String> fields) {
        var field = actual.field();
        DataValue format = switch (field.format()) {
            case ComponentSettings.IntRange range -> intFormat(range);
            case ComponentSettings.Choices choices -> choicesFormat(choices);
            case ComponentSettings.ExtractionBatch batch -> record(Map.of("type", text("record"), "fields",
                record(Map.of("amount", intFormat(batch.amount()), "mode", choicesFormat(batch.mode())))));
            case ComponentSettings.Toggle ignored -> record(Map.of("type", text("boolean")));
            case ComponentSettings.LabelText label -> record(Map.of("type", text("text"),
                "max_bytes", number(label.maximumBytes())));
            case ComponentSettings.ThresholdPair pair -> {
                yield pairFormat("off_below", "on_above", intFormat(pair.limits()), "off_below < on_above");
            }
            case ComponentSettings.DistancePair pair ->
                pairFormat("minimum", "maximum", intFormat(pair.limits()), "minimum <= maximum");
            case ComponentSettings.AltitudePair ignored -> pairFormat("zero_at", "full_at",
                record(Map.of("type", text("decimal"), "min", new DataValue.DecimalValue(0), "max", new DataValue.DecimalValue(1))),
                "zero_at != full_at (native float precision)");
            case ComponentSettings.AnglePair pair ->
                record(Map.of("type", text("record"), "fields", record(Map.of(
                    "primary", intFormat(pair.limits()), "secondary", intFormat(pair.limits())))));
            case ComponentSettings.VelocityCalibration calibration ->
                record(Map.of("type", text("record"), "fields", record(Map.of(
                    "full_speed", intFormat(calibration.speed()), "direction", record(Map.of("type", text("text"),
                    "choices", new DataValue.ListValue(calibration.direction().values().stream()
                        .<DataValue>map(ComponentSettingsProtocol::text).toList())))))));
            case ComponentSettings.FloorNames names ->
                record(Map.of("type", text("record"), "fields", record(Map.of(
                    "short", textFormat(names.shortMaximum()), "long", textFormat(names.longMaximum())))));
        };
        var state = Map.<String, DataValue>of("device", text(actual.device().toString()), "field", text(field.name()), "unit", text(field.unit()),
            "format", format, "value", actual.value(), "timing", text(field.timing()), "busy", new DataValue.BooleanValue(actual.busy()),
            "token", text(token.toString()));
        var extended = new java.util.LinkedHashMap<>(state);
        if (actual.binding() != null) extended.put("binding", actual.binding().value());
        motion.ifPresent(value -> extended.put("motion", value));
        if (fields.size() > 1) extended.put("fields", new DataValue.ListValue(fields.stream().<DataValue>map(ComponentSettingsProtocol::text).toList()));
        return record(extended);
    }
    private static DataValue.RecordValue intFormat(ComponentSettings.IntRange limits) {
        if (!limits.zeroAllowed())
            return record(Map.of("type", text("integer"), "min", number(limits.minimum()), "max", number(limits.maximum()),
                "zero_allowed", new DataValue.BooleanValue(false)));
        return record(Map.of("type", text("integer"), "min", number(limits.minimum()), "max", number(limits.maximum())));
    }
    private static DataValue.RecordValue textFormat(int maxLength) {
        return record(Map.of("type", text("text"), "max_utf16", number(maxLength)));
    }
    private static DataValue.RecordValue choicesFormat(ComponentSettings.Choices choices) {
        return record(Map.of("type", text("text"), "choices",
            new DataValue.ListValue(choices.values().stream().<DataValue>map(ComponentSettingsProtocol::text).toList())));
    }
    private static DataValue.RecordValue pairFormat(String first, String second, DataValue bound, String constraint) {
        return record(Map.of("type", text("record"), "fields", record(Map.of(first, bound, second, bound)), "constraint", text(constraint)));
    }

    static DataValue.RecordValue record(Map<String, DataValue> fields) { return new DataValue.RecordValue(fields); }
    static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    static DataValue.IntegerValue number(long value) { return new DataValue.IntegerValue(value); }
}
