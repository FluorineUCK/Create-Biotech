package com.nobodiiiii.createbiotech.content.biocomputing.application.production.order;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductionOrderFileJournalTest {
    private static final ProductionOrderFileJournal.Limits LIMITS = new ProductionOrderFileJournal.Limits(
        1_000_000, 8_000_000, new ArchiveLimits(10_000, 100_000, 1_000_000));
    @TempDir Path directory;

    @Test void failureBeforeOpeningTheOwnerChannelDoesNotLeakTheInitializationClaim() {
        var unavailable = new ProductionOrderFileJournal.Durability() {
            @Override public FileChannel openOwner(Path ownerFile, boolean initialize) throws IOException {
                throw new IOException("owner_open_failed");
            }
        };
        assertThrows(IllegalStateException.class, () -> new ProductionOrderFileJournal(directory, LIMITS, true, unavailable));
        try (var recovered = ProductionOrderFileJournal.initialize(directory, LIMITS)) {
            assertEquals(Snapshot.empty(), recovered.load());
        }
    }

    @Test void failedInitializationReleasesItsOwnClaimAfterClosingTheOwnerChannel() throws Exception {
        var unavailable = new ProductionOrderFileJournal.Durability() {
            @Override public void force(FileChannel channel) throws IOException { throw new IOException("force_failed"); }
        };
        assertThrows(IllegalStateException.class, () -> new ProductionOrderFileJournal(directory, LIMITS, true, unavailable));
        assertFalse(Files.exists(archive()));
        // Simulate explicit trusted-host recovery of initialization evidence, NOT automatic reset.
        Files.write(archive(), nbt(ValueNbtCodec.encode(ProductionOrderArchive.encode(Snapshot.empty()), LIMITS.values())),
            StandardOpenOption.CREATE_NEW);
        try (var recovered = ProductionOrderFileJournal.open(directory, LIMITS)) {
            assertEquals(Snapshot.empty(), recovered.load());
            assertTrue(recovered.commit(0, snapshot(1, "after explicit recovery")));
        }
    }

    @Test void closingAnOldOwnerAgainCannotReleaseItsReplacementOwnersClaim() {
        var old = ProductionOrderFileJournal.initialize(directory, LIMITS);
        old.close();
        var opened = new ArrayList<Path>();
        var probe = ownerOpenProbe(opened);
        try (var replacement = ProductionOrderFileJournal.open(directory, LIMITS)) {
            old.close();
            assertThrows(IllegalStateException.class, () -> new ProductionOrderFileJournal(directory, LIMITS, false, probe));
            assertTrue(opened.isEmpty());
            assertTrue(replacement.commit(0, snapshot(1, "replacement")));
        }
        try (var reopened = new ProductionOrderFileJournal(directory, LIMITS, false, probe)) {
            assertEquals(1, opened.size());
            assertEquals(1, reopened.load().revision());
        }
    }

    @Test void ambiguousOwnerCloseRetainsTheClaimAndDoesNotPermitAnotherChannel() {
        try (var ignored = ProductionOrderFileJournal.initialize(directory, LIMITS)) {}
        var durability = new ProductionOrderFileJournal.Durability() {
            @Override public FileChannel openOwner(Path ownerFile, boolean initialize) throws IOException {
                return failAfterClosing(ProductionOrderFileJournal.Durability.super.openOwner(ownerFile, initialize));
            }
        };
        var journal = new ProductionOrderFileJournal(directory, LIMITS, false, durability);
        assertThrows(IllegalStateException.class, journal::close);
        assertThrows(IllegalStateException.class, journal::load);
        journal.close();
        var opened = new ArrayList<Path>();
        assertThrows(IllegalStateException.class,
            () -> new ProductionOrderFileJournal(directory, LIMITS, false, ownerOpenProbe(opened)));
        assertTrue(opened.isEmpty(), "a close failure requires JVM restart, not a speculative new owner");
    }

    @Test void ambiguousConstructorCleanupRetainsItsClaimEvenWhenTheArchiveIsRepaired() throws Exception {
        try (var ignored = ProductionOrderFileJournal.initialize(directory, LIMITS)) {}
        byte[] valid = Files.readAllBytes(archive());
        Files.write(archive(), new byte[] {1, 2, 3});
        var durability = new ProductionOrderFileJournal.Durability() {
            @Override public FileChannel openOwner(Path ownerFile, boolean initialize) throws IOException {
                return failAfterClosing(ProductionOrderFileJournal.Durability.super.openOwner(ownerFile, initialize));
            }
        };
        var error = assertThrows(IllegalStateException.class,
            () -> new ProductionOrderFileJournal(directory, LIMITS, false, durability));
        assertEquals(1, error.getCause().getSuppressed().length);
        Files.write(archive(), valid);
        var opened = new ArrayList<Path>();
        assertThrows(IllegalStateException.class,
            () -> new ProductionOrderFileJournal(directory, LIMITS, false, ownerOpenProbe(opened)));
        assertTrue(opened.isEmpty());
    }

    private static ProductionOrderFileJournal.Durability ownerOpenProbe(List<Path> opened) {
        return new ProductionOrderFileJournal.Durability() {
            @Override public FileChannel openOwner(Path ownerFile, boolean initialize) throws IOException {
                opened.add(ownerFile);
                return ProductionOrderFileJournal.Durability.super.openOwner(ownerFile, initialize);
            }
        };
    }

    /** Real owner channel, with a provider close error after release so tests leak no OS handles. */
    private static FileChannel failAfterClosing(FileChannel delegate) {
        return new FileChannel() {
            @Override protected void implCloseChannel() throws IOException {
                delegate.close(); throw new IOException("owner_close_outcome_uncertain");
            }
            @Override public FileLock tryLock(long position, long size, boolean shared) throws IOException {
                return delegate.tryLock(position, size, shared);
            }
            @Override public FileLock lock(long position, long size, boolean shared) throws IOException {
                return delegate.lock(position, size, shared);
            }
            @Override public int read(ByteBuffer target) throws IOException { return delegate.read(target); }
            @Override public int read(ByteBuffer target, long position) throws IOException { return delegate.read(target, position); }
            @Override public long read(ByteBuffer[] targets, int offset, int length) throws IOException { return delegate.read(targets, offset, length); }
            @Override public int write(ByteBuffer source) throws IOException { return delegate.write(source); }
            @Override public int write(ByteBuffer source, long position) throws IOException { return delegate.write(source, position); }
            @Override public long write(ByteBuffer[] sources, int offset, int length) throws IOException { return delegate.write(sources, offset, length); }
            @Override public long position() throws IOException { return delegate.position(); }
            @Override public FileChannel position(long position) throws IOException { delegate.position(position); return this; }
            @Override public long size() throws IOException { return delegate.size(); }
            @Override public FileChannel truncate(long size) throws IOException { delegate.truncate(size); return this; }
            @Override public void force(boolean metadata) throws IOException { delegate.force(metadata); }
            @Override public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
                return delegate.transferTo(position, count, target);
            }
            @Override public long transferFrom(ReadableByteChannel source, long position, long count) throws IOException {
                return delegate.transferFrom(source, position, count);
            }
            @Override public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
                return delegate.map(mode, position, size);
            }
        };
    }

    @Test void duplicateOwnersIncludingPathAliasesAreRejectedBeforeAnyOwnerChannelIsOpened() throws Exception {
        Files.createDirectory(directory.resolve("child"));
        var opened = new ArrayList<Path>();
        var durability = new ProductionOrderFileJournal.Durability() {
            @Override public FileChannel openOwner(Path ownerFile, boolean initialize) throws IOException {
                opened.add(ownerFile);
                return ProductionOrderFileJournal.Durability.super.openOwner(ownerFile, initialize);
            }
        };
        try (var first = ProductionOrderFileJournal.initialize(directory, LIMITS)) {
            for (Path alias : List.of(directory, directory.resolve("."), directory.resolve("child").resolve(".."))) {
                assertThrows(IllegalStateException.class, () -> new ProductionOrderFileJournal(alias, LIMITS, false, durability));
                assertTrue(opened.isEmpty(), "opening then closing a second channel can release the first owner's OS lock");
                // Rejected construction must not release the first owner's in-process claim.
                assertThrows(IllegalStateException.class, () -> new ProductionOrderFileJournal(alias, LIMITS, false, durability));
                assertTrue(opened.isEmpty());
            }
            assertTrue(first.commit(0, snapshot(1, "still owned")));
        }
        try (var reopened = new ProductionOrderFileJournal(directory.resolve("."), LIMITS, false, durability)) {
            assertEquals(1, opened.size());
            assertEquals(1, reopened.load().revision());
        }
    }

    @Test void forceAndUnsupportedAtomicMoveFailuresNeverFallBackToAWeakerWrite() throws Exception {
        try (var ignored = ProductionOrderFileJournal.initialize(directory, LIMITS)) {}
        byte[] before = Files.readAllBytes(archive());
        for (boolean rejectForce : List.of(true, false)) {
            var events = new ArrayList<String>();
            var durability = new ProductionOrderFileJournal.Durability() {
                @Override public void force(FileChannel channel) throws IOException {
                    events.add("force");
                    if (rejectForce) throw new IOException("force_not_supported");
                    ProductionOrderFileJournal.Durability.super.force(channel);
                }
                @Override public void replace(Path pending, Path file) throws IOException {
                    events.add("atomic_replace");
                    throw new AtomicMoveNotSupportedException(pending.toString(), file.toString(), "test_provider");
                }
            };
            try (var journal = new ProductionOrderFileJournal(directory, LIMITS, false, durability)) {
                assertThrows(IllegalStateException.class, () -> journal.commit(0, snapshot(1, "must not publish")));
                assertThrows(IllegalStateException.class, journal::load);
            }
            assertEquals(rejectForce ? List.of("force") : List.of("force", "atomic_replace"), events);
            assertArrayEquals(before, Files.readAllBytes(archive()));
            try (var paths = Files.list(directory)) {
                assertFalse(paths.anyMatch(path -> path.toString().endsWith(".pending")));
            }
            try (var reopened = ProductionOrderFileJournal.open(directory, LIMITS)) {
                assertEquals(Snapshot.empty(), reopened.load());
            }
        }
    }

    @Test void successIsReturnedOnlyAfterFileForceAndAtomicPublication() throws Exception {
        try (var ignored = ProductionOrderFileJournal.initialize(directory, LIMITS)) {}
        var events = new ArrayList<String>();
        var next = snapshot(1, "new state");
        var durability = new ProductionOrderFileJournal.Durability() {
            @Override public void force(FileChannel channel) throws IOException {
                assertEquals(0, diskSnapshot().revision());
                ProductionOrderFileJournal.Durability.super.force(channel);
                events.add("forced");
            }
            @Override public void replace(Path pending, Path file) throws IOException {
                assertEquals(List.of("forced"), events);
                assertEquals(0, diskSnapshot().revision());
                ProductionOrderFileJournal.Durability.super.replace(pending, file);
                assertEquals(next, diskSnapshot());
                events.add("published");
            }
        };
        try (var journal = new ProductionOrderFileJournal(directory, LIMITS, false, durability)) {
            assertTrue(journal.commit(0, next));
            assertEquals(List.of("forced", "published"), events);
        }
    }

    @Test void ambiguousMoveFailurePoisonsTheOwnerUntilAnExplicitReopenReadsTheFile() throws Exception {
        try (var ignored = ProductionOrderFileJournal.initialize(directory, LIMITS)) {}
        var next = snapshot(1, "replacement may have happened");
        var durability = new ProductionOrderFileJournal.Durability() {
            @Override public void replace(Path pending, Path file) throws IOException {
                ProductionOrderFileJournal.Durability.super.replace(pending, file);
                throw new IOException("provider_failed_after_publication");
            }
        };
        try (var journal = new ProductionOrderFileJournal(directory, LIMITS, false, durability)) {
            assertThrows(IllegalStateException.class, () -> journal.commit(0, next));
            assertThrows(IllegalStateException.class, journal::load);
            assertThrows(IllegalStateException.class, () -> journal.commit(1, snapshot(2, "retry")));
        }
        try (var reopened = ProductionOrderFileJournal.open(directory, LIMITS)) {
            assertEquals(next, reopened.load());
        }
    }

    @Test void insertionOrderAndCursorArePreservedAcrossFileRecovery() {
        var first = snapshot(1, "first").orders().values().iterator().next();
        var secondKey = new Key(first.key().source(), new UUID(5, 6));
        var second = new Order(secondKey, first.request(), first.plan(), first.step(), first.phase(),
            first.receipt(), first.result(), first.diagnostic());
        var orders = new LinkedHashMap<Key, Order>();
        orders.put(secondKey, second);
        orders.put(first.key(), first);
        try (var journal = ProductionOrderFileJournal.initialize(directory, LIMITS)) {
            assertTrue(journal.commit(0, new Snapshot(1, 1, orders)));
        }
        try (var reopened = ProductionOrderFileJournal.open(directory, LIMITS)) {
            assertEquals(1, reopened.load().cursor());
            assertEquals(List.of(secondKey, first.key()), List.copyOf(reopened.load().orders().keySet()));
        }
    }

    private Snapshot diskSnapshot() throws IOException {
        return ProductionOrderArchive.decode((DataValue.RecordValue) ValueNbtCodec.decode(
            NbtIo.read(new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(archive())))), LIMITS.values()));
    }

    @Test void commitMustFitTheSameDecodeBudgetBeforeReplacingReadableState() throws Exception {
        try (var ignored = ProductionOrderFileJournal.initialize(directory, LIMITS)) {}
        byte[] before = Files.readAllBytes(archive());
        var constrained = new ProductionOrderFileJournal.Limits(1_000_000, 10_000, LIMITS.values());
        try (var journal = ProductionOrderFileJournal.open(directory, constrained)) {
            assertThrows(IllegalStateException.class, () -> journal.commit(0, snapshot(1, "x".repeat(15_000))));
        }
        assertArrayEquals(before, Files.readAllBytes(archive()));
        try (var reopened = ProductionOrderFileJournal.open(directory, constrained)) {
            assertEquals(Snapshot.empty(), reopened.load());
        }
    }

    @Test void onlyOneJournalOwnsTheFileAndClosingReleasesOwnership() {
        var first = ProductionOrderFileJournal.initialize(directory, LIMITS);
        try {
            assertThrows(IllegalStateException.class, () -> ProductionOrderFileJournal.open(directory, LIMITS));
            assertThrows(IllegalStateException.class, () -> ProductionOrderFileJournal.initialize(directory, LIMITS));
            assertEquals(Snapshot.empty(), first.load());
        } finally { first.close(); }
        assertThrows(IllegalStateException.class, first::load);
        assertThrows(IllegalStateException.class, () -> first.commit(0, snapshot(1, "later")));
        first.close();
        try (var second = ProductionOrderFileJournal.open(directory, LIMITS)) {
            assertTrue(second.commit(0, snapshot(1, "later")));
        }
    }

    @Test void openNeverCreatesAnEmptyStoreAndInitializationNeverOverwritesOne() throws Exception {
        assertThrows(IllegalStateException.class, () -> ProductionOrderFileJournal.open(directory, LIMITS));
        assertFalse(Files.exists(archive()));
        try (var journal = ProductionOrderFileJournal.initialize(directory, LIMITS)) {
            assertTrue(journal.commit(0, snapshot(1, "existing")));
        }
        byte[] before = Files.readAllBytes(archive());
        assertThrows(IllegalStateException.class, () -> ProductionOrderFileJournal.initialize(directory, LIMITS));
        assertArrayEquals(before, Files.readAllBytes(archive()));
        Files.delete(archive());
        assertThrows(IllegalStateException.class, () -> ProductionOrderFileJournal.open(directory, LIMITS));
        assertThrows(IllegalStateException.class, () -> ProductionOrderFileJournal.initialize(directory, LIMITS));
        assertFalse(Files.exists(archive()));
    }

    @Test void archiveWithoutOwnerMarkerIsNotOverwrittenByInitialization() throws Exception {
        byte[] evidence = new byte[] {1, 2, 3};
        Files.write(archive(), evidence);
        assertThrows(IllegalStateException.class, () -> ProductionOrderFileJournal.initialize(directory, LIMITS));
        assertArrayEquals(evidence, Files.readAllBytes(archive()));
    }

    @Test void disappearanceWhileOpenFailsLoadAndCommitInsteadOfUsingCachedState() throws Exception {
        try (var journal = ProductionOrderFileJournal.initialize(directory, LIMITS)) {
            Files.delete(archive());
            assertThrows(IllegalStateException.class, journal::load);
            assertThrows(IllegalStateException.class, () -> journal.commit(0, snapshot(1, "replacement")));
            assertFalse(Files.exists(archive()));
        }
    }

    @Test void malformedAndUnknownVersionsAreRetainedRatherThanReset() throws Exception {
        try (var ignored = ProductionOrderFileJournal.initialize(directory, LIMITS)) {}
        assertRejectedWithoutRewrite(new byte[] {1, 2, 3});
        var unsupportedValue = ValueNbtCodec.encode(ProductionOrderArchive.encode(Snapshot.empty()), LIMITS.values());
        unsupportedValue.putInt("Version", 99);
        assertRejectedWithoutRewrite(nbt(unsupportedValue));
        var fields = new LinkedHashMap<>(ProductionOrderArchive.encode(Snapshot.empty()).fields());
        fields.put("version", new DataValue.IntegerValue(99));
        assertRejectedWithoutRewrite(nbt(ValueNbtCodec.encode(new DataValue.RecordValue(fields), LIMITS.values())));
        byte[] valid = nbt(ValueNbtCodec.encode(ProductionOrderArchive.encode(Snapshot.empty()), LIMITS.values()));
        assertRejectedWithoutRewrite(Arrays.copyOf(valid, valid.length - 1));
        assertRejectedWithoutRewrite(Arrays.copyOf(valid, valid.length + 1));
    }

    @Test void callerFileAndArchiveLimitsRejectBeforePublishing() throws Exception {
        try (var ignored = ProductionOrderFileJournal.initialize(directory, LIMITS)) {}
        byte[] before = Files.readAllBytes(archive());
        var fileBound = new ProductionOrderFileJournal.Limits(before.length, LIMITS.maxDecodeHeapBytes(), LIMITS.values());
        try (var journal = ProductionOrderFileJournal.open(directory, fileBound)) {
            assertThrows(IllegalStateException.class, () -> journal.commit(0, snapshot(1, "x".repeat(2000))));
        }
        assertArrayEquals(before, Files.readAllBytes(archive()));
        var valueBound = new ProductionOrderFileJournal.Limits(LIMITS.maxFileBytes(), LIMITS.maxDecodeHeapBytes(),
            new ArchiveLimits(100, 500, 1000));
        try (var journal = ProductionOrderFileJournal.open(directory, valueBound)) {
            assertThrows(IllegalStateException.class, () -> journal.commit(0, snapshot(1, "x".repeat(2000))));
        }
        assertArrayEquals(before, Files.readAllBytes(archive()));
        assertThrows(IllegalStateException.class, () -> ProductionOrderFileJournal.open(directory,
            new ProductionOrderFileJournal.Limits(1, LIMITS.maxDecodeHeapBytes(), LIMITS.values())));
        assertThrows(IllegalStateException.class, () -> ProductionOrderFileJournal.open(directory,
            new ProductionOrderFileJournal.Limits(LIMITS.maxFileBytes(), 1, LIMITS.values())));
        assertArrayEquals(before, Files.readAllBytes(archive()));
    }

    @Test void successfulCommitsRequireExactlyOneRevisionStep() throws Exception {
        try (var journal = ProductionOrderFileJournal.initialize(directory, LIMITS)) {
            byte[] before = Files.readAllBytes(archive());
            assertThrows(IllegalStateException.class, () -> journal.commit(0, snapshot(2, "skip")));
            assertArrayEquals(before, Files.readAllBytes(archive()));
        }
    }

    @Test void serviceRecoveryUsesActualFilesWithoutReissuingAnAcceptedDeviceOperation() {
        var device = new Device();
        var key = new Key(new UUID(1, 2), new UUID(3, 4));
        var request = snapshot(1, "request").orders().get(key).request();
        var serviceLimits = new ProductionOrderService.Limits(4, 4, 100_000);
        try (var journal = ProductionOrderFileJournal.initialize(directory, LIMITS)) {
            var service = new ProductionOrderService(journal, device, serviceLimits);
            assertEquals("accepted", service.submit(key, request).code());
            assertEquals(0, device.dispatched);
        }
        try (var journal = ProductionOrderFileJournal.open(directory, LIMITS)) {
            var service = new ProductionOrderService(journal, device, serviceLimits);
            device.journal = journal;
            assertEquals("progressed", service.advance().code());
            assertEquals(Phase.WAITING, journal.load().orders().get(key).phase());
            assertEquals(1, device.dispatched);
        }
        try (var journal = ProductionOrderFileJournal.open(directory, LIMITS)) {
            var service = new ProductionOrderService(journal, device, serviceLimits);
            assertEquals("existing", service.submit(key, request).code());
            assertEquals("waiting", service.advance().code());
            device.complete = true;
            assertEquals("progressed", service.advance().code());
        }
        try (var journal = ProductionOrderFileJournal.open(directory, LIMITS)) {
            var service = new ProductionOrderService(journal, device, serviceLimits);
            assertEquals(Phase.COMPLETE, service.status(key).order().orElseThrow().phase());
            assertEquals("idle", service.advance().code());
            assertEquals(1, device.dispatched);
        }
    }

    @Test void persistedAmbiguousDispatchIsQuarantinedOnReopenWithoutRetryingTheDevice() {
        var queued = snapshot(1, "request");
        var order = queued.orders().values().iterator().next();
        var uncertain = new Order(order.key(), order.request(), order.plan(), 0, Phase.DISPATCHING,
            Optional.empty(), DataValue.NullValue.INSTANCE, "dispatching");
        try (var journal = ProductionOrderFileJournal.initialize(directory, LIMITS)) {
            assertTrue(journal.commit(0, new Snapshot(1, 0, Map.of(order.key(), uncertain))));
        }
        var device = new Device();
        try (var journal = ProductionOrderFileJournal.open(directory, LIMITS)) {
            var service = new ProductionOrderService(journal, device, new ProductionOrderService.Limits(4, 4, 100_000));
            assertEquals("progressed", service.advance().code());
            assertEquals(Phase.REVIEW_REQUIRED, journal.load().orders().get(order.key()).phase());
            assertEquals(0, device.dispatched);
        }
    }

    private static final class Device implements Backend {
        ProductionOrderFileJournal journal;
        int dispatched;
        boolean complete;
        public boolean authorized(UUID source) { return true; }
        public boolean supports(String extension, int version) { return true; }
        public Preparation prepare(UUID source, DataValue.RecordValue request) {
            return Preparation.ready(new Plan("create.production", 1, List.of(request)));
        }
        public Dispatch dispatch(Operation operation, DataValue.RecordValue command) {
            assertEquals(Phase.DISPATCHING, journal.load().orders().get(operation.order()).phase());
            dispatched++;
            return Dispatch.accepted(new DataValue.RecordValue(Map.of("receipt", new DataValue.IntegerValue(dispatched))));
        }
        public Observation observe(Operation operation, DataValue.RecordValue receipt) {
            return complete ? Observation.complete(receipt) : Observation.pending();
        }
    }

    private Path archive() { return directory.resolve("production-orders.nbt"); }
    private static Snapshot snapshot(long revision, String text) {
        var request = new DataValue.RecordValue(Map.of("value", new DataValue.TextValue(text)));
        var key = new Key(new UUID(1, 2), new UUID(3, 4));
        var order = new Order(key, request, new Plan("create.production", 1, List.of(request)),
            0, Phase.QUEUED, Optional.empty(), DataValue.NullValue.INSTANCE, "queued");
        return new Snapshot(revision, 0, Map.of(key, order));
    }
    private static byte[] nbt(CompoundTag tag) throws IOException {
        var bytes = new ByteArrayOutputStream();
        NbtIo.write(tag, new DataOutputStream(bytes));
        return bytes.toByteArray();
    }
    private void assertRejectedWithoutRewrite(byte[] bytes) throws IOException {
        Files.write(archive(), bytes);
        assertThrows(IllegalStateException.class, () -> ProductionOrderFileJournal.open(directory, LIMITS));
        assertArrayEquals(bytes, Files.readAllBytes(archive()));
        assertThrows(IllegalStateException.class, () -> ProductionOrderFileJournal.initialize(directory, LIMITS));
        assertArrayEquals(bytes, Files.readAllBytes(archive()));
    }

    @Test void staleRevisionReturnsFalseWithoutChangingTheFile() throws Exception {
        try (var journal = ProductionOrderFileJournal.initialize(directory, LIMITS)) {
            var next = new Snapshot(1, 0, Map.of());
            assertTrue(journal.commit(0, next));
            byte[] before = java.nio.file.Files.readAllBytes(directory.resolve("production-orders.nbt"));
            assertFalse(journal.commit(0, new Snapshot(2, 0, Map.of())));
            assertArrayEquals(before, java.nio.file.Files.readAllBytes(directory.resolve("production-orders.nbt")));
            assertEquals(next, journal.load());
        }
    }

    @Test void committedOrderSurvivesClosingAndReopeningARealFileWithNativeNbtTypes() {
        var request = new DataValue.RecordValue(Map.of(
            "native", new DataValue.NbtValue(Tag.TAG_COMPOUND, new DataValue.RecordValue(Map.of(
                "byte", new DataValue.NbtValue(Tag.TAG_BYTE, new DataValue.IntegerValue(7)),
                "long", new DataValue.NbtValue(Tag.TAG_LONG, new DataValue.IntegerValue(7)),
                "array", new DataValue.NbtValue(Tag.TAG_BYTE_ARRAY,
                    new DataValue.ListValue(List.of(new DataValue.IntegerValue(-1)))),
                "float", new DataValue.NbtValue(Tag.TAG_FLOAT, new DataValue.IntegerValue(Integer.MIN_VALUE)),
                "text", new DataValue.NbtValue(Tag.TAG_STRING, new DataValue.TextValue("温度\uD800"))
            )))));
        var key = new Key(new UUID(1, 2), new UUID(3, 4));
        var order = new Order(key, request, new Plan("create.production", 1, List.of(request)),
            0, Phase.QUEUED, Optional.empty(), DataValue.NullValue.INSTANCE, "queued");
        var expected = new Snapshot(1, 0, Map.of(key, order));
        try (var journal = ProductionOrderFileJournal.initialize(directory, LIMITS)) {
            assertEquals(Snapshot.empty(), journal.load());
            assertTrue(journal.commit(0, expected));
        }
        try (var reopened = ProductionOrderFileJournal.open(directory, LIMITS)) {
            assertEquals(expected, reopened.load());
        }
    }
}
