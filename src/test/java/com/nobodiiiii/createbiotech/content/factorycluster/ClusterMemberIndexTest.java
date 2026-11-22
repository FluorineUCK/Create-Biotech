package com.nobodiiiii.createbiotech.content.factorycluster;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.Reference;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;

class ClusterMemberIndexTest {
	private static final UUID PANEL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID CLUSTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

	@BeforeAll
	static void initializeLoaderForServerScope() throws ReflectiveOperationException {
		Class<?> loadingModList = Class.forName("net.neoforged.fml.loading.LoadingModList");
		if (loadingModList.getMethod("get").invoke(null) == null)
			loadingModList.getMethod("of", List.class, List.class, List.class, List.class, java.util.Map.class)
				.invoke(null, List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
	}

	@Test
	void oldInstanceCannotUnregisterReplacementAtSameStableId() {
		TestIndex index = new TestIndex();
		FakeMember oldSource = member(PANEL_ID, CLUSTER_ID, ClusterMemberType.PANEL);
		FakeMember movedTarget = member(PANEL_ID, CLUSTER_ID, ClusterMemberType.PANEL);
		index.register(oldSource);
		index.register(movedTarget);
		index.unregister(oldSource);
		assertSame(movedTarget, index.members(CLUSTER_ID, ClusterMemberType.PANEL).getFirst());
	}

	@Test
	void twoCoordinatorsReportConflictButPanelsDoNot() {
		TestIndex index = new TestIndex();
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
		TestIndex index = new TestIndex();
		MutableMember panel = new MutableMember(PANEL_ID, CLUSTER_ID);
		UUID replacementCluster = UUID.fromString("00000000-0000-0000-0000-000000000003");
		index.register(panel);

		index.rebind(panel, () -> panel.clusterId = replacementCluster);

		assertTrue(index.members(CLUSTER_ID, ClusterMemberType.PANEL).isEmpty());
		assertEquals(List.of(panel), index.members(replacementCluster, ClusterMemberType.PANEL));
	}

	@Test
	void stableLookupFindsMemberAfterItMovesToAnotherCluster() {
		TestIndex index = new TestIndex();
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
		TestIndex index = new TestIndex();
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
		TestIndex index = new TestIndex();
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

	@Test
	void withdrawalRemovesEveryFrozenBucketWithoutReadingChangedMemberIdentity() {
		var index = new TestIndex(); var member = new MutableMember(PANEL_ID, CLUSTER_ID);
		index.register(member);
		var nextGroup = new UUID(0, 90); var nextId = new UUID(0, 91);
		member.clusterId = nextGroup; member.memberId = nextId; member.type = ClusterMemberType.PATTERN_CORE;
		index.register(member);
		member.poison = true;
		index.unregister(member);
		assertTrue(index.members(CLUSTER_ID, ClusterMemberType.PANEL).isEmpty());
		assertTrue(index.members(nextGroup, ClusterMemberType.PATTERN_CORE).isEmpty());
		assertEquals(ClusterMemberIndex.LookupStatus.MISSING, index.lookupStable(ClusterMemberType.PANEL, PANEL_ID).status());
		assertEquals(ClusterMemberIndex.LookupStatus.MISSING, index.lookupStable(ClusterMemberType.PATTERN_CORE, nextId).status());
	}

	@Test
	void failingMutationReindexesItsFinalStateAfterTemporarilyWithdrawingTheOldRegistration() {
		var index = new TestIndex(); var member = new MutableMember(PANEL_ID, CLUSTER_ID);
		index.register(member); var retained = index.members(CLUSTER_ID, ClusterMemberType.PANEL);
		var nextGroup = new UUID(0, 90); var failure = new IllegalStateException("mutation failed");
		assertSame(failure, assertThrows(IllegalStateException.class, () -> index.rebind(member, () -> {
			assertTrue(index.members(CLUSTER_ID, ClusterMemberType.PANEL).isEmpty());
			member.clusterId = nextGroup; throw failure;
		})));
		assertTrue(index.members(CLUSTER_ID, ClusterMemberType.PANEL).isEmpty());
		assertSame(member, index.members(nextGroup, ClusterMemberType.PANEL).getFirst());
		assertEquals(List.of(member), retained); assertThrows(UnsupportedOperationException.class, retained::clear);
		index.rebind(member, () -> member.clusterId = null);
		assertTrue(index.members(nextGroup, ClusterMemberType.PANEL).isEmpty());
	}

	/** Exercises the actual static registry and post-registration application callback in an isolated scope. */
	private static final class TestIndex {
		private final MinecraftServer server;
		private TestIndex() {
			try {
				var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
				server = (MinecraftServer) ((sun.misc.Unsafe) field.get(null)).allocateInstance(DedicatedServer.class);
			} catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
		}
		void register(ClusterMember member) { ClusterMemberIndex.register(server, member); }
		void unregister(ClusterMember member) { ClusterMemberIndex.unregister(server, member); }
		void rebind(ClusterMember member, Runnable mutation) { ClusterMemberIndex.rebind(server, member, mutation); }
		List<ClusterMember> members(UUID group, ClusterMemberType type) { return ClusterMemberIndex.members(server, group, type); }
		ClusterMemberIndex.StableLookup lookupStable(ClusterMemberType type, UUID id) { return ClusterMemberIndex.lookupStable(server, type, id); }
		ClusterMemberIndex.ConflictReport conflicts(UUID group) { return ClusterMemberIndex.conflicts(server, group); }
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
		private UUID memberId;
		private UUID clusterId;
		private ClusterMemberType type = ClusterMemberType.PANEL;
		private boolean poison;

		private MutableMember(UUID memberId, UUID clusterId) {
			this.memberId = memberId;
			this.clusterId = clusterId;
		}

		@Override
		public UUID memberId() {
			if (poison) throw new AssertionError("Unregister must not read member identity");
			return memberId;
		}

		@Override
		public ClusterBinding bindingState() {
			if (poison) throw new AssertionError("Unregister must not read current binding");
			return clusterId == null ? null : new ClusterBinding(clusterId, 0,
				new ClusterAuthority(type, memberId), List.of());
		}

		@Override
		public ClusterMemberType memberType() {
			if (poison) throw new AssertionError("Unregister must not read current role");
			return type;
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
