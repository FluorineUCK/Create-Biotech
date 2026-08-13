package com.nobodiiiii.createbiotech.content.factorycluster;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.Reference;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ClusterMemberIndexTest {
	private static final UUID PANEL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID CLUSTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

	@Test
	void oldInstanceCannotUnregisterReplacementAtSameStableId() {
		ClusterMemberIndex.ServerIndex index = new ClusterMemberIndex.ServerIndex();
		FakeMember oldSource = member(PANEL_ID, CLUSTER_ID, ClusterMemberType.PANEL);
		FakeMember movedTarget = member(PANEL_ID, CLUSTER_ID, ClusterMemberType.PANEL);
		index.register(oldSource);
		index.register(movedTarget);
		index.unregister(oldSource);
		assertSame(movedTarget, index.members(CLUSTER_ID, ClusterMemberType.PANEL).getFirst());
	}

	@Test
	void twoCoordinatorsReportConflictButPanelsDoNot() {
		ClusterMemberIndex.ServerIndex index = new ClusterMemberIndex.ServerIndex();
		List<FakeMember> members = List.of(
			member(UUID.randomUUID(), CLUSTER_ID, ClusterMemberType.PANEL),
			member(UUID.randomUUID(), CLUSTER_ID, ClusterMemberType.PANEL),
			member(UUID.randomUUID(), CLUSTER_ID, ClusterMemberType.COMPUTER_COORDINATOR),
			member(UUID.randomUUID(), CLUSTER_ID, ClusterMemberType.COMPUTER_COORDINATOR));
		try {
			index.register(members.get(0));
			index.register(members.get(1));
			assertFalse(index.conflicts(CLUSTER_ID).panelConflict());
			index.register(members.get(2));
			index.register(members.get(3));
			assertTrue(index.conflicts(CLUSTER_ID).computerConflict());
		} finally {
			Reference.reachabilityFence(members);
		}
	}

	private static FakeMember member(UUID memberId, UUID clusterId, ClusterMemberType type) {
		return new FakeMember(memberId, clusterId, type);
	}

	private record FakeMember(UUID memberId, UUID clusterId,
		ClusterMemberType memberType) implements ClusterMember {
		@Override
		public List<LogisticsBinding> logisticsBindings() {
			return List.of();
		}

		@Override
		public SpaceAddress memberAddress() {
			return null;
		}

		@Override
		public boolean canRebind() {
			return false;
		}

		@Override
		public void applyClusterBinding(UUID clusterId, List<LogisticsBinding> bindings) {}
	}
}
