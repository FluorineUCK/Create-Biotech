package com.nobodiiiii.createbiotech.content.biocomputing.application.production.create;

import java.util.*;

/** Bounded, world-independent native delivery evidence; restoration replays ordinary accept. */
public final class DeliveryLedger<K> {
    public enum Outcome { ACCEPTED, DUPLICATE, FOREIGN, CONFLICT, CAPACITY }
    public record Fragment<K>(int order, int link, int index, boolean finalLink, boolean last,
                              Optional<Map<K, Long>> context, Map<K, Long> items) {
        public Fragment {
            if (link < 0 || index < 0) throw new IllegalArgumentException("negative_fragment_index");
            context = Objects.requireNonNull(context).map(DeliveryLedger::counts);
            items = counts(items);
        }
    }
    private record Identity(int order, int link, int index) {}
    private final Map<K, Long> expected;
    private final Map<Integer, Set<Integer>> orders;
    private final int maxFragments;
    private final LinkedHashMap<Identity, Fragment<K>> fragments = new LinkedHashMap<>();
    private Map<K, Long> accepted = Map.of();

    public DeliveryLedger(Map<K, Long> expected, Map<Integer, Set<Integer>> orders, int maxFragments) {
        this.expected = counts(expected);
        if (this.expected.isEmpty() || orders.isEmpty() || maxFragments <= 0)
            throw new IllegalArgumentException("empty_delivery_or_capacity");
        var copy = new LinkedHashMap<Integer, Set<Integer>>();
        orders.forEach((order, links) -> {
            var set = Set.copyOf(links);
            if (set.isEmpty()) throw new IllegalArgumentException("empty_links");
            // Cardinality + range proves contiguity without iterating an attacker-supplied maximum.
            for (int link : set) if (link < 0 || link >= set.size()) throw new IllegalArgumentException("noncontiguous_links");
            copy.put(Objects.requireNonNull(order), set);
        });
        this.orders = Map.copyOf(copy);
        this.maxFragments = maxFragments;
    }
    private static <K> Map<K, Long> counts(Map<K, Long> source) {
        var copy = Map.copyOf(source);
        for (long count : copy.values()) if (count <= 0) throw new IllegalArgumentException("nonpositive_count");
        return copy;
    }
    public Outcome accept(Fragment<K> fragment) {
        Objects.requireNonNull(fragment);
        var links = orders.get(fragment.order());
        if (links == null || !links.contains(fragment.link())) return Outcome.FOREIGN;
        if (fragment.index() >= maxFragments) return Outcome.CAPACITY;
        if (fragment.finalLink() != (fragment.link() == links.size() - 1)
            || fragment.context().filter(context -> !context.equals(expected)).isPresent()) return Outcome.CONFLICT;
        var identity = new Identity(fragment.order(), fragment.link(), fragment.index());
        var previous = fragments.get(identity);
        if (previous != null) return previous.equals(fragment) ? Outcome.DUPLICATE : Outcome.CONFLICT;
        for (var existing : fragments.values()) {
            if (existing.order() != fragment.order() || existing.link() != fragment.link()) continue;
            if ((existing.last() && fragment.index() > existing.index())
                || (fragment.last() && existing.index() > fragment.index())
                || (existing.last() && fragment.last())) return Outcome.CONFLICT;
        }
        var next = new HashMap<>(accepted);
        try {
            for (var entry : fragment.items().entrySet()) {
                long total = Math.addExact(next.getOrDefault(entry.getKey(), 0L), entry.getValue());
                if (total > expected.getOrDefault(entry.getKey(), 0L)) return Outcome.CONFLICT;
                next.put(entry.getKey(), total);
            }
        } catch (ArithmeticException overflow) { return Outcome.CONFLICT; }
        if (fragments.size() >= maxFragments) return Outcome.CAPACITY;
        accepted = Map.copyOf(next);
        fragments.put(identity, fragment);
        return Outcome.ACCEPTED;
    }
    public boolean complete() {
        if (!accepted.equals(expected)) return false;
        for (var order : orders.entrySet()) {
            boolean context = false;
            for (int link : order.getValue()) {
                int count = 0, terminal = -1;
                for (var fragment : fragments.values()) {
                    if (fragment.order() != order.getKey()) continue;
                    context |= fragment.context().isPresent();
                    if (fragment.link() != link) continue;
                    count++;
                    if (fragment.last()) terminal = fragment.index();
                }
                if (terminal < 0 || (long) count != (long) terminal + 1) return false;
            }
            if (!context) return false;
        }
        return true;
    }
    public List<Fragment<K>> fragments() { return List.copyOf(fragments.values()); }
    public Map<K, Long> accepted() { return accepted; }
}
