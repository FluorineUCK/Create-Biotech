package com.nobodiiiii.createbiotech.content.factorycluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

class ClusterBindingServiceTest {
	private static final String SELECTION_KEY =
		"create_biotech:factory_cluster_binding";
	private static final UUID CLUSTER =
		UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID OPEN =
		UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final UUID LOCKED =
		UUID.fromString("00000000-0000-0000-0000-000000000003");
	private static final UUID PARTICIPANT_OLD =
		UUID.fromString("00000000-0000-0000-0000-000000000004");

	private FakeMember source;
	private FakeMember target;
	private FakeMember targetInNether;

	@BeforeEach
	void setUp() {
		source = member(CLUSTER, address(Level.OVERWORLD),
			List.of(new LogisticsBinding(OPEN, "open")));
		target = member(UUID.randomUUID(), address(Level.OVERWORLD),
			List.of(new LogisticsBinding(LOCKED, "locked")));
		targetInNether = member(UUID.randomUUID(), address(Level.NETHER),
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
	void everyLoadedClusterMemberMustBeRebindable() {
		FakeMember activeClusterMember = member(CLUSTER, address(Level.OVERWORLD), List.of());
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

	@Test
	void wrongTypedSelectionRootIsCleared() {
		CompoundTag persistentData = new CompoundTag();
		persistentData.putString(SELECTION_KEY, "not a compound");

		assertTrue(ClusterBindingSelection.decode(persistentData).isEmpty());
		assertFalse(persistentData.contains(SELECTION_KEY));
	}

	@Test
	void malformedSelectionFieldsAreClearedWithoutDefaults() {
		List<Consumer<CompoundTag>> corruptions = List.of(
			tag -> tag.remove("MemberId"),
			tag -> tag.putString("MemberId", "wrong type"),
			tag -> tag.remove("Expires"),
			tag -> tag.putInt("Expires", 200),
			tag -> tag.remove("Dimension"),
			tag -> tag.putLong("Dimension", 0),
			tag -> tag.putString("Dimension", ""),
			tag -> tag.remove("Pos"),
			tag -> tag.putString("Pos", "wrong type"),
			tag -> tag.putString("SubLevel", "wrong type"));

		for (Consumer<CompoundTag> corruption : corruptions) {
			CompoundTag persistentData = validPersistentSelection();
			corruption.accept(persistentData.getCompound(SELECTION_KEY));
			assertTrue(ClusterBindingSelection.decode(persistentData).isEmpty());
			assertFalse(persistentData.contains(SELECTION_KEY));
		}
	}

	@Test
	void participantOldNetworkPermissionFailureMutatesNobodyAndKeepsSelection() {
		FakeMember participant = member(CLUSTER, address(Level.OVERWORLD),
			List.of(new LogisticsBinding(PARTICIPANT_OLD, "participant")));
		AtomicBoolean selectionCleared = new AtomicBoolean();

		ClusterBindingService.BindResult result = ClusterBindingService.bind(source,
			target, List.of(source, participant),
			id -> !id.equals(PARTICIPANT_OLD), false,
			() -> selectionCleared.set(true));

		assertEquals(ClusterBindingService.BindResult.PERMISSION, result);
		assertEquals(0, source.applyCount);
		assertEquals(0, target.applyCount);
		assertEquals(0, participant.applyCount);
		assertFalse(selectionCleared.get());
	}

	@Test
	void successAppliesOneSourceFirstNormalizedBindingAndClearsSelection() {
		FakeMember participant = member(CLUSTER, address(Level.OVERWORLD), List.of(
			new LogisticsBinding(OPEN, "replacement"),
			new LogisticsBinding(PARTICIPANT_OLD, "participant")));
		AtomicBoolean selectionCleared = new AtomicBoolean();

		ClusterBindingService.BindResult result = ClusterBindingService.bind(source,
			target, List.of(participant, source), id -> true, false,
			() -> selectionCleared.set(true));

		List<LogisticsBinding> expected = List.of(
			new LogisticsBinding(OPEN, "open"),
			new LogisticsBinding(LOCKED, "locked"),
			new LogisticsBinding(PARTICIPANT_OLD, "participant"));
		assertEquals(ClusterBindingService.BindResult.OK, result);
		assertEquals(expected, source.appliedBindings);
		assertSame(source.appliedBindings, target.appliedBindings);
		assertSame(source.appliedBindings, participant.appliedBindings);
		assertEquals(1, source.applyCount);
		assertEquals(1, target.applyCount);
		assertEquals(1, participant.applyCount);
		assertTrue(selectionCleared.get());
	}

	@Test
	void crossRootParticipantFailureMutatesNobody() {
		FakeMember participant = member(CLUSTER, address(Level.NETHER),
			List.of(new LogisticsBinding(PARTICIPANT_OLD, "participant")));

		ClusterBindingService.BindResult result = ClusterBindingService.bind(source,
			target, List.of(participant), id -> true, false, () -> {});

		assertEquals(ClusterBindingService.BindResult.DIMENSION, result);
		assertEquals(0, source.applyCount);
		assertEquals(0, target.applyCount);
		assertEquals(0, participant.applyCount);
	}

	private static CompoundTag validPersistentSelection() {
		CompoundTag selection = address(Level.OVERWORLD).save();
		selection.putUUID("MemberId", UUID.randomUUID());
		selection.putLong("Expires", 200);
		CompoundTag persistentData = new CompoundTag();
		persistentData.put(SELECTION_KEY, selection);
		return persistentData;
	}

	private static FakeMember member(UUID clusterId, SpaceAddress address,
		List<LogisticsBinding> bindings) {
		return new FakeMember(UUID.randomUUID(), clusterId, bindings,
			address);
	}

	private static SpaceAddress address(ResourceKey<Level> dimension) {
		return new SpaceAddress(dimension, null, BlockPos.ZERO);
	}

	private static final class FakeMember implements ClusterMember {
		private final UUID memberId;
		private UUID clusterId;
		private List<LogisticsBinding> bindings;
		private final SpaceAddress address;
		private boolean rebindable = true;
		private int applyCount;
		private List<LogisticsBinding> appliedBindings = List.of();

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
			applyCount++;
			this.clusterId = clusterId;
			this.bindings = bindings;
			this.appliedBindings = bindings;
		}
	}
}
