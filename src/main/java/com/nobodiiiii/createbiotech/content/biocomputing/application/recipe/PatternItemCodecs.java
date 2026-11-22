package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import java.util.Optional;

import com.mojang.serialization.Codec;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;

/** Canonical recipe item values; no source pages, index ownership or production request state. */
public final class PatternItemCodecs {
	private PatternItemCodecs() {}

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

}
