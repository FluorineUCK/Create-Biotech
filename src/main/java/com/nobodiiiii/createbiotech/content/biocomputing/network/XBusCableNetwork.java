package com.nobodiiiii.createbiotech.content.biocomputing.network;

import java.util.*;
import java.util.function.BiFunction;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusExchange;

/** Complete capture of one explicitly wired component; never a discovered available subset. */
public final class XBusCableNetwork {
    private XBusCableNetwork() {}
    public record End(XBusCableTarget target, Set<Direction> faces) {
        public End { Objects.requireNonNull(target); faces = Set.copyOf(faces); }
    }
    public record Link(End left, End right) {
        public Link { Objects.requireNonNull(left); Objects.requireNonNull(right); }
    }
    public enum Side { LEFT, RIGHT }
    public record Diagnostic(int link, Side side, String code) {
        public Diagnostic { if (link < 0) throw new IllegalArgumentException("Negative link"); Objects.requireNonNull(side); Objects.requireNonNull(code); }
    }
    public record Capture(String code, List<Diagnostic> diagnostics, Optional<XBusAccess.Network> network) {
        public Capture { Objects.requireNonNull(code); diagnostics = List.copyOf(diagnostics); Objects.requireNonNull(network); }
    }
    /**
     * Server coordinator only. Targets are fixed explicit bindings in this level, not player-entered read coordinates.
     * No owner or endpoint is force-loaded; all end routes must resolve before any group authority is returned.
     * This does not retire historical participants when the supplied configuration is edited.
     */
    public static Capture capture(ServerLevel level, List<Link> links) {
        Objects.requireNonNull(level);
        return capture(links, CBConfigs.SERVER.biocomputing.communication(CBConfigs.SERVER_SPEC.isLoaded()), resolver(level));
    }
    /** Complete enabled external component; branch changes by ANY owner revoke the captured component. */
    public static Capture captureEnabled(ServerLevel level, List<XBusWiringSavedData.EnabledConnection> connections) {
        Objects.requireNonNull(level);
        return captureEnabled(XBusWiringSavedData.get(level), connections,
            CBConfigs.SERVER.biocomputing.communication(CBConfigs.SERVER_SPEC.isLoaded()), resolver(level));
    }
    static BiFunction<End, End, XBusCableRoute.Result> resolver(ServerLevel level) {
        return (source, target) -> {
            var pos = source.target().position();
            if (!level.hasChunkAt(pos)) return failure("source_unloaded");
            if (!(level.getBlockEntity(pos) instanceof SmartBlockEntity owner)) return failure("not_found");
            return XBusCableRoute.resolve(owner, source.faces(), target.target());
        };
    }
    static Capture captureEnabled(XBusWiringSavedData store, List<XBusWiringSavedData.EnabledConnection> connections,
            CommunicationNetworkLimits limits, BiFunction<End, End, XBusCableRoute.Result> resolve) {
        Objects.requireNonNull(store); Objects.requireNonNull(connections); Objects.requireNonNull(limits); Objects.requireNonNull(resolve);
        if ((long)connections.size() * 2 > limits.maxEndpoints()) return rejected("endpoint_limit", List.of());
        var captured = List.copyOf(connections);
        if (captured.stream().anyMatch(connection -> !store.current(connection))) return rejected("stale", List.of());
        XBusWiringSavedData.ComponentView component;
        try { component = store.componentView(captured); }
        catch (IllegalArgumentException invalid) { return rejected(invalid.getMessage(), List.of()); }
        var faces = EnumSet.allOf(Direction.class);
        var links = captured.stream().map(connection -> new Link(new End(connection.entry().origin(), faces),
            new End(connection.entry().peer(), faces))).toList();
        // Network validation runs every provider callback before final proofs. Attaching the same proof to
        // each edge would needlessly rescan the component quadratically; the first edge is always included.
        return capture(links, limits, resolve, (index, access) -> index != 0 ? access : access.validated(() ->
            component.current() ? Optional.empty() : Optional.of(XBusExchange.Status.STALE)));
    }
    static Capture capture(List<Link> links, CommunicationNetworkLimits limits, BiFunction<End, End, XBusCableRoute.Result> resolve) {
        return capture(links, limits, resolve, (index, access) -> access);
    }
    private static Capture capture(List<Link> links, CommunicationNetworkLimits limits, BiFunction<End, End, XBusCableRoute.Result> resolve,
            BiFunction<Integer, XBusAccess, XBusAccess> validateDeclaration) {
        Objects.requireNonNull(links); Objects.requireNonNull(limits); Objects.requireNonNull(resolve);
        // Bound resolver work including duplicate/alias guards, before copying or invoking providers.
        if ((long)links.size() * 2 > limits.maxEndpoints()) return rejected("endpoint_limit", List.of());
        links = List.copyOf(links);
        if (links.isEmpty()) return rejected("invalid_configuration", List.of());
        var canonical = new LinkedHashSet<XBusNetwork.Pin>(); var edges = new ArrayList<XBusNetwork.Link>();
        for (var link : links) {
            var left = pin(link.left()); var right = pin(link.right());
            canonical.add(left); canonical.add(right); edges.add(new XBusNetwork.Link(left, right));
        }
        if (new XBusNetwork(List.copyOf(canonical), edges).components().size() != 1)
            return rejected("disconnected_configuration", List.of());
        var access = new ArrayList<XBusAccess>(); var diagnostics = new ArrayList<Diagnostic>();
        String rejection = null;
        for (int i = 0; i < links.size(); i++) {
            var link = links.get(i);
            var left = resolve(resolve, link.right(), link.left()); var right = resolve(resolve, link.left(), link.right());
            diagnostics.add(new Diagnostic(i, Side.LEFT, left.code())); diagnostics.add(new Diagnostic(i, Side.RIGHT, right.code()));
            if (rejection == null && left.pin().isEmpty()) rejection = left.code();
            if (rejection == null && right.pin().isEmpty()) rejection = right.code();
            int index = i;
            left.pin().ifPresent(p -> access.add(validateDeclaration.apply(index, p.access())));
            right.pin().ifPresent(p -> access.add(validateDeclaration.apply(index, p.access())));
        }
        if (rejection != null) return rejected(rejection, diagnostics);
        var network = XBusAccess.network(access); var inspection = network.inspect();
        if (inspection.rejection().filter(s -> s != XBusExchange.Status.RECOVERY_REQUIRED).isPresent())
            return rejected(inspection.rejection().orElseThrow().name().toLowerCase(Locale.ROOT), diagnostics);
        return new Capture(inspection.rejection().map(s -> s.name().toLowerCase(Locale.ROOT)).orElse("ready"), diagnostics, Optional.of(network));
    }
    static XBusCableRoute.Result resolve(BiFunction<End, End, XBusCableRoute.Result> resolve, End source, End target) {
        try {
            var result = Objects.requireNonNull(resolve.apply(source, target));
            if (result.pin().isEmpty()) return result.code().equals("ready") ? failure("provider_failure") : result;
            var captured = result.pin().orElseThrow();
            if (!result.code().equals("ready")) return failure("provider_failure");
            if (!captured.target().equals(target.target()) || !captured.observed().computer().equals(target.target().computer())
                    || !captured.observed().pin().equals(target.target().pin())) return failure("identity_changed");
            return result;
        } catch (RuntimeException | StackOverflowError failure) { return failure("provider_failure"); }
    }
    private static XBusNetwork.Pin pin(End end) { return new XBusNetwork.Pin(end.target().computer(), end.target().pin()); }
    private static XBusCableRoute.Result failure(String code) { return new XBusCableRoute.Result(code, Optional.empty()); }
    private static Capture rejected(String code, List<Diagnostic> diagnostics) {
        return new Capture(code, diagnostics, Optional.empty());
    }
}
