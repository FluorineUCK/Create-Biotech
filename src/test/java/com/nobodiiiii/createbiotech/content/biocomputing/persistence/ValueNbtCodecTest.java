package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.io.*;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

class ValueNbtCodecTest {
	static final ArchiveLimits LIMITS = new ArchiveLimits(100_000, 1_000_000, 10_000_000);

	@Test void allKindsSurviveActualNbtIoWithoutSharingMutableTags() throws IOException {
		var value = new DataValue.RecordValue(Map.of("", new DataValue.ListValue(List.of(
			new DataValue.BooleanValue(true), new DataValue.IntegerValue(Long.MIN_VALUE),
			new DataValue.DecimalValue(1.25), new DataValue.TextValue("温度"), DataValue.NullValue.INSTANCE,
			new DataValue.ErrorValue("UNLOADED", "target is not loaded"),
			new DataValue.MapValue(Map.of("a", new DataValue.IntegerValue(3)))))));
		var tag = ValueNbtCodec.encode(value, LIMITS);
		var bytes = new ByteArrayOutputStream();
		NbtIo.write(tag, new DataOutputStream(bytes));
		var loaded = NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
		assertEquals(value, ValueNbtCodec.decode(loaded, LIMITS));
		tag.putInt("Version", 99);
		assertEquals(value, ValueNbtCodec.decode(loaded, LIMITS));
	}

	@Test void deeplyNestedValuesUseAFlatTableRatherThanNbtRecursion() throws IOException {
		DataValue value = new DataValue.IntegerValue(1);
		for (int i = 0; i < 10_000; i++) value = new DataValue.ListValue(List.of(value));
		var encoded = ValueNbtCodec.encode(value, LIMITS);
		var bytes = new ByteArrayOutputStream();
		NbtIo.write(encoded, new DataOutputStream(bytes));
		assertEquals(value, ValueNbtCodec.decode(NbtIo.read(new DataInputStream(
			new ByteArrayInputStream(bytes.toByteArray()))), LIMITS));
	}

	@Test void wrongVersionWrongNbtTypeAndForwardReferencesAreRejected() {
		var value = new DataValue.ListValue(List.of(new DataValue.IntegerValue(2)));
		var tag = ValueNbtCodec.encode(value, LIMITS);
		tag.putInt("Version", 5);
		assertThrows(ArchiveException.class, () -> ValueNbtCodec.decode(tag, LIMITS));
		tag.putInt("Version", 1);
		tag.putString("Root", "1");
		assertThrows(ArchiveException.class, () -> ValueNbtCodec.decode(tag, LIMITS));
		tag.putInt("Root", 1);
		tag.getList("Values", Tag.TAG_COMPOUND).getCompound(1).putIntArray("Items", new int[] {1});
		assertThrows(ArchiveException.class, () -> ValueNbtCodec.decode(tag, LIMITS));
	}

	@Test void limitsApplyToEncodeAndDecodeIncludingLogicalCopies() {
		var value = new DataValue.ListValue(List.of(new DataValue.TextValue("abc")));
		var encoded = ValueNbtCodec.encode(value, LIMITS);
		for (var limit : List.of(new ArchiveLimits(1, 100, 100), new ArchiveLimits(100, 2, 100),
			new ArchiveLimits(100, 100, 4))) {
			assertThrows(ArchiveException.class, () -> ValueNbtCodec.encode(value, limit));
			assertThrows(ArchiveException.class, () -> ValueNbtCodec.decode(encoded, limit));
		}
		DataValue shared = new DataValue.IntegerValue(1);
		for (int i = 0; i < 12; i++) shared = new DataValue.ListValue(List.of(shared, shared));
		var huge = shared;
		assertThrows(ArchiveException.class, () -> ValueNbtCodec.encode(huge, new ArchiveLimits(100, 100, 1000)));
	}

	@Test void duplicateNamesAndInvalidBooleansAreNotCoerced() {
		var encoded = ValueNbtCodec.encode(new DataValue.RecordValue(Map.of("x", new DataValue.BooleanValue(true))), LIMITS);
		var rows = encoded.getList("Values", Tag.TAG_COMPOUND);
		var fields = rows.getCompound(1).getList("Fields", Tag.TAG_COMPOUND);
		fields.add(fields.getCompound(0).copy());
		assertThrows(ArchiveException.class, () -> ValueNbtCodec.decode(encoded, LIMITS));
		var bool = ValueNbtCodec.encode(new DataValue.BooleanValue(true), LIMITS);
		bool.getList("Values", Tag.TAG_COMPOUND).getCompound(0).putByte("Value", (byte) 2);
		assertThrows(ArchiveException.class, () -> ValueNbtCodec.decode(bool, LIMITS));
	}

	@Test void longUnicodeTextAndDataKeysSurviveNbtStringSizeRestrictions() throws IOException {
		String longText = "温".repeat(30_000);
		var value = new DataValue.RecordValue(Map.of(longText, new DataValue.TextValue(longText + "\uD800")));
		var bytes = new ByteArrayOutputStream();
		NbtIo.write(ValueNbtCodec.encode(value, LIMITS), new DataOutputStream(bytes));
		assertEquals(value, ValueNbtCodec.decode(NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))), LIMITS));
	}
}
