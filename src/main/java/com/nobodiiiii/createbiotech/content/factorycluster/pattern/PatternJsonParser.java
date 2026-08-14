package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.simibubi.create.content.logistics.BigItemStack;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
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
		if (raw.isBlank())
			return changed(fingerprint, new ParseResult.Blank(fingerprint));
		try {
			JsonElement element = JsonParser.parseString(raw);
			if (!element.isJsonObject())
				return invalid(source, PatternErrorReason.JSON, "Pattern page must be a JSON object", fingerprint);
			return parseObject(source, element.getAsJsonObject(), fingerprint);
		} catch (RuntimeException exception) {
			return invalid(source, PatternErrorReason.JSON, "Malformed pattern JSON", fingerprint);
		}
	}

	private PageInspection parseObject(PatternPageKey source, JsonObject root, String fingerprint) {
		if (!hasExactMembers(root, "v", "in", "out", "to"))
			return invalid(source, PatternErrorReason.JSON, "Unknown or missing root member", fingerprint);
		if (!root.get("v").isJsonPrimitive() || !root.getAsJsonPrimitive("v").isNumber()
			|| integer(root.get("v")).filter(value -> value == 1).isEmpty())
			return invalid(source, PatternErrorReason.VERSION, "Expected integer version 1", fingerprint);
		if (!root.get("in").isJsonArray() || !root.get("out").isJsonArray()
			|| !root.get("to").isJsonPrimitive() || !root.getAsJsonPrimitive("to").isString())
			return invalid(source, PatternErrorReason.JSON, "Invalid root member type", fingerprint);

		String address = root.get("to").getAsString().strip();
		if (address.isEmpty() || address.length() > 25)
			return invalid(source, PatternErrorReason.ADDRESS, "Address length must be 1..25", fingerprint);

		Optional<List<PatternIngredient>> inputs = parseInputs(root.getAsJsonArray("in"));
		if (inputs.isEmpty())
			return invalid(source, PatternErrorReason.INPUT_SELECTOR, "Invalid input", fingerprint);
		Optional<List<PatternOutput>> outputs = parseOutputs(root.getAsJsonArray("out"));
		if (outputs.isEmpty() || outputs.orElseThrow().isEmpty())
			return invalid(source, PatternErrorReason.OUTPUT, "Pattern needs at least one output", fingerprint);

		UUID patternId = canonicalPatternId(source, fingerprint);
		return changed(fingerprint, new ParseResult.Valid(new PatternRecord(patternId, source,
			inputs.orElseThrow(), outputs.orElseThrow(), address), fingerprint));
	}

	private Optional<List<PatternIngredient>> parseInputs(JsonArray values) {
		List<PatternIngredient> results = new ArrayList<>();
		for (JsonElement value : values) {
			if (!value.isJsonObject())
				return Optional.empty();
			JsonObject input = value.getAsJsonObject();
			boolean item = input.has("item");
			boolean tag = input.has("tag");
			if (item == tag || !hasAllowedMembers(input, "item", "tag", "count", "components")
				|| !input.has("count") || !input.get("count").isJsonPrimitive()
				|| !input.getAsJsonPrimitive("count").isNumber())
				return Optional.empty();
			Optional<Integer> count = integer(input.get("count")).filter(this::validCount);
			Optional<ResourceLocation> selector = resourceId(input.get(item ? "item" : "tag"));
			if (count.isEmpty() || selector.isEmpty() || (item && !BuiltInRegistries.ITEM.containsKey(selector.orElseThrow()))
				|| (tag && BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, selector.orElseThrow())).isEmpty()))
				return Optional.empty();
			Optional<DataComponentPredicate> components = components(input, DataComponentPredicate.CODEC,
				DataComponentPredicate.EMPTY);
			if (components.isEmpty())
				return Optional.empty();
			results.add(new PatternIngredient(item ? selector.orElseThrow() : null,
				item ? null : selector.orElseThrow(), count.orElseThrow(), components.orElseThrow()));
		}
		return Optional.of(List.copyOf(results));
	}

	private Optional<List<PatternOutput>> parseOutputs(JsonArray values) {
		List<PatternOutput> results = new ArrayList<>();
		for (JsonElement value : values) {
			if (!value.isJsonObject())
				return Optional.empty();
			JsonObject output = value.getAsJsonObject();
			if (!hasAllowedMembers(output, "item", "count", "components") || !output.has("item")
				|| !output.has("count") || !output.get("count").isJsonPrimitive()
				|| !output.getAsJsonPrimitive("count").isNumber())
				return Optional.empty();
			Optional<ResourceLocation> itemId = resourceId(output.get("item"));
			Optional<Integer> count = integer(output.get("count")).filter(this::validCount);
			if (itemId.isEmpty() || count.isEmpty() || !BuiltInRegistries.ITEM.containsKey(itemId.orElseThrow()))
				return Optional.empty();
			Item item = BuiltInRegistries.ITEM.get(itemId.orElseThrow());
			Optional<DataComponentPatch> patch = components(output, DataComponentPatch.CODEC, DataComponentPatch.EMPTY);
			if (patch.isEmpty())
				return Optional.empty();
			ItemStack stack = new ItemStack(item, 1);
			ItemStack before = stack.copy();
			stack.applyComponentsAndValidate(patch.orElseThrow());
			if (!patch.orElseThrow().isEmpty() && ItemStack.isSameItemSameComponents(before, stack))
				return Optional.empty();
			try {
				Math.multiplyExact(stack.getCount(), count.orElseThrow());
				results.add(new PatternOutput(new StackKey(stack), count.orElseThrow()));
			} catch (ArithmeticException exception) {
				return Optional.empty();
			}
		}
		return Optional.of(List.copyOf(results));
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

	private static UUID canonicalPatternId(PatternPageKey source, String fingerprint) {
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

	private static String sha256LowerHex(String raw) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(raw.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new AssertionError(exception);
		}
	}

}

sealed interface ParseResult permits ParseResult.Blank, ParseResult.Valid, ParseResult.Invalid {
	record Blank(String fingerprint) implements ParseResult {}
	record Valid(PatternRecord pattern, String fingerprint) implements ParseResult {}
	record Invalid(PatternPageError error, String fingerprint) implements ParseResult {}
}

record PageInspection(String fingerprint, Optional<ParseResult> changedResult) {
	PageInspection {
		changedResult = Optional.ofNullable(changedResult).orElseThrow();
	}
}
