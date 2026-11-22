package com.nobodiiiii.createbiotech.content.factorycluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer.ProductionComputerTestAccess.authoritativeTopology;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer.ComputerCoordinatorMemberTest;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer.ComputerCoordinatorMemberTest.PublishedStructure;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelBlockEntity;

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
	void realPanelCanStillTransferAuthorityDirectlyToItsComputerCoordinator() {
		UUID cluster = uuid(100);
		TestPanel panel = new TestPanel(pos(20));
		panel.commitClusterBinding(new ClusterBinding(cluster, 0,
			new ClusterAuthority(ClusterMemberType.PANEL, panel.memberId()),
			List.of(new LogisticsBinding(uuid(101)))));
		var computers = ComputerCoordinatorMemberTest.publishedStructure(uuid(200), List.of(uuid(1), uuid(2)));
		assertEquals(ClusterBindingService.BindResult.OK,
			ClusterBindingService.bind(panel, located(computers), List.of(), id -> true, false, () -> {}));
		var binding = panel.bindingState();
		assertEquals(1, binding.revision());
		assertEquals(new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, computers.structureId()),
			binding.authority());
		computers.computers().forEach(computer ->
			assertEquals(binding, computer.coordinatorMember.bindingState()));
	}

	@Test
	void staleInternalReplicaRefusesAFoundationTransactionWithoutPartialCommit() {
		UUID cluster = uuid(300);
		LogisticsBinding network = new LogisticsBinding(uuid(301));
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
		var topologyBefore = computers.computers().stream()
			.map(computer -> authoritativeTopology(computer)).toList();
		assertEquals(ahead.productionBinding(), topologyBefore.get(1).binding());

		assertEquals(ClusterBindingService.BindResult.PARTICIPANT_REJECTED,
			ClusterBindingService.bind(panel, located(computers), List.of(),
				id -> true, false, () -> {}));

		assertSame(panelBefore, panel.bindingState());
		for (int i = 0; i < computers.computers().size(); i++) {
			var before = topologyBefore.get(i);
			var after = authoritativeTopology(computers.computers().get(i));
			assertSame(before, after, "Rejected foundation prepare must not replace any authoritative topology");
			assertSame(before.binding(), after.binding(), "No replica may stage a new ProductionBinding on refusal");
			assertEquals(before, after);
		}
		// The compatibility boundary is a fresh value projection, not the application's stored identity.
		assertEquals(replicaBefore,
			computers.computers().get(1).coordinatorMember.bindingState());
	}

	@Test
	void twoRealComputerStructuresInOneParticipantSetReportComputerConflict() {
		UUID cluster = uuid(400);
		LogisticsBinding network = new LogisticsBinding(uuid(401));
		TestPanel panel = new TestPanel(pos(20));
		panel.commitClusterBinding(new ClusterBinding(cluster, 0,
			new ClusterAuthority(ClusterMemberType.PANEL, panel.memberId()), List.of(network)));
		PublishedStructure first = ComputerCoordinatorMemberTest.publishedStructure(
			uuid(410), List.of(uuid(5)));
		PublishedStructure second = ComputerCoordinatorMemberTest.publishedStructure(
			uuid(420), List.of(uuid(6)));

		assertEquals(ClusterBindingService.BindResult.CONFLICT,
			ClusterBindingService.bind(panel, located(first),
				List.of(located(second)), id -> true, false, () -> {}));
		assertTrue(first.computers().stream().allMatch(computer ->
			computer.coordinatorMember.bindingState() == null));
		assertTrue(second.computers().stream().allMatch(computer ->
			computer.coordinatorMember.bindingState() == null));
	}

    /** The headless fixture supplies location only, just like TestPanel below.
     * All production identity, admission and mutation calls still reach the actual coordinator. */
    private static ClusterMember located(PublishedStructure structure) {
        var coordinator = structure.coordinator();
        var location = address(structure.computers().getFirst().owner().getBlockPos());
        return new ClusterMember() {
            public UUID memberId() { return coordinator.memberId(); }
            public ClusterBinding bindingState() { return coordinator.bindingState(); }
            public boolean hasValidBindingState() { return coordinator.hasValidBindingState(); }
            public ClusterMemberType memberType() { return coordinator.memberType(); }
            public SpaceAddress memberAddress() { return location; }
            public boolean canRebind() { return coordinator.canRebind(); }
            public ClusterBindingPreparation prepareClusterBinding(ClusterBinding proposed) { return coordinator.prepareClusterBinding(proposed); }
            public ClusterBindingPreparation prepareLogisticsBinding(ClusterBinding proposed) { return coordinator.prepareLogisticsBinding(proposed); }
            public void commitClusterBinding(ClusterBinding prepared) { coordinator.commitClusterBinding(prepared); }
        };
    }

	private static final class TestPanel extends FactoryPanelBlockEntity {
		private final SpaceAddress address;
		private TestPanel(BlockPos pos) {
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
