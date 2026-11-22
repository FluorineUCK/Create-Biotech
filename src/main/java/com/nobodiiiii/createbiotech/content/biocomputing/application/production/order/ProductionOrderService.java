package com.nobodiiiii.createbiotech.content.biocomputing.application.production.order;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;

/**
 * Device-owned orders, not Computer tasks, RAM, topology or production CPU slots.
 * A trusted device adapter supplies transport identity, validates/freezes material plans, and
 * implements the native effect boundary. Recipe catalogs are optional adapter policy. No world handles
 * enter this journal. One owning server thread advances a service; no worker is created here.
 */
public final class ProductionOrderService {
    public record Limits(int orders, int steps, long valueUnits) {
        public Limits {
            if (orders < 1 || steps < 1 || valueUnits < 1) throw new IllegalArgumentException("invalid_limits");
        }
    }
    public record Key(UUID source, UUID request) {
        public Key { Objects.requireNonNull(source); Objects.requireNonNull(request); }
    }
    /** Stable effect identity survives save/reload and does not depend on tick or traversal order. */
    public record Operation(Key order, int step) {
        public Operation { Objects.requireNonNull(order); if (step < 0) throw new IllegalArgumentException("invalid_step"); }
    }
    public record Plan(String extension, int version, List<DataValue.RecordValue> steps) {
        public Plan {
            Objects.requireNonNull(extension); steps = List.copyOf(steps);
            if (extension.isEmpty() || version < 1 || steps.isEmpty()) throw new IllegalArgumentException("invalid_plan");
        }
    }
    public enum Phase { QUEUED, DISPATCHING, WAITING, COMPLETE, FAILED, REVIEW_REQUIRED }
    public record Order(Key key, DataValue.RecordValue request, Plan plan, int step, Phase phase,
                        Optional<DataValue.RecordValue> receipt, DataValue result, String diagnostic) {
        public Order {
            Objects.requireNonNull(key); Objects.requireNonNull(request); Objects.requireNonNull(plan);
            Objects.requireNonNull(phase); Objects.requireNonNull(receipt); Objects.requireNonNull(result);
            Objects.requireNonNull(diagnostic);
            if (step < 0 || step >= plan.steps().size()
                || (phase == Phase.QUEUED || phase == Phase.DISPATCHING) && receipt.isPresent()
                || (phase == Phase.WAITING || phase == Phase.COMPLETE) && receipt.isEmpty()
                || phase == Phase.COMPLETE && step != plan.steps().size() - 1)
                throw new IllegalArgumentException("invalid_order");
        }
        public boolean active() { return phase == Phase.QUEUED || phase == Phase.DISPATCHING || phase == Phase.WAITING; }
        public Operation operation() { return new Operation(key, step); }
        private long units() {
            long units = Math.addExact(request.storageUnits(), result.storageUnits());
            for (var command : plan.steps()) units = Math.addExact(units, command.storageUnits());
            return Math.addExact(units, receipt.map(DataValue::storageUnits).orElse(0L));
        }
    }
    public record Snapshot(long revision, int cursor, Map<Key, Order> orders, OptionalLong materialGrant) {
        public Snapshot(long revision, int cursor, Map<Key, Order> orders) {
            this(revision, cursor, orders, OptionalLong.empty());
        }
        public Snapshot {
            orders = Collections.unmodifiableMap(new LinkedHashMap<>(orders));
            Objects.requireNonNull(materialGrant);
            if (materialGrant.isPresent() && (materialGrant.getAsLong() < 0 || materialGrant.getAsLong() > revision))
                throw new IllegalArgumentException("invalid_material_grant_revision");
            if (revision < 0 || cursor < 0 || (orders.isEmpty() ? cursor != 0 : cursor >= orders.size()))
                throw new IllegalArgumentException("invalid_snapshot");
            orders.forEach((key, order) -> {
                if (!Objects.equals(key, order.key())) throw new IllegalArgumentException("invalid_order_index");
            });
        }
        public static Snapshot empty() { return new Snapshot(0, 0, Map.of()); }
    }
    public record Reply(String code, Optional<Order> order) {
        public Reply { Objects.requireNonNull(code); Objects.requireNonNull(order); }
    }
    public record Preparation(String code, Optional<Plan> plan) {
        public Preparation { Objects.requireNonNull(code); Objects.requireNonNull(plan); }
        public static Preparation ready(Plan plan) { return new Preparation("ready", Optional.of(plan)); }
        public static Preparation rejected(String code) { return new Preparation(code, Optional.empty()); }
    }
    public record Dispatch(String code, Optional<DataValue.RecordValue> receipt) {
        public Dispatch { Objects.requireNonNull(code); Objects.requireNonNull(receipt); }
        public static Dispatch accepted(DataValue.RecordValue receipt) { return new Dispatch("accepted", Optional.of(receipt)); }
        /** A rejection is proof that NO material operation took effect; ambiguity must throw instead. */
        public static Dispatch rejected(String code) { return new Dispatch(code, Optional.empty()); }
    }
    public enum ObservationKind { PENDING, COMPLETE, FAILED }
    public record Observation(ObservationKind kind, DataValue result, String code) {
        public Observation { Objects.requireNonNull(kind); Objects.requireNonNull(result); Objects.requireNonNull(code); }
        public static Observation pending() { return new Observation(ObservationKind.PENDING, DataValue.NullValue.INSTANCE, "waiting"); }
        public static Observation complete(DataValue result) { return new Observation(ObservationKind.COMPLETE, result, "complete"); }
        public static Observation failed(String code) { return new Observation(ObservationKind.FAILED, DataValue.NullValue.INSTANCE, code); }
    }
    public interface Journal {
        Snapshot load();
        /** Atomically durable BEFORE returning true, not just setDirty. False must publish nothing. */
        boolean commit(long expectedRevision, Snapshot next);
    }
    public interface Backend {
        boolean authorized(UUID verifiedSource);
        boolean supports(String extension, int version);
        /** A replacement network must neither inspect nor advance another network's retained orders. */
        default boolean inScope(Order order) { return true; }
        /** Pure bounded validation/freezing only; must not dispatch, alter inventory or edit recipe ROM. */
        Preparation prepare(UUID verifiedSource, DataValue.RecordValue request);
        Dispatch dispatch(Operation operation, DataValue.RecordValue command);
        /** Read-only evidence; repeated observation must not claim or consume an output twice. */
        Observation observe(Operation operation, DataValue.RecordValue receipt);
    }

    private final Journal journal;
    private final Backend backend;
    private final Limits limits;
    private boolean advancing;
    public ProductionOrderService(Journal journal, Backend backend, Limits limits) {
        this.journal = Objects.requireNonNull(journal); this.backend = Objects.requireNonNull(backend);
        this.limits = Objects.requireNonNull(limits);
    }
    private static Reply reply(String code, Order order) { return new Reply(code, Optional.ofNullable(order)); }

    public synchronized Reply submit(Key key, DataValue.RecordValue request) {
        Objects.requireNonNull(key); Objects.requireNonNull(request);
        if (advancing) return reply("busy", null);
        if (!backend.authorized(key.source())) return reply("denied", null);
        final Snapshot state;
        try { state = journal.load(); } catch (RuntimeException unavailable) { return reply("storage_unavailable", null); }
        var previous = state.orders().get(key);
        if (previous != null) return !backend.inScope(previous) ? reply("denied", null)
            : reply(previous.request().equals(request) ? "existing" : "request_conflict", previous);
        if (state.orders().size() >= limits.orders() || request.storageUnits() > limits.valueUnits()) return reply("capacity", null);
        final Preparation prepared;
        try { prepared = Objects.requireNonNull(backend.prepare(key.source(), request)); }
        catch (RuntimeException invalid) { return reply("invalid_request", null); }
        if (prepared.plan().isEmpty()) return reply(prepared.code(), null);
        var plan = prepared.plan().orElseThrow();
        if (!backend.supports(plan.extension(), plan.version())) return reply("unsupported_extension", null);
        var order = new Order(key, request, plan, 0, Phase.QUEUED, Optional.empty(), DataValue.NullValue.INSTANCE, "queued");
        String saved = save(state, order, state.cursor());
        return saved == null ? reply("accepted", order) : reply(saved, null);
    }

    /** The caller obtains source identity from authority/transport, never from an untrusted frame field. */
    public synchronized Reply status(Key key) {
        Objects.requireNonNull(key);
        if (!backend.authorized(key.source())) return reply("denied", null);
        try {
            var order = journal.load().orders().get(key);
            if (order != null && !backend.inScope(order)) return reply("denied", null);
            return reply(order == null ? "missing" : "ok", order);
        } catch (RuntimeException unavailable) { return reply("storage_unavailable", null); }
    }

    /**
     * Publishes already-observed native facts from the trusted device adapter, not a public
     * program command. Exact expected-order matching guards against stale evidence; authority
     * revocation cannot erase facts from an effect that has already happened. No dispatch occurs.
     */
    public synchronized Reply recordEvidence(Order expected, DataValue evidence, Phase phase, String diagnostic) {
        if (expected == null || evidence == null || phase == null || diagnostic == null
            || (phase != Phase.WAITING && phase != Phase.COMPLETE && phase != Phase.REVIEW_REQUIRED))
            return reply("invalid_evidence", null);
        if (advancing) return reply("busy", null);
        final Snapshot state;
        try { state = journal.load(); }
        catch (RuntimeException unavailable) { return reply("storage_unavailable", null); }
        var current = state.orders().get(expected.key());
        if (current == null || current.phase() != Phase.WAITING || !current.equals(expected))
            return reply("storage_changed", current);
        if (phase == Phase.COMPLETE && current.step() != current.plan().steps().size() - 1)
            return reply("invalid_evidence", current);
        var next = new Order(current.key(), current.request(), current.plan(), current.step(),
            phase, current.receipt(), evidence, diagnostic);
        if (next.equals(current)) return reply("progressed", current);
        // Share the ordinary order/step/value bounds and journal CAS. Never bypass save.
        return update(state, current, phase, current.receipt(), evidence, diagnostic, state.cursor());
    }
    /** At most one bounded native operation or observation. The owning device supplies the tick budget. */
    public synchronized Reply advance() {
        if (advancing) return reply("busy", null);
        advancing = true;
        try { return advanceOnce(); } finally { advancing = false; }
    }
    private Reply advanceOnce() {
        final Snapshot state;
        try { state = journal.load(); } catch (RuntimeException unavailable) { return reply("storage_unavailable", null); }
        var orders = List.copyOf(state.orders().values());
        for (int offset = 0; offset < orders.size(); offset++) {
            int index = (state.cursor() + offset) % orders.size();
            Order order = orders.get(index);
            if (!order.active() || !backend.inScope(order)) continue;
            int cursor = (index + 1) % orders.size();
            if (!backend.supports(order.plan().extension(), order.plan().version()))
                return defer(state, order, cursor, "unsupported_extension");
            if (order.phase() == Phase.DISPATCHING)
                return update(state, order, Phase.REVIEW_REQUIRED, order.receipt(), order.result(), "uncertain_dispatch", cursor);
            if (order.phase() == Phase.WAITING) {
                final Observation observation;
                try { observation = Objects.requireNonNull(backend.observe(order.operation(), order.receipt().orElseThrow())); }
                catch (RuntimeException unavailable) { return defer(state, order, cursor, "device_unavailable"); }
                if (observation.kind() == ObservationKind.PENDING) {
                    // Rotate even a waiting order so a slow machine cannot monopolize the device service.
                    return defer(state, order, cursor, "waiting");
                }
                if (observation.kind() == ObservationKind.FAILED)
                    return update(state, order, Phase.FAILED, order.receipt(), observation.result(), observation.code(), cursor);
                if (order.step() + 1 == order.plan().steps().size())
                    return update(state, order, Phase.COMPLETE, order.receipt(), observation.result(), "complete", cursor);
                var next = new Order(order.key(), order.request(), order.plan(), order.step() + 1,
                    Phase.QUEUED, Optional.empty(), DataValue.NullValue.INSTANCE, "queued");
                String saved = save(state, next, cursor);
                return reply(saved == null ? "progressed" : saved, saved == null ? next : order);
            }
            if (!backend.authorized(order.key().source()))
                return update(state, order, Phase.FAILED, Optional.empty(), DataValue.NullValue.INSTANCE, "denied", cursor);
            var attempting = new Order(order.key(), order.request(), order.plan(), order.step(),
                Phase.DISPATCHING, Optional.empty(), DataValue.NullValue.INSTANCE, "dispatching");
            String saved = save(state, attempting, cursor);
            if (saved != null) return reply(saved, order);
            // Reload the exact durable candidate before the irreversible boundary. Any competing
            // publication prevents this attempt, rather than inventing a successful receipt.
            final Snapshot committed;
            try { committed = journal.load(); }
            catch (RuntimeException unavailable) { return reply("storage_unavailable", attempting); }
            if (committed.revision() != state.revision() + 1 || !attempting.equals(committed.orders().get(order.key())))
                return reply("storage_changed", attempting);
            final Dispatch dispatch;
            try { dispatch = Objects.requireNonNull(backend.dispatch(order.operation(), order.plan().steps().get(order.step()))); }
            catch (RuntimeException uncertain) {
                return update(committed, attempting, Phase.REVIEW_REQUIRED, Optional.empty(), DataValue.NullValue.INSTANCE, "uncertain_dispatch", cursor);
            }
            return update(committed, attempting, dispatch.receipt().isPresent() ? Phase.WAITING : Phase.FAILED,
                dispatch.receipt(), DataValue.NullValue.INSTANCE, dispatch.code(), cursor);
        }
        return reply("idle", null);
    }
    /** A temporarily unavailable device/extension must not monopolize the shared order service. */
    private Reply defer(Snapshot state, Order order, int cursor, String code) {
        if (cursor == state.cursor()) return reply(code, order);
        String saved = save(state, order, cursor);
        return reply(saved == null ? code : saved, order);
    }
    private Reply update(Snapshot state, Order order, Phase phase, Optional<DataValue.RecordValue> receipt,
                         DataValue result, String code, int cursor) {
        var next = new Order(order.key(), order.request(), order.plan(), order.step(), phase, receipt, result, code);
        String saved = save(state, next, cursor);
        return reply(saved == null ? "progressed" : saved, saved == null ? next : order);
    }
    private String save(Snapshot before, Order order, int cursor) {
        if (before.revision() == Long.MAX_VALUE) return "version_exhausted";
        var orders = new LinkedHashMap<>(before.orders()); orders.put(order.key(), order);
        if (orders.size() > limits.orders()) return "capacity";
        long units = 0;
        try {
            for (var value : orders.values()) {
                if (value.plan().steps().size() > limits.steps()) return "capacity";
                units = Math.addExact(units, value.units());
            }
        } catch (ArithmeticException overflow) { return "capacity"; }
        if (units > limits.valueUnits()) return "capacity";
        try {
            return journal.commit(before.revision(), new Snapshot(before.revision() + 1, cursor, orders, before.materialGrant())) ? null : "storage_changed";
        } catch (RuntimeException unavailable) { return "storage_unavailable"; }
    }
}

