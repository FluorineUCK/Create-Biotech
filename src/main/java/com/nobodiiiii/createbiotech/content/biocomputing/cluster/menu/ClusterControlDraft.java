package com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu;

import com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusWire;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.SimpleIoWire;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.XBusPublicPin;
import java.util.*;

/** Client-local atomic draft; world authority remains in {@link ClusterControllerMenu}. */
public final class ClusterControlDraft {
    private ClusterConfiguration configuration;
    public ClusterControlDraft(ClusterConfiguration configuration) { this.configuration = Objects.requireNonNull(configuration); }
    public ClusterConfiguration configuration() { return configuration; }
    public void addMember(String name, ClusterControlView.Candidate candidate) {
        Objects.requireNonNull(candidate);
        if (!candidate.selectable() || candidate.identity().isEmpty()) throw new IllegalArgumentException("candidate is not selectable");
        var members = new LinkedHashMap<>(configuration.members());
        if (members.putIfAbsent(name, new ClusterConfiguration.Member(candidate.identity().orElseThrow(), candidate.position())) != null)
            throw new IllegalArgumentException("member name already exists");
        configuration = new ClusterConfiguration(members, configuration.ports(), configuration.connections(), configuration.wires(), configuration.signals(), configuration.publicPins());
    }
    public void renameMember(String oldName, String newName) {
        var existing = configuration.members().get(oldName);
        if (existing == null) throw new IllegalArgumentException("member does not exist");
        if (!oldName.equals(newName) && configuration.members().containsKey(newName)) throw new IllegalArgumentException("member name already exists");
        var members = new LinkedHashMap<String, ClusterConfiguration.Member>();
        configuration.members().forEach((name, member) -> members.put(name.equals(oldName) ? newName : name, member));
        var ports = new LinkedHashMap<String, ClusterConfiguration.Port>();
        configuration.ports().forEach((name, port) -> ports.put(name, port.member().equals(oldName)
            ? new ClusterConfiguration.Port(newName, port.input(), port.output(), port.external(), port.enabled()) : port));
        var connections = new LinkedHashMap<String, ClusterConfiguration.Connection>();
        configuration.connections().forEach((name, connection) -> connections.put(name, new ClusterConfiguration.Connection(
            connection.source().equals(oldName) ? newName : connection.source(), connection.output(),
            connection.target().equals(oldName) ? newName : connection.target(), connection.input(), connection.enabled())));
        var wires = new LinkedHashMap<String, XBusWire>();
        configuration.wires().forEach((name, wire) -> wires.put(name, wire.rename(oldName, newName)));
        var signals = new LinkedHashMap<String, SimpleIoWire>();
        configuration.signals().forEach((name, wire) -> signals.put(name, wire.rename(oldName, newName)));
        var pins = new LinkedHashMap<String, XBusPublicPin>();
        configuration.publicPins().forEach((name, pin) -> pins.put(name, pin.renameMember(oldName, newName)));
        configuration = new ClusterConfiguration(members, ports, connections, wires, signals, pins);
    }
    public void removeMember(String name) {
        if (!configuration.members().containsKey(name)) return;
        var members = new LinkedHashMap<>(configuration.members()); members.remove(name);
        var ports = new LinkedHashMap<>(configuration.ports()); ports.entrySet().removeIf(entry -> entry.getValue().member().equals(name));
        var connections = new LinkedHashMap<>(configuration.connections());
        connections.entrySet().removeIf(entry -> entry.getValue().source().equals(name) || entry.getValue().target().equals(name));
        var wires = new LinkedHashMap<>(configuration.wires()); wires.entrySet().removeIf(entry -> entry.getValue().touches(name));
        var signals = new LinkedHashMap<>(configuration.signals()); signals.entrySet().removeIf(entry -> entry.getValue().touches(name));
        var pins = new LinkedHashMap<>(configuration.publicPins()); pins.entrySet().removeIf(entry -> entry.getValue().target().member().equals(name));
        configuration = new ClusterConfiguration(members, ports, connections, wires, signals, pins);
    }
    public void putPort(String oldName, String newName, String member, String input, String output, String external, boolean enabled) {
        if (oldName != null && !configuration.ports().containsKey(oldName)) throw new IllegalArgumentException("port does not exist");
        if (!Objects.equals(oldName, newName) && configuration.ports().containsKey(newName)) throw new IllegalArgumentException("port name already exists");
        var ports = new LinkedHashMap<>(configuration.ports()); if (oldName != null) ports.remove(oldName);
        ports.put(newName, new ClusterConfiguration.Port(member, input, output, external, enabled));
        configuration = new ClusterConfiguration(configuration.members(), ports, configuration.connections(), configuration.wires(), configuration.signals(), configuration.publicPins());
    }
    public void removePort(String name) {
        if (!configuration.ports().containsKey(name)) return;
        var ports = new LinkedHashMap<>(configuration.ports()); ports.remove(name);
        configuration = new ClusterConfiguration(configuration.members(), ports, configuration.connections(), configuration.wires(), configuration.signals(), configuration.publicPins());
    }
    public void putConnection(String oldName, String newName, String source, String output, String target, String input, boolean enabled) {
        if (oldName != null && !configuration.connections().containsKey(oldName)) throw new IllegalArgumentException("connection does not exist");
        if (!Objects.equals(oldName, newName) && configuration.connections().containsKey(newName)) throw new IllegalArgumentException("connection name already exists");
        var connections = new LinkedHashMap<>(configuration.connections());
        if (oldName != null) connections.remove(oldName);
        connections.put(newName, new ClusterConfiguration.Connection(source, output, target, input, enabled));
        configuration = new ClusterConfiguration(configuration.members(), configuration.ports(), connections, configuration.wires(), configuration.signals(), configuration.publicPins());
    }
    public void removeConnection(String name) {
        if (!configuration.connections().containsKey(name)) return;
        var connections = new LinkedHashMap<>(configuration.connections()); connections.remove(name);
        configuration = new ClusterConfiguration(configuration.members(), configuration.ports(), connections, configuration.wires(), configuration.signals(), configuration.publicPins());
    }
    public void putWire(String oldName, String newName, XBusWire wire) {
        if (oldName != null && !configuration.wires().containsKey(oldName)) throw new IllegalArgumentException("wire does not exist");
        if (!Objects.equals(oldName, newName) && configuration.wires().containsKey(newName)) throw new IllegalArgumentException("wire name already exists");
        var wires = new LinkedHashMap<>(configuration.wires());
        if (oldName != null) wires.remove(oldName);
        wires.put(newName, Objects.requireNonNull(wire));
        configuration = new ClusterConfiguration(configuration.members(), configuration.ports(), configuration.connections(), wires, configuration.signals(), configuration.publicPins());
    }
    public void removeWire(String name) {
        var wires = new LinkedHashMap<>(configuration.wires()); wires.remove(name);
        configuration = new ClusterConfiguration(configuration.members(), configuration.ports(), configuration.connections(), wires, configuration.signals(), configuration.publicPins());
    }

    public void putPublicPin(String oldName, String newName, XBusPublicPin pin) {
        if (oldName != null && !configuration.publicPins().containsKey(oldName)) throw new IllegalArgumentException("public pin does not exist");
        if (!Objects.equals(oldName, newName) && configuration.publicPins().containsKey(newName)) throw new IllegalArgumentException("public pin name already exists");
        var pins = new LinkedHashMap<>(configuration.publicPins()); if (oldName != null) pins.remove(oldName);
        pins.put(newName, Objects.requireNonNull(pin));
        configuration = new ClusterConfiguration(configuration.members(), configuration.ports(), configuration.connections(), configuration.wires(), configuration.signals(), pins);
    }
    public void removePublicPin(String name) {
        var pins = new LinkedHashMap<>(configuration.publicPins()); pins.remove(name);
        configuration = new ClusterConfiguration(configuration.members(), configuration.ports(), configuration.connections(), configuration.wires(), configuration.signals(), pins);
    }

    public void putSignal(String oldName, String newName, SimpleIoWire wire) {
        if (oldName != null && !configuration.signals().containsKey(oldName)) throw new IllegalArgumentException("signal wire does not exist");
        if (!Objects.equals(oldName, newName) && configuration.signals().containsKey(newName)) throw new IllegalArgumentException("signal wire name already exists");
        var signals = new LinkedHashMap<>(configuration.signals()); if (oldName != null) signals.remove(oldName);
        signals.put(newName, Objects.requireNonNull(wire));
        configuration = new ClusterConfiguration(configuration.members(), configuration.ports(), configuration.connections(), configuration.wires(), signals, configuration.publicPins());
    }
    public void removeSignal(String name) {
        var signals = new LinkedHashMap<>(configuration.signals()); signals.remove(name);
        configuration = new ClusterConfiguration(configuration.members(), configuration.ports(), configuration.connections(), configuration.wires(), signals, configuration.publicPins());
    }

}
