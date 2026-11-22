package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NbtSnapshotValuesTest {
    private final ReadLimits limits = new ReadLimits(4096, 32768, 65536);
    @Test void privateCarriedServiceCannotBeReadThroughComponentsOrNestedContainerNbt() {
        var components = new CompoundTag();
        var secret = new CompoundTag(); secret.putString("request", "private-payload");
        String key = "create_biotech:biocomputing_library_service";
        components.put(key, secret); components.putString("other_mod:public", "visible");
        var expected = new DataValue.ErrorValue("access_denied", "access_denied");
        var direct = (DataValue.RecordValue)NbtSnapshotValues.copy(components, limits);
        assertEquals(expected, direct.field(key));
        assertEquals(new DataValue.TextValue("visible"), direct.field("other_mod:public"));
        var item = new CompoundTag(); item.put("components", components);
        var list = new ListTag(); list.add(item);
        var root = new CompoundTag(); root.put("Items", list);
        var snapshot = (DataValue.RecordValue)NbtSnapshotValues.copy(root, limits);
        var projectedItem = (DataValue.RecordValue)((DataValue.ListValue)snapshot.field("Items")).values().getFirst();
        assertEquals(expected, ((DataValue.RecordValue)projectedItem.field("components")).field(key));
        assertEquals("private-payload", components.getCompound(key).getString("request"));
    }
    @Test void copiesNestedValuesWithoutRetainingMutableTags() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("integer", 9007199254740993L); tag.putDouble("decimal", 0.25);
        tag.putIntArray("array", new int[]{2, 3});
        CompoundTag nested = new CompoundTag(); nested.putString("text", "before"); tag.put("nested", nested);
        var snapshot = (DataValue.RecordValue) NbtSnapshotValues.copy(tag, limits);
        nested.putString("text", "after"); tag.getIntArray("array")[0] = 99;
        assertEquals(new DataValue.IntegerValue(9007199254740993L), snapshot.field("integer"));
        assertEquals(new DataValue.DecimalValue(0.25), snapshot.field("decimal"));
        assertEquals(new DataValue.IntegerValue(2), ((DataValue.ListValue)snapshot.field("array")).values().getFirst());
        assertEquals(new DataValue.TextValue("before"), ((DataValue.RecordValue)snapshot.field("nested")).field("text"));
    }
    @Test void rejectsWideStringsUnitsAndExcessiveNestingRatherThanTruncating() {
        assertThrows(IllegalArgumentException.class, () -> NbtSnapshotValues.copy(new IntArrayTag(new int[]{1,2,3}), new ReadLimits(2, 100, 100)));
        assertThrows(IllegalArgumentException.class, () -> NbtSnapshotValues.copy(StringTag.valueOf("abc"), new ReadLimits(10, 2, 100)));
        assertThrows(IllegalArgumentException.class, () -> NbtSnapshotValues.copy(StringTag.valueOf("abc"), new ReadLimits(10, 100, 3)));
        CompoundTag root = new CompoundTag(), cursor = root;
        for (int i = 0; i < 10000; i++) { CompoundTag next = new CompoundTag(); cursor.put("x", next); cursor = next; }
        assertThrows(IllegalArgumentException.class, () -> NbtSnapshotValues.copy(root, limits));
    }
    @Test void rejectsInvalidLimitsNonFiniteNumbersAndEndTags() {
        assertThrows(IllegalArgumentException.class, () -> new ReadLimits(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ReadLimits(1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ReadLimits(1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> NbtSnapshotValues.copy(DoubleTag.valueOf(Double.NaN), limits));
        assertThrows(IllegalArgumentException.class, () -> NbtSnapshotValues.copy(EndTag.INSTANCE, limits));
    }
    @Test void aggregateAccountingIncludesFieldNamesAndAllBranches() {
        CompoundTag root = new CompoundTag(); root.putString("a", "ab"); root.putString("b", "cd");
        assertThrows(IllegalArgumentException.class, () -> NbtSnapshotValues.copy(root, new ReadLimits(10, 5, 100)));
        assertEquals(11, NbtSnapshotValues.copy(root, new ReadLimits(10, 6, 11)).storageUnits());
    }
}
