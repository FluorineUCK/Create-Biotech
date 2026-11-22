package com.nobodiiiii.createbiotech.content.biocomputing.network;

import com.mojang.logging.LogUtils;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.*;
import net.neoforged.neoforge.common.IOUtilities;
import org.slf4j.Logger;

/** Dimension-owned identity witnesses. Not wiring, a mailbox, RAM, or permission to replay data. */
public final class XBusHistorySavedData extends SavedData {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String DATA_NAME = "create_biotech_xbus_history";
    public static final int MAX_COMPUTERS = 65536, MAX_CLAIMS = 262144;
    private record Operation(UUID computer, UUID flow, long generation, long operation) {
        static Operation of(XBusReceipt.Position p) { return new Operation(p.computer(), p.flow(), p.generation(), p.operation()); }
    }
    private record Claim(XBusReceipt.Position writer, XBusReceipt.Position reader,
            List<XBusReceipt.Position> otherWriters, List<XBusReceipt.Position> otherReaders) {
        Claim {
            Objects.requireNonNull(writer); Objects.requireNonNull(reader);
            otherWriters = List.copyOf(otherWriters); otherReaders = List.copyOf(otherReaders);
            if (writer.computer().equals(reader.computer())) throw new IllegalArgumentException("self_claim");
            var seen = new HashSet<List<UUID>>(); var all = new ArrayList<>(List.of(writer, reader));
            all.addAll(otherWriters); all.addAll(otherReaders);
            for (var p : all) {
                if (!seen.add(List.of(p.computer(), p.flow()))) throw new IllegalArgumentException("duplicate_context");
                new com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins(List.of(p.pin()));
            }
        }
        Claim(XBusReceipt.Position writer, XBusReceipt.Position reader) { this(writer, reader, List.of(), List.of()); }
        static Claim of(XBusReceipt r) { return new Claim(r.writer(), r.reader(), r.otherWriters(), r.otherReaders()); }
        boolean matches(XBusReceipt r) {
            return writer.equals(r.writer()) && reader.equals(r.reader())
                && otherWriters.equals(r.otherWriters()) && otherReaders.equals(r.otherReaders());
        }
        List<XBusReceipt.Position> ends() { var all = new ArrayList<>(List.of(writer, reader)); all.addAll(otherWriters); all.addAll(otherReaders); return List.copyOf(all); }
        long units() { return 1L + otherWriters.size() + otherReaders.size(); }
        boolean extended() { return units() != 1 || ends().stream().anyMatch(p -> p.slot() != 0); }
        List<XBusNetwork.Selection> repairs() {
            var result = new LinkedHashSet<XBusNetwork.Selection>(); result.add(new XBusNetwork.Selection(pin(reader), pin(writer)));
            otherWriters.forEach(p -> result.add(new XBusNetwork.Selection(pin(reader), pin(p))));
            otherReaders.forEach(p -> result.add(new XBusNetwork.Selection(pin(p), pin(writer)))); return List.copyOf(result);
        }
    }
    private Map<UUID, FlowControl.Peer> frontiers = new LinkedHashMap<>();
    private Set<Claim> claims = new LinkedHashSet<>();
    /** Exact, already-enrolled claims that no longer need a payload from any owner. */
    private final Set<Claim> settled = new HashSet<>();
    private volatile Set<Claim> durableSettled = Set.of();
    /** The bounded identity frontiers from the last successful disk write, never payloads. */
    private volatile Map<UUID, FlowControl.Peer> durableFrontiers = Map.of();
    private volatile Object retirementWitness = new Object();
    private volatile boolean settlementWriteFailed;
    private Set<XBusCancellation> cancellations = new LinkedHashSet<>();
    /** An unconsumed physical occupancy is only an identity witness, never a pending payload. */
    private record Occupancy(XBusReceipt.Position writer, Set<XBusNetwork.Pin> pins, Set<XBusReceipt.Position> writers, boolean complete) {
        Occupancy {
            Objects.requireNonNull(writer); pins = Set.copyOf(pins); writers = Set.copyOf(writers);
            if (!pins.contains(pin(writer))) throw new IllegalArgumentException("missing_writer_pin");
            if (!writers.contains(writer)) throw new IllegalArgumentException("missing_writer");
            // A result-style write may finish on a paused peer and the same flow may
            // try again. Keep both operation identities until the held bus retires.
            var operations = new HashSet<Operation>();
            for (var p : writers) {
                if (!pins.contains(pin(p))) throw new IllegalArgumentException("invalid_writer_pin");
                if (!operations.add(Operation.of(p))) throw new IllegalArgumentException("duplicate_writer_operation");
            }
            for (var pin : pins) new com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins(List.of(pin.pin()));
        }
        long units() { return (long)writers.size() + pins.size(); }
    }
    private final Set<Occupancy> occupancies = new LinkedHashSet<>();
    private final Map<XBusNetwork.Pin, Occupancy> occupiedPins = new HashMap<>();
    private final Map<Operation, Claim> operationClaims = new HashMap<>();
    /** Reuse only an exactly matching, already validated identity. Operation is an index,
     * not evidence by itself. No payload, decision or extra history is cached here. */
    private Claim receiptClaim(XBusReceipt receipt) {
        var indexed = operationClaims.get(Operation.of(receipt.writer()));
        return indexed != null && indexed.matches(receipt) ? indexed : Claim.of(receipt);
    }
    private final Map<UUID, Set<Claim>> computerClaims = new HashMap<>();
    private CompoundTag unreadable;
    private boolean unreadableFile;

    /** Lifecycle-only identity query, never another Computer's memory. */
    public boolean tracks(UUID computer) {
        if (unreadableFile || unreadable != null) throw new IllegalArgumentException("invalid_save");
        return frontiers.containsKey(Objects.requireNonNull(computer));
    }

    public static XBusHistorySavedData get(ServerLevel level) {
        var storage = level.getDataStorage();
        var existing = storage.get(new Factory<>(XBusHistorySavedData::new, XBusHistorySavedData::load), DATA_NAME);
        if (existing != null) return existing;
        var directory = DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT)).resolve("data");
        return createMissing(storage, directory);
    }
    static XBusHistorySavedData get(DimensionDataStorage storage, Path directory) {
        var existing = storage.get(new Factory<>(XBusHistorySavedData::new, XBusHistorySavedData::load), DATA_NAME);
        return existing != null ? existing : createMissing(storage, directory);
    }
    private static XBusHistorySavedData createMissing(DimensionDataStorage storage, Path directory) {
        boolean absent = false;
        try { Files.readAttributes(directory.resolve(DATA_NAME + ".dat"), BasicFileAttributes.class); }
        catch (NoSuchFileException missing) { absent = true; }
        catch (IOException | SecurityException inaccessible) { /* Read failure is never an empty history. */ }
        var created = new XBusHistorySavedData(); created.unreadableFile = !absent;
        storage.set(DATA_NAME, created); return created;
    }

    /** Observes already validated owner publications; merges monotonically and never loads an old peer. */
    public Optional<XBusExchange.Status> observe(List<FlowBlockHost.Saved> owners, int computerLimit, int claimLimit) {
        if (unreadableFile || unreadable != null) return Optional.of(XBusExchange.Status.UNAVAILABLE);
        // Normal instruction rounds do not copy or scan unrelated world history.
        boolean changed = false;
        try {
            for (var saved : owners) {
                changed |= changes(frontiers.get(saved.state().computer()), FlowControl.Peer.of(saved));
                for (var entry : saved.control().peers().entrySet()) changed |= changes(frontiers.get(entry.getKey()), entry.getValue());
                for (var witness : saved.cancellations()) changed |= !cancellations.contains(witness)
                    || changes(frontiers.get(witness.holder().computer()), witness.proof());
                for (var receipt : saved.receipts()) {
                    var claim = receiptClaim(receipt);
                    changed |= consumedOccupancy(claim) != null;
                    if (retiredClaim(frontiers, claim)) continue;
                    check(operationClaims, claim); changed |= !claims.contains(claim);
                }
            }
        } catch (IllegalArgumentException conflict) { return Optional.of(XBusExchange.Status.HISTORY_CONFLICT); }
        changed |= occupancies.stream().anyMatch(hold -> fullyRetired(hold, frontiers));
        changed |= cancellations.stream().anyMatch(c -> retiredCancellation(c, frontiers));
        if (!changed) return Optional.empty();
        var nextFrontiers = new LinkedHashMap<>(frontiers); var nextClaims = new LinkedHashSet<>(claims);
        var nextCancellations = new LinkedHashSet<>(cancellations);
        var nextOperations = new HashMap<>(operationClaims);
        var consumed = new LinkedHashSet<Occupancy>();
        try {
            for (var saved : owners) {
                merge(nextFrontiers, saved.state().computer(), FlowControl.Peer.of(saved));
                for (var entry : saved.control().peers().entrySet()) merge(nextFrontiers, entry.getKey(), entry.getValue());
                for (var witness : saved.cancellations()) {
                    // A single owner may be newer than the dimension journal while the
                    // other competitors are unloaded. Retain every known identity, but
                    // do not invent consumption or retirement for those competitors.
                    for (var writer : witness.writers()) merge(nextFrontiers, writer.computer(), FlowControl.Peer.at(writer, -1));
                    merge(nextFrontiers, witness.holder().computer(), witness.proof());
                    addCancellation(nextCancellations, nextOperations, witness);
                }
                for (var receipt : saved.receipts()) {
                    var claim = receiptClaim(receipt);
                    var held = consumedOccupancy(claim); if (held != null) consumed.add(held);
                    if (!retiredClaim(nextFrontiers, claim)) add(nextClaims, nextOperations, claim);
                }
            }
            compact(nextFrontiers, nextClaims);
            for (var witness : nextCancellations) {
                if (witness.writers().stream().anyMatch(p -> nextOperations.containsKey(Operation.of(p)))) throw new IllegalArgumentException("consumed_cancellation");
                for (var hold : occupancies) if (hold.writer().equals(witness.holder())) consumed.add(hold);
            }
            for (var hold : occupancies) if (fullyRetired(hold, nextFrontiers)) consumed.add(hold);
            nextCancellations.removeIf(c -> retiredCancellation(c, nextFrontiers));
        } catch (IllegalArgumentException conflict) { return Optional.of(XBusExchange.Status.HISTORY_CONFLICT); }
        if (growingBeyond(nextFrontiers.size(), frontiers.size(), computerLimit, MAX_COMPUTERS)
            || growingBeyond(historyUnits(nextFrontiers, nextClaims) - consumed.stream().mapToLong(Occupancy::units).sum()
                + cancellationUnits(nextCancellations) - cancellationUnits(cancellations), historyUnits(frontiers, claims), claimLimit, MAX_CLAIMS))
            return Optional.of(XBusExchange.Status.ARCHIVE_LIMIT);
        if (!nextFrontiers.equals(frontiers) || !nextClaims.equals(claims) || !nextCancellations.equals(cancellations) || !consumed.isEmpty()) {
            frontiers = nextFrontiers; claims = nextClaims; cancellations = nextCancellations;
            settled.retainAll(claims); consumed.forEach(this::remove); rebuildIndices(); setDirty();
        }
        return Optional.empty();
    }
    private static boolean growingBeyond(long next, long old, int configured, int hard) {
        return next > hard || next > Math.max(0, configured) && next > old;
    }
    /** Main identities already count toward computerLimit. Child-slot witnesses share the bounded
     * history budget with claims; task creation never grants an unbounded dimension-side store. */
    private long historyUnits(Map<UUID, FlowControl.Peer> frontiers, Set<Claim> claims) {
        return claims.stream().mapToLong(Claim::units).sum() + occupancies.stream().mapToLong(Occupancy::units).sum()
            + cancellationUnits(cancellations)
            + frontiers.values().stream().mapToLong(p -> p.slots().size() - (p.slots().containsKey(0) ? 1 : 0)).sum();
    }
    private static long cancellationUnits(Collection<XBusCancellation> witnesses) { return witnesses.stream().mapToLong(XBusCancellation::units).sum(); }
    private static boolean retiredCancellation(XBusCancellation c, Map<UUID, FlowControl.Peer> frontiers) {
        return c.writers().stream().allMatch(p -> frontiers.containsKey(p.computer()) && frontiers.get(p.computer()).supersedes(p));
    }
    private static void addCancellation(Set<XBusCancellation> into, Map<Operation, Claim> claims, XBusCancellation candidate) {
        for (var p : candidate.writers()) {
            if (claims.containsKey(Operation.of(p))) throw new IllegalArgumentException("consumed_cancellation");
            for (var old : into) if (!old.equals(candidate) && old.writers().stream().anyMatch(q -> Operation.of(q).equals(Operation.of(p))))
                throw new IllegalArgumentException("cancellation_conflict");
        }
        into.add(candidate);
    }
    /** Only an explicit generation/slot retirement can create a cancellation; disconnection cannot. */
    Optional<XBusCancellation> cancellation(Map<XBusNetwork.Pin, FlowBlockHost.Saved> owners) {
        for (var witness : cancellations) for (var entry : owners.entrySet())
            if (witness.writers().stream().anyMatch(p -> pin(p).equals(entry.getKey())) && witness.pending(entry.getValue(), entry.getKey().pin())
                && (!entry.getValue().cancellations().contains(witness) || witness.activePending(entry.getValue(), entry.getKey().pin()))) return Optional.of(witness);
        for (var hold : occupancies) {
            if (!hold.complete() || !hold.pins().equals(owners.keySet())) continue;
            var proof = frontiers.get(hold.writer().computer());
            if (proof != null && proof.supersedes(hold.writer())) return Optional.of(new XBusCancellation(hold.writer(), hold.writers(), proof));
        }
        return Optional.empty();
    }
    /** Monotone retirement fence: unlike a delivery reservation it never rolls back on dirty failure. */
    Optional<XBusExchange.Status> reserveCancellation(XBusCancellation witness, int limit) {
        var next = new LinkedHashSet<>(cancellations);
        try { addCancellation(next, operationClaims, witness); }
        catch (IllegalArgumentException conflict) { return Optional.of(XBusExchange.Status.HISTORY_CONFLICT); }
        var held = occupancies.stream().filter(h -> h.writer().equals(witness.holder())).findFirst();
        if (held.isPresent() && (!held.get().complete() || !held.get().writers().equals(witness.writers()))) return Optional.of(XBusExchange.Status.HISTORY_CONFLICT);
        long old = historyUnits(frontiers, claims), after = old + cancellationUnits(next) - cancellationUnits(cancellations) - held.map(Occupancy::units).orElse(0L);
        if (growingBeyond(after, old, limit, MAX_CLAIMS)) return Optional.of(XBusExchange.Status.ARCHIVE_LIMIT);
        cancellations = next; merge(frontiers, witness.holder().computer(), witness.proof()); held.ifPresent(this::remove); setDirty(); return Optional.empty();
    }

    /** Caller has validated the complete loaded physical component and all owner identities. */
    XBusAccess.WriterSelection selectWriter(List<XBusNetwork.Pin> ordered, Map<XBusNetwork.Pin, FlowBlockHost.Saved> owners, int limit) {
        var previous = occupiedWriter(owners);
        if (previous.rejection().isPresent()) return previous;
        if (previous.writer().isPresent()) {
            var old = occupiedPins.get(previous.writer().orElseThrow());
            var writers = new LinkedHashSet<>(old.writers()); writers.addAll(waitingWriters(owners));
            // Seeing today's waiters cannot reconstruct an older schema's missing
            // competitors (one may already have returned a paused result). Keep that
            // uncertainty across saves instead of authorizing retirement by omission.
            var next = new Occupancy(old.writer(), old.pins(), writers, old.complete());
            if (!next.equals(old)) {
                long retained = historyUnits(frontiers, claims);
                if (growingBeyond(retained + next.units() - old.units(), retained, limit, MAX_CLAIMS)) return XBusAccess.WriterSelection.denied(XBusExchange.Status.ARCHIVE_LIMIT);
                remove(old); add(next); setDirty();
            }
            return previous;
        }
        for (var pin : ordered) {
            var saved = owners.get(pin); var state = saved.pinState(pin.pin());
            if (saved.state().paused() || state.paused() || !writing(saved, state, pin.pin())) continue;
            var position = saved.position(state, pin.pin()); var known = frontiers.get(pin.computer());
            if (known != null && known.check(position).isPresent()) return XBusAccess.WriterSelection.denied(known.check(position).orElseThrow());
            var hold = new Occupancy(position, owners.keySet(), waitingWriters(owners), true);
            long retained = historyUnits(frontiers, claims);
            if (growingBeyond(retained + hold.units(), retained, limit, MAX_CLAIMS)) return XBusAccess.WriterSelection.denied(XBusExchange.Status.ARCHIVE_LIMIT);
            add(hold); setDirty(); return new XBusAccess.WriterSelection(Optional.of(pin), Optional.empty());
        }
        return new XBusAccess.WriterSelection(Optional.empty(), Optional.empty());
    }
    private Set<XBusReceipt.Position> waitingWriters(Map<XBusNetwork.Pin, FlowBlockHost.Saved> owners) {
        var result = new LinkedHashSet<XBusReceipt.Position>();
        owners.forEach((pin, saved) -> {
            for (var claim : saved.pinClaims().entries()) if (claim.pin().equals(pin.pin())) {
                var state = saved.context(claim.flow()).orElseThrow();
                var position = saved.position(state, pin.pin());
                if (writing(saved, state, pin.pin()) && cancellations.stream().noneMatch(c -> c.writers().contains(position))) result.add(position);
            }
        });
        return result;
    }
    private static boolean fullyRetired(Occupancy hold, Map<UUID, FlowControl.Peer> frontiers) {
        return hold.complete() && hold.writers().stream().allMatch(p -> {
            var known = frontiers.get(p.computer()); return known != null && known.supersedes(p);
        });
    }
    /** Pure observation: never admits, retires, or chooses a replacement for an unavailable holder. */
    XBusAccess.WriterSelection occupiedWriter(Map<XBusNetwork.Pin, FlowBlockHost.Saved> owners) {
        if (unreadableFile || unreadable != null) return XBusAccess.WriterSelection.denied(XBusExchange.Status.UNAVAILABLE);
        for (var witness : cancellations) for (var p : witness.writers()) {
            var owner = owners.get(pin(p)); if (owner == null) continue;
            var context = owner.context(p); if (context.isEmpty()) continue;
            if (context.get().operation() < p.operation()) return XBusAccess.WriterSelection.denied(XBusExchange.Status.RECOVERY_REQUIRED);
            if (witness.activePending(owner, p.pin())) return XBusAccess.WriterSelection.denied(XBusExchange.Status.RETIRED);
        }
        var relevant = new HashSet<Occupancy>();
        for (var pin : owners.keySet()) { var hold = occupiedPins.get(pin); if (hold != null) relevant.add(hold); }
        if (relevant.isEmpty()) return new XBusAccess.WriterSelection(Optional.empty(), Optional.empty());
        if (relevant.size() != 1) return XBusAccess.WriterSelection.denied(XBusExchange.Status.RECOVERY_REQUIRED);
        var hold = relevant.iterator().next();
        if (!hold.pins().equals(owners.keySet())) return XBusAccess.WriterSelection.denied(XBusExchange.Status.RECOVERY_REQUIRED);
        var pin = pin(hold.writer()); var saved = owners.get(pin); var state = saved.pinState(pin.pin());
        if (!saved.position(state, pin.pin()).equals(hold.writer()) || !writing(saved, state, pin.pin()))
            return XBusAccess.WriterSelection.denied(XBusExchange.Status.RECOVERY_REQUIRED);
        var known = frontiers.get(pin.computer());
        if (known != null && known.check(hold.writer()).isPresent()) return XBusAccess.WriterSelection.denied(known.check(hold.writer()).orElseThrow());
        if (saved.state().paused() || state.paused()) return XBusAccess.WriterSelection.denied(XBusExchange.Status.PAUSED);
        return new XBusAccess.WriterSelection(Optional.of(pin), Optional.empty());
    }
    private static boolean writing(FlowBlockHost.Saved saved, FlowRuntime.Checkpoint state, String pin) {
        return state.status() == FlowRuntime.Status.WAITING_WRITE && state.cursor() < saved.program().steps().size()
            && pin.equals(FlowXBus.pin(saved.program(), state));
    }
    private void add(Occupancy hold) {
        if (hold.pins().stream().anyMatch(occupiedPins::containsKey)) throw new IllegalArgumentException("overlapping_occupancy");
        occupancies.add(hold); hold.pins().forEach(pin -> occupiedPins.put(pin, hold));
    }
    private void remove(Occupancy hold) { occupancies.remove(hold); hold.pins().forEach(pin -> occupiedPins.remove(pin, hold)); }
    private Occupancy consumedOccupancy(Claim claim) {
        for (var end : claim.ends()) {
            var occupied = occupiedPins.get(pin(end));
            if (occupied != null && occupied.writer().equals(end) && !end.equals(claim.writer()))
                throw new IllegalArgumentException("occupancy_writer_conflict");
        }
        var hold = occupiedPins.get(pin(claim.writer()));
        return hold != null && hold.writer().equals(claim.writer()) ? hold : null;
    }
    private static void merge(Map<UUID, FlowControl.Peer> into, UUID id, FlowControl.Peer candidate) {
        var old = into.get(id);
        into.put(id, old == null ? candidate : old.merge(candidate));
    }
    private static boolean changes(FlowControl.Peer old, FlowControl.Peer candidate) {
        return old == null || !old.merge(candidate).equals(old);
    }
    private static void check(Map<Operation, Claim> index, Claim candidate) {
        for (var end : candidate.ends()) {
            var old = index.get(Operation.of(end));
            if (old != null && !old.equals(candidate)) throw new IllegalArgumentException("claim_conflict");
        }
    }
    private static void add(Set<Claim> into, Map<Operation, Claim> index, Claim candidate) {
        check(index, candidate);
        into.add(candidate);
        for (var end : candidate.ends()) index.put(Operation.of(end), candidate);
    }
    private void rebuildIndices() {
        operationClaims.clear(); computerClaims.clear();
        for (var claim : claims) index(claim);
    }
    private void index(Claim claim) {
        for (var end : claim.ends()) {
            operationClaims.put(Operation.of(end), claim);
            computerClaims.computeIfAbsent(end.computer(), ignored -> new LinkedHashSet<>()).add(claim);
        }
    }
    private void remove(Claim claim) {
        claims.remove(claim);
        settled.remove(claim);
        for (var end : claim.ends()) {
            operationClaims.remove(Operation.of(end), claim);
            var group = computerClaims.get(end.computer());
            if (group != null) { group.remove(claim); if (group.isEmpty()) computerClaims.remove(end.computer()); }
        }
    }
    /**
     * Mark an already enrolled claim only when every member's current validated owner has the
     * same completed receipt. Reservation, a later PC or a partial set of owners is not proof.
     * The marker adds no payload or second claim, and must be durable before an owner deletes.
     */
    boolean certifyCompleted(XBusReceipt receipt, List<FlowBlockHost.Saved> owners) {
        if (unreadableFile || unreadable != null) return false;
        var claim = receiptClaim(receipt);
        if (!claims.contains(claim)) return false;
        var byId = new HashMap<UUID, FlowBlockHost.Saved>();
        for (var saved : owners) if (byId.putIfAbsent(saved.state().computer(), saved) != null) return false;
        for (var position : claim.ends()) {
            var saved = byId.get(position.computer());
            if (saved == null || !saved.receipts().contains(receipt)) return false;
        }
        if (settled.add(claim)) setDirty();
        return true;
    }

    /** Real owner tick only. Reclaim obsolete data even if the original wire no longer exists. */
    public int maintain(ComputerBlockHost host, ComputerHostLimits limits, boolean available) {
        return host.maintainXBusReceipts(retirementWitness, this::payloadDiscardable, limits, available);
    }
    boolean payloadDiscardable(XBusReceipt receipt) {
        if (unreadableFile || unreadable != null) return false;
        var claim = receiptClaim(receipt);
        return settled.contains(claim) && durableSettled.contains(claim)
            || retiredClaim(frontiers, claim) && retiredClaim(durableFrontiers, claim);
    }
    private static boolean retiredClaim(Map<UUID, FlowControl.Peer> frontiers, Claim claim) {
        // Releasing one task must not retire a cohort still owned by another local task or main.
        return claim.ends().stream().map(XBusReceipt.Position::computer).distinct().anyMatch(id -> {
            var known = frontiers.get(id); return known != null && claim.ends().stream().filter(p -> p.computer().equals(id)).allMatch(known::supersedes);
        });
    }
    private static void compact(Map<UUID, FlowControl.Peer> into, Set<Claim> pairs) {
        for (var it = pairs.iterator(); it.hasNext();) {
            var claim = it.next();
            if (!retiredClaim(into, claim)) continue;
            for (var end : claim.ends()) merge(into, end.computer(), FlowControl.Peer.at(end, end.operation()));
            it.remove();
        }
        // A peer's lower-bound retirement is not a reset of that peer. It must not cascade
        // through another pair whose real payload receipts are still held by live Computers.
    }

    XBusAccess.Inspection inspect(List<FlowBlockHost.Saved> owners, int computerLimit, int claimLimit) {
        var failure = observe(owners, computerLimit, claimLimit);
        if (failure.isPresent()) return denied(failure.orElseThrow());
        return inspectKnown(owners);
    }
    /** Status sampling only: consult existing fences without enrollment, compaction or retirement. */
    XBusAccess.Inspection inspectKnown(List<FlowBlockHost.Saved> owners) {
        if (unreadableFile || unreadable != null) return denied(XBusExchange.Status.UNAVAILABLE);
        var byId = new LinkedHashMap<UUID, FlowBlockHost.Saved>();
        for (var saved : owners) {
            var state = saved.state(); var known = frontiers.get(state.computer());
            if (known != null) {
                var rejection = known.check(0, state.flow(), 0, state.operation(), saved.control().generation());
                if (rejection.isPresent()) return denied(rejection.orElseThrow());
                // A task claim can compact its consumed pair out of the journal while its
                // retirement frontier remains. Check actual waiting pin owners as well as
                // main: an older child checkpoint must not create a fresh rendezvous then.
                // Unrelated pure/finished tasks still do not retire an unchanged main pin.
                for (var claim : saved.pinClaims().entries()) {
                    if (claim.flow().equals(state.flow())) continue;
                    var waiting = saved.context(claim.flow()).orElseThrow();
                    rejection = known.check(saved.position(waiting, claim.pin()));
                    if (rejection.isPresent()) return denied(rejection.orElseThrow());
                }
            }
            byId.put(state.computer(), saved);
        }
        // Local to this inspection: no witness or payload survives a provider callback or a new snapshot.
        var witnesses = new IdentityHashMap<FlowBlockHost.Saved, ReceiptScan>();
        boolean pending = false; var repairs = new LinkedHashSet<XBusNetwork.Selection>();
        var relevant = new LinkedHashSet<Claim>();
        for (var id : byId.keySet()) relevant.addAll(computerClaims.getOrDefault(id, Set.of()));
        for (var claim : relevant) {
            boolean completed = settled.contains(claim) && durableSettled.contains(claim);
            boolean missing = false, witness = false, exact = true;
            for (var end : claim.ends()) {
                var saved = byId.get(end.computer());
                if (saved == null) { exact = false; continue; }
                if (FlowControl.Peer.of(saved).supersedes(end)) continue;
                var context = saved.context(end);
                if (context.isEmpty()) return denied(XBusExchange.Status.HISTORY_CONFLICT);
                var state = context.orElseThrow();
                if (completed) {
                    if (state.operation() <= end.operation()) return denied(XBusExchange.Status.RETIRED);
                    continue; // The explicit completion proof replaces the need for the local data copy.
                }
                var receipts = saved.receipts();
                if (!receipts.isEmpty() && (receipts.size() == 1 ? receiptClaim(receipts.getFirst()).equals(claim)
                    : witnesses.computeIfAbsent(saved, ignored -> new ReceiptScan()).contains(receipts, claim))) {
                    witness = true; continue;
                }
                if (state.operation() > end.operation()) return denied(XBusExchange.Status.HISTORY_CONFLICT);
                if (state.operation() == end.operation() && state.cursor() != end.cursor())
                    return denied(XBusExchange.Status.HISTORY_CONFLICT);
                missing = true; exact &= state.operation() == end.operation();
            }
            if (missing) {
                pending = true;
                // Only an actual Computer receipt contains repair data. This identity journal cannot supply it.
                if (witness && exact) repairs.addAll(claim.repairs());
            }
        }
        return new XBusAccess.Inspection(pending ? Optional.of(XBusExchange.Status.RECOVERY_REQUIRED) : Optional.empty(), List.copyOf(repairs));
    }

    /** On-demand indexing preserves the original encounter order and short-circuit validation.
     * Inspect each receipt at most once instead of reconstructing it per claim and participant. */
    private final class ReceiptScan {
        private final Set<Claim> seen = new HashSet<>();
        private int next;

        boolean contains(List<XBusReceipt> receipts, Claim expected) {
            if (seen.contains(expected)) return true;
            while (next < receipts.size()) {
                var actual = receiptClaim(receipts.get(next++));
                seen.add(actual);
                if (actual.equals(expected)) return true;
            }
            return false;
        }
    }
    private static XBusNetwork.Pin pin(XBusReceipt.Position p) { return new XBusNetwork.Pin(p.computer(), p.pin()); }
    private static XBusAccess.Inspection denied(XBusExchange.Status status) { return new XBusAccess.Inspection(Optional.of(status), List.of()); }

    /** Pre-publication reservation, identity only. Uncertain post-publication failures retain the fence. */
    Reservation reserve(FlowBlockHost.Saved left, String leftPin, FlowBlockHost.Saved right, String rightPin, int computerLimit, int claimLimit) {
        var failure = observe(List.of(left, right), computerLimit, claimLimit);
        if (failure.isPresent()) return new Reservation(null, failure);
        boolean forward = left.pinState(leftPin).status() == FlowRuntime.Status.WAITING_WRITE && right.pinState(rightPin).status() == FlowRuntime.Status.WAITING_READ;
        boolean backward = right.pinState(rightPin).status() == FlowRuntime.Status.WAITING_WRITE && left.pinState(leftPin).status() == FlowRuntime.Status.WAITING_READ;
        if (!forward && !backward) return new Reservation(null, Optional.empty());
        var pair = forward ? new Claim(position(left, leftPin), position(right, rightPin)) : new Claim(position(right, rightPin), position(left, leftPin));
        // The access barrier has already limited pending history to an actual original repair.
        // A surviving reader may now be waiting at a later Read: do not reserve that successor.
        if (pair.ends().stream().anyMatch(p -> operationClaims.containsKey(Operation.of(p)))) return new Reservation(null, Optional.empty());
        try { check(operationClaims, pair); }
        catch (IllegalArgumentException conflict) { return new Reservation(null, Optional.of(XBusExchange.Status.HISTORY_CONFLICT)); }
        long retained = historyUnits(frontiers, claims);
        var held = consumedOccupancy(pair);
        if (growingBeyond(retained + 1 - (held == null ? 0 : held.units()), retained, claimLimit, MAX_CLAIMS)) return new Reservation(null, Optional.of(XBusExchange.Status.ARCHIVE_LIMIT));
        if (held != null) remove(held);
        claims.add(pair); index(pair); setDirty(); return new Reservation(pair, Optional.empty(), held);
    }
    private static XBusReceipt.Position position(FlowBlockHost.Saved saved, String pin) {
        return saved.position(saved.pinState(pin), pin);
    }
    /** Reserve the identities from the already validated all-owner candidate, never reconstruct a pair. */
    Reservation reserve(XBusReceipt receipt, List<FlowBlockHost.Saved> owners, int computerLimit, int claimLimit) {
        var failure = observe(owners, computerLimit, claimLimit);
        if (failure.isPresent()) return new Reservation(null, failure);
        var claim = Claim.of(receipt);
        try { check(operationClaims, claim); }
        catch (IllegalArgumentException conflict) { return new Reservation(null, Optional.of(XBusExchange.Status.HISTORY_CONFLICT)); }
        if (claims.contains(claim)) return new Reservation(null, Optional.empty());
        long retained = historyUnits(frontiers, claims);
        var held = consumedOccupancy(claim);
        if (growingBeyond(retained + claim.units() - (held == null ? 0 : held.units()), retained, claimLimit, MAX_CLAIMS))
            return new Reservation(null, Optional.of(XBusExchange.Status.ARCHIVE_LIMIT));
        if (held != null) remove(held);
        claims.add(claim); index(claim); setDirty(); return new Reservation(claim, Optional.empty(), held);
    }
    final class Reservation {
        private final Claim added; private final Optional<XBusExchange.Status> rejection;
        private final Occupancy held;
        private Reservation(Claim added, Optional<XBusExchange.Status> rejection) { this(added, rejection, null); }
        private Reservation(Claim added, Optional<XBusExchange.Status> rejection, Occupancy held) { this.added = added; this.rejection = rejection; this.held = held; }
        Optional<XBusExchange.Status> rejection() { return rejection; }
        void finish(XBusExchange.Status result) {
            // Normal rejections guarantee no consumption. A thrown dirty callback is NOT a rejection.
            if (added != null && result != XBusExchange.Status.EXCHANGED) { remove(added); if (held != null) add(held); setDirty(); }
        }
    }

    private boolean hasInstructionPositions() {
        return claims.stream().flatMap(c -> c.ends().stream()).anyMatch(p -> p.instruction() >= 0)
            || occupancies.stream().flatMap(h -> h.writers().stream()).anyMatch(p -> p.instruction() >= 0)
            || cancellations.stream().flatMap(c -> c.writers().stream()).anyMatch(p -> p.instruction() >= 0);
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (unreadableFile) throw new IllegalArgumentException("invalid_save");
        if (unreadable != null) return unreadable.copy();
        int version = frontiers.values().stream().anyMatch(FlowControl.Peer::taskAware) ? 2 : 1;
        if (claims.stream().anyMatch(Claim::extended)) version = 3;
        if (!occupancies.isEmpty()) version = 5;
        if (!cancellations.isEmpty()) version = 6;
        if (hasInstructionPositions()) version = 7;
        if (!settled.isEmpty()) version = 8;
        final int schema = version;
        tag.putInt("Version", version); var owners = new ListTag();
        frontiers.forEach((id, peer) -> {
            var n = new CompoundTag(); n.putUUID("Computer", id); n.putLong("Generation", peer.generation());
            if (schema == 1) { n.putUUID("Flow", peer.flow()); n.putLong("RetiredThrough", peer.retiredThrough()); }
            else {
                var slots = new ListTag();
                peer.slots().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
                    var slot = new CompoundTag(); var f = e.getValue(); slot.putInt("Slot", e.getKey()); slot.putUUID("Flow", f.flow());
                    slot.putLong("Incarnation", f.incarnation()); slot.putLong("RetiredThrough", f.retiredThrough()); slot.putBoolean("Retired", f.retired()); slots.add(slot);
                });
                n.put("Slots", slots);
            }
            owners.add(n);
        });
        tag.put("Frontiers", owners); var pairs = new ListTag();
        for (var claim : claims) {
            var n = new CompoundTag(); n.put("Writer", encode(claim.writer())); n.put("Reader", encode(claim.reader()));
            if (schema >= 3) {
                var writers = new ListTag(); claim.otherWriters().forEach(p -> writers.add(encode(p))); n.put("OtherWriters", writers);
                var readers = new ListTag(); claim.otherReaders().forEach(p -> readers.add(encode(p))); n.put("OtherReaders", readers);
            }
            if (schema >= 8) n.putBoolean("Settled", settled.contains(claim));
            pairs.add(n);
        }
        tag.put("Claims", pairs);
        if (schema >= 4) {
            var holds = new ListTag();
            for (var hold : occupancies) {
                var n = new CompoundTag(); n.put("Writer", encode(hold.writer())); var pins = new ListTag();
                hold.pins().stream().sorted(Comparator.comparing(XBusNetwork.Pin::computer).thenComparing(XBusNetwork.Pin::pin)).forEach(pin -> {
                    var p = new CompoundTag(); p.putUUID("Computer", pin.computer()); p.putString("Pin", pin.pin()); pins.add(p);
                });
                n.put("Pins", pins); holds.add(n);
                n.putBoolean("Complete", hold.complete()); var writers = new ListTag();
                hold.writers().stream().sorted(Comparator.comparing(XBusReceipt.Position::computer).thenComparing(XBusReceipt.Position::flow)
                    .thenComparingLong(XBusReceipt.Position::operation)).forEach(p -> writers.add(encode(p)));
                n.put("Writers", writers);
            }
            tag.put("Occupancies", holds);
        }
        if (schema >= 6) {
            var cancelled = new ListTag();
            for (var witness : cancellations) {
                var n = new CompoundTag(); n.put("Holder", encode(witness.holder())); var writers = new ListTag();
                witness.ordered().forEach(p -> writers.add(encode(p))); n.put("Writers", writers); n.put("Proof", encodeProof(witness.proof())); cancelled.add(n);
            }
            tag.put("Cancellations", cancelled);
        }
        return tag;
    }
    @Override public void save(File file, HolderLookup.Provider registries) {
        if (unreadableFile) return;
        if (frontiers.isEmpty() && durableFrontiers.isEmpty()
                && settled.isEmpty() && durableSettled.isEmpty() && !settlementWriteFailed) {
            super.save(file, registries);
            return;
        }
        if (!isDirty() && !settlementWriteFailed) return;
        var proposed = Set.copyOf(settled);
        var proposedFrontiers = Map.copyOf(frontiers);
        var envelope = new CompoundTag();
        envelope.put("data", save(new CompoundTag(), registries));
        NbtUtils.addCurrentDataVersion(envelope);
        settlementWriteFailed = false;
        IOUtilities.withIOWorker(() -> {
            try {
                // Use the same ordered atomic writer as normal SavedData. A queued dirty bit
                // alone does not survive a crash between the dimension and Computer saves.
                IOUtilities.writeNbtCompressed(envelope, file.toPath());
                durableSettled = proposed;
                durableFrontiers = proposedFrontiers;
                retirementWitness = new Object();
                settlementWriteFailed = false;
            } catch (IOException | SecurityException failure) {
                settlementWriteFailed = true;
                LOGGER.error("Could not save XBus receipt deletion witnesses", failure);
            }
        });
        setDirty(false);
    }
    public static XBusHistorySavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        var result = new XBusHistorySavedData();
        try {
            require(tag, "Version", Tag.TAG_INT);
            int version = tag.getInt("Version");
            if (version < 1 || version > 8) throw new IllegalArgumentException("version");
            if (version >= 6) fields(tag, "Version", "Frontiers", "Claims", "Occupancies", "Cancellations");
            else if (version >= 4) fields(tag, "Version", "Frontiers", "Claims", "Occupancies"); else fields(tag, "Version", "Frontiers", "Claims");
            var owners = list(tag, "Frontiers", MAX_COMPUTERS); var pairs = list(tag, "Claims", MAX_CLAIMS);
            long retained = pairs.size();
            for (var raw : owners) {
                var n = (CompoundTag)raw; require(n, "Generation", Tag.TAG_LONG); var id = uuid(n, "Computer");
                FlowControl.Peer peer;
                if (version == 1) {
                    fields(n, "Computer", "Flow", "Generation", "RetiredThrough"); require(n, "RetiredThrough", Tag.TAG_LONG);
                    peer = new FlowControl.Peer(n.getLong("Generation"), uuid(n, "Flow"), n.getLong("RetiredThrough"));
                } else {
                    fields(n, "Computer", "Generation", "Slots"); var slots = new LinkedHashMap<Integer, FlowControl.Frontier>();
                    for (var element : list(n, "Slots", MAX_CLAIMS + 1)) {
                        var slot = (CompoundTag)element; fields(slot, "Slot", "Flow", "Incarnation", "RetiredThrough", "Retired");
                        require(slot, "Slot", Tag.TAG_INT); require(slot, "Incarnation", Tag.TAG_LONG);
                        require(slot, "RetiredThrough", Tag.TAG_LONG); require(slot, "Retired", Tag.TAG_BYTE);
                        if (slot.getByte("Retired") != 0 && slot.getByte("Retired") != 1) throw new IllegalArgumentException("boolean");
                        if (slots.putIfAbsent(slot.getInt("Slot"), new FlowControl.Frontier(uuid(slot, "Flow"), slot.getLong("Incarnation"),
                            slot.getLong("RetiredThrough"), slot.getBoolean("Retired"))) != null) throw new IllegalArgumentException("duplicate_slot");
                    }
                    peer = new FlowControl.Peer(n.getLong("Generation"), slots);
                }
                if (result.frontiers.putIfAbsent(id, peer) != null)
                    throw new IllegalArgumentException("duplicate_computer");
                retained += peer.slots().size() - (peer.slots().containsKey(0) ? 1 : 0);
                if (retained > MAX_CLAIMS) throw new IllegalArgumentException("history_limit");
            }
            for (var raw : pairs) {
                var n = (CompoundTag)raw;
                if (version >= 8) {
                    fields(n, "Writer", "Reader", "OtherWriters", "OtherReaders", "Settled");
                    require(n, "Settled", Tag.TAG_BYTE);
                    if (n.getByte("Settled") != 0 && n.getByte("Settled") != 1) throw new IllegalArgumentException("boolean");
                } else if (version >= 3) fields(n, "Writer", "Reader", "OtherWriters", "OtherReaders"); else fields(n, "Writer", "Reader");
                require(n, "Writer", Tag.TAG_COMPOUND); require(n, "Reader", Tag.TAG_COMPOUND);
                var claim = new Claim(decode(n.getCompound("Writer"), version), decode(n.getCompound("Reader"), version),
                    version >= 3 ? list(n, "OtherWriters", MAX_CLAIMS).stream().map(p -> decode((CompoundTag)p, version)).toList() : List.of(),
                    version >= 3 ? list(n, "OtherReaders", MAX_CLAIMS).stream().map(p -> decode((CompoundTag)p, version)).toList() : List.of());
                retained += claim.units() - 1;
                if (retained > MAX_CLAIMS) throw new IllegalArgumentException("history_limit");
                if (result.claims.contains(claim)) throw new IllegalArgumentException("duplicate_claim");
                for (var end : claim.ends()) {
                    var known = result.frontiers.get(end.computer());
                    if (known == null || !known.identity(end) && !known.supersedes(end))
                        throw new IllegalArgumentException("invalid_frontier");
                }
                add(result.claims, result.operationClaims, claim);
                if (version >= 8 && n.getBoolean("Settled")) result.settled.add(claim);
            }
            if (version >= 4) for (var raw : list(tag, "Occupancies", MAX_CLAIMS)) {
                var n = (CompoundTag)raw;
                if (version >= 5) fields(n, "Writer", "Pins", "Writers", "Complete"); else fields(n, "Writer", "Pins");
                require(n, "Writer", Tag.TAG_COMPOUND);
                var pins = new LinkedHashSet<XBusNetwork.Pin>();
                for (var element : list(n, "Pins", MAX_CLAIMS)) {
                    var p = (CompoundTag)element; fields(p, "Computer", "Pin"); require(p, "Pin", Tag.TAG_STRING);
                    if (!pins.add(new XBusNetwork.Pin(uuid(p, "Computer"), p.getString("Pin")))) throw new IllegalArgumentException("duplicate_pin");
                }
                var writer = decode(n.getCompound("Writer"), version); var writers = new LinkedHashSet<XBusReceipt.Position>(); writers.add(writer);
                if (version >= 5) {
                    require(n, "Complete", Tag.TAG_BYTE); if (n.getByte("Complete") != 0 && n.getByte("Complete") != 1) throw new IllegalArgumentException("boolean");
                    writers.clear();
                    for (var p : list(n, "Writers", MAX_CLAIMS)) if (!writers.add(decode((CompoundTag)p, version))) throw new IllegalArgumentException("duplicate_writer");
                }
                var hold = new Occupancy(writer, pins, writers, version >= 5 && n.getBoolean("Complete"));
                retained += hold.units(); if (retained > MAX_CLAIMS) throw new IllegalArgumentException("history_limit");
                for (var participant : hold.writers()) {
                    var known = result.frontiers.get(participant.computer());
                    if (known == null || !known.identity(participant) && !known.supersedes(participant))
                        throw new IllegalArgumentException("invalid_frontier");
                }
                if (!result.frontiers.keySet().containsAll(pins.stream().map(XBusNetwork.Pin::computer).toList()))
                    throw new IllegalArgumentException("invalid_frontier");
                if (result.operationClaims.containsKey(Operation.of(hold.writer()))) throw new IllegalArgumentException("consumed_occupancy");
                result.add(hold);
            }
            if (version >= 6) for (var raw : list(tag, "Cancellations", MAX_CLAIMS)) {
                var n = (CompoundTag)raw; fields(n, "Holder", "Writers", "Proof"); require(n, "Holder", Tag.TAG_COMPOUND); require(n, "Proof", Tag.TAG_COMPOUND);
                var writers = new LinkedHashSet<XBusReceipt.Position>();
                for (var rawPosition : list(n, "Writers", MAX_CLAIMS)) if (!writers.add(decode((CompoundTag)rawPosition, version))) throw new IllegalArgumentException("duplicate_cancellation_position");
                var witness = new XBusCancellation(decode(n.getCompound("Holder"), version), writers, decodeProof(n.getCompound("Proof")));
                retained += witness.units(); if (retained > MAX_CLAIMS || result.cancellations.contains(witness)) throw new IllegalArgumentException("cancellation_limit_or_duplicate");
                for (var p : writers) {
                    var known = result.frontiers.get(p.computer());
                    if (known == null || !known.identity(p) && !known.supersedes(p)) throw new IllegalArgumentException("invalid_cancellation_frontier");
                }
                var known = result.frontiers.get(witness.holder().computer());
                if (!known.merge(witness.proof()).equals(known)) throw new IllegalArgumentException("missing_retirement_frontier");
                if (result.occupancies.stream().anyMatch(h -> h.writer().equals(witness.holder()))) throw new IllegalArgumentException("cancelled_occupancy");
                addCancellation(result.cancellations, result.operationClaims, witness);
            }
            result.rebuildIndices();
            result.durableSettled = Set.copyOf(result.settled);
            result.durableFrontiers = Map.copyOf(result.frontiers);
        } catch (RuntimeException invalid) { result.frontiers.clear(); result.claims.clear(); result.settled.clear(); result.cancellations.clear(); result.occupancies.clear(); result.occupiedPins.clear(); result.rebuildIndices(); result.unreadable = tag.copy(); }
        return result;
    }
    private static CompoundTag encode(XBusReceipt.Position p) {
        var tag = new CompoundTag(); tag.putUUID("Computer", p.computer()); tag.putUUID("Flow", p.flow());
        tag.putInt("Cursor", p.cursor()); tag.putString("Pin", p.pin()); tag.putLong("Generation", p.generation()); tag.putLong("Operation", p.operation());
        if (p.slot() != 0) { tag.putInt("Slot", p.slot()); tag.putLong("Incarnation", p.incarnation()); }
        if (p.instruction() >= 0) tag.putInt("Instruction", p.instruction());
        return tag;
    }
    private static CompoundTag encodeProof(FlowControl.Peer proof) {
        var n = new CompoundTag(); n.putLong("Generation", proof.generation()); var slots = new ListTag();
        proof.slots().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            var p = new CompoundTag(); var f = e.getValue(); p.putInt("Slot", e.getKey()); p.putUUID("Flow", f.flow());
            p.putLong("Incarnation", f.incarnation()); p.putLong("RetiredThrough", f.retiredThrough()); p.putBoolean("Retired", f.retired()); slots.add(p);
        }); n.put("Slots", slots); return n;
    }
    private static FlowControl.Peer decodeProof(CompoundTag n) {
        fields(n, "Generation", "Slots"); require(n, "Generation", Tag.TAG_LONG); var slots = new LinkedHashMap<Integer, FlowControl.Frontier>();
        for (var raw : list(n, "Slots", MAX_CLAIMS + 1)) {
            var s = (CompoundTag)raw; fields(s, "Slot", "Flow", "Incarnation", "RetiredThrough", "Retired");
            require(s, "Slot", Tag.TAG_INT); require(s, "Incarnation", Tag.TAG_LONG); require(s, "RetiredThrough", Tag.TAG_LONG); require(s, "Retired", Tag.TAG_BYTE);
            if (s.getByte("Retired") != 0 && s.getByte("Retired") != 1) throw new IllegalArgumentException("boolean");
            if (slots.putIfAbsent(s.getInt("Slot"), new FlowControl.Frontier(uuid(s, "Flow"), s.getLong("Incarnation"), s.getLong("RetiredThrough"), s.getBoolean("Retired"))) != null)
                throw new IllegalArgumentException("duplicate_slot");
        }
        return new FlowControl.Peer(n.getLong("Generation"), slots);
    }
    private static XBusReceipt.Position decode(CompoundTag tag, int version) {
        boolean task = version >= 3 && (tag.contains("Slot") || tag.contains("Incarnation"));
        boolean instruction = version >= 7 && tag.contains("Instruction");
        var names = new HashSet<>(Set.of("Computer", "Flow", "Cursor", "Pin", "Generation", "Operation"));
        if (task) { names.add("Slot"); names.add("Incarnation"); require(tag, "Slot", Tag.TAG_INT); require(tag, "Incarnation", Tag.TAG_LONG); }
        if (instruction) { names.add("Instruction"); require(tag, "Instruction", Tag.TAG_INT); if (tag.getInt("Instruction") < 0) throw new IllegalArgumentException("instruction"); }
        if (!tag.getAllKeys().equals(names)) throw new IllegalArgumentException("fields");
        require(tag, "Cursor", Tag.TAG_INT); require(tag, "Pin", Tag.TAG_STRING); require(tag, "Generation", Tag.TAG_LONG); require(tag, "Operation", Tag.TAG_LONG);
        return new XBusReceipt.Position(uuid(tag, "Computer"), uuid(tag, "Flow"), tag.getInt("Cursor"), tag.getString("Pin"), tag.getLong("Generation"), tag.getLong("Operation"),
            task ? tag.getInt("Slot") : 0, task ? tag.getLong("Incarnation") : 0, instruction ? tag.getInt("Instruction") : -1);
    }
    private static UUID uuid(CompoundTag tag, String key) {
        require(tag, key, Tag.TAG_INT_ARRAY); if (tag.getIntArray(key).length != 4) throw new IllegalArgumentException("uuid"); return tag.getUUID(key);
    }
    private static void require(CompoundTag tag, String key, int type) { if (!tag.contains(key, type)) throw new IllegalArgumentException("type"); }
    private static void fields(CompoundTag tag, String... fields) { if (!tag.getAllKeys().equals(Set.of(fields))) throw new IllegalArgumentException("fields"); }
    private static ListTag list(CompoundTag tag, String key, int max) {
        require(tag, key, Tag.TAG_LIST); var list = (ListTag)tag.get(key);
        if (list.size() > max || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) throw new IllegalArgumentException("list"); return list;
    }
}
