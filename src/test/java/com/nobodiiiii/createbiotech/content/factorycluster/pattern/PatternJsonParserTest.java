package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

class PatternJsonParserTest {
	private static final PatternPageKey PAGE = new PatternPageKey(
		new SpaceAddress(Level.OVERWORLD, UUID.fromString("00000000-0000-0000-0000-000000000001"),
			new BlockPos(12, 34, 56)), 2, 7);
	private static final String VALID_JSON = """
		{"v":1,"in":[{"item":"minecraft:iron_nugget","count":9}],
		 "out":[{"item":"minecraft:iron_ingot","count":1}],"to":"iron_processing"}
		""";

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		try {
			java.lang.reflect.Field bootstrapped =
				net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			bootstrapped.setAccessible(true);
			bootstrapped.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
	private static void installEmptyLoadingModList() {
		try {
			Class<?> loadingModList = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (loadingModList.getMethod("get").invoke(null) != null)
				return;
			Object empty = loadingModList.getMethod("of", List.class, List.class, List.class,
				List.class, java.util.Map.class).invoke(null, List.of(), List.of(), List.of(),
					List.of(), java.util.Map.of());
			if (empty == null)
				throw new IllegalStateException("LoadingModList.of returned null");
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
	private final RegistryAccess registries = RegistryAccess.EMPTY;
	private final PatternJsonParser parser = new PatternJsonParser(registries);

	@Test
	void parsesVersionOneItemPattern() {
		ParseResult result = parser.parse(PAGE, """
			{"v":1,"in":[{"item":"minecraft:iron_nugget","count":9}],
			 "out":[{"item":"minecraft:iron_ingot","count":1}],"to":"iron_processing"}
			""");
		PatternRecord pattern = assertInstanceOf(ParseResult.Valid.class, result).pattern();
		assertEquals(9, pattern.inputs().getFirst().count());
		assertEquals(Items.IRON_INGOT, pattern.mainOutput().stack().stack().getItem());
		assertEquals("iron_processing", pattern.recipeAddress());
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"{}",
		"{\"v\":2,\"in\":[],\"out\":[],\"to\":\"x\"}",
		"{\"v\":1,\"in\":[{\"item\":\"minecraft:stone\",\"tag\":\"minecraft:stone_crafting_materials\",\"count\":1}],\"out\":[{\"item\":\"minecraft:dirt\",\"count\":1}],\"to\":\"x\"}",
		"{\"v\":1,\"in\":[],\"out\":[{\"item\":\"minecraft:dirt\",\"count\":0}],\"to\":\"x\"}"
	})
	void invalidPageReturnsLocalError(String json) {
		assertInstanceOf(ParseResult.Invalid.class, parser.parse(PAGE, json));
	}

	@Test
	void everyParseOutcomeCarriesTheSameNormalizedRawFingerprint() {
		String blankFingerprint = sha256LowerHex("   ");
		assertEquals(blankFingerprint,
			assertInstanceOf(ParseResult.Blank.class, parser.parse(PAGE, "   ")).fingerprint());
		assertEquals(sha256LowerHex(VALID_JSON),
			assertInstanceOf(ParseResult.Valid.class, parser.parse(PAGE, VALID_JSON)).fingerprint());
		assertEquals(sha256LowerHex("{"),
			assertInstanceOf(ParseResult.Invalid.class, parser.parse(PAGE, "{")).fingerprint());
	}

	@Test
	void stackKeyAndRecordAccessorsCannotMutateStoredIdentity() {
		ItemStack source = new ItemStack(Items.PAPER, 4);
		source.set(DataComponents.MAX_STACK_SIZE, 1);
		StackKey key = new StackKey(source);
		ItemStack obtained = key.stack();
		obtained.set(DataComponents.MAX_STACK_SIZE, 2);
		assertEquals(1, key.stack().get(DataComponents.MAX_STACK_SIZE));
		assertEquals(new StackKey(source), key);
	}

	@Test
	void allPersistedValuesRoundTripComponentsAndRejectWrongNbtTypes() {
		PatternRecord record = componentSensitivePattern();
		assertEquals(record, PatternValueCodecs.loadRecord(
			PatternValueCodecs.saveRecord(record, registries), registries).orElseThrow());
		CompoundTag corrupt = PatternValueCodecs.saveRecord(record, registries);
		corrupt.putString("Inputs", "not-a-list");
		assertTrue(PatternValueCodecs.loadRecord(corrupt, registries).isEmpty());
	}

	@Test
	void persistedValueCodecsRoundTripEveryPublicValue() {
		StackKey stack = new StackKey(new ItemStack(Items.PAPER, 1));
		PatternIngredient itemIngredient = new PatternIngredient(
			ResourceLocation.withDefaultNamespace("iron_nugget"), null, 9, DataComponentPredicate.EMPTY);
		PatternOutput output = new PatternOutput(stack, 3);
		PatternRecord record = componentSensitivePattern();
		PatternPageError error = new PatternPageError(PAGE, PatternErrorReason.COUNT, "bad count");
		PatternQuery query = new PatternQuery(UUID.fromString("00000000-0000-0000-0000-000000000003"),
			UUID.fromString("00000000-0000-0000-0000-000000000004"),
			UUID.fromString("00000000-0000-0000-0000-000000000005"), stack, 7, 0);
		PatternReply match = new PatternReply(query.queryId(), query.requesterComputerId(), query.logisticsId(),
			7, PatternReplyStatus.MATCH, record);
		PatternReply notFound = new PatternReply(query.queryId(), query.requesterComputerId(), query.logisticsId(),
			7, PatternReplyStatus.NOT_FOUND, null);

		assertEquals(stack, PatternValueCodecs.loadStackKey(PatternValueCodecs.saveStackKey(stack, registries), registries).orElseThrow());
		assertEquals(itemIngredient, PatternValueCodecs.loadIngredient(PatternValueCodecs.saveIngredient(itemIngredient, registries), registries).orElseThrow());
		assertEquals(output, PatternValueCodecs.loadOutput(PatternValueCodecs.saveOutput(output, registries), registries).orElseThrow());
		assertEquals(PAGE, PatternValueCodecs.loadPageKey(PatternValueCodecs.savePageKey(PAGE)).orElseThrow());
		assertEquals(error, PatternValueCodecs.loadPageError(PatternValueCodecs.savePageError(error)).orElseThrow());
		assertEquals(record, PatternValueCodecs.loadRecord(PatternValueCodecs.saveRecord(record, registries), registries).orElseThrow());
		assertEquals(query, PatternValueCodecs.loadQuery(PatternValueCodecs.saveQuery(query, registries), registries).orElseThrow());
		assertEquals(match, PatternValueCodecs.loadReply(PatternValueCodecs.saveReply(match, registries), registries).orElseThrow());
		assertEquals(notFound, PatternValueCodecs.loadReply(PatternValueCodecs.saveReply(notFound, registries), registries).orElseThrow());
	}

	@Test
	void ingredientCodecRejectsUnknownSelectorsAndNonCanonicalSelectorStates() {
		PatternIngredient item = new PatternIngredient(ResourceLocation.withDefaultNamespace("iron_nugget"), null,
			1, DataComponentPredicate.EMPTY);
		PatternIngredient tag = new PatternIngredient(null, ItemTags.PLANKS.location(), 1,
			DataComponentPredicate.EMPTY);
		CompoundTag unknownItem = PatternValueCodecs.saveIngredient(item, registries);
		unknownItem.putString("Item", "minecraft:unknown_pattern_item");
		CompoundTag unknownTag = PatternValueCodecs.saveIngredient(tag, registries);
		unknownTag.putString("Tag", "minecraft:unknown_pattern_tag");
		CompoundTag bothSelectors = PatternValueCodecs.saveIngredient(item, registries);
		bothSelectors.putString("Tag", ItemTags.PLANKS.location().toString());
		CompoundTag missingSelector = PatternValueCodecs.saveIngredient(item, registries);
		missingSelector.remove("Item");
		CompoundTag wrongCountType = PatternValueCodecs.saveIngredient(item, registries);
		wrongCountType.putString("Count", "1");
		CompoundTag extraMember = PatternValueCodecs.saveIngredient(item, registries);
		extraMember.putString("Extra", "no");

		assertTrue(PatternValueCodecs.loadIngredient(unknownItem, registries).isEmpty());
		assertTrue(PatternValueCodecs.loadIngredient(unknownTag, registries).isEmpty());
		assertTrue(PatternValueCodecs.loadIngredient(bothSelectors, registries).isEmpty());
		assertTrue(PatternValueCodecs.loadIngredient(missingSelector, registries).isEmpty());
		assertTrue(PatternValueCodecs.loadIngredient(wrongCountType, registries).isEmpty());
		assertTrue(PatternValueCodecs.loadIngredient(extraMember, registries).isEmpty());
	}

	@Test
	void persistedCodecsRejectRepresentativeWrongTypesMissingFieldsExtrasAndInvariants() {
		StackKey stack = new StackKey(new ItemStack(Items.PAPER, 1));
		PatternOutput output = new PatternOutput(stack, 1);
		PatternQuery query = new PatternQuery(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), stack, 1, 0);
		PatternReply found = new PatternReply(query.queryId(), query.requesterComputerId(), query.logisticsId(),
			1, PatternReplyStatus.NOT_FOUND, null);
		CompoundTag missingStack = PatternValueCodecs.saveStackKey(stack, registries);
		missingStack.remove("Stack");
		CompoundTag wrongOutput = PatternValueCodecs.saveOutput(output, registries);
		wrongOutput.putString("Count", "1");
		CompoundTag extraPage = PatternValueCodecs.savePageKey(PAGE);
		extraPage.putString("Extra", "no");
		CompoundTag invalidError = PatternValueCodecs.savePageError(new PatternPageError(PAGE, PatternErrorReason.JSON, "x"));
		invalidError.putString("Reason", "NOPE");
		CompoundTag defaultedCursor = PatternValueCodecs.saveQuery(query, registries);
		defaultedCursor.putInt("Cursor", -1);
		CompoundTag replyWithUnexpectedPattern = PatternValueCodecs.saveReply(found, registries);
		replyWithUnexpectedPattern.put("Pattern", PatternValueCodecs.saveRecord(componentSensitivePattern(), registries));
		CompoundTag replyWithoutMatch = PatternValueCodecs.saveReply(new PatternReply(query.queryId(),
			query.requesterComputerId(), query.logisticsId(), 1, PatternReplyStatus.MATCH,
			componentSensitivePattern()), registries);
		replyWithoutMatch.remove("Pattern");

		assertTrue(PatternValueCodecs.loadStackKey(missingStack, registries).isEmpty());
		assertTrue(PatternValueCodecs.loadOutput(wrongOutput, registries).isEmpty());
		assertTrue(PatternValueCodecs.loadPageKey(extraPage).isEmpty());
		assertTrue(PatternValueCodecs.loadPageError(invalidError).isEmpty());
		assertTrue(PatternValueCodecs.loadQuery(defaultedCursor, registries).isEmpty());
		assertTrue(PatternValueCodecs.loadReply(replyWithUnexpectedPattern, registries).isEmpty());
		assertTrue(PatternValueCodecs.loadReply(replyWithoutMatch, registries).isEmpty());
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"{\"v\":1,\"in\":[],\"out\":[],\"to\":\"x\",\"extra\":true}",
		"{\"v\":1,\"in\":[{\"item\":\"minecraft:stone\",\"count\":1,\"extra\":true}],\"out\":[{\"item\":\"minecraft:dirt\",\"count\":1}],\"to\":\"x\"}",
		"{\"v\":1,\"in\":[{\"count\":1}],\"out\":[{\"item\":\"minecraft:dirt\",\"count\":1}],\"to\":\"x\"}",
		"{\"v\":1,\"in\":[{\"item\":\"minecraft:unknown_pattern_item\",\"count\":1}],\"out\":[{\"item\":\"minecraft:dirt\",\"count\":1}],\"to\":\"x\"}",
		"{\"v\":1,\"in\":[{\"tag\":\"minecraft:unknown_pattern_tag\",\"count\":1}],\"out\":[{\"item\":\"minecraft:dirt\",\"count\":1}],\"to\":\"x\"}",
		"{\"v\":1,\"in\":[{\"item\":\"minecraft:stone\",\"count\":1000000001}],\"out\":[{\"item\":\"minecraft:dirt\",\"count\":1}],\"to\":\"x\"}",
		"{\"v\":1,\"in\":[{\"item\":\"minecraft:stone\",\"count\":1,\"components\":{\"minecraft:unknown_component\":1}}],\"out\":[{\"item\":\"minecraft:dirt\",\"count\":1}],\"to\":\"x\"}",
		"{\"v\":1,\"in\":[{\"item\":\"minecraft:stone\",\"count\":1}],\"out\":[{\"item\":\"minecraft:dirt\",\"count\":1,\"extra\":true}],\"to\":\"x\"}",
		"{\"v\":1,\"in\":[{\"item\":\"minecraft:stone\",\"count\":1}],\"out\":[{\"item\":\"minecraft:unknown_pattern_item\",\"count\":1}],\"to\":\"x\"}",
		"{\"v\":1,\"in\":[{\"item\":\"minecraft:stone\",\"count\":1}],\"out\":[{\"item\":\"minecraft:dirt\",\"count\":1000000001}],\"to\":\"x\"}",
		"{\"v\":1,\"in\":[{\"item\":\"minecraft:stone\",\"count\":1}],\"out\":[{\"item\":\"minecraft:dirt\",\"count\":1,\"components\":{\"minecraft:max_stack_size\":0}}],\"to\":\"x\"}"
	})
	void parserRejectsAdversarialSchemaAndValueStates(String json) {
		assertInstanceOf(ParseResult.Invalid.class, parser.parse(PAGE, json));
	}

	@Test
	void inspectReturnsNoChangedResultForTheCachedRawFingerprint() {
		PageInspection inspection = parser.inspect(PAGE, VALID_JSON, sha256LowerHex(VALID_JSON));
		assertEquals(sha256LowerHex(VALID_JSON), inspection.fingerprint());
		assertTrue(inspection.changedResult().isEmpty());
	}

	@Test
	void zeroInputRecordAndMatchReplyRoundTripThroughPersistedCodecs() {
		PatternRecord zeroInput = new PatternRecord(
			UUID.fromString("00000000-0000-0000-0000-000000000006"), PAGE, List.of(),
			List.of(new PatternOutput(new StackKey(new ItemStack(Items.IRON_INGOT, 1)), 1)), "no_inputs");
		PatternQuery query = new PatternQuery(
			UUID.fromString("00000000-0000-0000-0000-000000000007"),
			UUID.fromString("00000000-0000-0000-0000-000000000008"),
			UUID.fromString("00000000-0000-0000-0000-000000000009"),
			zeroInput.mainOutput().stack(), 4, 0);
		PatternReply reply = new PatternReply(query.queryId(), query.requesterComputerId(), query.logisticsId(),
			4, PatternReplyStatus.MATCH, zeroInput);

		assertEquals(zeroInput, PatternValueCodecs.loadRecord(
			PatternValueCodecs.saveRecord(zeroInput, registries), registries).orElseThrow());
		assertEquals(reply, PatternValueCodecs.loadReply(
			PatternValueCodecs.saveReply(reply, registries), registries).orElseThrow());
	}

	@Test
	void canonicalPatternIdSurvivesPageKeyRoundTripAndChangesWithIdentityInputs() {
		PatternRecord first = assertInstanceOf(ParseResult.Valid.class,
			parser.parse(PAGE, VALID_JSON)).pattern();
		PatternPageKey restoredPage = PatternValueCodecs.loadPageKey(
			PatternValueCodecs.savePageKey(PAGE)).orElseThrow();
		PatternRecord restored = assertInstanceOf(ParseResult.Valid.class,
			parser.parse(restoredPage, VALID_JSON)).pattern();

		assertEquals(first.patternId(), restored.patternId());
		assertIdDiffers(first, new PatternPageKey(new SpaceAddress(Level.NETHER,
			PAGE.shelf().subLevelId(), PAGE.shelf().localPos()), PAGE.slot(), PAGE.page()));
		assertIdDiffers(first, new PatternPageKey(new SpaceAddress(PAGE.shelf().dimension(), null,
			PAGE.shelf().localPos()), PAGE.slot(), PAGE.page()));
		assertIdDiffers(first, new PatternPageKey(new SpaceAddress(PAGE.shelf().dimension(),
			PAGE.shelf().subLevelId(), PAGE.shelf().localPos().offset(1, 0, 0)), PAGE.slot(), PAGE.page()));
		assertIdDiffers(first, new PatternPageKey(new SpaceAddress(PAGE.shelf().dimension(),
			PAGE.shelf().subLevelId(), PAGE.shelf().localPos().offset(0, 1, 0)), PAGE.slot(), PAGE.page()));
		assertIdDiffers(first, new PatternPageKey(new SpaceAddress(PAGE.shelf().dimension(),
			PAGE.shelf().subLevelId(), PAGE.shelf().localPos().offset(0, 0, 1)), PAGE.slot(), PAGE.page()));
		assertIdDiffers(first, new PatternPageKey(PAGE.shelf(), 3, PAGE.page()));
		assertIdDiffers(first, new PatternPageKey(PAGE.shelf(), PAGE.slot(), 8));
		assertNotEquals(first.patternId(), assertInstanceOf(ParseResult.Valid.class,
			parser.parse(PAGE, VALID_JSON + " ")).pattern().patternId());
	}

	private void assertIdDiffers(PatternRecord first, PatternPageKey changedSource) {
		PatternRecord changed = assertInstanceOf(ParseResult.Valid.class,
			parser.parse(changedSource, VALID_JSON)).pattern();
		assertNotEquals(first.patternId(), changed.patternId());
	}
	private static PatternRecord componentSensitivePattern() {
		ItemStack output = new ItemStack(Items.PAPER, 1);
		output.set(DataComponents.MAX_STACK_SIZE, 1);
		return new PatternRecord(UUID.fromString("00000000-0000-0000-0000-000000000002"), PAGE,
			List.of(new PatternIngredient(ResourceLocation.withDefaultNamespace("iron_nugget"), null,
				9, DataComponentPredicate.EMPTY)),
			List.of(new PatternOutput(new StackKey(output), 1)), "component_test");
	}

	private static String sha256LowerHex(String raw) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(raw.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new AssertionError(exception);
		}
	}
}
