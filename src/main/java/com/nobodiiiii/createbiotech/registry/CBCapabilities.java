package com.nobodiiiii.createbiotech.registry;

import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

public final class CBCapabilities {

	private CBCapabilities() {}

	public static void register(RegisterCapabilitiesEvent event) {
		event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, CBBlockEntityTypes.BIO_PACKAGER.get(),
			(be, side) -> be.getItemCapability(side));
		event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, CBBlockEntityTypes.SHULKER_PACKAGER.get(),
			(be, side) -> be.shulkerInventory);
		event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, CBBlockEntityTypes.EVOKER_ENCHANTING_CHAMBER.get(),
			(be, side) -> be.getItemCapability(side));
		event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, CBBlockEntityTypes.EVOKER_ENCHANTING_CHAMBER.get(),
			(be, side) -> be.getFluidCapability(side));
		event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, CBBlockEntityTypes.BUDDING_EXPERIENCE.get(),
			(be, side) -> be.getFluidCapability(side));
		event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, CBBlockEntityTypes.EXPERIENCE_PUMP.get(),
			(be, side) -> be.getFluidCapability(side));
		event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, CBBlockEntityTypes.NETHER_PORTAL_FLUID.get(),
			(be, side) -> be.getFluidCapability(side));
		event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, CBBlockEntityTypes.FIXED_CARROT_FISHING_ROD.get(),
			(be, side) -> be.getItemCapability(side));
		event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, CBBlockEntityTypes.CREEPER_BLAST_CHAMBER.get(),
			(be, side) -> be.getItemCapability(side));
		event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, CBBlockEntityTypes.EXPLOSION_PROOF_ITEM_VAULT.get(),
			(be, side) -> be.getItemCapability(side));
		event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, CBBlockEntityTypes.SPIDER_ASSEMBLY_TABLE.get(),
			(be, side) -> be.getItemCapability(side));
		event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, CBBlockEntityTypes.SPIDER_ASSEMBLY_TABLE.get(),
			(be, side) -> be.getFluidCapability(side));
		event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, CBBlockEntityTypes.PETRI_DISH.get(),
			(be, side) -> be.getItemCapability(side));
		event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, CBBlockEntityTypes.PETRI_DISH.get(),
			(be, side) -> be.getFluidCapability(side));
		event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, CBBlockEntityTypes.SQUID_PRINTER.get(),
			(be, side) -> be.getFluidCapability(side));
		event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, CBBlockEntityTypes.SLIME_BELT.get(),
			(be, side) -> be.getItemCapability(side));
		event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, CBBlockEntityTypes.MAGMA_BELT.get(),
			(be, side) -> be.getItemCapability(side));
		event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, CBBlockEntityTypes.ALLAY_PORT.get(),
			(be, side) -> be.getItemHandler(side));
	}
}
