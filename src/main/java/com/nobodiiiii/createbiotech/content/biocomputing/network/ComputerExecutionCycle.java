package com.nobodiiiii.createbiotech.content.biocomputing.network;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusExchange;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusReceipt;
import java.util.*;
import net.minecraft.server.level.ServerLevel;

/** Explicitly clocked server execution cycle. The dimension owner, not this class, selects the seed clock.
 * One activity grant per Computer; each call advances one globally sampled instruction round. */
public final class ComputerExecutionCycle {
    public record Result(long work, int exchanges, int wakes, int failedOperations, Map<UUID, String> diagnostics) {
        public Result { diagnostics = Map.copyOf(diagnostics); }
        public boolean progressed() { return work > 0 || exchanges > 0 || wakes > 0 || failedOperations > 0; }
    }
    private record Actor(UUID identity, ComputerBehaviour computer) {}
    private final ServerLevel level;
    private final long activityTick;
    private final List<Actor> canonical;
    private final Map<UUID, ComputerBehaviour> participants;
    private final Set<UUID> active = new HashSet<>();
    private boolean running, failed;

    /** End-of-level-tick entry: only instances actually ticked by the world this tick participate. */
    public ComputerExecutionCycle(ServerLevel level) { this(level, ComputerExecutionDirectory.snapshot(level)); }
    private ComputerExecutionCycle(ServerLevel level, ComputerExecutionDirectory.Snapshot snapshot) {
        this(level, snapshot.ticking(), snapshot.tick());
    }

    /** Trusted dimension coordinator only. No ticking or loading arbitrary player-supplied positions. */
    public ComputerExecutionCycle(ServerLevel level, List<ComputerBehaviour> computers, long activityTick) {
        this.level = Objects.requireNonNull(level); this.activityTick = activityTick;
        if (activityTick != level.getGameTime()) throw new IllegalArgumentException("Activity identity must be the current world tick");
        var actors = new ArrayList<Actor>(); var identities = new HashSet<UUID>(); var positions = new HashSet<net.minecraft.core.BlockPos>();
        for (var computer : List.copyOf(computers)) {
            if (!present(computer)) continue;
            var identity = computer.communicationIdentity();
            if (identity.isEmpty() || computer.wireSnapshot().isEmpty()) continue;
            if (!identities.add(identity.orElseThrow()) || !positions.add(computer.blockEntity.getBlockPos()))
                throw new IllegalArgumentException("Duplicate physical Computer in cycle");
            actors.add(new Actor(identity.orElseThrow(), computer));
        }
        canonical = actors.stream().sorted(Comparator
            .comparingInt((Actor a) -> a.computer().blockEntity.getBlockPos().getY())
            .thenComparingInt(a -> a.computer().blockEntity.getBlockPos().getZ())
            .thenComparingInt(a -> a.computer().blockEntity.getBlockPos().getX())).toList();
        participants = canonical.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Actor::identity, Actor::computer));
        for (var actor : canonical) if (actor.computer().beginFlowTick(activityTick)) active.add(actor.identity());
    }
    public Result advanceRound(long seed) {
        if (running || failed) throw new IllegalStateException("Execution cycle cannot be reentered or retried after a publication failure");
        running = true;
        try {
            if (level.getGameTime() != activityTick) throw new IllegalStateException("Execution cycle belongs to an earlier world tick");
            return round(seed);
        }
        catch (RuntimeException | Error failure) { failed = true; throw failure; }
        finally { running = false; }
    }
    private Result round(long seed) {
        var inputs = new HashMap<UUID, Map<String, Integer>>();
        for (var actor : canonical) if (active.contains(actor.identity()) && present(actor.computer())) {
            var saved = actor.computer().wireSnapshot();
            if (saved.isEmpty() || saved.orElseThrow().state().paused()) continue;
            if (saved.orElseThrow().tasks().hasReadyTask(saved.orElseThrow().state())) {
                inputs.put(actor.identity(), Map.copyOf(actor.computer().sampleFlowRoundSignals())); continue;
            }
            // A sleeping/finished main flow does not suspend independently waiting child tasks.
            if (!saved.orElseThrow().pinClaims().entries().isEmpty()) inputs.put(actor.identity(), Map.of());
        }
        long work = 0; int exchanges = 0, wakes = 0, failedOperations = 0;
        var handled = new HashSet<UUID>(); var occupied = new HashSet<XBusNetwork.Pin>();
        var diagnostics = new LinkedHashMap<UUID, String>();
        for (var actor : XBusOrder.shuffled(canonical, seed)) {
            if (!inputs.containsKey(actor.identity()) || handled.contains(actor.identity()) || !present(actor.computer())) continue;
            var computer = actor.computer();
            work = Math.addExact(work, computer.advanceFlowRound(activityTick, inputs.get(actor.identity())));
            var saved = computer.wireSnapshot(); if (saved.isEmpty()) continue;
            var hardware = computer.hardware();
            Optional<XBusPortState> waiting = hardware.limits().pins().xbus().stream()
                .map(pin -> XBusPortState.of(saved.orElseThrow(), hardware.limits().pins(), pin)).flatMap(Optional::stream)
                .filter(port -> port.mode() == XBusPortState.Mode.READ || port.mode() == XBusPortState.Mode.READABLE
                    || port.mode() == XBusPortState.Mode.WRITE).findFirst();
            if (waiting.isEmpty()) continue;
            var reader = new XBusNetwork.Pin(actor.identity(), waiting.orElseThrow().pin());
            if (occupied.contains(reader)) continue;
            var captured = XBusConnectedNetwork.capture(level, computer, reader.pin());
            if (captured.connected().isEmpty()) { diagnostics.put(actor.identity(), captured.code()); continue; }
            var connected = captured.connected().orElseThrow(); var group = connected.topology().components().getFirst();
            if (group.stream().anyMatch(pin -> !participants.containsKey(pin.computer()) || !present(participants.get(pin.computer())))) {
                diagnostics.put(actor.identity(), "unavailable"); continue;
            }
            // A configuration edit cannot grant a second handoff through pins already occupied this round.
            if (!Collections.disjoint(occupied, group)) continue;
            boolean pausedPeers = onlyPausedPeers(connected, actor.identity());
            var admission = connected.network().beginRound(XBusOrder.shuffled(group, seed), !pausedPeers);
            var inspection = admission.inspection();
            if (inspection.rejection().isEmpty()) {
                var beforeRetirement = admission.beforeRetirement();
                var retirement = admission.retirement();
                if (retirement != XBusExchange.Status.NOT_READY) {
                    diagnostics.put(actor.identity(), code(retirement));
                    if (retirement == XBusExchange.Status.RETIRED) for (var entry : beforeRetirement.entrySet()) {
                        var member = participants.get(entry.getKey()); var after = member.wireSnapshot().orElseThrow();
                        if (after.equals(entry.getValue())) continue;
                        handled.add(entry.getKey());
                        if (!after.state().paused()) { failedOperations++; member.observeXBusExecution("retired", entry.getValue()); }
                    }
                    continue;
                }
            }
            var writers = connected.ports().entrySet().stream().filter(entry -> entry.getValue().observed().mode() == XBusPortState.Mode.WRITE)
                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toCollection(HashSet::new));
            if (inspection.rejection().isPresent()) {
                var status = inspection.rejection().orElseThrow();
                if (status != XBusExchange.Status.RECOVERY_REQUIRED) { diagnostics.put(actor.identity(), code(status)); continue; }
                var origin = reader; boolean writing = waiting.orElseThrow().mode() == XBusPortState.Mode.WRITE;
                var repair = inspection.recoveries().stream().filter(pair -> pair.reader().equals(origin)
                    || writing && pair.writer().equals(origin)).findFirst();
                if (repair.isPresent() && waiting.orElseThrow().mode() != XBusPortState.Mode.READABLE) {
                    reader = repair.orElseThrow().reader();
                    writers = new HashSet<>(Set.of(repair.orElseThrow().writer()));
                } else if (waiting.orElseThrow().mode() != XBusPortState.Mode.READABLE) {
                    diagnostics.put(actor.identity(), code(status)); continue;
                }
            } else if (pausedPeers) {
                // Revalidate every binding and history before ending a local wait; no fabricated handoff.
                var result = connected.network().rejectPaused(reader);
                diagnostics.put(actor.identity(), code(result));
                if (result == XBusExchange.Status.PAUSED) {
                    failedOperations++; handled.add(actor.identity());
                    computer.observeXBusExecution("paused", saved.orElseThrow());
                }
                continue;
            } else {
                var held = admission.writer().orElseThrow();
                if (held.rejection().isPresent()) {
                    var status = held.rejection().orElseThrow();
                    if (status == XBusExchange.Status.PAUSED) {
                        status = connected.network().rejectPaused(reader);
                        if (status == XBusExchange.Status.PAUSED) {
                            failedOperations++; handled.add(actor.identity());
                            computer.observeXBusExecution("paused", saved.orElseThrow());
                        }
                    }
                    diagnostics.put(actor.identity(), code(status)); continue;
                }
                writers = new HashSet<>(held.writer().stream().toList());
                if (waiting.orElseThrow().mode() == XBusPortState.Mode.WRITE) {
                    // Admission persists even without a reader. Consumption still belongs to a reader's turn.
                    diagnostics.put(actor.identity(), "not_ready"); continue;
                }
            }
            var networkRound = connected.topology().round(seed);
            var selected = networkRound.peek(reader, writers);
            if (selected.isEmpty()) { diagnostics.put(actor.identity(), "not_ready"); continue; }
            var before = new LinkedHashMap<UUID, FlowBlockHost.Saved>();
            for (var pin : group) before.computeIfAbsent(pin.computer(), id -> participants.get(id).wireSnapshot().orElseThrow());
            occupied.addAll(group); // Retain on exception: a dirty callback may throw after durable publication.
            var result = networkRound.attempt(reader, writers,
                pair -> connected.network().exchange(pair.reader(), pair.writer()));
            diagnostics.put(actor.identity(), code(result));
            if (result == XBusExchange.Status.EXCHANGED) {
                exchanges++;
                var receipts = new LinkedHashSet<XBusReceipt>();
                for (var entry : before.entrySet()) {
                    var after = participants.get(entry.getKey()).wireSnapshot().orElseThrow();
                    // Every recipient/competing writer completes its instruction for this logical round,
                    // not just the two pins used to initiate the all-party handoff.
                    if (!after.equals(entry.getValue())) handled.add(entry.getKey());
                    for (var receipt : after.receipts()) if (!entry.getValue().receipts().contains(receipt)) receipts.add(receipt);
                }
                for (var receipt : receipts) for (var end : receipt.readers()) {
                    var old = before.get(end.computer());
                    if (old != null && old.context(end).filter(s -> s.operation() == end.operation()).isPresent())
                        XBusWiringIndicatorBehaviour.transferred(level, connected, new XBusNetwork.Selection(
                            new XBusNetwork.Pin(end.computer(), end.pin()),
                            new XBusNetwork.Pin(receipt.writer().computer(), receipt.writer().pin())));
                }
            } else {
                occupied.removeAll(group);
                if (result == XBusExchange.Status.READABLE) {
                    wakes++;
                    for (var entry : before.entrySet())
                        if (!participants.get(entry.getKey()).wireSnapshot().orElseThrow().equals(entry.getValue())) handled.add(entry.getKey());
                }
            }
        }
        return new Result(work, exchanges, wakes, failedOperations, diagnostics);
    }
    private static boolean onlyPausedPeers(XBusConnectedNetwork.Connected connected, UUID owner) {
        boolean found = false;
        for (var port : connected.ports().values()) {
            if (port.observed().computer().equals(owner)) continue;
            if (port.observed().mode() != XBusPortState.Mode.PAUSED) return false;
            found = true;
        }
        return found;
    }
    private boolean present(ComputerBehaviour computer) {
        var entity = computer.blockEntity;
        return entity.getLevel() == level && !entity.isRemoved() && level.hasChunkAt(entity.getBlockPos())
            && level.getBlockEntity(entity.getBlockPos()) == entity && entity.getBehaviour(ComputerBehaviour.TYPE) == computer;
    }
    private static String code(XBusExchange.Status status) { return status.name().toLowerCase(Locale.ROOT); }
}
