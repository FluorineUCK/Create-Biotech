package com.nobodiiiii.createbiotech.content.biocomputing.network;

import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterMemberEditorAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import java.util.*;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Server-only player configuration. No program node or client-supplied coordinate acquires this service. */
public final class XBusWiringService {
    private XBusWiringService() {}
    /** One public alias per view; missing/closed mappings keep their inactive declarations manageable. */
    public static XBusWiringSession open(ClusterControllerBlockEntity owner, String publicEndpoint,
            ServerPlayer actor, BooleanSupplier currentMenu) {
        new EndpointConfiguration(publicEndpoint, true);
        var access = new Live(owner, actor, currentMenu, () -> PeerDiscovery.authorize(owner, actor), publicEndpoint);
        access.authorize();
        return new XBusWiringSession(access, () -> XBusWiringSavedData.get(access.level), XBusWiringService::limits, access::archive, publicEndpoint);
    }
    public static XBusWiringSession open(SmartBlockEntity owner, ServerPlayer actor, BooleanSupplier currentMenu) {
        return open(owner, actor, currentMenu, () -> PeerDiscovery.authorize(owner, actor));
    }
    public static XBusWiringSession open(ClusterMemberEditorAccess member, ServerPlayer actor, BooleanSupplier currentMenu) {
        return open(member.computer(), actor, currentMenu, () -> PeerDiscovery.authorize(member, actor));
    }
    private static XBusWiringSession open(SmartBlockEntity owner, ServerPlayer actor, BooleanSupplier menu, Runnable authorize) {
        var access = new Live(owner, actor, menu, authorize, null); access.authorize();
        return new XBusWiringSession(access, () -> XBusWiringSavedData.get(access.level), XBusWiringService::limits,
            access::archive);
    }
    private static CommunicationNetworkLimits limits() {
        return CBConfigs.SERVER.biocomputing.communication(CBConfigs.SERVER_SPEC.isLoaded());
    }
    private static final class Live implements XBusWiringSession.Access {
        final SmartBlockEntity owner;
        final ServerPlayer actor;
        final ServerLevel level;
        final ComputerBehaviour computer;
        final CommunicationEndpoint endpoint;
        final String publicEndpoint;
        final BooleanSupplier currentMenu;
        final Runnable menuAuthorization;
        Live(SmartBlockEntity owner, ServerPlayer actor, BooleanSupplier currentMenu, Runnable authorize, String publicEndpoint) {
            this.owner = Objects.requireNonNull(owner); this.actor = Objects.requireNonNull(actor);
            this.currentMenu = Objects.requireNonNull(currentMenu); menuAuthorization = Objects.requireNonNull(authorize);
            if (!(owner.getLevel() instanceof ServerLevel server)) throw unavailable("owner_unavailable");
            level = server; computer = owner.getBehaviour(ComputerBehaviour.TYPE);
            this.publicEndpoint = publicEndpoint;
            if (publicEndpoint != null && owner instanceof ClusterControllerBlockEntity controller) endpoint = controller;
            else if (publicEndpoint == null && computer != null) endpoint = computer;
            else throw unavailable("owner_unavailable");
        }
        public void authorize() {
            if (!currentMenu.getAsBoolean() || !actor.isAlive() || actor.serverLevel() != level) throw unavailable("menu_unavailable");
            menuAuthorization.run(); if (computer != null) computer.requireEndpointAvailable();
        }
        ArchiveLimits archive() {
            return computer != null ? computer.hardware().limits().archive()
                : CBConfigs.SERVER.biocomputing.cluster(CBConfigs.SERVER_SPEC.isLoaded()).archive();
        }
        public UUID owner() {
            authorize(); return endpoint.communicationIdentity().orElseThrow(() -> unavailable("identity_unavailable"));
        }
        public long sourceRevision() {
            authorize(); return owner instanceof ClusterControllerBlockEntity controller ? controller.configurationRevision() : 0;
        }
        public XBusWiringSession.Discovery discover() {
            authorize();
            List<XBusCableTarget> local;
            try {
                if (owner instanceof ClusterControllerBlockEntity controller) {
                    if (controller.enclosure().enclosure().isEmpty())
                        return new XBusWiringSession.Discovery(List.of(), List.of(), controller.diagnosticCode());
                    var state = controller.routingState();
                    if (state.isEmpty()) return new XBusWiringSession.Discovery(List.of(), List.of(), controller.diagnosticCode());
                    var mapping = state.orElseThrow().configuration().publicPins().get(publicEndpoint);
                    if (mapping == null) return new XBusWiringSession.Discovery(List.of(), List.of(), "public_pin_missing");
                    if (!mapping.enabled()) return new XBusWiringSession.Discovery(List.of(), List.of(), "local_closed");
                }
                var config = publicEndpoint == null ? endpoint.advertisement() : endpoint.advertisements().stream()
                    .filter(value -> value.enabled() && value.name().equals(publicEndpoint)).findFirst();
                if (config.isEmpty()) {
                    String code = owner instanceof ClusterControllerBlockEntity controller
                        && !controller.diagnosticCode().equals("ready") ? controller.diagnosticCode() : "local_closed";
                    return new XBusWiringSession.Discovery(List.of(), List.of(), code);
                }
                var name = config.orElseThrow().name(); var identity = owner(); var domain = endpoint.domain();
                var pins = new HardwarePins(endpoint.xbusPins(name), List.of()).xbus();
                if (pins.isEmpty()) return new XBusWiringSession.Discovery(List.of(), List.of(),
                    owner instanceof ClusterControllerBlockEntity controller
                        ? controller.publicPinDiagnostics().getOrDefault(name, "local_unsupported") : "local_unsupported");
                var found = new ArrayList<XBusCableTarget>();
                for (String pin : pins) {
                    var state = endpoint.xbusPort(name, pin).orElseThrow(() -> new CableDiscovery.DiscoveryException("endpoint_changed"));
                    found.add(new XBusCableTarget(owner.getBlockPos(), domain, name, pin, identity, state.computer()));
                }
                local = List.copyOf(found);
            } catch (CableDiscovery.DiscoveryException known) { return new XBusWiringSession.Discovery(List.of(), List.of(), known.code()); }
            catch (RuntimeException | StackOverflowError failure) { return new XBusWiringSession.Discovery(List.of(), List.of(), "provider_failure"); }
            try {
                var budget = limits(); var world = world(owner.getBlockPos()); world.beginScan();
                var ports = EndpointDirectory.scan(world, owner.getBlockPos(), budget);
                var peers = new ArrayList<XBusCableTarget>(); var used = new HashSet<CableDiscovery.Connection>();
                for (var port : ports) {
                    var pins = XBusPinDiscovery.inspect(world, port, budget.maxEndpoints());
                    if (pins.isEmpty()) continue;
                    if ((long)local.size() + peers.size() + pins.size() > budget.maxEndpoints()) throw new CableDiscovery.DiscoveryException("endpoint_limit");
                    var provider = Objects.requireNonNull(world.endpoint(port.connection().position(), port.connection().side()));
                    var identity = provider.communicationIdentity().orElseThrow(() -> new CableDiscovery.DiscoveryException("unsupported"));
                    for (String pin : pins) {
                        var state = provider.xbusPort(port.name(), pin).orElseThrow(() -> new CableDiscovery.DiscoveryException("endpoint_changed"));
                        if (!state.pin().equals(pin)) throw new CableDiscovery.DiscoveryException("port_mismatch");
                        peers.add(new XBusCableTarget(port.connection().position(), port.domain(), port.name(), pin, identity, state.computer()));
                    }
                    if (!XBusPinDiscovery.inspect(world, port, budget.maxEndpoints()).equals(pins)
                        || provider.communicationIdentity().filter(identity::equals).isEmpty()) throw new CableDiscovery.DiscoveryException("endpoint_changed");
                    used.add(port.connection());
                }
                // One bounded physical recheck, not a separate flood fill per offered pin.
                world.beginScan();
                if (!new HashSet<>(CableDiscovery.scanEndpointConnections(world, owner.getBlockPos(), budget.maxVisited(), budget.maxEndpoints())).containsAll(used))
                    throw new CableDiscovery.DiscoveryException("endpoint_changed");
                return new XBusWiringSession.Discovery(local, peers, "ready");
            } catch (CableDiscovery.DiscoveryException known) { return new XBusWiringSession.Discovery(local, List.of(), known.code()); }
            catch (RuntimeException | StackOverflowError failure) { return new XBusWiringSession.Discovery(local, List.of(), "provider_failure"); }
        }
        public XBusCableRoute.Result resolve(XBusCableTarget origin, XBusCableTarget target) {
            try { return XBusCableRoute.resolve(world(origin.position()), origin.position(), limits(), target); }
            catch (CableDiscovery.DiscoveryException failure) { return new XBusCableRoute.Result(failure.code(), Optional.empty()); }
        }
        private XBusCableRoute.World world(BlockPos origin) {
            authorize();
            if (!level.hasChunkAt(origin)) throw new CableDiscovery.DiscoveryException("source_unloaded");
            if (!permitted(origin)) throw new CableDiscovery.DiscoveryException("access_denied");
            if (!(level.getBlockEntity(origin) instanceof SmartBlockEntity source) || source.isRemoved()) throw new CableDiscovery.DiscoveryException("not_found");
            return new XBusCableRoute.World() {
                private LoadedEndpointWorld delegate;
                private void current() {
                    authorize();
                    if (source.isRemoved() || source.getLevel() != level || !level.hasChunkAt(origin)
                        || level.getBlockEntity(origin) != source) throw new CableDiscovery.DiscoveryException("source_unavailable");
                }
                public void beginScan() { current(); delegate = new LoadedEndpointWorld(level, origin, Live.this::permitted); }
                public Object instance(BlockPos pos) {
                    current();
                    if (!level.hasChunkAt(pos)) throw new CableDiscovery.DiscoveryException("target_unloaded");
                    if (!permitted(pos)) throw new CableDiscovery.DiscoveryException("access_denied");
                    return level.getBlockEntity(pos);
                }
                public boolean loaded(BlockPos pos) { current(); return delegate.loaded(pos); }
                public CableDiscovery.Cell cell(BlockPos pos) { current(); return delegate.cell(pos); }
                public boolean acceptsCable(BlockPos pos, Direction side) { current(); return delegate.acceptsCable(pos, side); }
                public CommunicationEndpoint endpoint(BlockPos pos) { current(); return delegate.endpoint(pos); }
                public CommunicationEndpoint endpoint(BlockPos pos, Direction side) { current(); return delegate.endpoint(pos, side); }
            };
        }
        private boolean permitted(BlockPos pos) { return level.getWorldBorder().isWithinBounds(pos) && level.mayInteract(actor, pos); }
    }
    private static PeerDiscovery.DiscoveryException unavailable(String detail) { return new PeerDiscovery.DiscoveryException("unavailable", detail); }
}
