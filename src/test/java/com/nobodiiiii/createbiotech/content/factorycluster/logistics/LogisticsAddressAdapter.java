package com.nobodiiiii.createbiotech.content.factorycluster.logistics;

import java.util.List;
import java.util.Optional;

import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;

import net.minecraft.world.level.block.entity.BlockEntity;

/** One explicit, type-focused Create address extraction boundary. */
public interface LogisticsAddressAdapter {
	boolean supports(BlockEntity blockEntity);

	List<ObservedAddress> observe(BlockEntity blockEntity,
		Optional<LogisticallyLinkedBehaviour> directLink,
		LogisticsNetworkAssociation association);
}
