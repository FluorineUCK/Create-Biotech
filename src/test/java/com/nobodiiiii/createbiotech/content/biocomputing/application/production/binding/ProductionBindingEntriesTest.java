package com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBinding.Role.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBindingTransactions.*;

import java.util.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBinding.Role;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionCoordinator.Preparation;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionMemberRegistry.Lookup;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionMemberRegistry.LookupStatus;

class ProductionBindingEntriesTest {
    @Test void firstLibraryReceivesExactlyTheExistingProposalWithoutRevisingTheSource() {
        var f = new Fixture(); var existing = f.source.binding; var checked = new ArrayList<UUID>();
        assertEquals(Result.OK, f.attach(id -> { checked.add(id); return true; }));
        assertEquals(existing.logisticsIds(), checked);
        assertSame(existing, f.source.binding);
        assertSame(existing, f.target.prepared);
        assertSame(f.target.prepared, f.target.binding);
        assertNull(f.source.prepared);
        assertEquals(List.of("prepare:2", "commit:2", "clear"), f.events);
    }

    @Test void firstAttachmentRejectsInvalidWorldFactsAndUnauthorizedNetworksBeforePreparing() {
        for (String mode : List.of("source-role", "target-role", "unformed", "invalid-source", "invalid-target",
            "no-source", "no-authority", "wrong-authority", "missing-source", "missing-target", "dimension",
            "bound-target", "active-target", "structure", "authority", "other-library", "duplicate", "permission")) {
            var f = new Fixture();
            switch (mode) {
                case "source-role" -> f.source.role = LIBRARY;
                case "target-role" -> f.target.role = PANEL;
                case "unformed" -> f.source.formed = false;
                case "invalid-source" -> f.source.valid = false;
                case "invalid-target" -> f.target.valid = false;
                case "no-source" -> f.source.binding = null;
                case "no-authority" -> f.source.binding = new ProductionBinding(id(100), 7, null, List.of(id(501)));
                case "wrong-authority" -> f.source.binding = state(100, 7, PANEL, 1, 501);
                case "missing-source" -> f.source.current = false;
                case "missing-target" -> f.target.current = false;
                case "dimension" -> { f.target.dimension = "minecraft:the_nether"; f.target.canRebind = false; }
                case "bound-target" -> f.target.binding = f.source.binding;
                case "active-target" -> f.target.canRebind = false;
                case "structure" -> { f.structuralConflict = true; f.authorityReady = false; }
                case "authority" -> f.authorityReady = false;
                case "other-library" -> f.libraries.add(new Member(4, LIBRARY));
                case "duplicate" -> f.identity(f.target, LookupStatus.CONFLICT, null);
                case "permission" -> { }
                default -> throw new AssertionError(mode);
            }
            var sourceBefore = f.source.binding; var targetBefore = f.target.binding;
            var expected = switch (mode) {
                case "dimension" -> Result.DIMENSION;
                case "bound-target", "active-target" -> Result.ACTIVE;
                case "authority" -> Result.AUTHORITY_OFFLINE;
                case "permission" -> Result.PERMISSION;
                default -> Result.CONFLICT;
            };
            assertEquals(expected, f.attach(network -> !mode.equals("permission") || !network.equals(id(502))), mode);
            assertSame(sourceBefore, f.source.binding, mode); assertSame(targetBefore, f.target.binding, mode);
            assertTrue(f.events.isEmpty(), mode);
        }
    }

    @Test void failedPreparationDoesNotCommitOrClearSelection() {
        for (String mode : List.of("ACTIVE", "CAPACITY", "IDENTITY", "REVISION", "throws")) {
            var f = new Fixture(); var source = f.source.binding;
            if (mode.equals("throws")) f.target.throwsPrepare = true;
            else f.target.preparation = Preparation.valueOf(mode);
            var expected = switch (mode) {
                case "ACTIVE" -> Result.ACTIVE;
                case "CAPACITY" -> Result.TOO_MANY_BINDINGS;
                default -> Result.PARTICIPANT_REJECTED;
            };
            assertEquals(expected, f.attach(network -> true), mode);
            assertSame(source, f.source.binding, mode); assertNull(f.target.binding, mode);
            assertEquals(List.of("prepare:2"), f.events, mode);
        }
    }

    @Test void preparationCannotOverwriteAChangedSourceOrNewlyOccupiedTarget() {
        for (String mode : List.of("unformed", "source-changed", "target-bound", "authority-reconciled")) {
            var f = new Fixture(); var captured = f.source.binding;
            var changedSource = state(100, 8, COORDINATOR, 3, 501, 502);
            var foreign = state(200, 1, LIBRARY, 2, 504);
            switch (mode) {
                case "unformed" -> f.target.onPrepare = () -> f.source.formed = false;
                case "source-changed" -> f.target.onPrepare = () -> f.source.binding = changedSource;
                case "target-bound" -> f.target.onPrepare = () -> f.target.binding = foreign;
                case "authority-reconciled" -> f.onAuthority = () -> f.source.binding = changedSource;
                default -> throw new AssertionError(mode);
            }
            assertEquals(Result.CONFLICT, f.attach(network -> true), mode);
            assertSame(captured, f.target.prepared, mode);
            assertSame(mode.equals("source-changed") || mode.equals("authority-reconciled") ? changedSource : captured,
                f.source.binding, mode);
            assertSame(mode.equals("target-bound") ? foreign : null, f.target.binding, mode);
            assertEquals(List.of("prepare:2"), f.events, mode);
        }
    }

    @Test void attachmentUsesExactIdentityButAcceptsAnUnregisteredOrAlreadyIndexedSameInstance() {
        for (String mode : List.of("missing", "same", "replacement", "other-member")) {
            var f = new Fixture(); var replacement = new Member(2, LIBRARY);
            assertEquals(f.target, replacement); assertNotSame(f.target, replacement);
            if (mode.equals("same")) { f.identity(f.target, LookupStatus.FOUND, f.target); f.libraries.add(f.target); }
            if (mode.equals("replacement")) f.identity(f.target, LookupStatus.FOUND, replacement);
            if (mode.equals("other-member")) f.libraries.add(replacement);
            // The active source need not permit ordinary rebinding; this writes only the unbound target.
            f.source.canRebind = false;
            var expected = mode.equals("missing") || mode.equals("same") ? Result.OK : Result.CONFLICT;
            assertEquals(expected, f.attach(network -> true), mode);
            assertEquals(expected == Result.OK ? List.of("prepare:2", "commit:2", "clear") : List.of(), f.events, mode);
            assertNull(replacement.binding);
        }
    }

    @Test void importingConsistentItemConfigurationIsAuthorizationOnlyIncludingEmptyNetworkLists() {
        for (boolean empty : List.of(false, true)) {
            var f = new Fixture(); f.source.formed = false;
            f.onAuthority = () -> fail("Import must not reconcile the carried authority");
            f.source.binding = state(100, 7, COORDINATOR, 3, empty ? new long[0] : new long[]{501, 502});
            var second = new Member(4, PANEL); second.binding = state(200, 2, PANEL, 4,
                empty ? new long[0] : new long[]{501, 502});
            var blank = new Member(5, PANEL); blank.binding = state(300, 1, PANEL, 5);
            f.identity(second, LookupStatus.FOUND, second);
            var panels = List.of(blank, f.source, second, blank);
            var before = panels.stream().map(m -> m.binding).toList(); var checked = new ArrayList<UUID>();
            assertEquals(Result.NO_SOURCE, f.service.authorizePanelImport(List.of(), f, network -> fail("No source")));
            assertEquals(Result.OK, f.service.authorizePanelImport(panels, f, network -> { checked.add(network); return true; }));
            assertEquals(empty ? List.of() : List.of(id(501), id(502), id(501), id(502)), checked);
            for (int i = 0; i < panels.size(); i++) assertSame(before.get(i), panels.get(i).binding);
            assertTrue(f.events.isEmpty(), "Authorization cannot prepare, commit or clear selection");
        }
    }

    @Test void importRejectsForeignInstancesInvalidPanelsPermissionsAndOrderedConfigurationConflictsWithoutWrites() {
        for (String mode : List.of("role", "foreign-level-or-instance", "invalid", "formed", "no-binding",
            "duplicate", "replacement", "different-network", "different-order", "permission")) {
            var f = new Fixture(); f.source.formed = false; f.onAuthority = () -> fail("Import is read-only");
            var second = new Member(4, PANEL); second.binding = state(200, 2, PANEL, 4, 501, 502);
            switch (mode) {
                case "role" -> second.role = LIBRARY;
                case "foreign-level-or-instance" -> second.currentForPlayer = false;
                case "invalid" -> second.valid = false;
                case "formed" -> second.formed = true;
                case "no-binding" -> second.binding = null;
                case "duplicate" -> f.identity(second, LookupStatus.CONFLICT, null);
                case "replacement" -> {
                    var replacement = new Member(4, PANEL); assertEquals(second, replacement); assertNotSame(second, replacement);
                    f.identity(second, LookupStatus.FOUND, replacement);
                }
                case "different-network" -> second.binding = state(200, 2, PANEL, 4, 501, 503);
                case "different-order" -> second.binding = state(200, 2, PANEL, 4, 502, 501);
                case "permission" -> { }
                default -> throw new AssertionError(mode);
            }
            var beforeSource = f.source.binding; var beforeSecond = second.binding;
            assertEquals(mode.equals("permission") ? Result.PERMISSION : Result.CONFLICT,
                f.service.authorizePanelImport(List.of(f.source, second), f,
                    network -> !mode.equals("permission") || !network.equals(id(502))), mode);
            assertSame(beforeSource, f.source.binding, mode); assertSame(beforeSecond, second.binding, mode);
            assertTrue(f.events.isEmpty(), mode);
        }
    }

    private static UUID id(long value) { return new UUID(0, value); }
    private static ProductionBinding state(long group, long revision, Role role, long authority, long... networks) {
        return new ProductionBinding(id(group), revision, new ProductionBinding.Authority(role, id(authority)),
            Arrays.stream(networks).mapToObj(ProductionBindingEntriesTest::id).toList());
    }

    private static final class Member {
        final UUID id;
        Role role;
        ProductionBinding binding, prepared;
        boolean formed, valid = true, current = true, currentForPlayer = true, canRebind = true, throwsPrepare;
        String dimension = "minecraft:overworld";
        Preparation preparation = Preparation.READY;
        Runnable onPrepare = () -> {};
        Member(long id, Role role) { this.id = id(id); this.role = role; }
        @Override public boolean equals(Object other) { return other instanceof Member m && id.equals(m.id) && role == m.role; }
        @Override public int hashCode() { return Objects.hash(id, role); }
    }

    private static final class Fixture implements Access<Member>, EntryChecks<Member> {
        final Member source = new Member(1, PANEL), target = new Member(2, LIBRARY);
        final ProductionBindingTransactions<Member> service = new ProductionBindingTransactions<>(this);
        final List<String> events = new ArrayList<>();
        final List<Member> libraries = new ArrayList<>();
        final Map<ProductionBinding.Authority, Lookup<Member>> identities = new HashMap<>();
        boolean structuralConflict, authorityReady = true;
        Runnable onAuthority = () -> {};
        Fixture() { source.formed = true; source.binding = state(100, 7, COORDINATOR, 3, 501, 502); }
        Result attach(Predicate<UUID> permission) {
            return service.attachFirstLibrary(source, target, this, permission, () -> events.add("clear"));
        }
        void identity(Member member, LookupStatus status, Member found) {
            identities.put(new ProductionBinding.Authority(member.role, member.id), new Lookup<>(status, found));
        }
        @Override public UUID groupId(Member m) { return m.binding == null ? null : m.binding.groupId(); }
        @Override public Role role(Member m) { return m.role; }
        @Override public UUID memberId(Member m) { return m.id; }
        @Override public boolean validBinding(Member m) { return m.valid; }
        @Override public ProductionBinding binding(Member m) { return m.binding; }
        @Override public String dimensionId(Member m) { return m.dimension; }
        @Override public List<UUID> logisticsIds(Member m) { return m.binding.logisticsIds(); }
        @Override public boolean canRebind(Member m) { return m.canRebind; }
        @Override public Preparation prepareRebind(Member m, ProductionBinding proposal) {
            events.add("prepare:" + m.id.getLeastSignificantBits()); m.prepared = proposal;
            if (m.throwsPrepare) throw new IllegalStateException("Refused by participant");
            m.onPrepare.run(); return m.preparation;
        }
        @Override public void commit(Member m, ProductionBinding prepared) {
            assertSame(m.prepared, prepared); events.add("commit:" + m.id.getLeastSignificantBits()); m.binding = prepared;
        }
        @Override public boolean formedProductionMember(Member m) { return m.formed; }
        @Override public boolean current(Member m) { return m.current; }
        @Override public boolean currentForPlayer(Member m) { return m.currentForPlayer; }
        @Override public boolean hasStructuralConflict(UUID group) { return structuralConflict; }
        @Override public boolean authorityReady(Member m) { onAuthority.run(); return authorityReady; }
        @Override public List<Member> members(UUID group, Role role) {
            assertEquals(id(100), group); assertEquals(LIBRARY, role); return List.copyOf(libraries);
        }
        @Override public Lookup<Member> lookup(Role role, UUID id) {
            return identities.getOrDefault(new ProductionBinding.Authority(role, id), new Lookup<>(LookupStatus.MISSING, null));
        }
    }
}
