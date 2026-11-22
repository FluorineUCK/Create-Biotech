package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProgramEditorWireCompactionTest {
    private static final ArchiveLimits LIMITS = new ArchiveLimits(100_000, 1_000_000, 10_000_000);

    @Test void independentEqualRecordsUseTheExistingBackwardReferenceTableWithoutDroppingOccurrences() {
        var records = new ArrayList<DataValue>();
        for (int i = 0; i < 200; i++) records.add(record("item", new DataValue.TextValue("minecraft:air"),
            "count", new DataValue.IntegerValue(0)));
        var value = new DataValue.ListValue(records);
        byte[] bytes = ProgramEditorWire.encodeValue(value, LIMITS);
        assertTrue(bytes.length < 2000, "Repeated immutable values should reuse rows, not list positions");
        assertEquals(200, ((DataValue.ListValue) ProgramEditorWire.decodeValue(bytes, LIMITS)).values().size());
        assertEquals(value, ProgramEditorWire.decodeValue(bytes, LIMITS));
        var wire = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            var tag = assertInstanceOf(CompoundTag.class, wire.readNbt(NbtAccounter.create(2L * 1024 * 1024)));
            assertEquals(0, wire.readableBytes());
            assertEquals(2, tag.getInt("Version"));
            assertEquals(4, tag.getList("Values", Tag.TAG_COMPOUND).size());
            assertEquals(1, tag.getInt("WireVersion"));
            assertEquals(value, ProgramEditorWire.decodeValue(raw(tag), LIMITS));
        } finally { wire.release(); }
    }

    @Test void equalMapContentsWithDifferentIterationOrdersMustNotShareAReorderedRow() {
        var ab = record("a", new DataValue.IntegerValue(1), "b", new DataValue.IntegerValue(2));
        var ba = record("b", new DataValue.IntegerValue(2), "a", new DataValue.IntegerValue(1));
        var value = new DataValue.ListValue(List.of(ab, ba, new DataValue.MapValue(ab.fields()),
            new DataValue.MapValue(ba.fields())));
        var copy = (DataValue.ListValue) ProgramEditorWire.decodeValue(ProgramEditorWire.encodeValue(value, LIMITS), LIMITS);
        assertEquals(List.of("a", "b"), new ArrayList<>(((DataValue.RecordValue)copy.values().get(0)).fields().keySet()));
        assertEquals(List.of("b", "a"), new ArrayList<>(((DataValue.RecordValue)copy.values().get(1)).fields().keySet()));
        assertEquals(List.of("a", "b"), new ArrayList<>(((DataValue.MapValue)copy.values().get(2)).entries().keySet()));
        assertEquals(List.of("b", "a"), new ArrayList<>(((DataValue.MapValue)copy.values().get(3)).entries().keySet()));
        assertEquals(value.storageUnits(), copy.storageUnits());
    }

    @Test void typeDistinctionsNullErrorsAndRawUtf16SurviveCompaction() {
        var value = new DataValue.ListValue(List.of(DataValue.NullValue.INSTANCE, new DataValue.BooleanValue(false),
            new DataValue.IntegerValue(0), new DataValue.DecimalValue(0.0), new DataValue.DecimalValue(-0.0),
            new DataValue.TextValue("0"), new DataValue.TextValue("\uD800\u0000日志"),
            new DataValue.ErrorValue("unloaded", "世界\uDC00"), new DataValue.ListValue(List.of()),
            new DataValue.RecordValue(Map.of()), new DataValue.MapValue(Map.of())));
        var copy = (DataValue.ListValue) ProgramEditorWire.decodeValue(ProgramEditorWire.encodeValue(value, LIMITS), LIMITS);
        for (int i = 0; i < value.values().size(); i++) assertEquals(value.values().get(i), copy.values().get(i), "kind at " + i);
    }

    @Test void originalNodeStringAndLogicalBudgetsAreCheckedBeforeDeduplication() {
        var value = new DataValue.ListValue(List.of(new DataValue.TextValue("abc"), new DataValue.TextValue("abc")));
        for (var limits : List.of(new ArchiveLimits(2, 100, 100), new ArchiveLimits(100, 5, 100),
            new ArchiveLimits(100, 100, 8)))
            assertThrows(ArchiveException.class, () -> ProgramEditorWire.encodeValue(value, limits));
    }

    @Test void valuesThatCannotBeCompactedStillFailRatherThanProducePartialPayloads() {
        var value = new DataValue.TextValue("x".repeat(40_000));
        assertThrows(IllegalArgumentException.class, () -> ProgramEditorWire.encodeValue(value, LIMITS));
    }

    @Test void deepValuesRemainFlatAndNonRecursive() {
        DataValue value = new DataValue.IntegerValue(1);
        for (int i = 0; i < 400; i++) value = new DataValue.ListValue(List.of(value));
        assertEquals(value, ProgramEditorWire.decodeValue(ProgramEditorWire.encodeValue(value, LIMITS), LIMITS));
    }

    @Test void changingValuesCanShareRepeatedFieldNamesWithoutChangingRecords() {
        var records = new ArrayList<DataValue>();
        for (int i = 0; i < 100; i++) records.add(record("a-long-repeated-field-name", new DataValue.IntegerValue(i),
            "a-second-long-repeated-field-name", new DataValue.TextValue("same")));
        var value = new DataValue.ListValue(records);
        byte[] bytes = ProgramEditorWire.encodeValue(value, LIMITS);
        assertTrue(bytes.length < 15_000);
        assertEquals(value, ProgramEditorWire.decodeValue(bytes, LIMITS));
    }

    @Test void fieldDictionaryRejectsWrongTypesReferencesAndUnknownWireVersions() {
        var value = record("a", new DataValue.IntegerValue(1), "b", new DataValue.IntegerValue(2));
        var original = EditorValueTableCompaction.compact(ValueNbtCodec.encode(value, LIMITS));
        for (int ref : new int[] {-1, 2, Integer.MAX_VALUE}) {
            var bad = original.copy();
            bad.getList("Values", Tag.TAG_COMPOUND).getCompound(2)
                .getList("Fields", Tag.TAG_COMPOUND).getCompound(0).putInt("Name", ref);
            assertThrows(IllegalArgumentException.class, () -> ProgramEditorWire.decodeValue(raw(bad), LIMITS));
        }
        var badType = original.copy(); badType.putString("FieldNames", "not a dictionary");
        assertThrows(IllegalArgumentException.class, () -> ProgramEditorWire.decodeValue(raw(badType), LIMITS));
        var badName = original.copy(); var names = new ListTag(); names.add(StringTag.valueOf("not UTF-16"));
        badName.put("FieldNames", names);
        assertThrows(IllegalArgumentException.class, () -> ProgramEditorWire.decodeValue(raw(badName), LIMITS));
        var badVersion = original.copy(); badVersion.putInt("WireVersion", 99);
        assertThrows(IllegalArgumentException.class, () -> ProgramEditorWire.decodeValue(raw(badVersion), LIMITS));
        var wrongVersionType = original.copy(); wrongVersionType.putString("WireVersion", "1");
        assertThrows(IllegalArgumentException.class, () -> ProgramEditorWire.decodeValue(raw(wrongVersionType), LIMITS));
    }

    @Test void decoderBudgetsCountEveryExpandedFieldNameAndLegacyWireStillLoads() {
        var value = new DataValue.ListValue(List.of(record("abc", new DataValue.IntegerValue(1),
            "def", new DataValue.IntegerValue(2)), record("abc", new DataValue.IntegerValue(3),
            "def", new DataValue.IntegerValue(4))));
        byte[] bytes = ProgramEditorWire.encodeValue(value, LIMITS);
        assertThrows(ArchiveException.class, () -> ProgramEditorWire.decodeValue(bytes, new ArchiveLimits(100, 11, 1000)));
        var legacy = ValueNbtCodec.encode(value, LIMITS); legacy.putInt("Version", 1);
        assertEquals(value, ProgramEditorWire.decodeValue(raw(legacy), LIMITS));
    }

    private static byte[] raw(CompoundTag tag) {
        var wire = new FriendlyByteBuf(Unpooled.buffer());
        try {
            wire.writeNbt(tag); byte[] bytes = new byte[wire.readableBytes()]; wire.readBytes(bytes); return bytes;
        } finally { wire.release(); }
    }

    private static DataValue.RecordValue record(String first, DataValue a, String second, DataValue b) {
        var fields = new LinkedHashMap<String, DataValue>(); fields.put(first, a); fields.put(second, b);
        return new DataValue.RecordValue(fields);
    }
}
