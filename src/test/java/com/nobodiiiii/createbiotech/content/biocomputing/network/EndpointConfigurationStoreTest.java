package com.nobodiiiii.createbiotech.content.biocomputing.network;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

class EndpointConfigurationStoreTest {
	@Test void normalNbtIoRoundTripRetainsLiteralNameAndRejectsInvalidSurrogateWithoutRewriting() throws IOException {
		var config = new EndpointConfiguration(" A§\u0000😀 ", true);
		var store = new EndpointConfigurationStore(); store.configure(config);
		var encoded = realRoundTrip(assertInstanceOf(CompoundTag.class, store.write()));
		var restored = new EndpointConfigurationStore(); restored.read(encoded);
		encoded.putString("Name", "external mutation");
		assertEquals(config, restored.configuration().orElseThrow());
		var bad = assertInstanceOf(CompoundTag.class, store.write()); bad.putString("Name", "\udc00");
		restored.read(bad); assertTrue(restored.configuration().isEmpty());
		assertTrue(restored.diagnostic().isPresent()); assertEquals(bad, restored.write());
	}
	@Test void absentConfigureDisableClearAndDefensiveCopies() {
		var store = new EndpointConfigurationStore();
		assertTrue(store.configuration().isEmpty()); assertNull(store.write());
		store.disable(); assertNull(store.write());
		store.configure(new EndpointConfiguration("literal", true));
		var first = assertInstanceOf(CompoundTag.class, store.write()); first.putString("Name", "mutated");
		assertEquals("literal", store.configuration().orElseThrow().name());
		store.disable(); assertEquals(new EndpointConfiguration("literal", false), store.configuration().orElseThrow());
		store.clear(); assertNull(store.write()); assertTrue(store.diagnostic().isEmpty());
	}
	@Test void unknownOrCorruptRawDataIsPreservedAndHidden() throws IOException {
		var raw = new CompoundTag(); raw.putInt("Version", 99); raw.putString("Future", "opaque");
		var store = new EndpointConfigurationStore(); store.read(realRoundTrip(raw));
		assertTrue(store.configuration().isEmpty()); assertTrue(store.diagnostic().isPresent());
		assertEquals(raw, store.write()); store.disable(); assertEquals(raw, store.write());
		store.configure(new EndpointConfiguration("replacement", true));
		assertEquals(new EndpointConfiguration("replacement", true), store.configuration().orElseThrow());
	}
	@Test void strictNbtRejectsMissingExtraWrongTypesAndInvalidBoolean() {
		for (CompoundTag raw : new CompoundTag[]{tag(false), tag(true), wrongType(), invalidBoolean()}) {
			var store = new EndpointConfigurationStore(); store.read(raw);
			assertTrue(store.configuration().isEmpty()); assertTrue(store.diagnostic().isPresent()); assertEquals(raw, store.write());
		}
	}
	private static CompoundTag tag(boolean extra) { var t=new CompoundTag();t.putInt("Version",1);t.putString("Name","x");if(extra){t.putBoolean("Enabled",true);t.putInt("Extra",1);}return t; }
	private static CompoundTag wrongType(){var t=new CompoundTag();t.putInt("Version",1);t.putInt("Name",1);t.putBoolean("Enabled",true);return t;}
	private static CompoundTag invalidBoolean(){var t=new CompoundTag();t.putInt("Version",1);t.putString("Name","x");t.putByte("Enabled",(byte)2);return t;}
	private static CompoundTag realRoundTrip(CompoundTag tag) throws IOException {var out=new ByteArrayOutputStream();NbtIo.write(tag,new DataOutputStream(out));return NbtIo.read(new DataInputStream(new ByteArrayInputStream(out.toByteArray())));}
}
