package com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect;

import java.util.Objects;

/** Supported effects are still subject to every live host, fence and durable-material check. */
public final class ProductionEffectPolicy {
	private ProductionEffectPolicy() {}

	public static boolean supported(ProductionEffect intent) {
		return switch (Objects.requireNonNull(intent, "intent")) {
			case ProductionEffect.CreateDispatch ignored -> true;
			case ProductionEffect.StockSampleRequest ignored -> true;
			case ProductionEffect.OutputPackageRequest ignored -> true;
			case ProductionEffect.PanelSnapshotPublication ignored -> true;
			case ProductionEffect.LibraryQuery ignored -> false;
			case ProductionEffect.RadioSend ignored -> false;
			case ProductionEffect.AgentTicketOffer ignored -> false;
		};
	}
}
