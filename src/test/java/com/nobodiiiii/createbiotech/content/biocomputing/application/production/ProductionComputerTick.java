package com.nobodiiiii.createbiotech.content.biocomputing.application.production;

import java.util.*;
import javax.annotation.Nullable;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.ProductionComputerRuntime.Scope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpoch;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionLayout;

/** Server-thread production application driver; not the generic Computer scheduler or RAM. */
public final class ProductionComputerTick<S, I> {
    public record Observation(@Nullable UUID computerId, @Nullable ProductionResourceProfile profile,
        boolean online, boolean serverAttached, long routeGeneration, long validationFence) {}
    public record MemberFact(Scope scope, long generation, UUID computerId, ProductionResourceProfile profile,
        boolean loaded, long routeGeneration, long validationFence) {}
    public record IntentInfo(UUID intentId, Scope scope, UUID targetId, long generation,
        long routeGeneration, long validationFence) {}
    public record Confirmation(Scope scope, long generation, UUID intentId, boolean committed) {}

    /** Call-scoped ports: immutable production values in, checked application effects out. */
    public interface Access<S, I> {
        @Nullable ProductionEpoch epoch();
        @Nullable UUID computerId();
        boolean hasRejectedArchive();
        S persisted();
        void install(S snapshot);
        @Nullable S snapshot(UUID groupId);
        Scope scope(S snapshot);
        long generation(S snapshot);
        void restore(S snapshot);
        boolean publishEpoch(Scope scope, long generation);
        int captureLimit();
        @Nullable Observation observe(ProductionLayout.Member member);
        boolean publishMember(MemberFact fact);
        /** The service's per-group ready queue must be bounded by this driver's confirmation capacity. */
        List<I> drain(UUID groupId, long gameTick);
        IntentInfo intent(I intent);
        boolean commit(I intent);
        void resolved(I intent, boolean committed);
        boolean publishConfirmation(Confirmation confirmation);
    }

    private final int confirmationCapacity;
    private final ArrayDeque<Confirmation> confirmations = new ArrayDeque<>();
    private int captureCursor;
    private @Nullable UUID captureEpochId;
    private int captureNodeCount;

    public ProductionComputerTick(int confirmationCapacity) {
        if (confirmationCapacity <= 0) throw new IllegalArgumentException("empty confirmation capacity");
        this.confirmationCapacity = confirmationCapacity;
    }

    public void tick(Access<S, I> access, long gameTick) {
        Objects.requireNonNull(access);
        if (access.hasRejectedArchive()) return;
        var epoch = access.epoch();
        if (epoch == null || !epoch.coordinatorId().equals(access.computerId())) return;
        var scope = scope(epoch);
        var serviceState = access.snapshot(scope.groupId());
        if (serviceState == null && scope.equals(access.scope(access.persisted()))) access.restore(access.persisted());
        serviceState = access.snapshot(scope.groupId());
        if (serviceState == null || !scope.epochId().equals(access.scope(serviceState).epochId()))
            access.publishEpoch(scope, serviceState == null ? 0 : access.generation(serviceState));
        serviceState = access.snapshot(scope.groupId());
        long factGeneration = serviceState == null ? 0 : access.generation(serviceState);

        flush(access);
        var members = epoch.layout().members();
        prepareCapture(epoch.epochId(), members.size());
        int captureCount = Math.min(members.size(), access.captureLimit());
        for (int captured = 0; captured < captureCount; captured++) {
            int index = captureCursor;
            var member = members.get(index);
            var observed = access.observe(member);
            if (!access.publishMember(new MemberFact(scope, factGeneration, member.computerId(), member.profile(),
                matches(member, observed), observed == null ? 0 : observed.routeGeneration(),
                observed == null ? 0 : observed.validationFence()))) break;
            captureCursor = (index + 1) % members.size();
        }
        // A blocked receipt belongs to an already executed request: never drain another batch over it.
        if (!confirmations.isEmpty()) return;
        var batch = access.drain(scope.groupId(), gameTick);
        if (batch.size() > confirmationCapacity)
            throw new IllegalStateException("production service exceeded bounded confirmation capacity");
        persist(access, scope.groupId());
        for (var intent : batch) {
            var info = access.intent(intent);
            boolean committed = validateAndCommit(access, intent, info);
            access.resolved(intent, committed);
            confirmations.addLast(new Confirmation(scope, Math.max(factGeneration, info.generation()),
                info.intentId(), committed));
        }
        flush(access);
        var current = access.epoch();
        if (current != null) persist(access, current.groupId());
    }

    public int prepareCapture(UUID epochId, int nodeCount) {
        Objects.requireNonNull(epochId);
        if (nodeCount <= 0) throw new IllegalArgumentException("empty epoch membership");
        if (!epochId.equals(captureEpochId) || nodeCount != captureNodeCount) {
            captureEpochId = epochId; captureNodeCount = nodeCount; captureCursor = 0;
        } else captureCursor %= nodeCount;
        return captureCursor;
    }

    /** Reload discards transient sampling/receipts; durable retry evidence stays in the application snapshot. */
    public void reset() {
        captureCursor = 0; captureEpochId = null; captureNodeCount = 0; confirmations.clear();
    }

    private void flush(Access<S, I> access) {
        while (!confirmations.isEmpty()) {
            var confirmation = confirmations.getFirst();
            var current = access.epoch();
            if (current == null || !confirmation.scope().equals(scope(current))) {
                confirmations.removeFirst(); continue;
            }
            if (!access.publishConfirmation(confirmation)) return;
            confirmations.removeFirst();
        }
    }

    private boolean validateAndCommit(Access<S, I> access, I intent, IntentInfo info) {
        var current = access.epoch();
        if (current == null || !info.scope().equals(scope(current))) return false;
        var expected = current.layout().members().stream()
            .filter(member -> member.computerId().equals(info.targetId())).findFirst().orElse(null);
        if (expected == null) return false;
        var observed = access.observe(expected);
        if (!matches(expected, observed) || !observed.serverAttached()
            || observed.routeGeneration() != info.routeGeneration()
            || observed.validationFence() != info.validationFence()) return false;
        return access.commit(intent);
    }

    private static boolean matches(ProductionLayout.Member expected, @Nullable Observation observed) {
        return observed != null && expected.computerId().equals(observed.computerId())
            && expected.profile().equals(observed.profile()) && observed.online();
    }
    private void persist(Access<S, I> access, UUID groupId) {
        var state = access.snapshot(groupId);
        if (state != null && !state.equals(access.persisted())) access.install(state);
    }
    private static Scope scope(ProductionEpoch epoch) { return new Scope(epoch.groupId(), epoch.epochId()); }
}
