package com.nobodiiiii.createbiotech.content.biocomputing.diagnostics;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice;
import java.util.*;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/** Transient diagnostics only: never an execution checkpoint or a delivery witness. */
public final class RecentEventLog {
    public static final int DEFAULT_CAPACITY = 32;
    public static final int MAX_CAPACITY = 4096, TEXT_CODE_POINTS = 96;
    public enum Kind { WAIT, TRANSFER, UNAVAILABLE, RECOVERED, ERROR, CONTROL }
    private final LongSupplier clock;
    private final IntSupplier capacity;
    private final Deque<DataValue> entries = new ArrayDeque<>();
    private final Map<String, Object> observations = new HashMap<>();
    private Map<String, String> wiring = Map.of();
    private Map<String, SerialDevice.PortView> serial = Map.of();
    private Optional<String> serialDiagnostic = Optional.empty();
    private long omitted, sequence;

    public RecentEventLog(LongSupplier clock, IntSupplier capacity) {
        this.clock = Objects.requireNonNull(clock); this.capacity = Objects.requireNonNull(capacity);
    }
    public void settle(String channel, Object identity) { observations.put(channel, Objects.requireNonNull(identity)); }
    public void transition(String channel, Object identity, Kind kind, String code, String subject) {
        if (!Objects.equals(observations.put(channel, Objects.requireNonNull(identity)), identity)) append(kind, code, subject);
    }
    public void retainChannels(Set<String> channels) { observations.keySet().retainAll(channels); }
    /** Actual scheduler rejection, not binding health. Ordinary waiting is already logged by the CPU.
     * A later handoff has its own post-commit event; observing readiness never manufactures one. */
    public void observeExecution(String channel, Object operation, String code, String subject) {
        if (Set.of("not_ready", "ready", "prepared", "exchanged", "readable").contains(code)) return;
        Kind kind = switch (code) {
            case "recovery_required" -> Kind.WAIT;
            case "paused" -> Kind.CONTROL;
            case "type_mismatch", "memory_limit", "archive_limit", "history_conflict", "invalid_save",
                 "provider_failure", "arithmetic_overflow", "endpoint_limit" -> Kind.ERROR;
            default -> Kind.UNAVAILABLE;
        };
        transition("execution:" + channel, List.of(operation, code), kind, "execution_" + code, subject);
    }
    /** Complete, bounded snapshot of this device's enabled declarations; no payload or access authority.
     * Forget removed/disabled names rather than retaining tombstones or announcing a false recovery. */
    public void observeWiring(Map<String, String> codes) {
        var next = new TreeMap<>(codes);
        for (var entry : next.entrySet()) {
            String code = Objects.requireNonNull(entry.getValue()), previous = wiring.get(entry.getKey());
            if (code.equals(previous)) continue;
            if (code.equals("ready")) {
                if (previous != null) append(Kind.RECOVERED, "wiring_restored", entry.getKey());
            } else append(Set.of("provider_failure", "invalid_save", "endpoint_limit", "failed").contains(code)
                ? Kind.ERROR : Kind.UNAVAILABLE, "wiring_" + code, entry.getKey());
        }
        wiring = Map.copyOf(next);
    }
    /** Local interface observations only. Transmission, physical reception and business replies stay distinct. */
    public void observeSerial(SerialDevice.View view) {
        if (!view.diagnostic().equals(serialDiagnostic)) {
            append(view.diagnostic().isPresent() ? Kind.ERROR : Kind.RECOVERED,
                "serial_" + view.diagnostic().orElse("restored"), "");
            serialDiagnostic = view.diagnostic();
        }
        var next = new TreeMap<String, SerialDevice.PortView>();
        view.ports().forEach((name, port) -> { if (port.side().isPresent()) next.put(name, port); });
        next.forEach((name, port) -> {
            var prior = serial.get(name); var now = port.status(); var before = prior == null ? null : prior.status();
            if (before == null || !now.connection().equals(before.connection())) {
                boolean ready = now.connection().equals("ready");
                append(ready ? before == null ? Kind.CONTROL : Kind.RECOVERED : Kind.UNAVAILABLE,
                    "serial_" + (ready && before != null ? "restored" : now.connection()), name);
            }
            if ((before == null || !now.transmit().equals(before.transmit()) || !now.failure().equals(before.failure()))
                    && !now.transmit().equals("idle") && (before != null || !now.transmit().equals("transmitted"))) {
                Kind kind = switch (now.transmit()) {
                    case "queued", "transmitting" -> Kind.WAIT;
                    case "transmitted" -> Kind.TRANSFER;
                    case "collision" -> Kind.ERROR;
                    default -> Kind.UNAVAILABLE;
                };
                append(kind, "serial_" + (now.transmit().equals("interrupted") && !now.failure().isEmpty() ? now.failure() : now.transmit()), name);
            }
            // The first observation can be a loaded old buffer: it is not proof of a new reception.
            if (before != null && now.receive().frames() > before.receive().frames()) append(Kind.TRANSFER, "serial_received", name);
            for (var fault : now.receive().faults()) if (before == null || !before.receive().faults().contains(fault))
                append(Kind.ERROR, "serial_rx_" + fault.name().toLowerCase(Locale.ROOT), name);
        });
        serial = Map.copyOf(next);
    }
    public void append(Kind kind, String code, String subject) {
        entries.addLast(new DataValue.RecordValue(Map.of(
            "tick", new DataValue.IntegerValue(Math.max(0, clock.getAsLong())),
            "sequence", new DataValue.IntegerValue(++sequence),
            "kind", new DataValue.TextValue(kind.name().toLowerCase(Locale.ROOT)),
            "code", new DataValue.TextValue(bounded(code)), "subject", new DataValue.TextValue(bounded(subject)))));
        trim();
    }
    private void trim() {
        int limit = Math.max(1, Math.min(MAX_CAPACITY, capacity.getAsInt()));
        while (entries.size() > limit) { entries.removeFirst(); omitted++; }
    }
    public DataValue.RecordValue value() {
        trim();
        return new DataValue.RecordValue(Map.of("entries", new DataValue.ListValue(List.copyOf(entries)),
            "omitted", new DataValue.IntegerValue(omitted), "sequence", new DataValue.IntegerValue(sequence)));
    }
    public void clear() { entries.clear(); observations.clear(); wiring = Map.of(); serial = Map.of(); serialDiagnostic = Optional.empty(); omitted = sequence = 0; }
    private static String bounded(String text) {
        Objects.requireNonNull(text);
        String clean = text.replaceAll("[\\p{Cntrl}\\u00a7]", "");
        int length = clean.offsetByCodePoints(0, Math.min(TEXT_CODE_POINTS, clean.codePointCount(0, clean.length())));
        return length == clean.length() ? clean : clean.substring(0, length) + "…";
    }
}
