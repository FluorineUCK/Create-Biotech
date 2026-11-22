package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.PatternQuery;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.PatternReply;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.PatternSearchCodecs;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternPageKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternPageError;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternErrorReason;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.UnsupportedPatternEnvelope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternJsonParser;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.ParseResult;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PageInspection;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternDataCodecs;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRepresentationBounds;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StandardPatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.SequencedPatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternStage;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternOutput;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternItemCodecs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.simibubi.create.content.logistics.BigItemStack;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

class PatternJsonParserTest {
	private static final PatternPageKey PAGE = new PatternPageKey(
		new RecipeSourceLocation(Level.OVERWORLD, UUID.fromString("00000000-0000-0000-0000-000000000001"),
			new BlockPos(12, 34, 56)), 2, 7);
	private static final String VALID_JSON = """
		{"version":2,"type":"standard","inputs":[{"item":"minecraft:iron_nugget","count":9}],
		 "outputs":[{"item":"minecraft:iron_ingot","count":1}],"address":"iron_processing"}
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
		Items.AIR.getDefaultInstance();
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
	void parsesVersionTwoStandardEnvelopeAndNormalizesItsCreateAddress() {
		PatternRecord pattern = assertInstanceOf(ParseResult.Valid.class, parser.parse(PAGE, """
			{"version":2,"type":"standard","inputs":[{"item":"minecraft:iron_nugget","count":9}],
			 "outputs":[{"item":"minecraft:iron_ingot","count":1}],"address":"  sheet_press  "}
			""")).pattern();

		assertEquals("sheet_press", assertInstanceOf(StandardPatternBody.class, pattern.body()).address());
		assertEquals(OptionalInt.empty(), pattern.priorityOverride());
	}

	@Test
	void parsesTwoStageSequencedEnvelopeInDeclaredOrder() {
		PatternRecord pattern = assertInstanceOf(ParseResult.Valid.class, parser.parse(PAGE, """
			{"version":2,"type":"sequenced","inputs":[{"item":"minecraft:paper","count":1}],
			 "outputs":[{"item":"minecraft:book","count":1}],
			 "stages":[{"address":" deploy_cog ","inputs":[{"item":"minecraft:iron_nugget","count":1}]},
			           {"address":"deploy_large_cog","inputs":[{"item":"minecraft:gold_nugget","count":1}]}]}
			""")).pattern();

		SequencedPatternBody body = assertInstanceOf(SequencedPatternBody.class, pattern.body());
		assertEquals(List.of(0, 1), body.stages().stream().map(PatternStage::index).toList());
		assertEquals(List.of("deploy_cog", "deploy_large_cog"),
			body.stages().stream().map(PatternStage::address).toList());
		assertEquals(pattern, PatternDataCodecs.loadRecord(
			PatternDataCodecs.saveRecord(pattern, registries), registries).orElseThrow());
	}

	@Test
	void exactCompatibleOperationMustFitOneCreatePackage() {
		ParseResult.Invalid invalid = assertInstanceOf(ParseResult.Invalid.class, parser.parse(PAGE, """
			{"version":2,"type":"standard","inputs":[{"item":"minecraft:stone","count":577}],
			 "outputs":[{"item":"minecraft:dirt","count":1}],"address":"crusher"}
			"""));
		assertEquals(PatternErrorReason.PACKAGE_CAPACITY, invalid.error().reason());
		ParseResult.Invalid componentLimited = assertInstanceOf(ParseResult.Invalid.class,
			parser.parse(PAGE, """
				{"version":2,"type":"standard","inputs":[{"item":"minecraft:paper","count":10,
				 "components":{"minecraft:max_stack_size":1}}],
				 "outputs":[{"item":"minecraft:dirt","count":1}],"address":"printer"}
				"""));
		assertEquals(PatternErrorReason.PACKAGE_CAPACITY, componentLimited.error().reason());
	}

	@Test
	void sequencedFirstPackageCombinesEnvelopeInputsWithStageZeroInputs() {
		ParseResult.Invalid invalid = assertInstanceOf(ParseResult.Invalid.class, parser.parse(PAGE, """
			{"version":2,"type":"sequenced","inputs":[{"item":"minecraft:stone","count":576}],
			 "outputs":[{"item":"minecraft:paper","count":1}],
			 "stages":[{"address":"first","inputs":[{"item":"minecraft:dirt","count":1}]}]}
			"""));
		assertEquals(PatternErrorReason.PACKAGE_CAPACITY, invalid.error().reason());
	}

	@Test
	void invalidSequencedStageAddressReportsAddressReason() {
		ParseResult.Invalid invalid = assertInstanceOf(ParseResult.Invalid.class, parser.parse(PAGE, """
			{"version":2,"type":"sequenced","inputs":[],
			 "outputs":[{"item":"minecraft:paper","count":1}],
			 "stages":[{"address":"   ","inputs":[{"item":"minecraft:dirt","count":1}]}]}
			"""));
		assertEquals(PatternErrorReason.ADDRESS, invalid.error().reason());
	}

	@Test
	void sequencedStageRetainsCountAndComponentErrorReasons() {
		ParseResult.Invalid count = assertInstanceOf(ParseResult.Invalid.class, parser.parse(PAGE, """
			{"version":2,"type":"sequenced","inputs":[],
			 "outputs":[{"item":"minecraft:paper","count":1}],
			 "stages":[{"address":"stage","inputs":[{"item":"minecraft:dirt","count":0}]}]}
			"""));
		ParseResult.Invalid components = assertInstanceOf(ParseResult.Invalid.class, parser.parse(PAGE, """
			{"version":2,"type":"sequenced","inputs":[],
			 "outputs":[{"item":"minecraft:paper","count":1}],
			 "stages":[{"address":"stage","inputs":[{"item":"minecraft:dirt","count":1,
			 "components":{"minecraft:unknown_component":1}}]}]}
			"""));

		assertEquals(PatternErrorReason.COUNT, count.error().reason());
		assertEquals(PatternErrorReason.COMPONENTS, components.error().reason());
	}

	@Test
	void parsesOptionalPatternPriorityAsAnExactInteger() {
		PatternRecord pattern = assertInstanceOf(ParseResult.Valid.class, parser.parse(PAGE, """
			{"version":2,"type":"standard","inputs":[],
			 "outputs":[{"item":"minecraft:paper","count":1}],"address":"printer","priority":-4}
			""")).pattern();
		assertEquals(OptionalInt.of(-4), pattern.priorityOverride());
	}

	@Test
	void unsupportedTypePreservesBoundedEnvelopeWithoutStandardFallback() {
		String raw = """
			{"version":2,"type":"custom_loop","inputs":[{"item":"minecraft:stone","count":1}],
			 "outputs":[{"item":"minecraft:dirt","count":1}],"loop":{"future":true}}
			""";
		ParseResult.Unsupported result = assertInstanceOf(ParseResult.Unsupported.class,
			parser.parse(PAGE, raw));

		assertEquals(PatternErrorReason.UNSUPPORTED_PATTERN_TYPE, result.error().reason());
		assertEquals(2, result.envelope().version());
		assertEquals("custom_loop", result.envelope().type());
		assertEquals(PAGE, result.envelope().source());
		assertEquals(result.fingerprint(), result.envelope().fingerprint());
		assertEquals(raw, result.envelope().rawText());
		assertFalse(result.envelope().rawText().length() > UnsupportedPatternEnvelope.MAX_RAW_TEXT_LENGTH);
	}

	@Test
	void unsupportedRawTextIsBoundedWhileFingerprintStillCoversTheWholePage() {
		String raw = """
			{"version":2,"type":"future","inputs":[],
			 "outputs":[{"item":"minecraft:paper","count":1}]}
			""" + " ".repeat(UnsupportedPatternEnvelope.MAX_RAW_TEXT_LENGTH * 2);
		ParseResult.Unsupported result = assertInstanceOf(ParseResult.Unsupported.class,
			parser.parse(PAGE, raw));
		assertEquals(UnsupportedPatternEnvelope.MAX_RAW_TEXT_LENGTH,
			result.envelope().rawText().length());
		assertEquals(sha256LowerHex(raw), result.envelope().fingerprint());
	}

	@Test
	void newValueTypesDefensivelyCopyAndRejectNonContiguousStages() {
		List<PatternIngredient> mutable = new java.util.ArrayList<>();
		mutable.add(new PatternIngredient(ResourceLocation.withDefaultNamespace("stone"), null, 1,
			DataComponentPredicate.EMPTY));
		PatternStage stage = new PatternStage(0, " stage ", mutable);
		mutable.clear();
		assertEquals(1, stage.inputs().size());
		assertEquals("stage", stage.address());
		assertThrows(IllegalArgumentException.class, () -> new SequencedPatternBody(List.of(
			stage, new PatternStage(2, "later", List.of()))));
		assertEquals("route", new CreateRouteKey(UUID.randomUUID(), "  route  ").address());
	}

	@Test
	void recordCapacityCalculationRejectsCheckedCountOverflow() {
		PatternIngredient huge = new PatternIngredient(ResourceLocation.withDefaultNamespace("stone"),
			null, BigItemStack.INF, DataComponentPredicate.EMPTY);
		assertThrows(IllegalArgumentException.class, () -> new PatternRecord(UUID.randomUUID(), PAGE,
			List.of(huge, huge, huge),
			List.of(new PatternOutput(new StackKey(new ItemStack(Items.PAPER)), 1)),
			new StandardPatternBody("overflow"), OptionalInt.empty()));
	}

	@Test
	void parsesVersionTwoItemPattern() {
		ParseResult result = parser.parse(PAGE, """
			{"version":2,"type":"standard","inputs":[{"item":"minecraft:iron_nugget","count":9}],
			 "outputs":[{"item":"minecraft:iron_ingot","count":1}],"address":"iron_processing"}
			""");
		PatternRecord pattern = assertInstanceOf(ParseResult.Valid.class, result).pattern();
		assertEquals(9, pattern.inputs().getFirst().count());
		assertEquals(Items.IRON_INGOT, pattern.mainOutput().stack().stack().getItem());
		assertEquals("iron_processing",
			assertInstanceOf(StandardPatternBody.class, pattern.body()).address());
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"{}",
		"{\"version\":1,\"type\":\"standard\",\"inputs\":[],\"outputs\":[],\"address\":\"x\"}",
		"{\"version\":2,\"type\":\"standard\",\"inputs\":[{\"item\":\"minecraft:stone\",\"tag\":\"minecraft:stone_crafting_materials\",\"count\":1}],\"outputs\":[{\"item\":\"minecraft:dirt\",\"count\":1}],\"address\":\"x\"}",
		"{\"version\":2,\"type\":\"standard\",\"inputs\":[],\"outputs\":[{\"item\":\"minecraft:dirt\",\"count\":0}],\"address\":\"x\"}"
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
		assertEquals(record, PatternDataCodecs.loadRecord(
			PatternDataCodecs.saveRecord(record, registries), registries).orElseThrow());
		CompoundTag corrupt = PatternDataCodecs.saveRecord(record, registries);
		corrupt.putString("Inputs", "not-a-list");
		assertTrue(PatternDataCodecs.loadRecord(corrupt, registries).isEmpty());
	}

	@Test
	void unsupportedCodecAcceptsOnlyV2AndActuallyUnsupportedTypes() {
		ParseResult.Unsupported parsed = assertInstanceOf(ParseResult.Unsupported.class,
			parser.parse(PAGE, """
				{"version":2,"type":"future","inputs":[],
				 "outputs":[{"item":"minecraft:paper","count":1}]}
				"""));
		CompoundTag oldVersion = PatternDataCodecs.saveUnsupported(parsed.envelope());
		oldVersion.putInt("Version", 1);
		CompoundTag supportedType = PatternDataCodecs.saveUnsupported(parsed.envelope());
		supportedType.putString("Type", "standard");

		assertTrue(PatternDataCodecs.loadUnsupported(oldVersion).isEmpty());
		assertTrue(PatternDataCodecs.loadUnsupported(supportedType).isEmpty());
		assertThrows(IllegalArgumentException.class, () -> new UnsupportedPatternEnvelope(1,
			"future", PAGE, parsed.fingerprint(), "{}"));
		assertThrows(IllegalArgumentException.class, () -> new UnsupportedPatternEnvelope(2,
			"sequenced", PAGE, parsed.fingerprint(), "{}"));
	}

	@Test
	void recordCodecRejectsOversizedTopLevelAndNestedCollections() {
		PatternIngredient tagIngredient = new PatternIngredient(null, ItemTags.PLANKS.location(), 1,
			DataComponentPredicate.EMPTY);
		ListTag oversizedInputs = repeated(PatternItemCodecs.saveIngredient(tagIngredient, registries));
		CompoundTag topInputs = PatternDataCodecs.saveRecord(componentSensitivePattern(), registries);
		topInputs.put("Inputs", oversizedInputs);

		ListTag oversizedOutputs = repeated(PatternItemCodecs.saveOutput(
			new PatternOutput(new StackKey(new ItemStack(Items.PAPER)), 1), registries));
		CompoundTag topOutputs = PatternDataCodecs.saveRecord(componentSensitivePattern(), registries);
		topOutputs.put("Outputs", oversizedOutputs);

		PatternRecord sequence = new PatternRecord(UUID.randomUUID(), PAGE, List.of(),
			List.of(new PatternOutput(new StackKey(new ItemStack(Items.PAPER)), 1)),
			new SequencedPatternBody(List.of(new PatternStage(0, "stage", List.of(tagIngredient)))),
			OptionalInt.empty());
		CompoundTag nestedInputs = PatternDataCodecs.saveRecord(sequence, registries);
		nestedInputs.getCompound("Body").getList("Stages", Tag.TAG_COMPOUND).getCompound(0)
			.put("Inputs", repeated(PatternItemCodecs.saveIngredient(tagIngredient, registries)));

		CompoundTag manyStages = PatternDataCodecs.saveRecord(sequence, registries);
		CompoundTag stageTemplate = manyStages.getCompound("Body")
			.getList("Stages", Tag.TAG_COMPOUND).getCompound(0).copy();
		ListTag stages = new ListTag();
		for (int index = 0; index <= PatternRepresentationBounds.MAX_ENTRIES; index++) {
			CompoundTag stage = stageTemplate.copy();
			stage.putInt("Index", index);
			stages.add(stage);
		}
		manyStages.getCompound("Body").put("Stages", stages);

		assertTrue(PatternDataCodecs.loadRecord(topInputs, registries).isEmpty());
		assertTrue(PatternDataCodecs.loadRecord(topOutputs, registries).isEmpty());
		assertTrue(PatternDataCodecs.loadRecord(nestedInputs, registries).isEmpty());
		assertTrue(PatternDataCodecs.loadRecord(manyStages, registries).isEmpty());
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
			7, PatternReply.Status.MATCH_BATCH, record);
		PatternReply notFound = new PatternReply(query.queryId(), query.requesterComputerId(), query.logisticsId(),
			7, PatternReply.Status.GROUP_END, null);

		assertEquals(stack, PatternItemCodecs.loadStackKey(PatternItemCodecs.saveStackKey(stack, registries), registries).orElseThrow());
		assertEquals(itemIngredient, PatternItemCodecs.loadIngredient(PatternItemCodecs.saveIngredient(itemIngredient, registries), registries).orElseThrow());
		assertEquals(output, PatternItemCodecs.loadOutput(PatternItemCodecs.saveOutput(output, registries), registries).orElseThrow());
		assertEquals(PAGE, PatternDataCodecs.loadPageKey(PatternDataCodecs.savePageKey(PAGE)).orElseThrow());
		assertEquals(error, PatternDataCodecs.loadPageError(PatternDataCodecs.savePageError(error)).orElseThrow());
		assertEquals(record, PatternDataCodecs.loadRecord(PatternDataCodecs.saveRecord(record, registries), registries).orElseThrow());
		assertEquals(query, PatternSearchCodecs.loadQuery(PatternSearchCodecs.saveQuery(query, registries), registries).orElseThrow());
		assertEquals(match, PatternSearchCodecs.loadReply(PatternSearchCodecs.saveReply(match, registries), registries).orElseThrow());
		assertEquals(notFound, PatternSearchCodecs.loadReply(PatternSearchCodecs.saveReply(notFound, registries), registries).orElseThrow());
	}

	@Test
	void ingredientCodecRejectsUnknownSelectorsAndNonCanonicalSelectorStates() {
		PatternIngredient item = new PatternIngredient(ResourceLocation.withDefaultNamespace("iron_nugget"), null,
			1, DataComponentPredicate.EMPTY);
		PatternIngredient tag = new PatternIngredient(null, ItemTags.PLANKS.location(), 1,
			DataComponentPredicate.EMPTY);
		CompoundTag unknownItem = PatternItemCodecs.saveIngredient(item, registries);
		unknownItem.putString("Item", "minecraft:unknown_pattern_item");
		CompoundTag unknownTag = PatternItemCodecs.saveIngredient(tag, registries);
		unknownTag.putString("Tag", "minecraft:unknown_pattern_tag");
		CompoundTag bothSelectors = PatternItemCodecs.saveIngredient(item, registries);
		bothSelectors.putString("Tag", ItemTags.PLANKS.location().toString());
		CompoundTag missingSelector = PatternItemCodecs.saveIngredient(item, registries);
		missingSelector.remove("Item");
		CompoundTag wrongCountType = PatternItemCodecs.saveIngredient(item, registries);
		wrongCountType.putString("Count", "1");
		CompoundTag extraMember = PatternItemCodecs.saveIngredient(item, registries);
		extraMember.putString("Extra", "no");

		assertTrue(PatternItemCodecs.loadIngredient(unknownItem, registries).isEmpty());
		assertTrue(PatternItemCodecs.loadIngredient(unknownTag, registries).isEmpty());
		assertTrue(PatternItemCodecs.loadIngredient(bothSelectors, registries).isEmpty());
		assertTrue(PatternItemCodecs.loadIngredient(missingSelector, registries).isEmpty());
		assertTrue(PatternItemCodecs.loadIngredient(wrongCountType, registries).isEmpty());
		assertTrue(PatternItemCodecs.loadIngredient(extraMember, registries).isEmpty());
	}

	@Test
	void persistedCodecsRejectRepresentativeWrongTypesMissingFieldsExtrasAndInvariants() {
		StackKey stack = new StackKey(new ItemStack(Items.PAPER, 1));
		PatternOutput output = new PatternOutput(stack, 1);
		PatternQuery query = new PatternQuery(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), stack, 1, 0);
		PatternReply found = new PatternReply(query.queryId(), query.requesterComputerId(), query.logisticsId(),
			1, PatternReply.Status.GROUP_END, null);
		CompoundTag missingStack = PatternItemCodecs.saveStackKey(stack, registries);
		missingStack.remove("Stack");
		CompoundTag wrongOutput = PatternItemCodecs.saveOutput(output, registries);
		wrongOutput.putString("Count", "1");
		CompoundTag extraPage = PatternDataCodecs.savePageKey(PAGE);
		extraPage.putString("Extra", "no");
		CompoundTag invalidError = PatternDataCodecs.savePageError(new PatternPageError(PAGE, PatternErrorReason.JSON, "x"));
		invalidError.putString("Reason", "NOPE");
		CompoundTag defaultedCursor = PatternSearchCodecs.saveQuery(query, registries);
		defaultedCursor.putInt("Cursor", -1);
		CompoundTag replyWithUnexpectedPattern = PatternSearchCodecs.saveReply(found, registries);
		replyWithUnexpectedPattern.put("Pattern", PatternDataCodecs.saveRecord(componentSensitivePattern(), registries));
		CompoundTag replyWithoutMatch = PatternSearchCodecs.saveReply(new PatternReply(query.queryId(),
			query.requesterComputerId(), query.logisticsId(), 1, PatternReply.Status.MATCH_BATCH,
			componentSensitivePattern()), registries);
		replyWithoutMatch.remove("Pattern");

		assertTrue(PatternItemCodecs.loadStackKey(missingStack, registries).isEmpty());
		assertTrue(PatternItemCodecs.loadOutput(wrongOutput, registries).isEmpty());
		assertTrue(PatternDataCodecs.loadPageKey(extraPage).isEmpty());
		assertTrue(PatternDataCodecs.loadPageError(invalidError).isEmpty());
		assertTrue(PatternSearchCodecs.loadQuery(defaultedCursor, registries).isEmpty());
		assertTrue(PatternSearchCodecs.loadReply(replyWithUnexpectedPattern, registries).isEmpty());
		assertTrue(PatternSearchCodecs.loadReply(replyWithoutMatch, registries).isEmpty());
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"{\"version\":2,\"type\":\"standard\",\"inputs\":[],\"outputs\":[],\"address\":\"x\",\"extra\":true}",
		"{\"version\":2,\"type\":\"standard\",\"inputs\":[{\"item\":\"minecraft:stone\",\"count\":1,\"extra\":true}],\"outputs\":[{\"item\":\"minecraft:dirt\",\"count\":1}],\"address\":\"x\"}",
		"{\"version\":2,\"type\":\"standard\",\"inputs\":[{\"count\":1}],\"outputs\":[{\"item\":\"minecraft:dirt\",\"count\":1}],\"address\":\"x\"}",
		"{\"version\":2,\"type\":\"standard\",\"inputs\":[{\"item\":\"minecraft:unknown_pattern_item\",\"count\":1}],\"outputs\":[{\"item\":\"minecraft:dirt\",\"count\":1}],\"address\":\"x\"}",
		"{\"version\":2,\"type\":\"standard\",\"inputs\":[{\"tag\":\"minecraft:unknown_pattern_tag\",\"count\":1}],\"outputs\":[{\"item\":\"minecraft:dirt\",\"count\":1}],\"address\":\"x\"}",
		"{\"version\":2,\"type\":\"standard\",\"inputs\":[{\"item\":\"minecraft:stone\",\"count\":1000000001}],\"outputs\":[{\"item\":\"minecraft:dirt\",\"count\":1}],\"address\":\"x\"}",
		"{\"version\":2,\"type\":\"standard\",\"inputs\":[{\"item\":\"minecraft:stone\",\"count\":1,\"components\":{\"minecraft:unknown_component\":1}}],\"outputs\":[{\"item\":\"minecraft:dirt\",\"count\":1}],\"address\":\"x\"}",
		"{\"version\":2,\"type\":\"standard\",\"inputs\":[{\"item\":\"minecraft:stone\",\"count\":1}],\"outputs\":[{\"item\":\"minecraft:dirt\",\"count\":1,\"extra\":true}],\"address\":\"x\"}",
		"{\"version\":2,\"type\":\"standard\",\"inputs\":[{\"item\":\"minecraft:stone\",\"count\":1}],\"outputs\":[{\"item\":\"minecraft:unknown_pattern_item\",\"count\":1}],\"address\":\"x\"}",
		"{\"version\":2,\"type\":\"standard\",\"inputs\":[{\"item\":\"minecraft:stone\",\"count\":1}],\"outputs\":[{\"item\":\"minecraft:dirt\",\"count\":1000000001}],\"address\":\"x\"}",
		"{\"version\":2,\"type\":\"standard\",\"inputs\":[{\"item\":\"minecraft:stone\",\"count\":1}],\"outputs\":[{\"item\":\"minecraft:dirt\",\"count\":1,\"components\":{\"minecraft:max_stack_size\":0}}],\"address\":\"x\"}"
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
			List.of(new PatternOutput(new StackKey(new ItemStack(Items.IRON_INGOT, 1)), 1)),
			new StandardPatternBody("no_inputs"), OptionalInt.empty());
		PatternQuery query = new PatternQuery(
			UUID.fromString("00000000-0000-0000-0000-000000000007"),
			UUID.fromString("00000000-0000-0000-0000-000000000008"),
			UUID.fromString("00000000-0000-0000-0000-000000000009"),
			zeroInput.mainOutput().stack(), 4, 0);
		PatternReply reply = new PatternReply(query.queryId(), query.requesterComputerId(), query.logisticsId(),
			4, PatternReply.Status.MATCH_BATCH, zeroInput);

		assertEquals(zeroInput, PatternDataCodecs.loadRecord(
			PatternDataCodecs.saveRecord(zeroInput, registries), registries).orElseThrow());
		assertEquals(reply, PatternSearchCodecs.loadReply(
			PatternSearchCodecs.saveReply(reply, registries), registries).orElseThrow());
	}

	@Test
	void canonicalPatternIdSurvivesPageKeyRoundTripAndChangesWithIdentityInputs() {
		PatternRecord first = assertInstanceOf(ParseResult.Valid.class,
			parser.parse(PAGE, VALID_JSON)).pattern();
		PatternPageKey restoredPage = PatternDataCodecs.loadPageKey(
			PatternDataCodecs.savePageKey(PAGE)).orElseThrow();
		PatternRecord restored = assertInstanceOf(ParseResult.Valid.class,
			parser.parse(restoredPage, VALID_JSON)).pattern();

		assertEquals(first.patternId(), restored.patternId());
		assertIdDiffers(first, new PatternPageKey(new RecipeSourceLocation(Level.NETHER,
			PAGE.shelf().subLevelId(), PAGE.shelf().localPos()), PAGE.slot(), PAGE.page()));
		assertIdDiffers(first, new PatternPageKey(new RecipeSourceLocation(PAGE.shelf().dimension(), null,
			PAGE.shelf().localPos()), PAGE.slot(), PAGE.page()));
		assertIdDiffers(first, new PatternPageKey(new RecipeSourceLocation(PAGE.shelf().dimension(),
			PAGE.shelf().subLevelId(), PAGE.shelf().localPos().offset(1, 0, 0)), PAGE.slot(), PAGE.page()));
		assertIdDiffers(first, new PatternPageKey(new RecipeSourceLocation(PAGE.shelf().dimension(),
			PAGE.shelf().subLevelId(), PAGE.shelf().localPos().offset(0, 1, 0)), PAGE.slot(), PAGE.page()));
		assertIdDiffers(first, new PatternPageKey(new RecipeSourceLocation(PAGE.shelf().dimension(),
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
			List.of(new PatternOutput(new StackKey(output), 1)),
			new StandardPatternBody("component_test"), OptionalInt.of(7));
	}

	private static ListTag repeated(CompoundTag element) {
		ListTag values = new ListTag();
		for (int index = 0; index <= PatternRepresentationBounds.MAX_ENTRIES; index++)
			values.add(element.copy());
		return values;
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
