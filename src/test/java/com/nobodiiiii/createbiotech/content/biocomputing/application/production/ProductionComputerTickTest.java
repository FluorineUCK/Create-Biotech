package com.nobodiiiii.createbiotech.content.biocomputing.application.production;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.ProductionComputerRuntime.Scope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.ProductionComputerTick.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

class ProductionComputerTickTest {
    private static final UUID A = id(3), B = id(4);
    private static final Scope SCOPE = new Scope(id(1), id(2));
    private static final ProductionResourceProfile LIBRARIAN =
        ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN, 1, 0).orElseThrow();
    private static final ProductionResourceProfile FLETCHER =
        ProductionResourceProfile.forProfession(ProductionProfession.FLETCHER, 2, 0).orElseThrow();

    @Test void persistsBeforeEffectsAndBackpressuredReceiptNeverReexecutesOrDrainsNewWork() {
        var f = new Fixture();
        var first = intent(10, A, 5);
        f.ready.add(first); f.acceptConfirmations = false;
        f.tick.tick(f, 100);
        assertEquals(List.of(first), f.committed);
        assertEquals(List.of(true), f.resolutions);
        assertEquals(1, f.drains); assertEquals(8, f.persisted.generation());
        assertTrue(f.events.indexOf("install:8") < f.events.indexOf("commit:" + first.intentId()));
        assertTrue(f.events.indexOf("resolved:" + first.intentId()) < f.events.indexOf("confirmation:" + first.intentId()));
        assertEquals(List.of(), f.confirmed);

        var second = intent(11, B, 12);
        f.ready.add(second);
        f.tick.tick(f, 101);
        assertEquals(1, f.drains); assertEquals(List.of(first), f.committed);
        assertEquals(List.of(second), f.ready);
        assertEquals(List.of(A, B), f.members.stream().map(MemberFact::computerId).toList());

        f.acceptConfirmations = true;
        f.tick.tick(f, 102);
        assertEquals(2, f.drains); assertEquals(List.of(first, second), f.committed);
        assertEquals(List.of(true, true), f.resolutions);
        assertEquals(List.of(new Confirmation(SCOPE, 7, first.intentId(), true),
            new Confirmation(SCOPE, 12, second.intentId(), true)), f.confirmed);
        assertEquals(11, f.persisted.generation());
        assertEquals(f.service, f.persisted);
        assertEquals(List.of(100L, 102L), f.drainTicks);
    }

    @Test void captureRotatesOnlyAfterAcceptanceHonorsBudgetAndKeepsEveryMembersOwnProfile() {
        var f = new Fixture();
        f.acceptMembers = false;
        f.tick.tick(f, 0);
        assertTrue(f.members.isEmpty());
        assertEquals(List.of(A), f.memberAttempts.stream().map(MemberFact::computerId).toList());
        f.acceptMembers = true;
        f.tick.tick(f, 1);
        assertEquals(List.of(A), f.members.stream().map(MemberFact::computerId).toList());
        f.captureLimit = 10;
        f.tick.tick(f, 2);
        assertEquals(List.of(A, B, A), f.members.stream().map(MemberFact::computerId).toList());
        assertEquals(List.of(LIBRARIAN, FLETCHER, LIBRARIAN), f.members.stream().map(MemberFact::profile).toList());
        assertTrue(f.members.stream().allMatch(MemberFact::loaded));
        assertTrue(f.members.stream().allMatch(m -> m.generation() == 7 && m.routeGeneration() == 2 && m.validationFence() == 3));

        f.epoch = epoch(SCOPE, false);
        f.tick.tick(f, 3);
        assertEquals(4, f.members.size()); assertEquals(A, f.members.getLast().computerId());
        f.epoch = epoch(SCOPE, true); f.captureLimit = 1;
        f.tick.tick(f, 4);
        assertEquals(A, f.members.getLast().computerId());
        var next = new Scope(SCOPE.groupId(), id(30));
        f.epoch = epoch(next, true);
        f.tick.tick(f, 5);
        assertEquals(A, f.members.getLast().computerId());
        assertEquals(next, f.members.getLast().scope());
        f.observations.put(B, new Observation(B, LIBRARIAN, true, true, 9, 8));
        f.tick.tick(f, 6);
        var wrongProfile = f.members.getLast();
        assertEquals(B, wrongProfile.computerId()); assertEquals(FLETCHER, wrongProfile.profile());
        assertFalse(wrongProfile.loaded()); assertEquals(9, wrongProfile.routeGeneration());
        f.observations.remove(A); f.tick.tick(f, 7);
        var missing = f.members.getLast();
        assertFalse(missing.loaded()); assertEquals(0, missing.routeGeneration()); assertEquals(0, missing.validationFence());
        int count = f.members.size(); f.captureLimit = 0; f.tick.tick(f, 8);
        assertEquals(count, f.members.size());
    }

    @Test void rejectedArchiveMissingEpochMissingIdentityAndFollowersNeverTouchServiceOrEffects() {
        for (int reason = 0; reason < 4; reason++) {
            var f = new Fixture();
            switch (reason) {
                case 0 -> f.rejected = true;
                case 1 -> f.epoch = null;
                case 2 -> f.owner = null;
                case 3 -> f.owner = B;
            }
            var original = f.persisted; f.ready.add(intent(10, A, 5));
            f.tick.tick(f, 123);
            assertEquals(List.of(), f.events); assertEquals(0, f.drains);
            assertTrue(f.memberAttempts.isEmpty()); assertTrue(f.restored.isEmpty()); assertTrue(f.epochs.isEmpty());
            assertTrue(f.committed.isEmpty()); assertTrue(f.resolutions.isEmpty()); assertTrue(f.confirmationAttempts.isEmpty());
            assertSame(original, f.persisted); assertEquals(1, f.ready.size());
        }
    }

    @Test void restoresOnlyMatchingDurableScopeAndAnnouncesAbsentOrRetiredEpochWithoutInventingGeneration() {
        var matching = new Fixture(); matching.service = null;
        var saved = matching.persisted; matching.tick.tick(matching, 0);
        assertEquals(List.of(saved), matching.restored); assertTrue(matching.epochs.isEmpty());
        assertEquals(1, matching.members.getFirst().generation());
        assertFalse(matching.events.stream().anyMatch(e -> e.startsWith("install:")));

        for (var other : List.of(new Scope(id(40), SCOPE.epochId()), new Scope(SCOPE.groupId(), id(41)))) {
            var f = new Fixture(); f.service = null; f.persisted = new State(other, 100);
            f.tick.tick(f, 1);
            assertTrue(f.restored.isEmpty());
            assertEquals(List.of(Map.entry(SCOPE, 0L)), f.epochs);
            assertEquals(0, f.members.getFirst().generation());
            assertEquals(other, f.persisted.scope(), "An asynchronous epoch fact is not an installed snapshot");
        }
        var existing = new Fixture(); existing.tick.tick(existing, 2);
        assertTrue(existing.restored.isEmpty()); assertTrue(existing.epochs.isEmpty());
        assertEquals(7, existing.persisted.generation());

        var retired = new Fixture(); retired.service = new State(new Scope(SCOPE.groupId(), id(42)), 17);
        retired.tick.tick(retired, 3);
        assertTrue(retired.restored.isEmpty());
        assertEquals(List.of(Map.entry(SCOPE, 17L)), retired.epochs);
        assertEquals(17, retired.members.getFirst().generation());
    }

    @Test void everyCommitRequiresCurrentScopeExactMemberProfileOnlineServerAndBothFences() {
        for (int reason = 0; reason < 12; reason++) {
            var f = new Fixture(); var request = intent(10, A, 5);
            switch (reason) {
                case 1 -> request = intent(10, id(99), 5);
                case 2 -> f.observations.remove(A);
                case 3 -> f.observations.put(A, new Observation(B, LIBRARIAN, true, true, 2, 3));
                case 4 -> f.observations.put(A, new Observation(A, FLETCHER, true, true, 2, 3));
                case 5 -> f.observations.put(A, new Observation(A, LIBRARIAN, false, true, 2, 3));
                case 6 -> f.observations.put(A, new Observation(A, LIBRARIAN, true, false, 2, 3));
                case 7 -> f.observations.put(A, new Observation(A, LIBRARIAN, true, true, 4, 3));
                case 8 -> f.observations.put(A, new Observation(A, LIBRARIAN, true, true, 2, 4));
                case 9 -> request = new IntentInfo(id(10), new Scope(id(99), SCOPE.epochId()), A, 5, 2, 3);
                case 10 -> request = new IntentInfo(id(10), new Scope(SCOPE.groupId(), id(99)), A, 5, 2, 3);
                case 11 -> f.commitResult = false;
            }
            f.ready.add(request); f.tick.tick(f, reason);
            assertEquals(reason == 0 || reason == 11 ? List.of(request) : List.of(), f.committed, "case " + reason);
            assertEquals(List.of(reason == 0), f.resolutions, "case " + reason);
            assertEquals(List.of(new Confirmation(SCOPE, 7, id(10), reason == 0)), f.confirmed);
            assertEquals(f.service, f.persisted);
        }
    }

    @Test void receiptsCannotLeakAcrossEpochOrGroupAndReloadResetsTransientCursorAndQueue() {
        for (int transition = 0; transition < 3; transition++) {
            var f = new Fixture(); f.acceptConfirmations = false; f.ready.add(intent(10, A, 5));
            f.tick.tick(f, 0);
            assertEquals(1, f.confirmationAttempts.size()); assertEquals(1, f.drains);
            if (transition == 0) f.epoch = epoch(new Scope(SCOPE.groupId(), id(90)), true);
            else if (transition == 1) f.epoch = epoch(new Scope(id(91), SCOPE.epochId()), true);
            else f.tick.reset();
            f.acceptConfirmations = true; f.tick.tick(f, 1);
            assertEquals(1, f.confirmationAttempts.size(), "No old receipt reaches the new scope or reloaded service");
            assertTrue(f.confirmed.isEmpty()); assertEquals(1, f.committed.size()); assertEquals(2, f.drains);
            if (transition != 1) assertEquals(List.of(A, A), f.members.stream().map(MemberFact::computerId).toList());
        }
        var f = new Fixture(); f.captureLimit = 0;
        f.ready.addAll(List.of(intent(10, A, 5), intent(11, B, 6)));
        f.afterCommit = () -> f.epoch = null;
        f.tick.tick(f, 2);
        assertEquals(1, f.committed.size());
        assertEquals(List.of(true, false), f.resolutions, "Each effect checks live epoch again, not the captured sampling view");
        assertTrue(f.confirmationAttempts.isEmpty());
        assertEquals(8, f.persisted.generation());
    }

    @Test void fullBoundedBatchRetainsEveryReceiptAndAnInvalidServiceBatchCannotPerformWorldEffects() {
        var f = new Fixture(); f.acceptConfirmations = false;
        var batch = List.of(intent(10, A, 1), intent(11, B, 2), intent(12, A, 3), intent(13, B, 4));
        f.ready.addAll(batch); f.tick.tick(f, 0); f.tick.tick(f, 1);
        assertEquals(batch, f.committed); assertEquals(1, f.drains); assertTrue(f.confirmed.isEmpty());
        f.acceptConfirmations = true; f.tick.tick(f, 2);
        assertEquals(batch, f.committed); assertEquals(2, f.drains);
        assertEquals(batch.stream().map(i -> new Confirmation(SCOPE, 7, i.intentId(), true)).toList(), f.confirmed);
        assertEquals(12, f.persisted.generation());

        var invalid = new Fixture(); invalid.ready.addAll(batch);
        var tooSmall = new ProductionComputerTick<State, IntentInfo>(1);
        assertThrows(IllegalStateException.class, () -> tooSmall.tick(invalid, 0));
        assertTrue(invalid.committed.isEmpty()); assertTrue(invalid.resolutions.isEmpty());
        assertTrue(invalid.confirmationAttempts.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new ProductionComputerTick<State, IntentInfo>(0));
    }

    private record State(Scope scope, long generation) {}

    private static final class Fixture implements Access<State, IntentInfo> {
        ProductionEpoch epoch = ProductionComputerTickTest.epoch(SCOPE, true);
        UUID owner = A;
        boolean rejected, acceptMembers = true, acceptConfirmations = true, commitResult = true;
        int captureLimit = 1, drains;
        State persisted = new State(SCOPE, 1), service = new State(SCOPE, 7);
        final ProductionComputerTick<State, IntentInfo> tick = new ProductionComputerTick<>(4);
        final Map<UUID, Observation> observations = new HashMap<>();
        final List<MemberFact> members = new ArrayList<>(), memberAttempts = new ArrayList<>();
        final List<IntentInfo> ready = new ArrayList<>(), committed = new ArrayList<>();
        final List<Confirmation> confirmed = new ArrayList<>(), confirmationAttempts = new ArrayList<>();
        final List<Boolean> resolutions = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<Long> drainTicks = new ArrayList<>();
        final List<State> restored = new ArrayList<>();
        final List<Map.Entry<Scope, Long>> epochs = new ArrayList<>();
        Runnable afterCommit = () -> {};

        Fixture() {
            observations.put(A, new Observation(A, LIBRARIAN, true, true, 2, 3));
            observations.put(B, new Observation(B, FLETCHER, true, true, 2, 3));
        }
        @Override public ProductionEpoch epoch() { return epoch; }
        @Override public UUID computerId() { return owner; }
        @Override public boolean hasRejectedArchive() { return rejected; }
        @Override public State persisted() { return persisted; }
        @Override public void install(State state) { events.add("install:" + state.generation()); persisted = state; }
        @Override public State snapshot(UUID group) { events.add("snapshot"); return service; }
        @Override public Scope scope(State state) { return state.scope(); }
        @Override public long generation(State state) { return state.generation(); }
        @Override public void restore(State state) { restored.add(state); service = state; }
        @Override public boolean publishEpoch(Scope scope, long generation) {
            epochs.add(Map.entry(scope, generation)); return true;
        }
        @Override public int captureLimit() { return captureLimit; }
        @Override public Observation observe(ProductionLayout.Member member) { return observations.get(member.computerId()); }
        @Override public boolean publishMember(MemberFact fact) {
            memberAttempts.add(fact); if (acceptMembers) members.add(fact); return acceptMembers;
        }
        @Override public List<IntentInfo> drain(UUID group, long gameTick) {
            drains++; drainTicks.add(gameTick); events.add("drain");
            var batch = List.copyOf(ready); ready.clear();
            if (!batch.isEmpty() && service != null) service = new State(service.scope(), service.generation() + 1);
            return batch;
        }
        @Override public IntentInfo intent(IntentInfo intent) { return intent; }
        @Override public boolean commit(IntentInfo intent) {
            assertEquals(service, persisted, "The durable snapshot must be installed before any world effect");
            events.add("commit:" + intent.intentId()); committed.add(intent); afterCommit.run(); return commitResult;
        }
        @Override public void resolved(IntentInfo intent, boolean result) {
            events.add("resolved:" + intent.intentId()); resolutions.add(result);
        }
        @Override public boolean publishConfirmation(Confirmation confirmation) {
            events.add("confirmation:" + confirmation.intentId()); confirmationAttempts.add(confirmation);
            if (acceptConfirmations) {
                confirmed.add(confirmation);
                if (service != null) service = new State(service.scope(), service.generation() + 1);
            }
            return acceptConfirmations;
        }
    }

    private static IntentInfo intent(int id, UUID target, long generation) {
        return new IntentInfo(id(id), SCOPE, target, generation, 2, 3);
    }
    private static UUID id(int n) { return new UUID(0, n); }
    private static ProductionEpoch epoch(Scope scope, boolean second) {
        var a = new ProductionLayout.Member(A, new ProductionLayout.Address(Level.OVERWORLD, null, new BlockPos(1, 1, 1)), LIBRARIAN);
        var b = new ProductionLayout.Member(B, new ProductionLayout.Address(Level.OVERWORLD, null, new BlockPos(3, 1, 1)), FLETCHER);
        return new ProductionEpoch(scope.epochId(), scope.groupId(), id(20), A,
            new ProductionLayout(new ProductionLayout.Bounds(0, 0, 0, 4, 3, 2), second ? List.of(a, b) : List.of(a), List.of()));
    }
}
