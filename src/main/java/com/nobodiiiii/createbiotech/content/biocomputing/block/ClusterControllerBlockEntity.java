package com.nobodiiiii.createbiotech.content.biocomputing.block;

import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.mojang.serialization.DataResult;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import com.nobodiiiii.createbiotech.registry.CBParticleTypes;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusExchange;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusReceipt;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.SimpleIoNetwork;
import com.nobodiiiii.createbiotech.content.biocomputing.diagnostics.RecentEventLog;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;

/** Physical concentrated I/O host; members retain their independent Computer behaviours. */
public class ClusterControllerBlockEntity extends SmartBlockEntity implements CommunicationEndpoint {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    public static final String ROOT = "BiocomputingCluster";
    private final ClusterRouter router = new ClusterRouter(this::setChanged);
    private final ClusterStructureGuard structure = new ClusterStructureGuard();
    private record TopologyValidation(ClusterStructureGuard.Proof evidence, net.minecraft.world.level.Level world,
            java.util.function.Supplier<Optional<XBusExchange.Status>> check) {}
    private TopologyValidation topologyValidation;
    private UUID identity = UUID.randomUUID();
    private Tag invalidSave;
    private String diagnostic = "unformed";
    private boolean structureReady;
    private boolean particleFailureLogged;
    private long revision;
    private long signalIndicatorTick = Long.MIN_VALUE;
    private long xbusIndicatorTick = Long.MIN_VALUE;
    private long physicalTick = Long.MIN_VALUE;
    private Map<String, String> wireDiagnostics = Map.of();
    private final RecentEventLog recentEvents = new RecentEventLog(() -> level == null ? 0 : level.getGameTime(),
        () -> CBConfigs.SERVER.biocomputing.recentEventCapacity(CBConfigs.SERVER_SPEC.isLoaded()));
    private Boolean logStructureReady;
    private final Map<String, String> logPortStates = new HashMap<>();
    public Map<String, String> wireDiagnostics() { return wireDiagnostics; }
    public ClusterControllerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }
    @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        behaviours.add(new XBusWiringIndicatorBehaviour(this, this::communicationIdentity, codes -> recentEvents.observeWiring(codes)));
    }
    public UUID controllerId() { return identity; }
    /** Loaded is not ticking: an inactive controller cannot extend a member's physical interface. */
    public boolean physicalIoTickAvailable() { return available() && physicalTick == level.getGameTime(); }
    @Override public Optional<UUID> communicationIdentity() { return available() ? Optional.of(identity) : Optional.empty(); }
    public long configurationRevision() { return revision; }
    private ClusterLimits limits() { return CBConfigs.SERVER.biocomputing.cluster(CBConfigs.SERVER_SPEC.isLoaded()); }
    private boolean available() {
        return invalidSave == null && !isRemoved() && level instanceof ServerLevel
            && level.hasChunkAt(worldPosition) && level.getBlockEntity(worldPosition) == this;
    }
    public EnclosureScanner.Result enclosure() {
        try { return scanEnclosure(); } finally { observeStructureEvents(); }
    }
    private EnclosureScanner.Result scanEnclosure() {
        if (!available()) { structureReady = false; diagnostic = invalidSave == null ? "unavailable" : "invalid_save"; return unavailableEnclosure(); }
        try {
            var result = structure.current(new WorldEnclosureView(level, worldPosition), worldPosition,
                CBConfigs.SERVER.biocomputing.enclosure(CBConfigs.SERVER_SPEC.isLoaded()));
            boolean wasReady = structureReady;
            structureReady = result.status() == EnclosureScanner.Status.READY;
            if (!structureReady) diagnostic = result.status().name().toLowerCase(Locale.ROOT);
            else {
                if (!wasReady) diagnostic = "ready";
                routingState(); // A sound shell must not hide an unreadable private routing archive.
            }
            return result;
        } catch (IllegalArgumentException failure) { structureReady = false; diagnostic = "invalid_configuration"; return unavailableEnclosure(); }
    }
    private void observeStructureEvents() {
        if (structureReady) {
            if (Boolean.FALSE.equals(logStructureReady)) recentEvents.append(RecentEventLog.Kind.RECOVERED, "recovered", "");
            recentEvents.settle("structure", "ready");
        } else recentEvents.transition("structure", diagnostic, RecentEventLog.Kind.UNAVAILABLE, diagnostic, "");
        logStructureReady = structureReady;
    }
    /** Only controller-owned public diagnostics, never member programs, locals or message payloads. */
    public DataValue.RecordValue recentEvents() { enclosure(); observePublicPinEvents(); return recentEvents.value(); }
    private void observePublicPinEvents() {
        routingState().ifPresent(state -> state.configuration().publicPins().forEach((name, mapping) -> {
            try {
                var port = publicPin(name, mapping.target().pin()).observed();
                String code = switch (port.mode()) {
                    case READ -> "waiting_read";
                    case WRITE -> "waiting_write";
                    case READABLE -> "waiting_readable";
                    case PAUSED -> "paused";
                    case FAILED -> "failed";
                    default -> "ready";
                };
                observePortEvent("pin:" + name, code, port.mode() + ":" + port.flow() + ":" + port.operation(), name);
            } catch (RouteFailure failure) { observePortEvent("pin:" + name, failure.getMessage(), failure.getMessage(), name); }
        }));
    }
    private void observePortEvent(String channel, String code, Object identity, String name) {
        String previous = logPortStates.put(channel, code);
        boolean healthy = portAvailable(code);
        if (healthy && previous != null && !portAvailable(previous))
            recentEvents.append(RecentEventLog.Kind.RECOVERED, "recovered", name);
        if (code.equals("ready") || code.equals("xbus_exchanged") || code.equals("xbus_complete_complete")) recentEvents.settle(channel, identity);
        else recentEvents.transition(channel, identity, code.equals("paused") ? RecentEventLog.Kind.CONTROL
            : code.startsWith("waiting_") || code.equals("xbus_recovery_required") || healthy ? RecentEventLog.Kind.WAIT
            : code.equals("resource_limit") || code.equals("failed") ? RecentEventLog.Kind.ERROR : RecentEventLog.Kind.UNAVAILABLE, code, name);
    }
    private static boolean portAvailable(String code) {
        return Set.of("ready", "paused", "xbus_exchanged", "xbus_readable").contains(code) || code.startsWith("waiting_")
            || code.matches("xbus_(idle|paused|read|write|readable|complete)_(idle|paused|read|write|readable|complete)");
    }
    /** Post-commit diagnostics only. One writer operation identifies the cohort; never retain its payload. */
    public void recordRendezvous(UUID computer, String pin, XBusReceipt receipt) {
        var state = routingState(); if (state.isEmpty() || !structureReady) return;
        var config = state.orElseThrow().configuration();
        if (receipt.local(computer).stream().noneMatch(p -> p.pin().equals(pin))) return;
        var transfer = receipt.writer();
        config.publicPins().forEach((name, mapping) -> {
            var member = config.members().get(mapping.target().member());
            if (mapping.enabled() && member.identity().equals(computer) && mapping.target().pin().equals(pin))
                recentEvents.transition("transfer:" + name, transfer, RecentEventLog.Kind.TRANSFER, "xbus_exchanged", name);
        });
        var links = new TreeMap<String, XBusNetwork.Link>(); var pins = new LinkedHashSet<XBusNetwork.Pin>();
        config.wires().forEach((name, wire) -> {
            if (!wire.enabled()) return;
            var left = new XBusNetwork.Pin(config.members().get(wire.left().member()).identity(), wire.left().pin());
            var right = new XBusNetwork.Pin(config.members().get(wire.right().member()).identity(), wire.right().pin());
            pins.add(left); pins.add(right); links.put(name, new XBusNetwork.Link(left, right));
        });
        // All explicit segments of this shared channel carried the handoff. This is not routing,
        // another transfer, or a claim that an idle member consumed data. Other pins stay separate.
        var source = new XBusNetwork.Pin(transfer.computer(), transfer.pin());
        var component = new XBusNetwork(List.copyOf(pins), links.values()).components().stream()
            .filter(group -> group.contains(source)).findFirst();
        if (component.isEmpty()) return;
        var connected = Set.copyOf(component.orElseThrow());
        if (receipt.readers().stream().noneMatch(p -> connected.contains(new XBusNetwork.Pin(p.computer(), p.pin())))) return;
        links.forEach((name, edge) -> {
            if (connected.contains(edge.left()))
                recentEvents.transition("transfer:" + name, transfer, RecentEventLog.Kind.TRANSFER, "xbus_exchanged", name);
        });
    }
    /** Member-local execution failure, projected only onto explicitly configured wires/public pins. */
    public void recordXBusExecution(UUID computer, String pin, Object operation, String code) {
        var state = routingState(); if (state.isEmpty() || !structureReady) return;
        var config = state.orElseThrow().configuration();
        config.publicPins().forEach((name, mapping) -> {
            var member = config.members().get(mapping.target().member());
            if (mapping.enabled() && member.identity().equals(computer) && mapping.target().pin().equals(pin))
                recentEvents.observeExecution("pin:" + name, operation, code, name);
        });
        config.wires().forEach((name, wire) -> {
            if (!wire.enabled()) return;
            for (var end : List.of(wire.left(), wire.right())) {
                var member = config.members().get(end.member());
                if (member.identity().equals(computer) && end.pin().equals(pin))
                    recentEvents.observeExecution("wire:" + name + ":" + end.member(), operation, code, name);
            }
        });
    }
    private static EnclosureScanner.Result unavailableEnclosure() {
        return new EnclosureScanner.Result(EnclosureScanner.Status.NOT_CONTROLLER, Optional.empty());
    }
    public Optional<ClusterState> routingState() {
        if (invalidSave != null) { diagnostic = "invalid_save"; return Optional.empty(); }
        var previousFailure = router.diagnostic().map(ClusterControllerBlockEntity::routerCode);
        var state = router.snapshot(limits());
        if (state.isEmpty()) diagnostic = router.diagnostic().map(ClusterControllerBlockEntity::routerCode).orElse("unavailable");
        else if (structureReady) {
            if (!withinBindingCapacity(state.orElseThrow().configuration())) diagnostic = "computer_limit";
            else if (diagnostic.equals("computer_limit") || previousFailure.filter(diagnostic::equals).isPresent()) diagnostic = "ready";
        }
        return state;
    }
    private boolean withinBindingCapacity(ClusterConfiguration configuration) {
        return configuration.members().size() <= CBConfigs.SERVER.biocomputing.enclosure(CBConfigs.SERVER_SPEC.isLoaded()).maxComputers();
    }
    /** Runtime gate only: keep the original configuration readable and editable while over capacity. */
    private Optional<ClusterState> communicationState() {
        var state = routingState();
        if (state.filter(value -> !withinBindingCapacity(value.configuration())).isPresent()) {
            diagnostic = "computer_limit";
            return Optional.empty();
        }
        return state;
    }
    private static String routerCode(String detail) { return detail.startsWith("invalid_save:") ? "invalid_save" : detail; }
    public String diagnosticCode() { return diagnostic; }
    @Override public Optional<EndpointConfiguration> advertisement() { return advertisements().stream().findFirst(); }
    @Override public List<EndpointConfiguration> advertisements() {
        if (enclosure().enclosure().isEmpty()) return List.of();
        return communicationState().map(state -> {
            var ports = new ArrayList<EndpointConfiguration>();
            state.configuration().ports().forEach((name, port) -> ports.add(new EndpointConfiguration(name, port.enabled())));
            state.configuration().publicPins().forEach((name, pin) -> ports.add(new EndpointConfiguration(name, pin.enabled())));
            return List.copyOf(ports);
        }).orElse(List.of());
    }

    private record PublicPinBinding(XBusPublicPin mapping, ComputerBehaviour computer, XBusPortState observed) {}
    private PublicPinBinding publicPin(String name, String pin) {
        if (!available()) throw new RouteFailure("unavailable");
        var geometry = enclosure().enclosure().orElseThrow(() -> new RouteFailure(diagnostic));
        var state = communicationState().orElseThrow(() -> new RouteFailure(diagnostic));
        var mapping = state.configuration().publicPins().get(name);
        if (mapping == null) throw new RouteFailure("public_pin_missing");
        if (!mapping.enabled()) throw new RouteFailure("closed");
        var target = mapping.target();
        if (!target.pin().equals(pin)) throw new RouteFailure("xbus_pin_missing");
        var computer = input(state.configuration().members().get(target.member()), target.endpoint(), geometry);
        var observed = computer.xbusPort(target.endpoint(), target.pin()).orElseThrow(() -> new RouteFailure("xbus_pin_missing"));
        return new PublicPinBinding(mapping, computer, observed);
    }
    @Override public Optional<XBusPortState> xbusPort(String endpoint, String pin) {
        try { return Optional.of(publicPin(endpoint, pin).observed()); }
        catch (RouteFailure failure) { return Optional.empty(); }
    }
    @Override public List<String> xbusPins(String endpoint) {
        var mapping = communicationState().map(state -> state.configuration().publicPins().get(endpoint));
        return mapping.flatMap(value -> xbusPort(endpoint, value.target().pin())).map(value -> List.of(value.pin())).orElse(List.of());
    }
    @Override public Optional<XBusAccess> xbusAccess(String endpoint, XBusPortState expected) {
        Objects.requireNonNull(expected);
        try {
            var binding = publicPin(endpoint, expected.pin()); long observedRevision = revision;
            var observedIdentity = identity;
            if (!binding.observed().equals(expected)) return Optional.of(XBusAccess.denied(XBusExchange.Status.STALE));
            return binding.computer().xbusAccess(binding.mapping().target().endpoint(), expected).map(access -> access.guarded(() -> {
                try {
                    var current = publicPin(endpoint, expected.pin());
                    if (revision != observedRevision || !current.mapping().equals(binding.mapping()) || current.computer() != binding.computer())
                        return Optional.of(XBusExchange.Status.STALE);
                    return Optional.empty();
                } catch (RouteFailure failure) { return Optional.of(publicPinFailure(failure.getMessage())); }
            }).validated(() -> memberXBusProof(binding.computer(), observedIdentity, observedRevision)));
        } catch (RouteFailure failure) { return Optional.of(XBusAccess.denied(publicPinFailure(failure.getMessage()))); }
    }
    /** Internal routes are not external advertisements. Caller must assemble the complete physical-pin network. */
    public XBusWireBinding.Result captureXBusWire(String name) {
        var batch = captureXBusWires(List.of(name));
        return new XBusWireBinding.Result(batch.code(), Optional.ofNullable(batch.bindings().get(name)));
    }
    /** Capture a component's internal routes together, never cache permission across captures.
     * All member/provider callbacks finish before the shared captured geometry is proven afresh. */
    public XBusWireBinding.BatchResult captureXBusWires(Collection<String> names) {
        var candidates = collectXBusCandidates(names);
        if (candidates.error().isPresent())
            return new XBusWireBinding.BatchResult(candidates.error().orElseThrow().message(), Map.of());
        var bindings = candidates.getOrThrow();
        var denied = XBusAccess.network(bindings.values().stream().flatMap(binding -> binding.accesses().stream()).toList()).inspectBindings();
        return denied.map(status -> new XBusWireBinding.BatchResult(status.name().toLowerCase(Locale.ROOT), Map.of()))
            .orElseGet(() -> new XBusWireBinding.BatchResult("ready", bindings));
    }

    /** Collect guarded observations, not a ready route. Whole-network capture validates these
     * together with external aliases, after the last provider callback; standalone callers
     * use captureXBusWires for a validated result. Nothing is cached between captures. */
    public DataResult<Map<String, XBusWireBinding>> collectXBusCandidates(Collection<String> names) {
        try {
            if (names.isEmpty()) throw new RouteFailure("not_found");
            long observedRevision = revision;
            var observedIdentity = identity;
            var geometry = enclosure().enclosure().orElseThrow(() -> new RouteFailure(diagnostic));
            var proof = structure.proof().orElseThrow(() -> new RouteFailure("unavailable"));
            var topology = topologyValidation(proof);
            var bindings = new LinkedHashMap<String, XBusWireBinding>();
            for (var name : names) {
                if (bindings.containsKey(name)) continue;
                var left = internalPin(name, true, geometry); var right = internalPin(name, false, geometry);
                bindings.put(name, new XBusWireBinding(internalAccess(name, true, left, observedRevision, observedIdentity, geometry, topology),
                    internalAccess(name, false, right, observedRevision, observedIdentity, geometry, topology)));
            }
            return DataResult.success(Collections.unmodifiableMap(bindings));
        } catch (RouteFailure failure) { return DataResult.error(failure::getMessage); }
        catch (RuntimeException | StackOverflowError failure) { return DataResult.error(() -> "provider_failure"); }
    }
    /** Share evidence identity, never a cached decision. Each network barrier invokes this afresh
     * after all route/provider callbacks; changed captures cannot replace old captured evidence. */
    private java.util.function.Supplier<Optional<XBusExchange.Status>> topologyValidation(ClusterStructureGuard.Proof proof) {
        if (topologyValidation == null || topologyValidation.evidence() != proof || topologyValidation.world() != level) {
            var world = level;
            var position = worldPosition.immutable();
            topologyValidation = new TopologyValidation(proof, world, () ->
                level == world && worldPosition.equals(position)
                    && proof.unchanged(new WorldEnclosureView(world, position), position,
                        CBConfigs.SERVER.biocomputing.enclosure(CBConfigs.SERVER_SPEC.isLoaded()))
                    ? Optional.empty() : Optional.of(XBusExchange.Status.UNAVAILABLE));
        }
        return topologyValidation.check();
    }
    private record InternalPinBinding(XBusWire.Pin mapping, ComputerBehaviour computer, XBusPortState observed) {}
    private InternalPinBinding internalPin(String name, boolean left, ClusterEnclosure geometry) {
        if (!available()) throw new RouteFailure("unavailable");
        var state = communicationState().orElseThrow(() -> new RouteFailure(diagnostic));
        var wire = state.configuration().wires().get(name);
        if (wire == null) throw new RouteFailure("not_found");
        if (!wire.enabled()) throw new RouteFailure("closed");
        var pin = left ? wire.left() : wire.right();
        var computer = input(state.configuration().members().get(pin.member()), pin.endpoint(), geometry);
        var observed = computer.xbusPort(pin.endpoint(), pin.pin()).orElseThrow(() -> new RouteFailure("xbus_pin_missing"));
        return new InternalPinBinding(pin, computer, observed);
    }
    private XBusWireBinding.End internalAccess(String name, boolean left, InternalPinBinding binding,
            long observedRevision, UUID observedIdentity, ClusterEnclosure geometry,
            java.util.function.Supplier<Optional<XBusExchange.Status>> topology) {
        var access = binding.computer().xbusAccess(binding.mapping().endpoint(), binding.observed())
            .orElseThrow(() -> new RouteFailure("unsupported"));
        return new XBusWireBinding.End(binding.computer().blockEntity.getBlockPos(), binding.observed(), access.guarded(() -> {
            try {
                if (revision != observedRevision) return Optional.of(XBusExchange.Status.STALE);
                // Recheck each route/member/provider, then prove the captured geometry after ALL callbacks.
                var current = internalPin(name, left, geometry);
                if (!current.mapping().equals(binding.mapping()) || current.computer() != binding.computer()
                        || !current.observed().equals(binding.observed())) return Optional.of(XBusExchange.Status.STALE);
                return Optional.empty();
            } catch (RouteFailure failure) { return Optional.of(publicPinFailure(failure.getMessage())); }
        }).validated(() -> memberXBusIdentityProof(binding.computer(), observedIdentity, observedRevision)).validated(topology));
    }
    /** Callback-free final proof, after every member/public-alias/provider callback has finished. */
    private Optional<XBusExchange.Status> memberXBusProof(ComputerBehaviour computer, UUID observedIdentity, long observedRevision) {
        var denied = memberXBusIdentityProof(computer, observedIdentity, observedRevision);
        if (denied.isPresent()) return denied;
        return structure.unchanged(new WorldEnclosureView(level, worldPosition), worldPosition,
            CBConfigs.SERVER.biocomputing.enclosure(CBConfigs.SERVER_SPEC.isLoaded()))
            ? Optional.empty() : Optional.of(XBusExchange.Status.UNAVAILABLE);
    }
    private Optional<XBusExchange.Status> memberXBusIdentityProof(ComputerBehaviour computer, UUID observedIdentity, long observedRevision) {
        var owner = computer.blockEntity;
        if (!available() || owner.isRemoved() || !level.hasChunkAt(owner.getBlockPos()) || level.getBlockEntity(owner.getBlockPos()) != owner)
            return Optional.of(XBusExchange.Status.UNAVAILABLE);
        if (!identity.equals(observedIdentity) || revision != observedRevision
                || computer.clusterAttachment().filter(a -> a.controller().equals(identity) && a.position().equals(worldPosition)).isEmpty())
            return Optional.of(XBusExchange.Status.STALE);
        return Optional.empty();
    }
    private static XBusExchange.Status publicPinFailure(String code) {
        return switch (code) {
            case "closed" -> XBusExchange.Status.CLOSED;
            case "public_pin_missing", "not_found" -> XBusExchange.Status.NOT_FOUND;
            case "unsupported" -> XBusExchange.Status.UNSUPPORTED;
            case "endpoint_changed", "identity_changed", "not_attached" -> XBusExchange.Status.STALE;
            case "xbus_pin_missing" -> XBusExchange.Status.PORT_MISMATCH;
            default -> XBusExchange.Status.UNAVAILABLE;
        };
    }
    /** Per-export observation for the owner's management menu, never a payload or a connection claim. */
    public Map<String, String> publicPinDiagnostics() {
        var result = new LinkedHashMap<String, String>();
        routingState().ifPresent(state -> state.configuration().publicPins().forEach((name, mapping) -> {
            try { publicPin(name, mapping.target().pin()); result.put(name, "public_pin_ready"); }
            catch (RouteFailure failure) { result.put(name, failure.getMessage()); }
        }));
        return Collections.unmodifiableMap(result);
    }

    public String configure(ClusterConfiguration configuration, ServerPlayer player) {
        if (!available() || player.serverLevel() != level || player.distanceToSqr(worldPosition.getX() + .5, worldPosition.getY() + .5, worldPosition.getZ() + .5) > 64)
            return "unavailable";
        if (!level.mayInteract(player, worldPosition) || !level.getWorldBorder().isWithinBounds(worldPosition)) return "access_denied";
        var geometry = enclosure().enclosure(); if (geometry.isEmpty()) return diagnostic;
        if (!withinBindingCapacity(configuration)) return "computer_limit";
        var state = routingState(); if (state.isEmpty()) return diagnostic;
        if (state.get().busy() && !state.get().configuration().equals(configuration)) return "busy";
        Map<String, ComputerBehaviour> members = new LinkedHashMap<>();
        try {
            for (var entry : configuration.members().entrySet()) {
                var member = entry.getValue();
                if (!geometry.get().containsMember(member.position())) return "not_contacting";
                if (!level.getWorldBorder().isWithinBounds(member.position()) || !level.mayInteract(player, member.position())) return "access_denied";
                var computer = member(member, geometry.get());
                var existing = computer.clusterAttachment();
                if (existing.isPresent() && !existing.get().controller().equals(identity)) {
                    var old = existing.get();
                    if (!level.hasChunkAt(old.position())) return "controller_unloaded";
                    if (level.getBlockEntity(old.position()) instanceof ClusterControllerBlockEntity other && other.controllerId().equals(old.controller())) return "already_bound";
                }
                // A replacement controller may have no connections yet. Its empty table must not release an old reserved output.
                boolean sameAttachment = existing.filter(a -> a.controller().equals(identity) && a.position().equals(worldPosition)
                    && a.outputs().equals(configuration.outputNames(entry.getKey()))).isPresent();
                if (!sameAttachment && !computer.communicationQuiescent()) return "busy";
                members.put(entry.getKey(), computer);
            }
            // Removing a name/output binding does not change geometric membership.
            for (var old : state.get().configuration().members().values()) {
                if (configuration.members().containsValue(old) || !level.hasChunkAt(old.position())) continue;
                if (!level.mayInteract(player, old.position())) return "access_denied";
                if (level.getBlockEntity(old.position()) instanceof SmartBlockEntity smart) {
                    var computer = smart.getBehaviour(ComputerBehaviour.TYPE);
                    if (computer != null && computer.clusterAttachment().filter(a -> a.controller().equals(identity)).isPresent()
                        && !computer.communicationQuiescent()) return "busy";
                }
            }
            if (!state.get().configuration().equals(configuration)
                    && (!state.get().configuration().connections().isEmpty() || !configuration.connections().isEmpty())) {
                // Member outboxes own internal in-flight transfers, so the controller queue alone is not a safe edit fence.
                var observed = new LinkedHashSet<>(state.get().configuration().members().values()); observed.addAll(configuration.members().values());
                for (var expected : observed) {
                    if (!level.hasChunkAt(expected.position())) return "member_unloaded";
                    if (!level.mayInteract(player, expected.position())) return "access_denied";
                    if (level.getBlockEntity(expected.position()) instanceof SmartBlockEntity smart) {
                        var computer = smart.getBehaviour(ComputerBehaviour.TYPE);
                        if (computer != null && (computer.communicationIdentity().isEmpty()
                                || computer.communicationIdentity().filter(expected.identity()::equals).isPresent() && !computer.communicationQuiescent())) return "busy";
                    }
                }
            }
            for (var wire : configuration.wires().values()) {
                if (!wire.enabled()) continue;
                for (var pin : List.of(wire.left(), wire.right())) {
                    var computer = members.get(pin.member());
                    var grant = computer.advertisement();
                    if (grant.isEmpty()) return "closed";
                    if (!grant.orElseThrow().name().equals(pin.endpoint())) return "endpoint_changed";
                    if (computer.xbusPort(pin.endpoint(), pin.pin()).isEmpty()) return "xbus_pin_missing";
                }
            }
            for (var wire : configuration.signals().values()) {
                if (!wire.enabled()) continue;
                for (var pin : List.of(wire.left(), wire.right())) {
                    var computer = members.get(pin.member()); var grant = computer.advertisement();
                    if (grant.isEmpty()) return "closed";
                    if (!grant.orElseThrow().name().equals(pin.endpoint())) return "endpoint_changed";
                    if (computer.signalPort(pin.endpoint(), pin.pin()).isEmpty()) return "signal_pin_missing";
                }
            }
            for (var mapping : configuration.publicPins().values()) {
                if (!mapping.enabled()) continue;
                var pin = mapping.target(); var computer = members.get(pin.member()); var grant = computer.advertisement();
                if (grant.isEmpty()) return "closed";
                if (!grant.orElseThrow().name().equals(pin.endpoint())) return "endpoint_changed";
                if (computer.xbusPort(pin.endpoint(), pin.pin()).isEmpty()) return "xbus_pin_missing";
            }
            String result = router.configure(configuration, limits()); if (!result.equals("configured")) return result;
            var channels = new HashSet<String>(Set.of("structure"));
            configuration.wireNames().forEach(name -> { channels.add("transfer:" + name); channels.add("wire:" + name); });
            configuration.publicPins().keySet().forEach(name -> { channels.add("transfer:" + name); channels.add("pin:" + name); });
            configuration.publicPins().keySet().forEach(name -> channels.add("execution:pin:" + name));
            configuration.wires().forEach((name, wire) -> {
                channels.add("execution:wire:" + name + ":" + wire.left().member());
                channels.add("execution:wire:" + name + ":" + wire.right().member());
            });
            recentEvents.retainChannels(channels);
            logPortStates.keySet().retainAll(channels);
            wireDiagnostics = Map.of();
            for (var old : state.get().configuration().members().values())
                if (!configuration.members().containsValue(old) && level.hasChunkAt(old.position())
                        && level.getBlockEntity(old.position()) instanceof SmartBlockEntity smart) {
                    var computer = smart.getBehaviour(ComputerBehaviour.TYPE); if (computer != null) computer.detachCluster(identity);
                }
            members.forEach((name, computer) -> {
                computer.attachCluster(new ClusterAttachment(identity, worldPosition, configuration.outputNames(name)));
            });
            revision++; diagnostic = "configured"; return "configured";
        } catch (RouteFailure failure) { return failure.getMessage(); }
    }

    private ComputerBehaviour member(ClusterConfiguration.Member expected, ClusterEnclosure geometry) {
        var pos = expected.position();
        if (!level.hasChunkAt(pos)) throw new RouteFailure("member_unloaded");
        if (!SubLevelCompat.sameSpace(level, worldPosition, pos)) throw new RouteFailure("wrong_space");
        if (!geometry.containsMember(pos)) throw new RouteFailure("not_contacting");
        if (!(level.getBlockEntity(pos) instanceof SmartBlockEntity smart)) throw new RouteFailure("member_missing");
        var computer = smart.getBehaviour(ComputerBehaviour.TYPE);
        if (computer == null) throw new RouteFailure("member_missing");
        if (!computer.peripheralAvailable()) throw new RouteFailure("unavailable");
        var id = computer.communicationIdentity();
        if (id.isEmpty()) throw new RouteFailure(computer.missingCommunicationIdentityCode());
        if (!expected.identity().equals(id.get())) throw new RouteFailure("identity_changed");
        return computer;
    }
    private ComputerBehaviour input(ClusterConfiguration.Member expected, String name, ClusterEnclosure geometry) {
        var computer = member(expected, geometry);
        if (computer.clusterAttachment().filter(binding -> binding.controller().equals(identity) && binding.position().equals(worldPosition)).isEmpty())
            throw new RouteFailure("not_attached");
        var advertisement = computer.advertisement();
        if (advertisement.isEmpty()) throw new RouteFailure("closed");
        if (!advertisement.get().name().equals(name)) throw new RouteFailure("endpoint_changed");
        return computer;
    }
    @Override public AdmissionStatus receive(String port, MessageEnvelope message) {
        var geometry = enclosure().enclosure(); if (geometry.isEmpty()) return AdmissionStatus.UNAVAILABLE;
        var state = communicationState(); if (state.isEmpty()) return AdmissionStatus.UNAVAILABLE;
        var mapping = state.get().configuration().publicPins().get(port);
        if (mapping != null) return mapping.enabled() ? AdmissionStatus.UNSUPPORTED : AdmissionStatus.CLOSED;
        var route = state.get().configuration().ports().get(port); if (route == null || !route.enabled()) return AdmissionStatus.CLOSED;
        try {
            input(state.get().configuration().members().get(route.member()), route.input(), geometry.get());
            return router.receive(ClusterState.Side.EXTERNAL, port, message, limits());
        } catch (RouteFailure failure) { diagnostic = failure.getMessage(); return diagnostic.equals("closed") ? AdmissionStatus.CLOSED : AdmissionStatus.UNAVAILABLE; }
    }

    @Override public void tick() {
        try { tickRuntime(); } finally { observeStructureEvents(); observePublicPinEvents(); }
    }
    private void tickRuntime() {
        super.tick();
        if (!available()) { wireDiagnostics = Map.of(); return; }
        physicalTick = level.getGameTime();
        var state = communicationState(); if (state.isEmpty()) { wireDiagnostics = Map.of(); return; }
        var outgoing = state.get().outbound();
        if (outgoing.isEmpty() && state.get().configuration().wireNames().isEmpty()) { wireDiagnostics = Map.of(); return; }
        var geometry = enclosure().enclosure();
        if (geometry.isEmpty()) {
            var failed = new LinkedHashMap<String, String>();
            state.get().configuration().wireNames().forEach(name -> failed.put(name, diagnostic));
            wireDiagnostics = Map.copyOf(failed); return;
        }
        observeWires(state.get().configuration(), geometry.get());
        observeSignals(state.get().configuration(), geometry.get());
        if (outgoing.isEmpty()) return;
        var head = outgoing.get();
        try {
            if (head.target().side() == ClusterState.Side.EXTERNAL) {
                // A queued result is not a permanent grant to use a removed, replaced or closed member.
                var ingress = state.get().forwards().getFirst().ingress();
                var config = state.get().configuration();
                var route = config.ports().values().stream().filter(port -> port.output().equals(ingress.port())).findFirst().orElseThrow();
                input(config.members().get(route.member()), route.input(), geometry.get());
                var delivered = EndpointTransport.deliverEnvelope(this, Set.of(geometry.get().outward()), head.target().port(), head.envelope());
                diagnostic = delivered.code();
                if (delivered.accepted()) router.acknowledge(head, limits());
                return;
            }
            var computer = input(head.target().member().orElseThrow(), head.target().port(), geometry.get());
            var result = computer.receive(head.target().port(), head.envelope()); diagnostic = result.name().toLowerCase(Locale.ROOT);
            if (result.accepted()) {
                if (result == AdmissionStatus.ACCEPTED) transferParticles(worldPosition, head.target().member().orElseThrow().position());
                router.acknowledge(head, limits());
            }
        } catch (RouteFailure failure) { diagnostic = failure.getMessage(); }
    }
    private void observeWires(ClusterConfiguration config, ClusterEnclosure geometry) {
        boolean show = xbusIndicatorTick != level.getGameTime() && level.getGameTime()
            % CBConfigs.SERVER.biocomputing.xbusIndicatorInterval(CBConfigs.SERVER_SPEC.isLoaded()) == 0;
        if (show) xbusIndicatorTick = level.getGameTime();
        observeWires(config, geometry, show);
    }
    private void observeWires(ClusterConfiguration config, ClusterEnclosure geometry, boolean indicators) {
        var observed = new LinkedHashMap<String, String>(wireDiagnostics);
        observed.keySet().retainAll(config.wireNames());
        boolean current = communicationState().filter(state -> state.configuration().equals(config)).isPresent();
        long observedRevision = revision;
        // Reuse capture evidence only within this display pass, not authorization or decisions.
        // A fresh capture has already run its terminal proof. Reuse after another edge must revalidate.
        var components = new HashMap<XBusNetwork.Pin, XBusConnectedNetwork.Connected>();
        for (var entry : config.wires().entrySet()) {
            var wire = entry.getValue(); String code;
            if (!wire.enabled()) code = "closed";
            else if (!current || revision != observedRevision) code = "xbus_stale";
            else try {
                var left = new XBusNetwork.Pin(config.members().get(wire.left().member()).identity(), wire.left().pin());
                var right = new XBusNetwork.Pin(config.members().get(wire.right().member()).identity(), wire.right().pin());
                var connected = components.get(left);
                boolean fresh = connected == null;
                if (fresh) {
                    var capture = XBusConnectedNetwork.capture((ServerLevel) level, internalPin(entry.getKey(), true, geometry).computer(), wire.left().pin());
                    connected = capture.connected().orElseThrow(() -> new RouteFailure(capture.code()));
                    for (var pin : connected.ports().keySet()) components.put(pin, connected);
                }
                if (revision != observedRevision) throw new RouteFailure("xbus_stale");
                var a = connected.ports().get(left).observed(); var b = connected.ports().get(right).observed();
                // Observation only. All internal and external consumption belongs to the dimension round.
                var rejected = fresh ? Optional.<XBusExchange.Status>empty() : connected.network().inspectBindings();
                if (rejected.isPresent()) code = "xbus_" + rejected.orElseThrow().name().toLowerCase(Locale.ROOT);
                else {
                    code = "xbus_" + a.mode().name().toLowerCase(Locale.ROOT) + "_" + b.mode().name().toLowerCase(Locale.ROOT);
                    if (indicators) {
                        var leftPos = connected.ports().get(left).position();
                        var rightPos = connected.ports().get(right).position();
                        waitingParticles(leftPos, rightPos, a.mode());
                        waitingParticles(rightPos, leftPos, b.mode());
                    }
                }
            } catch (RouteFailure failure) { code = failure.getMessage(); }
            observePortEvent("wire:" + entry.getKey(), code, code, entry.getKey());
            observed.put(entry.getKey(), code);
        }
        wireDiagnostics = Collections.unmodifiableMap(observed);
    }
    private boolean attached(ComputerBehaviour computer) {
        return computer.clusterAttachment().filter(a -> a.controller().equals(identity) && a.position().equals(worldPosition)).isPresent();
    }
    public boolean manages(ComputerBehaviour computer) {
        var geometry = enclosure().enclosure(); var state = communicationState();
        if (geometry.isEmpty() || state.isEmpty() || !attached(computer)) return false;
        for (var expected : state.orElseThrow().configuration().members().values()) try {
            if (member(expected, geometry.orElseThrow()) == computer) return true;
        } catch (RouteFailure ignored) { }
        return false;
    }
    /** A debugger freezes world input now; it never executes the other Computers. */
    public Map<String, Integer> signalInputs(ComputerBehaviour computer) {
        var geometry = enclosure().enclosure(); var state = communicationState();
        if (geometry.isEmpty() || state.isEmpty() || !attached(computer)) return Map.of();
        for (var expected : state.orElseThrow().configuration().members().values()) try {
            if (member(expected, geometry.orElseThrow()) == computer)
                return sampleSignals(state.orElseThrow().configuration(), geometry.orElseThrow()).inputs(expected.identity());
        } catch (RouteFailure ignored) { }
        return Map.of();
    }
    private SimpleIoNetwork.Frame sampleSignals(ClusterConfiguration config, ClusterEnclosure geometry) {
        var ports = new LinkedHashMap<SimpleIoNetwork.Pin, SimpleIoNetwork.Port>();
        for (var expected : config.members().values()) try {
            var computer = member(expected, geometry);
            if (!attached(computer)) continue;
            var outputs = computer.signalOutputs(); if (outputs.isEmpty()) continue;
            for (String pin : computer.hardware().limits().pins().simpleIo()) {
                var output = outputs.orElseThrow().outputs().get(pin);
                ports.put(new SimpleIoNetwork.Pin(expected.identity(), pin), output == null ? SimpleIoNetwork.Port.input() : SimpleIoNetwork.Port.output(output));
            }
        } catch (RouteFailure ignored) { }
        var wires = new ArrayList<SimpleIoNetwork.Wire>();
        for (var wire : config.signals().values()) {
            if (!wire.enabled()) continue; // Explicit disconnection, unlike an enabled wire with an unavailable endpoint.
            for (var pin : List.of(wire.left(), wire.right())) {
                var physical = signalPin(config, pin); SimpleIoNetwork.Port port;
                try {
                    port = input(config.members().get(pin.member()), pin.endpoint(), geometry).signalPort(pin.endpoint(), pin.pin())
                        .orElseThrow(() -> new RouteFailure("signal_pin_missing"));
                } catch (RouteFailure failure) {
                    port = SimpleIoNetwork.Port.unavailable(switch (failure.getMessage()) {
                        case "member_unloaded" -> SimpleIoNetwork.Availability.UNLOADED;
                        case "closed", "endpoint_changed" -> SimpleIoNetwork.Availability.CLOSED;
                        case "signal_pin_missing", "unavailable", "hardware_mismatch" -> SimpleIoNetwork.Availability.HARDWARE_MISMATCH;
                        case "resource_limit" -> SimpleIoNetwork.Availability.RESOURCE_LIMIT;
                        default -> SimpleIoNetwork.Availability.MISSING;
                    });
                }
                ports.merge(physical, port, (previous, next) -> previous.availability() == SimpleIoNetwork.Availability.AVAILABLE ? next : previous);
            }
            wires.add(new SimpleIoNetwork.Wire(signalPin(config, wire.left()), signalPin(config, wire.right())));
        }
        return SimpleIoNetwork.sample(ports, wires);
    }
    private static SimpleIoNetwork.Pin signalPin(ClusterConfiguration config, SimpleIoWire.Pin pin) {
        return new SimpleIoNetwork.Pin(config.members().get(pin.member()).identity(), pin.pin());
    }
    private void observeSignals(ClusterConfiguration config, ClusterEnclosure geometry) {
        var frame = sampleSignals(config, geometry); var observed = new LinkedHashMap<>(wireDiagnostics);
        boolean show = signalIndicatorTick != level.getGameTime() && level.getGameTime()
            % CBConfigs.SERVER.biocomputing.xbusIndicatorInterval(CBConfigs.SERVER_SPEC.isLoaded()) == 0;
        if (show) signalIndicatorTick = level.getGameTime();
        observed.keySet().retainAll(config.wireNames());
        for (var entry : config.signals().entrySet()) {
            var wire = entry.getValue(); String code = "closed";
            if (wire.enabled()) {
                var left = frame.samples().get(signalPin(config, wire.left())); var right = frame.samples().get(signalPin(config, wire.right()));
                code = left.level().isPresent() && right.level().isPresent() ? "signal_" + left.level().getAsInt() + "_" + right.level().getAsInt()
                    : "signal_" + left.problems().stream().map(a -> a.name().toLowerCase(Locale.ROOT)).sorted().collect(java.util.stream.Collectors.joining("+"));
                if (show && left.level().isPresent() && right.level().isPresent()) {
                    // Reuse the world-visible beam asset as a periodic continuity indicator, not an ACK or message.
                    transferParticles(config.members().get(wire.left().member()).position(), config.members().get(wire.right().member()).position());
                }
            }
            // A continuity diagnostic is not a signal trace: never log the actual Simple I/O levels.
            String logCode = code.matches("signal_[0-9]+_[0-9]+") ? "ready" : code;
            observePortEvent("wire:" + entry.getKey(), logCode, logCode, entry.getKey());
            observed.put(entry.getKey(), code);
        }
        wireDiagnostics = Map.copyOf(observed);
    }
    /** Cosmetic only: marker types distinguish waits and use a server-configured display interval. */
    private void waitingParticles(BlockPos pos, BlockPos peer, XBusPortState.Mode mode) {
        if (!(level instanceof ServerLevel server)) return;
        var particle = switch (mode) {
            case READ -> CBParticleTypes.BIOCOMPUTING_READ_WAIT.get();
            case WRITE -> net.minecraft.core.particles.ParticleTypes.SMALL_FLAME;
            case READABLE -> net.minecraft.core.particles.ParticleTypes.COMPOSTER;
            default -> null;
        };
        if (particle == null) return;
        try {
            var point = SubLevelCompat.toWorld(level, pos, ClusterIndicatorGeometry.waitingPoint(pos, peer));
            server.sendParticles(particle, point.x, point.y, point.z, 1, 0, 0, 0, 0);
        } catch (RuntimeException failure) {
            if (!particleFailureLogged) LOGGER.warn("XBus wait particles failed at {}; communication is unchanged", worldPosition, failure);
            particleFailureLogged = true;
        }
    }
    private void transferParticles(BlockPos from, BlockPos to) {
        if (!(level instanceof ServerLevel server)) return;
        try {
            var start = SubLevelCompat.toWorld(level, from, Vec3.atCenterOf(from));
            var end = SubLevelCompat.toWorld(level, to, Vec3.atCenterOf(to));
            // Solid particles render before the translucent shell in Fast/Fancy. This existing
            // provider consumes a complete displacement, not velocity, and stops at the target.
            var displacement = end.subtract(start);
            server.sendParticles(CBParticleTypes.STRAIGHT_ENCHANT.get(), start.x, start.y, start.z,
                0, displacement.x, displacement.y, displacement.z, 1);
            particleFailureLogged = false;
        } catch (RuntimeException failure) {
            // The recipient already durably accepted the value. Cosmetics must not prevent
            // its acknowledgement, misreport delivery, or escape the controller's server tick.
            if (!particleFailureLogged)
                LOGGER.warn("Cluster interaction particles failed at {}; accepted communication is unchanged", worldPosition, failure);
            particleFailureLogged = true;
        }
    }
    @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (clientPacket) { tag.remove(ROOT); return; }
        if (invalidSave != null) { tag.put(ROOT, invalidSave.copy()); return; }
        var root = new CompoundTag(); root.putInt("Version", 1); root.putUUID("Controller", identity);
        var saved = router.write(); if (saved != null) root.put("Router", saved);
        tag.put(ROOT, root);
    }
    @Override protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (clientPacket) return;
        physicalTick = Long.MIN_VALUE;
        recentEvents.clear(); logPortStates.clear(); logStructureReady = null;
        structure.clear(); topologyValidation = null; structureReady = false; diagnostic = "unformed"; revision++; invalidSave = null; wireDiagnostics = Map.of();
        var saved = tag.get(ROOT);
        if (saved == null) { router.read(null); return; }
        if (!(saved instanceof CompoundTag root) || root.getInt("Version") != 1 || !root.hasUUID("Controller")
                || !Set.of("Version", "Controller", "Router").containsAll(root.getAllKeys())) { invalidSave = saved.copy(); return; }
        identity = root.getUUID("Controller"); router.read(root.get("Router"));
    }
    @Override public void invalidate() {
        if (level instanceof ServerLevel server) com.nobodiiiii.createbiotech.content.biocomputing.network.signal.CableSignalWorldTicks.invalidate(server, worldPosition);
        physicalTick = Long.MIN_VALUE; structure.clear(); topologyValidation = null; structureReady = false; wireDiagnostics = Map.of(); router.unload(); super.invalidate();
    }
    private static final class RouteFailure extends IllegalStateException { RouteFailure(String code) { super(code); } }
    public EndpointTransport.Result receiveInternal(BlockPos source, String output, MessageEnvelope message) {
        var geometry = enclosure().enclosure(); if (geometry.isEmpty()) return result(diagnostic);
        var state = communicationState(); if (state.isEmpty()) return result(diagnostic);
        var config = state.get().configuration();
        var named = config.members().entrySet().stream().filter(entry -> entry.getValue().position().equals(source)).findFirst();
        if (named.isEmpty()) return result("not_member");
        if (!message.source().equals(named.get().getValue().identity())) return result("identity_changed");
        var connection = config.connections().values().stream()
            .filter(c -> c.source().equals(named.get().getKey()) && c.output().equals(output)).findFirst();
        if (connection.isPresent()) return transferInternal(config, connection.get(), message, geometry.get());
        var route = config.ports().values().stream().filter(port -> port.member().equals(named.get().getKey()) && port.output().equals(output)).findFirst();
        if (route.isEmpty() || !route.get().enabled()) return result("closed");
        try {
            input(named.get().getValue(), route.get().input(), geometry.get());
            var admitted = router.receive(ClusterState.Side.INTERNAL, output, message, limits());
            diagnostic = admitted.name().toLowerCase(Locale.ROOT);
            if (admitted == AdmissionStatus.ACCEPTED) transferParticles(source, worldPosition);
            return new EndpointTransport.Result(diagnostic, Optional.of(admitted));
        } catch (RouteFailure failure) { return result(failure.getMessage()); }
    }
    /** Admission directly into the destination's existing durable inbox; the source retains its outbox until ACK. */
    private EndpointTransport.Result transferInternal(ClusterConfiguration config, ClusterConfiguration.Connection connection,
            MessageEnvelope message, ClusterEnclosure geometry) {
        if (!connection.enabled()) return result("closed");
        try {
            var source = config.members().get(connection.source()); var target = config.members().get(connection.target());
            var sender = member(source, geometry);
            var sourceEndpoint = sender.advertisement().orElseThrow(() -> new RouteFailure("closed"));
            input(source, sourceEndpoint.name(), geometry);
            var receiver = input(target, connection.input(), geometry);
            if (message.protocol() instanceof MessageProtocol.Request request) {
                String back = request.context().returnEndpoint();
                boolean returnRoute = config.connections().values().stream().anyMatch(c -> c.enabled() && c.source().equals(connection.target())
                    && c.target().equals(connection.source()) && c.output().equals(back) && c.input().equals(back));
                if (!sourceEndpoint.name().equals(back) || !returnRoute) return result("return_route_missing");
            }
            if (message.protocol() instanceof MessageProtocol.Reply reply && !reply.correlation().requester().equals(target.identity()))
                return result("identity_changed");
            var admitted = receiver.receive(connection.input(), message);
            diagnostic = admitted.name().toLowerCase(Locale.ROOT);
            if (admitted == AdmissionStatus.ACCEPTED) transferParticles(source.position(), target.position());
            return new EndpointTransport.Result(diagnostic, Optional.of(admitted));
        } catch (RouteFailure failure) { return result(failure.getMessage()); }
    }
    private EndpointTransport.Result result(String code) { diagnostic = code; return new EndpointTransport.Result(code, Optional.empty()); }
}
