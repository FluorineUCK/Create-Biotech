package com.nobodiiiii.createbiotech.content.wirelessterminal;

import com.nobodiiiii.createbiotech.mixin.StockKeeperRequestMenuAccessor;
import com.nobodiiiii.createbiotech.registry.CBMenuTypes;
import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestMenu;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntity;

public class WirelessStockKeeperRequestMenu extends StockKeeperRequestMenu {

	public WirelessStockKeeperRequestMenu(int id, Inventory inv, RegistryFriendlyByteBuf extraData) {
		this(CBMenuTypes.WIRELESS_STOCK_KEEPER_REQUEST.get(), id, inv, extraData);
	}

	public WirelessStockKeeperRequestMenu(MenuType<?> type, int id, Inventory inv, RegistryFriendlyByteBuf extraData) {
		super(type, id, inv, extraData);
	}

	public WirelessStockKeeperRequestMenu(MenuType<?> type, int id, Inventory inv, StockTickerBlockEntity contentHolder) {
		super(type, id, inv, contentHolder);
	}

	public static AbstractContainerMenu create(int containerId, Inventory playerInventory,
		StockTickerBlockEntity stockTickerBlockEntity) {
		return new WirelessStockKeeperRequestMenu(CBMenuTypes.WIRELESS_STOCK_KEEPER_REQUEST.get(), containerId,
			playerInventory, stockTickerBlockEntity);
	}

	@Override
	protected StockTickerBlockEntity createOnClient(RegistryFriendlyByteBuf extraData) {
		StockKeeperRequestMenuAccessor accessor = (StockKeeperRequestMenuAccessor) this;
		accessor.createBiotech$setAdmin(extraData.readBoolean());
		accessor.createBiotech$setLocked(extraData.readBoolean());

		BlockPos targetPos = extraData.readBlockPos();
		CompoundTag updateTag = extraData.readNbt();
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null)
			return null;

		BlockEntity existing = level.getBlockEntity(targetPos);
		StockTickerBlockEntity stockTicker;
		if (existing instanceof StockTickerBlockEntity existingStockTicker) {
			stockTicker = existingStockTicker;
		} else {
			stockTicker = new StockTickerBlockEntity(AllBlockEntityTypes.STOCK_TICKER.get(), targetPos,
				AllBlocks.STOCK_TICKER.getDefaultState());
			stockTicker.setLevel(level);
		}

		if (updateTag != null)
			stockTicker.handleUpdateTag(updateTag, level.registryAccess());
		return stockTicker;
	}

	@Override
	public boolean stillValid(Player player) {
		if (contentHolder == null || contentHolder.isRemoved())
			return false;
		return player.level().isClientSide || contentHolder.isKeeperPresent();
	}
}
