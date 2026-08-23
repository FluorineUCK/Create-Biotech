package com.nobodiiiii.createbiotech.content.surgery;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxItem;
import com.nobodiiiii.createbiotech.entity.SlimeBionicEntity;
import com.nobodiiiii.createbiotech.foundation.item.CBItemData;
import com.nobodiiiii.createbiotech.registry.CBEntityTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class SurgicalAssemblyBoxHelper {
	private static final String ASSEMBLY_TAG = "SurgicalAssembly";

	private SurgicalAssemblyBoxHelper() {}

	public static boolean hasAssembly(ItemStack stack) {
		return read(stack) != null;
	}

	public static boolean hasAnyBiologicalContents(ItemStack stack) {
		return CapturedEntityBoxHelper.hasCapturedEntity(stack) || hasAssembly(stack);
	}

	@Nullable
	public static SurgicalAssembly read(ItemStack stack) {
		CompoundTag root = CBItemData.getReadOnly(stack);
		if (root == null || !root.contains(ASSEMBLY_TAG, Tag.TAG_COMPOUND))
			return null;
		return SurgicalAssembly.load(root.getCompound(ASSEMBLY_TAG));
	}

	public static boolean write(ItemStack stack, SurgicalAssembly assembly) {
		if (!(stack.getItem() instanceof CapturedEntityBoxItem)
			|| CapturedEntityBoxHelper.hasCapturedEntity(stack) || hasAssembly(stack))
			return false;
		CBItemData.edit(stack, root -> root.put(ASSEMBLY_TAG, assembly.save()));
		return true;
	}

	public static void clear(ItemStack stack) {
		CBItemData.edit(stack, root -> root.remove(ASSEMBLY_TAG));
	}

	public static boolean fillFromPlayerStack(ItemStack stack, Player player, SurgicalAssembly assembly) {
		if (stack.getCount() <= 1)
			return write(stack, assembly);

		ItemStack filled = stack.copyWithCount(1);
		if (!write(filled, assembly))
			return false;
		stack.shrink(1);
		if (!player.getInventory().add(filled))
			player.drop(filled, false);
		return true;
	}

	public static boolean release(UseOnContext context) {
		ItemStack stack = context.getItemInHand();
		SurgicalAssembly assembly = read(stack);
		if (assembly == null)
			return false;

		Level level = context.getLevel();
		SlimeBionicEntity entity = CBEntityTypes.SLIME_BIONIC.get().create(level);
		if (entity == null)
			return false;

		BlockPos clickedPos = context.getClickedPos();
		Direction face = context.getClickedFace();
		BlockState clickedState = level.getBlockState(clickedPos);
		BlockPos spawnPos = clickedState.getCollisionShape(level, clickedPos).isEmpty()
			? clickedPos : clickedPos.relative(face);
		entity.setAssembly(assembly);
		entity.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
			context.getHorizontalDirection().toYRot(), 0.0f);
		if (!level.addFreshEntity(entity))
			return false;

		clear(stack);
		return true;
	}

	public static void appendTooltip(ItemStack stack, List<Component> tooltip) {
		SurgicalAssembly assembly = read(stack);
		if (assembly == null)
			return;
		tooltip.add(Component.translatable("item.create_biotech.cardboard_box.surgical_contents",
			Component.translatable("entity.create_biotech.slime_bionic")));
	}
}
