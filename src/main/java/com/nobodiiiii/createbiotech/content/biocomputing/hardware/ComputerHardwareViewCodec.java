package com.nobodiiiii.createbiotech.content.biocomputing.hardware;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits;
import java.util.*;
import net.minecraft.resources.ResourceLocation;

/** Strict value projection, bounded by the enclosing menu's normal value/archive codec. */
public final class ComputerHardwareViewCodec {
    private ComputerHardwareViewCodec() {}

    public static DataValue value(ComputerHardwareView view) {
        return new DataValue.RecordValue(Map.of(
            "status", new DataValue.TextValue(view.status().name()),
            "profile", new DataValue.TextValue(view.profile()),
            "resident", view.resident().<DataValue>map(r -> new DataValue.RecordValue(Map.of(
                "type", new DataValue.TextValue(r.entityType().toString()),
                "profession", new DataValue.TextValue(r.profession().toString()), "level", new DataValue.IntegerValue(r.level()))))
                .orElse(DataValue.NullValue.INSTANCE),
            "effective", view.effective().<DataValue>map(e -> new DataValue.RecordValue(Map.of(
                "program", new DataValue.IntegerValue(e.resources().programUnits()),
                "memory", new DataValue.IntegerValue(e.resources().memoryUnits()),
                "work", new DataValue.IntegerValue(e.resources().workPerSlice()),
                "register_slots", new DataValue.IntegerValue(e.resources().registerSlots()),
                "task_slots", new DataValue.IntegerValue(e.taskSlots()),
                "isa_1a", new DataValue.BooleanValue(e.isa1a()),
                "isa_1b", new DataValue.BooleanValue(e.isa1b()),
                "xbus", strings(e.pins().xbus()), "simple_io", strings(e.pins().simpleIo()), "serial", serial(e.serial())))).orElse(DataValue.NullValue.INSTANCE)));
    }

    public static ComputerHardwareView read(DataValue value) {
        var root = record(value, "status", "profile", "resident", "effective");
        Optional<ResidentHardwareIdentity> resident = Optional.empty();
        if (!(root.field("resident") instanceof DataValue.NullValue)) {
            var r = record(root.field("resident"), "type", "profession", "level");
            long level = number(r.field("level"));
            if (level < Integer.MIN_VALUE || level > Integer.MAX_VALUE) throw invalid();
            resident = Optional.of(new ResidentHardwareIdentity(identifier(r.field("type")), identifier(r.field("profession")), (int)level));
        }
        Optional<ComputerHardwareView.Effective> effective = Optional.empty();
        if (!(root.field("effective") instanceof DataValue.NullValue)) {
            var e = record(root.field("effective"), "program", "memory", "work", "register_slots", "task_slots", "isa_1a", "isa_1b", "xbus", "simple_io", "serial");
            effective = Optional.of(new ComputerHardwareView.Effective(
                new ResourceLimits(number(e.field("program")), number(e.field("memory")), number(e.field("work")), integer(e.field("register_slots"))),
                new HardwarePins(strings(e.field("xbus")), strings(e.field("simple_io"))), serial(e.field("serial")), integer(e.field("task_slots")), bool(e.field("isa_1a")), bool(e.field("isa_1b"))));
        }
        return new ComputerHardwareView(HardwareSelection.Status.valueOf(text(root.field("status"))), resident,
            text(root.field("profile")), effective);
    }
    private static DataValue.RecordValue serial(SerialHardware hardware) {
        var ports = new LinkedHashMap<String, DataValue>();
        hardware.ports().forEach((name, p) -> ports.put(name, new DataValue.RecordValue(Map.of(
            "bits_per_tick", new DataValue.IntegerValue(p.bitsPerTick()), "frame_bytes", new DataValue.IntegerValue(p.maxFrameBytes()),
            "receive_frames", new DataValue.IntegerValue(p.receiveFrames()), "receive_bytes", new DataValue.IntegerValue(p.receiveBytes()),
            "tx_frames",new DataValue.IntegerValue(p.transmitFrames()), "tx_bytes",new DataValue.IntegerValue(p.transmitBytes()),
            "duplex", p.duplex().<DataValue>map(d->new DataValue.RecordValue(Map.of("tx", new DataValue.IntegerValue(d.transmitBitsPerTick()),
                "rx", new DataValue.IntegerValue(d.receiveBitsPerTick())))).orElse(DataValue.NullValue.INSTANCE)))));
        return new DataValue.RecordValue(ports);
    }
    private static SerialHardware serial(DataValue value) {
        if (!(value instanceof DataValue.RecordValue record) || record.fields().size() > HardwarePins.MAX_PINS) throw invalid();
        var ports = new LinkedHashMap<String, SerialHardware.Port>();
        record.fields().forEach((name, raw) -> {
            var p = record(raw, "bits_per_tick", "frame_bytes", "receive_frames", "receive_bytes", "duplex","tx_frames","tx_bytes");
            Optional<SerialHardware.IndependentDuplex> duplex = Optional.empty();
            if (!(p.field("duplex") instanceof DataValue.NullValue)) {
                var d=record(p.field("duplex"),"tx","rx");
                duplex=Optional.of(new SerialHardware.IndependentDuplex(number(d.field("tx")),number(d.field("rx"))));
            }
            ports.put(name, new SerialHardware.Port(number(p.field("bits_per_tick")), integer(p.field("frame_bytes")),
                integer(p.field("receive_frames")), number(p.field("receive_bytes")), duplex,integer(p.field("tx_frames")),number(p.field("tx_bytes"))));
        });
        return new SerialHardware(ports);
    }
    private static int integer(DataValue value) {
        long number = number(value);
        if (number < 1 || number > Integer.MAX_VALUE) throw invalid();
        return (int)number;
    }
    private static DataValue.ListValue strings(List<String> values) {
        return new DataValue.ListValue(values.stream().<DataValue>map(DataValue.TextValue::new).toList());
    }
    private static List<String> strings(DataValue value) {
        if (!(value instanceof DataValue.ListValue list) || list.values().size() > HardwarePins.MAX_PINS) throw invalid();
        return list.values().stream().map(ComputerHardwareViewCodec::text).toList();
    }
    private static DataValue.RecordValue record(DataValue value, String... keys) {
        if (value instanceof DataValue.RecordValue record && record.fields().keySet().equals(Set.of(keys))) return record;
        throw invalid();
    }
    private static String text(DataValue value) { if (value instanceof DataValue.TextValue text) return text.value(); throw invalid(); }
    private static boolean bool(DataValue value) { if (value instanceof DataValue.BooleanValue flag) return flag.value(); throw invalid(); }
    private static long number(DataValue value) { if (value instanceof DataValue.IntegerValue number) return number.value(); throw invalid(); }
    private static ResourceLocation identifier(DataValue value) {
        String name = text(value); var id = ResourceLocation.tryParse(name);
        if (id == null || !id.toString().equals(name)) throw invalid(); return id;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid public hardware view"); }
}
