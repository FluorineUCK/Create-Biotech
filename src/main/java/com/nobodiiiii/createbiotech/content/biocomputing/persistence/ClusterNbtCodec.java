package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveData.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.RequestCorrelation;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/** One strict archive for configuration, admission receipts, forwarding queue and return paths. */
public final class ClusterNbtCodec {
    private ClusterNbtCodec() {}
    public static CompoundTag encode(ClusterState state, ArchiveLimits limits) { return ValueNbtCodec.encode(value(state), limits); }
    public static DataValue.RecordValue value(ClusterState state) {
        return obj("version", !state.configuration().publicPins().isEmpty() ? 5 : state.configuration().signals().isEmpty() ? 3 : 4, "configuration", configuration(state.configuration()), "mailbox", state.mailbox().value(),
            "forwards", sequence(state.forwards(), f -> obj("ingress", ingress(f.ingress()), "target", target(f.target()),
                "return", f.returnEndpoint().<DataValue>map(DataValue.TextValue::new).orElse(DataValue.NullValue.INSTANCE))),
            "pending", sequence(state.pending().entrySet(), e -> obj("correlation", e.getKey().value(), "expected", ingress(e.getValue().expected()),
                "target", target(e.getValue().target()), "responder", e.getValue().responder().<DataValue>map(id -> new DataValue.TextValue(id.toString())).orElse(DataValue.NullValue.INSTANCE))),
            "receipt_ports", sequence(state.receiptPorts().entrySet(), e -> obj("source", e.getKey().toString(), "ingress", ingress(e.getValue()))));
    }
    public static ClusterState decode(CompoundTag tag, ArchiveLimits limits) {
        try {
            var data = ValueNbtCodec.decode(tag, limits); exact(data, "version", "configuration", "mailbox", "forwards", "pending", "receipt_ports");
            long schema = number(at(data, "version"));
            if (schema < 1 || schema > 5) throw new ArchiveException(ArchiveException.Code.UNSUPPORTED_VERSION, "Unsupported cluster version");
            var config = readConfiguration(at(data, "configuration"), schema); var mailbox = MailboxArchiveCodec.decode(at(data, "mailbox"));
            var forwards = new ArrayList<ClusterState.Forward>();
            for (var value : list(at(data, "forwards"))) {
                exact(value, "ingress", "target", "return");
                forwards.add(new ClusterState.Forward(readIngress(at(value, "ingress")), readTarget(at(value, "target")), optional(at(value, "return")).map(ArchiveData::string)));
            }
            var pending = new LinkedHashMap<RequestCorrelation, ClusterState.ReturnPath>();
            for (var value : list(at(data, "pending"))) {
                exact(value, "correlation", "expected", "target", "responder");
                var correlation = RpcArchiveCodec.readCorrelation(at(value, "correlation"));
                var path = new ClusterState.ReturnPath(readIngress(at(value, "expected")), readTarget(at(value, "target")), optional(at(value, "responder")).map(ClusterNbtCodec::uuid));
                if (pending.putIfAbsent(correlation, path) != null) throw ArchiveException.invalid("Duplicate pending correlation");
            }
            var receipts = new LinkedHashMap<UUID, ClusterState.Ingress>();
            for (var value : list(at(data, "receipt_ports"))) {
                exact(value, "source", "ingress");
                if (receipts.putIfAbsent(uuid(at(value, "source")), readIngress(at(value, "ingress"))) != null)
                    throw ArchiveException.invalid("Duplicate receipt port source");
            }
            return new ClusterState(config, mailbox, forwards, pending, receipts);
        } catch (ComputationException | IllegalArgumentException failure) {
            if (failure instanceof ArchiveException archive) throw archive;
            throw ArchiveException.invalid("Invalid cluster archive: " + failure.getMessage());
        }
    }
    private static DataValue configuration(ClusterConfiguration config) {
        var legacy = obj("members", named(config.members(), ClusterNbtCodec::member), "ports", named(config.ports(), p ->
            obj("member", p.member(), "input", p.input(), "output", p.output(), "external", p.external(), "enabled", p.enabled())),
            "connections", named(config.connections(), c -> obj("source", c.source(), "output", c.output(), "target", c.target(), "input", c.input(), "enabled", c.enabled())),
            "wires", XBusWireCodec.value(config.wires()));
        if (config.signals().isEmpty() && config.publicPins().isEmpty()) return legacy;
        var fields = new LinkedHashMap<>(legacy.fields()); fields.put("signals", SimpleIoWireCodec.value(config.signals()));
        if (!config.publicPins().isEmpty()) fields.put("public_pins", XBusPublicPinCodec.value(config.publicPins()));
        return new DataValue.RecordValue(fields);
    }
    private static ClusterConfiguration readConfiguration(DataValue value, long schema) {
        if (schema == 1) exact(value, "members", "ports");
        else if (schema == 2) exact(value, "members", "ports", "connections");
        else if (schema == 3) exact(value, "members", "ports", "connections", "wires");
        else if (schema == 4) exact(value, "members", "ports", "connections", "wires", "signals");
        else exact(value, "members", "ports", "connections", "wires", "signals", "public_pins");
        Map<String, ClusterConfiguration.Connection> connections = schema == 1 ? Map.of() : readNamed(at(value, "connections"), c -> {
            exact(c, "source", "output", "target", "input", "enabled");
            return new ClusterConfiguration.Connection(string(at(c, "source")), string(at(c, "output")), string(at(c, "target")), string(at(c, "input")), bool(at(c, "enabled")));
        });
        return new ClusterConfiguration(readNamed(at(value, "members"), ClusterNbtCodec::readMember), readNamed(at(value, "ports"), p -> {
            exact(p, "member", "input", "output", "external", "enabled");
            return new ClusterConfiguration.Port(string(at(p, "member")), string(at(p, "input")), string(at(p, "output")), string(at(p, "external")), bool(at(p, "enabled")));
        }), connections, schema < 3 ? Map.of() : XBusWireCodec.read(at(value, "wires")), schema < 4 ? Map.of() : SimpleIoWireCodec.read(at(value, "signals")),
            schema < 5 ? Map.of() : XBusPublicPinCodec.read(at(value, "public_pins")));
    }
    private static DataValue member(ClusterConfiguration.Member member) {
        return obj("identity", member.identity().toString(), "x", member.position().getX(), "y", member.position().getY(), "z", member.position().getZ());
    }
    private static ClusterConfiguration.Member readMember(DataValue value) {
        exact(value, "identity", "x", "y", "z");
        return new ClusterConfiguration.Member(uuid(at(value, "identity")), new BlockPos(coordinate(at(value, "x")), coordinate(at(value, "y")), coordinate(at(value, "z"))));
    }
    private static DataValue ingress(ClusterState.Ingress ingress) { return obj("side", ingress.side().name(), "port", ingress.port()); }
    private static ClusterState.Ingress readIngress(DataValue value) {
        exact(value, "side", "port"); return new ClusterState.Ingress(enumeration(ClusterState.Side.class, at(value, "side")), string(at(value, "port")));
    }
    private static DataValue target(ClusterState.Target target) {
        return obj("side", target.side().name(), "port", target.port(), "member", target.member().map(ClusterNbtCodec::member).orElse(DataValue.NullValue.INSTANCE));
    }
    private static ClusterState.Target readTarget(DataValue value) {
        exact(value, "side", "port", "member");
        return new ClusterState.Target(enumeration(ClusterState.Side.class, at(value, "side")), string(at(value, "port")), optional(at(value, "member")).map(ClusterNbtCodec::readMember));
    }
    private static Optional<DataValue> optional(DataValue value) { return value == DataValue.NullValue.INSTANCE ? Optional.empty() : Optional.of(value); }
    private static UUID uuid(DataValue value) { return UUID.fromString(string(value)); }
    private static int coordinate(DataValue value) {
        long n = number(value); if (n < Integer.MIN_VALUE || n > Integer.MAX_VALUE) throw ArchiveException.invalid("Invalid block coordinate"); return (int)n;
    }
    private static void exact(DataValue value, String... keys) {
        if (!fields(value).keySet().equals(Set.of(keys))) throw ArchiveException.invalid("Unexpected cluster fields");
    }
}
