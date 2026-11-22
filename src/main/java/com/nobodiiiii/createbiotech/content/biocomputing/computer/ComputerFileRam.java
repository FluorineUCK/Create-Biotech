package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongPredicate;
import java.util.function.Predicate;

/** Single volatile file buffer. The owning host subtracts its reservation from shared hardware RAM. */
public final class ComputerFileRam {
    public record File(UUID id, String name, int size) {}
    @FunctionalInterface public interface Encoder { void write(OutputStream output) throws IOException; }
    private Lease active;

    public int reservedBytes() { return active == null ? 0 : active.bytes.length; }

    public Optional<Lease> reserve(UUID owner, String name, int size, long capacity, LongPredicate runtimeFits) {
        if (active != null || size <= 0 || size > capacity || !runtimeFits.test(capacity - size)) return Optional.empty();
        active = new Lease(Objects.requireNonNull(owner), Objects.requireNonNull(name), size);
        return Optional.of(active);
    }

    public Optional<File> file(UUID owner) {
        return active == null || !active.ready || !active.owner.equals(owner) ? Optional.empty()
            : Optional.of(new File(active.id, active.name, active.bytes.length));
    }

    /** Scoped trusted borrower; only a matching live file can be read or retired. */
    public <T> Optional<T> use(UUID owner, UUID id, java.util.function.Function<byte[], T> operation) {
        return active != null && active.ready && active.owner.equals(owner) && active.id.equals(id)
            ? Optional.of(operation.apply(active.bytes)) : Optional.empty();
    }

    public void clear(UUID owner, UUID id) {
        if (active != null && active.owner.equals(owner) && active.id.equals(id)) clear();
    }

    public void clear(UUID owner) {
        if (active != null && active.owner.equals(owner)) clear();
    }

    public void clear() { if (active != null) active.abort(); }

    public final class Lease {
        private final UUID owner, id = UUID.randomUUID();
        private final String name;
        private byte[] bytes;
        private int received;
        private boolean ready, writing;

        private Lease(UUID owner, String name, int size) {
            this.owner = owner; this.name = name; bytes = new byte[size];
        }
        public boolean append(int offset, byte[] chunk) {
            if (active != this || ready || writing || offset != received || chunk.length == 0 || chunk.length > bytes.length - received) return false;
            System.arraycopy(chunk, 0, bytes, received, chunk.length);
            received += chunk.length;
            return true;
        }
        /** Write once into an empty reservation, without publishing partial contents or returning its backing array. */
        public boolean writeExact(Encoder encoder) throws IOException {
            if (active != this || ready || writing || received != 0) return false;
            Objects.requireNonNull(encoder);
            var sink = new OutputStream() {
                private boolean open = true, failed;
                private void require(int count) throws IOException {
                    if (!open || active != Lease.this || count > bytes.length - received) {
                        failed = true;
                        throw new IOException("file_output_unavailable");
                    }
                }
                @Override public void write(int value) throws IOException {
                    require(1);
                    bytes[received++] = (byte)value;
                }
                @Override public void write(byte[] source, int offset, int count) throws IOException {
                    Objects.checkFromIndexSize(offset, count, source.length);
                    require(count);
                    System.arraycopy(source, offset, bytes, received, count);
                    received += count;
                }
            };
            boolean complete = false;
            writing = true;
            try {
                encoder.write(sink);
                complete = !sink.failed && filled();
                return complete;
            } finally {
                sink.open = false;
                writing = false;
                if (!complete) abort();
            }
        }
        public int readyBytes() { return active == this && ready ? bytes.length : 0; }
        public byte[] slice(int offset, int count) {
            if (active != this || !ready || offset < 0 || count < 1 || count > bytes.length || offset > bytes.length - count)
                throw new IllegalArgumentException("file_unavailable");
            return java.util.Arrays.copyOfRange(bytes, offset, offset + count);
        }
        public boolean filled() { return active == this && received == bytes.length; }
        public boolean seal(Predicate<byte[]> validate) {
            if (!filled() || ready || writing) return false;
            if (!validate.test(bytes)) { abort(); return false; }
            ready = true;
            return true;
        }
        public void abort() {
            if (active == this) active = null;
            bytes = null;
        }
    }
}
