package com.nobodiiiii.createbiotech.content.biocomputing.application.production.order;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductionOrderRepositoryTest {
    @TempDir Path directory;
    private static final ProductionOrderFileJournal.Limits FILES = new ProductionOrderFileJournal.Limits(
        65536, 1048576, new ArchiveLimits(1024, 65536, 65536));
    private static Snapshot changed(Snapshot previous) {
        return new Snapshot(previous.revision() + 1, previous.cursor(), previous.orders());
    }
    @Test void detachedDeviceRetainsDurableReceiptOwnerAndCanReattachAfterServerRestart() {
        var id = UUID.randomUUID(); var owner = new Object();
        try (var repository = new ProductionOrderRepository(directory, FILES, 4)) {
            var lease = repository.attach(id, owner, true);
            assertEquals(Snapshot.empty(), lease.load());
            assertThrows(IllegalStateException.class, () -> repository.attach(id, new Object(), false));
            lease.close();
            assertThrows(IllegalStateException.class, lease::load);
            // The independent observer can still persist a receipt while the physical port is unloaded.
            var journal = repository.journal(id);
            var before = journal.load(); assertTrue(journal.commit(before.revision(), changed(before)));
            try (var attached = repository.attach(id, new Object(), false)) { assertEquals(1, attached.load().revision()); }
        }
        try (var repository = new ProductionOrderRepository(directory, FILES, 4)) {
            assertEquals(1, repository.snapshots().get(id).revision());
            try (var attached = repository.attach(id, owner, false)) { assertEquals(1, attached.load().revision()); }
            assertThrows(IllegalStateException.class, () -> repository.attach(id, new Object(), true));
        }
    }
    @Test void countLimitAndMissingArchiveCannotCreateAnEmptyReplacement() throws Exception {
        var id = UUID.randomUUID();
        try (var repository = new ProductionOrderRepository(directory, FILES, 1)) {
            repository.attach(id, new Object(), true).close();
            assertThrows(IllegalStateException.class, () -> repository.attach(UUID.randomUUID(), new Object(), true));
        }
        java.nio.file.Files.delete(directory.resolve(id.toString()).resolve("production-orders.nbt"));
        try (var repository = new ProductionOrderRepository(directory, FILES, 1)) {
            assertTrue(repository.snapshots().isEmpty());
            assertThrows(IllegalStateException.class, () -> repository.attach(id, new Object(), false));
            assertThrows(IllegalStateException.class, () -> repository.attach(id, new Object(), true));
        }
    }
    @Test void closureInvalidatesAllLeasesAndIndexOnlyChangesAfterDurablePublication() {
        var repository = new ProductionOrderRepository(directory, FILES, 2); var id = UUID.randomUUID();
        var lease = repository.attach(id, new Object(), true);
        assertFalse(lease.commit(99, new Snapshot(100, 0, Map.of())));
        assertEquals(0, repository.snapshots().get(id).revision());
        assertTrue(lease.commit(0, changed(lease.load())));
        assertEquals(1, repository.snapshots().get(id).revision());
        assertThrows(UnsupportedOperationException.class, () -> repository.snapshots().clear());
        repository.close(); assertThrows(IllegalStateException.class, lease::load);
        assertThrows(IllegalStateException.class, () -> repository.journal(id));
    }
    @Test void loweredCapacityRejectsTheWholeRepositoryAndReleasesEveryOpenedOwner() {
        var ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        try (var repository = new ProductionOrderRepository(directory, FILES, 2)) {
            for (var id : ids) repository.attach(id, new Object(), true).close();
        }
        assertThrows(IllegalStateException.class, () -> new ProductionOrderRepository(directory, FILES, 1));
        try (var repository = new ProductionOrderRepository(directory, FILES, 2)) {
            assertEquals(Set.copyOf(ids), repository.snapshots().keySet());
            for (var id : ids) try (var lease = repository.attach(id, new Object(), false)) { assertEquals(Snapshot.empty(), lease.load()); }
        }
    }
    @Test void corruptJournalInvalidatesCachedEvidenceAndRequiresReopenNotEmptyReinitialization() throws Exception {
        var id = UUID.randomUUID(); var file = directory.resolve(id.toString()).resolve("production-orders.nbt");
        byte[] original;
        try (var repository = new ProductionOrderRepository(directory, FILES, 2)) {
            var lease = repository.attach(id, new Object(), true); original = java.nio.file.Files.readAllBytes(file);
            java.nio.file.Files.write(file, new byte[] {1, 2, 3});
            assertThrows(IllegalStateException.class, lease::load);
            assertTrue(repository.snapshots().isEmpty()); lease.close();
            assertThrows(IllegalStateException.class, () -> repository.attach(id, new Object(), false));
            assertThrows(IllegalStateException.class, () -> repository.attach(id, new Object(), true));
        }
        java.nio.file.Files.write(file, original); // Explicit trusted repair in this test, never an automatic device fallback.
        try (var repository = new ProductionOrderRepository(directory, FILES, 2)) { assertEquals(Snapshot.empty(), repository.journal(id).load()); }
    }
    @Test void materialFenceAndStableLeaseSurviveMixedSavesWithoutForkingRetainedOrders() {
        var physical = UUID.randomUUID();
        var key = new Key(UUID.randomUUID(),UUID.randomUUID());
        var manifest = new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.RecordValue(Map.of());
        long savedBlockGrant;
        try (var repository = new ProductionOrderRepository(directory, FILES, 4);
             var lease = repository.attachMaterialOutput(physical,new Object(),true).orElseThrow()) {
            savedBlockGrant = lease.publishMaterialGrant(OptionalLong.empty());
            var command = new Order(key,manifest,new Plan("create.packaging",1,List.of(manifest)),0,Phase.QUEUED,
                Optional.empty(),com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.NullValue.INSTANCE,"queued");
            var before = lease.load();
            assertTrue(lease.commit(before.revision(),new Snapshot(before.revision()+1,0,Map.of(key,command),before.materialGrant())));
            assertTrue(lease.matchesMaterialGrant(savedBlockGrant));
            lease.publishMaterialGrant(OptionalLong.of(savedBlockGrant));
        }
        try (var repository = new ProductionOrderRepository(directory, FILES, 4);
             var lease = repository.attachMaterialOutput(physical,new Object(),true).orElseThrow()) {
            assertFalse(lease.matchesMaterialGrant(savedBlockGrant), "Older active block NBT does not match the durable revoke");
            assertEquals(OptionalLong.of(0),lease.load().materialGrant());
            assertEquals(Phase.QUEUED,lease.load().orders().get(key).phase(),"No authority to advance the accepted command");
            assertThrows(IllegalStateException.class, () -> repository.attachMaterialOutput(physical,new Object(),true));
            long replacement = lease.publishMaterialGrant(OptionalLong.empty());
            assertTrue(replacement > savedBlockGrant);
            lease.publishMaterialGrant(OptionalLong.of(savedBlockGrant));
            assertTrue(lease.matchesMaterialGrant(replacement), "An older block witness cannot revoke the newer grant");
            assertEquals(Set.of(physical),repository.snapshots().keySet());
            assertEquals(Set.of(key),lease.load().orders().keySet());
        }
    }

    @Test void corruptKnownMaterialSlotCannotBeRecreated() throws Exception {
        var physical = UUID.randomUUID();
        try (var repository = new ProductionOrderRepository(directory, FILES, 4);
             var lease = repository.attachMaterialOutput(physical,new Object(),true).orElseThrow()) {
            lease.publishMaterialGrant(OptionalLong.empty());
        }
        java.nio.file.Files.delete(directory.resolve(physical.toString()).resolve("production-orders.nbt"));
        try (var repository = new ProductionOrderRepository(directory, FILES, 4)) {
            assertThrows(IllegalStateException.class, () -> repository.attachMaterialOutput(physical,new Object(),true));
        }
    }

}
