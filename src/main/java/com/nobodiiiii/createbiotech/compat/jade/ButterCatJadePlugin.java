package com.nobodiiiii.createbiotech.compat.jade;

import com.nobodiiiii.createbiotech.content.buttercat.block.ButterCatEngineBlock;
import com.nobodiiiii.createbiotech.content.buttercat.block.ButterCatEngineBlockEntity;

import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

@WailaPlugin
public class ButterCatJadePlugin implements IWailaPlugin {
	@Override
	public void register(IWailaCommonRegistration registration) {
		registration.registerBlockDataProvider(ButterCatComponentProvider.INSTANCE,
			ButterCatEngineBlockEntity.class);
	}

	@Override
	public void registerClient(IWailaClientRegistration registration) {
		registration.registerBlockIcon(ButterCatComponentProvider.INSTANCE, ButterCatEngineBlock.class);
		registration.registerBlockComponent(ButterCatComponentProvider.INSTANCE, ButterCatEngineBlock.class);
	}
}
