package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternEditData;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternStage;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.SequencedPatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StandardPatternBody;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt;
import com.simibubi.create.content.logistics.box.PackageItem;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;

class PatternContentTest {

	@BeforeAll
	static void bootstrap() throws ReflectiveOperationException {
		PatternItemCodecsTest.bootstrapMinecraftRegistries();
	}

	@Test
	void standardEditableContentKeepsVersionTwoJsonWithoutSourceIdentity() {
		var data = new PatternEditData(List.of(paper(2)), List.of(output()),
			new StandardPatternBody("  press  "), OptionalInt.of(-7));
		assertEquals(JsonParser.parseString("""
			{"version":2,"type":"standard","inputs":[{"item":"minecraft:paper","count":2}],
			 "outputs":[{"item":"minecraft:paper","count":1}],"address":"press","priority":-7}
			"""), JsonParser.parseString(data.toJson(RegistryAccess.EMPTY)));
	}

	@Test
	void sequencedEditableContentCopiesListsAndRequiresContiguousStages() {
		var inputs = new ArrayList<>(List.of(paper(3)));
		var first = new PatternStage(0, "  press  ", inputs);
		var stages = new ArrayList<>(List.of(first, new PatternStage(1, "pack", List.of())));
		var body = new SequencedPatternBody(stages);
		var outputs = new ArrayList<>(List.of(output()));
		var data = new PatternEditData(List.of(), outputs, body, OptionalInt.empty());
		inputs.clear(); stages.clear(); outputs.clear();
		assertEquals(JsonParser.parseString("""
			{"version":2,"type":"sequenced","inputs":[],
			 "outputs":[{"item":"minecraft:paper","count":1}],
			 "stages":[{"address":"press","inputs":[{"item":"minecraft:paper","count":3}]},
			           {"address":"pack","inputs":[]}]}
			"""), JsonParser.parseString(data.toJson(RegistryAccess.EMPTY)));
		assertThrows(UnsupportedOperationException.class, () -> body.stages().clear());
		assertThrows(UnsupportedOperationException.class, () -> first.inputs().clear());
		assertThrows(IllegalArgumentException.class, () -> new SequencedPatternBody(List.of()));
		assertThrows(IllegalArgumentException.class,
			() -> new SequencedPatternBody(List.of(new PatternStage(1, "pack", List.of()))));
	}

	@Test
	void historicalSchemaKeepsBodyStageEditableAndKindIdentities() {
		var body = new SequencedPatternBody(List.of(new PatternStage(0, "press", List.of(paper(2)))));
		var data = new PatternEditData(List.of(), List.of(output()), body, OptionalInt.of(4));
		for (Object value : List.of(new StandardPatternBody("pack"), body,
			body.stages().getFirst(), data, PatternKind.SEQUENCED)) {
			CompoundTag saved = CurrentSchemaStateNbt.save(value, RegistryAccess.EMPTY);
			assertEquals(6, saved.getInt("Version"));
			assertEquals("com.nobodiiiii.createbiotech.content.factorycluster.pattern."
				+ value.getClass().getSimpleName(), saved.getString("Type"));
			assertEquals(value, CurrentSchemaStateNbt.load(saved, RegistryAccess.EMPTY,
				value.getClass()).orElseThrow());
		}
		CompoundTag saved = CurrentSchemaStateNbt.save(data, RegistryAccess.EMPTY);
		CompoundTag encodedBody = saved.getCompound("Value").getCompound("body");
		assertEquals("com.nobodiiiii.createbiotech.content.factorycluster.pattern.SequencedPatternBody",
			encodedBody.getString("Type"));
		CompoundTag stage = ((ListTag) encodedBody.getCompound("stages").get("Values")).getCompound(0);
		assertEquals("com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternStage",
			stage.getString("Type"));
		CompoundTag kind = CurrentSchemaStateNbt.save(PatternKind.STANDARD, RegistryAccess.EMPTY);
		assertEquals("com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternKind",
			kind.getCompound("Value").getString("Type"));
	}

	@Test
	void addressAndKindRetainTheirCanonicalTextContract() {
		assertEquals("press", RecipeAddressRules.requireNormalized("  press  "));
		assertEquals("x".repeat(25), RecipeAddressRules.requireNormalized("x".repeat(25)));
		for (String invalid : List.of("", "  ", "x".repeat(26), "a\tb", "a\nb")) {
			assertThrows(IllegalArgumentException.class, () -> RecipeAddressRules.requireNormalized(invalid));
			assertThrows(IllegalArgumentException.class, () -> new StandardPatternBody(invalid));
			assertThrows(IllegalArgumentException.class, () -> new PatternStage(0, invalid, List.of()));
		}
		assertEquals("standard", PatternKind.STANDARD.serializedName());
		assertEquals(PatternKind.SEQUENCED, PatternKind.fromSerializedName("sequenced").orElseThrow());
		assertTrue(PatternKind.fromSerializedName("STANDARD").isEmpty());
		assertTrue(PatternKind.fromSerializedName("standard ").isEmpty());
	}

	@Test
	void firstStageSharesComponentSensitivePackageCapacityWithEnvelopeButLaterStagesDoNot() {
		var predicate = DataComponentPredicate.builder().expect(DataComponents.MAX_STACK_SIZE, 16).build();
		int capacity = PackageItem.SLOTS * 16;
		var common = new PatternIngredient(ResourceLocation.parse("minecraft:paper"), null, capacity - 1, predicate);
		var one = new PatternIngredient(common.itemId(), null, 1, predicate);
		var two = new PatternIngredient(common.itemId(), null, 2, predicate);
		var full = new PatternIngredient(common.itemId(), null, capacity, predicate);
		var fullLater = new PatternStage(1, "pack", List.of(full));
		assertDoesNotThrow(() -> new PatternEditData(List.of(common), List.of(output()),
			new SequencedPatternBody(List.of(new PatternStage(0, "press", List.of(one)), fullLater)),
			OptionalInt.empty()));
		assertThrows(IllegalArgumentException.class, () -> new PatternEditData(List.of(common), List.of(output()),
			new SequencedPatternBody(List.of(new PatternStage(0, "press", List.of(two)), fullLater)),
			OptionalInt.empty()));
		assertDoesNotThrow(() -> new PatternEditData(List.of(full), List.of(output()),
			new StandardPatternBody("press"), OptionalInt.empty()));
		assertThrows(IllegalArgumentException.class, () -> new PatternEditData(List.of(full, one), List.of(output()),
			new StandardPatternBody("press"), OptionalInt.empty()));
	}

	@Test
	void representationBudgetCountsTopLevelAndNestedEntriesTogether() {
		int maximum = WritableBookContent.PAGE_EDIT_LENGTH;
		var outputs = Collections.nCopies(maximum, output());
		assertDoesNotThrow(() -> new PatternEditData(List.of(), outputs,
			new StandardPatternBody("press"), OptionalInt.empty()));
		assertThrows(IllegalArgumentException.class, () -> new PatternEditData(List.of(paper(1)), outputs,
			new StandardPatternBody("press"), OptionalInt.empty()));
		var stages = IntStream.range(0, maximum)
			.mapToObj(index -> new PatternStage(index, "press", List.of())).toList();
		var body = new SequencedPatternBody(stages);
		assertThrows(IllegalArgumentException.class,
			() -> new PatternEditData(List.of(), List.of(output()), body, OptionalInt.empty()));
		assertThrows(IllegalArgumentException.class,
			() -> new PatternStage(0, "press", Collections.nCopies(maximum + 1, paper(1))));
	}

	@Test
	void newJavaTypeNamesCannotBecomeAlternateHistoricalRootOrNestedFormats() {
		var body = new SequencedPatternBody(List.of(new PatternStage(0, "press", List.of())));
		for (Object value : List.of(new StandardPatternBody("pack"), body, body.stages().getFirst(),
			new PatternEditData(List.of(), List.of(output()), body, OptionalInt.empty()), PatternKind.STANDARD)) {
			CompoundTag saved = CurrentSchemaStateNbt.save(value, RegistryAccess.EMPTY);
			CompoundTag altered = saved.copy();
			altered.putString("Type", value.getClass().getName());
			assertTrue(CurrentSchemaStateNbt.load(altered, RegistryAccess.EMPTY, value.getClass()).isEmpty());
			altered = saved.copy();
			altered.getCompound("Value").putString("Type", value.getClass().getName());
			assertTrue(CurrentSchemaStateNbt.load(altered, RegistryAccess.EMPTY, value.getClass()).isEmpty());
		}
		CompoundTag saved = CurrentSchemaStateNbt.save(body, RegistryAccess.EMPTY);
		((ListTag) saved.getCompound("Value").getCompound("stages").get("Values"))
			.getCompound(0).putString("Type", PatternStage.class.getName());
		assertTrue(CurrentSchemaStateNbt.load(saved, RegistryAccess.EMPTY, SequencedPatternBody.class).isEmpty());
	}

	private static PatternIngredient paper(int count) {
		return new PatternIngredient(ResourceLocation.parse("minecraft:paper"), null, count,
			DataComponentPredicate.EMPTY);
	}

	private static PatternOutput output() {
		return new PatternOutput(new StackKey(new ItemStack(Items.PAPER)), 1);
	}
}
