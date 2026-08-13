package com.nobodiiiii.createbiotech.content.factorycluster;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ClusterBinding(UUID clusterId, List<LogisticsBinding> logisticsBindings) {
	public ClusterBinding {
		Objects.requireNonNull(clusterId, "clusterId");
		logisticsBindings = LogisticsBinding.normalize(logisticsBindings);
	}

	public ClusterBinding withBindings(List<LogisticsBinding> bindings) {
		return new ClusterBinding(clusterId, bindings);
	}
}
