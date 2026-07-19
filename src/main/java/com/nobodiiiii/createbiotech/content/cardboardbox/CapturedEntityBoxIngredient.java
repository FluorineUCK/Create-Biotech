package com.nobodiiiii.createbiotech.content.cardboardbox;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.nobodiiiii.createbiotech.registry.CBIngredients;
import com.nobodiiiii.createbiotech.registry.CBItems;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.common.crafting.ICustomIngredient;
import net.neoforged.neoforge.common.crafting.IngredientType;

public class CapturedEntityBoxIngredient implements ICustomIngredient {
	public static final MapCodec<CapturedEntityBoxIngredient> CODEC = RecordCodecBuilder.mapCodec(instance ->
		instance.group(
			BuiltInRegistries.ENTITY_TYPE.byNameCodec().fieldOf("entity")
				.forGetter(CapturedEntityBoxIngredient::getEntityType),
			BuiltInRegistries.ITEM.byNameCodec().optionalFieldOf("item")
				.forGetter(ingredient -> ingredient.items.size() == 1
					? Optional.of(ingredient.items.iterator().next()) : Optional.empty()),
			BuiltInRegistries.ITEM.byNameCodec().listOf().optionalFieldOf("items", List.of())
				.forGetter(ingredient -> ingredient.items.size() > 1 ? List.copyOf(ingredient.items) : List.of())
		).apply(instance, CapturedEntityBoxIngredient::decode));

	private final Set<Item> items;
	private final EntityType<?> entityType;

	private CapturedEntityBoxIngredient(Set<Item> items, EntityType<?> entityType) {
		this.items = Collections.unmodifiableSet(items);
		this.entityType = entityType;
		for (Item item : items)
			requireBoxItem(item);
	}

	private static CapturedEntityBoxIngredient decode(EntityType<?> entityType, Optional<Item> item,
		List<Item> items) {
		Set<Item> decodedItems = item.map(Set::of)
			.orElseGet(() -> items.isEmpty() ? defaultItems() : Set.copyOf(items));
		return new CapturedEntityBoxIngredient(decodedItems, entityType);
	}

	public static CapturedEntityBoxIngredient of(EntityType<?> entityType) {
		return new CapturedEntityBoxIngredient(defaultItems(), entityType);
	}

	public static CapturedEntityBoxIngredient of(EntityType<?> entityType, Item... items) {
		return new CapturedEntityBoxIngredient(Arrays.stream(items).collect(Collectors.toSet()), entityType);
	}

	public EntityType<?> getEntityType() {
		return entityType;
	}

	private static Set<Item> defaultItems() {
		return Set.of(CBItems.CARDBOARD_BOX.get(), CBItems.LARGE_CARDBOARD_BOX.get());
	}

	@Override
	public Stream<ItemStack> getItems() {
		return items.stream()
			.map(Item::getDefaultInstance)
			.map(stack -> createDisplayStack(stack, entityType));
	}

	@Override
	public boolean test(ItemStack stack) {
		return !stack.isEmpty()
			&& items.contains(stack.getItem())
			&& CapturedEntityBoxHelper.containsEntityType(stack, entityType);
	}

	@Override
	public boolean isSimple() {
		return false;
	}

	@Override
	public IngredientType<?> getType() {
		return CBIngredients.CAPTURED_ENTITY_BOX.get();
	}

	private static ItemStack createDisplayStack(ItemStack stack, EntityType<?> entityType) {
		ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
		if (entityId == null)
			return stack;

		CompoundTag tag = new CompoundTag();
		CompoundTag entityData = new CompoundTag();
		entityData.putString("id", entityId.toString());
		tag.put("CapturedEntity", entityData);
		tag.putString("CapturedEntityDescId", entityType.getDescriptionId());
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return stack;
	}

	private static Item requireBoxItem(Item item) {
		if (!(item instanceof CapturedEntityBoxItem))
			throw new IllegalArgumentException("Item " + BuiltInRegistries.ITEM.getKey(item)
				+ " is not a captured entity box item");
		return item;
	}

	@Override
	public boolean equals(Object object) {
		return this == object || object instanceof CapturedEntityBoxIngredient other
			&& entityType == other.entityType
			&& items.equals(other.items);
	}

	@Override
	public int hashCode() {
		return Objects.hash(items, entityType);
	}
}
