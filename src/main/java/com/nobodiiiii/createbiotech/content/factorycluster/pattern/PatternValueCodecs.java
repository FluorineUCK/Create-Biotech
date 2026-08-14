package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;

public final class PatternValueCodecs {
	private PatternValueCodecs() {}

	public static CompoundTag saveStackKey(StackKey value, HolderLookup.Provider registries) {
		CompoundTag tag = new CompoundTag();
		tag.put("Stack", encode(ItemStack.CODEC, value.stack(), registries).orElseThrow());
		return tag;
	}

	public static Optional<StackKey> loadStackKey(CompoundTag tag, HolderLookup.Provider registries) {
		if (!hasExactKeys(tag, "Stack") || !hasType(tag, "Stack", Tag.TAG_COMPOUND))
			return Optional.empty();
		return decode(ItemStack.CODEC, tag.getCompound("Stack"), registries)
			.filter(stack -> !stack.isEmpty() && stack.getCount() == 1)
			.map(StackKey::new);
	}

	public static CompoundTag saveIngredient(PatternIngredient value, HolderLookup.Provider registries) {
		CompoundTag tag = new CompoundTag();
		if (value.itemId() != null)
			tag.putString("Item", value.itemId().toString());
		else
			tag.putString("Tag", value.tagId().toString());
		tag.putInt("Count", value.count());
		tag.put("Components", encode(DataComponentPredicate.CODEC, value.components(), registries).orElseThrow());
		return tag;
	}

	public static Optional<PatternIngredient> loadIngredient(CompoundTag tag, HolderLookup.Provider registries) {
		boolean item = hasType(tag, "Item", Tag.TAG_STRING);
		boolean tagSelector = hasType(tag, "Tag", Tag.TAG_STRING);
		if (item == tagSelector || !hasType(tag, "Count", Tag.TAG_INT)
			|| !hasType(tag, "Components", Tag.TAG_COMPOUND)
			|| !hasExactKeys(tag, item ? new String[] { "Item", "Count", "Components" }
				: new String[] { "Tag", "Count", "Components" }))
			return Optional.empty();
		ResourceLocation selector = ResourceLocation.tryParse(item ? tag.getString("Item") : tag.getString("Tag"));
		if (selector == null || tag.getInt("Count") <= 0
			|| tag.getInt("Count") > com.simibubi.create.content.logistics.BigItemStack.INF
			|| (item && !BuiltInRegistries.ITEM.containsKey(selector))
			|| (!item && BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, selector)).isEmpty()))
			return Optional.empty();
		return decode(DataComponentPredicate.CODEC, tag.getCompound("Components"), registries)
			.flatMap(components -> safely(() -> new PatternIngredient(item ? selector : null,
				item ? null : selector, tag.getInt("Count"), components)));
	}

	public static CompoundTag saveOutput(PatternOutput value, HolderLookup.Provider registries) {
		CompoundTag tag = new CompoundTag();
		tag.put("Stack", saveStackKey(value.stack(), registries));
		tag.putInt("Count", value.count());
		return tag;
	}

	public static Optional<PatternOutput> loadOutput(CompoundTag tag, HolderLookup.Provider registries) {
		if (!hasExactKeys(tag, "Stack", "Count") || !hasType(tag, "Stack", Tag.TAG_COMPOUND)
			|| !hasType(tag, "Count", Tag.TAG_INT) || tag.getInt("Count") <= 0 || tag.getInt("Count") > com.simibubi.create.content.logistics.BigItemStack.INF)
			return Optional.empty();
		return loadStackKey(tag.getCompound("Stack"), registries)
			.flatMap(stack -> safely(() -> new PatternOutput(stack, tag.getInt("Count"))));
	}

	public static CompoundTag savePageKey(PatternPageKey value) {
		CompoundTag tag = new CompoundTag();
		tag.put("Shelf", value.shelf().save());
		tag.putByte("Slot", (byte) value.slot());
		tag.putByte("Page", (byte) value.page());
		return tag;
	}

	public static Optional<PatternPageKey> loadPageKey(CompoundTag tag) {
		if (!hasExactKeys(tag, "Shelf", "Slot", "Page") || !hasType(tag, "Shelf", Tag.TAG_COMPOUND)
			|| !hasType(tag, "Slot", Tag.TAG_BYTE) || !hasType(tag, "Page", Tag.TAG_BYTE))
			return Optional.empty();
		return SpaceAddress.tryLoad(tag.getCompound("Shelf"))
			.flatMap(shelf -> safely(() -> new PatternPageKey(shelf, tag.getByte("Slot"), tag.getByte("Page"))));
	}

	public static CompoundTag savePageError(PatternPageError value) {
		CompoundTag tag = new CompoundTag();
		tag.put("Source", savePageKey(value.source()));
		tag.putString("Reason", value.reason().name());
		tag.putString("Detail", value.detail());
		return tag;
	}

	public static Optional<PatternPageError> loadPageError(CompoundTag tag) {
		if (!hasExactKeys(tag, "Source", "Reason", "Detail") || !hasType(tag, "Source", Tag.TAG_COMPOUND)
			|| !hasType(tag, "Reason", Tag.TAG_STRING) || !hasType(tag, "Detail", Tag.TAG_STRING) || tag.getString("Detail").length() > 96)
			return Optional.empty();
		return loadPageKey(tag.getCompound("Source")).flatMap(source -> enumValue(PatternErrorReason.class,
			tag.getString("Reason")).flatMap(reason -> safely(() -> new PatternPageError(source, reason,
			tag.getString("Detail")))));
	}

	public static CompoundTag saveRecord(PatternRecord value, HolderLookup.Provider registries) {
		CompoundTag tag = new CompoundTag();
		tag.putUUID("PatternId", value.patternId());
		tag.put("Source", savePageKey(value.source()));
		tag.put("Inputs", saveIngredients(value.inputs(), registries));
		tag.put("Outputs", saveOutputs(value.outputs(), registries));
		tag.putString("Address", value.recipeAddress());
		return tag;
	}

	public static Optional<PatternRecord> loadRecord(CompoundTag tag, HolderLookup.Provider registries) {
		if (!hasExactKeys(tag, "PatternId", "Source", "Inputs", "Outputs", "Address")
			|| !hasType(tag, "PatternId", Tag.TAG_INT_ARRAY) || !tag.hasUUID("PatternId")
			|| !hasType(tag, "Source", Tag.TAG_COMPOUND) || !hasType(tag, "Inputs", Tag.TAG_LIST)
			|| !hasType(tag, "Outputs", Tag.TAG_LIST) || !hasType(tag, "Address", Tag.TAG_STRING))
			return Optional.empty();
		if (!hasCompoundList(tag, "Inputs") || !hasCompoundList(tag, "Outputs"))
			return Optional.empty();
		Optional<List<PatternIngredient>> inputs = loadIngredients((ListTag) tag.get("Inputs"), registries);
		Optional<List<PatternOutput>> outputs = loadOutputs((ListTag) tag.get("Outputs"), registries);
		return loadPageKey(tag.getCompound("Source")).flatMap(source -> inputs.flatMap(loadedInputs ->
			outputs.flatMap(loadedOutputs -> safely(() -> new PatternRecord(tag.getUUID("PatternId"), source,
				loadedInputs, loadedOutputs, tag.getString("Address"))))));
	}

	public static CompoundTag saveQuery(PatternQuery value, HolderLookup.Provider registries) {
		CompoundTag tag = new CompoundTag();
		tag.putUUID("QueryId", value.queryId());
		tag.putUUID("RequesterComputerId", value.requesterComputerId());
		tag.putUUID("LogisticsId", value.logisticsId());
		tag.put("RequestedOutput", saveStackKey(value.requestedOutput(), registries));
		tag.putLong("Generation", value.passGeneration());
		tag.putInt("Cursor", value.cursor());
		return tag;
	}

	public static Optional<PatternQuery> loadQuery(CompoundTag tag, HolderLookup.Provider registries) {
		if (!hasExactKeys(tag, "QueryId", "RequesterComputerId", "LogisticsId", "RequestedOutput", "Generation", "Cursor")
			|| !hasUuid(tag, "QueryId") || !hasUuid(tag, "RequesterComputerId") || !hasUuid(tag, "LogisticsId")
			|| !hasType(tag, "RequestedOutput", Tag.TAG_COMPOUND) || !hasType(tag, "Generation", Tag.TAG_LONG)
			|| !hasType(tag, "Cursor", Tag.TAG_INT) || tag.getInt("Cursor") < 0)
			return Optional.empty();
		return loadStackKey(tag.getCompound("RequestedOutput"), registries).flatMap(output -> safely(() ->
			new PatternQuery(tag.getUUID("QueryId"), tag.getUUID("RequesterComputerId"), tag.getUUID("LogisticsId"),
				output, tag.getLong("Generation"), tag.getInt("Cursor"))));
	}

	public static CompoundTag saveReply(PatternReply value, HolderLookup.Provider registries) {
		CompoundTag tag = new CompoundTag();
		tag.putUUID("QueryId", value.queryId());
		tag.putUUID("RequesterComputerId", value.requesterComputerId());
		tag.putUUID("LogisticsId", value.logisticsId());
		tag.putLong("Generation", value.generation());
		tag.putString("Status", value.status().name());
		if (value.pattern() != null)
			tag.put("Pattern", saveRecord(value.pattern(), registries));
		return tag;
	}

	public static Optional<PatternReply> loadReply(CompoundTag tag, HolderLookup.Provider registries) {
		if (!hasUuid(tag, "QueryId") || !hasUuid(tag, "RequesterComputerId")
			|| !hasUuid(tag, "LogisticsId") || !hasType(tag, "Generation", Tag.TAG_LONG)
			|| !hasType(tag, "Status", Tag.TAG_STRING))
			return Optional.empty();
		return enumValue(PatternReplyStatus.class, tag.getString("Status")).flatMap(status -> {
			boolean containsPattern = tag.contains("Pattern");
			if ((status == PatternReplyStatus.MATCH) != containsPattern
				|| !hasExactKeys(tag, containsPattern ? new String[] { "QueryId", "RequesterComputerId",
					"LogisticsId", "Generation", "Status", "Pattern" }
					: new String[] { "QueryId", "RequesterComputerId", "LogisticsId", "Generation", "Status" })
				|| (containsPattern && !hasType(tag, "Pattern", Tag.TAG_COMPOUND)))
				return Optional.empty();
			Optional<PatternRecord> pattern = containsPattern
				? loadRecord(tag.getCompound("Pattern"), registries) : Optional.ofNullable(null);
			return containsPattern ? pattern.flatMap(value -> safely(() -> new PatternReply(tag.getUUID("QueryId"),
				tag.getUUID("RequesterComputerId"), tag.getUUID("LogisticsId"), tag.getLong("Generation"),
				status, value))) : safely(() -> new PatternReply(tag.getUUID("QueryId"),
					tag.getUUID("RequesterComputerId"), tag.getUUID("LogisticsId"), tag.getLong("Generation"),
					status, null));
		});
	}

	private static ListTag saveIngredients(List<PatternIngredient> values, HolderLookup.Provider registries) {
		ListTag tag = new ListTag();
		values.forEach(value -> tag.add(saveIngredient(value, registries)));
		return tag;
	}

	private static ListTag saveOutputs(List<PatternOutput> values, HolderLookup.Provider registries) {
		ListTag tag = new ListTag();
		values.forEach(value -> tag.add(saveOutput(value, registries)));
		return tag;
	}

	private static Optional<List<PatternIngredient>> loadIngredients(ListTag values, HolderLookup.Provider registries) {
		List<PatternIngredient> results = new java.util.ArrayList<>();
		for (Tag value : values) {
			if (!(value instanceof CompoundTag child))
				return Optional.empty();
			Optional<PatternIngredient> ingredient = loadIngredient(child, registries);
			if (ingredient.isEmpty())
				return Optional.empty();
			results.add(ingredient.orElseThrow());
		}
		return Optional.of(List.copyOf(results));
	}

	private static Optional<List<PatternOutput>> loadOutputs(ListTag values, HolderLookup.Provider registries) {
		List<PatternOutput> results = new java.util.ArrayList<>();
		for (Tag value : values) {
			if (!(value instanceof CompoundTag child))
				return Optional.empty();
			Optional<PatternOutput> output = loadOutput(child, registries);
			if (output.isEmpty())
				return Optional.empty();
			results.add(output.orElseThrow());
		}
		return Optional.of(List.copyOf(results));
	}

	private static boolean hasCompoundList(CompoundTag tag, String key) {
		if (!hasType(tag, key, Tag.TAG_LIST))
			return false;
		ListTag list = (ListTag) tag.get(key);
		return list.isEmpty() || list.getElementType() == Tag.TAG_COMPOUND;
	}

	private static boolean hasUuid(CompoundTag tag, String key) {
		return hasType(tag, key, Tag.TAG_INT_ARRAY) && tag.hasUUID(key);
	}

	private static boolean hasType(CompoundTag tag, String key, int type) {
		Tag value = tag.get(key);
		return value != null && value.getId() == type;
	}

	private static boolean hasExactKeys(CompoundTag tag, String... keys) {
		return tag.getAllKeys().size() == keys.length && java.util.Set.of(keys).equals(tag.getAllKeys());
	}

	private static <T> Optional<T> safely(java.util.function.Supplier<T> supplier) {
		try {
			return Optional.of(supplier.get());
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	private static <T> Optional<T> decode(Codec<T> codec, Tag tag, HolderLookup.Provider registries) {
		return codec.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag).result()
			.flatMap(value -> encode(codec, value, registries).filter(tag::equals).map(ignored -> value));
	}

	private static <T> Optional<CompoundTag> encode(Codec<T> codec, T value, HolderLookup.Provider registries) {
		return codec.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), value).result()
			.filter(CompoundTag.class::isInstance).map(CompoundTag.class::cast);
	}

	private static <E extends Enum<E>> Optional<E> enumValue(Class<E> type, String value) {
		try {
			return Optional.of(Enum.valueOf(type, value));
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}
}
