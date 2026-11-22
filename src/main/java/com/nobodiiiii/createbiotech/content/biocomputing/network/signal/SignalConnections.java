package com.nobodiiiii.createbiotech.content.biocomputing.network.signal;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterPortBinding;
import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;

/** Explicit physical SIO grants, separate from program RAM, serial and legacy message endpoints. */
public final class SignalConnections {
    public record Binding(Direction side, boolean enabled, Optional<ClusterPortBinding> gateway) {
        public Binding {
            Objects.requireNonNull(side); Objects.requireNonNull(gateway);
            if (gateway.isPresent() && gateway.orElseThrow().side() != side) throw invalid();
        }
        public Binding(Direction side, boolean enabled) { this(side, enabled, Optional.empty()); }
    }
    public record GatewayConfiguration(String pin, String alias) {
        public GatewayConfiguration { name(pin); name(alias); }
        public DataValue.RecordValue value() { return new DataValue.RecordValue(Map.of("pin", new DataValue.TextValue(pin), "alias", new DataValue.TextValue(alias))); }
        public static GatewayConfiguration read(DataValue.RecordValue value) {
            if (!value.fields().keySet().equals(Set.of("pin", "alias")) || !(value.field("pin") instanceof DataValue.TextValue pin)
                || !(value.field("alias") instanceof DataValue.TextValue alias)) throw invalid();
            return new GatewayConfiguration(pin.value(), alias.value());
        }
    }
    public static String clearGateway(DataValue.RecordValue value) {
        if (!value.fields().keySet().equals(Set.of("pin")) || !(value.field("pin") instanceof DataValue.TextValue pin)) throw invalid();
        name(pin.value()); return pin.value();
    }
    public record Configuration(String pin, Direction side, boolean enabled) {
        public Configuration { name(pin); Objects.requireNonNull(side); }
        public DataValue.RecordValue value() {
            return new DataValue.RecordValue(Map.of("pin", new DataValue.TextValue(pin),
                "side", new DataValue.TextValue(side.getName()), "enabled", new DataValue.BooleanValue(enabled)));
        }
        public static Configuration read(DataValue.RecordValue value) {
            if (!value.fields().keySet().equals(Set.of("pin", "side", "enabled"))
                || !(value.field("pin") instanceof DataValue.TextValue pin)
                || !(value.field("side") instanceof DataValue.TextValue side)
                || !(value.field("enabled") instanceof DataValue.BooleanValue enabled)) throw invalid();
            var direction = Direction.byName(side.value());
            if (direction == null || !direction.getName().equals(side.value())) throw invalid();
            return new Configuration(pin.value(), direction, enabled.value());
        }
    }
    private Tag durable;
    private Map<String, Binding> bindings = Map.of();
    private Optional<String> diagnostic = Optional.empty();

    public Map<String, Binding> bindings() { return bindings; }
    public Optional<String> diagnostic() { return diagnostic; }
    public Tag write() { return durable == null ? null : durable.copy(); }
    public void read(Tag raw) {
        durable = raw == null ? null : raw.copy(); bindings = Map.of(); diagnostic = Optional.empty();
        if (raw == null) return;
        try {
            if (!(raw instanceof CompoundTag root) || !root.getAllKeys().equals(Set.of("version", "ports"))
                || !root.contains("version", Tag.TAG_INT) || (root.getInt("version") != 1 && root.getInt("version") != 2)
                || !root.contains("ports", Tag.TAG_COMPOUND)) throw invalid();
            var ports = root.getCompound("ports");
            if (ports.size() > HardwarePins.MAX_PINS) throw invalid();
            var result = new TreeMap<String, Binding>();
            for (String key : ports.getAllKeys()) {
                name(key);
                if (!(ports.get(key) instanceof CompoundTag port)
                    || !(port.getAllKeys().equals(Set.of("side", "enabled")) || root.getInt("version") == 2 && port.getAllKeys().equals(Set.of("side", "enabled", "gateway")))
                    || !port.contains("side", Tag.TAG_STRING) || !port.contains("enabled", Tag.TAG_BYTE)
                    || port.getByte("enabled") < 0 || port.getByte("enabled") > 1) throw invalid();
                var side = Direction.byName(port.getString("side"));
                if (side == null || !side.getName().equals(port.getString("side"))) throw invalid();
                var gateway = port.contains("gateway") ? Optional.of(ClusterPortBinding.read(port.get("gateway"))) : Optional.<ClusterPortBinding>empty();
                result.put(key, new Binding(side, port.getBoolean("enabled"), gateway));
            }
            bindings = Collections.unmodifiableMap(result);
        } catch (IllegalArgumentException invalid) { diagnostic = Optional.of("invalid_configuration"); }
    }
    public Map<String, Binding> preview(Configuration config) {
        if (diagnostic.isPresent()) throw invalid();
        var gateway = Optional.ofNullable(bindings.get(config.pin())).flatMap(Binding::gateway);
        if (gateway.isPresent() && gateway.orElseThrow().side() != config.side()) throw new IllegalArgumentException("gateway_locked");
        var next = new TreeMap<>(bindings); next.put(config.pin(), new Binding(config.side(), config.enabled(), gateway));
        if (next.size() > HardwarePins.MAX_PINS) throw invalid();
        return Collections.unmodifiableMap(next);
    }
    public void configure(Configuration config) {
        replace(preview(config));
    }
    public Map<String, Binding> previewGateway(String pin, Optional<ClusterPortBinding> gateway) {
        name(pin); Objects.requireNonNull(gateway); if (diagnostic.isPresent()) throw invalid();
        var old = bindings.get(pin);
        if (old == null && gateway.isEmpty()) throw new IllegalArgumentException("missing_pin");
        if (old != null && old.gateway().equals(gateway)) return bindings;
        var next = new TreeMap<>(bindings);
        next.put(pin, new Binding(gateway.map(ClusterPortBinding::side).orElseGet(() -> old.side()), false, gateway));
        if (next.size() > HardwarePins.MAX_PINS) throw invalid();
        return Collections.unmodifiableMap(next);
    }
    public void configureGateway(String pin, Optional<ClusterPortBinding> gateway) { replace(previewGateway(pin, gateway)); }
    private void replace(Map<String, Binding> next) {
        var ports = new CompoundTag();
        next.forEach((pin, binding) -> {
            var value = new CompoundTag(); value.putString("side", binding.side().getName());
            value.putBoolean("enabled", binding.enabled()); binding.gateway().ifPresent(route -> value.put("gateway", route.write())); ports.put(pin, value);
        });
        var root = new CompoundTag(); root.putInt("version", 2); root.put("ports", ports);
        bindings = next; durable = root;
    }
    public void clear() { durable = null; bindings = Map.of(); diagnostic = Optional.empty(); }
    private static void name(String pin) { new HardwarePins(List.of(), List.of(pin)); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_configuration"); }
}
