package com.nobodiiiii.createbiotech.content.biocomputing.network;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;
import java.util.function.Supplier;

/** Menu-owned, one-shot selections. Client choices are indices/names, never coordinates or identity grants. */
public final class XBusWiringSession {
    interface Access {
        void authorize();
        UUID owner();
        default long sourceRevision() { return 0; }
        Discovery discover();
        XBusCableRoute.Result resolve(XBusCableTarget origin, XBusCableTarget target);
    }
    record Discovery(List<XBusCableTarget> local, List<XBusCableTarget> peers, String code) {
        Discovery { local = List.copyOf(local); peers = List.copyOf(peers); Objects.requireNonNull(code); }
    }
    private record Publication(long token, XBusWiringSavedData store, XBusWiringSavedData.View view, Discovery discovery, long sourceRevision) {}
    private final Access access;
    private final Supplier<XBusWiringSavedData> stores;
    private final Supplier<CommunicationNetworkLimits> limits;
    private final Supplier<ArchiveLimits> archives;
    // Presentation/selection scope only: the authoritative View, CAS and capacity remain device-wide.
    private final String sourceEndpoint;
    private Publication publication;
    private long lastToken, generation;

    XBusWiringSession(Access access, Supplier<XBusWiringSavedData> stores,
            Supplier<CommunicationNetworkLimits> limits, Supplier<ArchiveLimits> archives) {
        this(access, stores, limits, archives, null);
    }
    XBusWiringSession(Access access, Supplier<XBusWiringSavedData> stores,
            Supplier<CommunicationNetworkLimits> limits, Supplier<ArchiveLimits> archives, String sourceEndpoint) {
        this.access = Objects.requireNonNull(access); this.stores = Objects.requireNonNull(stores);
        this.limits = Objects.requireNonNull(limits); this.archives = Objects.requireNonNull(archives);
        this.sourceEndpoint = sourceEndpoint;
        if (sourceEndpoint != null) new EndpointConfiguration(sourceEndpoint, true);
    }
    public DataValue.RecordValue read(long token) {
        return read(token, archives.get());
    }
    public DataValue.RecordValue read(long token, ArchiveLimits replyLimits) {
        long operation = enter(token); publication = null;
        access.authorize(); var store = stores.get(); var view = store.view(access.owner());
        long sourceRevision = access.sourceRevision();
        var discovery = access.discover();
        if ((long)discovery.local().size() + discovery.peers().size() > limits.get().maxEndpoints()) fail("endpoint_limit");
        for (var local : discovery.local()) {
            if (!local.device().equals(view.device())) fail("identity_changed");
            if (!inScope(local)) fail("invalid_selection");
        }
        var next = new Publication(token, store, view, discovery, sourceRevision);
        var codes = new ArrayList<String>();
        for (var entry : visibleEntries(next)) codes.add(diagnose(entry));
        var reply = status(next, codes); encode(reply, replyLimits);
        requireOwner(next); checkGeneration(operation);
        if (!view.equals(store.view(view.device()))) fail("stale");
        publication = next; return reply;
    }
    public DataValue.RecordValue add(long token, long selectedView, String name, String localPin, int peerOption) {
        return add(token, selectedView, name, localPin, peerOption, archives.get());
    }
    public DataValue.RecordValue add(long token, long selectedView, String name, String localPin, int peerOption, ArchiveLimits replyLimits) {
        long operation = enter(token); var selected = take(selectedView);
        requireOwner(selected);
        var local = selected.discovery().local().stream().filter(p -> p.pin().equals(localPin)).toList();
        if (local.size() != 1 || peerOption < 0 || peerOption >= selected.discovery().peers().size()) fail("invalid_selection");
        var entry = new XBusWiringSavedData.Entry(name, local.getFirst(), selected.discovery().peers().get(peerOption));
        var binding = bindings(entry);
        var next = new Publication[1]; var reply = new DataValue.RecordValue[1];
        selected.store().add(selected.view(), name, entry.origin(), entry.peer(), limits.get().maxEndpoints() / 2, view -> {
            requireOwner(selected);
            next[0] = new Publication(token, selected.store(), view, selected.discovery(), selected.sourceRevision());
            reply[0] = status(next[0], Collections.nCopies(visibleEntries(next[0]).size(), "unchecked")); encode(reply[0], replyLimits);
            // Complete response preparation before the all-party barrier. Nothing invokes providers after it.
            requireReady(binding);
            if (stores.get() != selected.store()) fail("stale");
            checkGeneration(operation);
        });
        publication = next[0]; return reply[0];
    }
    public DataValue.RecordValue remove(long token, long selectedView, String name) {
        return remove(token, selectedView, name, archives.get());
    }
    public DataValue.RecordValue remove(long token, long selectedView, String name, ArchiveLimits replyLimits) {
        long operation = enter(token); var selected = take(selectedView); requireOwner(selected);
        if (sourceEndpoint != null && visibleEntries(selected).stream().noneMatch(entry -> entry.name().equals(name))) fail("invalid_selection");
        var next = new Publication[1]; var reply = new DataValue.RecordValue[1];
        selected.store().remove(selected.view(), name, view -> {
            next[0] = new Publication(token, selected.store(), view, selected.discovery(), selected.sourceRevision());
            reply[0] = status(next[0], Collections.nCopies(visibleEntries(next[0]).size(), "unchecked")); encode(reply[0], replyLimits);
            // A broken/removed peer must not prevent its owner deleting inactive declaration metadata.
            requireOwner(selected); checkGeneration(operation);
        });
        publication = next[0]; return reply[0];
    }
    public DataValue.RecordValue setEnabled(long token, long selectedView, String name, boolean enabled) {
        return setEnabled(token, selectedView, name, enabled, archives.get());
    }
    public DataValue.RecordValue setEnabled(long token, long selectedView, String name, boolean enabled, ArchiveLimits replyLimits) {
        long operation = enter(token); var selected = take(selectedView); requireOwner(selected);
        var entry = visibleEntries(selected).stream().filter(value -> value.name().equals(name)).findFirst()
            .orElseThrow(() -> invalid("invalid_selection"));
        // Disabling must remain possible while the peer is missing, closed or unloaded.
        var binding = enabled ? bindings(entry) : null;
        var next = new Publication[1]; var reply = new DataValue.RecordValue[1];
        selected.store().setEnabled(selected.view(), name, enabled, view -> {
            requireOwner(selected);
            next[0] = new Publication(token, selected.store(), view, selected.discovery(), selected.sourceRevision());
            reply[0] = status(next[0], Collections.nCopies(visibleEntries(next[0]).size(), "unchecked")); encode(reply[0], replyLimits);
            if (binding != null) requireReady(binding);
            requireOwner(selected); checkGeneration(operation);
        });
        publication = next[0]; return reply[0];
    }
    public void invalidate() { publication = null; generation++; }
    private long enter(long token) {
        if (token <= lastToken) fail("stale");
        lastToken = token; return ++generation;
    }
    private Publication take(long token) {
        var selected = publication; publication = null;
        if (selected == null || selected.token() != token) fail("stale");
        return selected;
    }
    private void checkGeneration(long expected) { if (generation != expected) fail("stale"); }
    private void requireOwner(Publication selected) {
        access.authorize();
        if (!access.owner().equals(selected.view().device())) fail("identity_changed");
        if (access.sourceRevision() != selected.sourceRevision()) fail("stale");
        if (stores.get() != selected.store()) fail("stale");
    }
    private XBusAccess.Network bindings(XBusWiringSavedData.Entry entry) {
        if (limits.get().maxEndpoints() < 2) fail("endpoint_limit");
        var left = resolved(entry.peer(), entry.origin()); var right = resolved(entry.origin(), entry.peer());
        return XBusAccess.network(List.of(left.access(), right.access()));
    }
    private XBusCableRoute.Pin resolved(XBusCableTarget origin, XBusCableTarget target) {
        XBusCableRoute.Result result;
        try { result = Objects.requireNonNull(access.resolve(origin, target)); }
        catch (RuntimeException | StackOverflowError failure) { throw invalid("provider_failure"); }
        if (result.pin().isEmpty()) fail(result.code().equals("ready") ? "provider_failure" : result.code());
        var pin = result.pin().orElseThrow();
        if (!result.code().equals("ready")) fail("provider_failure");
        if (!pin.target().equals(target) || !pin.observed().computer().equals(target.computer()) || !pin.observed().pin().equals(target.pin())) fail("identity_changed");
        return pin;
    }
    private static void requireReady(XBusAccess.Network network) {
        network.inspectBindings().ifPresent(code -> fail(code.name().toLowerCase(Locale.ROOT)));
    }
    private String diagnose(XBusWiringSavedData.Entry entry) {
        try { requireReady(bindings(entry)); return "ready"; }
        catch (PeerDiscovery.DiscoveryException failure) { return failure.getMessage(); }
    }
    private static void encode(DataValue.RecordValue reply, ArchiveLimits replyLimits) {
        try { ProgramEditorWire.encodeValue(reply, replyLimits); }
        catch (IllegalArgumentException tooLarge) { throw new PeerDiscovery.DiscoveryException("too_large", "wiring_response_too_large"); }
    }
    private boolean inScope(XBusCableTarget target) { return sourceEndpoint == null || sourceEndpoint.equals(target.endpoint()); }
    private List<XBusWiringSavedData.Entry> visibleEntries(Publication selected) {
        return selected.view().entries().stream().filter(entry -> inScope(entry.origin())).toList();
    }
    private DataValue.RecordValue status(Publication selected, List<String> codes) {
        var entries = new ArrayList<DataValue>();
        var visible = visibleEntries(selected);
        for (int index = 0; index < visible.size(); index++) {
            var entry = visible.get(index);
            entries.add(record(Map.of("name", text(entry.name()), "local_pin", text(entry.origin().pin()),
                "peer", pin(entry.peer()), "availability", text(codes.get(index)), "enabled", new DataValue.BooleanValue(entry.enabled()))));
        }
        return record(Map.of("wiring", record(Map.of("view", integer(selected.token()), "revision", integer(selected.view().revision()),
            "mode", text("declarations_only"), "discovery", text(selected.discovery().code()),
            "local_pins", new DataValue.ListValue(selected.discovery().local().stream().<DataValue>map(p -> text(p.pin())).toList()),
            "peer_pins", new DataValue.ListValue(selected.discovery().peers().stream().<DataValue>map(XBusWiringSession::pin).toList()),
            "entries", new DataValue.ListValue(entries)))));
    }
    private static DataValue.RecordValue pin(XBusCableTarget pin) {
        var pos = pin.position();
        return record(Map.of("endpoint", text(pin.endpoint()), "domain", text(pin.domain()), "pin", text(pin.pin()),
            "position", record(Map.of("x", integer(pos.getX()), "y", integer(pos.getY()), "z", integer(pos.getZ())))));
    }
    private static DataValue.RecordValue record(Map<String, DataValue> fields) { return new DataValue.RecordValue(fields); }
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
    private static PeerDiscovery.DiscoveryException invalid(String detail) { return new PeerDiscovery.DiscoveryException("invalid", detail); }
    private static void fail(String detail) { throw invalid(detail); }
}
