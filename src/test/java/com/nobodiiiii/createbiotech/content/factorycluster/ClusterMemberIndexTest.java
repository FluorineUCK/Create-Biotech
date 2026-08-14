package com.nobodiiiii.createbiotech.content.factorycluster;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

	@Test
	void rebindRemovesOldClusterEntryBeforeRegisteringNewIdentity() {
		ClusterMemberIndex.ServerIndex index = new ClusterMemberIndex.ServerIndex();
		MutableMember panel = new MutableMember(PANEL_ID, CLUSTER_ID);
		UUID replacementCluster = UUID.fromString("00000000-0000-0000-0000-000000000003");
		index.register(panel);

		index.rebind(panel, () -> panel.clusterId = replacementCluster);

		assertTrue(index.members(CLUSTER_ID, ClusterMemberType.PANEL).isEmpty());
		assertEquals(List.of(panel), index.members(replacementCluster, ClusterMemberType.PANEL));
	}

	@Test
	void stableLookupFindsMemberAfterItMovesToAnotherCluster() {
		ClusterMemberIndex.ServerIndex index = new ClusterMemberIndex.ServerIndex();
		UUID mergedCluster = UUID.fromString("00000000-0000-0000-0000-000000000003");
		FakeMember migratedAuthority = member(PANEL_ID, mergedCluster,
			ClusterMemberType.PANEL);
		index.register(migratedAuthority);

		ClusterMemberIndex.StableLookup lookup = index.lookupStable(
			ClusterMemberType.PANEL, PANEL_ID);

		assertEquals(ClusterMemberIndex.LookupStatus.FOUND, lookup.status());
		assertSame(migratedAuthority, lookup.member());
	}

	@Test
	void stableLookupReportsDuplicateIdentityAcrossClusters() {
		ClusterMemberIndex.ServerIndex index = new ClusterMemberIndex.ServerIndex();
		FakeMember oldClusterMember = member(PANEL_ID, CLUSTER_ID,
			ClusterMemberType.PANEL);
		FakeMember migratedClusterMember = member(PANEL_ID,
			UUID.fromString("00000000-0000-0000-0000-000000000003"),
			ClusterMemberType.PANEL);
		index.register(oldClusterMember);
		index.register(migratedClusterMember);

		ClusterMemberIndex.StableLookup lookup = index.lookupStable(
			ClusterMemberType.PANEL, PANEL_ID);

		assertEquals(ClusterMemberIndex.LookupStatus.CONFLICT, lookup.status());
	}

	@Test
	void stableLookupReportsDuplicateIdentityInsideOneCluster() {
		ClusterMemberIndex.ServerIndex index = new ClusterMemberIndex.ServerIndex();
		FakeMember first = member(PANEL_ID, CLUSTER_ID, ClusterMemberType.PANEL);
		FakeMember duplicate = member(PANEL_ID, CLUSTER_ID, ClusterMemberType.PANEL);
		index.register(first);
		index.register(duplicate);

		assertEquals(ClusterMemberIndex.LookupStatus.CONFLICT,
			index.lookupStable(ClusterMemberType.PANEL, PANEL_ID).status());

		index.unregister(first);
		assertSame(duplicate,
			index.lookupStable(ClusterMemberType.PANEL, PANEL_ID).member());
	}

	private static FakeMember member(UUID memberId, UUID clusterId, ClusterMemberType type) {
		return new FakeMember(memberId, clusterId, type);
	}

	private record FakeMember(UUID memberId, UUID clusterId,
		ClusterMemberType memberType) implements ClusterMember {
		@Override
		public ClusterBinding bindingState() {
			return new ClusterBinding(clusterId, 0,
				new ClusterAuthority(memberType, memberId), List.of());
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
		public ClusterBindingPreparation prepareClusterBinding(ClusterBinding proposed) {
			return ClusterBindingPreparation.READY;
		}

		@Override
		public void commitClusterBinding(ClusterBinding prepared) {}
	}

	private static final class MutableMember implements ClusterMember {
		private final UUID memberId;
		private UUID clusterId;

		private MutableMember(UUID memberId, UUID clusterId) {
			this.memberId = memberId;
			this.clusterId = clusterId;
		}

		@Override
		public UUID memberId() {
			return memberId;
		}

		@Override
		public ClusterBinding bindingState() {
			return new ClusterBinding(clusterId, 0,
				new ClusterAuthority(ClusterMemberType.PANEL, memberId), List.of());
		}

		@Override
		public ClusterMemberType memberType() {
			return ClusterMemberType.PANEL;
		}

		@Override
		public SpaceAddress memberAddress() {
			return null;
		}

		@Override
		public boolean canRebind() {
			return true;
		}

		@Override
		public ClusterBindingPreparation prepareClusterBinding(ClusterBinding proposed) {
			return ClusterBindingPreparation.READY;
		}

		@Override
		public void commitClusterBinding(ClusterBinding prepared) {
			this.clusterId = prepared.clusterId();
		}
	}
}
