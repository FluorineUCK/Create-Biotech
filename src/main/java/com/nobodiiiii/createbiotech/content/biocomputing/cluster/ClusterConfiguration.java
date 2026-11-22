package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import java.util.*;
import net.minecraft.core.BlockPos;

/** Explicit name bindings and routes for geometric members; not the physical member roster or shared RAM.
 * The members field retains its archive name so existing configured routes keep their identity bindings. */
public record ClusterConfiguration(Map<String, Member> members, Map<String, Port> ports, Map<String, Connection> connections,
    Map<String, XBusWire> wires, Map<String, SimpleIoWire> signals, Map<String, XBusPublicPin> publicPins) {
    public ClusterConfiguration(Map<String, Member> members, Map<String, Port> ports, Map<String, Connection> connections,
        Map<String, XBusWire> wires, Map<String, SimpleIoWire> signals) { this(members, ports, connections, wires, signals, Map.of()); }
    public ClusterConfiguration(Map<String, Member> members, Map<String, Port> ports, Map<String, Connection> connections,
        Map<String, XBusWire> wires) { this(members, ports, connections, wires, Map.of()); }
    public ClusterConfiguration(Map<String, Member> members, Map<String, Port> ports, Map<String, Connection> connections) {
        this(members, ports, connections, Map.of());
    }
    public record Connection(String source, String output, String target, String input, boolean enabled) {
        public Connection { name(source); name(output); name(target); name(input); }
    }
    public ClusterConfiguration(Map<String, Member> members, Map<String, Port> ports) { this(members, ports, Map.of()); }
    public record Member(UUID identity, BlockPos position) {
        public Member { Objects.requireNonNull(identity); position = Objects.requireNonNull(position).immutable(); }
    }
    public record Port(String member, String input, String output, String external, boolean enabled) {
        public Port { name(member); name(input); name(output); name(external); }
    }
    public ClusterConfiguration {
        members = namedCopy(members); ports = namedCopy(ports); connections = namedCopy(connections); wires = namedCopy(wires);
        signals = namedCopy(signals);
        publicPins = namedCopy(publicPins);
        if (!Collections.disjoint(ports.keySet(), publicPins.keySet())) throw new IllegalArgumentException("Public endpoint names overlap between protocols");
        for (var pin : publicPins.values())
            if (!members.containsKey(pin.target().member())) throw new IllegalArgumentException("Public pin references an absent member");
        if (!Collections.disjoint(wires.keySet(), signals.keySet())) throw new IllegalArgumentException("Wire names overlap between interface types");
        for (var wire : signals.values())
            if (!members.containsKey(wire.left().member()) || !members.containsKey(wire.right().member()))
                throw new IllegalArgumentException("Signal wire references an absent member");
        for (var wire : wires.values())
            if (!members.containsKey(wire.left().member()) || !members.containsKey(wire.right().member()))
                throw new IllegalArgumentException("Wire references an absent member");
        var identities = new HashSet<UUID>(); var positions = new HashSet<BlockPos>(); var outputs = new HashSet<String>();
        for (var member : members.values()) {
            if (!identities.add(member.identity()) || !positions.add(member.position()))
                throw new IllegalArgumentException("Duplicate cluster member identity or position");
        }
        for (var port : ports.values()) {
            if (!members.containsKey(port.member())) throw new IllegalArgumentException("Port references an absent member");
            if (!outputs.add(port.output())) throw new IllegalArgumentException("Duplicate internal output port");
        }
        var sourceOutputs = new HashSet<List<String>>();
        ports.values().forEach(port -> sourceOutputs.add(List.of(port.member(), port.output())));
        for (var connection : connections.values()) {
            if (!members.containsKey(connection.source()) || !members.containsKey(connection.target()) || connection.source().equals(connection.target()))
                throw new IllegalArgumentException("Connection requires two distinct configured members");
            if (!sourceOutputs.add(List.of(connection.source(), connection.output())))
                throw new IllegalArgumentException("Duplicate output of the same member");
        }
    }
    /** Closed routes still reserve their output names, preventing a fallback to external cable names. */
    public Set<String> outputNames(String member) {
        var names = new HashSet<String>();
        ports.values().stream().filter(p -> p.member().equals(member)).forEach(p -> names.add(p.output()));
        connections.values().stream().filter(c -> c.source().equals(member)).forEach(c -> names.add(c.output()));
        return Set.copyOf(names);
    }
    public static ClusterConfiguration empty() { return new ClusterConfiguration(Map.of(), Map.of()); }
    public Set<String> wireNames() { var names = new HashSet<>(wires.keySet()); names.addAll(signals.keySet()); return Set.copyOf(names); }
    static void name(String name) { new EndpointConfiguration(name, true); }
    private static <T> Map<String, T> namedCopy(Map<String, T> source) {
        var copy = new LinkedHashMap<String, T>();
        source.forEach((key, value) -> { name(key); copy.put(key, Objects.requireNonNull(value)); });
        return Collections.unmodifiableMap(copy);
    }
}
