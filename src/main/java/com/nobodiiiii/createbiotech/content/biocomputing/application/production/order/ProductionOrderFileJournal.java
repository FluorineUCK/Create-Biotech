package com.nobodiiiii.createbiotech.content.biocomputing.application.production.order;

import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import static java.nio.file.StandardOpenOption.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService.Snapshot;

/**
 * Host-owned, exclusively locked file journal. The trusted host supplies an existing dedicated
 * directory, never a path from a computer program. All instances must cooperate with the lock;
 * external writers/removal of lock files and network filesystems are outside this contract.
 *
 * A successful publication forces file contents/metadata before an atomic same-directory move.
 * Unsupported force/atomic-move operations fail, with no weaker fallback. This covers normal
 * process restart using the filesystem's atomic replacement semantics, NOT directory-rename
 * survival across power loss, and NOT a transaction with Minecraft world saves/native effects.
 */
public final class ProductionOrderFileJournal implements ProductionOrderService.Journal, AutoCloseable {
    private static final String FILE_NAME = "production-orders.nbt";
    private static final String LOCK_NAME = "production-orders.lock";
    // Closing ANY second channel for one lock file can release another channel's process-wide
    // OS locks on some platforms. Claim the canonical path before even opening such a channel.
    private static final Map<Path, Object> OWNERS = new ConcurrentHashMap<>();

    /** Package-private I/O failure seam; public factories always use these strict real operations. */
    interface Durability {
        default FileChannel openOwner(Path ownerFile, boolean initialize) throws IOException {
            return initialize ? FileChannel.open(ownerFile, CREATE_NEW, WRITE, NOFOLLOW_LINKS)
                : FileChannel.open(ownerFile, WRITE, NOFOLLOW_LINKS);
        }
        default void force(FileChannel channel) throws IOException { channel.force(true); }
        default void replace(Path pending, Path file) throws IOException {
            Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }
    private static final Durability FILESYSTEM = new Durability() {};

    public record Limits(int maxFileBytes, long maxDecodeHeapBytes, ArchiveLimits values) {
        public Limits {
            Objects.requireNonNull(values);
            if (maxFileBytes < 1 || maxDecodeHeapBytes < 1) throw new IllegalArgumentException("invalid_file_limits");
        }
    }
    private final Path directory;
    private final Path file;
    private final Path ownerFile;
    private final Object ownerClaim = new Object();
    private final Limits limits;
    private final Durability durability;
    private final FileChannel ownerChannel;
    private final FileLock ownerLock;
    private boolean closed;
    private boolean failed;

    /** Explicit first creation only. An existing owner marker or archive is never overwritten. */
    public static ProductionOrderFileJournal initialize(Path trustedHostDirectory, Limits limits) {
        return new ProductionOrderFileJournal(trustedHostDirectory, limits, true, FILESYSTEM);
    }
    public static ProductionOrderFileJournal open(Path trustedHostDirectory, Limits limits) {
        return new ProductionOrderFileJournal(trustedHostDirectory, limits, false, FILESYSTEM);
    }

    ProductionOrderFileJournal(Path trustedHostDirectory, Limits limits, boolean initialize, Durability durability) {
        this.limits = Objects.requireNonNull(limits);
        this.durability = Objects.requireNonNull(durability);
        FileChannel channel = null;
        Path claimedOwnerFile = null;
        try {
            directory = Objects.requireNonNull(trustedHostDirectory).toRealPath();
            if (!Files.isDirectory(directory)) throw new IOException("journal_directory_required");
            file = directory.resolve(FILE_NAME);
            ownerFile = directory.resolve(LOCK_NAME);
            if (OWNERS.putIfAbsent(ownerFile, ownerClaim) != null) throw new IOException("journal_already_owned");
            claimedOwnerFile = ownerFile;
            // CREATE_NEW is also a permanent initialization marker: a missing existing archive
            // cannot accidentally be initialized as an empty store after its owner has closed.
            if (initialize) {
                if (Files.exists(file, NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(file.toString());
                channel = durability.openOwner(ownerFile, true);
            } else {
                requireRegularFile(ownerFile);
                channel = durability.openOwner(ownerFile, false);
            }
            ownerChannel = channel;
            try { ownerLock = channel.tryLock(); }
            catch (OverlappingFileLockException owned) { throw new IOException("journal_already_owned", owned); }
            if (ownerLock == null) throw new IOException("journal_already_owned");
            if (initialize) {
                byte[] empty = encode(Snapshot.empty());
                durability.force(channel);
                // Reservation prevents atomic-move providers that replace despite no REPLACE_EXISTING
                // option from overwriting an existing archive during explicit initialization.
                Files.createFile(file);
                publish(empty);
            }
            read(); // No lazy empty fallback for absent, corrupt, unsupported or oversized files.
        } catch (IOException | RuntimeException error) {
            boolean definitelyClosed = true;
            if (channel != null) try { channel.close(); }
            catch (IOException | RuntimeException cleanup) {
                definitelyClosed = false;
                error.addSuppressed(cleanup);
            }
            // A failed duplicate has no claim to release. If close itself failed, retain this
            // claim until JVM restart rather than risk another owner touching a live channel.
            if (definitelyClosed && claimedOwnerFile != null) OWNERS.remove(claimedOwnerFile, ownerClaim);
            throw failure(error);
        }
    }

    @Override public synchronized Snapshot load() {
        requireOwner();
        try { return read(); }
        catch (IOException | RuntimeException error) { failed = true; throw failure(error); }
    }

    @Override public synchronized boolean commit(long expectedRevision, Snapshot next) {
        requireOwner();
        Objects.requireNonNull(next);
        try {
            // Read the actual file, not a cached snapshot: disappearance/corruption is an error.
            if (read().revision() != expectedRevision) return false;
            if (expectedRevision == Long.MAX_VALUE || next.revision() != expectedRevision + 1)
                throw new IllegalArgumentException("invalid_journal_revision");
            publish(encode(next));
            return true;
        } catch (IOException | RuntimeException error) {
            // A provider's I/O failure can be ambiguous. Do not allow subsequent dispatch through
            // this instance; a fresh open must inspect the authoritative file before recovery.
            failed = true;
            throw failure(error);
        }
    }

    private Snapshot read() throws IOException {
        requireRegularFile(file);
        try (var channel = FileChannel.open(file, READ, NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size < 1 || size > limits.maxFileBytes()) throw new IOException("journal_file_limit");
            var bytes = ByteBuffer.allocate((int) size);
            while (bytes.hasRemaining()) if (channel.read(bytes) < 0) throw new EOFException("truncated_journal");
            if (channel.read(ByteBuffer.allocate(1)) != -1) throw new IOException("journal_changed_during_read");
            return decode(bytes.array());
        }
    }

    private Snapshot decode(byte[] bytes) throws IOException {
        var input = new DataInputStream(new ByteArrayInputStream(bytes));
        var tag = NbtIo.read(input, NbtAccounter.create(limits.maxDecodeHeapBytes()));
        if (input.available() != 0) throw new IOException("trailing_journal_bytes");
        var decoded = ValueNbtCodec.decode(tag, limits.values());
        if (!(decoded instanceof DataValue.RecordValue record)) throw new IOException("invalid_journal_root");
        return ProductionOrderArchive.decode(record);
    }

    private byte[] encode(Snapshot snapshot) throws IOException {
        var bytes = new BoundedOutput(limits.maxFileBytes());
        NbtIo.write(ValueNbtCodec.encode(ProductionOrderArchive.encode(snapshot), limits.values()), new DataOutputStream(bytes));
        byte[] encoded = bytes.toByteArray();
        // Parser allocation is a separate caller-owned bound. Never publish a file which this
        // same journal cannot reopen, even when its logical value and encoded-byte limits fit.
        if (!snapshot.equals(decode(encoded))) throw new IOException("journal_round_trip_mismatch");
        return encoded;
    }

    private void publish(byte[] bytes) throws IOException {
        Path pending = Files.createTempFile(directory, FILE_NAME + ".", ".pending");
        try {
            try (var channel = FileChannel.open(pending, WRITE, NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                durability.force(channel);
            }
            durability.replace(pending, file);
        } catch (IOException | RuntimeException error) {
            try { Files.deleteIfExists(pending); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    private static void requireRegularFile(Path path) throws IOException {
        if (!Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS).isRegularFile())
            throw new IOException("journal_regular_file_required");
    }
    private void requireOwner() {
        if (closed || failed || OWNERS.get(ownerFile) != ownerClaim || !ownerLock.isValid())
            throw new IllegalStateException("journal_unavailable");
    }
    private static IllegalStateException failure(Throwable cause) {
        return new IllegalStateException("journal_unavailable", cause);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            ownerChannel.close();
            OWNERS.remove(ownerFile, ownerClaim);
        } catch (IOException | RuntimeException error) {
            // Retaining the claim is intentional when channel closure is uncertain.
            throw failure(error);
        }
    }

    private static final class BoundedOutput extends ByteArrayOutputStream {
        private final int limit;
        private BoundedOutput(int limit) { this.limit = limit; }
        private void reserve(int length) {
            if (length > limit - count) throw new IllegalArgumentException("journal_file_limit");
        }
        @Override public synchronized void write(int value) { reserve(1); super.write(value); }
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            reserve(length); super.write(bytes, offset, length);
        }
    }
}
