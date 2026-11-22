package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

/** Portable Library DATA entry: base64 avoids one typed archive node per source byte. */
public final class SpeakerMediaLibraryFile {
    public static final String SCHEMA = "speaker_file_v1";
    // Bounded metadata: six archive rows, five fields, short names (including the 120-character file name), and stream wrappers.
    private static final long TRANSFER_METADATA_UNITS = 8192;
    private SpeakerMediaLibraryFile() {}

    /** Encodes a bounded snapshot of the file, for an explicit Library segmented upload. */
    public static byte[] encode(String name, byte[] contents, int limit, ArchiveLimits archive) {
        return SerialRecordCodec.encode(encodeRecord(name, contents, limit), new SerialRecordCodec.Limits(limit, limit, archive));
    }

    public static long transferWorkspace(String name, int size, int limit) {
        var format = SpeakerMediaFormat.fromName(name);
        if (format == null || name.length() > SpeakerMediaChunk.MAX_NAME || size <= 0) throw invalid();
        long base64Chars = 4L * ((size + 2L) / 3);
        if (2 * base64Chars > limit) throw new IllegalArgumentException("payload_too_large");
        // Base64 encoder's octets, worst-case UTF-16 text, and archived UTF-16 octets. Output is charged separately.
        return TRANSFER_METADATA_UNITS + 5 * base64Chars;
    }

    private static DataValue.RecordValue encodeRecord(String name, byte[] contents, int limit) {
        transferWorkspace(name, contents.length, limit);
        var value = new DataValue.RecordValue(Map.of("schema", text(SCHEMA), "name", text(name),
            "format", text(SpeakerMediaFormat.fromName(name).name()), "payload", text(Base64.getEncoder().encodeToString(contents))));
        return new DataValue.RecordValue(Map.of("value", value));
    }

    /** Build the existing typed archive and count its exact wire size without allocating a serialized copy. */
    public static TransferEncoding prepareTransfer(String name, byte[] contents, int limit, ArchiveLimits archive) {
        CompoundTag tag;
        try { tag = ValueNbtCodec.encode(encodeRecord(name, contents, limit), archive); }
        catch (ArchiveException failure) {
            throw new IllegalArgumentException(switch (failure.code()) {
                case INVALID_FORMAT -> "invalid_record";
                case UNSUPPORTED_VERSION -> "unsupported_version";
                case LIMIT_EXCEEDED -> "value_limit";
            }, failure);
        }
        var counter = new CountOutput(limit);
        try { NbtIo.write(tag, new DataOutputStream(counter)); }
        catch (IOException failure) { throw new IllegalArgumentException("invalid_record", failure); }
        return new TransferEncoding(tag, counter.bytes);
    }

    private static final class CountOutput extends OutputStream {
        private final int maximum;
        private int bytes;
        private CountOutput(int maximum) { this.maximum = maximum; }
        private void advance(int count) {
            if (count > maximum - bytes) throw new IllegalArgumentException("payload_too_large");
            bytes += count;
        }
        @Override public void write(int value) { advance(1); }
        @Override public void write(byte[] source, int offset, int count) {
            Objects.checkFromIndexSize(offset, count, source.length);
            advance(count);
        }
    }

    public static final class TransferEncoding {
        private final CompoundTag tag;
        private final int size;
        private TransferEncoding(CompoundTag tag, int size) { this.tag = tag; this.size = size; }
        public int size() { return size; }
        public void write(OutputStream output) throws IOException {
            NbtIo.write(tag, new DataOutputStream(output));
        }
    }

    public static byte[] read(DataValue value, int maximumBytes) {
        if (!(value instanceof DataValue.RecordValue record) || !record.fields().keySet().equals(java.util.Set.of("schema", "name", "format", "payload"))
            || !text(SCHEMA).equals(record.fields().get("schema"))
            || !(record.field("name") instanceof DataValue.TextValue name)
            || !(record.field("payload") instanceof DataValue.TextValue payload)
            || name.value().length() > SpeakerMediaChunk.MAX_NAME) throw invalid();
        var format = SpeakerMediaFormat.fromName(name.value());
        String encoded = payload.value();
        // Bound the decode allocation; canonical padding later separates the last 0–2 bytes.
        if (format == null || !text(format.name()).equals(record.field("format")) || maximumBytes < 1
            || encoded.isEmpty() || encoded.length() % 4 != 0 || encoded.length() > 4L * ((maximumBytes + 2L) / 3)) throw invalid();
        byte[] contents = Base64.getDecoder().decode(encoded);
        if (contents.length > maximumBytes || !format.matches(contents)
            || !Base64.getEncoder().encodeToString(contents).equals(encoded)) throw invalid();
        return contents;
    }

    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("media_invalid"); }
}
