package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationNetworkLimits;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** One level's current physical components. Explicit faces are terminals, never implicit device bridges. */
public final class SerialNetwork {
    public enum Phase { TRANSMITTING, TRANSMITTED, COLLISION, INTERRUPTED }
    /** Post-tick cosmetic snapshot only. No payload, hardware owner, identity or delivery promise escapes. */
    public record Activity(List<BlockPos> cables, List<CableDiscovery.Connection> terminals, Phase phase) {
        public Activity {
            cables = cables.stream().map(BlockPos::immutable).toList(); terminals = List.copyOf(terminals);
            Objects.requireNonNull(phase);
        }
    }
    private record Route(CableDiscovery.Segment segment, List<SerialDevice.Attachment> ports,
                         List<CableDiscovery.Connection> terminals, SerialWireMedium medium) {
        Activity activity(Phase phase) { return new Activity(phase == Phase.INTERRUPTED ? List.of() : segment.cables(), terminals, phase); }
    }
    private record Topology(Route route, Map<SerialDevice.Attachment, SerialDevice.PhysicalSession> sessions) {
        List<SerialDevice.Attachment> ports() { return route.ports(); }
        Activity activity(Phase phase) { return route.activity(phase); }
    }
    private record Live(Topology topology, SerialChannel channel) {}
    private Map<Topology, Live> active = new LinkedHashMap<>();

    public List<Activity> tick(CableDiscovery.World world, Map<BlockPos, SerialDevice> devices,
                     CommunicationNetworkLimits discovery, SharedSerialChannel.Limits limits) {
        return tick(world, devices, discovery, limits, (pos, port) -> {
            if (port.gateway().isPresent()) throw new CableDiscovery.DiscoveryException("gateway_unavailable");
            return new CableDiscovery.Connection(pos, port.side());
        });
    }
    public List<Activity> tick(CableDiscovery.World world, Map<BlockPos, SerialDevice> devices,
                     CommunicationNetworkLimits discovery, SharedSerialChannel.Limits limits,
                     BiFunction<BlockPos, SerialDevice.Attachment, CableDiscovery.Connection> resolver) {
        var legacy = SerialWireMedium.shared(limits);
        return tickWired(world, devices, discovery, limits, ignored -> legacy, resolver);
    }

    /**
     * Read the medium of each physical cable cell reached by bounded discovery. Direct adjacency
     * retains the old shared behavior. This API declares no new cable blocks or world authority.
     */
    public List<Activity> tickWired(CableDiscovery.World world, Map<BlockPos, SerialDevice> devices,
                   CommunicationNetworkLimits discovery, SharedSerialChannel.Limits adjacent,
                   Function<BlockPos, SerialWireMedium> cableMedium,
                   BiFunction<BlockPos, SerialDevice.Attachment, CableDiscovery.Connection> resolver) {
        var feedback = new ArrayList<Activity>();
        var attachments = new LinkedHashMap<CableDiscovery.Connection, List<SerialDevice.Attachment>>();
        var faults = new HashMap<SerialDevice.Attachment, String>();
        var all = new LinkedHashMap<SerialDevice.Attachment, CableDiscovery.Connection>();
        devices.forEach((pos, device) -> device.attachments().forEach(port -> {
            var physical = port.gateway().map(g -> new CableDiscovery.Connection(g.position(), g.side()))
                .orElseGet(() -> new CableDiscovery.Connection(pos, port.side()));
            all.put(port, physical);
            try {
                var resolved = Objects.requireNonNull(resolver.apply(pos, port));
                all.put(port, resolved); attachments.computeIfAbsent(resolved, ignored -> new ArrayList<>()).add(port);
            } catch (CableDiscovery.DiscoveryException unavailable) { faults.put(port, unavailable.code()); }
        }));
        var identities = new HashMap<UUID, Set<SerialDevice>>();
        all.keySet().forEach(a -> identities.computeIfAbsent(a.identity(), ignored -> new HashSet<>()).add(a.device()));
        var duplicate = new HashSet<SerialDevice>();
        identities.values().stream().filter(owners -> owners.size() > 1).forEach(duplicate::addAll);
        var next = new LinkedHashMap<Topology, Live>();
        var seen = new HashSet<CableDiscovery.Segment>();
        var connected = new HashSet<SerialDevice.Attachment>();
        for (var start : attachments.keySet().stream().map(CableDiscovery.Connection::position).distinct().toList()) {
            try {
                for (var segment : CableDiscovery.segments(world, start, discovery.maxVisited(), discovery.maxEndpoints())) {
                    if (!seen.add(segment)) continue;
                    var ports = new ArrayList<SerialDevice.Attachment>();
                    var terminals = new LinkedHashSet<CableDiscovery.Connection>();
                    for (var end : segment.endpoints()) for (var port : attachments.getOrDefault(end, List.of()))
                        { ports.add(port); terminals.add(end); }
                    if (ports.isEmpty()) continue;
                    if (ports.stream().anyMatch(p -> duplicate.contains(p.device()))) {
                        ports.forEach(p -> faults.put(p, "duplicate_identity")); continue;
                    }
                    var medium = selectMedium(segment, adjacent, cableMedium);
                    String rejection = rejection(segment, ports, terminals, medium);
                    if (rejection != null) {
                        ports.forEach(port -> faults.put(port, rejection));
                        continue;
                    }
                    var route = new Route(segment, List.copyOf(ports), List.copyOf(terminals), medium);
                    var sessions = new HashMap<SerialDevice.Attachment, SerialDevice.PhysicalSession>();
                    for (var port : ports) sessions.put(port, port.device().physicalSession(port));
                    var topology = new Topology(route, Map.copyOf(sessions));
                    var live = active.get(topology);
                    if (live == null) live = new Live(topology, open(route));
                    next.put(topology, live); connected.addAll(ports);
                }
            } catch (CableDiscovery.DiscoveryException failure) {
                attachments.forEach((end, ports) -> { if (end.position().equals(start)) ports.forEach(p -> faults.put(p, failure.code())); });
            }
        }
        // A scan failure on any owner rejects the whole observed component, never a partial winner.
        next.entrySet().removeIf(e -> e.getKey().ports().stream().anyMatch(faults::containsKey));
        connected.clear();
        var nextRoutes = new HashMap<Route, Topology>();
        next.keySet().forEach(t -> { connected.addAll(t.ports()); nextRoutes.put(t.route(), t); });
        for (var old : active.entrySet()) if (!next.containsKey(old.getKey())) {
            var ended = old.getKey();
            if (old.getValue().channel().transmitting()) feedback.add(ended.activity(Phase.INTERRUPTED));
            var successor = nextRoutes.get(ended.route());
            for (var port : ended.ports()) {
                // A local close/reopen already ended the old local send. If the route is unchanged,
                // do not discard a new send the program submitted on the new interface session.
                if (successor == null || ended.sessions().get(port).equals(successor.sessions().get(port)))
                    port.device().disconnect(port.name(), "topology_changed");
            }
        }
        all.forEach((a, end) -> {
            if (!connected.contains(a)) {
                String tx = a.device().status(a.name()).transmit();
                a.device().disconnect(a.name(), faults.getOrDefault(a, "disconnected"));
                if (tx.equals("queued") || tx.equals("transmitting")) feedback.add(new Activity(List.of(),
                    List.of(end), Phase.INTERRUPTED));
            }
        });
        active = next;
        // All senders are admitted before one medium interval, independent of enumeration order.
        for (var live : active.values()) for (var port : live.topology().ports()) {
            port.device().connect(port.name()); port.device().start(port, live.channel());
        }
        for (var live : active.values()) {
            var before = new HashMap<SerialDevice.Attachment, SerialReceiveBuffer.Status>();
            live.topology().ports().forEach(p -> before.put(p, p.receiver().status()));
            boolean transmitting = live.channel().transmitting(), collided = live.channel().collided();
            var completed = live.channel().advance();
            for (var done : completed) live.topology().ports().stream().filter(p -> p.key().equals(done.source())).findFirst()
                .ifPresent(p -> p.device().complete(p.name(), done.outcome()));
            live.topology().ports().forEach(p -> { if (!p.receiver().status().equals(before.get(p))) p.device().received(); });
            if (transmitting) feedback.add(live.topology().activity(collided ? Phase.COLLISION
                : completed.isEmpty() ? Phase.TRANSMITTING : Phase.TRANSMITTED));
        }
        return List.copyOf(feedback);
    }

    private static SerialWireMedium selectMedium(CableDiscovery.Segment segment, SharedSerialChannel.Limits adjacent,
                                                 Function<BlockPos, SerialWireMedium> cableMedium) {
        if (segment.cables().isEmpty()) return SerialWireMedium.shared(adjacent);
        var first = cableMedium.apply(segment.cables().getFirst());
        if (first == null) return null;
        for (int i = 1; i < segment.cables().size(); i++)
            if (!first.equals(cableMedium.apply(segment.cables().get(i)))) return null;
        return first;
    }

    private static String rejection(CableDiscovery.Segment segment, List<SerialDevice.Attachment> ports,
                                    Set<CableDiscovery.Connection> terminals, SerialWireMedium medium) {
        if (medium == null) return "incompatible_media";
        if (ports.size() > medium.limits().maxPorts()) return "port_limit";
        if (medium.mode() == SerialWireMedium.Mode.SHARED) return null;
        if (ports.size() != 2 || terminals.size() != 2 || !pointToPoint(segment)) return "duplex_topology";
        if (ports.stream().anyMatch(port -> port.hardware().duplex().isEmpty())) return "duplex_hardware";
        return null;
    }

    /** A connected open path has exactly two terminal edges and degree two at each cable. */
    private static boolean pointToPoint(CableDiscovery.Segment segment) {
        if (segment.endpoints().size() != 2) return false;
        var cells = new HashSet<>(segment.cables());
        var ends = new HashMap<BlockPos, Integer>();
        for (var terminal : segment.endpoints()) {
            var next = terminal.position().relative(terminal.side());
            if (!cells.contains(next)) return false;
            ends.merge(next, 1, Integer::sum);
        }
        for (var cell : cells) {
            int degree = ends.getOrDefault(cell, 0);
            for (var side : Direction.values()) if (cells.contains(cell.relative(side))) degree++;
            if (degree != 2) return false;
        }
        return true;
    }

    private static SerialChannel open(Route route) {
        var medium = route.medium();
        if (medium.mode() == SerialWireMedium.Mode.FULL_DUPLEX)
            return new FullDuplexSerialChannel(medium.limits(), route.ports().get(0), route.ports().get(1));
        var hardware = new LinkedHashMap<SharedSerialChannel.Port, SharedSerialChannel.Hardware>();
        var receivers = new LinkedHashMap<SharedSerialChannel.Port, SerialReceiveBuffer>();
        for (var port : route.ports()) {
            hardware.put(port.key(), port.hardware().transmitter());
            receivers.put(port.key(), port.receiver());
        }
        return new SharedSerialChannel(medium.limits(), hardware, receivers);
    }
    public void unload() {
        var old = active; active = Map.of();
        old.keySet().forEach(t -> t.ports().forEach(a -> a.device().unload()));
    }
}
