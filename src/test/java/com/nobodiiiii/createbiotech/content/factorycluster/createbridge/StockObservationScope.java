package com.nobodiiiii.createbiotech.content.factorycluster.createbridge;

import java.util.Objects;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

/** Absolute, frozen target used to credit a bare Create delivery from actual stock. */
public sealed interface StockObservationScope {
	record NetworkSummary(UUID logisticsNetworkId, StackKey item, long targetLevel)
		implements StockObservationScope {
		public NetworkSummary {
			logisticsNetworkId = Objects.requireNonNull(logisticsNetworkId,
				"logisticsNetworkId");
			item = Objects.requireNonNull(item, "item");
			if (targetLevel < 0)
				throw new IllegalArgumentException("Stock target cannot be negative");
		}
	}

	record AttachedInventory(SpaceAddress packager, InventoryIdentity inventory,
		StackKey item, long targetLevel) implements StockObservationScope {
		public AttachedInventory {
			packager = Objects.requireNonNull(packager, "packager");
			inventory = Objects.requireNonNull(inventory, "inventory");
			item = Objects.requireNonNull(item, "item");
			if (targetLevel < 0)
				throw new IllegalArgumentException("Stock target cannot be negative");
		}
	}
}
