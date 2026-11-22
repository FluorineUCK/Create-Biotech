package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import java.util.*;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/** Ephemeral production scan scheduling. No program, RAM, hardware budget, world or scan result is retained. */
public final class ProductionScanCohort<H> {
    /** Stateless host access; exact loaded instances, not equal identifiers, own their State. */
    public interface Hosts<H> {
        State<H> state(H host);
        @Nullable UUID id(H host);
        BlockPos position(H host);
        @Nullable ProductionStructure.Revision revision(H host);
    }
    /** Supplied for one tick only. Refresh and withdrawal remain the checked production application's responsibility. */
    public interface World<H, R> {
        boolean isChunkLoaded(ChunkPos chunk);
        boolean isLoaded(BlockPos position);
        boolean sameSpace(BlockPos origin, BlockPos position);
        ProductionLayout.Address address(BlockPos position);
        @Nullable H loadedComputer(BlockPos position);
        Refresh<H, R> refresh(H owner);
        void withdrawPartial(List<H> members);
        void fanFailure(List<H> members, R reason);
    }
    public record Refresh<H, R>(List<H> members, boolean committed, @Nullable R reason) {
        public Refresh { members = List.copyOf(members); }
    }
    /** Owned by one Computer; deliberately absent from its persistent and network archives. */
    public static final class State<H> {
        @Nullable private ProductionScanCohort<H> cohort;
        private boolean dirty;
        private long lastDiscoveryTick = Long.MIN_VALUE;

        public boolean dirty() { return dirty; }
        public void markDirty() { dirty = true; if (cohort != null) cohort.dirty = true; }
        public void detach(H member) {
            if (cohort != null) cohort.detach(member);
            cohort = null;
        }
        public void reset(H member) { detach(member); dirty = false; lastDiscoveryTick = Long.MIN_VALUE; }
        private boolean beginDiscovery(long now, long interval) {
            if (!dirty && !due(lastDiscoveryTick, now, interval)) return false;
            lastDiscoveryTick = now;
            return true;
        }
    }

    private final Hosts<H> hosts;
    private final List<H> members = new ArrayList<>();
    private boolean dirty = true;
    private long lastScanTick = Long.MIN_VALUE;

    private ProductionScanCohort(List<H> exactMembers, Hosts<H> hosts) {
        this.hosts = Objects.requireNonNull(hosts);
        reconcile(exactMembers);
    }

    public static <H, R> void tick(H caller, long now, long interval, Hosts<H> hosts, World<H, R> world) {
        Objects.requireNonNull(caller); Objects.requireNonNull(hosts); Objects.requireNonNull(world);
        if (interval < 1) throw new IllegalArgumentException("nonpositive production scan interval");
        var state = hosts.state(caller);
        var cohort = state.cohort;
        if (cohort == null) {
            if (!state.beginDiscovery(now, interval)) return;
            var rebuilt = rebuildPersisted(caller, hosts, world);
            cohort = rebuilt.cohort();
            if (cohort != null && rebuilt.partial()) {
                cohort.dirty = false; cohort.lastScanTick = now;
                world.withdrawPartial(List.copyOf(cohort.members));
                state.dirty = false;
                return;
            }
            if (cohort == null) {
                discover(caller, now, hosts, world);
                state.dirty = false;
                return;
            }
        }
        if (state.dirty) cohort.dirty = true;
        state.dirty = false;
        H owner = cohort.owner();
        if (owner != caller || !cohort.dirty && !due(cohort.lastScanTick, now, interval)) return;
        var outcome = world.refresh(owner);
        cohort.lastScanTick = now; cohort.dirty = false;
        if (!outcome.members().isEmpty()) cohort.reconcile(outcome.members());
        cohort.fanFailure(outcome, world);
    }

    private static <H, R> void discover(H caller, long now, Hosts<H> hosts, World<H, R> world) {
        var outcome = world.refresh(caller);
        if (outcome.members().isEmpty()) return;
        var cohort = new ProductionScanCohort<>(outcome.members(), hosts);
        H owner = cohort.owner();
        if (!outcome.committed() && outcome.reason() == null && owner != caller) {
            outcome = world.refresh(owner);
            if (!outcome.members().isEmpty()) cohort.reconcile(outcome.members());
        }
        cohort.lastScanTick = now; cohort.dirty = false;
        cohort.fanFailure(outcome, world);
    }

    private <R> void fanFailure(Refresh<H, R> outcome, World<H, R> world) {
        if (!outcome.committed() && outcome.reason() != null)
            world.fanFailure(List.copyOf(members), outcome.reason());
    }

    private static <H, R> Rebuild<H> rebuildPersisted(H caller, Hosts<H> hosts, World<H, R> world) {
        var record = hosts.revision(caller);
        if (record == null) return new Rebuild<>(null, false);
        boolean partial = false;
        for (var chunk : record.structure().containingChunks())
            if (!world.isChunkLoaded(chunk)) { partial = true; break; }
        var exact = new ArrayList<H>();
        for (var node : record.structure().layout().members()) {
            var pos = node.address().localPos();
            if (!world.isLoaded(pos)) {
                if (!partial) return new Rebuild<>(null, false);
                continue;
            }
            if (!world.sameSpace(hosts.position(caller), pos)) return new Rebuild<>(null, false);
            H computer = world.loadedComputer(pos);
            // structureId is part of the complete revision equality, not a second source of authority.
            if (computer == null || !world.address(pos).equals(node.address())
                || !node.computerId().equals(hosts.id(computer)) || !record.equals(hosts.revision(computer)))
                return new Rebuild<>(null, false);
            exact.add(computer);
        }
        if (!containsIdentity(exact, caller)) return new Rebuild<>(null, false);
        return new Rebuild<>(new ProductionScanCohort<>(exact, hosts), partial);
    }

    private void detach(H member) {
        if (members.removeIf(existing -> existing == member)) dirty = true;
        var state = hosts.state(member);
        if (state.cohort == this) state.cohort = null;
    }

    private void reconcile(List<H> exactMembers) {
        var next = new ArrayList<H>();
        for (H member : exactMembers) if (!containsIdentity(next, member)) next.add(Objects.requireNonNull(member));
        for (H old : List.copyOf(members)) if (!containsIdentity(next, old)) {
            // This exclusion was just observed by the completed scan, unlike an asynchronous detach.
            members.removeIf(existing -> existing == old);
            var state = hosts.state(old);
            if (state.cohort == this) state.cohort = null;
            state.markDirty();
        }
        for (H member : next) {
            var state = hosts.state(member);
            if (state.cohort != null && state.cohort != this) state.cohort.detach(member);
            if (!containsIdentity(members, member)) members.add(member);
            state.cohort = this;
        }
    }

    private H owner() {
        return members.stream().min(Comparator.comparing(member -> Objects.requireNonNull(hosts.id(member)))).orElseThrow();
    }
    private static <H> boolean containsIdentity(List<H> list, H member) { return list.stream().anyMatch(existing -> existing == member); }
    private static boolean due(long last, long now, long interval) {
        return last == Long.MIN_VALUE || now < last || now - last >= interval;
    }
    private record Rebuild<H>(@Nullable ProductionScanCohort<H> cohort, boolean partial) {}
}
