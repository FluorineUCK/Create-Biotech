package com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu;

import com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHardwareViewCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.BoundedEditorEnvelope.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.XBusWireCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.SimpleIoWireCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.XBusPublicPinCodec;
import java.util.*;
import net.minecraft.core.BlockPos;

/** Bounded value-codec envelopes for configuration and public controller state. */
public final class ClusterControlWire {
    private ClusterControlWire() {}
    public static byte[] encodeComputerPosition(BlockPos target, ArchiveLimits limits) {
        return ProgramEditorWire.encodeValue(position(Objects.requireNonNull(target)), Objects.requireNonNull(limits));
    }
    public static BlockPos decodeComputerPosition(byte[] bytes, ArchiveLimits limits) {
        return position(ProgramEditorWire.decodeValue(bytes, Objects.requireNonNull(limits)));
    }
    public static byte[] encodeConfiguration(ClusterConfiguration configuration, ArchiveLimits limits) {
        return CLUSTER_CONFIGURATION.encode(configurationValue(Objects.requireNonNull(configuration)), Objects.requireNonNull(limits));
    }
    public static ClusterConfiguration decodeConfiguration(byte[] bytes, ArchiveLimits limits) {
        return configuration(record(CLUSTER_CONFIGURATION.decode(bytes, Objects.requireNonNull(limits)), "configuration"));
    }
    public static byte[] encodeView(ClusterControlView view, ArchiveLimits limits) {
        Objects.requireNonNull(view);
        var candidates = new ArrayList<DataValue>();
        for (var candidate : view.candidates()) {
            var endpoints = candidate.endpoints().stream().<DataValue>map(DataValue.TextValue::new).toList();
            candidates.add(new DataValue.RecordValue(Map.of(
                "position", position(candidate.position()),
                "identity", candidate.identity().<DataValue>map(id -> new DataValue.TextValue(id.toString())).orElse(DataValue.NullValue.INSTANCE),
                "endpoints", new DataValue.ListValue(endpoints),
                "member", new DataValue.BooleanValue(candidate.member()),
                "selectable", new DataValue.BooleanValue(candidate.selectable()),
                "editable", new DataValue.BooleanValue(candidate.editable()),
                "diagnostic", new DataValue.TextValue(candidate.diagnostic()),
                "hardware", candidate.hardware().map(ComputerHardwareViewCodec::value).orElse(DataValue.NullValue.INSTANCE))));
        }
        var diagnostics = new LinkedHashMap<String, DataValue>();
        view.wireDiagnostics().forEach((name, code) -> diagnostics.put(name, new DataValue.TextValue(code)));
        var pinDiagnostics = new LinkedHashMap<String, DataValue>();
        view.publicPinDiagnostics().forEach((name, code) -> pinDiagnostics.put(name, new DataValue.TextValue(code)));
        return CLUSTER_VIEW.encode(new DataValue.RecordValue(Map.of(
            "configuration", configurationValue(view.configuration()),
            "candidates", new DataValue.ListValue(candidates),
            "diagnostic", new DataValue.TextValue(view.diagnostic()),
            "queued", new DataValue.IntegerValue(view.queued()),
            "pending", new DataValue.IntegerValue(view.pending()), "wires", new DataValue.RecordValue(diagnostics),
            "public_pins", new DataValue.RecordValue(pinDiagnostics))), Objects.requireNonNull(limits));
    }
    public static ClusterControlView decodeView(byte[] bytes, ArchiveLimits limits) {
        var root = exact(record(CLUSTER_VIEW.decode(bytes, Objects.requireNonNull(limits)), "view"),
            "configuration", "candidates", "diagnostic", "queued", "pending", "wires", "public_pins");
        var candidates = new ArrayList<ClusterControlView.Candidate>();
        for (var value : list(root.field("candidates"), "candidates").values()) {
            var candidate = exact(record(value, "candidate"), "position", "identity", "endpoints", "member", "selectable", "editable", "diagnostic", "hardware");
            Optional<UUID> identity = switch (candidate.field("identity")) {
                case DataValue.NullValue ignored -> Optional.empty();
                case DataValue.TextValue text -> Optional.of(uuid(text.value(), "candidate identity"));
                default -> throw invalid("candidate identity");
            };
            var endpoints = new ArrayList<String>();
            for (var endpoint : list(candidate.field("endpoints"), "candidate endpoints").values()) {
                String name = text(endpoint, "candidate endpoint"); new EndpointConfiguration(name, true); endpoints.add(name);
            }
            candidates.add(new ClusterControlView.Candidate(position(candidate.field("position")), identity, endpoints,
                bool(candidate.field("member"), "geometric member"),
                bool(candidate.field("selectable"), "candidate selectable"), bool(candidate.field("editable"), "candidate editable"),
                text(candidate.field("diagnostic"), "candidate diagnostic"),
                candidate.field("hardware") instanceof DataValue.NullValue ? Optional.empty() : Optional.of(ComputerHardwareViewCodec.read(candidate.field("hardware")))));
        }
        int queued = nonnegativeInt(root.field("queued"), "queued"), pending = nonnegativeInt(root.field("pending"), "pending");
        var diagnostics = new LinkedHashMap<String, String>();
        record(root.field("wires"), "wire diagnostics").fields().forEach((name, code) -> diagnostics.put(name, text(code, "wire diagnostic")));
        var pinDiagnostics = new LinkedHashMap<String, String>();
        record(root.field("public_pins"), "public pin diagnostics").fields().forEach((name, code) -> pinDiagnostics.put(name, text(code, "public pin diagnostic")));
        return new ClusterControlView(configuration(record(root.field("configuration"), "configuration")), candidates,
            text(root.field("diagnostic"), "diagnostic"), queued, pending, diagnostics, pinDiagnostics);
    }

    private static DataValue.RecordValue configurationValue(ClusterConfiguration configuration) {
        var members = new LinkedHashMap<String, DataValue>();
        configuration.members().forEach((name, member) -> members.put(name, new DataValue.RecordValue(Map.of(
            "identity", new DataValue.TextValue(member.identity().toString()), "position", position(member.position())))));
        var ports = new LinkedHashMap<String, DataValue>();
        configuration.ports().forEach((name, port) -> ports.put(name, new DataValue.RecordValue(Map.of(
            "member", new DataValue.TextValue(port.member()), "input", new DataValue.TextValue(port.input()),
            "output", new DataValue.TextValue(port.output()), "external", new DataValue.TextValue(port.external()),
            "enabled", new DataValue.BooleanValue(port.enabled())))));
        var connections = new LinkedHashMap<String, DataValue>();
        configuration.connections().forEach((name, connection) -> connections.put(name, new DataValue.RecordValue(Map.of(
            "source", new DataValue.TextValue(connection.source()), "output", new DataValue.TextValue(connection.output()),
            "target", new DataValue.TextValue(connection.target()), "input", new DataValue.TextValue(connection.input()),
            "enabled", new DataValue.BooleanValue(connection.enabled())))));
        return new DataValue.RecordValue(Map.of("members", new DataValue.RecordValue(members), "ports", new DataValue.RecordValue(ports),
            "connections", new DataValue.RecordValue(connections), "wires", XBusWireCodec.value(configuration.wires()), "signals", SimpleIoWireCodec.value(configuration.signals()),
            "public_pins", XBusPublicPinCodec.value(configuration.publicPins())));
    }

    private static ClusterConfiguration configuration(DataValue.RecordValue encoded) {
        encoded = exact(encoded, "members", "ports", "connections", "wires", "signals", "public_pins");
        var members = new LinkedHashMap<String, ClusterConfiguration.Member>();
        record(encoded.field("members"), "members").fields().forEach((name, value) -> {
            var member = exact(record(value, "member"), "identity", "position");
            members.put(name, new ClusterConfiguration.Member(uuid(text(member.field("identity"), "member identity"), "member identity"),
                position(member.field("position"))));
        });
        var ports = new LinkedHashMap<String, ClusterConfiguration.Port>();
        record(encoded.field("ports"), "ports").fields().forEach((name, value) -> {
            var port = exact(record(value, "port"), "member", "input", "output", "external", "enabled");
            ports.put(name, new ClusterConfiguration.Port(text(port.field("member"), "port member"),
                text(port.field("input"), "port input"), text(port.field("output"), "port output"),
                text(port.field("external"), "port external"), bool(port.field("enabled"), "port enabled")));
        });
        var connections = new LinkedHashMap<String, ClusterConfiguration.Connection>();
        record(encoded.field("connections"), "connections").fields().forEach((name, value) -> {
            var connection = exact(record(value, "connection"), "source", "output", "target", "input", "enabled");
            connections.put(name, new ClusterConfiguration.Connection(text(connection.field("source"), "connection source"),
                text(connection.field("output"), "connection output"), text(connection.field("target"), "connection target"),
                text(connection.field("input"), "connection input"), bool(connection.field("enabled"), "connection enabled")));
        });
        return new ClusterConfiguration(members, ports, connections, XBusWireCodec.read(encoded.field("wires")), SimpleIoWireCodec.read(encoded.field("signals")),
            XBusPublicPinCodec.read(encoded.field("public_pins")));
    }

    private static DataValue.RecordValue position(BlockPos position) {
        return new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(position.getX()),
            "y", new DataValue.IntegerValue(position.getY()), "z", new DataValue.IntegerValue(position.getZ())));
    }
    private static BlockPos position(DataValue value) {
        var position = exact(record(value, "position"), "x", "y", "z");
        return new BlockPos(integer(position.field("x"), "position x"), integer(position.field("y"), "position y"),
            integer(position.field("z"), "position z"));
    }
    private static DataValue.RecordValue exact(DataValue.RecordValue value, String... fields) {
        if (!value.fields().keySet().equals(Set.of(fields))) throw invalid("unexpected cluster control fields");
        return value;
    }
    private static DataValue.RecordValue record(DataValue value, String label) {
        if (value instanceof DataValue.RecordValue record) return record; throw invalid(label);
    }
    private static DataValue.ListValue list(DataValue value, String label) {
        if (value instanceof DataValue.ListValue list) return list; throw invalid(label);
    }
    private static String text(DataValue value, String label) {
        if (value instanceof DataValue.TextValue text) return text.value(); throw invalid(label);
    }
    private static boolean bool(DataValue value, String label) {
        if (value instanceof DataValue.BooleanValue flag) return flag.value(); throw invalid(label);
    }
    private static int integer(DataValue value, String label) {
        if (!(value instanceof DataValue.IntegerValue integer)) throw invalid(label);
        try { return Math.toIntExact(integer.value()); } catch (ArithmeticException overflow) { throw invalid(label); }
    }
    private static int nonnegativeInt(DataValue value, String label) {
        int result = integer(value, label); if (result < 0) throw invalid(label); return result;
    }
    private static UUID uuid(String value, String label) {
        try { return UUID.fromString(value); } catch (IllegalArgumentException malformed) { throw invalid(label); }
    }
    private static IllegalArgumentException invalid(String label) { return new IllegalArgumentException("invalid " + label); }
}
