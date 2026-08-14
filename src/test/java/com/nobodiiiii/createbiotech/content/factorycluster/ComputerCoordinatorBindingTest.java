package com.nobodiiiii.createbiotech.content.factorycluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerCoordinatorMemberTest;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerCoordinatorMemberTest.PublishedStructure;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelBlockEntity;
import com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternStorageCoreBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;

class ComputerCoordinatorBindingTest {
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
	void realPanelPatternAndComputerAdvanceZeroToOneToTwoAndTransferAuthority() {
		UUID cluster = uuid(100);
		LogisticsBinding network = new LogisticsBinding(uuid(101), "network");
		TestPanel panel = new TestPanel(pos(20));
		panel.commitClusterBinding(new ClusterBinding(cluster, 0,
			new ClusterAuthority(ClusterMemberType.PANEL, panel.memberId()), List.of(network)));
		TestPattern pattern = new TestPattern(pos(21));

		assertEquals(ClusterBindingService.BindResult.OK,
			ClusterBindingService.bind(panel, pattern, List.of(), id -> true, false, () -> {}));
		ClusterBinding revisionOne = panel.bindingState();
		assertEquals(1, revisionOne.revision());
		assertEquals(new ClusterAuthority(ClusterMemberType.PATTERN_CORE,
			pattern.memberId()), revisionOne.authority());
		assertSame(revisionOne, pattern.bindingState());

		PublishedStructure computers = ComputerCoordinatorMemberTest.publishedStructure(
			uuid(200), List.of(uuid(1), uuid(2)));
		assertEquals(ClusterBindingPreparation.READY,
			computers.coordinator().prepareClusterBinding(new ClusterBinding(cluster, 2,
				new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR,
					computers.structureId()), revisionOne.logisticsBindings())));
		assertEquals(ClusterBindingService.BindResult.OK,
			ClusterBindingService.bind(pattern, computers.coordinator(),
				List.of(panel), id -> true, false, () -> {}));

		ClusterBinding revisionTwo = computers.coordinator().bindingState();
		assertEquals(2, revisionTwo.revision());
		assertEquals(new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR,
			computers.structureId()), revisionTwo.authority());
		assertEquals(computers.structureId(), computers.coordinator().memberId());
		assertTrue(computers.computers().stream().noneMatch(computer ->
			revisionTwo.authority().memberId().equals(computer.computerId().orElseThrow())));
		assertSame(revisionTwo, panel.bindingState());
		assertSame(revisionTwo, pattern.bindingState());
		computers.computers().forEach(computer ->
			assertSame(revisionTwo, computer.coordinatorMember.bindingState()));
	}

	@Test
	void staleInternalReplicaRefusesAFoundationTransactionWithoutPartialCommit() {
		UUID cluster = uuid(300);
		LogisticsBinding network = new LogisticsBinding(uuid(301), "network");
		TestPanel panel = new TestPanel(pos(20));
		panel.commitClusterBinding(new ClusterBinding(cluster, 0,
			new ClusterAuthority(ClusterMemberType.PANEL, panel.memberId()), List.of(network)));
		PublishedStructure computers = ComputerCoordinatorMemberTest.publishedStructure(
			uuid(302), List.of(uuid(3), uuid(4)));
		ClusterBinding ahead = new ClusterBinding(cluster, 5,
			new ClusterAuthority(ClusterMemberType.PANEL, panel.memberId()), List.of(network));
		computers.installTopology(1, ahead);
		computers.republish();
		ClusterBinding panelBefore = panel.bindingState();
		ClusterBinding replicaBefore = computers.computers().get(1)
			.coordinatorMember.bindingState();

		assertEquals(ClusterBindingService.BindResult.PARTICIPANT_REJECTED,
			ClusterBindingService.bind(panel, computers.coordinator(), List.of(),
				id -> true, false, () -> {}));

		assertSame(panelBefore, panel.bindingState());
		assertSame(replicaBefore,
			computers.computers().get(1).coordinatorMember.bindingState());
	}

	@Test
	void twoRealComputerStructuresInOneParticipantSetReportComputerConflict() {
		UUID cluster = uuid(400);
		LogisticsBinding network = new LogisticsBinding(uuid(401), "network");
		TestPanel panel = new TestPanel(pos(20));
		panel.commitClusterBinding(new ClusterBinding(cluster, 0,
			new ClusterAuthority(ClusterMemberType.PANEL, panel.memberId()), List.of(network)));
		PublishedStructure first = ComputerCoordinatorMemberTest.publishedStructure(
			uuid(410), List.of(uuid(5)));
		PublishedStructure second = ComputerCoordinatorMemberTest.publishedStructure(
			uuid(420), List.of(uuid(6)));

		assertEquals(ClusterBindingService.BindResult.CONFLICT,
			ClusterBindingService.bind(panel, first.coordinator(),
				List.of(second.coordinator()), id -> true, false, () -> {}));
		assertTrue(first.computers().stream().allMatch(computer ->
			computer.coordinatorMember.bindingState() == null));
		assertTrue(second.computers().stream().allMatch(computer ->
			computer.coordinatorMember.bindingState() == null));
	}

	private static final class TestPanel extends FactoryPanelBlockEntity {
		private final SpaceAddress address;
		private TestPanel(BlockPos pos) {
			super(BlockEntityType.FURNACE, pos, Blocks.FURNACE.defaultBlockState());
			address = address(pos);
		}
		@Override public SpaceAddress memberAddress() { return address; }
	}

	private static final class TestPattern extends PatternStorageCoreBlockEntity {
		private final SpaceAddress address;
		private TestPattern(BlockPos pos) {
			super(BlockEntityType.FURNACE, pos, Blocks.FURNACE.defaultBlockState());
			address = address(pos);
		}
		@Override public SpaceAddress memberAddress() { return address; }
	}

	private static BlockPos pos(int x) { return new BlockPos(x, 1, 1); }
	private static SpaceAddress address(BlockPos pos) {
		return new SpaceAddress(Level.OVERWORLD, null, pos);
	}
	private static UUID uuid(long value) { return new UUID(0, value); }

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
}
