package com.nobodiiiii.createbiotech.content.factorycluster;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

public interface ClusterMember {
	UUID memberId();

	@Nullable
	ClusterBinding bindingState();

	default @Nullable UUID clusterId() {
		ClusterBinding state = bindingState();
		return state == null ? null : state.clusterId();
	}

	default List<LogisticsBinding> logisticsBindings() {
		ClusterBinding state = bindingState();
		return state == null ? List.of() : state.logisticsBindings();
	}

	default boolean hasValidBindingState() {
		return true;
	}

	ClusterMemberType memberType();

	SpaceAddress memberAddress();

	boolean canRebind();

	/**
	 * Performs all validation for an exact proposed state without mutating this member.
	 */
	ClusterBindingPreparation prepareClusterBinding(ClusterBinding proposed);

	/**
	 * Commits a state accepted by {@link #prepareClusterBinding(ClusterBinding)}.
	 * Implementations must not revalidate or fail after a successful prepare.
	 */
	void commitClusterBinding(ClusterBinding prepared);
}
