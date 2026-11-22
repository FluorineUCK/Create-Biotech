package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;
import java.util.function.Function;

/** Opt-in sampling extension. It does not constrain ordinary typed or native-NBT serial frames. */
public record ReaderSamplingProtocol(UUID device, UUID session, DataValue.RecordValue address, Set<String> allowed) {
    public ReaderSamplingProtocol { allowed = Set.copyOf(allowed); }
    public static final int MAX_REQUEST_CHARS = 128;
    private static final Set<String> FIELDS = Set.of("kind", "version", "target", "request", "operation", "arguments");

    /** The boolean is established by physical source proof; no wire field can grant permission. */
    public Optional<DataValue.RecordValue> respond(boolean authorized, DataValue.RecordValue frame,
            Function<Set<String>, DataValue.RecordValue> capture) {
        return respond(authorized, frame, null, capture, null);
    }
    public Optional<DataValue.RecordValue> respond(boolean authorized, DataValue.RecordValue frame, ReadLimits limits,
            Function<Set<String>, DataValue.RecordValue> capture,
            java.util.function.BiFunction<String, ReadRange, DataValue.RecordValue> captureRange) {
        if (!text("reader_command").equals(frame.fields().get("kind")) || !address.equals(frame.fields().get("target"))
            || !(frame.fields().get("request") instanceof DataValue.TextValue request)
            || request.value().isEmpty() || request.value().length() > MAX_REQUEST_CHARS) return Optional.empty();
        String code = "ok"; DataValue.RecordValue value = null;
        if (!authorized) code = "denied";
        else if (!frame.fields().keySet().equals(FIELDS)) code = "invalid_command";
        else if (!integer(1).equals(frame.field("version"))) code = "unsupported_version";
        else if (!(frame.field("operation") instanceof DataValue.TextValue operation)
            || !(frame.field("arguments") instanceof DataValue.RecordValue args)) code = "invalid_command";
        else if (operation.value().equals("status")) {
            if (!args.fields().isEmpty()) code = "invalid_command";
        } else if (operation.value().equals("sample")) {
            if (!args.fields().keySet().equals(Set.of("device", "session", "components"))) code = "invalid_command";
            else if (!text(device.toString()).equals(args.field("device"))) code = "device_changed";
            else if (!text(session.toString()).equals(args.field("session"))) code = "stale_session";
            else if (!(args.field("components") instanceof DataValue.ListValue ids) || ids.values().isEmpty()) code = "invalid_command";
            else {
                var selected = new LinkedHashSet<String>();
                for (var id : ids.values()) {
                    if (!(id instanceof DataValue.TextValue name) || !selected.add(name.value())) { code = "invalid_command"; break; }
                }
                if (code.equals("ok") && !allowed.containsAll(selected)) code = "component_denied";
                if (code.equals("ok")) value = capture.apply(Set.copyOf(selected));
            }
        } else if (operation.value().equals("sample_range")) {
            if (!args.fields().keySet().equals(Set.of("device", "session", "component", "offset", "count"))) code = "invalid_command";
            else if (!text(device.toString()).equals(args.field("device"))) code = "device_changed";
            else if (!text(session.toString()).equals(args.field("session"))) code = "stale_session";
            else if (!(args.field("component") instanceof DataValue.TextValue component)
                || !(args.field("offset") instanceof DataValue.IntegerValue offset)
                || !(args.field("count") instanceof DataValue.IntegerValue count)) code = "invalid_command";
            else if (!allowed.contains(component.value())) code = "component_denied";
            else if (captureRange == null || limits == null) code = "unsupported_operation";
            else {
                ReadRange range = null;
                try { range = new ReadRange(Math.toIntExact(offset.value()), Math.toIntExact(count.value())); }
                catch (IllegalArgumentException | ArithmeticException invalid) { code = "invalid_range"; }
                if (range != null) {
                    if (!range.fits(limits)) code = "snapshot_limit";
                    else value = captureRange.apply(component.value(), range);
                }
            }
        } else code = "unsupported_operation";
        var reply = new LinkedHashMap<String, DataValue>();
        reply.put("kind", text("reader_reply")); reply.put("version", integer(1)); reply.put("target", address);
        reply.put("request", request); reply.put("code", text(code));
        if (authorized) reply.put("state", new DataValue.RecordValue(Map.of("device", text(device.toString()),
            "session", text(session.toString()), "components", new DataValue.ListValue(allowed.stream().sorted().<DataValue>map(ReaderSamplingProtocol::text).toList()))));
        if (value != null) reply.put("value", value);
        return Optional.of(new DataValue.RecordValue(reply));
    }
    public static DataValue.RecordValue failure(DataValue.RecordValue reply, String code) {
        var fields = new LinkedHashMap<String, DataValue>(reply.fields());
        fields.remove("value"); fields.remove("state"); fields.put("code", text(code));
        return new DataValue.RecordValue(fields);
    }
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
}
