package com.nobodiiiii.createbiotech.compat.jade;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.buttercat.block.ButterCatEngineBlockEntity;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBItems;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec2;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.JadeIds;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.theme.IThemeHelper;
import snownee.jade.api.ui.IElement;
import snownee.jade.api.ui.IElementHelper;

public enum ButterCatComponentProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
	INSTANCE;

	private static final ResourceLocation ID = CreateBiotech.asResource("butter_cat_engine");
	private static final String INFINITE = "infinite";
	private static final String BUTTER_COUNT = "butterCount";
	private static final String MAX_BUTTER_COUNT = "maxButterCount";
	private static final String REMAINING = "cd";

	@Override
	public IElement getIcon(BlockAccessor accessor, IPluginConfig config, IElement currentIcon) {
		ItemStack iconStack = accessor.getBlockState().is(CBBlocks.BUTTER_CAT_ENGINE.get())
			? new ItemStack(CBItems.BUTTER_CAT_ENGINE.get())
			: new ItemStack(CBItems.CUTE_CAT_ON_SHAFT.get());
		return IElementHelper.get().item(iconStack, 0.5F);
	}

	@Override
	public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
		CompoundTag data = accessor.getServerData();
		replaceTitle(tooltip, accessor);

		IElementHelper elements = IElementHelper.get();
		IElement butter = itemElement(elements, new ItemStack(CBItems.BUTTER.get()));
		IElement superButter = itemElement(elements, new ItemStack(CBItems.SUPER_BUTTER.get()));
		IElement clock = itemElement(elements, new ItemStack(Items.CLOCK));
		if (!accessor.getBlockState().is(CBBlocks.BUTTER_CAT_ENGINE.get()))
			return;

		if (data.getBoolean(INFINITE)) {
			tooltip.add(superButter);
			tooltip.append(Component.translatable("item.create_biotech.butter").append(":"));
			tooltip.append(IThemeHelper.get().info(Component.translatable("jade.infinity")));
			return;
		}

		tooltip.add(butter);
		tooltip.append(Component.translatable("item.create_biotech.butter").append(":"));
		int butterCount = data.getInt(BUTTER_COUNT);
		int maxButterCount = data.getInt(MAX_BUTTER_COUNT);
		tooltip.append(Component.literal(butterCount > maxButterCount ? "§c" : "§f")
			.append(String.format("%d/%d", butterCount, maxButterCount)));
		if (butterCount > 0) {
			tooltip.add(clock);
			tooltip.append(Component.translatable("string.create_biotech.remaining"));
			tooltip.append(IThemeHelper.get().seconds(data.getInt(REMAINING), 20.0F));
		}
	}

	private static IElement itemElement(IElementHelper elements, ItemStack stack) {
		IElement element = elements.item(stack, 0.5F).size(new Vec2(10, 10)).translate(new Vec2(0, -1));
		element.message(null);
		return element;
	}

	private static void replaceTitle(ITooltip tooltip, BlockAccessor accessor) {
		if (tooltip.get(JadeIds.CORE_OBJECT_NAME).isEmpty())
			return;
		MutableComponent title = Component.translatable(accessor.getBlockState().is(CBBlocks.BUTTER_CAT_ENGINE.get())
			? "block.create_biotech.butter_cat_engine"
			: "block.create_biotech.cute_cat_on_shaft");
		tooltip.remove(JadeIds.CORE_OBJECT_NAME);
		tooltip.add(0, IThemeHelper.get().title(title), JadeIds.CORE_OBJECT_NAME);
	}

	@Override
	public void appendServerData(CompoundTag data, BlockAccessor accessor) {
		ButterCatEngineBlockEntity blockEntity = (ButterCatEngineBlockEntity) accessor.getBlockEntity();
		data.putBoolean(INFINITE, blockEntity.isInfinite());
		data.putInt(BUTTER_COUNT, blockEntity.getTotalCount());
		data.putInt(MAX_BUTTER_COUNT, blockEntity.getMaxButterCount());
		data.putInt(REMAINING, blockEntity.getCd(true));
	}

	@Override
	public ResourceLocation getUid() {
		return ID;
	}
}
