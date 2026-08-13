package com.nobodiiiii.createbiotech.content.factorycluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClusterBindingServiceTest {
	private static final UUID CLUSTER =
		UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID OPEN =
		UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final UUID LOCKED =
		UUID.fromString("00000000-0000-0000-0000-000000000003");

	private FakeMember source;
	private FakeMember target;
	private FakeMember targetInNether;

	@BeforeEach
	void setUp() throws ReflectiveOperationException {
		source = member(CLUSTER, address("OVERWORLD"),
			List.of(new LogisticsBinding(OPEN, "open")));
		target = member(UUID.randomUUID(), address("OVERWORLD"),
			List.of(new LogisticsBinding(LOCKED, "locked")));
		targetInNether = member(UUID.randomUUID(), address("NETHER"),
			List.of(new LogisticsBinding(LOCKED, "locked")));
	}

	@Test
	void bindRejectsDifferentRootDimension() {
		ClusterBindingService.BindResult result = ClusterBindingService.validate(source,
			targetInNether, id -> true, List.of(source, targetInNether));
		assertEquals(ClusterBindingService.BindResult.DIMENSION, result);
	}

	@Test
	void bindRequiresAdministrationOfEveryOldAndNewNetwork() {
		ClusterBindingService.BindResult result = ClusterBindingService.validate(source,
			target, id -> !id.equals(LOCKED), List.of(source, target));
		assertEquals(ClusterBindingService.BindResult.PERMISSION, result);
	}

	@Test
	void activeMemberPreventsRebinding() {
		target.rebindable = false;
		assertEquals(ClusterBindingService.BindResult.ACTIVE,
			ClusterBindingService.validate(source, target, id -> true,
				List.of(source, target)));
	}

	@Test
	void everyLoadedClusterMemberMustBeRebindable() throws ReflectiveOperationException {
		FakeMember activeClusterMember = member(CLUSTER, address("OVERWORLD"), List.of());
		activeClusterMember.rebindable = false;
		assertEquals(ClusterBindingService.BindResult.ACTIVE,
			ClusterBindingService.validate(source, target, id -> true,
				List.of(source, target, activeClusterMember)));
	}

	@Test
	void sourceMustAlreadyBelongToACluster() {
		source.clusterId = null;
		assertEquals(ClusterBindingService.BindResult.NO_SOURCE,
			ClusterBindingService.validate(source, target, id -> true,
				List.of(source, target)));
	}

	@Test
	void bindingRequiresAtLeastOneLogisticsNetwork() {
		source.bindings = List.of();
		target.bindings = List.of();
		assertEquals(ClusterBindingService.BindResult.EMPTY_NETWORKS,
			ClusterBindingService.validate(source, target, id -> true,
				List.of(source, target)));
	}

	@Test
	void okIsTheOnlySuccessfulResult() {
		assertTrue(ClusterBindingService.BindResult.OK.succeeded());
		assertFalse(ClusterBindingService.BindResult.PERMISSION.succeeded());
	}

	private static FakeMember member(UUID clusterId, SpaceAddress address,
		List<LogisticsBinding> bindings) {
		return new FakeMember(UUID.randomUUID(), clusterId, bindings,
			address);
	}

	private static SpaceAddress address(String dimensionField)
		throws ReflectiveOperationException {
		Class<?> levelClass = Class.forName("net.minecraft.world.level.Level");
		Object dimension = levelClass.getField(dimensionField).get(null);
		Object zero = Class.forName("net.minecraft.core.BlockPos")
			.getField("ZERO").get(null);
		return (SpaceAddress) SpaceAddress.class.getDeclaredConstructors()[0]
			.newInstance(dimension, null, zero);
	}

	private static final class FakeMember implements ClusterMember {
		private final UUID memberId;
		private UUID clusterId;
		private List<LogisticsBinding> bindings;
		private final SpaceAddress address;
		private boolean rebindable = true;

		private FakeMember(UUID memberId, UUID clusterId,
			List<LogisticsBinding> bindings, SpaceAddress address) {
			this.memberId = memberId;
			this.clusterId = clusterId;
			this.bindings = bindings;
			this.address = address;
		}

		@Override
		public UUID memberId() {
			return memberId;
		}

		@Override
		public UUID clusterId() {
			return clusterId;
		}

		@Override
		public List<LogisticsBinding> logisticsBindings() {
			return bindings;
		}

		@Override
		public ClusterMemberType memberType() {
			return ClusterMemberType.PANEL;
		}

		@Override
		public SpaceAddress memberAddress() {
			return address;
		}

		@Override
		public boolean canRebind() {
			return rebindable;
		}

		@Override
		public void applyClusterBinding(UUID clusterId,
			List<LogisticsBinding> bindings) {
			this.clusterId = clusterId;
			this.bindings = bindings;
		}
	}
}
