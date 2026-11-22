package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;

/** Device-owned public application data. No world recipes, program RAM or production effects. */
public final class StockApplication {
    private StockApplication() {}
    /** Caller-supplied balance limits. Units are canonical DataValue occurrences, not heap bytes or free RAM.
     * textChars bounds entry strings and nested public keys/text; addressChars bounds each input address. */
    public record Limits(int entries, int inputs, long retainedUnits, int textChars, int addressChars) {
        public Limits { if (entries < 0 || inputs < 0 || retainedUnits < 1 || textChars < 1 || addressChars < 1) throw new IllegalArgumentException("invalid_limits"); }
    }
    public record Application(UUID computer, UUID program) {
        public Application { Objects.requireNonNull(computer); Objects.requireNonNull(program); }
    }
    /** Trusted current host observation, never decoded from a command. Completion is deliberately not a denial. */
    public record Access(Application application, boolean available, boolean paused) {
        public Access { Objects.requireNonNull(application); }
    }
    public record Fence(UUID device, UUID session, long revision) {
        public Fence { Objects.requireNonNull(device); Objects.requireNonNull(session); if (revision < 0) throw new IllegalArgumentException("invalid_revision"); }
    }
    public record Entry(String key, String title, String unit, DataValue.RecordValue icon, DataValue.RecordValue data) {
        public Entry { Objects.requireNonNull(key); Objects.requireNonNull(title); Objects.requireNonNull(unit); Objects.requireNonNull(icon); Objects.requireNonNull(data); }
    }
    public record Selection(String key, long quantity) {
        public Selection { Objects.requireNonNull(key); }
    }
    /** Catalog requests contain keys only. Native orders are separately captured by the trusted Create boundary. */
    public record Request(Fence fence, long sequence, List<Selection> selections, String address,
                          Optional<DataValue.NbtValue> nativeOrder) {
        public Request {
            Objects.requireNonNull(fence); selections = List.copyOf(selections); Objects.requireNonNull(address);
            Objects.requireNonNull(nativeOrder);
            if (nativeOrder.isPresent() && !selections.isEmpty()) throw new IllegalArgumentException("invalid_input");
        }
        public Request(Fence fence, long sequence, List<Selection> selections, String address) {
            this(fence, sequence, selections, address, Optional.empty());
        }
        public static Request nativeOrder(Fence fence, long sequence, String address, DataValue.NbtValue order) {
            return new Request(fence, sequence, List.of(), address, Optional.of(order));
        }
        public String kind() { return nativeOrder.isPresent() ? "native_order" : "catalog"; }
    }
    public record Item(Entry entry, long quantity) { public Item { Objects.requireNonNull(entry); } }
    public record Input(UUID device, long sequence, Application application, UUID player, Request request, List<Item> items) {
        public Input { Objects.requireNonNull(device); Objects.requireNonNull(application); Objects.requireNonNull(player); Objects.requireNonNull(request); items = List.copyOf(items); }
    }
    public record State(UUID device, UUID session, long revision, Optional<Application> application, boolean open,
                        long nextSequence, List<Entry> catalog, List<Input> inputs) {
        public State {
            Objects.requireNonNull(device); Objects.requireNonNull(session); Objects.requireNonNull(application);
            catalog = List.copyOf(catalog); inputs = List.copyOf(inputs);
            if (revision < 0 || nextSequence < 1 || open && application.isEmpty()) throw new IllegalArgumentException("invalid_state");
        }
        public Fence fence() { return new Fence(device, session, revision); }
    }
    public record Update(String code, State state, Optional<Input> input) {}
    private static Update result(String code, State state) { return new Update(code, state, Optional.empty()); }
    public static State closed(UUID device, UUID session) { return new State(device, session, 0, Optional.empty(), false, 1, List.of(), List.of()); }
    public static Update bind(State before, Application application, UUID session) {
        Objects.requireNonNull(application); Objects.requireNonNull(session);
        if (before.session().equals(session)) return result("session_reused", before);
        boolean same = before.application().filter(application::equals).isPresent();
        if (!same && !before.inputs().isEmpty()) return result("busy", before);
        if (before.revision() == Long.MAX_VALUE) return result("version_exhausted", before);
        return result("bound", new State(before.device(), session, before.revision()+1, Optional.of(application), true, before.nextSequence(), same ? before.catalog() : List.of(), before.inputs()));
    }
    /** Administrative revocation closes access, retaining identity, catalog and input watermark. */
    public static State revoke(State before, UUID session) {
        if (before.session().equals(session)) throw new IllegalArgumentException("session_reused");
        return new State(before.device(),session,before.revision(),before.application(),false,before.nextSequence(),before.catalog(),before.inputs());
    }
    public static Update replace(State before, Access access, Fence fence, List<Entry> catalog, Limits limits) {
        String error = fenceError(before, access, fence);
        if (error != null) return result(error, before);
        if (before.revision() == Long.MAX_VALUE) return result("version_exhausted", before);
        try {
            if (catalog.size() > Math.min(limits.entries(), StockApplicationValues.MAX_MEMBERS)) return result("capacity", before);
            var after = new State(before.device(), before.session(), before.revision()+1, before.application(), before.open(), before.nextSequence(), catalog, before.inputs());
            checkCapacity(after, limits);
            return result("accepted", after);
        } catch (IllegalArgumentException invalid) { return result(invalid.getMessage(), before); }
          catch (com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException | ArithmeticException overflow) { return result("capacity", before); }
    }
    public static Update confirm(State before, Access access, UUID player, Request request, Limits limits) {
        Objects.requireNonNull(player);
        String error = accessError(before, access);
        if (error == null && !before.device().equals(request.fence().device())) error = "device_changed";
        if (error != null) return result(error, before);
        // A retained exact receipt is read-only: session refresh fences new admissions, not this observation.
        if (request.sequence() < before.nextSequence()) {
            var existing = before.inputs().stream().filter(i -> i.sequence() == request.sequence()).findFirst();
            if (existing.isEmpty()) return result("retired", before);
            var input = existing.orElseThrow();
            return input.player().equals(player) && input.request().equals(request)
                ? new Update("existing", before, existing) : result("conflict", before);
        }
        error = sessionError(before, request.fence());
        if (error != null) return result(error, before);
        if (request.sequence() != before.nextSequence()) return result("stale_sequence", before);
        if (request.fence().revision() != before.revision()) return result("stale_revision", before);
        if (before.nextSequence() == Long.MAX_VALUE) return result("sequence_exhausted", before);
        if (request.selections().isEmpty() && request.nativeOrder().isEmpty()) return result("invalid_input", before);
        if (request.selections().size() > limits.entries() || request.address().length() > limits.addressChars()) return result("capacity", before);
        if (request.address().isBlank()) return result("invalid_address", before);
        var keys = new HashSet<String>();
        var items = new ArrayList<Item>();
        for (var selected : request.selections()) {
            if (selected.quantity() < 1 || !keys.add(selected.key())) return result("invalid_input", before);
            var entry = before.catalog().stream().filter(e -> e.key().equals(selected.key())).findFirst();
            if (entry.isEmpty()) return result("unknown_entry", before);
            items.add(new Item(entry.orElseThrow(), selected.quantity()));
        }
        var input = new Input(before.device(), before.nextSequence(), before.application().orElseThrow(), player, request, items);
        var queue = new ArrayList<>(before.inputs()); queue.add(input);
        var after = new State(before.device(), before.session(), before.revision(), before.application(), before.open(), before.nextSequence()+1, before.catalog(), queue);
        try { checkCapacity(after, limits); }
        catch (IllegalArgumentException invalid) { return result(invalid.getMessage(), before); }
        catch (com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException | ArithmeticException overflow) { return result("capacity", before); }
        return new Update("input_queued", after, Optional.of(input));
    }
    public static Update poll(State before, Access access, Fence fence) {
        String error = fenceError(before, access, fence);
        if (error != null) return result(error, before);
        return new Update(before.inputs().isEmpty() ? "empty" : "input", before, before.inputs().stream().findFirst());
    }
    public static Update ack(State before, Access access, Fence fence, long sequence) {
        String error = fenceError(before, access, fence);
        if (error != null) return result(error, before);
        if (before.inputs().isEmpty() || before.inputs().getFirst().sequence() != sequence) return result("stale_input", before);
        return result("acknowledged", new State(before.device(), before.session(), before.revision(), before.application(), before.open(), before.nextSequence(), before.catalog(), before.inputs().subList(1, before.inputs().size())));
    }

    /** Full logical state envelope including metadata, catalog and every frozen input/request snapshot.
     * Serial codec/queue and save representation budgets are separately enforced by their existing owners. */
    public static long retainedUnits(State state) { return StockApplicationValues.state(state).storageUnits(); }
    private static void checkCapacity(State state, Limits limits) {
        if (state.catalog().size() > limits.entries() || state.inputs().size() > limits.inputs()
            || state.catalog().size() > StockApplicationValues.MAX_MEMBERS || state.inputs().size() > StockApplicationValues.MAX_MEMBERS
            || retainedUnits(state) > Math.min(limits.retainedUnits(), StockApplicationValues.FORMAT.maxValueUnits())) throw new IllegalArgumentException("capacity");
        StockApplicationValues.validateEntries(state.catalog(), Math.min(limits.textChars(), StockApplicationValues.MAX_TEXT));
        for (var input : state.inputs()) {
            StockApplicationValues.validateEntries(input.items().stream().map(Item::entry).toList(), Math.min(limits.textChars(), StockApplicationValues.MAX_TEXT));
            input.request().nativeOrder().ifPresent(order -> {
                StockNativeOrder.validate(order, Math.min(limits.entries(), StockApplicationValues.MAX_MEMBERS));
                StockApplicationValues.checkText(order, Math.min(limits.textChars(), StockApplicationValues.MAX_TEXT));
            });
            StockApplicationValues.textLength(input.request().address(), Math.min(limits.addressChars(), StockApplicationValues.MAX_TEXT));
        }
        try { com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.encode(StockApplicationValues.state(state), StockApplicationValues.FORMAT); }
        catch (com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException invalid) { throw new IllegalArgumentException("capacity"); }
    }
    public static String accessError(State state, Access access) {
        if (!state.open()) return "closed";
        if (!state.application().orElseThrow().equals(access.application())) return "permission";
        if (!access.available()) return "unavailable";
        if (access.paused()) return "paused";
        return null;
    }
    private static String sessionError(State state, Fence fence) {
        if (!state.device().equals(fence.device())) return "device_changed";
        if (!state.session().equals(fence.session())) return "stale_session";
        return null;
    }
    static String fenceError(State state, Access access, Fence fence) {
        String error = accessError(state, access);
        if (error == null) error = sessionError(state, fence);
        return error != null ? error : state.revision() != fence.revision() ? "stale_revision" : null;
    }
}
