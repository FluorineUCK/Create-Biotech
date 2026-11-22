package com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBinding.Role.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBindingTransactions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionCoordinator.Preparation;

import java.util.*;
import org.junit.jupiter.api.Test;

class ProductionBindingTransactionsTest {
    @Test void mergeUsesAuthoritativeNetworksAndCommitsOneProposalAfterEveryExactParticipantPrepares() {
        var f = new Fixture(); var checked = new LinkedHashSet<UUID>();
        assertEquals(Result.OK, f.service.bind(f.a, f.b, List.of(f.stale, f.a, f.b, f.stale),
            id -> { checked.add(id); return true; }, false, () -> f.events.add("clear")));
        assertEquals(List.of("prepare:1", "prepare:2", "prepare:3", "commit:1", "commit:2", "commit:3", "clear"), f.events);
        assertEquals(Set.of(id(501), id(502), id(503)), checked, "Even removed stale networks need authorization");
        assertEquals(new ProductionBinding(id(100), 5, new ProductionBinding.Authority(PANEL, id(1)),
            List.of(id(501), id(502))), f.a.binding);
        for (var member : f.all) { assertSame(f.a.binding, member.binding); assertSame(member.prepared, member.binding); }
    }

    @Test void refusedOrThrowingPreparationCannotCommitAndDoesNotHideTheFirstFailure() {
        for (var refusal : List.of(Preparation.ACTIVE, Preparation.CAPACITY, Preparation.IDENTITY, Preparation.REVISION)) {
            var f = new Fixture(); var before = f.bindings(); f.a.result = refusal; f.b.throwPrepare = true;
            var expected = switch (refusal) {
                case ACTIVE -> Result.ACTIVE;
                case CAPACITY -> Result.TOO_MANY_BINDINGS;
                default -> Result.PARTICIPANT_REJECTED;
            };
            assertEquals(expected, f.service.bind(f.a, f.b, f.all, id -> true, false, () -> f.events.add("clear")));
            assertEquals(List.of("prepare:1", "prepare:2", "prepare:3"), f.events);
            assertEquals(before, f.bindings());
        }
        var f = new Fixture(); f.a.throwPrepare = true; var before = f.bindings();
        assertEquals(Result.PARTICIPANT_REJECTED, f.service.bind(f.a, f.b, f.all, id -> true, false, () -> fail("Cleared after failure")));
        assertEquals(before, f.bindings()); assertEquals(List.of("prepare:1", "prepare:2", "prepare:3"), f.events);
    }

    @Test void admissionRejectsMissingAuthorityPermissionsCapacityAndRevisionBeforePreparation() {
        for (var expected : List.of(Result.NO_SOURCE, Result.ACTIVE, Result.DIMENSION, Result.PERMISSION,
            Result.EMPTY_NETWORKS, Result.TOO_MANY_BINDINGS, Result.AUTHORITY_OFFLINE, Result.STALE_BINDING, Result.CONFLICT)) {
            var f = new Fixture();
            switch (expected) {
                case NO_SOURCE -> f.a.binding = null;
                case ACTIVE -> f.stale.rebindable = false;
                case DIMENSION -> f.b.dimension = "minecraft:the_nether";
                case EMPTY_NETWORKS -> { f.a.binding = binding(100, 2, f.a); f.b.binding = binding(200, 4, f.b); }
                case TOO_MANY_BINDINGS -> {
                    f.a.binding = binding(100, 2, f.a, java.util.stream.LongStream.rangeClosed(1, 16).toArray());
                    f.b.binding = binding(200, 4, f.b, java.util.stream.LongStream.rangeClosed(17, 33).toArray());
                }
                case AUTHORITY_OFFLINE -> f.stale.binding = binding(100, 1, new Member(99, COORDINATOR), 503);
                case STALE_BINDING -> f.b.binding = binding(200, Long.MAX_VALUE, f.b, 502);
                case CONFLICT -> f.stale.valid = false;
                case PERMISSION -> { }
                default -> throw new AssertionError(expected);
            }
            var before = f.bindings();
            assertEquals(expected, f.service.bind(f.a, f.b, f.all,
                id -> expected != Result.PERMISSION || !id.equals(id(503)), false, () -> fail("Cleared rejected transaction")), expected.name());
            assertEquals(before, f.bindings(), expected.name()); assertTrue(f.events.isEmpty(), expected.name());
        }
    }

    @Test void productionAuthorityRankIsIndependentOfInputOrder() {
        for (var role : List.of(LIBRARY, COORDINATOR)) {
            var f = new Fixture(); f.b.role = role; f.b.binding = binding(200, 4, f.b, 502);
            assertEquals(Result.OK, f.service.bind(f.a, f.b, List.of(f.stale, f.b, f.a), id -> true, false, () -> {}));
            assertEquals(new ProductionBinding.Authority(role, f.b.id), f.a.binding.authority());
            assertEquals(id(100), f.b.binding.groupId(), "The source group remains the merged group");
            assertEquals(5, f.b.binding.revision());
        }
        var f = new Fixture(); f.a.role = LIBRARY; f.b.role = COORDINATOR;
        f.a.binding = binding(100, 2, f.a, 501); f.stale.binding = binding(100, 1, f.a, 503); f.b.binding = binding(200, 4, f.b, 502);
        assertEquals(Result.OK, f.service.bind(f.a, f.b, f.all, id -> true, false, () -> {}));
        assertEquals(COORDINATOR, f.a.binding.authority().role());
    }

    @Test void replacementUsesExactNormalizedOrderAndMayRemoveTheFinalNetworkWithoutEditingAnotherGroup() {
        for (boolean empty : List.of(false, true)) {
            var f = new Fixture(); var before = f.bindings();
            var requested = empty ? List.<UUID>of() : Arrays.asList(id(504), id(501), null, id(504));
            assertEquals(Result.PERMISSION, f.service.replace(f.a, requested, f.all, id -> !id.equals(id(503)), false));
            assertEquals(before, f.bindings()); assertTrue(f.events.isEmpty());
            assertEquals(Result.OK, f.service.replace(f.a, requested, f.all, id -> true, false));
            assertEquals(empty ? List.of() : List.of(id(504), id(501)), f.a.binding.logisticsIds());
            assertEquals(3, f.a.binding.revision()); assertSame(f.a.binding, f.stale.binding);
            assertSame(before.get(1), f.b.binding, "Unrelated loaded group is not a participant");
            assertEquals(List.of("prepare:1", "prepare:3", "commit:1", "commit:3"), f.events);
        }
    }

    @Test void formedPanelAndLibraryUseOnlyTheNarrowLogisticsCapabilityWithoutRebindingIdentity() {
        for (var role : List.of(PANEL, LIBRARY)) {
            var f = new Fixture(); f.b.role = role; f.form(); var before = f.a.binding;
            assertEquals(Result.ACTIVE, f.service.replace(f.b, List.of(id(504)), f.all, id -> true, false));
            assertEquals(Result.OK, f.service.replaceFormed(f.b, List.of(id(504)), f.all, id -> true, false, true));
            assertEquals(new ProductionBinding(before.groupId(), 8, before.authority(), List.of(id(504))), f.a.binding);
            assertEquals(List.of("logistics:1", "logistics:2", "logistics:3", "commit:1", "commit:2", "commit:3"), f.events);
            f.all.forEach(member -> assertSame(f.a.binding, member.binding));
        }
    }

    @Test void formedPreparationStopsAtFirstRefusalAndNeverCommitsAPartialUpdate() {
        for (String mode : List.of("ACTIVE", "CAPACITY", "IDENTITY", "REVISION", "throws", "permission", "offline")) {
            var f = new Fixture(); f.form(); var before = f.bindings();
            if (mode.equals("throws")) f.b.throwPrepare = true;
            else if (!mode.equals("permission") && !mode.equals("offline")) f.b.result = Preparation.valueOf(mode);
            var expected = switch (mode) {
                case "ACTIVE" -> Result.ACTIVE;
                case "CAPACITY" -> Result.TOO_MANY_BINDINGS;
                case "permission" -> Result.PERMISSION;
                case "offline" -> Result.AUTHORITY_OFFLINE;
                default -> Result.PARTICIPANT_REJECTED;
            };
            assertEquals(expected, f.service.replaceFormed(f.b, List.of(id(504)), f.all,
                id -> !mode.equals("permission") || !id.equals(id(501)), false, !mode.equals("offline")), mode);
            assertEquals(mode.equals("permission") || mode.equals("offline") ? List.of()
                : List.of("logistics:1", "logistics:2"), f.events, mode);
            assertEquals(before, f.bindings(), mode);
        }
    }

    @Test void readOnlyProofCannotReconcileAStaleReplicaButExplicitReconciliationCan() {
        var f = new Fixture(); var loaded = List.of(f.a, f.stale); var before = f.bindings();
        assertEquals(Availability.CONFLICT, f.service.snapshotAccess(f.stale, loaded));
        assertEquals(before, f.bindings()); assertTrue(f.events.isEmpty());
        assertEquals(Reconcile.UPDATED, f.service.reconcile(f.stale, loaded));
        assertSame(f.a.binding, f.stale.binding); assertSame(before.get(0), f.a.binding); assertSame(before.get(1), f.b.binding);
        assertEquals(List.of("prepare:3", "commit:3"), f.events); f.events.clear();
        assertEquals(Reconcile.CURRENT, f.service.reconcile(f.stale, loaded));
        assertEquals(Availability.READY, f.service.snapshotAccess(f.stale, loaded)); assertTrue(f.events.isEmpty());
    }

    @Test void returningReplicaFollowsTheStableAuthorityAcrossGroupsUsingThePublicRegistry() {
        var f = new Fixture(); f.a.binding = f.b.binding;
        var registry = new ProductionMemberRegistry<Object, Member>(new ProductionMemberRegistry.Access<>() {
            @Override public UUID groupId(Member m) { return m.binding == null ? null : m.binding.groupId(); }
            @Override public ProductionBinding.Role role(Member m) { return m.role; }
            @Override public UUID memberId(Member m) { return m.id; }
            @Override public boolean validBinding(Member m) { return m.valid; }
            @Override public ProductionBinding binding(Member m) { return m.binding; }
        });
        var scope = new Object();
        f.all.forEach(member -> registry.register(scope, member));
        java.util.function.Function<ProductionBinding.Authority, ProductionMemberRegistry.Lookup<Member>> lookup =
            authority -> registry.lookupStable(scope, authority.role(), authority.memberId());
        var before = f.bindings();
        assertEquals(Availability.CONFLICT, f.service.snapshotAccess(f.stale, List.of(f.stale), lookup));
        assertEquals(before, f.bindings()); assertTrue(f.events.isEmpty());
        assertEquals(Reconcile.UPDATED, f.service.reconcile(f.stale, List.of(f.stale), lookup));
        assertSame(f.b.binding, f.stale.binding); assertEquals(id(200), f.stale.binding.groupId());
        assertSame(before.get(0), f.a.binding); assertSame(before.get(1), f.b.binding);
        assertEquals(List.of("prepare:3", "commit:3"), f.events); f.events.clear();
        assertEquals(Availability.READY, f.service.snapshotAccess(f.stale, f.all, lookup)); assertTrue(f.events.isEmpty());
        java.lang.ref.Reference.reachabilityFence(List.of(f, scope));
    }

    @Test void authorityResolutionRejectsOfflineRedirectsCyclesAmbiguousInstancesAndConflictingRevisionsWithoutWrites() {
        for (String mode : List.of("missing-authority", "missing-redirect", "cycle", "newer-replica", "same-revision-diff",
            "invalid-authority", "missing-local", "missing-authority-token", "duplicate-loading", "duplicate-authority",
            "prepare-refusal", "prepare-throws")) {
            var f = new Fixture(); var loaded = new ArrayList<>(List.of(f.a, f.stale));
            switch (mode) {
                case "missing-authority" -> loaded.remove(f.a);
                case "missing-redirect" -> f.a.binding = f.b.binding;
                case "cycle" -> { f.a.binding = binding(100, 2, f.b, 501); f.b.binding = binding(100, 3, f.a, 501); loaded.add(f.b); }
                case "newer-replica" -> f.stale.binding = binding(100, 5, f.a, 501);
                case "same-revision-diff" -> f.stale.binding = binding(100, 2, f.a, 503);
                case "invalid-authority" -> f.a.valid = false;
                case "missing-local" -> f.stale.binding = null;
                case "missing-authority-token" -> f.stale.binding = new ProductionBinding(id(100), 1, null, List.of(id(503)));
                case "duplicate-loading", "duplicate-authority" -> {
                    var original = mode.equals("duplicate-loading") ? f.stale : f.a;
                    var duplicate = new Member(original.id.getLeastSignificantBits(), original.role); duplicate.binding = original.binding;
                    assertEquals(original, duplicate); assertNotSame(original, duplicate); loaded.add(duplicate);
                }
                case "prepare-refusal" -> f.stale.result = Preparation.CAPACITY;
                case "prepare-throws" -> f.stale.throwPrepare = true;
                default -> throw new AssertionError(mode);
            }
            var before = loaded.stream().map(member -> member.binding).toList();
            boolean offline = mode.equals("missing-authority") || mode.equals("missing-redirect");
            assertEquals(offline ? Availability.AUTHORITY_OFFLINE : Availability.CONFLICT, f.service.snapshotAccess(f.stale, loaded), mode);
            assertTrue(f.events.isEmpty(), mode);
            assertEquals(offline ? Reconcile.AUTHORITY_OFFLINE : Reconcile.CONFLICT, f.service.reconcile(f.stale, loaded), mode);
            assertEquals(before, loaded.stream().map(member -> member.binding).toList(), mode);
            assertEquals(mode.startsWith("prepare-") ? List.of("prepare:3") : List.of(), f.events, mode);
        }
    }

    @Test void equalValuedMembersAreStillDistinctParticipantsWhileRepeatedReferencesArePreparedOnlyOnce() {
        var f = new Fixture(); var equal = new Member(1, PANEL); equal.binding = f.a.binding;
        assertEquals(f.a, equal); assertNotSame(f.a, equal);
        assertEquals(Result.OK, f.service.bind(f.a, f.b, List.of(equal, f.a, equal, f.stale), id -> true, false, () -> {}));
        assertEquals(List.of("prepare:1", "prepare:2", "prepare:1", "prepare:3",
            "commit:1", "commit:2", "commit:1", "commit:3"), f.events);
        assertSame(f.a.binding, equal.binding); assertSame(equal.prepared, equal.binding);
    }

    private static UUID id(long value) { return new UUID(0, value); }
    private static ProductionBinding binding(long group, long revision, Member authority, long... networks) {
        return new ProductionBinding(id(group), revision, new ProductionBinding.Authority(authority.role, authority.id),
            Arrays.stream(networks).mapToObj(ProductionBindingTransactionsTest::id).toList());
    }

    private static final class Fixture {
        final Member a = new Member(1, PANEL), b = new Member(2, PANEL), stale = new Member(3, PANEL);
        final List<Member> all = List.of(a, b, stale);
        final List<String> events = new ArrayList<>();
        final Access<Member> access = new Access<>() {
            @Override public UUID groupId(Member m) { return m.binding == null ? null : m.binding.groupId(); }
            @Override public ProductionBinding.Role role(Member m) { return m.role; }
            @Override public UUID memberId(Member m) { return m.id; }
            @Override public boolean validBinding(Member m) { return m.valid; }
            @Override public ProductionBinding binding(Member m) { return m.binding; }
            @Override public String dimensionId(Member m) { return m.dimension; }
            @Override public List<UUID> logisticsIds(Member m) { return m.binding == null ? List.of() : m.binding.logisticsIds(); }
            @Override public boolean canRebind(Member m) { return m.rebindable; }
            @Override public Preparation prepareRebind(Member m, ProductionBinding proposed) { return prepare(m, proposed, "prepare:"); }
            @Override public Preparation prepareLogistics(Member m, ProductionBinding proposed) { return prepare(m, proposed, "logistics:"); }
            @Override public void commit(Member m, ProductionBinding proposed) {
                assertSame(m.prepared, proposed); m.binding = proposed; events.add("commit:" + m.id.getLeastSignificantBits());
            }
        };
        final ProductionBindingTransactions<Member> service = new ProductionBindingTransactions<>(access);
        Fixture() { a.binding = binding(100, 2, a, 501); b.binding = binding(200, 4, b, 502); stale.binding = binding(100, 1, a, 503); }
        Preparation prepare(Member member, ProductionBinding proposed, String prefix) {
            member.prepared = proposed; events.add(prefix + member.id.getLeastSignificantBits());
            if (member.throwPrepare) throw new IllegalStateException("refused");
            return member.result;
        }
        List<ProductionBinding> bindings() { return all.stream().map(member -> member.binding).toList(); }
        void form() {
            a.role = COORDINATOR; var current = binding(100, 7, a, 501);
            all.forEach(member -> { member.binding = current; member.rebindable = false; });
        }
    }
    private static final class Member {
        final UUID id;
        ProductionBinding.Role role;
        ProductionBinding binding, prepared;
        String dimension = "minecraft:overworld";
        boolean valid = true, rebindable = true, throwPrepare;
        Preparation result = Preparation.READY;
        Member(long id, ProductionBinding.Role role) { this.id = id(id); this.role = role; }
        @Override public boolean equals(Object other) { return other instanceof Member member && id.equals(member.id); }
        @Override public int hashCode() { return id.hashCode(); }
    }
}
