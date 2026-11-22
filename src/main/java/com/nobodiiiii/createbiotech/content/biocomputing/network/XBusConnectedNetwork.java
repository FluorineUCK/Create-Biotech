package com.nobodiiiii.createbiotech.content.biocomputing.network;

import com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusExchange;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/** One complete physical-pin component, including internal wires and every enabled external alias.
 * Capture does not run instructions, acknowledge exchanges, enroll history or choose a simulation clock. */
public final class XBusConnectedNetwork {
    private XBusConnectedNetwork() {}
    public record Port(BlockPos position, XBusPortState observed) {}
    /** Display-only physical endpoints: external aliases end at gateways, not at hidden member positions. */
    public record DisplayWire(XBusNetwork.Link pins, BlockPos left, BlockPos right, boolean cable) {}
    public record Connected(XBusNetwork topology, XBusAccess.Network network, Map<XBusNetwork.Pin, Port> ports,
            List<DisplayWire> displayWires) {
        public Connected { ports = Map.copyOf(ports); displayWires = List.copyOf(displayWires); }
    }
    public record Capture(String code, Optional<Connected> connected) {}

    /** Server coordinator entry point. No caller-provided subset, arbitrary coordinates or forced chunk loads. */
    public static Capture capture(ServerLevel level, ComputerBehaviour start, String pin) {
        try {
            var identity = start.communicationIdentity().orElseThrow(() -> new Failure("unavailable"));
            var component = new Builder(level).assemble(start, new XBusNetwork.Pin(identity, pin));
            var rejected = component.network().inspectBindings();
            if (rejected.isPresent()) throw new Failure(rejected.orElseThrow().name().toLowerCase(Locale.ROOT));
            return new Capture("ready", Optional.of(component));
        }
        catch (Failure failure) { return new Capture(failure.getMessage(), Optional.empty()); }
        catch (RuntimeException | StackOverflowError failure) { return new Capture("provider_failure", Optional.empty()); }
    }

    /** Read the sending level without exposing or retaining a grant. statusCode itself performs
     * the post-discovery physical proof and recovery checks, without acknowledging a transfer. */
    public static int readStatus(ServerLevel level, ComputerBehaviour start, XBusNetwork.Pin origin) {
        try {
            var component = new Builder(level).assemble(start, origin);
            return component.network().statusCode(origin);
        } catch (RuntimeException | StackOverflowError invalid) { return 0; }
    }

    /** Bounded route resolution; no claim, payload access, writer selection or acknowledgement. */
    public static Optional<XBusPortState> readyWriter(ServerLevel level, ComputerBehaviour start, XBusNetwork.Pin origin) {
        try { return new Builder(level).assemble(start, origin).network().readyWriter(origin); }
        catch (RuntimeException | StackOverflowError invalid) { return Optional.empty(); }
    }

    private record Computer(ComputerBehaviour behaviour, BlockPos position, List<String> pins,
            Optional<ClusterAttachment> attachment) {}
    private record Controller(ClusterControllerBlockEntity entity, UUID identity, long revision, ClusterConfiguration config) {}
    private record Wire(Controller controller, String name, XBusNetwork.Link link) {}

    private static final class Builder {
        private final ServerLevel level;
        private final XBusWiringSavedData store;
        private final int maxEndpoints;
        private final Map<UUID, Computer> computers = new HashMap<>();
        private final Map<BlockPos, Controller> controllers = new HashMap<>();
        private final Map<BlockPos, UUID> positions = new HashMap<>();
        private final Set<XBusNetwork.Pin> pins = new LinkedHashSet<>();
        private final ArrayDeque<XBusNetwork.Pin> pending = new ArrayDeque<>();
        private final Set<XBusWiringSavedData.EnabledConnection> external = new LinkedHashSet<>();
        private final Set<Wire> internal = new LinkedHashSet<>();
        private final List<XBusNetwork.Link> links = new ArrayList<>();
        private final Map<XBusNetwork.Pin, Port> observations = new HashMap<>();

        private Builder(ServerLevel level) {
            this.level = Objects.requireNonNull(level); store = XBusWiringSavedData.get(level);
            maxEndpoints = CBConfigs.SERVER.biocomputing.communication(CBConfigs.SERVER_SPEC.isLoaded()).maxEndpoints();
        }
        private Connected assemble(ComputerBehaviour start, XBusNetwork.Pin origin) {
            var first = computer(start.blockEntity.getBlockPos(), origin.computer());
            if (first.behaviour() != start) fail("stale");
            add(origin);
            while (!pending.isEmpty()) discover(pending.removeFirst());
            if (links.isEmpty()) fail("not_found");
            XBusWiringSavedData.ComponentView closure;
            try { closure = store.componentView(pins, List.copyOf(external)); }
            catch (IllegalArgumentException invalid) { throw new Failure(invalid.getMessage()); }
            var canonical = pins.stream().sorted(Comparator
                .comparingInt((XBusNetwork.Pin p) -> computers.get(p.computer()).position().getY())
                .thenComparingInt(p -> computers.get(p.computer()).position().getZ())
                .thenComparingInt(p -> computers.get(p.computer()).position().getX())
                .thenComparingInt(p -> computers.get(p.computer()).pins().indexOf(p.pin()))).toList();
            var access = new ArrayList<XBusAccess>();
            var groups = new LinkedHashMap<Controller, List<Wire>>();
            for (var wire : internal) groups.computeIfAbsent(wire.controller(), ignored -> new ArrayList<>()).add(wire);
            var bindings = new HashMap<Wire, XBusWireBinding>();
            for (var group : groups.entrySet()) {
                var candidates = group.getKey().entity().collectXBusCandidates(group.getValue().stream().map(Wire::name).toList())
                    .getOrThrow(Failure::new);
                for (var wire : group.getValue()) bindings.put(wire, candidates.get(wire.name()));
            }
            for (var wire : internal) {
                var binding = bindings.get(wire);
                if (!binding.link().equals(wire.link())) fail("stale");
                access.addAll(binding.accesses());
                observe(binding.left().observed()); observe(binding.right().observed());
            }
            var resolver = XBusCableNetwork.resolver(level); var faces = EnumSet.allOf(Direction.class);
            for (var connection : external) {
                var left = new XBusCableNetwork.End(connection.entry().origin(), faces);
                var right = new XBusCableNetwork.End(connection.entry().peer(), faces);
                var a = XBusCableNetwork.resolve(resolver, right, left);
                var b = XBusCableNetwork.resolve(resolver, left, right);
                var leftPin = a.pin().orElseThrow(() -> new Failure(a.code()));
                var rightPin = b.pin().orElseThrow(() -> new Failure(b.code()));
                access.add(leftPin.access()); access.add(rightPin.access());
                observe(leftPin.observed()); observe(rightPin.observed());
            }
            // One final proof after ALL alias/provider/physical callbacks, including for an internal-only group.
            access.set(0, access.getFirst().validated(() -> closure.current() ? current() : Optional.of(XBusExchange.Status.STALE)));
            var network = XBusAccess.network(access);
            var displayWires = new ArrayList<DisplayWire>();
            for (var wire : internal) displayWires.add(new DisplayWire(wire.link(),
                observations.get(wire.link().left()).position(), observations.get(wire.link().right()).position(), false));
            for (var connection : external) {
                var left = connection.entry().origin(); var right = connection.entry().peer();
                displayWires.add(new DisplayWire(new XBusNetwork.Link(physical(left), physical(right)),
                    left.position(), right.position(), true));
            }
            return new Connected(new XBusNetwork(canonical, links), network, observations, displayWires);
        }
        private void observe(XBusPortState observed) {
            var pin = new XBusNetwork.Pin(observed.computer(), observed.pin());
            var port = new Port(computers.get(pin.computer()).position(), observed);
            var previous = observations.putIfAbsent(pin, port);
            if (previous != null && !previous.equals(port)) fail("stale");
        }
        private void discover(XBusNetwork.Pin pin) {
            var computer = computers.get(pin.computer());
            computer.attachment().ifPresent(attachment -> {
                var controller = controller(attachment.position(), attachment.controller());
                for (var entry : controller.config().wires().entrySet()) {
                    var wire = entry.getValue(); if (!wire.enabled()) continue;
                    var left = memberPin(controller.config(), wire.left());
                    var right = memberPin(controller.config(), wire.right());
                    if (!left.equals(pin) && !right.equals(pin)) continue;
                    var link = new XBusNetwork.Link(left, right);
                    if (!internal.add(new Wire(controller, entry.getKey(), link))) continue;
                    bound(); links.add(link);
                    for (var end : List.of(wire.left(), wire.right())) {
                        var member = controller.config().members().get(end.member());
                        computer(member.position(), member.identity()); add(memberPin(controller.config(), end));
                    }
                }
            });
            // Metadata first: a configured but unavailable branch rejects the component, never disappears from it.
            Set<XBusWiringSavedData.EnabledConnection> declared;
            try { declared = store.connections(pin); }
            catch (IllegalArgumentException invalid) { throw new Failure(invalid.getMessage()); }
            for (var connection : declared) {
                if (!external.add(connection)) continue;
                bound();
                var left = connection.entry().origin(); var right = connection.entry().peer();
                links.add(new XBusNetwork.Link(physical(left), physical(right)));
                for (var target : List.of(left, right)) { locate(target); add(physical(target)); }
            }
        }
        private void bound() {
            if (((long)external.size() + internal.size()) * 2 > maxEndpoints) fail("endpoint_limit");
        }
        private void add(XBusNetwork.Pin pin) {
            if (!computers.get(pin.computer()).pins().contains(pin.pin())) fail("port_mismatch");
            if (pins.add(pin)) {
                if (pins.size() > maxEndpoints) fail("endpoint_limit");
                pending.addLast(pin);
            }
        }
        private void locate(XBusCableTarget target) {
            if (target.device().equals(target.computer())) { computer(target.position(), target.computer()); return; }
            var controller = controller(target.position(), target.device());
            var member = controller.config().members().values().stream().filter(m -> m.identity().equals(target.computer()))
                .findFirst().orElseThrow(() -> new Failure("identity_changed"));
            computer(member.position(), member.identity());
        }
        private Computer computer(BlockPos pos, UUID identity) {
            var current = computers.get(identity);
            if (current != null) {
                if (!current.position().equals(pos)) fail("identity_changed");
                return current;
            }
            var entity = loaded(pos);
            if (!(entity instanceof SmartBlockEntity smart)) throw new Failure("not_found");
            var behaviour = smart.getBehaviour(ComputerBehaviour.TYPE);
            if (behaviour == null) throw new Failure("unsupported");
            if (behaviour.communicationIdentity().filter(identity::equals).isEmpty()) fail("identity_changed");
            var hardware = behaviour.hardware(); if (!hardware.ready()) fail("unavailable");
            var previous = positions.putIfAbsent(pos.immutable(), identity);
            if (previous != null && !previous.equals(identity)) fail("identity_changed");
            var result = new Computer(behaviour, pos.immutable(), hardware.limits().pins().xbus(), behaviour.clusterAttachment());
            computers.put(identity, result); return result;
        }
        private Controller controller(BlockPos pos, UUID identity) {
            var current = controllers.get(pos);
            if (current != null) {
                if (!current.identity().equals(identity)) fail("identity_changed");
                return current;
            }
            if (!(loaded(pos) instanceof ClusterControllerBlockEntity entity)) throw new Failure("not_found");
            if (!entity.controllerId().equals(identity)) fail("identity_changed");
            long revision = entity.configurationRevision();
            var config = entity.routingState().orElseThrow(() -> new Failure("unavailable")).configuration();
            var result = new Controller(entity, identity, revision, config); controllers.put(pos.immutable(), result); return result;
        }
        private BlockEntity loaded(BlockPos pos) {
            if (!level.hasChunkAt(pos)) throw new Failure("target_unloaded");
            var entity = level.getBlockEntity(pos);
            if (entity == null || entity.isRemoved()) throw new Failure("not_found");
            return entity;
        }
        /** Local fields and current physical instances only; no provider callbacks or world-wide rescans. */
        private Optional<XBusExchange.Status> current() {
            for (var computer : computers.values()) {
                if (!present(computer.behaviour().blockEntity)) return Optional.of(XBusExchange.Status.UNAVAILABLE);
                if (!computer.behaviour().clusterAttachment().equals(computer.attachment())) return Optional.of(XBusExchange.Status.STALE);
            }
            for (var controller : controllers.values()) {
                if (!present(controller.entity())) return Optional.of(XBusExchange.Status.UNAVAILABLE);
                if (!controller.entity().controllerId().equals(controller.identity())
                        || controller.entity().configurationRevision() != controller.revision()) return Optional.of(XBusExchange.Status.STALE);
            }
            return Optional.empty();
        }
        private boolean present(BlockEntity entity) {
            return entity.getLevel() == level && !entity.isRemoved() && level.hasChunkAt(entity.getBlockPos())
                && level.getBlockEntity(entity.getBlockPos()) == entity;
        }
    }
    private static XBusNetwork.Pin physical(XBusCableTarget target) { return new XBusNetwork.Pin(target.computer(), target.pin()); }
    private static XBusNetwork.Pin memberPin(ClusterConfiguration config, XBusWire.Pin pin) {
        return new XBusNetwork.Pin(config.members().get(pin.member()).identity(), pin.pin());
    }
    private static void fail(String code) { throw new Failure(code); }
    private static final class Failure extends RuntimeException { private Failure(String code) { super(code); } }
}
