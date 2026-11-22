package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.SimpleIoState;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwareSelection;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;

/** A one-shot clocked output mux. It never writes the instruction-owned SIO latches.
 * Remaining counts future driven ticks of the current segment (not world time).
 * The last tick's driven level is transient and cannot leak from an unloaded case. */
final class VindicatorSequencer {
    static final String ROOT = "BiocomputingVindicatorSequencer";
    static final int CAPACITY = 16;
    private static final ResourceLocation SPECIES = ResourceLocation.withDefaultNamespace("vindicator");
    static boolean supports(HardwareSelection h) {
        return h.ready() && h.resident().filter(r -> SPECIES.equals(r.entityType())).isPresent();
    }

    private record Segment(int level, long ticks) {
        Segment {
            if (level < SimpleIoState.MIN || level > SimpleIoState.MAX || ticks <= 0)
                throw new IllegalArgumentException("invalid_sequencer_segment");
        }
        RecordValue value() {
            return new RecordValue(Map.of("level",new IntegerValue(level),"ticks",new IntegerValue(ticks)));
        }
    }
    private String pin = "";
    private List<Segment> segments = List.of();
    private int index;
    private long remaining;
    private boolean driving;
    private Tag invalid;

    boolean armed() { return !segments.isEmpty(); }
    boolean configured() { return armed() || invalid != null; }
    String pin() { return pin; }
    void suspend() { driving = false; }
    void stop() {
        pin = ""; segments = List.of(); index = 0; remaining = 0; driving = false; invalid = null;
    }
    void configure(RecordValue request, List<String> pins) {
        var fields = request.fields();
        if (!(fields.get("op") instanceof TextValue op)) throw new IllegalArgumentException("invalid_sequencer_command");
        if (op.value().equals("stop") && fields.size() == 1) { stop(); return; }
        if (!op.value().equals("start") || fields.size() != 3 || !(fields.get("pin") instanceof TextValue output)
            || !pins.contains(output.value())) throw new IllegalArgumentException("invalid_sequencer_pin");
        if (!(fields.get("segments") instanceof ListValue list) || list.values().isEmpty() || list.values().size() > CAPACITY)
            throw new IllegalArgumentException("invalid_sequencer_length");
        var parsed = new ArrayList<Segment>();
        for (var value : list.values()) {
            if (!(value instanceof RecordValue segment) || segment.fields().size() != 2
                || !(segment.fields().get("level") instanceof IntegerValue level)
                || level.value() < SimpleIoState.MIN || level.value() > SimpleIoState.MAX
                || !(segment.fields().get("ticks") instanceof IntegerValue ticks))
                throw new IllegalArgumentException("invalid_sequencer_segment");
            parsed.add(new Segment((int)level.value(), ticks.value()));
        }
        stop(); pin = output.value(); segments = List.copyOf(parsed); remaining = segments.getFirst().ticks();
    }
    void tick() {
        if (!armed()) return;
        if (remaining == 0) {
            if (++index == segments.size()) { stop(); return; }
            remaining = segments.get(index).ticks();
        }
        driving = true;
        remaining--;
    }
    SimpleIoState mux(SimpleIoState ordinary, boolean enabled) {
        if (!armed()) return ordinary;
        return enabled && driving ? ordinary.write(pin,segments.get(index).level()) : ordinary.input(pin);
    }
    RecordValue status(boolean available, boolean paused) {
        String state = !available ? "unavailable" : invalid != null ? "invalid_save" : !armed() ? "idle"
            : paused ? "paused" : driving ? "running" : "armed";
        return new RecordValue(Map.of("available",new BooleanValue(available),"state",new TextValue(state),
            "pin",new TextValue(pin),"index",new IntegerValue(index),"remaining",new IntegerValue(remaining),
            "level",new IntegerValue(available && !paused && driving ? segments.get(index).level() : -1),
            "segments",new ListValue(segments.stream().<DataValue>map(Segment::value).toList())));
    }
    Tag save() {
        if (invalid != null) return invalid.copy();
        if (!armed()) return null;
        var t = new CompoundTag();
        t.putInt("Version",1); t.putString("Pin",pin);
        t.putIntArray("Levels",segments.stream().mapToInt(Segment::level).toArray());
        t.putLongArray("Ticks",segments.stream().mapToLong(Segment::ticks).toArray());
        t.putInt("Index",index); t.putLong("Remaining",remaining);
        return t;
    }
    void read(Tag raw) {
        stop();
        if (raw == null) return;
        try {
            if (!(raw instanceof CompoundTag t) || !t.getAllKeys().equals(Set.of("Version","Pin","Index","Remaining","Levels","Ticks"))
                || !t.contains("Version",Tag.TAG_INT) || t.getInt("Version") != 1
                || !t.contains("Pin",Tag.TAG_STRING) || t.getString("Pin").isBlank()
                || t.getString("Pin").length() > com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins.MAX_NAME
                || !t.contains("Index",Tag.TAG_INT)
                || !t.contains("Remaining",Tag.TAG_LONG) || !t.contains("Levels",Tag.TAG_INT_ARRAY)
                || !t.contains("Ticks",Tag.TAG_LONG_ARRAY)) throw new IllegalArgumentException();
            var levels = t.getIntArray("Levels"); var ticks = t.getLongArray("Ticks");
            if (levels.length == 0 || levels.length > CAPACITY || levels.length != ticks.length) throw new IllegalArgumentException();
            var parsed = new ArrayList<Segment>();
            for (int i=0;i<levels.length;i++) parsed.add(new Segment(levels[i],ticks[i]));
            int at = t.getInt("Index"); long left = t.getLong("Remaining");
            if (at < 0 || at >= levels.length || left < 0 || left > ticks[at]) throw new IllegalArgumentException();
            pin = t.getString("Pin"); segments = List.copyOf(parsed); index = at; remaining = left;
        } catch (IllegalArgumentException malformed) {
            invalid = raw.copy();
        }
    }
}
