package com.nobodiiiii.createbiotech.content.buttercat;

import org.jetbrains.annotations.Nullable;

import com.nobodiiiii.createbiotech.content.buttercat.block.ButterCatEngineBlock;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.foundation.item.CBItemData;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.CatVariant;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

/**
 * Moves a cat's variant between boxed cats, butter cat block items and butter cat block
 * entities, so a cat keeps its breed no matter how often it changes form.
 */
public final class ButterCatVariants {
	/** Variant of a butter cat block entity, and of the item it was picked up as. */
	public static final String BLOCK_TAG = "catVariant";
	/** Variant key written by {@link net.minecraft.world.entity.animal.Cat} itself. */
	private static final String ENTITY_TAG = "variant";

	public static final ResourceKey<CatVariant> DEFAULT = CatVariant.TABBY;

	private ButterCatVariants() {}

	/** Returns the stored variant, or null when the tag holds none or an unknown one. */
	@Nullable
	public static ResourceKey<CatVariant> read(CompoundTag tag) {
		return tag.contains(BLOCK_TAG, Tag.TAG_STRING) ? parse(tag.getString(BLOCK_TAG)) : null;
	}

	public static void write(CompoundTag tag, ResourceKey<CatVariant> variant) {
		tag.putString(BLOCK_TAG, variant.location()
			.toString());
	}

	/**
	 * Returns the variant an item hands to the butter cat block it creates: the breed of the
	 * cat inside a captured entity box, or the breed a picked up butter cat block kept.
	 */
	@Nullable
	public static ResourceKey<CatVariant> ofPlacementItem(ItemStack stack) {
		ResourceKey<CatVariant> capturedVariant = ofCapturedCat(stack);
		return capturedVariant != null ? capturedVariant : ofBlockItem(stack);
	}

	/** Keeps the variant on a picked up butter cat block so placing it restores the same cat. */
	public static void saveToBlockItem(ItemStack stack, ResourceKey<CatVariant> variant) {
		if (!(stack.getItem() instanceof BlockItem blockItem)
			|| !(blockItem.getBlock() instanceof ButterCatEngineBlock))
			return;

		CBItemData.edit(stack, tag -> {
			if (DEFAULT.equals(variant))
				tag.remove(BLOCK_TAG);
			else
				write(tag, variant);
		});
	}

	/** Breed a butter cat block item renders as and places with. */
	public static ResourceKey<CatVariant> ofBlockItemOrDefault(ItemStack stack) {
		ResourceKey<CatVariant> variant = ofBlockItem(stack);
		return variant != null ? variant : DEFAULT;
	}

	@Nullable
	private static ResourceKey<CatVariant> ofCapturedCat(ItemStack stack) {
		CompoundTag catData = CapturedEntityBoxHelper.getCapturedEntityData(stack, EntityType.CAT);
		return catData == null ? null : parse(catData.getString(ENTITY_TAG));
	}

	@Nullable
	private static ResourceKey<CatVariant> ofBlockItem(ItemStack stack) {
		CompoundTag tag = CBItemData.getReadOnly(stack);
		return tag == null ? null : read(tag);
	}

	@Nullable
	private static ResourceKey<CatVariant> parse(String variantId) {
		ResourceLocation location = ResourceLocation.tryParse(variantId);
		if (location == null)
			return null;

		ResourceKey<CatVariant> variant = ResourceKey.create(Registries.CAT_VARIANT, location);
		return BuiltInRegistries.CAT_VARIANT.containsKey(variant) ? variant : null;
	}
}
