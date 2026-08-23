package com.nobodiiiii.createbiotech.content.cardboardbox;

import java.util.List;
import java.util.function.Consumer;

import com.nobodiiiii.createbiotech.content.universaljoint.UniversalJointRepair;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalAssemblyBoxHelper;
import com.simibubi.create.foundation.item.render.SimpleCustomRenderer;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.box.PackageStyles;
import com.simibubi.create.content.logistics.box.PackageStyles.PackageStyle;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

public abstract class CapturedEntityBoxItem extends PackageItem {
	private static final int EMPTY_BOX_MAX_STACK_SIZE = 16;

	private final String descriptionId;

	protected CapturedEntityBoxItem(Properties properties, String descriptionId, PackageStyle style) {
		super(properties, style);
		this.descriptionId = descriptionId;
		PackageStyles.ALL_BOXES.remove(this);
		PackageStyles.STANDARD_BOXES.remove(this);
		PackageStyles.RARE_BOXES.remove(this);
	}

	@Override
	public String getDescriptionId() {
		return descriptionId;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents,
		TooltipFlag isAdvanced) {
		CapturedEntityBoxHelper.appendHoverText(stack, tooltipComponents);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Player player = context.getPlayer();
		if (player == null)
			return InteractionResult.PASS;

		ItemStack stack = context.getItemInHand();
		if (!player.isShiftKeyDown()) {
			InteractionResult repairResult = UniversalJointRepair.useOn(context);
			if (repairResult != InteractionResult.PASS)
				return repairResult;
			return InteractionResult.PASS;
		}
		Level level = context.getLevel();
		if (!level.isClientSide())
			UniversalJointRepair.clearSelection(stack);
		if (!hasCapturedEntity(stack))
			if (!SurgicalAssemblyBoxHelper.hasAssembly(stack))
				return InteractionResult.PASS;

		if (!level.isClientSide()) {
			if (SurgicalAssemblyBoxHelper.hasAssembly(stack))
				SurgicalAssemblyBoxHelper.release(context);
			else
				CapturedEntityBoxHelper.releaseCapturedEntity(context);
		}
		return InteractionResult.sidedSuccess(level.isClientSide());
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		return InteractionResultHolder.pass(player.getItemInHand(hand));
	}

	@Override
	public boolean hasCustomEntity(ItemStack stack) {
		return hasAnyContents(stack);
	}

	@Override
	public Entity createEntity(Level world, Entity location, ItemStack itemstack) {
		return hasAnyContents(itemstack)
			? CardboardBoxEntity.fromDroppedItem(world, location, itemstack) : null;
	}

	@Override
	public boolean hasCraftingRemainingItem(ItemStack stack) {
		return hasAnyContents(stack);
	}

	@Override
	public ItemStack getCraftingRemainingItem(ItemStack stack) {
		if (!hasAnyContents(stack))
			return ItemStack.EMPTY;

		ItemStack remainder = stack.copy();
		remainder.setCount(1);
		CapturedEntityBoxHelper.clearCapturedEntity(remainder);
		SurgicalAssemblyBoxHelper.clear(remainder);
		return remainder;
	}

	@Override
	public int getMaxStackSize(ItemStack stack) {
		return hasAnyContents(stack) ? 1 : EMPTY_BOX_MAX_STACK_SIZE;
	}

	public static boolean hasCapturedEntity(ItemStack stack) {
		return CapturedEntityBoxHelper.hasCapturedEntity(stack);
	}

	public static boolean isBox(ItemStack stack) {
		return stack.getItem() instanceof CapturedEntityBoxItem;
	}

	public static boolean hasAnyContents(ItemStack stack) {
		return hasCapturedEntity(stack) || SurgicalAssemblyBoxHelper.hasAssembly(stack);
	}

	@Override
	@OnlyIn(Dist.CLIENT)
	public void initializeClient(Consumer<IClientItemExtensions> consumer) {
		consumer.accept(SimpleCustomRenderer.create(this, new CapturedEntityBoxItemRenderer()));
	}
}
