package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.*;

/** Opt-in menu transport, not device data: preserve small legacy payloads byte for byte. */
public enum BoundedEditorEnvelope {
    DOCUMENT(0x42435001), CLUSTER_CONFIGURATION(0x42434301), CLUSTER_VIEW(0x42435601), CAPTURE(0x42435301);

    // Old document safety limits; no increase to the common 24 KiB packet or device budgets.
    static final int MAX_EXPANDED_BYTES = 512 * 1024;
    private static final int HEADER = 8;
    private final int magic;
    BoundedEditorEnvelope(int magic) { this.magic = magic; }
    public byte[] encode(DataValue value, ArchiveLimits limits) {
        byte[] raw = ProgramEditorWire.encodeValue(value, limits, MAX_EXPANDED_BYTES);
        if (raw.length <= ProgramEditorWire.MAX_BYTES) return raw; // Historical uncompressed editor value envelope.
        var compressor = new Deflater();
        try {
            compressor.setInput(raw); compressor.finish();
            byte[] output = new byte[ProgramEditorWire.MAX_BYTES]; ByteBuffer.wrap(output).putInt(magic).putInt(raw.length);
            int used = HEADER;
            while (!compressor.finished() && used < output.length) {
                int count = compressor.deflate(output, used, output.length - used);
                if (count == 0) break; used += count;
            }
            if (!compressor.finished()) throw new IllegalArgumentException("editor value exceeds editor wire capacity");
            return Arrays.copyOf(output, used);
        } finally { compressor.end(); }
    }
    public DataValue decode(byte[] bytes, ArchiveLimits limits) {
        if (bytes == null || bytes.length == 0 || bytes.length > ProgramEditorWire.MAX_BYTES)
            throw new IllegalArgumentException("invalid editor value length");
        if (bytes[0] != 0x42) return ProgramEditorWire.decodeValue(bytes, limits);
        if (bytes.length <= HEADER) throw new IllegalArgumentException("truncated editor value envelope");
        var header = ByteBuffer.wrap(bytes);
        if (header.getInt() != magic) throw new IllegalArgumentException("unsupported editor value envelope");
        int length = header.getInt();
        if (length <= 0 || length > MAX_EXPANDED_BYTES) throw new IllegalArgumentException("invalid expanded editor value length");
        var inflater = new Inflater();
        try {
            inflater.setInput(bytes, HEADER, bytes.length - HEADER); byte[] raw = new byte[length]; int used = 0;
            while (!inflater.finished() && used < length) {
                int count = inflater.inflate(raw, used, length - used);
                if (count == 0) break; used += count;
            }
            if (!inflater.finished() || used != length || inflater.getRemaining() != 0)
                throw new IllegalArgumentException("truncated, oversized or trailing compressed editor value");
            return ProgramEditorWire.decodeValue(raw, limits, MAX_EXPANDED_BYTES);
        } catch (DataFormatException invalid) {
            throw new IllegalArgumentException("invalid compressed editor value", invalid);
        } finally { inflater.end(); }
    }
}
