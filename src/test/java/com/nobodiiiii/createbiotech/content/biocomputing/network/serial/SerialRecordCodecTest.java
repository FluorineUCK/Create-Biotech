package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SerialRecordCodecTest {
    private static final ArchiveLimits VALUES = new ArchiveLimits(100_000, 1_000_000, 10_000_000);
    private static final SerialRecordCodec.Limits LIMITS = new SerialRecordCodec.Limits(1_000_000, 16_000_000, VALUES);

    @BeforeAll static void gameVersionForMalformedNbtReports() {
        // NbtIo wraps truncated compound input in Minecraft's crash-report exception.
        net.minecraft.SharedConstants.tryDetectVersion();
    }

    @Test void namedRecordRoundTripsThroughActualBytesWithoutMutableAliases() {
        var source = new DataValue.RecordValue(Map.of("读数", new DataValue.ListValue(List.of(
            new DataValue.IntegerValue(Long.MIN_VALUE), new DataValue.TextValue("1\uD800"),
            new DataValue.DecimalValue(-0.0), new DataValue.BooleanValue(false), DataValue.NullValue.INSTANCE,
            new DataValue.ErrorValue("unloaded", "不是零"),
            new DataValue.MapValue(Map.of("项目", new DataValue.IntegerValue(1)))))));

        byte[] bytes = SerialRecordCodec.encode(source, LIMITS);
        var received = SerialRecordCodec.decode(bytes, LIMITS);
        assertEquals(source, received);
        assertNotSame(source, received);
        Arrays.fill(bytes, (byte) 0);
        assertEquals(source, received, "Modifying the byte buffer must not modify either immutable record");
        assertEquals(source, SerialRecordCodec.decode(SerialRecordCodec.encode(source, LIMITS), LIMITS));
    }

    @Test void exactEncodedByteLimitAcceptsButOneByteLessRejectsWithoutTruncation() {
        var source = new DataValue.RecordValue(Map.of("名称", new DataValue.TextValue("温".repeat(300))));
        byte[] bytes = SerialRecordCodec.encode(source, LIMITS);
        assertTrue(bytes.length > source.storageUnits(), "Wire bytes must not be confused with logical RAM units");
        var exact = new SerialRecordCodec.Limits(bytes.length, LIMITS.maxDecodeHeapBytes(), VALUES);
        assertArrayEquals(bytes, SerialRecordCodec.encode(source, exact));
        assertEquals(source, SerialRecordCodec.decode(bytes, exact));

        var smaller = new SerialRecordCodec.Limits(bytes.length - 1, LIMITS.maxDecodeHeapBytes(), VALUES);
        assertThrows(IllegalArgumentException.class, () -> SerialRecordCodec.encode(source, smaller));
        assertThrows(IllegalArgumentException.class, () -> SerialRecordCodec.decode(bytes, smaller));
        assertArrayEquals(bytes, SerialRecordCodec.encode(source, exact), "Rejection must not change the source");
    }

    @Test void rejectsTrailingTruncatedNonRecordAndMalformedDataWithSafeDiagnostics() throws IOException {
        byte[] good = SerialRecordCodec.encode(new DataValue.RecordValue(Map.of()), LIMITS);
        rejected("INVALID_RECORD", () -> SerialRecordCodec.decode(Arrays.copyOf(good, good.length + 1), LIMITS));
        rejected("INVALID_RECORD", () -> SerialRecordCodec.decode(Arrays.copyOf(good, good.length - 1), LIMITS));
        rejected("INVALID_RECORD", () -> SerialRecordCodec.decode(new byte[0], LIMITS));
        rejected("INVALID_RECORD", () -> SerialRecordCodec.decode(new byte[] {0}, LIMITS));
        byte[] scalar = raw(ValueNbtCodec.encode(new DataValue.TextValue("private payload"), VALUES));
        rejected("INVALID_RECORD", () -> SerialRecordCodec.decode(scalar, LIMITS));
        byte[] invalid = raw(new CompoundTag());
        rejected("INVALID_RECORD", () -> SerialRecordCodec.decode(invalid, LIMITS));
    }

    @Test void byteValueParserAndVersionFailuresRemainDistinct() throws IOException {
        var source = new DataValue.RecordValue(Map.of("private", new DataValue.TextValue("payload")));
        byte[] bytes = SerialRecordCodec.encode(source, LIMITS);
        var byteLimit = new SerialRecordCodec.Limits(bytes.length - 1, LIMITS.maxDecodeHeapBytes(), VALUES);
        rejected("PAYLOAD_TOO_LARGE", () -> SerialRecordCodec.encode(source, byteLimit));
        rejected("PAYLOAD_TOO_LARGE", () -> SerialRecordCodec.decode(bytes, byteLimit));
        for (var values : List.of(new ArchiveLimits(1, 1000, 1000),
                new ArchiveLimits(1000, 0, 1000), new ArchiveLimits(1000, 1000, 1))) {
            var limits = new SerialRecordCodec.Limits(LIMITS.maxEncodedBytes(), LIMITS.maxDecodeHeapBytes(), values);
            rejected("VALUE_LIMIT", () -> SerialRecordCodec.encode(source, limits));
            rejected("VALUE_LIMIT", () -> SerialRecordCodec.decode(bytes, limits));
        }
        var parserLimit = new SerialRecordCodec.Limits(LIMITS.maxEncodedBytes(), 1, VALUES);
        rejected("DECODE_LIMIT", () -> SerialRecordCodec.decode(bytes, parserLimit));
        var future = ValueNbtCodec.encode(source, VALUES);
        future.putInt("Version", 99);
        byte[] unsupported = raw(future);
        rejected("UNSUPPORTED_VERSION", () -> SerialRecordCodec.decode(unsupported, LIMITS));
    }

    @Test void deepValuesAndLongRawUtf16RemainUsableWithBoundedFlatArchives() {
        DataValue nested = new DataValue.IntegerValue(42);
        for (int i = 0; i < 10_000; i++) nested = new DataValue.ListValue(List.of(nested));
        var source = new DataValue.RecordValue(Map.of("nested", nested,
            "温".repeat(30_000) + "\uDC00", new DataValue.TextValue("\uD800" + "文".repeat(30_000))));
        byte[] bytes = SerialRecordCodec.encode(source, LIMITS);
        assertEquals(source, SerialRecordCodec.decode(bytes, LIMITS));
    }

    @Test void compactArchiveReferencesDoNotBypassLogicalReceiverCapacity() {
        DataValue shared = new DataValue.IntegerValue(7);
        for (int i = 0; i < 12; i++) shared = new DataValue.ListValue(List.of(shared, shared));
        var source = new DataValue.RecordValue(Map.of("shared", shared));
        byte[] bytes = SerialRecordCodec.encode(source, LIMITS);
        assertTrue(bytes.length < source.storageUnits());
        var smallRam = new SerialRecordCodec.Limits(LIMITS.maxEncodedBytes(), LIMITS.maxDecodeHeapBytes(),
            new ArchiveLimits(VALUES.maxNodes(), VALUES.maxStringChars(), source.storageUnits() - 1));
        rejected("VALUE_LIMIT", () -> SerialRecordCodec.decode(bytes, smallRam));
        assertEquals(source, SerialRecordCodec.decode(bytes, LIMITS));
    }

    @Test void declaredHugeAndNegativeArraysDoNotEscapeTheDecoderBoundary() throws IOException {
        for (int length : new int[] {Integer.MAX_VALUE, -1}) {
            var bytes = new ByteArrayOutputStream();
            var data = new DataOutputStream(bytes);
            data.writeByte(10); // Compound root.
            data.writeUTF("");
            data.writeByte(7); // Byte array: reject its declared size before attempting the allocation/read.
            data.writeUTF("private");
            data.writeInt(length);
            rejected(length < 0 ? "INVALID_RECORD" : "DECODE_LIMIT",
                () -> SerialRecordCodec.decode(bytes.toByteArray(), LIMITS));
        }
    }

    @Test void valuesAreNotSilentlyCoercedAndPayloadDetailsAreNotReported() throws IOException {
        var tag = ValueNbtCodec.encode(new DataValue.RecordValue(Map.of("private", new DataValue.BooleanValue(true))), VALUES);
        tag.getList("Values", 10).getCompound(0).putByte("Value", (byte) 2);
        byte[] invalidBoolean = raw(tag);
        rejected("INVALID_RECORD", () -> SerialRecordCodec.decode(invalidBoolean, LIMITS));
        tag = ValueNbtCodec.encode(new DataValue.RecordValue(Map.of("private", new DataValue.BooleanValue(true))), VALUES);
        var fields = tag.getList("Values", 10).getCompound(1).getList("Fields", 10);
        fields.add(fields.getCompound(0).copy());
        byte[] duplicateName = raw(tag);
        rejected("INVALID_RECORD", () -> SerialRecordCodec.decode(duplicateName, LIMITS));
    }

    @Test void unsupportedLimitsAreRejectedRatherThanConvertedToUnlimited() {
        assertThrows(IllegalArgumentException.class, () -> new SerialRecordCodec.Limits(0, 1, VALUES));
        assertThrows(IllegalArgumentException.class, () -> new SerialRecordCodec.Limits(-1, 1, VALUES));
        assertThrows(IllegalArgumentException.class, () -> new SerialRecordCodec.Limits(1, 0, VALUES));
        assertThrows(IllegalArgumentException.class, () -> new SerialRecordCodec.Limits(1, -1, VALUES));
        assertThrows(NullPointerException.class, () -> new SerialRecordCodec.Limits(1, 1, null));
    }

    private static void rejected(String code, Runnable operation) {
        var failure = assertThrows(SerialRecordCodec.Failure.class, operation::run);
        assertEquals(code, failure.code().name());
        assertEquals(code, failure.getMessage(), "Diagnostic text must never contain payloads or archive details");
        assertNull(failure.getCause(), "Raw parser exceptions can contain private fields");
    }

    private static byte[] raw(CompoundTag tag) throws IOException {
        var bytes = new ByteArrayOutputStream();
        NbtIo.write(tag, new DataOutputStream(bytes));
        return bytes.toByteArray();
    }
}
