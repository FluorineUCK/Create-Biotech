package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialReceiveBuffer;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialGatewayBinding;
import net.minecraft.core.BlockPos;
import java.util.*;
import net.minecraft.core.Direction;

/** Strict, bounded menu metadata, independent of legacy endpoint grants and program inputs. */
public final class SerialControlData {
    private SerialControlData() {}
    public record PanelSocket(UUID id, BlockPos pos, Direction side) {
        public PanelSocket { Objects.requireNonNull(id); pos = pos.immutable(); Objects.requireNonNull(side); }
        public DataValue.RecordValue encode() { return new DataValue.RecordValue(Map.of(
            "id", text(id.toString()), "pos", new DataValue.IntegerValue(pos.asLong()), "side", text(side.getName()))); }
        public static PanelSocket decode(DataValue value) {
            var r = record(value); exact(r, "id", "pos", "side");
            if (!(r.field("pos") instanceof DataValue.IntegerValue p)) throw new IllegalArgumentException("invalid_panel");
            var side = Direction.byName(string(r.field("side")));
            if (side == null) throw new IllegalArgumentException("invalid_panel");
            return new PanelSocket(UUID.fromString(string(r.field("id"))), BlockPos.of(p.value()), side);
        }
    }
    public record GatewayConfiguration(String port, String alias, Optional<PanelSocket> panel) {
        public GatewayConfiguration(String port, String alias) { this(port, alias, Optional.empty()); }
        public GatewayConfiguration { new HardwarePins(List.of(port), List.of()); new HardwarePins(List.of(alias), List.of()); }
        public DataValue.RecordValue encode() {
            if (panel.isPresent()) return new DataValue.RecordValue(Map.of("port",text(port),"alias",text(alias),"panel",panel.get().encode()));
            return new DataValue.RecordValue(Map.of("port", text(port), "alias", text(alias)));
        }
        public static GatewayConfiguration decode(DataValue.RecordValue value) {
            if(value.fields().containsKey("panel")) {
                exact(value, "port", "alias", "panel");
                return new GatewayConfiguration(string(value.field("port")), string(value.field("alias")), Optional.of(PanelSocket.decode(value.field("panel"))));
            }
            exact(value, "port", "alias"); return new GatewayConfiguration(string(value.field("port")), string(value.field("alias")));
        }
    }
    public static String clearGateway(DataValue.RecordValue value) {
        exact(value, "port"); String name = string(value.field("port")); new HardwarePins(List.of(name), List.of()); return name;
    }
    // Fixed metadata tuple avoids repeating nested field tables for every one of the bounded hardware ports.
    // The already encoded physical face belongs to this route; no payload or private serial identity is included.
    private static DataValue.ListValue gateway(SerialGatewayBinding binding) {
        return new DataValue.ListValue(List.of(text(binding.controller().toString()),
            new DataValue.IntegerValue(binding.position().asLong()), text(binding.alias())));
    }
    private static SerialGatewayBinding gateway(DataValue value, Direction side) {
        if (!(value instanceof DataValue.ListValue tuple) || tuple.values().size() != 3
            || !(tuple.values().get(1) instanceof DataValue.IntegerValue position)) throw new IllegalArgumentException("Invalid gateway metadata");
        return new SerialGatewayBinding(UUID.fromString(string(tuple.values().get(0))), BlockPos.of(position.value()), side, string(tuple.values().get(2)));
    }
    public record Configuration(String port, Direction side, boolean enabled) {
        public Configuration { new HardwarePins(List.of(port), List.of()); Objects.requireNonNull(side); }
        public DataValue.RecordValue encode() {
            return new DataValue.RecordValue(Map.of("port", text(port), "side", text(side.getName()),
                "enabled", new DataValue.BooleanValue(enabled)));
        }
        public static Configuration decode(DataValue.RecordValue value) {
            exact(value, "port", "side", "enabled");
            var side = Direction.byName(string(value.field("side")));
            if (side == null) throw new IllegalArgumentException("Invalid serial face");
            return new Configuration(string(value.field("port")), side, flag(value.field("enabled")));
        }
    }
    public static DataValue.RecordValue encode(SerialDevice.View view) {
        var ports = new TreeMap<String, DataValue>();
        view.ports().forEach((name, port) -> {
            var status = port.status();
            ports.put(name, new DataValue.RecordValue(Map.of("side", port.side().<DataValue>map(d -> text(d.getName())).orElse(DataValue.NullValue.INSTANCE),
                "enabled", new DataValue.BooleanValue(port.enabled()), "gateway", port.gateway().<DataValue>map(SerialControlData::gateway).orElse(DataValue.NullValue.INSTANCE),
                "transmit", text(status.transmit()), "connection", text(status.connection()),
                "failure", text(status.failure()), "frames", new DataValue.IntegerValue(status.receive().frames()),
                "bytes", new DataValue.IntegerValue(status.receive().bytes()), "faults", new DataValue.ListValue(status.receive().faults().stream()
                    .sorted().<DataValue>map(f -> text(f.name())).toList()))));
        });
        return new DataValue.RecordValue(Map.of("serial", new DataValue.RecordValue(Map.of(
            "diagnostic", view.diagnostic().<DataValue>map(SerialControlData::text).orElse(DataValue.NullValue.INSTANCE),
            "ports", new DataValue.RecordValue(ports)))));
    }
    public static SerialDevice.View decode(DataValue.RecordValue value) {
        exact(value, "serial"); var root = record(value.field("serial")); exact(root, "diagnostic", "ports");
        var raw = record(root.field("ports")); new HardwarePins(List.copyOf(raw.fields().keySet()), List.of());
        var ports = new TreeMap<String, SerialDevice.PortView>();
        raw.fields().forEach((name, entry) -> {
            var port = record(entry); exact(port, "side", "enabled", "gateway", "transmit", "connection", "failure", "frames", "bytes", "faults");
            Optional<Direction> side = Optional.empty();
            if (port.field("side") != DataValue.NullValue.INSTANCE) {
                var direction = Direction.byName(string(port.field("side")));
                if (direction == null) throw new IllegalArgumentException("Invalid serial face");
                side = Optional.of(direction);
            }
            boolean enabled = flag(port.field("enabled"));
            var gateway = port.field("gateway") == DataValue.NullValue.INSTANCE ? Optional.<SerialGatewayBinding>empty()
                : Optional.of(gateway(port.field("gateway"), side.orElseThrow(() -> new IllegalArgumentException("Gateway needs a face"))));
            if (enabled && side.isEmpty()) throw new IllegalArgumentException("Enabled serial port has no face");
            long frames = count(port.field("frames")), bytes = count(port.field("bytes"));
            if (frames > Integer.MAX_VALUE || (frames == 0) != (bytes == 0) || bytes < frames)
                throw new IllegalArgumentException("Invalid serial buffer counts");
            if (!(port.field("faults") instanceof DataValue.ListValue faults)) throw new IllegalArgumentException("Invalid serial faults");
            var codes = EnumSet.noneOf(SerialReceiveBuffer.Fault.class);
            for (var fault : faults.values()) if (!codes.add(SerialReceiveBuffer.Fault.valueOf(string(fault)))) throw new IllegalArgumentException("Duplicate serial fault");
            ports.put(name, new SerialDevice.PortView(side, enabled, new SerialDevice.Status(string(port.field("transmit")),
                string(port.field("connection")), string(port.field("failure")), new SerialReceiveBuffer.Status((int)frames, bytes, codes)), gateway));
        });
        var diagnostic = root.field("diagnostic");
        return new SerialDevice.View(diagnostic == DataValue.NullValue.INSTANCE ? Optional.empty() : Optional.of(string(diagnostic)), ports);
    }
    private static DataValue.TextValue text(String text) { return new DataValue.TextValue(text); }
    private static String string(DataValue value) {
        if (value instanceof DataValue.TextValue text && text.value().length() <= HardwarePins.MAX_NAME) return text.value();
        throw new IllegalArgumentException("Invalid serial metadata text");
    }
    private static boolean flag(DataValue value) {
        if (value instanceof DataValue.BooleanValue flag) return flag.value();
        throw new IllegalArgumentException("Invalid serial flag");
    }
    private static long count(DataValue value) {
        if (value instanceof DataValue.IntegerValue count && count.value() >= 0) return count.value();
        throw new IllegalArgumentException("Invalid serial count");
    }
    private static DataValue.RecordValue record(DataValue value) {
        if (value instanceof DataValue.RecordValue record) return record;
        throw new IllegalArgumentException("Invalid serial record");
    }
    private static void exact(DataValue.RecordValue value, String... fields) {
        if (!value.fields().keySet().equals(Set.of(fields))) throw new IllegalArgumentException("Invalid serial fields");
    }
}
