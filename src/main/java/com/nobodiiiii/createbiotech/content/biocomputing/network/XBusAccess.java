package com.nobodiiiii.createbiotech.content.biocomputing.network;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusExchange;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.function.Supplier;
import java.util.function.IntSupplier;

/** Transient rendezvous access; no host, locals or payload are exposed to its caller. */
public final class XBusAccess {
    private record Target(ComputerBlockHost host, XBusPortState expected, Supplier<ComputerHostLimits> limits) {
        private Target { Objects.requireNonNull(host); Objects.requireNonNull(expected); Objects.requireNonNull(limits); }
    }
    private final Optional<Target> target;
    private final Supplier<Optional<XBusExchange.Status>> rejection;
    private record PhysicalRoute(Supplier<Optional<XBusExchange.Status>> authorize, Supplier<Optional<XBusExchange.Status>> physical) {}
    private final List<PhysicalRoute> routes;
    private final List<Supplier<Optional<XBusExchange.Status>>> validations;
    private record History(XBusHistorySavedData store, IntSupplier computers, IntSupplier claims) {}
    private final Optional<History> history;

    /** Provider-only construction. The guard must revalidate ownership, grant and the exact observation. */
    public XBusAccess(ComputerBlockHost host, XBusPortState expected, Supplier<ComputerHostLimits> limits,
            Supplier<Optional<XBusExchange.Status>> rejection) {
        this(Optional.of(new Target(host, expected, limits)), rejection, List.of(), List.of(), Optional.empty());
    }
    private XBusAccess(Optional<Target> target, Supplier<Optional<XBusExchange.Status>> rejection, List<PhysicalRoute> routes,
            List<Supplier<Optional<XBusExchange.Status>>> validations, Optional<History> history) {
        this.target = Objects.requireNonNull(target); this.rejection = Objects.requireNonNull(rejection);
        this.routes = List.copyOf(routes);
        this.validations = List.copyOf(validations);
        this.history = history;
    }
    /** A supported gateway can reject a missing/revoked mapping without inventing a recipient host. */
    public static XBusAccess denied(XBusExchange.Status reason) {
        Objects.requireNonNull(reason);
        if (reason == XBusExchange.Status.EXCHANGED || reason == XBusExchange.Status.READABLE || reason == XBusExchange.Status.PREPARED)
            throw new IllegalArgumentException("An access denial cannot report progress");
        return new XBusAccess(Optional.empty(), () -> Optional.of(reason), List.of(), List.of(), Optional.empty());
    }

    public XBusExchange.Status exchange(XBusAccess peer) {
        return exchange(peer, false);
    }
    private XBusExchange.Status exchange(XBusAccess peer, boolean reclaimed) {
        Objects.requireNonNull(peer);
        Target a, b; ComputerHostLimits leftLimits, rightLimits; History journal; int computers, claims;
        try {
            var left = Objects.requireNonNull(rejection.get()); if (left.isPresent()) return left.orElseThrow();
            var right = Objects.requireNonNull(peer.rejection.get()); if (right.isPresent()) return right.orElseThrow();
            a = target.orElseThrow(); b = peer.target.orElseThrow();
            leftLimits = Objects.requireNonNull(a.limits().get()); rightLimits = Objects.requireNonNull(b.limits().get());
            journal = commonHistory(List.of(this, peer));
            computers = journal == null ? 0 : journal.computers().getAsInt();
            claims = journal == null ? 0 : journal.claims().getAsInt();
            var checks = new ArrayList<>(routes); checks.addAll(peer.routes);
            // Finish ALL provider callbacks before either side's callback-free physical check.
            for (var route : checks) { var denied = Objects.requireNonNull(route.authorize().get()); if (denied.isPresent()) return denied.orElseThrow(); }
            for (var route : checks) { var denied = Objects.requireNonNull(route.physical().get()); if (denied.isPresent()) return denied.orElseThrow(); }
            var terminal = new ArrayList<>(validations); terminal.addAll(peer.validations);
            var terminalDenied = validateOnce(terminal); if (terminalDenied.isPresent()) return terminalDenied.orElseThrow();
            if (!matches(a, leftLimits) || !matches(b, rightLimits)) return XBusExchange.Status.STALE;
            if (journal != null) {
                var inspection = journal.store().inspect(List.of(a.host().flowSnapshot().orElseThrow(), b.host().flowSnapshot().orElseThrow()), computers, claims);
                if (!allowed(inspection, a.expected(), b.expected())) return inspection.rejection().orElseThrow();
                if (inspection.rejection().isEmpty()) {
                    var owners = new LinkedHashMap<XBusNetwork.Pin, FlowBlockHost.Saved>();
                    var leftPin = new XBusNetwork.Pin(a.expected().computer(), a.expected().pin());
                    var rightPin = new XBusNetwork.Pin(b.expected().computer(), b.expected().pin());
                    owners.put(leftPin, a.host().flowSnapshot().orElseThrow()); owners.put(rightPin, b.host().flowSnapshot().orElseThrow());
                    var held = journal.store().occupiedWriter(owners);
                    if (held.rejection().isPresent()) return held.rejection().orElseThrow();
                    var selected = b.expected().mode() == XBusPortState.Mode.WRITE ? rightPin : leftPin;
                    if (held.writer().isPresent() && !held.writer().orElseThrow().equals(selected)) return XBusExchange.Status.NOT_READY;
                }
            }
        } catch (RuntimeException | StackOverflowError failure) { return XBusExchange.Status.UNAVAILABLE; }
        // Dirty callbacks may invalidate route authorization. Re-run the entire barrier,
        // once, before allowing an operation after protocol-only reclamation.
        if (!reclaimed && reclaimCompleted(journal, List.of(a.host(), b.host())) > 0)
            return exchange(peer, true);
        // Do not disguise a post-publication dirty callback exception as a preflight rejection.
        return exchangeChecked(a, leftLimits, b, rightLimits, journal, computers, claims);
    }
    private static int reclaimCompleted(History history, List<ComputerBlockHost> owners) {
        if (history == null) return 0;
        int released = 0;
        for (var host : owners.stream().distinct().toList())
            released += host.reclaimCompletedXBus(history.store()::payloadDiscardable);
        return released;
    }
    private static History commonHistory(List<XBusAccess> accesses) {
        History journal = null;
        for (var access : accesses) if (access.history.isPresent()) {
            var next = access.history.orElseThrow();
            if (journal != null && journal.store() != next.store()) throw new IllegalArgumentException("history_domain_mismatch");
            journal = next;
        }
        if (journal != null && accesses.stream().anyMatch(a -> a.history.isEmpty())) throw new IllegalArgumentException("history_missing");
        if (journal == null) return null;
        var store = journal.store();
        return new History(store,
            () -> accesses.stream().map(a -> a.history.orElseThrow()).mapToInt(h -> h.computers().getAsInt()).min().orElse(0),
            () -> accesses.stream().map(a -> a.history.orElseThrow()).mapToInt(h -> h.claims().getAsInt()).min().orElse(0));
    }
    private static boolean allowed(Inspection inspection, XBusPortState a, XBusPortState b) {
        if (inspection.rejection().isEmpty()) return true;
        if (inspection.rejection().orElseThrow() != XBusExchange.Status.RECOVERY_REQUIRED) return false;
        var left = new XBusNetwork.Pin(a.computer(), a.pin()); var right = new XBusNetwork.Pin(b.computer(), b.pin());
        return inspection.recoveries().stream().anyMatch(r -> r.reader().equals(left) && r.writer().equals(right)
            || r.reader().equals(right) && r.writer().equals(left))
            || a.mode() == XBusPortState.Mode.WRITE && b.mode() == XBusPortState.Mode.READABLE
            || b.mode() == XBusPortState.Mode.WRITE && a.mode() == XBusPortState.Mode.READABLE;
    }
    private static XBusExchange.Status exchangeChecked(Target a, ComputerHostLimits leftLimits, Target b, ComputerHostLimits rightLimits,
            History history, int computers, int claims) {
        // This narrow two-endpoint API cannot authorize an entire physical component. Cohorts
        // must use Network.exchange's all-party barrier and full dimension reservation instead.
        for (var target : List.of(a, b)) {
            var saved = target.host().flowSnapshot().orElseThrow();
            if (saved.pinClaims().shared(target.expected().pin()) || saved.receipts().stream().anyMatch(
                    com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusReceipt::grouped))
                return XBusExchange.Status.UNSUPPORTED;
        }
        var reservation = history == null ? null : history.store().reserve(a.host().flowSnapshot().orElseThrow(), a.expected().pin(),
            b.host().flowSnapshot().orElseThrow(), b.expected().pin(), computers, claims);
        if (reservation != null && reservation.rejection().isPresent()) return reservation.rejection().orElseThrow();
        var result = a.host().exchange(a.expected().pin(), leftLimits, true, b.host(), b.expected().pin(), rightLimits, true);
        if (reservation != null) reservation.finish(result);
        if (history != null && result == XBusExchange.Status.EXCHANGED) {
            var owners = List.of(a.host().flowSnapshot().orElseThrow(), b.host().flowSnapshot().orElseThrow());
            // A normal pair appends to both owners; repair appends only to the older owner.
            // Inspect both tails, not all historical operations. The journal independently
            // requires the exact enrolled receipt in EVERY participant before marking it.
            for (var owner : owners) if (!owner.receipts().isEmpty())
                history.store().certifyCompleted(owner.receipts().getLast(), owners);
        }
        return result;
    }
    private static boolean matches(Target target, ComputerHostLimits limits) {
        return target.host().flowSnapshot().flatMap(saved -> XBusPortState.of(saved, limits.pins(), target.expected().pin()))
            .filter(target.expected()::equals).isPresent();
    }

    /** Read-only diagnosis; recoveries contain identities only, never another Computer's data. */
    public record Inspection(Optional<XBusExchange.Status> rejection, List<XBusNetwork.Selection> recoveries) {
        public Inspection { Objects.requireNonNull(rejection); recoveries = List.copyOf(recoveries); }
    }
    /** Coordinator-only selection; not a player-visible acknowledgement or delivery result. */
    public record WriterSelection(Optional<XBusNetwork.Pin> writer, Optional<XBusExchange.Status> rejection) {
        public WriterSelection { Objects.requireNonNull(writer); Objects.requireNonNull(rejection); if (writer.isPresent() && rejection.isPresent()) throw new IllegalArgumentException("Rejected writer"); }
        static WriterSelection denied(XBusExchange.Status status) { return new WriterSelection(Optional.empty(), Optional.of(status)); }
    }
    /** Coordinator bookkeeping, never a transfer permit or a player-visible delivery result. */
    public record RoundAdmission(Inspection inspection, XBusExchange.Status retirement,
            Map<java.util.UUID, FlowBlockHost.Saved> beforeRetirement, Optional<WriterSelection> writer) {
        public RoundAdmission { beforeRetirement = Map.copyOf(beforeRetirement); Objects.requireNonNull(writer); }
    }
    /**
     * Trusted coordinator only: supply EVERY access in one explicitly connected, loaded component.
     * This does not discover omitted historical participants or authorize arbitrary world positions.
     * Retain aliases here: deduplicating them before validation would discard their route guards.
     */
    public static Network network(List<XBusAccess> participants) { return new Network(participants); }
    public static final class Network {
        private record Checked(Target target, ComputerHostLimits limits) {}
        private record Validation(Inspection inspection, Map<XBusNetwork.Pin, Checked> pins, History history, int computers, int claims) {}
        private final List<XBusAccess> participants;
        private Network(List<XBusAccess> participants) { this.participants = List.copyOf(participants); }
        public Inspection inspect() { return validate().inspection(); }
        /** One synchronous instruction-round admission: all provider callbacks and the final
         * topology barrier finish before inspection/retirement/selection use the same evidence.
         * No validated access escapes this call. exchange/rejectPaused still validate afresh. */
        public RoundAdmission beginRound(List<XBusNetwork.Pin> ordered, boolean admitWriter) {
            var validated = validateAndReclaim(); var inspection = validated.inspection();
            if (inspection.rejection().isPresent())
                return new RoundAdmission(inspection, XBusExchange.Status.NOT_READY, Map.of(), Optional.empty());
            var before = new LinkedHashMap<java.util.UUID, FlowBlockHost.Saved>();
            for (var pin : validated.pins().values()) {
                var target = pin.target();
                before.putIfAbsent(target.expected().computer(), target.host().flowSnapshot().orElseThrow());
            }
            var retired = settleRetired(validated);
            if (retired != XBusExchange.Status.NOT_READY)
                return new RoundAdmission(inspection, retired, before, Optional.empty());
            var writer = admitWriter ? Optional.of(selectWriter(validated, ordered, false)) : Optional.<WriterSelection>empty();
            return new RoundAdmission(inspection, retired, Map.of(), writer);
        }
        /** Only a real instruction round admits a first sender; status/configuration observation does not. */
        public WriterSelection selectWriter(List<XBusNetwork.Pin> ordered) {
            return selectWriter(validate(), ordered, true);
        }
        private WriterSelection selectWriter(Validation validated, List<XBusNetwork.Pin> ordered, boolean retire) {
            if (validated.inspection().rejection().isPresent()) return WriterSelection.denied(validated.inspection().rejection().orElseThrow());
            if (ordered.size() != validated.pins().size() || !new HashSet<>(ordered).equals(validated.pins().keySet()))
                return WriterSelection.denied(XBusExchange.Status.PORT_MISMATCH);
            if (validated.history() != null) {
                var journal = validated.history().store(); var saves = saves(validated);
                var settled = retire ? settleRetired(validated) : XBusExchange.Status.NOT_READY;
                if (settled != XBusExchange.Status.NOT_READY) return WriterSelection.denied(settled);
                return journal.selectWriter(ordered, saves, validated.claims());
            }
            return new WriterSelection(ordered.stream().filter(p -> validated.pins().get(p).target().expected().mode() == XBusPortState.Mode.WRITE).findFirst(), Optional.empty());
        }
        /** Runs before pause handling; retires exact old tasks, never a freshly installed peer. */
        public XBusExchange.Status settleRetired() {
            var validated = validate();
            return validated.inspection().rejection().orElseGet(() -> settleRetired(validated));
        }
        private static XBusExchange.Status settleRetired(Validation validated) {
            if (validated.history() == null) return XBusExchange.Status.NOT_READY;
            var journal = validated.history().store(); var cancellation = journal.cancellation(saves(validated));
            if (cancellation.isEmpty()) return XBusExchange.Status.NOT_READY;
            var bindings = validated.pins().values().stream().map(p -> new ComputerBlockHost.XBusBinding(
                p.target().host(), p.target().expected().pin(), p.limits(), true)).toList();
            var prepared = ComputerBlockHost.prepareCancellation(bindings, cancellation.orElseThrow());
            if (prepared.status() != XBusExchange.Status.RETIRED) return prepared.status();
            var rejection = journal.reserveCancellation(cancellation.orElseThrow(), validated.claims());
            return rejection.isPresent() ? rejection.orElseThrow() : prepared.commit();
        }
        private static Map<XBusNetwork.Pin, FlowBlockHost.Saved> saves(Validation validated) {
            var owners = new LinkedHashMap<XBusNetwork.Pin, FlowBlockHost.Saved>();
            validated.pins().forEach((pin, checked) -> owners.put(pin, checked.target().host().flowSnapshot().orElseThrow()));
            return Map.copyOf(owners);
        }
        /** Completes only the caller's operation after proving the admitted sender (or ALL peers) paused. */
        public XBusExchange.Status rejectPaused(XBusNetwork.Pin origin) {
            var validated = validate();
            if (validated.inspection().rejection().isPresent()) return validated.inspection().rejection().orElseThrow();
            var caller = validated.pins().get(Objects.requireNonNull(origin));
            if (caller == null) return XBusExchange.Status.NOT_FOUND;
            var mode = caller.target().expected().mode();
            if (mode != XBusPortState.Mode.READ && mode != XBusPortState.Mode.WRITE && mode != XBusPortState.Mode.READABLE)
                return XBusExchange.Status.NOT_READY;
            boolean found = validated.history() != null && validated.history().store().occupiedWriter(saves(validated)).rejection().equals(Optional.of(XBusExchange.Status.PAUSED));
            if (!found) for (var peer : validated.pins().values()) {
                if (peer.target().expected().computer().equals(origin.computer())) continue;
                if (peer.target().expected().mode() != XBusPortState.Mode.PAUSED) return XBusExchange.Status.NOT_READY;
                found = true;
            }
            if (!found) return XBusExchange.Status.NOT_READY;
            // No provider callback after this barrier; no transfer reservation, receipt or peer write.
            return caller.target().host().rejectPausedXBus(origin.pin(), caller.limits());
        }
        /** Observation-only configuration check. Does not retire history or authorize an exchange. */
        public Optional<XBusExchange.Status> inspectBindings() { return validate(false).inspection().rejection(); }
        /** Nonblocking electrical status, not a receive/readiness probe or an acknowledgement.
         * No owner publication, history enrollment, reservation or retirement is performed. */
        public int statusCode(XBusNetwork.Pin origin) {
            return observedWriter(origin, false).isPresent()
                ? com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.SimpleIoState.MAX : 0;
        }
        /** Witness only, never a receive grant. Ignore our own write, and wait for arbitration
         * before announcing a contended bus. The operation distinguishes consecutive offers. */
        public Optional<XBusPortState> readyWriter(XBusNetwork.Pin origin) { return observedWriter(origin, true); }
        private Optional<XBusPortState> observedWriter(XBusNetwork.Pin origin, boolean forReceiver) {
            var validated = validate(false);
            if (validated.inspection().rejection().isPresent()) return Optional.empty();
            var caller = validated.pins().get(Objects.requireNonNull(origin));
            if (caller == null || caller.target().expected().mode() == XBusPortState.Mode.PAUSED) return Optional.empty();
            try {
                var owners = saves(validated);
                var snapshots = validated.pins().values().stream().map(c -> c.target().host()).distinct()
                    .map(host -> host.flowSnapshot().orElseThrow()).toList();
                if (XBusRecovery.inspect(snapshots, receipt -> validated.history() != null
                    && validated.history().store().payloadDiscardable(receipt)).rejection().isPresent()) return Optional.empty();
                Optional<XBusNetwork.Pin> held = Optional.empty();
                if (validated.history() != null) {
                    var journal = validated.history().store();
                    if (journal.inspectKnown(snapshots).rejection().isPresent()) return Optional.empty();
                    var occupancy = journal.occupiedWriter(owners);
                    if (occupancy.rejection().isPresent()) return Optional.empty();
                    held = occupancy.writer();
                }
                XBusPortState writer = null;
                for (var entry : validated.pins().entrySet()) {
                    var state = entry.getValue().target().expected();
                    if (state.mode() == XBusPortState.Mode.FAILED) return Optional.empty();
                    if (state.mode() != XBusPortState.Mode.WRITE) continue;
                    if (forReceiver) {
                        if (held.isPresent() && !held.get().equals(entry.getKey())) continue;
                        if (writer != null || origin.computer().equals(state.computer())) return Optional.empty();
                    }
                    writer = state;
                }
                return Optional.ofNullable(writer);
            } catch (RuntimeException | StackOverflowError failure) { return Optional.empty(); }
        }
        public XBusExchange.Status exchange(XBusNetwork.Pin left, XBusNetwork.Pin right) {
            Objects.requireNonNull(left); Objects.requireNonNull(right);
            var validated = validateAndReclaim(); var inspection = validated.inspection();
            var a = validated.pins().get(left); var b = validated.pins().get(right);
            boolean repair = inspection.recoveries().stream().anyMatch(r -> r.reader().equals(left) && r.writer().equals(right)
                || r.reader().equals(right) && r.writer().equals(left));
            if (inspection.rejection().isPresent()) {
                var status = inspection.rejection().orElseThrow();
                // An older slx must still be able to wake before reaching its recorded Read.
                // This exact WRITE/READABLE pair can only peek, unless it is itself an original repair pair.
                if (status != XBusExchange.Status.RECOVERY_REQUIRED || !repair && !readable(a, b)) return status;
            }
            if (a == null || b == null) return XBusExchange.Status.NOT_FOUND;
            if (!repair && validated.history() != null) {
                var held = validated.history().store().occupiedWriter(saves(validated));
                if (held.rejection().isPresent()) return held.rejection().orElseThrow();
                var selected = b.target().expected().mode() == XBusPortState.Mode.WRITE ? right : left;
                if (held.writer().isPresent() && !held.writer().orElseThrow().equals(selected)) return XBusExchange.Status.NOT_READY;
            }
            // No provider callbacks after the all-party barrier, and no swallowing dirty callback failures.
            boolean group = validated.pins().size() > 2 || validated.pins().values().stream().anyMatch(p -> {
                var saved = p.target().host().flowSnapshot().orElseThrow();
                return saved.pinClaims().shared(p.target().expected().pin())
                    || saved.pinState(p.target().expected().pin()).entry().isPresent()
                    || saved.receipts().stream().anyMatch(r -> r.grouped() || r.positions().stream().anyMatch(end -> end.slot() != 0));
            });
            if (group) {
                boolean probesOnly = readable(a, b) && !repair;
                var bindings = validated.pins().values().stream().map(p -> new ComputerBlockHost.XBusBinding(
                    p.target().host(), p.target().expected().pin(), p.limits(), true)).toList();
                var selected = b.target().expected().mode() == XBusPortState.Mode.WRITE ? b : a;
                // For a repair both physical endpoints may already be past the recorded operation.
                // The owner prepares from retained cohort evidence before considering a new write.
                if (inspection.rejection().isPresent() && !probesOnly) selected = b;
                var writer = new ComputerBlockHost.XBusBinding(selected.target().host(), selected.target().expected().pin(), selected.limits(), true);
                var prepared = probesOnly ? ComputerBlockHost.prepareReadableGroup(bindings, writer)
                    : ComputerBlockHost.prepareExchangeGroup(bindings, writer);
                if (prepared.status() != XBusExchange.Status.EXCHANGED && prepared.status() != XBusExchange.Status.READABLE) return prepared.status();
                XBusHistorySavedData.Reservation reservation = null;
                if (validated.history() != null && prepared.receipt().isPresent()) {
                    var saves = bindings.stream().map(ComputerBlockHost.XBusBinding::host).distinct().map(host -> host.flowSnapshot().orElseThrow()).toList();
                    reservation = validated.history().store().reserve(prepared.receipt().orElseThrow(), saves, validated.computers(), validated.claims());
                    if (reservation.rejection().isPresent()) return reservation.rejection().orElseThrow();
                }
                var result = prepared.commit();
                if (reservation != null) reservation.finish(result);
                if (validated.history() != null && result == XBusExchange.Status.EXCHANGED && prepared.receipt().isPresent()) {
                    var owners = bindings.stream().map(ComputerBlockHost.XBusBinding::host).distinct()
                        .map(host -> host.flowSnapshot().orElseThrow()).toList();
                    validated.history().store().certifyCompleted(prepared.receipt().orElseThrow(), owners);
                }
                return result;
            }
            return exchangeChecked(a.target(), a.limits(), b.target(), b.limits(), validated.history(), validated.computers(), validated.claims());
        }
        private static boolean readable(Checked a, Checked b) {
            if (a == null || b == null) return false;
            var left = a.target().expected().mode(); var right = b.target().expected().mode();
            return left == XBusPortState.Mode.WRITE && right == XBusPortState.Mode.READABLE
                || right == XBusPortState.Mode.WRITE && left == XBusPortState.Mode.READABLE;
        }
        private Validation validate() {
            return validate(true);
        }
        private Validation validateAndReclaim() {
            var validated = validate();
            if (validated.inspection().rejection().isEmpty()
                && reclaimCompleted(validated.history(), validated.pins().values().stream()
                    .map(pin -> pin.target().host()).toList()) > 0) return validate();
            return validated;
        }
        private Validation validate(boolean inspectHistory) {
            try {
                for (var access : participants) {
                    var denied = Objects.requireNonNull(access.rejection.get());
                    if (denied.isPresent()) return denied(denied.orElseThrow());
                }
                var checked = new ArrayList<Checked>();
                for (var access : participants) {
                    var target = access.target.orElseThrow();
                    checked.add(new Checked(target, Objects.requireNonNull(target.limits().get())));
                }
                var journal = commonHistory(participants);
                int computers = journal == null ? 0 : journal.computers().getAsInt();
                int claims = journal == null ? 0 : journal.claims().getAsInt();
                var routes = participants.stream().flatMap(a -> a.routes.stream()).toList();
                for (var route : routes) {
                    var denied = Objects.requireNonNull(route.authorize().get());
                    if (denied.isPresent()) return denied(denied.orElseThrow());
                }
                for (var route : routes) {
                    var denied = Objects.requireNonNull(route.physical().get());
                    if (denied.isPresent()) return denied(denied.orElseThrow());
                }
                var terminalDenied = validateOnce(participants.stream().flatMap(access -> access.validations.stream()).toList());
                if (terminalDenied.isPresent()) return denied(terminalDenied.orElseThrow());
                var pins = new LinkedHashMap<XBusNetwork.Pin, Checked>();
                var owners = new LinkedHashMap<java.util.UUID, Checked>();
                for (var item : checked) {
                    var target = item.target();
                    if (!target.host().loaded()) return denied(XBusExchange.Status.UNAVAILABLE);
                    if (!matches(target, item.limits())) return denied(XBusExchange.Status.STALE);
                    var id = target.expected().computer(); var previous = owners.putIfAbsent(id, item);
                    if (previous != null && (previous.target().host() != target.host() || !previous.limits().equals(item.limits())))
                        return denied(XBusExchange.Status.STALE);
                    pins.putIfAbsent(new XBusNetwork.Pin(id, target.expected().pin()), item);
                }
                if (!inspectHistory) return new Validation(new Inspection(Optional.empty(), List.of()), Map.copyOf(pins), journal, computers, claims);
                var saves = owners.values().stream().map(item -> item.target().host().flowSnapshot().orElseThrow()).toList();
                var inspection = XBusRecovery.inspect(saves, receipt -> journal != null
                    && journal.store().payloadDiscardable(receipt));
                if (journal != null) {
                    var global = journal.store().inspect(saves, computers, claims);
                    if (global.rejection().isPresent() && (inspection.rejection().isEmpty()
                        || global.rejection().orElseThrow() != XBusExchange.Status.RECOVERY_REQUIRED)) inspection = global;
                    else if (global.rejection().isPresent()) {
                        var repairs = new java.util.LinkedHashSet<>(inspection.recoveries()); repairs.addAll(global.recoveries());
                        inspection = new Inspection(inspection.rejection(), List.copyOf(repairs));
                    }
                }
                for (var repair : inspection.recoveries())
                    if (!pins.containsKey(repair.reader()) || !pins.containsKey(repair.writer())) return denied(XBusExchange.Status.PORT_MISMATCH);
                return new Validation(inspection, Map.copyOf(pins), journal, computers, claims);
            } catch (RuntimeException | StackOverflowError failure) { return denied(XBusExchange.Status.UNAVAILABLE); }
        }
        private static Validation denied(XBusExchange.Status status) {
            return new Validation(new Inspection(Optional.of(status), List.of()), Map.of(), null, 0, 0);
        }
    }

    /** Only callback-free proofs may share a barrier. Identity deduplication is local to this call;
     * every subsequent inspection/exchange still re-observes world state and all route guards run. */
    private static Optional<XBusExchange.Status> validateOnce(List<Supplier<Optional<XBusExchange.Status>>> proofs) {
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Supplier<Optional<XBusExchange.Status>>, Boolean>());
        for (var proof : proofs) if (seen.add(proof)) {
            var denied = Objects.requireNonNull(proof.get());
            if (denied.isPresent()) return denied;
        }
        return Optional.empty();
    }

    /** Add routing authority without replacing the underlying Computer's live checks. */
    public XBusAccess guarded(Supplier<Optional<XBusExchange.Status>> guard) {
        Objects.requireNonNull(guard);
        return new XBusAccess(target, () -> {
            var denied = Objects.requireNonNull(guard.get());
            return denied.isPresent() ? denied : rejection.get();
        }, routes, validations, history);
    }

    /**
     * Provider-owned final proof. Must only observe local authorization, identity, hardware and world state:
     * no endpoint/capability callbacks, host restoration, mutation, dirty notifications or execution.
     * Runs after BOTH sides' potentially mutating callbacks. A Java provider must honor this contract.
     * The same proof instance shared by aliases is invoked once per barrier, never cached across barriers.
     */
    public XBusAccess validated(Supplier<Optional<XBusExchange.Status>> validation) {
        var combined = new ArrayList<>(validations); combined.add(Objects.requireNonNull(validation));
        return new XBusAccess(target, rejection, routes, combined, history);
    }
    boolean hasFinalValidation() { return !validations.isEmpty(); }

    /** Coordinator-only: authorize may inspect providers; physical must only inspect loaded world geometry/instances. */
    public XBusAccess withPhysicalRoute(Supplier<Optional<XBusExchange.Status>> authorize,
            Supplier<Optional<XBusExchange.Status>> physical) {
        var combined = new ArrayList<>(routes);
        combined.add(new PhysicalRoute(Objects.requireNonNull(authorize), Objects.requireNonNull(physical)));
        return new XBusAccess(target, rejection, combined, validations, history);
    }

    /** Provider-owned dimension history. Limits must be read-only configuration suppliers. */
    public XBusAccess historical(XBusHistorySavedData store, IntSupplier computers, IntSupplier claims) {
        Objects.requireNonNull(store); Objects.requireNonNull(computers); Objects.requireNonNull(claims);
        if (history.isPresent() && history.orElseThrow().store() != store) throw new IllegalArgumentException("history_domain_mismatch");
        return new XBusAccess(target, rejection, routes, validations, Optional.of(new History(store, computers, claims)));
    }
}
