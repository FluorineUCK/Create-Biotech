package com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu;

import com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.WiringControlData;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.WiringMenu;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.BrowserLiteralDisplay;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.network.CBPackets;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.nobodiiiii.createbiotech.registry.CBMenuTypes;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import java.util.*;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** Authority-owning physical Cluster configuration menu. */
public final class ClusterControllerMenu extends AbstractContainerMenu implements WiringMenu, com.nobodiiiii.createbiotech.content.biocomputing.diagnostics.RecentEventMenu {
    private final UUID nonce;
    @Nullable private final ClusterControllerBlockEntity serverOwner;
    @Nullable private final ServerPlayer serverPlayer;
    @Nullable private final ClusterControlSession session;
    private long latestIntent, responseVersion;
    private Optional<ClusterControlResponse> response = Optional.empty();
    private Optional<ClusterControlView> view = Optional.empty();
    @Nullable private XBusWiringSession wiringSession;
    @Nullable private ClusterControlSession.Action latestAction;
    @Nullable private ClusterWiringSources wiringSources;
    @Nullable private String wiringEndpoint;
    @Nullable private DataValue.RecordValue wiringStatus;
    @Nullable private DataValue.RecordValue logStatus;

    public ClusterControllerMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, buffer.readUUID(), null, null);
    }
    private ClusterControllerMenu(int containerId, UUID nonce, @Nullable ClusterControllerBlockEntity owner, @Nullable ServerPlayer player) {
        super(CBMenuTypes.BIOCOMPUTING_CLUSTER.get(), containerId);
        this.nonce = Objects.requireNonNull(nonce); serverOwner = owner; serverPlayer = player;
        session = owner == null ? null : new ClusterControlSession(nonce);
    }
    static boolean acceptsResponse(int containerId, UUID nonce, long latestIntent, ClusterControlResponse incoming) {
        return incoming.containerId() == containerId && incoming.nonce().equals(nonce) && incoming.intent() == latestIntent;
    }
    public long responseVersion() { return responseVersion; }
    public Optional<ClusterControlResponse> response() { return response.filter(reply -> reply.intent() == latestIntent); }
    public Optional<ClusterControlView> view() { return view; }
    @Override public void requestLog() {
        if (serverOwner != null) return;
        latestAction = ClusterControlSession.Action.READ_LOG; logStatus = null;
        CBPackets.sendToServer(new ClusterControlRequest(containerId, nonce, ++latestIntent,
            response.map(ClusterControlResponse::revision).orElse(0L), latestAction, new byte[0]));
    }
    @Override public long logResponseVersion() { return responseVersion; }
    @Override public Optional<com.nobodiiiii.createbiotech.content.biocomputing.diagnostics.RecentEventMenu.Reply> logResponse() {
        if (latestAction != ClusterControlSession.Action.READ_LOG) return Optional.empty();
        return response().map(reply -> new com.nobodiiiii.createbiotech.content.biocomputing.diagnostics.RecentEventMenu.Reply(reply.code(), Optional.ofNullable(logStatus)));
    }
    public Optional<ClusterWiringSources> wiringSources() {
        return latestAction == ClusterControlSession.Action.LIST_WIRING && response.filter(reply -> reply.intent() == latestIntent && reply.code().equals("ok")).isPresent()
            ? Optional.ofNullable(wiringSources) : Optional.empty();
    }
    public void listWiringSources() { requestWiring(ClusterControlSession.Action.LIST_WIRING, new DataValue.RecordValue(Map.of())); }
    public void selectWiringSource(String endpoint) {
        new EndpointConfiguration(endpoint, true);
        if (wiringSources == null || !wiringSources.endpoints().containsKey(endpoint)) throw new IllegalArgumentException("invalid_selection");
        wiringEndpoint = endpoint;
    }
    @Override public long wiringResponseVersion() { return responseVersion; }
    @Override public Optional<WiringMenu.Reply> wiringResponse() {
        if (latestAction == null || !latestAction.wiring() || latestAction == ClusterControlSession.Action.LIST_WIRING) return Optional.empty();
        return response.filter(reply -> reply.intent() == latestIntent).map(reply -> new WiringMenu.Reply(reply.intent(), reply.code(), reply.detail(),
            reply.code().equals("ok") ? Optional.ofNullable(wiringStatus) : Optional.empty()));
    }
    @Override public Optional<Component> wiringContext() {
        if (wiringSources == null || wiringEndpoint == null) return Optional.empty();
        var pos = wiringSources.position();
        return Optional.of(Component.translatable("create_biotech.biocomputing.wiring.controller_source",
            BrowserLiteralDisplay.quote(wiringEndpoint), pos.getX(), pos.getY(), pos.getZ()));
    }
    @Override public void wiringControl(WiringMenu.Action action, DataValue.RecordValue inputs) {
        if (action == WiringMenu.Action.READ) {
            if (wiringEndpoint == null) throw new IllegalStateException("select a public source before reading");
            requestWiring(ClusterControlSession.Action.READ_WIRING, new DataValue.RecordValue(Map.of("endpoint", new DataValue.TextValue(wiringEndpoint))));
        } else requestWiring(switch (action) {
            case ADD -> ClusterControlSession.Action.ADD_WIRING;
            case REMOVE -> ClusterControlSession.Action.REMOVE_WIRING;
            case SET_ENABLED -> ClusterControlSession.Action.SET_WIRING_ENABLED;
            default -> throw new IllegalArgumentException("invalid wiring action");
        }, inputs);
    }
    @Override public void leaveWiring() { listWiringSources(); }
    private void requestWiring(ClusterControlSession.Action action, DataValue.RecordValue inputs) {
        if (serverOwner != null || response.isEmpty()) return;
        var authority = response.orElseThrow(); long intent = ++latestIntent; latestAction = action; wiringStatus = null;
        if (action == ClusterControlSession.Action.LIST_WIRING) { wiringSources = null; wiringEndpoint = null; }
        try {
            byte[] encoded = action.requiresValue() ? ProgramEditorWire.encodeValue(inputs, authority.archiveLimits()) : new byte[0];
            CBPackets.sendToServer(new ClusterControlRequest(containerId, nonce, intent, authority.revision(), action, encoded));
        } catch (IllegalArgumentException invalid) {
            accept(new ClusterControlResponse(containerId, nonce, intent, authority.revision(), "invalid", "invalid_selection", authority.archiveLimits(), new byte[0]));
        }
    }
    public void openComputer(BlockPos target) {
        Objects.requireNonNull(target);
        if (serverOwner != null || response.isEmpty()) return;
        var authority = response.orElseThrow(); long intent = ++latestIntent;
        latestAction = ClusterControlSession.Action.OPEN_COMPUTER;
        try {
            CBPackets.sendToServer(new ClusterControlRequest(containerId, nonce, intent, authority.revision(),
                ClusterControlSession.Action.OPEN_COMPUTER, ClusterControlWire.encodeComputerPosition(target, authority.archiveLimits())));
        } catch (IllegalArgumentException invalid) {
            accept(new ClusterControlResponse(containerId, nonce, intent, authority.revision(), "invalid",
                trim(String.valueOf(invalid.getMessage())), authority.archiveLimits(), new byte[0]));
        }
    }
    public void requestRefresh() {
        if (serverOwner != null) return;
        latestAction = ClusterControlSession.Action.REFRESH;
        CBPackets.sendToServer(new ClusterControlRequest(containerId, nonce, ++latestIntent,
            response.map(ClusterControlResponse::revision).orElse(0L), ClusterControlSession.Action.REFRESH, new byte[0]));
    }
    public void configure(ClusterConfiguration configuration, long draftRevision) {
        Objects.requireNonNull(configuration);
        if (serverOwner != null || response.isEmpty()) return;
        var authority = response.orElseThrow(); long intent = ++latestIntent;
        latestAction = ClusterControlSession.Action.CONFIGURE;
        try {
            byte[] encoded = ClusterControlWire.encodeConfiguration(configuration, authority.archiveLimits());
            CBPackets.sendToServer(new ClusterControlRequest(containerId, nonce, intent, draftRevision,
                ClusterControlSession.Action.CONFIGURE, encoded));
        } catch (IllegalArgumentException invalid) {
            accept(new ClusterControlResponse(containerId, nonce, intent, authority.revision(), "invalid",
                trim(String.valueOf(invalid.getMessage())), authority.archiveLimits(), new byte[0]));
        }
    }
    public void accept(ClusterControlResponse incoming) {
        Objects.requireNonNull(incoming);
        if (!acceptsResponse(containerId, nonce, latestIntent, incoming)) return;
        response = Optional.of(incoming); responseVersion++;
        logStatus = null;
        if (latestAction == ClusterControlSession.Action.READ_LOG) {
            if (incoming.state().length > 0 && incoming.code().equals("ok")) try {
                var value = ProgramEditorWire.decodeValue(incoming.state(), incoming.archiveLimits());
                com.nobodiiiii.createbiotech.content.biocomputing.diagnostics.RecentEventWire.readStatus(value);
                logStatus = (DataValue.RecordValue)value;
            } catch (IllegalArgumentException invalid) { logStatus = null; }
            return; // A diagnostic refresh must not replace a configuration view or a draft.
        }
        wiringStatus = null;
        if (latestAction != null && latestAction.wiring()) {
            if (latestAction == ClusterControlSession.Action.LIST_WIRING) wiringSources = null;
            if (incoming.state().length == 0 || !incoming.code().equals("ok")) return;
            try {
                var decoded = ProgramEditorWire.decodeValue(incoming.state(), incoming.archiveLimits());
                if (latestAction == ClusterControlSession.Action.LIST_WIRING) {
                    var sources = ClusterWiringSources.decode(decoded);
                    if (sources.view() != incoming.intent()) throw new IllegalArgumentException("stale");
                    wiringSources = sources;
                } else if (decoded instanceof DataValue.RecordValue record) wiringStatus = record;
            } catch (IllegalArgumentException malformed) { wiringStatus = null; }
            return;
        }
        wiringSources = null; wiringEndpoint = null;
        if (incoming.state().length == 0) return;
        try { view = Optional.of(ClusterControlWire.decodeView(incoming.state(), incoming.archiveLimits())); }
        catch (IllegalArgumentException malformed) { view = Optional.empty(); }
    }
    public void handle(ServerPlayer player, ClusterControlRequest request) {
        if (player != serverPlayer || player.containerMenu != this || request.containerId() != containerId
            || !request.nonce().equals(nonce) || !stillValid(player) || serverOwner == null || session == null) return;
        var limits = limits();
        var wiringReply = new byte[][] {new byte[0]};
        try {
            var result = session.apply(request.nonce(), request.intent(), request.revision(), request.action(),
                serverOwner::configurationRevision, () -> {
                    if (!request.action().wiring()) invalidateWiring();
                    return switch (request.action()) {
                    case REFRESH -> "ready";
                    case READ_LOG -> {
                        wiringReply[0] = ProgramEditorWire.encodeValue(
                            com.nobodiiiii.createbiotech.content.biocomputing.diagnostics.RecentEventWire.statusValue(serverOwner.recentEvents(), limits.archive()), limits.archive());
                        yield "ok";
                    }
                    case CONFIGURE -> configure(player, ClusterControlWire.decodeConfiguration(request.value(), limits.archive()), limits.archive());
                    case OPEN_COMPUTER -> openComputer(player, ClusterControlWire.decodeComputerPosition(request.value(), limits.archive()));
                    case READ_WIRING, ADD_WIRING, REMOVE_WIRING, LIST_WIRING, SET_WIRING_ENABLED -> {
                        wiringReply[0] = ProgramEditorWire.encodeValue(wiring(request, limits.archive()), limits.archive());
                        yield "ok";
                    }
                }; });
            result.ifPresent(answer -> {
                if (!answer.code().equals("ok")) invalidateWiring();
                if (player.containerMenu != this) return;
                if (request.action().wiring() || request.action() == ClusterControlSession.Action.READ_LOG) sendWiring(player, request, answer.code(), "", limits, wiringReply[0]);
                else send(player, request.intent(), answer.code(), answer.revision(), limits);
            });
        } catch (PeerDiscovery.DiscoveryException invalid) {
            invalidateWiring();
            sendWiring(player, request, invalid.code(), trim(invalid.getMessage()), limits, new byte[0]);
        } catch (IllegalArgumentException invalid) {
            invalidateWiring();
            if (request.action().wiring() || request.action() == ClusterControlSession.Action.READ_LOG) sendWiring(player, request, "invalid", wiringFailure(invalid), limits, new byte[0]);
            else send(player, request.intent(), "invalid", serverOwner.configurationRevision(), limits, trim(String.valueOf(invalid.getMessage())));
        }
    }
    private DataValue.RecordValue wiring(ClusterControlRequest request,
            com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits archive) {
        if (request.action() == ClusterControlSession.Action.LIST_WIRING) {
            invalidateWiring();
            var names = new TreeMap<String, Boolean>();
            XBusWiringSavedData.get(serverPlayer.serverLevel()).view(serverOwner.controllerId()).entries()
                .forEach(entry -> names.put(entry.origin().endpoint(), false));
            serverOwner.routingState().ifPresent(state -> state.configuration().publicPins().keySet().forEach(name -> names.put(name, true)));
            var value = new ClusterWiringSources(request.intent(), serverOwner.getBlockPos(), names).encode();
            try { ProgramEditorWire.encodeValue(value, archive); }
            catch (IllegalArgumentException tooLarge) { throw new PeerDiscovery.DiscoveryException("too_large", "wiring_response_too_large"); }
            return value;
        }
        var decoded = ProgramEditorWire.decodeValue(request.value(), archive);
        if (!(decoded instanceof DataValue.RecordValue inputs)) throw new IllegalArgumentException("invalid_selection");
        if (request.action() == ClusterControlSession.Action.READ_WIRING) {
            invalidateWiring();
            if (!inputs.fields().keySet().equals(Set.of("endpoint")) || !(inputs.field("endpoint") instanceof DataValue.TextValue endpoint))
                throw new IllegalArgumentException("invalid_selection");
            wiringSession = XBusWiringService.open(serverOwner, endpoint.value(), serverPlayer,
                () -> serverPlayer.containerMenu == this && stillValid(serverPlayer));
            return wiringSession.read(request.intent(), archive);
        }
        if (wiringSession == null) throw new PeerDiscovery.DiscoveryException("invalid", "stale");
        if (request.action() == ClusterControlSession.Action.ADD_WIRING) {
            var add = WiringControlData.Add.decode(inputs);
            return wiringSession.add(request.intent(), add.view(), add.name(), add.localPin(), add.peer(), archive);
        }
        if (request.action() == ClusterControlSession.Action.SET_WIRING_ENABLED) {
            var enabled = WiringControlData.SetEnabled.decode(inputs);
            return wiringSession.setEnabled(request.intent(), enabled.view(), enabled.name(), enabled.enabled(), archive);
        }
        var remove = WiringControlData.Remove.decode(inputs);
        return wiringSession.remove(request.intent(), remove.view(), remove.name(), archive);
    }
    private void sendWiring(ServerPlayer player, ClusterControlRequest request, String code, String detail, ClusterLimits limits, byte[] bytes) {
        CBPackets.sendToPlayer(new ClusterControlResponse(containerId, nonce, request.intent(), serverOwner.configurationRevision(),
            code, detail, limits.archive(), bytes), player);
    }
    private void invalidateWiring() {
        if (wiringSession != null) wiringSession.invalidate();
        wiringSession = null;
    }
    private static String wiringFailure(IllegalArgumentException failure) {
        // Storage/protocol diagnostics are public codes; arbitrary exception text is not a wire value.
        String code = failure.getMessage();
        return code != null && Set.of("invalid_save", "capacity", "duplicate_name", "not_found", "revision_exhausted", "stale", "invalid_selection").contains(code)
            ? code : "invalid_selection";
    }
    @Override public void removed(Player player) { super.removed(player); invalidateWiring(); }
    private String openComputer(ServerPlayer actor, BlockPos target) {
        var owner = ClusterMemberEditorAccess.resolve(serverOwner, target, actor);
        var provider = com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramMenu.provider(
            new ClusterMemberEditorAccess(serverOwner, owner));
        return actor.openMenu(provider, provider::writeOpeningData).isPresent() ? "opened" : "unavailable";
    }
    private String configure(ServerPlayer actor, ClusterConfiguration configuration,
        com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits archiveLimits) {
        var view = publicView(actor);
        var failure = configurationFailure(configuration, view.candidates());
        if (failure.isPresent()) return failure.orElseThrow();
        failure = preflight(configuration, view.candidates(), "configured", view.queued(), view.pending(), archiveLimits);
        if (failure.isPresent()) return failure.orElseThrow();
        return serverOwner.configure(configuration, actor);
    }
    static Optional<String> configurationFailure(ClusterConfiguration configuration, List<ClusterControlView.Candidate> candidates) {
        Objects.requireNonNull(configuration); Objects.requireNonNull(candidates);
        for (var member : configuration.members().values()) {
            var candidate = candidates.stream().filter(value -> value.position().equals(member.position())
                && value.identity().filter(member.identity()::equals).isPresent()).findFirst();
            if (candidate.isEmpty()) return Optional.of("member_missing");
            if (!candidate.get().selectable()) return Optional.of(candidate.get().diagnostic());
        }
        for (var port : configuration.ports().values()) {
            if (!port.enabled()) continue;
            var member = configuration.members().get(port.member());
            var candidate = candidates.stream().filter(value -> value.position().equals(member.position())
                && value.identity().filter(member.identity()::equals).isPresent()).findFirst().orElseThrow();
            if (!candidate.endpoints().contains(port.input()))
                return Optional.of(candidate.endpoints().isEmpty() ? "closed" : "endpoint_changed");
        }
        for (var connection : configuration.connections().values()) {
            if (!connection.enabled()) continue;
            var target = configuration.members().get(connection.target());
            var candidate = candidates.stream().filter(value -> value.position().equals(target.position())
                && value.identity().filter(target.identity()::equals).isPresent()).findFirst().orElseThrow();
            if (!candidate.endpoints().contains(connection.input()))
                return Optional.of(candidate.endpoints().isEmpty() ? "closed" : "endpoint_changed");
        }
        for (var wire : configuration.wires().values()) {
            if (!wire.enabled()) continue;
            for (var pin : List.of(wire.left(), wire.right())) {
                var member = configuration.members().get(pin.member());
                var candidate = candidates.stream().filter(value -> value.position().equals(member.position())
                    && value.identity().filter(member.identity()::equals).isPresent()).findFirst().orElseThrow();
                if (!candidate.endpoints().contains(pin.endpoint()))
                    return Optional.of(candidate.endpoints().isEmpty() ? "closed" : "endpoint_changed");
            }
        }
        for (var wire : configuration.signals().values()) {
            if (!wire.enabled()) continue;
            for (var pin : List.of(wire.left(), wire.right())) {
                var member = configuration.members().get(pin.member());
                var candidate = candidates.stream().filter(value -> value.position().equals(member.position())
                    && value.identity().filter(member.identity()::equals).isPresent()).findFirst().orElseThrow();
                if (!candidate.endpoints().contains(pin.endpoint()))
                    return Optional.of(candidate.endpoints().isEmpty() ? "closed" : "endpoint_changed");
            }
        }
        for (var mapping : configuration.publicPins().values()) {
            if (!mapping.enabled()) continue;
            var target = mapping.target(); var member = configuration.members().get(target.member());
            var candidate = candidates.stream().filter(value -> value.position().equals(member.position())
                && value.identity().filter(member.identity()::equals).isPresent()).findFirst().orElseThrow();
            if (!candidate.endpoints().contains(target.endpoint())) return Optional.of(candidate.endpoints().isEmpty() ? "closed" : "endpoint_changed");
        }
        return Optional.empty();
    }
    static Optional<String> preflight(ClusterConfiguration configuration, List<ClusterControlView.Candidate> candidates,
        String diagnostic, int queued, int pending, com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits limits) {
        try {
            // Reserve a distinct, longer-than-status value per wire: a shared placeholder would
            // be pooled by the packet codec and underestimate different live statuses in bytes.
            // Status codes carry no free-form exception detail or payload.
            var wires = new LinkedHashMap<String, String>();
            configuration.wires().keySet().forEach(name -> wires.put(name, XBusWire.UNSUPPORTED_TOPOLOGY + ":" + name));
            configuration.signals().keySet().forEach(name -> wires.put(name, "signal_hardware_mismatch+unloaded+closed+missing+resource_limit:" + name));
            var pins = new LinkedHashMap<String, String>();
            configuration.publicPins().keySet().forEach(name -> pins.put(name, "public_pin_missing+controller_unloaded+identity_changed:" + name));
            ClusterControlWire.encodeView(new ClusterControlView(configuration, candidates, diagnostic, queued, pending, wires, pins), limits);
            return Optional.empty();
        } catch (IllegalArgumentException tooLarge) { return Optional.of("too_large"); }
    }
    private void send(ServerPlayer player, long intent, String code, long revision, ClusterLimits limits) {
        send(player, intent, code, revision, limits, serverOwner == null ? "unavailable" : serverOwner.diagnosticCode());
    }
    private void send(ServerPlayer player, long intent, String code, long revision, ClusterLimits limits, String detail) {
        byte[] state = new byte[0]; String result = code;
        try { state = ClusterControlWire.encodeView(publicView(player), limits.archive()); }
        catch (IllegalArgumentException tooLarge) { result = "too_large"; detail = String.valueOf(tooLarge.getMessage()); }
        CBPackets.sendToPlayer(new ClusterControlResponse(containerId, nonce, intent, revision, result,
            trim(detail), limits.archive(), state), player);
    }
    private ClusterControlView publicView(ServerPlayer actor) {
        var routing = serverOwner.routingState();
        var configuration = routing.map(ClusterState::configuration).orElseGet(ClusterConfiguration::empty);
        int queued = routing.map(state -> state.mailbox().queued().size()).orElse(0);
        int pending = routing.map(state -> state.pending().size()).orElse(0);
        var enclosure = serverOwner.enclosure();
        var candidates = candidates(actor, enclosure.enclosure(), configuration);
        var wires = new LinkedHashMap<String, String>();
        serverOwner.wireDiagnostics().forEach((name, code) -> { if (configuration.wireNames().contains(name)) wires.put(name, code); });
        return new ClusterControlView(configuration, candidates, serverOwner.diagnosticCode(), queued, pending, wires, serverOwner.publicPinDiagnostics());
    }
    private List<ClusterControlView.Candidate> candidates(ServerPlayer actor, Optional<ClusterEnclosure> enclosure,
        ClusterConfiguration configuration) {
        ServerLevel level = actor.serverLevel(); var result = new ArrayList<ClusterControlView.Candidate>();
        var positions = new LinkedHashMap<BlockPos, Boolean>();
        enclosure.ifPresent(value -> value.contacts().keySet().forEach(position -> positions.put(position, value.containsMember(position))));
        configuration.members().values().forEach(member -> positions.putIfAbsent(member.position(), false));
        positions.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparingLong(BlockPos::asLong)))
            .forEach(entry -> result.add(candidate(level, actor, entry.getKey(), entry.getValue())));
        return List.copyOf(result);
    }
    private ClusterControlView.Candidate candidate(ServerLevel level, ServerPlayer actor, BlockPos position, boolean contacting) {
        Optional<com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHardwareView> hardware = Optional.empty();
        String diagnostic = "ready"; Optional<UUID> identity = Optional.empty(); List<String> endpoints = List.of(); boolean selectable = false, editable = false;
        if (!level.hasChunkAt(position)) diagnostic = "member_unloaded";
        else if (!SubLevelCompat.sameSpace(level, serverOwner.getBlockPos(), position)) diagnostic = "wrong_space";
        else if (!level.getWorldBorder().isWithinBounds(position) || !level.mayInteract(actor, position)) diagnostic = "access_denied";
        else if (!(level.getBlockEntity(position) instanceof SmartBlockEntity smart)) diagnostic = "member_missing";
        else {
            var computer = smart.getBehaviour(ComputerBehaviour.TYPE);
            if (computer != null && !smart.isRemoved()) hardware = Optional.of(
                com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHardwareView.from(computer.hardware()));
            editable = contacting && !smart.isRemoved() && computer != null;
            if (computer == null) diagnostic = "member_missing";
            else if (!computer.peripheralAvailable()) diagnostic = "unavailable";
            else {
                identity = computer.communicationIdentity();
                endpoints = computer.advertisements().stream().filter(endpoint -> endpoint.enabled()).map(endpoint -> endpoint.name()).toList();
                if (identity.isEmpty()) diagnostic = computer.missingCommunicationIdentityCode();
                else if (!contacting) diagnostic = "not_contacting";
                else {
                    var attachment = computer.clusterAttachment();
                    if (attachment.filter(value -> !value.controller().equals(serverOwner.controllerId())).isPresent()) {
                        var old = attachment.orElseThrow();
                        if (!level.hasChunkAt(old.position())) diagnostic = "controller_unloaded";
                        else if (level.getBlockEntity(old.position()) instanceof ClusterControllerBlockEntity controller
                            && controller.controllerId().equals(old.controller())) diagnostic = "already_bound";
                        else selectable = true;
                    } else selectable = true;
                }
            }
        }
        return new ClusterControlView.Candidate(position, identity, endpoints, contacting, selectable, editable, diagnostic, hardware);
    }
    private static ClusterLimits limits() { return CBConfigs.SERVER.biocomputing.cluster(CBConfigs.SERVER_SPEC.isLoaded()); }
    @Override public boolean stillValid(Player player) {
        if (serverOwner == null) return true;
        if (player != serverPlayer || !(serverOwner.getLevel() instanceof ServerLevel level) || player.level() != level
            || serverOwner.isRemoved()) return false;
        var pos = serverOwner.getBlockPos();
        return level.hasChunkAt(pos) && level.getBlockEntity(pos) == serverOwner
            && level.getWorldBorder().isWithinBounds(pos) && level.mayInteract(player, pos)
            && player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) <= 64;
    }
    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    public static OpeningProvider provider(ClusterControllerBlockEntity owner) { return new OpeningProvider(owner); }
    public static final class OpeningProvider implements MenuProvider {
        private final ClusterControllerBlockEntity owner; private final UUID nonce = UUID.randomUUID();
        private OpeningProvider(ClusterControllerBlockEntity owner) { this.owner = Objects.requireNonNull(owner); }
        @Override public Component getDisplayName() { return Component.translatable("create_biotech.biocomputing.cluster.title"); }
        @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
            if (!(player instanceof ServerPlayer server)) throw new IllegalArgumentException("server player required");
            return new ClusterControllerMenu(id, nonce, owner, server);
        }
        public void writeOpeningData(RegistryFriendlyByteBuf buffer) { buffer.writeUUID(nonce); }
    }
    private static String trim(String value) {
        if (value == null) return "";
        return value.length() <= ClusterControlResponse.MAX_DETAIL ? value : value.substring(0, ClusterControlResponse.MAX_DETAIL);
    }
}
