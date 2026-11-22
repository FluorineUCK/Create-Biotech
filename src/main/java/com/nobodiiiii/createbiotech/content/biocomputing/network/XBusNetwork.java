package com.nobodiiiii.createbiotech.content.biocomputing.network;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusExchange;
import java.util.*;
import java.util.function.Function;

/** Explicit physical-pin topology, independent of grants, world objects, payloads and host clocks. */
public final class XBusNetwork {
    public record Pin(UUID computer, String pin) {
        public Pin { Objects.requireNonNull(computer); Objects.requireNonNull(pin); if (pin.isBlank()) throw new IllegalArgumentException("Empty pin"); }
    }
    public record Link(Pin left, Pin right) {
        public Link { Objects.requireNonNull(left); Objects.requireNonNull(right); }
    }
    public record Selection(Pin reader, Pin writer) {
        public Selection { Objects.requireNonNull(reader); Objects.requireNonNull(writer); }
    }
    private final Map<Pin, Integer> component;
    private final List<List<Pin>> networks;
    /** Canonical order must contain each physical pin once, regardless of its public aliases. */
    public XBusNetwork(List<Pin> canonical, Collection<Link> links) {
        canonical = List.copyOf(canonical);
        var edges = new LinkedHashMap<Pin, Set<Pin>>();
        for (var pin : canonical) if (edges.put(pin, new HashSet<>()) != null)
            throw new IllegalArgumentException("Duplicate physical pin in canonical order");
        for (var link : links) {
            if (!edges.containsKey(link.left()) || !edges.containsKey(link.right()))
                throw new IllegalArgumentException("Link endpoint missing from canonical order");
            edges.get(link.left()).add(link.right()); edges.get(link.right()).add(link.left());
        }
        var components = new HashMap<Pin, Integer>(); var groups = new ArrayList<List<Pin>>();
        for (var root : canonical) {
            if (components.containsKey(root)) continue;
            int group = groups.size(); var pending = new ArrayDeque<Pin>(); pending.add(root); components.put(root, group);
            while (!pending.isEmpty()) for (var next : edges.get(pending.removeFirst())) {
                if (components.putIfAbsent(next, group) == null) pending.addLast(next);
            }
            groups.add(new ArrayList<>());
        }
        // One pass, including isolated pins; no quadratic scan per disconnected component.
        // Graph traversal and edge insertion order never become candidate priority.
        for (var pin : canonical) groups.get(components.get(pin)).add(pin);
        component = Map.copyOf(components); networks = groups.stream().map(List::copyOf).toList();
    }
    public Round round(long seed) { return new Round(seed); }
    /** Immutable declared components in caller-supplied canonical order, not world discovery or execution order. */
    public List<List<Pin>> components() { return networks; }
    /** Single-coordinator round, not thread-safe, not a Minecraft tick or a persisted mailbox. */
    public final class Round {
        private final List<List<Pin>> ordered;
        private final BitSet occupied = new BitSet();
        private Round(long seed) { ordered = networks.stream().map(pins -> XBusOrder.shuffled(pins, seed)).toList(); }
        private int group(Pin pin) {
            var group = component.get(Objects.requireNonNull(pin));
            if (group == null) throw new IllegalArgumentException("Unknown physical pin");
            return group;
        }
        /** Offered pin identities only. The real exchange must revalidate mode, grants and exact continuation. */
        public Optional<Selection> peek(Pin reader, Set<Pin> writers) {
            int group = group(reader); var offered = Set.copyOf(writers);
            if (!component.keySet().containsAll(offered)) throw new IllegalArgumentException("Unknown writer pin");
            if (occupied.get(group)) return Optional.empty();
            return ordered.get(group).stream().filter(pin -> !pin.equals(reader) && offered.contains(pin))
                .findFirst().map(writer -> new Selection(reader, writer));
        }
        public boolean occupied(Pin pin) { return occupied.get(group(pin)); }
        /**
         * Only an actual committed exchange occupies a network. READABLE and explicit rejections release it.
         * Reserve during callbacks to prohibit reentrant double consumption; exceptions keep it occupied because
         * a dirty callback may throw AFTER publication. No catch/retry against another writer in this attempt.
         * Protocol recovery across all participants must be settled by the host before calling this method.
         */
        public XBusExchange.Status attempt(Pin reader, Set<Pin> writers, Function<Selection, XBusExchange.Status> exchange) {
            Objects.requireNonNull(exchange);
            var selected = peek(reader, writers); if (selected.isEmpty()) return XBusExchange.Status.NOT_READY;
            int group = group(reader); occupied.set(group);
            var result = Objects.requireNonNull(exchange.apply(selected.orElseThrow()));
            if (result == XBusExchange.Status.PREPARED) throw new IllegalStateException("Preparation is not a completed exchange");
            if (result != XBusExchange.Status.EXCHANGED) occupied.clear(group);
            return result;
        }
    }
}
