package com.nobodiiiii.createbiotech.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.nobodiiiii.createbiotech.content.slimebelt.transport.SlimeBeltTunnelCapabilityInvalidator;
import com.simibubi.create.content.logistics.tunnel.BeltTunnelBlockEntity;

import net.neoforged.neoforge.items.IItemHandler;

@Mixin(BeltTunnelBlockEntity.class)
public abstract class BeltTunnelBlockEntityCapabilityMixin implements SlimeBeltTunnelCapabilityInvalidator {

	@Shadow(remap = false)
	protected IItemHandler cap;

	@Override
	public void createBiotech$clearItemCapability() {
		cap = null;
	}
}
