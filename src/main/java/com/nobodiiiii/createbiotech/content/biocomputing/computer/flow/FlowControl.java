package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;

/** Private protocol lifetime, not shared RAM. Frontiers are bounded by Computer and stable task slot,
 * never by the cumulative number of completed task UUIDs. */
public record FlowControl(long generation, Map<UUID, Peer> peers) {
    public static final FlowControl INITIAL = new FlowControl(0, Map.of());
    public record Frontier(UUID flow, long incarnation, long retiredThrough, boolean retired) {
        public Frontier {
            Objects.requireNonNull(flow);
            if (incarnation < 0 || retiredThrough < -1) throw new IllegalArgumentException("Invalid task frontier");
        }
        Frontier merge(Frontier next) {
            if (incarnation != next.incarnation()) return incarnation > next.incarnation() ? this : next;
            if (!flow.equals(next.flow())) throw new IllegalArgumentException("Contradictory task incarnation");
            return new Frontier(flow, incarnation, Math.max(retiredThrough, next.retiredThrough()), retired || next.retired());
        }
        DataValue value() { return new DataValue.RecordValue(Map.of("flow", new DataValue.TextValue(flow.toString()),
            "incarnation", new DataValue.IntegerValue(incarnation), "retired_through", new DataValue.IntegerValue(retiredThrough),
            "retired", new DataValue.BooleanValue(retired))); }
    }
    public record Peer(long generation, Map<Integer, Frontier> slots) {
        public Peer {
            slots = Map.copyOf(slots);
            if (generation < 0 || slots.isEmpty()) throw new IllegalArgumentException("Invalid peer frontier");
            var ids = new HashSet<UUID>();
            for (var entry : slots.entrySet()) {
                if (entry.getKey() < 0 || !ids.add(entry.getValue().flow())) throw new IllegalArgumentException("Invalid peer slot ownership");
                if (entry.getKey() == 0 && (entry.getValue().incarnation() != 0 || entry.getValue().retired()))
                    throw new IllegalArgumentException("Main is retired by its program generation");
            }
        }
        public Peer(long generation, UUID flow, long retiredThrough) { this(generation, Map.of(0, new Frontier(flow, 0, retiredThrough, false))); }
        public boolean taskAware() { return slots.size() != 1 || !slots.containsKey(0); }
        public UUID flow() { return slots.get(0).flow(); }
        public long retiredThrough() { return slots.get(0).retiredThrough(); }
        public Peer merge(Peer next) {
            if (generation != next.generation()) return generation > next.generation() ? this : next;
            var merged = new LinkedHashMap<>(slots);
            next.slots().forEach((key, value) -> merged.merge(key, value, Frontier::merge));
            return new Peer(generation, merged);
        }
        public Optional<XBusExchange.Status> check(int slot, UUID flow, long incarnation, long operation, long generation) {
            if (generation > this.generation) return Optional.empty();
            if (generation < this.generation) return Optional.of(XBusExchange.Status.RETIRED);
            var known = slots.get(slot);
            if (known == null || incarnation > known.incarnation()) return Optional.empty();
            if (incarnation < known.incarnation()) return Optional.of(XBusExchange.Status.RETIRED);
            if (!flow.equals(known.flow())) return Optional.of(XBusExchange.Status.HISTORY_CONFLICT);
            return known.retired() || operation <= known.retiredThrough() ? Optional.of(XBusExchange.Status.RETIRED) : Optional.empty();
        }
        public Optional<XBusExchange.Status> check(XBusReceipt.Position p) { return check(p.slot(), p.flow(), p.incarnation(), p.operation(), p.generation()); }
        public boolean identity(XBusReceipt.Position p) {
            var s = slots.get(p.slot());
            return generation == p.generation() && s != null && s.incarnation() == p.incarnation() && s.flow().equals(p.flow());
        }
        public boolean supersedes(XBusReceipt.Position p) {
            if (generation != p.generation()) return generation > p.generation();
            var s = slots.get(p.slot());
            return s != null && (s.incarnation() > p.incarnation() || s.incarnation() == p.incarnation() && s.flow().equals(p.flow()) && s.retired());
        }
        public boolean coversReceipt(XBusReceipt.Position p) {
            return supersedes(p) || identity(p) && p.operation() > slots.get(p.slot()).retiredThrough();
        }
        public static Peer at(XBusReceipt.Position p, long retiredThrough) {
            return new Peer(p.generation(), Map.of(p.slot(), new Frontier(p.flow(), p.incarnation(), retiredThrough, false)));
        }
        public static Peer of(FlowBlockHost.Saved saved) {
            var slots = new LinkedHashMap<Integer, Frontier>();
            slots.put(0, new Frontier(saved.state().flow(), 0, -1, false));
            var children = saved.tasks().slots().entries();
            for (int i = 0; i < children.size(); i++) {
                var s = children.get(i); slots.put(i + 1, new Frontier(s.flow(), s.incarnation(), -1, s.retired()));
            }
            return new Peer(saved.control().generation(), slots);
        }
        DataValue value() {
            if (!taskAware()) return new DataValue.RecordValue(Map.of("generation", new DataValue.IntegerValue(generation),
                "flow", new DataValue.TextValue(flow().toString()), "retired_through", new DataValue.IntegerValue(retiredThrough())));
            var values = new LinkedHashMap<String, DataValue>();
            slots.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> values.put(Integer.toString(e.getKey()), e.getValue().value()));
            return new DataValue.RecordValue(Map.of("generation", new DataValue.IntegerValue(generation), "slots", new DataValue.RecordValue(values)));
        }
    }
    public FlowControl {
        if (generation < 0) throw new IllegalArgumentException("Negative flow generation");
        peers = Map.copyOf(peers);
    }
    public boolean pristine() { return generation == 0 && peers.isEmpty(); }
    public boolean taskAware() { return peers.values().stream().anyMatch(Peer::taskAware); }
    public boolean wideOperations() { return peers.values().stream().flatMap(peer -> peer.slots().values().stream()).anyMatch(slot -> slot.retiredThrough() > Integer.MAX_VALUE); }
    /** The implicit initial generation has no retained dynamic metadata. */
    public long storageUnits() { return pristine() ? 0 : value().storageUnits(); }
    public DataValue.RecordValue value() {
        var fields = new LinkedHashMap<String, DataValue>();
        peers.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> fields.put(e.getKey().toString(), e.getValue().value()));
        return new DataValue.RecordValue(Map.of("generation", new DataValue.IntegerValue(generation), "peers", new DataValue.RecordValue(fields)));
    }
    public Optional<XBusExchange.Status> check(FlowRuntime.Checkpoint peer, long peerGeneration) {
        var known = peers.get(peer.computer());
        return known == null ? Optional.empty() : known.check(0, peer.flow(), 0, peer.operation(), peerGeneration);
    }
    public Optional<XBusExchange.Status> check(XBusReceipt.Position position) {
        var known = peers.get(position.computer()); return known == null ? Optional.empty() : known.check(position);
    }
    public FlowControl observe(UUID computer, long peerGeneration, UUID flow) { return record(computer, peerGeneration, flow, -1); }
    private FlowControl record(UUID computer, long peerGeneration, UUID flow, long retiredThrough) {
        return observe(computer, new Peer(peerGeneration, flow, retiredThrough));
    }
    public FlowControl observe(UUID computer, Peer witness) {
        var known = peers.get(computer);
        var replacement = witness;
        if (known != null) replacement = known.merge(replacement);
        if (replacement.equals(known)) return this;
        var next = new LinkedHashMap<>(peers); next.put(computer, replacement); return new FlowControl(generation, next);
    }
    public FlowControl nextGeneration() {
        if (generation == Long.MAX_VALUE) throw FlowProgram.error(ComputationException.Code.ARITHMETIC_OVERFLOW, "flow", "Flow generation exhausted");
        return new FlowControl(generation + 1, peers);
    }
    public FlowControl retire(List<XBusReceipt> receipts, FlowRuntime.Checkpoint state) {
        return retireOperations(receipts, state.computer()).nextGeneration();
    }
    public FlowControl retireOperations(List<XBusReceipt> receipts, UUID computer) {
        var result = this;
        for (var receipt : receipts) for (var remote : receipt.remote(computer)) {
            result = result.observe(remote.computer(), Peer.at(remote, remote.operation()));
        }
        return result;
    }
    public static FlowControl fromReceipts(List<XBusReceipt> receipts, FlowRuntime.Checkpoint state) {
        var result = new FlowControl(receipts.isEmpty() ? 0 : receipts.getFirst().own(state).generation(), Map.of());
        for (var receipt : receipts) {
            var remote = receipt.peer(state); result = result.observe(remote.computer(), remote.generation(), remote.flow());
        }
        return result;
    }
}
