package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StandardPatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.SequencedPatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternStage;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternPackageCapacity;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternOutput;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeAddressRules;
import com.simibubi.create.content.logistics.BigItemStack;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class PatternJsonParser {
	private final HolderLookup.Provider registries;

	public PatternJsonParser(HolderLookup.Provider registries) {
		this.registries = java.util.Objects.requireNonNull(registries, "registries");
	}

	public ParseResult parse(PatternPageKey source, String raw) {
		return inspect(source, raw, null).changedResult().orElseThrow();
	}

	public PageInspection inspect(PatternPageKey source, String raw, @Nullable String cachedFingerprint) {
		String fingerprint = sha256LowerHex(raw);
		if (fingerprint.equals(cachedFingerprint))
			return new PageInspection(fingerprint, Optional.empty());
        if (raw.isBlank() || com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBookIdentityPage.isIdentityPage(raw))
			return changed(fingerprint, new ParseResult.Blank(fingerprint));
		try {
			JsonElement element = JsonParser.parseString(raw);
			if (!element.isJsonObject())
				return invalid(source, PatternErrorReason.JSON, "Pattern page must be a JSON object", fingerprint);
			return parseObject(source, element.getAsJsonObject(), raw, fingerprint);
		} catch (RuntimeException exception) {
			return invalid(source, PatternErrorReason.JSON, "Malformed pattern JSON", fingerprint);
		}
	}

	private PageInspection parseObject(PatternPageKey source, JsonObject root, String raw, String fingerprint) {
		if (!hasRequiredMembers(root, "version", "type", "inputs", "outputs"))
			return invalid(source, PatternErrorReason.JSON, "Missing common Pattern member", fingerprint);
		if (!root.get("version").isJsonPrimitive() || !root.getAsJsonPrimitive("version").isNumber()
			|| integer(root.get("version")).filter(value -> value == 2 || value == 3).isEmpty())
			return invalid(source, PatternErrorReason.VERSION, "Expected page version 2 or shaped page version 3", fingerprint);
		if (!root.get("type").isJsonPrimitive() || !root.getAsJsonPrimitive("type").isString()
			|| !root.get("inputs").isJsonArray() || !root.get("outputs").isJsonArray())
			return invalid(source, PatternErrorReason.JSON, "Invalid common Pattern member type", fingerprint);
		String type = root.get("type").getAsString();
		CraftingSlots grid = CraftingSlots.NONE;
		if (root.get("version").getAsInt() == 3) {
			if (!"standard".equals(type) || !root.has("crafting_slots"))
				return invalid(source, PatternErrorReason.VERSION, "Version 3 requires a shaped workbench grid", fingerprint);
			try { grid = CraftingSlots.read(root.remove("crafting_slots")); }
			catch (RuntimeException invalidGrid) { return invalid(source, PatternErrorReason.JSON, "Invalid crafting slots", fingerprint); }
		}
		if (type.isBlank() || !type.equals(type.strip())
			|| type.length() > UnsupportedPatternEnvelope.MAX_TYPE_LENGTH)
			return invalid(source, PatternErrorReason.JSON, "Invalid Pattern type", fingerprint);

		List<PatternIngredient> inputs;
		List<PatternOutput> outputs;
		try {
			inputs = parseInputs(root.getAsJsonArray("inputs"));
			grid.validate(inputs);
			outputs = parseOutputs(root.getAsJsonArray("outputs"));
		} catch (PatternParseFailure failure) {
			return invalid(source, failure.reason, failure.getMessage(), fingerprint);
		}
		if (outputs.isEmpty())
			return invalid(source, PatternErrorReason.OUTPUT, "Pattern needs at least one output", fingerprint);
		Optional<OptionalInt> priority = parsePriority(root);
		if (priority.isEmpty())
			return invalid(source, PatternErrorReason.JSON, "Pattern priority must be an integer", fingerprint);

		Optional<PatternKind> kind = PatternKind.fromSerializedName(type);
		if (kind.isEmpty()) {
			UnsupportedPatternEnvelope envelope = new UnsupportedPatternEnvelope(2, type, source,
				fingerprint, boundedRaw(raw));
			PatternPageError error = new PatternPageError(source,
				PatternErrorReason.UNSUPPORTED_PATTERN_TYPE, "Unsupported Pattern type: " + type);
			return changed(fingerprint, new ParseResult.Unsupported(envelope, error, fingerprint));
		}

		PatternBody body;
		if (kind.orElseThrow() == PatternKind.STANDARD) {
			if (!hasExactMembers(root, priority.orElseThrow().isPresent()
				? new String[] { "version", "type", "inputs", "outputs", "address", "priority" }
				: new String[] { "version", "type", "inputs", "outputs", "address" })
				|| !root.get("address").isJsonPrimitive()
				|| !root.getAsJsonPrimitive("address").isString())
				return invalid(source, PatternErrorReason.JSON, "Invalid STANDARD body", fingerprint);
			try {
				body = new StandardPatternBody(RecipeAddressRules.requireNormalized(
					root.get("address").getAsString()), grid);
			} catch (IllegalArgumentException exception) {
				return invalid(source, PatternErrorReason.ADDRESS, "Invalid STANDARD address", fingerprint);
			}
		} else {
			if (!hasExactMembers(root, priority.orElseThrow().isPresent()
				? new String[] { "version", "type", "inputs", "outputs", "stages", "priority" }
				: new String[] { "version", "type", "inputs", "outputs", "stages" })
				|| !root.get("stages").isJsonArray())
				return invalid(source, PatternErrorReason.JSON, "Invalid SEQUENCED body", fingerprint);
			try {
				List<PatternStage> stages = parseStages(root.getAsJsonArray("stages"));
				if (stages.isEmpty())
					return invalid(source, PatternErrorReason.INPUT_SELECTOR,
						"SEQUENCED Pattern needs at least one stage", fingerprint);
				body = new SequencedPatternBody(stages);
			} catch (PatternParseFailure failure) {
				return invalid(source, failure.reason, failure.getMessage(), fingerprint);
			}
		}

		try {
			PatternPackageCapacity.requirePatternFits(inputs, body);
		} catch (IllegalArgumentException exception) {
			return invalid(source, PatternErrorReason.PACKAGE_CAPACITY,
				"One Pattern operation exceeds Create package capacity", fingerprint);
		}

		UUID patternId = canonicalPatternId(source, fingerprint);
		return changed(fingerprint, new ParseResult.Valid(new PatternRecord(patternId, source,
			inputs, outputs, body, priority.orElseThrow()), fingerprint));
	}

	private List<PatternStage> parseStages(JsonArray values) throws PatternParseFailure {
		List<PatternStage> results = new ArrayList<>();
		for (JsonElement value : values) {
			if (!value.isJsonObject())
				throw new PatternParseFailure(PatternErrorReason.JSON, "Pattern stage must be an object");
			JsonObject stage = value.getAsJsonObject();
			if (!hasExactMembers(stage, "address", "inputs") || !stage.get("address").isJsonPrimitive()
				|| !stage.getAsJsonPrimitive("address").isString() || !stage.get("inputs").isJsonArray())
				throw new PatternParseFailure(PatternErrorReason.JSON, "Invalid Pattern stage body");
			List<PatternIngredient> inputs = parseInputs(stage.getAsJsonArray("inputs"));
			String address;
			try {
				address = RecipeAddressRules.requireNormalized(stage.get("address").getAsString());
			} catch (IllegalArgumentException exception) {
				throw new PatternParseFailure(PatternErrorReason.ADDRESS, "Invalid Pattern stage address");
			}
			results.add(new PatternStage(results.size(), address, inputs));
		}
		return List.copyOf(results);
	}

	private static Optional<OptionalInt> parsePriority(JsonObject root) {
		if (!root.has("priority"))
			return Optional.of(OptionalInt.empty());
		if (!root.get("priority").isJsonPrimitive() || !root.getAsJsonPrimitive("priority").isNumber())
			return Optional.empty();
		return integer(root.get("priority")).map(OptionalInt::of);
	}

	private List<PatternIngredient> parseInputs(JsonArray values) throws PatternParseFailure {
		List<PatternIngredient> results = new ArrayList<>();
		for (JsonElement value : values) {
			if (!value.isJsonObject())
				throw new PatternParseFailure(PatternErrorReason.INPUT_SELECTOR,
					"Pattern input must be an object");
			JsonObject input = value.getAsJsonObject();
			boolean item = input.has("item");
			boolean tag = input.has("tag");
			if (item == tag || !hasAllowedMembers(input, "item", "tag", "count", "components"))
				throw new PatternParseFailure(PatternErrorReason.INPUT_SELECTOR,
					"Pattern input needs exactly one selector");
			if (!input.has("count") || !input.get("count").isJsonPrimitive()
				|| !input.getAsJsonPrimitive("count").isNumber())
				throw new PatternParseFailure(PatternErrorReason.COUNT,
					"Pattern input count must be an integer");
			Optional<Integer> count = integer(input.get("count")).filter(this::validCount);
			Optional<ResourceLocation> selector = resourceId(input.get(item ? "item" : "tag"));
			if (count.isEmpty())
				throw new PatternParseFailure(PatternErrorReason.COUNT,
					"Pattern input count must be positive and representable");
			if (selector.isEmpty() || (item && !BuiltInRegistries.ITEM.containsKey(selector.orElseThrow()))
				|| (tag && BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, selector.orElseThrow())).isEmpty()))
				throw new PatternParseFailure(PatternErrorReason.INPUT_SELECTOR,
					"Unknown Pattern input selector");
			Optional<DataComponentPredicate> components = components(input, DataComponentPredicate.CODEC,
				DataComponentPredicate.EMPTY);
			if (components.isEmpty())
				throw new PatternParseFailure(PatternErrorReason.COMPONENTS,
					"Invalid Pattern input components");
			results.add(new PatternIngredient(item ? selector.orElseThrow() : null,
				item ? null : selector.orElseThrow(), count.orElseThrow(), components.orElseThrow()));
		}
		return List.copyOf(results);
	}

	private List<PatternOutput> parseOutputs(JsonArray values) throws PatternParseFailure {
		List<PatternOutput> results = new ArrayList<>();
		for (JsonElement value : values) {
			if (!value.isJsonObject())
				throw new PatternParseFailure(PatternErrorReason.OUTPUT,
					"Pattern output must be an object");
			JsonObject output = value.getAsJsonObject();
			if (!hasAllowedMembers(output, "item", "count", "components") || !output.has("item"))
				throw new PatternParseFailure(PatternErrorReason.OUTPUT, "Invalid Pattern output selector");
			if (!output.has("count") || !output.get("count").isJsonPrimitive()
				|| !output.getAsJsonPrimitive("count").isNumber())
				throw new PatternParseFailure(PatternErrorReason.COUNT,
					"Pattern output count must be an integer");
			Optional<ResourceLocation> itemId = resourceId(output.get("item"));
			Optional<Integer> count = integer(output.get("count")).filter(this::validCount);
			if (count.isEmpty())
				throw new PatternParseFailure(PatternErrorReason.COUNT,
					"Pattern output count must be positive and representable");
			if (itemId.isEmpty() || !BuiltInRegistries.ITEM.containsKey(itemId.orElseThrow()))
				throw new PatternParseFailure(PatternErrorReason.OUTPUT, "Unknown Pattern output item");
			Item item = BuiltInRegistries.ITEM.get(itemId.orElseThrow());
			Optional<DataComponentPatch> patch = components(output, DataComponentPatch.CODEC, DataComponentPatch.EMPTY);
			if (patch.isEmpty())
				throw new PatternParseFailure(PatternErrorReason.COMPONENTS,
					"Invalid Pattern output components");
			ItemStack stack = new ItemStack(item, 1);
			ItemStack before = stack.copy();
			try {
				stack.applyComponentsAndValidate(patch.orElseThrow());
			} catch (RuntimeException exception) {
				throw new PatternParseFailure(PatternErrorReason.COMPONENTS,
					"Invalid Pattern output component values");
			}
			if (!patch.orElseThrow().isEmpty() && ItemStack.isSameItemSameComponents(before, stack))
				throw new PatternParseFailure(PatternErrorReason.COMPONENTS,
					"Pattern output components must change the exact stack");
			results.add(new PatternOutput(new StackKey(stack), count.orElseThrow()));
		}
		return List.copyOf(results);
	}

	private <T> Optional<T> components(JsonObject value, Codec<T> codec, T defaultValue) {
		if (!value.has("components"))
			return Optional.of(defaultValue);
		return codec.parse(registries.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE),
			value.get("components")).result();
	}

	private boolean validCount(int count) {
		return count > 0 && count <= BigItemStack.INF;
	}

	private static Optional<ResourceLocation> resourceId(JsonElement element) {
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
			return Optional.empty();
		return Optional.ofNullable(ResourceLocation.tryParse(element.getAsString()));
	}

	private static Optional<Integer> integer(JsonElement element) {
		try {
			return Optional.of(element.getAsBigDecimal().intValueExact());
		} catch (NumberFormatException | ArithmeticException exception) {
			return Optional.empty();
		}
	}

	private static boolean hasExactMembers(JsonObject object, String... members) {
		return object.size() == members.length && hasAllowedMembers(object, members);
	}

	private static boolean hasRequiredMembers(JsonObject object, String... members) {
		for (String member : members)
			if (!object.has(member))
				return false;
		return true;
	}

	private static boolean hasAllowedMembers(JsonObject object, String... members) {
		for (String member : object.keySet())
			if (!java.util.Set.of(members).contains(member))
				return false;
		return true;
	}

	private static PageInspection changed(String fingerprint, ParseResult result) {
		return new PageInspection(fingerprint, Optional.of(result));
	}

	private static PageInspection invalid(PatternPageKey source, PatternErrorReason reason, String detail,
		String fingerprint) {
		return changed(fingerprint, new ParseResult.Invalid(new PatternPageError(source, reason, detail), fingerprint));
	}

	public static UUID canonicalPatternId(PatternPageKey source, String fingerprint) {
		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream output = new DataOutputStream(bytes)) {
			output.writeUTF(source.shelf().dimension().location().toString());
			output.writeBoolean(source.shelf().subLevelId() != null);
			if (source.shelf().subLevelId() != null) {
				output.writeLong(source.shelf().subLevelId().getMostSignificantBits());
				output.writeLong(source.shelf().subLevelId().getLeastSignificantBits());
			}
			output.writeInt(source.shelf().localPos().getX());
			output.writeInt(source.shelf().localPos().getY());
			output.writeInt(source.shelf().localPos().getZ());
			output.writeByte(source.slot());
			output.writeByte(source.page());
			output.write(fingerprint.getBytes(StandardCharsets.US_ASCII));
			output.flush();
			return UUID.nameUUIDFromBytes(bytes.toByteArray());
		} catch (IOException exception) {
			throw new AssertionError(exception);
		}
	}

	public static String sha256LowerHex(String raw) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(raw.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new AssertionError(exception);
		}
	}

	private static String boundedRaw(String raw) {
		return raw.length() <= UnsupportedPatternEnvelope.MAX_RAW_TEXT_LENGTH ? raw
			: raw.substring(0, UnsupportedPatternEnvelope.MAX_RAW_TEXT_LENGTH);
	}

	private static final class PatternParseFailure extends Exception {
		private final PatternErrorReason reason;

		private PatternParseFailure(PatternErrorReason reason, String detail) {
			super(detail);
			this.reason = reason;
		}
	}

}

