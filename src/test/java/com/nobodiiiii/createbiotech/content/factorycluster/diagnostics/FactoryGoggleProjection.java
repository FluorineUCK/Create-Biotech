package com.nobodiiiii.createbiotech.content.factorycluster.diagnostics;

import com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleState;

import java.util.List;
import java.util.Objects;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;

/** Small immutable normal-view values: no authority, full identity, catalog, or world object. */
public sealed interface FactoryGoggleProjection {
	record Computer(String cluster, String role, String formation, String profession, int level,
			int usedSlots, int totalSlots, String primary, List<String> listeners, String state)
			implements FactoryGoggleProjection {
		public Computer {
			frequency(primary);
			listeners = List.copyOf(listeners);
			listeners.forEach(FactoryGoggleProjection::frequency);
			if (listeners.size() > ProductionResourceProfile.WANDERING_TRADER_PROCESS_SLOTS || level < 0
					|| level > 5 || usedSlots < 0 || totalSlots < 0)
				throw new IllegalArgumentException("invalid Computer projection");
			cluster = text(cluster); role = text(role); formation = text(formation);
			profession = text(profession); state = text(state);
		}
	}
	record Core(String formation, String frequency, int priority, int validCache, int indexedPages,
			int pages, int queuedPages) implements FactoryGoggleProjection {
		public Core {
			FactoryGoggleProjection.frequency(frequency);
			formation = text(formation);
			if (validCache < 0 || indexedPages < 0 || pages < indexedPages || queuedPages < 0)
				throw new IllegalArgumentException("invalid Core projection");
		}
	}
	record Panel(String cluster, String face, String base, String agentState, List<String> aliases,
			int tasks, int waits) implements FactoryGoggleProjection {
		public Panel {
			if (aliases.size() > ClusterBinding.MAX_BINDINGS || tasks < 0 || waits < 0)
				throw new IllegalArgumentException("invalid Panel projection");
			aliases = aliases.stream().map(FactoryGoggleProjection::text).toList();
			cluster = text(cluster); face = text(face); base = text(base); agentState = text(agentState);
		}
	}
	/** Null priority / queue -1 mean that the endpoint does not offer that state. */
	record Endpoint(String role, String address, String alias, Integer priority, int queue, String run)
			implements FactoryGoggleProjection {
		public Endpoint {
			if (queue < -1) throw new IllegalArgumentException("invalid queue");
			role = text(role); address = text(address); alias = text(alias); run = text(run);
		}
	}
	record Radio(String projectedPoint, boolean dynamic, long frameAge, String distance, String linkType)
			implements FactoryGoggleProjection {
		public Radio {
			if (frameAge < 0) throw new IllegalArgumentException("negative frame age");
			projectedPoint = text(projectedPoint); distance = text(distance); linkType = text(linkType);
		}
	}
	private static String text(String value) { return FactoryGoggleState.text(value); }
	private static void frequency(String value) {
		if (Objects.requireNonNull(value).length() > 25)
			throw new IllegalArgumentException("frequency exceeds 25 characters");
	}
}
