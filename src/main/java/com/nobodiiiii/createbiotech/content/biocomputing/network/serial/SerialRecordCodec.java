package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtAccounterException;
import net.minecraft.nbt.NbtException;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ReportedNbtException;

/** Typed record payloads, not transport framing, addressing, acknowledgements or world references. */
public final class SerialRecordCodec {
    private SerialRecordCodec() {}

    public enum Code { PAYLOAD_TOO_LARGE, VALUE_LIMIT, DECODE_LIMIT, UNSUPPORTED_VERSION, INVALID_RECORD }
    public static final class Failure extends IllegalArgumentException {
        private final Code code;
        private Failure(Code code) { super(code.name()); this.code = code; }
        public Code code() { return code; }
    }

    /** Supplied by the caller; encoded bytes, parser allocation and logical values are separate budgets. */
    public record Limits(int maxEncodedBytes, long maxDecodeHeapBytes, ArchiveLimits values) {
        public Limits {
            Objects.requireNonNull(values);
            if (maxEncodedBytes < 1 || maxDecodeHeapBytes < 1)
                throw new IllegalArgumentException("Positive serial record limits required");
        }
    }

    /** Returns the actual uncompressed value-archive bytes. Physical framing must be charged separately. */
    public static byte[] encode(DataValue.RecordValue record, Limits limits) {
        Objects.requireNonNull(record);
        Objects.requireNonNull(limits);
        var bytes = new BoundedOutput(limits.maxEncodedBytes());
        try {
            NbtIo.write(ValueNbtCodec.encode(record, limits.values()), new DataOutputStream(bytes));
        } catch (ArchiveException invalid) {
            throw archiveFailure(invalid);
        } catch (IOException invalid) {
            throw new Failure(Code.INVALID_RECORD);
        }
        return bytes.toByteArray();
    }

    public static DataValue.RecordValue decode(byte[] bytes, Limits limits) {
        Objects.requireNonNull(bytes);
        Objects.requireNonNull(limits);
        if (bytes.length > limits.maxEncodedBytes()) throw new Failure(Code.PAYLOAD_TOO_LARGE);
        try {
            var input = new DataInputStream(new ByteArrayInputStream(bytes));
            var tag = NbtIo.read(input, NbtAccounter.create(limits.maxDecodeHeapBytes()));
            if (input.available() != 0) throw new Failure(Code.INVALID_RECORD);
            var value = ValueNbtCodec.decode(tag, limits.values());
            if (!(value instanceof DataValue.RecordValue record)) throw new Failure(Code.INVALID_RECORD);
            return record;
        } catch (Failure known) {
            throw known;
        } catch (ArchiveException invalid) {
            throw archiveFailure(invalid);
        } catch (NbtAccounterException exhausted) {
            throw new Failure(Code.DECODE_LIMIT);
        } catch (IOException | NbtException | ReportedNbtException | IllegalArgumentException | NegativeArraySizeException invalid) {
            throw new Failure(Code.INVALID_RECORD);
        }
    }

    private static Failure archiveFailure(ArchiveException failure) {
        return new Failure(switch (failure.code()) {
            case INVALID_FORMAT -> Code.INVALID_RECORD;
            case UNSUPPORTED_VERSION -> Code.UNSUPPORTED_VERSION;
            case LIMIT_EXCEEDED -> Code.VALUE_LIMIT;
        });
    }

    private static final class BoundedOutput extends ByteArrayOutputStream {
        private final int limit;
        private BoundedOutput(int limit) { this.limit = limit; }
        private void reserve(int length) {
            if (length > limit - count) throw new Failure(Code.PAYLOAD_TOO_LARGE);
        }
        @Override public synchronized void write(int value) {
            reserve(1);
            super.write(value);
        }
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            reserve(length);
            super.write(bytes, offset, length);
        }
    }
}
