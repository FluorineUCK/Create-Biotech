package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

class NativeNbtCodecTest {
    static final ArchiveLimits VALUES = new ArchiveLimits(10000, 100000, 1000000);
    static final NativeNbtCodec.Limits LIMITS = new NativeNbtCodec.Limits(100000, 1000000, VALUES);

    @Test void rootTagsAndFiniteResourcesAreExplicitRatherThanSilentlyFlattened() {
        for (Tag tag : java.util.List.of(EndTag.INSTANCE, ByteTag.valueOf((byte)1), ShortTag.valueOf((short)1),
                IntTag.valueOf(1), LongTag.valueOf(1), FloatTag.valueOf(1.5f), DoubleTag.valueOf(Double.POSITIVE_INFINITY),
                StringTag.valueOf("text"), new ByteArrayTag(new byte[]{1}), new IntArrayTag(new int[]{1}), new LongArrayTag(new long[]{1}), new ListTag())) {
            var value = NativeNbtCodec.snapshot(tag, LIMITS);
            var encoded = NativeNbtCodec.encode(value, LIMITS);
            assertEquals(tag, NativeNbtCodec.toTag(NativeNbtCodec.decode(encoded, LIMITS), LIMITS));
            assertEquals(value, ValueNbtCodec.decode(ValueNbtCodec.encode(value, VALUES), VALUES));
            var tiny = new NativeNbtCodec.Limits(Math.max(1, encoded.length - 1), LIMITS.maxDecodeHeapBytes(), VALUES);
            if (encoded.length > 1) {
                assertEquals(NativeNbtCodec.Code.PAYLOAD_TOO_LARGE, assertThrows(NativeNbtCodec.Failure.class, () -> NativeNbtCodec.encode(value, tiny)).code());
                assertEquals(NativeNbtCodec.Code.PAYLOAD_TOO_LARGE, assertThrows(NativeNbtCodec.Failure.class, () -> NativeNbtCodec.decode(encoded, tiny)).code());
            }
            assertThrows(NativeNbtCodec.Failure.class, () -> NativeNbtCodec.decode(java.util.Arrays.copyOf(encoded, encoded.length + 1), LIMITS));
        }
        var nbt = NativeNbtCodec.snapshot(IntTag.valueOf(1), LIMITS);
        assertNotEquals(NativeNbtCodec.snapshot(ByteTag.valueOf((byte)1), LIMITS), nbt);
        assertNotEquals(new DataValue.IntegerValue(1), nbt);
        var saved = ValueNbtCodec.encode(nbt, VALUES); assertEquals(3, saved.getInt("Version"));
        saved.putInt("Version", 2); assertThrows(ArchiveException.class, () -> ValueNbtCodec.decode(saved, VALUES));
        assertEquals(2, ValueNbtCodec.encode(new DataValue.IntegerValue(1), VALUES).getInt("Version"));
    }

    @Test void malformedAndOverBudgetInputDoesNotBecomeEmptyOrZero() throws java.io.IOException {
        var tag = new CompoundTag(); tag.putString("private", "payload");
        byte[] bytes = NativeNbtCodec.encode(NativeNbtCodec.snapshot(tag, LIMITS), LIMITS);
        assertEquals(NativeNbtCodec.Code.DECODE_LIMIT, assertThrows(NativeNbtCodec.Failure.class,
            () -> NativeNbtCodec.decode(bytes, new NativeNbtCodec.Limits(100000, 1, VALUES))).code());
        for (var budget : java.util.List.of(new ArchiveLimits(1, 1000, 1000), new ArchiveLimits(1000, 0, 1000), new ArchiveLimits(1000, 1000, 1))) {
            var small = new NativeNbtCodec.Limits(100000, 1000000, budget);
            assertEquals(NativeNbtCodec.Code.VALUE_LIMIT, assertThrows(NativeNbtCodec.Failure.class, () -> NativeNbtCodec.snapshot(tag, small)).code());
            assertEquals(NativeNbtCodec.Code.VALUE_LIMIT, assertThrows(NativeNbtCodec.Failure.class, () -> NativeNbtCodec.decode(bytes, small)).code());
        }
        for (int size : new int[]{-1, Integer.MAX_VALUE}) {
            var output = new java.io.ByteArrayOutputStream(); var data = new java.io.DataOutputStream(output);
            data.writeByte(Tag.TAG_BYTE_ARRAY); data.writeUTF(""); data.writeInt(size);
            var failure = assertThrows(NativeNbtCodec.Failure.class, () -> NativeNbtCodec.decode(output.toByteArray(), LIMITS));
            assertEquals(size < 0 ? NativeNbtCodec.Code.INVALID_NBT : NativeNbtCodec.Code.DECODE_LIMIT, failure.code());
            assertEquals(failure.code().name(), failure.getMessage()); assertNull(failure.getCause());
        }
        var large = new CompoundTag(); large.putString("text", "a".repeat(65536));
        assertEquals(NativeNbtCodec.Code.INVALID_NBT, assertThrows(NativeNbtCodec.Failure.class,
            () -> NativeNbtCodec.encode(NativeNbtCodec.snapshot(large, LIMITS), LIMITS)).code(), "No fallback to empty UTF strings");
    }

    @Test void nativeTypesAndArraysSurviveWithoutWorldOrTagAliases() {
        var source = new CompoundTag();
        source.putByte("b", (byte) 1); source.putShort("s", (short) 1);
        source.putInt("i", 1); source.putLong("l", 1);
        source.putFloat("f", 1.5f); source.putDouble("d", 1.5);
        source.putByteArray("bytes", new byte[]{1, -2});
        source.putIntArray("ints", new int[]{1, Integer.MIN_VALUE});
        source.putLongArray("longs", new long[]{1, Long.MIN_VALUE});
        source.putString("text", "书本");
        var list = new ListTag(); list.add(IntTag.valueOf(3)); source.put("list", list);
        var expected = source.copy();
        var value = NativeNbtCodec.snapshot(source, LIMITS);
        source.putInt("i", 99); source.getByteArray("bytes")[0] = 88;
        var bytes = NativeNbtCodec.encode(value, LIMITS);
        var received = NativeNbtCodec.decode(bytes, LIMITS);
        assertEquals(value, received);
        assertEquals(expected, NativeNbtCodec.toTag(received, LIMITS));
        var exposed = (CompoundTag)NativeNbtCodec.toTag(received, LIMITS);
        exposed.putInt("i", 77);
        assertEquals(expected, NativeNbtCodec.toTag(received, LIMITS));
        assertEquals(DataType.Scalar.NBT, DataType.typeOf(received));
        assertEquals(received, ValueNbtCodec.decode(ValueNbtCodec.encode(received, VALUES), VALUES));
    }
}
