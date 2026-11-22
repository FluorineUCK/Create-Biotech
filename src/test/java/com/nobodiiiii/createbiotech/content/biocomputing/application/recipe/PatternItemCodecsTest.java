package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.simibubi.create.content.logistics.BigItemStack;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

class PatternItemCodecsTest {

	@BeforeAll
	static void bootstrapMinecraftRegistries() throws ReflectiveOperationException {
		Class<?> loadingModList = Class.forName("net.neoforged.fml.loading.LoadingModList");
		if (loadingModList.getMethod("get").invoke(null) == null)
			loadingModList.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
				.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
		net.minecraft.SharedConstants.tryDetectVersion();
		var bootstrapped = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
		bootstrapped.setAccessible(true);
		bootstrapped.setBoolean(null, true);
		Items.AIR.getDefaultInstance();
	}

	@Test
	void persistedStackIdentityKeepsTheExistingCanonicalComponentShape() throws Exception {
		ItemStack source = new ItemStack(Items.PAPER, 4);
		source.set(DataComponents.MAX_STACK_SIZE, 16);
		StackKey key = new StackKey(source);
		var expected = TagParser.parseTag("""
			{Stack:{id:"minecraft:paper",count:1,components:{"minecraft:max_stack_size":16}}}
			""");

		assertEquals(expected, PatternItemCodecs.saveStackKey(key, RegistryAccess.EMPTY));
		assertEquals(key, PatternItemCodecs.loadStackKey(expected, RegistryAccess.EMPTY).orElseThrow());
		assertEquals(4, source.getCount());
	}

	@Test
	void ingredientAndOutputKeepTheirDifferentPersistedQuantityFields() throws Exception {
		var ingredient = new PatternIngredient(ResourceLocation.withDefaultNamespace("paper"), null, 9,
			DataComponentPredicate.EMPTY);
		var output = new PatternOutput(new StackKey(new ItemStack(Items.PAPER)), 4);
		var inputTag = TagParser.parseTag("{Item:\"minecraft:paper\",Count:9,Components:{}}");
		var outputTag = TagParser.parseTag("{Stack:{Stack:{id:\"minecraft:paper\",count:1}},Count:4}");

		assertEquals(inputTag, PatternItemCodecs.saveIngredient(ingredient, RegistryAccess.EMPTY));
		assertEquals(ingredient, PatternItemCodecs.loadIngredient(inputTag, RegistryAccess.EMPTY).orElseThrow());
		assertEquals(outputTag, PatternItemCodecs.saveOutput(output, RegistryAccess.EMPTY));
		assertEquals(output, PatternItemCodecs.loadOutput(outputTag, RegistryAccess.EMPTY).orElseThrow());
	}

	@Test
	void sourceAndReturnedStacksCannotMutateStoredIdentityOrItsHash() {
		ItemStack source = new ItemStack(Items.PAPER, 4);
		source.set(DataComponents.MAX_STACK_SIZE, 16);
		StackKey key = new StackKey(source);
		int hash = key.hashCode();
		var output = new PatternOutput(key, 9);
		source.setCount(20);
		source.set(DataComponents.MAX_STACK_SIZE, 32);
		ItemStack returned = output.stack().stack();
		returned.setCount(7);
		returned.set(DataComponents.MAX_STACK_SIZE, 1);

		assertEquals(1, key.stack().getCount());
		assertEquals(16, key.stack().get(DataComponents.MAX_STACK_SIZE));
		assertEquals(hash, key.hashCode());
		assertEquals(9, output.count());
		assertEquals(20, source.getCount());
		assertEquals(32, source.get(DataComponents.MAX_STACK_SIZE));
	}

	@Test
	void componentSensitiveSelectorReadsButDoesNotModifyAnItem() {
		var predicate = DataComponentPredicate.builder().expect(DataComponents.MAX_STACK_SIZE, 16).build();
		var ingredient = new PatternIngredient(ResourceLocation.withDefaultNamespace("paper"), null, 9, predicate);
		ItemStack source = new ItemStack(Items.PAPER, 4);
		source.set(DataComponents.MAX_STACK_SIZE, 16);

		assertTrue(ingredient.matches(source));
		assertFalse(ingredient.matches(new ItemStack(Items.PAPER)));
		assertFalse(ingredient.matches(new ItemStack(Items.STONE)));
		assertEquals(4, source.getCount());
		assertEquals(16, source.get(DataComponents.MAX_STACK_SIZE));
	}

	@Test
	void malformedOrNoncanonicalStackPayloadIsNotSilentlyNormalized() throws Exception {
		var valid = TagParser.parseTag("{Stack:{id:\"minecraft:paper\",count:1}}");
		var extra = valid.copy();
		extra.putString("Unexpected", "ignored data");
		assertTrue(PatternItemCodecs.loadStackKey(extra, RegistryAccess.EMPTY).isEmpty());
		var multiple = valid.copy();
		multiple.getCompound("Stack").putInt("count", 2);
		assertTrue(PatternItemCodecs.loadStackKey(multiple, RegistryAccess.EMPTY).isEmpty());
		var noncanonical = valid.copy();
		noncanonical.getCompound("Stack").remove("count");
		assertTrue(PatternItemCodecs.loadStackKey(noncanonical, RegistryAccess.EMPTY).isEmpty());
		var unknown = valid.copy();
		unknown.getCompound("Stack").putString("id", "create_biotech:missing_test_item");
		assertTrue(PatternItemCodecs.loadStackKey(unknown, RegistryAccess.EMPTY).isEmpty());
	}

	@Test
	void quantitiesAndSelectorsKeepTheirExistingValidationBoundaries() throws Exception {
		var input = TagParser.parseTag("{Item:\"minecraft:paper\",Count:9,Components:{}}");
		var both = input.copy();
		both.putString("Tag", "minecraft:planks");
		assertTrue(PatternItemCodecs.loadIngredient(both, RegistryAccess.EMPTY).isEmpty());
		var wrongType = input.copy();
		wrongType.putByte("Count", (byte) 9);
		assertTrue(PatternItemCodecs.loadIngredient(wrongType, RegistryAccess.EMPTY).isEmpty());
		var missingTag = TagParser.parseTag("{Tag:\"create_biotech:missing_test_tag\",Count:1,Components:{}}");
		assertTrue(PatternItemCodecs.loadIngredient(missingTag, RegistryAccess.EMPTY).isEmpty());
		for (int invalid : new int[] { 0, -1, BigItemStack.INF + 1 }) {
			var tag = input.copy();
			tag.putInt("Count", invalid);
			assertTrue(PatternItemCodecs.loadIngredient(tag, RegistryAccess.EMPTY).isEmpty());
			assertThrows(IllegalArgumentException.class,
				() -> new PatternOutput(new StackKey(new ItemStack(Items.PAPER)), invalid));
		}
		var maximum = new PatternOutput(new StackKey(new ItemStack(Items.PAPER)), BigItemStack.INF);
		assertEquals(maximum, PatternItemCodecs.loadOutput(
			PatternItemCodecs.saveOutput(maximum, RegistryAccess.EMPTY), RegistryAccess.EMPTY).orElseThrow());
	}

	@Test
	void decodingAndChangingAResultNeverMutatesTheInputCompound() throws Exception {
		var input = TagParser.parseTag("""
			{Stack:{Stack:{id:"minecraft:paper",count:1,components:{"minecraft:max_stack_size":16}}},Count:9}
			""");
		var original = input.copy();
		var decoded = PatternItemCodecs.loadOutput(input, RegistryAccess.EMPTY).orElseThrow();
		ItemStack result = decoded.stack().stack();
		result.setCount(4);
		result.set(DataComponents.MAX_STACK_SIZE, 1);
		assertEquals(original, input);
		input.putInt("Count", 3);
		input.getCompound("Stack").getCompound("Stack").getCompound("components")
			.putInt("minecraft:max_stack_size", 2);
		assertEquals(9, decoded.count());
		assertEquals(16, decoded.stack().stack().get(DataComponents.MAX_STACK_SIZE));
		assertEquals(original, PatternItemCodecs.saveOutput(decoded, RegistryAccess.EMPTY));
	}
}
