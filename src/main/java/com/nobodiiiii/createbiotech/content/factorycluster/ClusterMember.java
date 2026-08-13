package com.nobodiiiii.createbiotech.content.factorycluster;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

public interface ClusterMember {
	UUID memberId();

	@Nullable
	UUID clusterId();

	List<LogisticsBinding> logisticsBindings();

	ClusterMemberType memberType();

	SpaceAddress memberAddress();

	boolean canRebind();

	void applyClusterBinding(UUID clusterId, List<LogisticsBinding> bindings);
}
