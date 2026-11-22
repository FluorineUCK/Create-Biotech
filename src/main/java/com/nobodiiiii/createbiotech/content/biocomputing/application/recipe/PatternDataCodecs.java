package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Recipe facts only; production queries and replies have a separate owner. */
public final class PatternDataCodecs {
	private static final int MAX_PATTERN_ENTRIES = PatternRepresentationBounds.MAX_ENTRIES;
	private PatternDataCodecs() {}

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
		return RecipeSourceLocation.tryLoad(tag.getCompound("Shelf"))
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
		tag.putInt("Version", 2);
		tag.putUUID("PatternId", value.patternId());
		tag.put("Source", savePageKey(value.source()));
		tag.put("Inputs", saveIngredients(value.inputs(), registries));
		tag.put("Outputs", saveOutputs(value.outputs(), registries));
		tag.putString("Type", value.kind().serializedName());
		tag.put("Body", saveBody(value.body(), registries));
		value.priorityOverride().ifPresent(priority -> tag.putInt("PriorityOverride", priority));
		return tag;
	}

	public static Optional<PatternRecord> loadRecord(CompoundTag tag, HolderLookup.Provider registries) {
		boolean priority = tag.contains("PriorityOverride");
		if (!hasExactKeys(tag, priority
			? new String[] { "Version", "PatternId", "Source", "Inputs", "Outputs", "Type", "Body", "PriorityOverride" }
			: new String[] { "Version", "PatternId", "Source", "Inputs", "Outputs", "Type", "Body" })
			|| !hasType(tag, "Version", Tag.TAG_INT) || tag.getInt("Version") != 2
			|| !hasType(tag, "PatternId", Tag.TAG_INT_ARRAY) || !tag.hasUUID("PatternId")
			|| !hasType(tag, "Source", Tag.TAG_COMPOUND) || !hasType(tag, "Inputs", Tag.TAG_LIST)
			|| !hasType(tag, "Outputs", Tag.TAG_LIST) || !hasType(tag, "Type", Tag.TAG_STRING)
			|| !hasType(tag, "Body", Tag.TAG_COMPOUND)
			|| (priority && !hasType(tag, "PriorityOverride", Tag.TAG_INT)))
			return Optional.empty();
		if (!hasCompoundList(tag, "Inputs") || !hasCompoundList(tag, "Outputs"))
			return Optional.empty();
		EntryBudget budget = new EntryBudget();
		Optional<List<PatternIngredient>> inputs = loadIngredients((ListTag) tag.get("Inputs"), registries, budget);
		Optional<List<PatternOutput>> outputs = loadOutputs((ListTag) tag.get("Outputs"), registries, budget);
		Optional<PatternBody> body = loadBody(tag.getString("Type"), tag.getCompound("Body"), registries, budget);
		OptionalInt loadedPriority = priority ? OptionalInt.of(tag.getInt("PriorityOverride")) : OptionalInt.empty();
		return loadPageKey(tag.getCompound("Source")).flatMap(source -> inputs.flatMap(loadedInputs ->
			outputs.flatMap(loadedOutputs -> body.flatMap(loadedBody -> safely(() -> new PatternRecord(
				tag.getUUID("PatternId"), source, loadedInputs, loadedOutputs, loadedBody, loadedPriority))))));
	}

	public static CompoundTag saveUnsupported(UnsupportedPatternEnvelope value) {
		CompoundTag tag = new CompoundTag();
		tag.putInt("Version", value.version());
		tag.putString("Type", value.type());
		tag.put("Source", savePageKey(value.source()));
		tag.putString("Fingerprint", value.fingerprint());
		tag.putString("RawText", value.rawText());
		return tag;
	}

	public static Optional<UnsupportedPatternEnvelope> loadUnsupported(CompoundTag tag) {
		if (!hasExactKeys(tag, "Version", "Type", "Source", "Fingerprint", "RawText")
			|| !hasType(tag, "Version", Tag.TAG_INT) || !hasType(tag, "Type", Tag.TAG_STRING)
			|| !hasType(tag, "Source", Tag.TAG_COMPOUND) || !hasType(tag, "Fingerprint", Tag.TAG_STRING)
			|| !hasType(tag, "RawText", Tag.TAG_STRING)
			|| tag.getString("RawText").length() > UnsupportedPatternEnvelope.MAX_RAW_TEXT_LENGTH)
			return Optional.empty();
		return loadPageKey(tag.getCompound("Source")).flatMap(source -> safely(() ->
			new UnsupportedPatternEnvelope(tag.getInt("Version"), tag.getString("Type"), source,
				tag.getString("Fingerprint"), tag.getString("RawText"))));
	}

	private static ListTag saveIngredients(List<PatternIngredient> values, HolderLookup.Provider registries) {
		ListTag tag = new ListTag();
		values.forEach(value -> tag.add(PatternItemCodecs.saveIngredient(value, registries)));
		return tag;
	}

	private static ListTag saveOutputs(List<PatternOutput> values, HolderLookup.Provider registries) {
		ListTag tag = new ListTag();
		values.forEach(value -> tag.add(PatternItemCodecs.saveOutput(value, registries)));
		return tag;
	}

	private static CompoundTag saveBody(PatternBody body, HolderLookup.Provider registries) {
		CompoundTag tag = new CompoundTag();
		if (body instanceof StandardPatternBody standard) {
			tag.putString("Address", standard.address());
			if (standard.grid().shaped()) tag.putIntArray("CraftingSlots", standard.grid().cells());
			return tag;
		}
		ListTag stages = new ListTag();
		for (PatternStage stage : ((SequencedPatternBody) body).stages()) {
			CompoundTag child = new CompoundTag();
			child.putInt("Index", stage.index());
			child.putString("Address", stage.address());
			child.put("Inputs", saveIngredients(stage.inputs(), registries));
			stages.add(child);
		}
		tag.put("Stages", stages);
		return tag;
	}

	private static Optional<PatternBody> loadBody(String serializedKind, CompoundTag tag,
		HolderLookup.Provider registries, EntryBudget budget) {
		return PatternKind.fromSerializedName(serializedKind).flatMap(kind -> {
			if (kind == PatternKind.STANDARD && tag.contains("CraftingSlots")) {
				if (!hasExactKeys(tag, "Address", "CraftingSlots") || !hasType(tag, "Address", Tag.TAG_STRING)
					|| !hasType(tag, "CraftingSlots", Tag.TAG_INT_ARRAY)) return Optional.empty();
				int[] cells = tag.getIntArray("CraftingSlots");
				if (cells.length == 0 || cells.length > CraftingSlots.CAPACITY) return Optional.empty();
				return safely(() -> (PatternBody)new StandardPatternBody(tag.getString("Address"),
					new CraftingSlots(java.util.Arrays.stream(cells).boxed().toList())));
			}
			if (kind == PatternKind.STANDARD)
				return hasExactKeys(tag, "Address") && hasType(tag, "Address", Tag.TAG_STRING)
					? safely(() -> (PatternBody) new StandardPatternBody(tag.getString("Address")))
					: Optional.empty();
			if (!hasExactKeys(tag, "Stages") || !hasCompoundList(tag, "Stages"))
				return Optional.empty();
			ListTag encodedStages = (ListTag) tag.get("Stages");
			if (!budget.consume(encodedStages.size()))
				return Optional.empty();
			List<PatternStage> stages = new java.util.ArrayList<>();
			for (Tag value : encodedStages) {
				CompoundTag child = (CompoundTag) value;
				if (!hasExactKeys(child, "Index", "Address", "Inputs")
					|| !hasType(child, "Index", Tag.TAG_INT) || !hasType(child, "Address", Tag.TAG_STRING)
					|| !hasCompoundList(child, "Inputs"))
					return Optional.empty();
				Optional<List<PatternIngredient>> inputs = loadIngredients((ListTag) child.get("Inputs"),
					registries, budget);
				if (inputs.isEmpty())
					return Optional.empty();
				Optional<PatternStage> stage = safely(() -> new PatternStage(child.getInt("Index"),
					child.getString("Address"), inputs.orElseThrow()));
				if (stage.isEmpty())
					return Optional.empty();
				stages.add(stage.orElseThrow());
			}
			return safely(() -> (PatternBody) new SequencedPatternBody(stages));
		});
	}

	private static Optional<List<PatternIngredient>> loadIngredients(ListTag values,
		HolderLookup.Provider registries, EntryBudget budget) {
		if (!budget.consume(values.size()))
			return Optional.empty();
		List<PatternIngredient> results = new java.util.ArrayList<>();
		for (Tag value : values) {
			if (!(value instanceof CompoundTag child))
				return Optional.empty();
			Optional<PatternIngredient> ingredient = PatternItemCodecs.loadIngredient(child, registries);
			if (ingredient.isEmpty())
				return Optional.empty();
			results.add(ingredient.orElseThrow());
		}
		return Optional.of(List.copyOf(results));
	}

	private static Optional<List<PatternOutput>> loadOutputs(ListTag values,
		HolderLookup.Provider registries, EntryBudget budget) {
		if (!budget.consume(values.size()))
			return Optional.empty();
		List<PatternOutput> results = new java.util.ArrayList<>();
		for (Tag value : values) {
			if (!(value instanceof CompoundTag child))
				return Optional.empty();
			Optional<PatternOutput> output = PatternItemCodecs.loadOutput(child, registries);
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

	private static <E extends Enum<E>> Optional<E> enumValue(Class<E> type, String value) {
		try {
			return Optional.of(Enum.valueOf(type, value));
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	private static final class EntryBudget {
		private int remaining = MAX_PATTERN_ENTRIES;

		private boolean consume(int entries) {
			if (entries < 0 || entries > remaining)
				return false;
			remaining -= entries;
			return true;
		}
	}
}
