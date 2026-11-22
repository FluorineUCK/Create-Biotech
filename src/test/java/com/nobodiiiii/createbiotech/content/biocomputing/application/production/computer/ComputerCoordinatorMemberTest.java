package com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionLayout;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionStructure;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpoch;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.TestComputer;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.TestComputer.*;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingPreparation;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingService;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMember;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.factorycluster.LogisticsBinding;
import com.nobodiiiii.createbiotech.foundation.item.CBItemData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public class ComputerCoordinatorMemberTest {
	private static final RegistryAccess REGISTRIES = RegistryAccess.EMPTY;
	private static final ComputerStructureScanner.Limits LIMITS =
		new ComputerStructureScanner.Limits(3, 4, 64, 16);
	private static final ProductionResourceProfile PROFILE =
		ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN, 3, 0).orElseThrow();
	private static final UUID LOW = uuid(1);
	private static final UUID HIGH = uuid(2);

	@BeforeAll
	static void bootstrap() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		try {
			java.lang.reflect.Field field =
				net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			field.setAccessible(true);
			field.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	@Test
	void stableAdapterUsesStructureIdentityAndOnlyReadyElectedComputerIsPublished() {
		Fixture fixture = fixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		ComputerCoordinatorMember lowDormant = fixture.be(LOW).coordinatorMember;
		ComputerCoordinatorMember highDormant = fixture.be(HIGH).coordinatorMember;

		fixture.refresh(LOW);

		UUID structureId = fixture.be(LOW).computerStructureMemberId().orElseThrow();
		assertSame(lowDormant, fixture.be(LOW).coordinatorMember);
		assertSame(highDormant, fixture.be(HIGH).coordinatorMember);
		assertFalse(ClusterMember.class.isInstance(fixture.be(LOW)));
		assertEquals(structureId, lowDormant.memberId());
		assertEquals(LOW, lowDormant.coordinatorComputerId());
		assertNotEquals(lowDormant.memberId(), lowDormant.coordinatorComputerId());
		assertEquals(ClusterMemberType.COMPUTER_COORDINATOR, lowDormant.memberType());
		assertSame(lowDormant, fixture.be(LOW).publishedCoordinatorMember().orElseThrow());
		assertTrue(fixture.be(HIGH).publishedCoordinatorMember().isEmpty());

		Fixture notReady = fixture(empty(LOW, pos(0)));
		notReady.refresh(LOW);
		assertTrue(notReady.be(LOW).currentStructureRecord().isPresent());
		assertEquals(ComputerAvailabilityReason.NOT_READY,
			notReady.be(LOW).availabilityReason());
		assertTrue(notReady.be(LOW).publishedCoordinatorMember().isEmpty());
	}

	@Test
	void profileLossWithdrawsPublishedCoordinatorBeforeNotReadyStateIsDiscarded() {
		Fixture fixture = readyBoundFixture();
		ComputerCoordinatorMember adapter = fixture.be(LOW).coordinatorMember;

		fixture.be(HIGH).owner().clearResidentSource();

		assertTrue(fixture.be(LOW).publishedCoordinatorMember().isEmpty());
		assertFalse(adapter.canRebind());
	}

	@Test
	void authorityAdapterRetainsExactEpochButReportsCoordinatorOffline() {
		Fixture fixture = readyBoundFixture(profiled(LOW, pos(0)));
		ComputerCoordinatorMember authority = fixture.be(LOW).coordinatorMember;
		UUID clusterId = authority.bindingState().clusterId();
		assertEquals(ProductionComputer.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(fixture.be(LOW), clusterId, fixture, LIMITS));
		UUID epochId = fixture.be(LOW).epoch().orElseThrow().epochId();
		assertTrue(authority.hasExactEpoch(clusterId, epochId));
		assertTrue(authority.coordinatorOnline());

		fixture.bindingAccess = ClusterBindingService.BindingAccess.AUTHORITY_OFFLINE;
		fixture.refresh(LOW);

		assertTrue(authority.hasExactEpoch(clusterId, epochId),
			"authority loss must not fabricate a reformation identity");
		assertFalse(authority.coordinatorOnline(),
			"a loaded, bound, exact-epoch adapter must expose election loss");
	}

	@Test
	void prepareRejectsIdentityEpochFaultMissingReplicaRevisionAndUnreadyAuthorityWithoutMutation() {
		Fixture fixture = readyBoundFixture();
		ComputerCoordinatorMember adapter = fixture.be(LOW).coordinatorMember;
		ClusterBinding current = adapter.bindingState();
		ClusterBinding next = binding(adapter, current.revision() + 1,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, adapter.memberId()));

		assertPreparationDoesNotMutate(fixture, adapter, new ClusterBinding(next.clusterId(),
			next.revision(), null, next.logisticsBindings()), ClusterBindingPreparation.IDENTITY);

		ProductionEpoch epoch = ProductionEpoch.freeze(current.clusterId(), adapter.memberId(), fixture.snapshot.layout());
		for (ProductionComputer computer : fixture.computers.values()) {
			ProductionComputer.TopologyState state = computer.topologyState();
			computer.applyTopologyState(state.record(), state.binding(), true, epoch, Set.of());
		}
		assertPreparationDoesNotMutate(fixture, adapter, next, ClusterBindingPreparation.ACTIVE);

		for (ProductionComputer computer : fixture.computers.values()) {
			ProductionComputer.TopologyState state = computer.topologyState();
			computer.applyTopologyState(state.record(), state.binding(), true, epoch,
				Set.of(EpochFault.WIDTH));
		}
		assertPreparationDoesNotMutate(fixture, adapter, next, ClusterBindingPreparation.ACTIVE);

		Fixture missing = readyBoundFixture();
		ComputerCoordinatorMember missingAdapter = missing.be(LOW).coordinatorMember;
		missing.unload(HIGH);
		assertPreparationDoesNotMutate(missing, missingAdapter,
			binding(missingAdapter, missingAdapter.bindingState().revision() + 1,
				new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR,
					missingAdapter.memberId())), ClusterBindingPreparation.IDENTITY);

		Fixture stale = readyBoundFixture();
		ComputerCoordinatorMember staleAdapter = stale.be(LOW).coordinatorMember;
		ClusterBinding ahead = binding(staleAdapter, 5,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR,
				staleAdapter.memberId()));
		ProductionComputer high = stale.be(HIGH);
		high.applyTopologyState(high.currentStructureRecord().orElseThrow(), ahead,
			true, null, Set.of());
		assertPreparationDoesNotMutate(stale, staleAdapter,
			binding(staleAdapter, 4, ahead.authority()), ClusterBindingPreparation.REVISION);
		ClusterBinding divergent = new ClusterBinding(ahead.clusterId(), 5,
			ahead.authority(), List.of(new LogisticsBinding(uuid(900))));
		assertPreparationDoesNotMutate(stale, staleAdapter, divergent,
			ClusterBindingPreparation.REVISION);

		Fixture offline = readyBoundFixture();
		offline.bindingAccess = ClusterBindingService.BindingAccess.AUTHORITY_OFFLINE;
		offline.refresh(LOW);
		assertEquals(ComputerAvailabilityReason.AUTHORITY_OFFLINE,
			offline.be(LOW).availabilityReason());
		assertPreparationDoesNotMutate(offline, offline.be(LOW).coordinatorMember,
			binding(offline.be(LOW).coordinatorMember, 2,
				new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR,
					offline.be(LOW).coordinatorMember.memberId())),
			ClusterBindingPreparation.ACTIVE);

	}

	@Test
	void adapterAcceptsExactMaximumAndBindingValueRejectsMaximumPlusOne() {
		Fixture fixture = readyBoundFixture();
		ComputerCoordinatorMember adapter = fixture.be(LOW).coordinatorMember;
		List<LogisticsBinding> maximum = new ArrayList<>();
		for (int i = 0; i < ClusterBinding.MAX_BINDINGS; i++)
			maximum.add(new LogisticsBinding(uuid(1000 + i)));
		ClusterBinding exactMaximum = new ClusterBinding(adapter.bindingState().clusterId(),
			adapter.bindingState().revision() + 1,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, adapter.memberId()),
			maximum);

		assertEquals(ClusterBindingPreparation.READY,
			adapter.prepareClusterBinding(exactMaximum));

		List<LogisticsBinding> maximumPlusOne = new ArrayList<>(maximum);
		maximumPlusOne.add(new LogisticsBinding(uuid(2000)));
		assertThrows(IllegalArgumentException.class, () -> new ClusterBinding(uuid(300), 1,
			exactMaximum.authority(), maximumPlusOne));
	}

	@Test
	void successfulPrepareCachesExactReplicasAndCommitWritesOneBindingWithoutWorldRescan() {
		Fixture fixture = fixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		fixture.refresh(LOW);
		ComputerCoordinatorMember adapter = fixture.be(LOW).coordinatorMember;
		ClusterBinding prepared = binding(adapter, 1,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, adapter.memberId()));
		fixture.resetProbes();

		assertEquals(ClusterBindingPreparation.READY,
			adapter.prepareClusterBinding(prepared));
		int afterPrepare = fixture.worldProbes();
		adapter.commitClusterBinding(prepared);

		assertEquals(afterPrepare, fixture.worldProbes());
		// Bindings are immutable values; exact replica/adapter identity is checked separately.
		assertEquals(prepared, fixture.be(LOW).bindingState());
		assertEquals(prepared, fixture.be(HIGH).bindingState());
	}

	@Test
	void historicalAuthorityFreeBindingRejectsLogisticsChangeWithoutThrowingOrMutation() {
		Fixture fixture = readyBoundFixture();
		var adapter = fixture.be(LOW).coordinatorMember;
		var current = adapter.bindingState();
		var historical = new ClusterBinding(current.clusterId(), current.revision(), null, current.logisticsBindings());
		fixture.computers.values().forEach(c -> c.stagePreparedBinding(historical));
		var before = bytes(fixture);
		var next = binding(adapter, current.revision() + 1, current.authority());
		assertEquals(ClusterBindingPreparation.IDENTITY, adapter.prepareLogisticsBinding(next));
		assertEquals(before, bytes(fixture));
	}

	@Test
	void activeLogisticsCommitStagesEveryHeterogeneousReplicaBeforeNotification() {
		var second = ProductionResourceProfile.forProfession(ProductionProfession.FLETCHER, 2, 0).orElseThrow();
		Fixture fixture = readyBoundFixture(profiled(LOW, pos(0)), new NodeSpec(HIGH, pos(2), second));
		var adapter = fixture.be(LOW).coordinatorMember;
		var current = adapter.bindingState();
		assertEquals(ProductionComputer.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(fixture.be(LOW), current.clusterId(), fixture, LIMITS));
		var epoch = fixture.be(LOW).epoch().orElseThrow();
		var profiles = fixture.computers.values().stream().map(c -> c.installedProfile().orElseThrow()).toList();
		var next = new ClusterBinding(current.clusterId(), current.revision() + 1, current.authority(),
			List.of(new LogisticsBinding(uuid(777))));
		var observations = new ArrayList<List<ClusterBinding>>();
		fixture.computers.values().forEach(c -> ((ObservedComputer)c.owner()).onBindingPublished = () ->
			observations.add(fixture.computers.values().stream().map(ProductionComputer::bindingState).toList()));
		assertEquals(ClusterBindingPreparation.ACTIVE, adapter.prepareClusterBinding(next));
		assertEquals(ClusterBindingPreparation.READY, adapter.prepareLogisticsBinding(next));
		fixture.resetProbes();
		adapter.commitClusterBinding(next);
		assertEquals(0, fixture.worldProbes(), "Commit uses the prepared exact replicas, not a second scan");
		assertEquals(List.of(List.of(next, next), List.of(next, next)), observations);
		assertEquals(profiles, fixture.computers.values().stream().map(c -> c.installedProfile().orElseThrow()).toList());
		fixture.computers.values().forEach(c -> assertEquals(epoch, c.epoch().orElseThrow()));
		assertEquals(List.of(PROFILE, second), epoch.layout().members().stream().map(ProductionLayout.Member::profile).toList());
	}

	@Test
	void activeEpochRejectsBindingButNaturalClosePreservesIdentityAndAllowsNextCommit() {
		Fixture fixture = readyBoundFixture();
		ComputerCoordinatorMember adapter = fixture.be(LOW).coordinatorMember;
		UUID structureId = adapter.memberId();
		ClusterBinding old = adapter.bindingState();
		assertEquals(ProductionComputer.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(fixture.be(LOW), old.clusterId(),
				fixture, LIMITS));
		ClusterBinding next = binding(adapter, old.revision() + 1,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, structureId));
		assertEquals(ClusterBindingPreparation.ACTIVE,
			adapter.prepareClusterBinding(next));

		UUID epochId = fixture.be(LOW).epoch().orElseThrow().epochId();
		assertEquals(ProductionComputer.EpochCloseResult.CLOSED,
			ComputerTopologyController.closeIdleEpoch(fixture.be(LOW), epochId,
				new ReadyQuiescence(), fixture, LIMITS));

		assertEquals(structureId, adapter.memberId());
		assertEquals(old, adapter.bindingState());
		assertEquals(ClusterBindingPreparation.READY,
			adapter.prepareClusterBinding(next));
		adapter.commitClusterBinding(next);
		fixture.computers.values().forEach(computer -> assertEquals(next, computer.bindingState()));
	}

	@Test
	void lowerReplicaAdoptsOnlyReadyFoundationStateAndNeverPublishesOverCoordinator() {
		Fixture fixture = readyBoundFixture();
		ComputerCoordinatorMember adapter = fixture.be(LOW).coordinatorMember;
		ClusterBinding authoritative = binding(adapter, 2,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, adapter.memberId()));
		assertEquals(ClusterBindingPreparation.READY,
			adapter.prepareClusterBinding(authoritative));
		adapter.commitClusterBinding(authoritative);
		ClusterBinding lower = new ClusterBinding(authoritative.clusterId(), 1,
			authoritative.authority(), authoritative.logisticsBindings());
		ProductionComputer replica = fixture.be(HIGH);
		replica.applyTopologyState(replica.currentStructureRecord().orElseThrow(), lower,
			true, null, Set.of());

		fixture.bindingAccess = ClusterBindingService.BindingAccess.AUTHORITY_OFFLINE;
		fixture.refresh(LOW);
		assertEquals(lower, replica.bindingState());
		assertTrue(replica.publishedCoordinatorMember().isEmpty());

		fixture.bindingAccess = ClusterBindingService.BindingAccess.READY;
		fixture.refresh(LOW);
		assertEquals(authoritative, replica.bindingState());
		assertSame(adapter, fixture.be(LOW).publishedCoordinatorMember().orElseThrow());
		assertTrue(replica.publishedCoordinatorMember().isEmpty());

		ClusterBinding divergent = new ClusterBinding(authoritative.clusterId(),
			authoritative.revision(), authoritative.authority(),
			List.of(new LogisticsBinding(uuid(999))));
		replica.applyTopologyState(replica.currentStructureRecord().orElseThrow(), divergent,
			true, null, Set.of());
		assertFalse(adapter.updateBindingAccess(ClusterBindingService.BindingAccess.READY));
		assertFalse(adapter.canRebind());
	}

	@Test
	void unloadReloadAndPostCloseReelectionUseExactAdaptersAndStableStructureIdentity() {
		Fixture fixture = readyBoundFixture();
		ComputerCoordinatorMember original = fixture.be(LOW).coordinatorMember;
		UUID structureId = original.memberId();
		CompoundTag saved = save(fixture.be(LOW));
		fixture.unload(LOW);
		assertTrue(fixture.be(HIGH).publishedCoordinatorMember().isEmpty());

		ProductionComputer reloaded = computer(LOW, pos(0), PROFILE);
		physical(reloaded).read(saved, REGISTRIES, false);
		fixture.put(reloaded);
		fixture.refresh(LOW);
		assertNotSame(original, reloaded.coordinatorMember);
		assertEquals(structureId, reloaded.coordinatorMember.memberId());
		assertEquals(ComputerAvailabilityReason.NONE, reloaded.availabilityReason());
		assertSame(reloaded.coordinatorMember,
			reloaded.publishedCoordinatorMember().orElseThrow());

		ClusterBinding binding = reloaded.bindingState();
		assertEquals(ProductionComputer.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(reloaded, binding.clusterId(), fixture, LIMITS));
		UUID epochId = reloaded.epoch().orElseThrow().epochId();
		assertEquals(ProductionComputer.EpochCloseResult.CLOSED,
			ComputerTopologyController.closeIdleEpoch(reloaded, epochId,
				new ReadyQuiescence(), fixture, LIMITS));
		fixture.destroy(LOW);
		fixture.snapshot = snapshot(List.of(profiledNode(HIGH, pos(2))));
		fixture.refresh(HIGH);
		assertEquals(structureId, fixture.be(HIGH).coordinatorMember.memberId());
		assertSame(fixture.be(HIGH).coordinatorMember,
			fixture.be(HIGH).publishedCoordinatorMember().orElseThrow());
	}

	@Test
	void replacementPublicationRetiresTheOldOwnerAndLateInvalidationCannotDetachNewMembers() {
		Fixture fixture = readyBoundFixture();
		var old = fixture.be(LOW);
		var original = old.coordinatorMember;
		var nextBinding = binding(original, 2, original.bindingState().authority());
		assertEquals(ClusterBindingPreparation.READY, original.prepareClusterBinding(nextBinding));
		var replacement = computer(LOW, pos(0), PROFILE);
		physical(replacement).read(save(old), REGISTRIES, false);
		fixture.put(replacement);
		fixture.refresh(LOW);

		assertSame(replacement.coordinatorMember, replacement.publishedCoordinatorMember().orElseThrow());
		assertTrue(old.publishedCoordinatorMember().isEmpty());
		assertFalse(original.canRebind());
		assertThrows(NullPointerException.class, () -> original.commitClusterBinding(nextBinding));
		assertTrue(fixture.be(HIGH).isAttachedTo(replacement.coordinatorMember));
		assertFalse(fixture.be(HIGH).isAttachedTo(original));
		old.owner().invalidate();
		assertTrue(fixture.be(HIGH).isAttachedTo(replacement.coordinatorMember));
		assertTrue(replacement.publishedCoordinatorMember().isPresent());
		assertTrue(replacement.coordinatorMember.canRebind());
	}

	@Test
	void activeCoordinatorLossPublishesNoReplacement() {
		Fixture fixture = readyBoundFixture();
		ClusterBinding binding = fixture.be(LOW).bindingState();
		assertEquals(ProductionComputer.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(fixture.be(LOW), binding.clusterId(),
				fixture, LIMITS));

		fixture.unload(LOW);
		fixture.snapshot = snapshot(List.of(profiledNode(HIGH, pos(2))));
		fixture.refresh(HIGH);

		assertTrue(fixture.be(HIGH).publishedCoordinatorMember().isEmpty());
		assertEquals(ComputerAvailabilityReason.COORDINATOR_MISSING,
			fixture.be(HIGH).availabilityReason());
	}

	@Test
	void startEpochRequiresSameTickOwnedAdapterAndReadyFoundationAccess() {
		Fixture unbound = fixture(profiled(LOW, pos(0)));
		unbound.refresh(LOW);
		assertEquals(ProductionComputer.EpochStartResult.BINDING_UNAVAILABLE,
			ComputerTopologyController.startEpoch(unbound.be(LOW), uuid(400), unbound, LIMITS));

		Fixture offline = readyBoundFixture(profiled(LOW, pos(0)));
		offline.bindingAccess = ClusterBindingService.BindingAccess.AUTHORITY_OFFLINE;
		assertEquals(ProductionComputer.EpochStartResult.BINDING_UNAVAILABLE,
			ComputerTopologyController.startEpoch(offline.be(LOW),
				offline.be(LOW).bindingState().clusterId(), offline, LIMITS));

		Fixture conflict = readyBoundFixture(profiled(LOW, pos(0)));
		conflict.bindingAccess = ClusterBindingService.BindingAccess.CONFLICT;
		assertEquals(ProductionComputer.EpochStartResult.BINDING_UNAVAILABLE,
			ComputerTopologyController.startEpoch(conflict.be(LOW),
				conflict.be(LOW).bindingState().clusterId(), conflict, LIMITS));

		Fixture ready = readyBoundFixture(profiled(LOW, pos(0)));
		assertEquals(ProductionComputer.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(ready.be(LOW),
				ready.be(LOW).bindingState().clusterId(), ready, LIMITS));
	}

	@Test
	void startEpochRecapturesAfterReadyAccessMutatesOwnerAndPropagatesLowerReplica() {
		Fixture fixture = readyBoundFixture();
		ComputerCoordinatorMember adapter = fixture.be(LOW).coordinatorMember;
		ClusterBinding revisionTwo = binding(adapter, 2, adapter.bindingState().authority());
		fixture.bindingAccessEffect = member -> {
			assertSame(adapter, member);
			ProductionComputer owner = fixture.be(LOW);
			owner.stagePreparedBinding(revisionTwo);
			owner.publishPreparedBinding();
			return ClusterBindingService.BindingAccess.READY;
		};

		assertEquals(ProductionComputer.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(fixture.be(LOW), revisionTwo.clusterId(),
				fixture, LIMITS));
		fixture.computers.values().forEach(computer -> {
			assertEquals(revisionTwo, computer.bindingState());
			assertTrue(computer.epoch().isPresent());
		});
	}

	@Test
	void normalClosePublishesPendingLowerCoordinatorReadyForImmediateBindingTransaction() {
		Fixture fixture = readyBoundFixture(profiled(HIGH, pos(2)));
		ClusterBinding initial = fixture.be(HIGH).bindingState();
		assertEquals(ProductionComputer.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(fixture.be(HIGH), initial.clusterId(),
				fixture, LIMITS));
		fixture.put(computer(LOW, pos(0), PROFILE));
		fixture.snapshot = snapshot(List.of(profiledNode(LOW, pos(0)),
			profiledNode(HIGH, pos(2))));
		fixture.refresh(LOW);
		int accessCallsBeforeClose = fixture.bindingAccessCalls;

		assertEquals(ProductionComputer.EpochCloseResult.CLOSED,
			ComputerTopologyController.closeIdleEpoch(fixture.be(LOW),
				fixture.be(HIGH).epoch().orElseThrow().epochId(), new ReadyQuiescence(),
				fixture, LIMITS));

		ComputerCoordinatorMember elected = fixture.be(LOW).publishedCoordinatorMember()
			.orElseThrow();
		assertEquals(accessCallsBeforeClose + 1, fixture.bindingAccessCalls);
		assertTrue(elected.canRebind());
		ClusterBinding next = binding(elected, initial.revision() + 1,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, elected.memberId()));
		assertEquals(ClusterBindingPreparation.READY,
			elected.prepareClusterBinding(next));
		elected.commitClusterBinding(next);
		fixture.computers.values().forEach(computer -> assertEquals(next, computer.bindingState()));
	}

	@Test
	void normalCloseRecordsAuthorityOfflineWhenNewCoordinatorAccessIsNotReady() {
		Fixture fixture = readyBoundFixture(profiled(HIGH, pos(2)));
		ClusterBinding initial = fixture.be(HIGH).bindingState();
		assertEquals(ProductionComputer.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(fixture.be(HIGH), initial.clusterId(),
				fixture, LIMITS));
		fixture.put(computer(LOW, pos(0), PROFILE));
		fixture.snapshot = snapshot(List.of(profiledNode(LOW, pos(0)),
			profiledNode(HIGH, pos(2))));
		fixture.refresh(LOW);
		fixture.bindingAccess = ClusterBindingService.BindingAccess.AUTHORITY_OFFLINE;

		assertEquals(ProductionComputer.EpochCloseResult.CLOSED,
			ComputerTopologyController.closeIdleEpoch(fixture.be(LOW),
				fixture.be(HIGH).epoch().orElseThrow().epochId(), new ReadyQuiescence(),
				fixture, LIMITS));

		assertEquals(ComputerAvailabilityReason.AUTHORITY_OFFLINE,
			fixture.be(LOW).availabilityReason());
		assertFalse(fixture.be(LOW).coordinatorMember.canRebind());
	}

	private static void assertPreparationDoesNotMutate(Fixture fixture,
		ComputerCoordinatorMember adapter, ClusterBinding proposed,
		ClusterBindingPreparation expected) {
		Map<UUID, CompoundTag> before = bytes(fixture);
		assertEquals(expected, adapter.prepareClusterBinding(proposed));
		assertEquals(before, bytes(fixture));
	}

	private static Fixture readyBoundFixture(NodeSpec... specs) {
		Fixture fixture = specs.length == 0
			? fixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2))) : fixture(specs);
		fixture.refresh(fixture.snapshot.layout().members().getFirst().computerId());
		ComputerCoordinatorMember adapter = fixture.snapshot.layout().members().stream()
			.map(ProductionLayout.Member::computerId).map(fixture::be)
			.map(computer -> computer.publishedCoordinatorMember().orElse(null))
			.filter(Objects::nonNull).findFirst().orElseThrow();
		ClusterBinding initial = binding(adapter, 1,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, adapter.memberId()));
		assertEquals(ClusterBindingPreparation.READY,
			adapter.prepareClusterBinding(initial));
		adapter.commitClusterBinding(initial);
		fixture.bindingAccess = ClusterBindingService.BindingAccess.READY;
		return fixture;
	}

	private static ClusterBinding binding(ComputerCoordinatorMember member, long revision,
		ClusterAuthority authority) {
		UUID cluster = member.bindingState() == null ? uuid(500)
			: member.bindingState().clusterId();
		return new ClusterBinding(cluster, revision, authority,
			List.of(new LogisticsBinding(uuid(501))));
	}

	private static Fixture fixture(NodeSpec... specs) { return new Fixture(specs); }

	public static PublishedStructure publishedStructure(UUID structureId,
		List<UUID> computerIds) {
		List<ProductionComputer> computers = new ArrayList<>();
		List<ProductionLayout.Member> nodes = new ArrayList<>();
		for (int i = 0; i < computerIds.size(); i++) {
			BlockPos position = pos(i);
			UUID computerId = computerIds.get(i);
			computers.add(computer(computerId, position, PROFILE));
			nodes.add(new ProductionLayout.Member(computerId, address(position), PROFILE));
		}
		ProductionStructure structure = snapshot(nodes);
		ProductionStructure.Revision record = new ProductionStructure.Revision(structureId, 0,
			computerIds.getFirst(), structure);
		computers.forEach(computer -> computer.applyTopologyState(record, null,
			true, null, Set.of()));
		computers.getFirst().publishCoordinatorMember(computers, structure.layout().members().stream().map(ProductionLayout.Member::computerId).collect(java.util.stream.Collectors.toSet()));
		return new PublishedStructure(structureId, record, computers);
	}

	public static final class PublishedStructure {
		private final UUID structureId;
		private final ProductionStructure.Revision record;
		private final List<ProductionComputer> computers;

		private PublishedStructure(UUID structureId, ProductionStructure.Revision record,
			List<ProductionComputer> computers) {
			this.structureId = structureId;
			this.record = record;
			this.computers = List.copyOf(computers);
		}

		public UUID structureId() { return structureId; }
		public ProductionStructure.Revision record() { return record; }
		public List<ProductionComputer> computers() { return computers; }
		public ComputerCoordinatorMember coordinator() {
			return computers.getFirst().coordinatorMember;
		}
		public void installTopology(int index, ClusterBinding binding) {
			computers.get(index).applyTopologyState(record, binding, true, null, Set.of());
		}
		public void republish() {
			computers.getFirst().publishCoordinatorMember(computers,
				record.structure().layout().members().stream().map(ProductionLayout.Member::computerId).collect(java.util.stream.Collectors.toSet()));
		}
	}

	private static NodeSpec profiled(UUID id, BlockPos pos) { return new NodeSpec(id, pos, PROFILE); }
	private static NodeSpec empty(UUID id, BlockPos pos) { return new NodeSpec(id, pos, null); }
	private static ProductionLayout.Member profiledNode(UUID id, BlockPos pos) {
		return new ProductionLayout.Member(id, address(pos), PROFILE);
	}

	private static ProductionComputer computer(UUID id, BlockPos pos,
		ProductionResourceProfile profile) {
		ProductionComputer computer = new ObservedComputer(pos).production();
		CompoundTag saved = save(computer);
		saved.getCompound("ComputerData").putUUID("ComputerId", id);
		physical(computer).read(saved, REGISTRIES, false);
		if (profile != null)
			physical(computer).installResidentSnapshot(capturedBox(id), profile);
		return computer;
	}
	private static ItemStack capturedBox(UUID id) {
		ItemStack stack = new ItemStack(Items.PAPER);
		CompoundTag captured = new CompoundTag();
		captured.putString("id", "minecraft:villager");
		captured.putUUID("UUID", id);
		captured.put("Offers", new CompoundTag());
		captured.put("Brain", new CompoundTag());
		captured.put("Inventory", new ListTag());
		CompoundTag itemData = new CompoundTag();
		itemData.put("CapturedEntity", captured);
		CBItemData.set(stack, itemData);
		return stack;
	}

	private static ProductionStructure snapshot(List<ProductionLayout.Member> nodes) {
		BoundingBox bounds = new BoundingBox(0, 0, 0, 2, 2, 2);
		Set<BlockPos> casing = new LinkedHashSet<>();
		for (int x = 0; x <= 2; x++)
			for (int y = 0; y <= 2; y++)
				for (int z = 0; z <= 2; z++)
					if (x == 0 || x == 2 || y == 0 || y == 2 || z == 0 || z == 2)
						casing.add(new BlockPos(x, y, z));
		return new ProductionStructure(new ProductionLayout(productionBounds(bounds), nodes.stream().sorted(java.util.Comparator.comparing(
				ProductionLayout.Member::computerId)).toList(), List.of()), casing, Set.of(new ChunkPos(0, 0)));
	}

	private static BlockPos pos(int x) { return new BlockPos(x, 1, 1); }
	private static ProductionLayout.Address address(BlockPos pos) {
		return new ProductionLayout.Address(Level.OVERWORLD, null, pos);
	}
	private static UUID uuid(long value) { return new UUID(0, value); }
	private static CompoundTag save(ProductionComputer computer) {
		CompoundTag tag = new CompoundTag();
		physical(computer).write(tag, REGISTRIES, false);
		return tag;
	}
	private static Map<UUID, CompoundTag> bytes(Fixture fixture) {
		Map<UUID, CompoundTag> result = new LinkedHashMap<>();
		fixture.computers.values().forEach(computer ->
			result.put(computer.computerId().orElseThrow(), save(computer)));
		return result;
	}

	private record NodeSpec(UUID id, BlockPos pos, ProductionResourceProfile profile) {}

	private static final class ObservedComputer extends TestComputer {
		private final ProductionLayout.Address address;
		private Runnable onBindingPublished = () -> {};
		private ObservedComputer(BlockPos pos) {
			super(BlockEntityType.FURNACE, pos, Blocks.FURNACE.defaultBlockState());
			address = ComputerCoordinatorMemberTest.address(pos);
		}
		@Override public void sendData() { super.sendData(); onBindingPublished.run(); }
	}

	private static final class Fixture implements ComputerTopologyController.WorldAccess {
		private final Map<UUID, ProductionComputer> computers = new LinkedHashMap<>();
		private final Map<BlockPos, ProductionComputer> byPosition = new HashMap<>();
		private final Set<BlockPos> unloaded = new HashSet<>();
		private ProductionStructure snapshot;
		private ComputerStructureScanner.State scanState;
		private ClusterBindingService.BindingAccess bindingAccess =
			ClusterBindingService.BindingAccess.CONFLICT;
		private Function<ComputerCoordinatorMember,
			ClusterBindingService.BindingAccess> bindingAccessEffect;
		private int bindingAccessCalls;
		private int probes;

		private Fixture(NodeSpec... specs) {
			List<ProductionLayout.Member> nodes = new ArrayList<>();
			for (NodeSpec spec : specs) {
				ProductionComputer computer = computer(spec.id(), spec.pos(), spec.profile());
				put(computer);
				nodes.add(new ProductionLayout.Member(spec.id(), address(spec.pos()), spec.profile()));
			}
			snapshot = snapshot(nodes);
			scanState = nodes.stream().anyMatch(node -> node.profile() == null)
				? ComputerStructureScanner.State.VALID_NOT_READY
				: ComputerStructureScanner.State.VALID;
		}

		private void put(ProductionComputer computer) {
			UUID id = computer.computerId().orElseThrow();
			computers.put(id, computer);
			byPosition.put(computer.owner().getBlockPos(), computer);
			unloaded.remove(computer.owner().getBlockPos());
		}
		private ProductionComputer be(UUID id) {
			return Objects.requireNonNull(computers.get(id));
		}
		private void refresh(UUID id) {
			ComputerTopologyController.refresh(be(id), this, LIMITS);
		}
		private void unload(UUID id) {
			ProductionComputer computer = be(id);
			computer.owner().invalidate();
			computers.remove(id);
			byPosition.remove(computer.owner().getBlockPos());
			unloaded.add(computer.owner().getBlockPos());
		}
		private void destroy(UUID id) {
			ProductionComputer computer = be(id);
			computer.owner().invalidate();
			computers.remove(id);
			byPosition.remove(computer.owner().getBlockPos());
			unloaded.remove(computer.owner().getBlockPos());
		}
		private void resetProbes() { probes = 0; }
		private int worldProbes() { return probes; }

		@Override public ComputerStructureScanner.ScanResult scan(BlockPos seed,
			ComputerStructureScanner.Limits limits) {
			probes++;
			UUID observed = computers.values().stream()
				.map(ProductionComputer::computerStructureMemberId)
				.flatMap(java.util.Optional::stream).findFirst().orElse(null);
			return new ComputerStructureScanner.ScanResult(scanState, snapshot, observed);
		}
		@Override public boolean isLoaded(BlockPos pos) { probes++; return !unloaded.contains(pos); }
		@Override public boolean isChunkLoaded(ChunkPos chunk) { probes++; return true; }
		@Override public boolean sameSpace(BlockPos origin, BlockPos pos) { probes++; return true; }
		@Override public ProductionComputer loadedComputer(BlockPos pos) {
			probes++;
			return unloaded.contains(pos) ? null : byPosition.get(pos);
		}
		@Override public ProductionLayout.Address address(BlockPos pos) { return ComputerCoordinatorMemberTest.address(pos); }
		@Override public UUID newStructureMemberId() { return uuid(600); }
		@Override public ClusterBindingService.BindingAccess bindingAccess(
			ComputerCoordinatorMember member) {
			bindingAccessCalls++;
			return bindingAccessEffect == null ? bindingAccess
				: bindingAccessEffect.apply(member);
		}
		@Override public boolean enforceFoundationPublication() { return true; }
	}

	private static final class ReadyQuiescence implements EpochQuiescence {
		@Override public boolean allRootsStopped() { return true; }
		@Override public boolean nodeIdle(UUID computerId) { return true; }
		@Override public boolean nodeRootFree(UUID computerId) { return true; }
		@Override public boolean nodeMailboxEmpty(UUID computerId) { return true; }
	}

	private static void installEmptyLoadingModList() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) != null) return;
			Object empty = type.getMethod("of", List.class, List.class, List.class,
				List.class, java.util.Map.class).invoke(null, List.of(), List.of(),
					List.of(), List.of(), java.util.Map.of());
			if (empty == null) throw new IllegalStateException("LoadingModList.of returned null");
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
	private static ProductionLayout.Bounds productionBounds(BoundingBox bounds) {
		return new ProductionLayout.Bounds(bounds.minX(), bounds.minY(), bounds.minZ(),
			bounds.maxX(), bounds.maxY(), bounds.maxZ());
	}
}
