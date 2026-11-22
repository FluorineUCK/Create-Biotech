package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternPageKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternJsonParser;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.ParseResult;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternDataCodecs;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

class PatternSourceDataTest {

	private static final String RAW = "{\"version\":2,\"type\":\"standard\",\"inputs\":[],\"outputs\":[{\"item\":\"minecraft:paper\",\"count\":1}],\"address\":\"press\"}";
	private static final String FINGERPRINT = "91c4b1108a2cb34ed61e8474ebaf745e80452fbcaabc551a8e4ee506baa9b07e";
	private static final UUID ID = UUID.fromString("7af8fe15-132e-35e3-af86-8275d6698d6d");
	private static final UUID SUB = UUID.fromString("00000000-0000-0000-0000-000000000123");

	@BeforeAll
	static void bootstrap() throws ReflectiveOperationException {
		Class<?> loadingModList = Class.forName("net.neoforged.fml.loading.LoadingModList");
		if (loadingModList.getMethod("get").invoke(null) == null)
			loadingModList.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
				.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
		net.minecraft.SharedConstants.tryDetectVersion();
		var flag = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
		flag.setAccessible(true); flag.setBoolean(null, true);
		Items.AIR.getDefaultInstance();
	}

	@Test
	void completeRecordKeepsGoldenPageIdentityAndPersistedFormat() throws Exception {
		var key = source();
		var result = assertInstanceOf(ParseResult.Valid.class,
			new PatternJsonParser(RegistryAccess.EMPTY).parse(key, RAW));
		assertEquals(FINGERPRINT, result.fingerprint());
		assertEquals(ID, result.pattern().patternId());
		CompoundTag expected = TagParser.parseTag("""
			{Version:2,PatternId:[I;2063138325,321795555,-1350139275,-697725587],
			 Source:{Shelf:{Dimension:"minecraft:overworld",SubLevel:[I;0,0,0,291],Pos:3573412753472L},Slot:2b,Page:7b},
			 Inputs:[],Outputs:[{Stack:{Stack:{id:"minecraft:paper",count:1}},Count:1}],
			 Type:"standard",Body:{Address:"press"}}
			""");
		// UUID int-array literals are independent of the parser and NBT writer.
		assertEquals(ID, expected.getUUID("PatternId"));
		assertEquals(expected, PatternDataCodecs.saveRecord(result.pattern(), RegistryAccess.EMPTY));
		assertEquals(result.pattern(), PatternDataCodecs.loadRecord(expected, RegistryAccess.EMPTY).orElseThrow());
	}

	@Test
	void sourceLocationCopiesPositionsAndPreservesExistingSpatialNbtAcceptance() throws Exception {
		var mutable = new BlockPos.MutableBlockPos(12, 64, -9);
		var key = new PatternPageKey(new RecipeSourceLocation(Level.OVERWORLD, SUB, mutable), 2, 7);
		mutable.set(0, 0, 0);
		assertEquals(new BlockPos(12, 64, -9), key.shelf().localPos());
		CompoundTag encoded = PatternDataCodecs.savePageKey(key);
		encoded.getCompound("Shelf").putString("HistoricalExtra", "ignored");
		assertEquals(key, PatternDataCodecs.loadPageKey(encoded).orElseThrow());
		encoded.getCompound("Shelf").putString("Pos", "wrong type");
		assertTrue(PatternDataCodecs.loadPageKey(encoded).isEmpty());
	}

	@Test
	void legacyFullRecordRetainsShelfIdentityWithoutChangingUnrelatedWorldAddresses() {
		var record = assertInstanceOf(ParseResult.Valid.class,
			new PatternJsonParser(RegistryAccess.EMPTY).parse(source(), RAW)).pattern();
		CompoundTag saved = CurrentSchemaStateNbt.save(record, RegistryAccess.EMPTY);
		assertEquals("com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternRecord", saved.getString("Type"));
		assertEquals("com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternPageKey",
			saved.getCompound("Value").getCompound("source").getString("Type"));
		assertEquals("com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress",
			saved.getCompound("Value").getCompound("source").getCompound("shelf").getString("Type"));
		assertEquals(record, CurrentSchemaStateNbt.load(saved, RegistryAccess.EMPTY, PatternRecord.class).orElseThrow());
		SpaceAddress address = new SpaceAddress(Level.OVERWORLD, SUB, new BlockPos(12, 64, -9));
		assertEquals(address, CurrentSchemaStateNbt.load(CurrentSchemaStateNbt.save(address, RegistryAccess.EMPTY),
			RegistryAccess.EMPTY, SpaceAddress.class).orElseThrow());
	}

	@Test
	void allParserOutcomesAndInspectionSnapshotsRetainHistoricalSchemaNames() {
		var parser = new PatternJsonParser(RegistryAccess.EMPTY);
		for (String raw : List.of("", RAW, "{", RAW.replace("standard", "unknown_kind"))) {
			var inspection = parser.inspect(source(), raw, null);
			Object result = inspection.changedResult().orElseThrow();
			CompoundTag saved = CurrentSchemaStateNbt.save(result, RegistryAccess.EMPTY);
			assertEquals("com.nobodiiiii.createbiotech.content.factorycluster.pattern.ParseResult$"
				+ result.getClass().getSimpleName(), saved.getString("Type"));
			assertEquals(result, CurrentSchemaStateNbt.load(saved, RegistryAccess.EMPTY,
				result.getClass()).orElseThrow());
			CompoundTag savedInspection = CurrentSchemaStateNbt.save(inspection, RegistryAccess.EMPTY);
			assertEquals("com.nobodiiiii.createbiotech.content.factorycluster.pattern.PageInspection",
				savedInspection.getString("Type"));
			assertEquals(inspection, CurrentSchemaStateNbt.load(savedInspection, RegistryAccess.EMPTY,
				inspection.getClass()).orElseThrow());
		}
		assertTrue(parser.inspect(source(), RAW, FINGERPRINT).changedResult().isEmpty());
	}

	@Test
	void historicalAddressDecodesAccordingToItsExactFieldTypeAndRejectsNewAliases() {
		RecipeSourceLocation location = source().shelf();
		SpaceAddress address = new SpaceAddress(location.dimension(), location.subLevelId(), location.localPos());
		CompoundTag old = CurrentSchemaStateNbt.save(address, RegistryAccess.EMPTY);
		assertEquals(old, CurrentSchemaStateNbt.save(location, RegistryAccess.EMPTY));
		assertEquals(location, CurrentSchemaStateNbt.load(old, RegistryAccess.EMPTY,
			RecipeSourceLocation.class).orElseThrow());
		assertEquals(address, CurrentSchemaStateNbt.load(old, RegistryAccess.EMPTY,
			SpaceAddress.class).orElseThrow());
		CompoundTag alternateRoot = old.copy();
		alternateRoot.putString("Type", RecipeSourceLocation.class.getName());
		assertTrue(CurrentSchemaStateNbt.load(alternateRoot, RegistryAccess.EMPTY, RecipeSourceLocation.class).isEmpty());
		CompoundTag alternateValue = old.copy();
		alternateValue.getCompound("Value").putString("Type", RecipeSourceLocation.class.getName());
		assertTrue(CurrentSchemaStateNbt.load(alternateValue, RegistryAccess.EMPTY, RecipeSourceLocation.class).isEmpty());
		assertTrue(CurrentSchemaStateNbt.load(alternateValue, RegistryAccess.EMPTY, SpaceAddress.class).isEmpty());
		var record = assertInstanceOf(ParseResult.Valid.class,
			new PatternJsonParser(RegistryAccess.EMPTY).parse(source(), RAW)).pattern();
		CompoundTag saved = CurrentSchemaStateNbt.save(record, RegistryAccess.EMPTY);
		saved.getCompound("Value").getCompound("source").getCompound("shelf")
			.putString("Type", RecipeSourceLocation.class.getName());
		assertTrue(CurrentSchemaStateNbt.load(saved, RegistryAccess.EMPTY, PatternRecord.class).isEmpty());
	}

	private static PatternPageKey source() {
		return new PatternPageKey(new RecipeSourceLocation(Level.OVERWORLD, SUB, new BlockPos(12, 64, -9)), 2, 7);
	}
}
