package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.io.*;
import java.util.List;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DecimalValueNbtTest {
    private static final ArchiveLimits LIMITS = ValueNbtCodecTest.LIMITS;

    @Test void signedZeroAndFiniteExtremesSurviveActualNbtIoExactly() throws IOException {
        for (double number : new double[] {0.0, -0.0, 1.25, -1.25, Double.MIN_VALUE,
            -Double.MIN_VALUE, Double.MIN_NORMAL, -Double.MAX_VALUE, Double.MAX_VALUE}) {
            var value = new DataValue.DecimalValue(number);
            var archive = ValueNbtCodec.encode(value, LIMITS);
            assertEquals(value, ValueNbtCodec.decode(archive, LIMITS), "in-memory " + number);
            var bytes = new ByteArrayOutputStream();
            NbtIo.write(archive, new DataOutputStream(bytes));
            var loaded = NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
            var copy = assertInstanceOf(DataValue.DecimalValue.class, ValueNbtCodec.decode(loaded, LIMITS));
            assertEquals(Double.doubleToRawLongBits(number), Double.doubleToRawLongBits(copy.value()), "NBT " + number);
        }
    }

    @Test void versionTwoUsesLongBitsAndDoesNotChangeTheDecimalValueType() {
        var archive = ValueNbtCodec.encode(new DataValue.DecimalValue(-0.0), LIMITS);
        assertEquals(2, archive.getInt("Version"));
        var row = archive.getList("Values", Tag.TAG_COMPOUND).getCompound(0);
        assertEquals("decimal", row.getString("Kind"));
        assertTrue(row.contains("Value", Tag.TAG_LONG));
        assertEquals(Long.MIN_VALUE, row.getLong("Value"));
        assertEquals(new DataValue.DecimalValue(-0.0), ValueNbtCodec.decode(archive, LIMITS));
    }

    @Test void historicalVersionOneDoubleRowsStillLoad() {
        for (double number : new double[] {0.0, 1.25, -1.25, Double.MIN_VALUE, Double.MAX_VALUE}) {
            var archive = decimal(1, DoubleTag.valueOf(number));
            assertEquals(new DataValue.DecimalValue(number), ValueNbtCodec.decode(archive, LIMITS));
        }
    }

    @Test void versionSpecificNumericTagTypesCannotBeSilentlyCoerced() {
        assertThrows(ArchiveException.class, () -> ValueNbtCodec.decode(decimal(1, LongTag.valueOf(1)), LIMITS));
        assertThrows(ArchiveException.class, () -> ValueNbtCodec.decode(decimal(2, DoubleTag.valueOf(1)), LIMITS));
        assertThrows(ArchiveException.class, () -> ValueNbtCodec.decode(decimal(2, IntTag.valueOf(1)), LIMITS));
    }

    @Test void neitherVersionMayIntroduceNonFiniteValues() {
        for (double number : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(ArchiveException.class, () -> ValueNbtCodec.decode(decimal(1, DoubleTag.valueOf(number)), LIMITS));
            assertThrows(ArchiveException.class, () -> ValueNbtCodec.decode(
                decimal(2, LongTag.valueOf(Double.doubleToRawLongBits(number))), LIMITS));
        }
    }

    private static CompoundTag decimal(int version, Tag payload) {
        var row = new CompoundTag(); row.putString("Kind", "decimal"); row.put("Value", payload);
        var rows = new ListTag(); rows.add(row);
        var archive = new CompoundTag(); archive.putInt("Version", version);
        archive.putInt("Root", 0); archive.put("Values", rows); return archive;
    }
}
