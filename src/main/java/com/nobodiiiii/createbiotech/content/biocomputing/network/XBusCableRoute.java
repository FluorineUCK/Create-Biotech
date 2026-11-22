package com.nobodiiiii.createbiotech.content.biocomputing.network;

import java.util.*;
import net.minecraft.core.BlockPos;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusExchange;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import com.nobodiiiii.createbiotech.registry.CBConfigs;

/** Explicit physical coordinator service. Resolution alone never exchanges or runs a program. */
public final class XBusCableRoute {
    private XBusCableRoute() {}
    interface World extends EndpointDirectory.World {
        default void beginScan() {}
        /** Physical instance token, not a provider method call or a persistent device ID. */
        default Object instance(BlockPos pos) { return endpoint(pos); }
    }
    public record Pin(XBusCableTarget target, XBusPortState observed, XBusAccess access) {
        public Pin { Objects.requireNonNull(target); Objects.requireNonNull(observed); Objects.requireNonNull(access); }
    }
    public record Result(String code, Optional<Pin> pin) {
        public Result { Objects.requireNonNull(code); Objects.requireNonNull(pin); }
    }
    public static Result bind(SmartBlockEntity owner, Set<Direction> sourceFaces, String name, String pin) {
        return fromOwner(owner, sourceFaces, (world, limits) -> bind(world, owner.getBlockPos(), limits, name, pin));
    }
    public static Result resolve(SmartBlockEntity owner, Set<Direction> sourceFaces, XBusCableTarget target) {
        return fromOwner(owner, sourceFaces, (world, limits) -> resolve(world, owner.getBlockPos(), limits, target));
    }
    private static Result fromOwner(SmartBlockEntity owner, Set<Direction> faces,
            java.util.function.BiFunction<World, CommunicationNetworkLimits, Result> operation) {
        try {
            requireOwner(owner);
            var level = (ServerLevel)owner.getLevel();
            var origin = owner.getBlockPos().immutable();
            var sourceFaces = Set.copyOf(faces);
            Runnable sourceAvailable = () -> {
                requireOwner(owner);
                if (owner.getLevel() != level || !owner.getBlockPos().equals(origin)) fail("unavailable");
            };
            // Captured access keeps checking the originating instance as well as the target. No offline player impersonation.
            World world = new World() {
                private LoadedEndpointWorld delegate;
                public void beginScan() {
                    sourceAvailable.run();
                    delegate = new LoadedEndpointWorld(level, origin, pos -> level.getWorldBorder().isWithinBounds(pos), sourceFaces);
                }
                public Object instance(BlockPos pos) { sourceAvailable.run(); return level.getBlockEntity(pos); }
                public boolean loaded(BlockPos pos) { sourceAvailable.run(); return delegate.loaded(pos); }
                public CableDiscovery.Cell cell(BlockPos pos) { sourceAvailable.run(); return delegate.cell(pos); }
                public boolean acceptsCable(BlockPos pos, Direction side) { sourceAvailable.run(); return delegate.acceptsCable(pos, side); }
                public CommunicationEndpoint endpoint(BlockPos pos) { sourceAvailable.run(); return delegate.endpoint(pos); }
                public CommunicationEndpoint endpoint(BlockPos pos, Direction side) { sourceAvailable.run(); return delegate.endpoint(pos, side); }
            };
            return operation.apply(world, CBConfigs.SERVER.biocomputing.communication(CBConfigs.SERVER_SPEC.isLoaded()));
        } catch (CableDiscovery.DiscoveryException failure) { return failure(failure.code()); }
        catch (RuntimeException | StackOverflowError failure) { return failure("provider_failure"); }
    }
    private static void requireOwner(SmartBlockEntity owner) {
        if (owner == null || owner.isRemoved() || !(owner.getLevel() instanceof ServerLevel level)
                || !level.hasChunkAt(owner.getBlockPos()) || !level.getWorldBorder().isWithinBounds(owner.getBlockPos())
                || level.getBlockEntity(owner.getBlockPos()) != owner) fail("unavailable");
    }
    static Result bind(World world, BlockPos origin, CommunicationNetworkLimits limits, String name, String pin) {
        try {
            world.beginScan();
            new EndpointConfiguration(name, true);
            var candidates = EndpointDirectory.inspect(world, origin, limits).stream().filter(port -> port.name().equals(name)).toList();
            var open = candidates.stream().filter(EndpointDirectory.Port::enabled).toList();
            if (open.isEmpty()) return failure(candidates.isEmpty() ? "not_found" : "closed");
            if (open.size() != 1) return failure("ambiguous");
            var port = open.getFirst();
            var provider = world.endpoint(port.connection().position(), port.connection().side());
            if (provider == null) return failure("endpoint_changed");
            var identity = provider.communicationIdentity();
            if (identity.isEmpty()) return failure("unsupported");
            var pins = XBusPinDiscovery.inspect(world, port, limits.maxEndpoints());
            if (!pins.contains(pin)) return failure(pins.isEmpty() ? "unsupported" : "port_mismatch");
            var observed = provider.xbusPort(name, pin);
            if (observed.isEmpty()) return failure("endpoint_changed");
            var target = new XBusCableTarget(port.connection().position(), port.domain(), name, pin,
                identity.orElseThrow(), observed.orElseThrow().computer());
            return access(world, origin, limits, target, provider, observed.orElseThrow());
        } catch (CableDiscovery.DiscoveryException failure) { return failure(failure.code()); }
        catch (RuntimeException | StackOverflowError failure) { return failure("provider_failure"); }
    }
    static Result resolve(World world, BlockPos origin, CommunicationNetworkLimits limits, XBusCableTarget target) {
        try {
            var current = check(world, origin, limits, target, Optional.empty());
            return access(world, origin, limits, target, current.provider(), current.observed());
        } catch (CableDiscovery.DiscoveryException failure) { return failure(failure.code()); }
        catch (RuntimeException | StackOverflowError failure) { return failure("provider_failure"); }
    }
    private static Result access(World world, BlockPos origin, CommunicationNetworkLimits limits, XBusCableTarget target,
            CommunicationEndpoint provider, XBusPortState state) {
        var source = origin.immutable();
        var access = provider.xbusAccess(target.endpoint(), state);
        if (access.isEmpty() || !access.orElseThrow().hasFinalValidation()) return failure("unsupported");
        var initial = check(world, source, limits, target, Optional.of(state));
        var current = new java.util.concurrent.atomic.AtomicReference<>(initial);
        return new Result("ready", Optional.of(new Pin(target, state, access.orElseThrow().withPhysicalRoute(
            () -> rejection(() -> {
                var checked = check(world, source, limits, target, Optional.of(state));
                if (checked.instance() != initial.instance()) fail("unavailable");
                current.set(checked);
            }),
            () -> rejection(() -> physical(world, source, limits, target, current.get()))))));
    }
    private static Optional<XBusExchange.Status> rejection(Runnable check) {
        try { check.run(); return Optional.empty(); }
        catch (CableDiscovery.DiscoveryException failure) { return Optional.of(status(failure.code())); }
        catch (RuntimeException | StackOverflowError failure) { return Optional.of(XBusExchange.Status.UNAVAILABLE); }
    }
    private record Current(CommunicationEndpoint provider, XBusPortState observed, CableDiscovery.Connection connection, Object instance) {}
    private static Current check(World world, BlockPos origin, CommunicationNetworkLimits limits, XBusCableTarget target, Optional<XBusPortState> expected) {
        world.beginScan();
        if (!world.loaded(target.position())) fail("target_unloaded");
        if (world.cell(target.position()).kind() != CableDiscovery.Kind.ENDPOINT) fail("not_found");
        var ports = EndpointDirectory.inspect(world, origin, limits);
        var atPosition = ports.stream().filter(port -> port.connection().position().equals(target.position())).toList();
        if (atPosition.isEmpty()) missingPort(world, origin, limits, target);
        var port = atPosition.stream().filter(value -> value.domain().equals(target.domain()) && value.name().equals(target.endpoint()))
            .findFirst().orElseThrow(() -> new CableDiscovery.DiscoveryException("endpoint_changed"));
        if (!port.enabled()) fail("closed");
        var pins = XBusPinDiscovery.inspect(world, port, limits.maxEndpoints());
        if (!pins.contains(target.pin())) fail(pins.isEmpty() ? "unsupported" : "port_mismatch");
        var provider = world.endpoint(target.position(), port.connection().side());
        if (provider == null || !provider.domain().equals(target.domain())) fail("endpoint_changed");
        if (provider.communicationIdentity().filter(target.device()::equals).isEmpty()) fail("identity_changed");
        var observed = provider.xbusPort(target.endpoint(), target.pin()).orElseThrow(() -> new CableDiscovery.DiscoveryException("port_mismatch"));
        if (!observed.pin().equals(target.pin())) fail("port_mismatch");
        if (!observed.computer().equals(target.computer())) fail("identity_changed");
        if (expected.filter(value -> !value.equals(observed)).isPresent()) fail("stale");
        if (!CableDiscovery.scanEndpointConnections(world, origin, limits.maxVisited(), limits.maxEndpoints()).contains(port.connection()))
            fail("unreachable");
        return new Current(provider, observed, port.connection(), world.instance(target.position()));
    }
    /** Runs after both sides' provider callbacks. Never calls advertisement, identity, pins, port or access. */
    private static void physical(World world, BlockPos origin, CommunicationNetworkLimits limits, XBusCableTarget target, Current expected) {
        world.beginScan();
        if (!world.loaded(target.position())) fail("target_unloaded");
        if (world.instance(target.position()) != expected.instance()) fail("unavailable");
        if (world.cell(target.position()).kind() != CableDiscovery.Kind.ENDPOINT) fail("not_found");
        if (!CableDiscovery.scanEndpointConnections(world, origin, limits.maxVisited(), limits.maxEndpoints()).contains(expected.connection()))
            fail("unreachable");
    }
    /** A reachable but unconfigured replacement has no advertisements; that is not a broken cable. */
    private static void missingPort(World world, BlockPos origin, CommunicationNetworkLimits limits, XBusCableTarget target) {
        var connections = CableDiscovery.scanEndpointConnections(world, origin, limits.maxVisited(), limits.maxEndpoints()).stream()
            .filter(connection -> connection.position().equals(target.position())).toList();
        if (connections.isEmpty()) fail("unreachable");
        for (var connection : connections) {
            var provider = world.endpoint(target.position(), connection.side());
            if (provider == null || !provider.domain().equals(target.domain())) continue;
            if (provider.communicationIdentity().filter(target.device()::equals).isEmpty()) fail("identity_changed");
            fail("not_found");
        }
        fail("endpoint_changed");
    }
    private static XBusExchange.Status status(String code) {
        return switch (code) {
            case "closed" -> XBusExchange.Status.CLOSED;
            case "identity_changed", "endpoint_changed", "stale" -> XBusExchange.Status.STALE;
            case "not_found" -> XBusExchange.Status.NOT_FOUND;
            case "port_mismatch" -> XBusExchange.Status.PORT_MISMATCH;
            case "unsupported" -> XBusExchange.Status.UNSUPPORTED;
            default -> XBusExchange.Status.UNAVAILABLE;
        };
    }
    private static void fail(String code) { throw new CableDiscovery.DiscoveryException(code); }
    private static Result failure(String code) { return new Result(code, Optional.empty()); }
}
