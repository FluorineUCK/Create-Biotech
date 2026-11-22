package com.nobodiiiii.createbiotech.content.factorycluster.communication;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;

class ProductionCommunicationAuthorityPolicyTest {
	private static final UUID CLUSTER = id(1), EPOCH = id(2), COMPUTER = id(3), PANEL = id(4);
	private static final ClusterAuthority AUTHORITY =
		new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, COMPUTER);
	private static final ClusterCommunicationCommandService.CommandAuthority COMMAND =
		new ClusterCommunicationCommandService.CommandAuthority(CLUSTER, EPOCH, AUTHORITY, PANEL);

	@Test
	void loadedBoundExactEpochComputerStillRejectsWhenItsCoordinatorIsOffline() {
		ProductionCommunicationAuthorityPolicy.Evidence offline = evidence(EPOCH, EPOCH, false,
			EPOCH);
		ClusterCommunicationCommandService service = service(offline);

		assertEquals(ClusterCommunicationCommandService.AuthorityStatus.AUTHORITY_OFFLINE,
			ProductionCommunicationAuthorityPolicy.validate(COMMAND, offline));
		assertEquals(ClusterCommunicationCommandService.ChangeResult.AUTHORITY_OFFLINE,
			service.changePrimary(COMMAND, CommunicationFrequency.of("authority-test"), 0));
	}

	@Test
	void panelAuthorityCannotBypassItsFormedClustersOfflineCoordinator() {
		ClusterAuthority panelAuthority = new ClusterAuthority(ClusterMemberType.PANEL, PANEL);
		var panelCommand = new ClusterCommunicationCommandService.CommandAuthority(CLUSTER, EPOCH,
			panelAuthority, PANEL);
		ClusterBinding binding = new ClusterBinding(CLUSTER, 0, panelAuthority, List.of());
		var evidence = new ProductionCommunicationAuthorityPolicy.Evidence(Optional.of(EPOCH),
			List.of(
				new ProductionCommunicationAuthorityPolicy.AuthorityMember(ClusterMemberType.PANEL,
					PANEL, Optional.of(binding), true, Optional.empty()),
				new ProductionCommunicationAuthorityPolicy.AuthorityMember(
					ClusterMemberType.COMPUTER_COORDINATOR, COMPUTER, Optional.of(binding), true,
					Optional.of(new ProductionCommunicationAuthorityPolicy.ComputerCoordinator(
						true, false)))),
			List.of(new ProductionCommunicationAuthorityPolicy.LocalPanel(PANEL,
				Optional.of(CLUSTER), Optional.of(EPOCH))));

		assertEquals(ClusterCommunicationCommandService.AuthorityStatus.AUTHORITY_OFFLINE,
			ProductionCommunicationAuthorityPolicy.validate(panelCommand, evidence));
	}

	@Test
	void exactRuntimeComputerAndPanelEpochIdentityMustAllFollowReformation() {
		UUID reformed = id(20);
		var staleCommand = new ClusterCommunicationCommandService.CommandAuthority(CLUSTER, EPOCH,
			AUTHORITY, PANEL);
		var currentCommand = new ClusterCommunicationCommandService.CommandAuthority(CLUSTER,
			reformed, AUTHORITY, PANEL);

		assertEquals(ClusterCommunicationCommandService.AuthorityStatus.STALE_EPOCH,
			ProductionCommunicationAuthorityPolicy.validate(staleCommand,
				evidence(reformed, reformed, true, reformed)));
		assertEquals(ClusterCommunicationCommandService.AuthorityStatus.AUTHORITY_OFFLINE,
			ProductionCommunicationAuthorityPolicy.validate(currentCommand,
				evidence(reformed, EPOCH, true, reformed)));
		assertEquals(ClusterCommunicationCommandService.AuthorityStatus.LOCAL_MEMBER_UNLOADED,
			ProductionCommunicationAuthorityPolicy.validate(currentCommand,
				evidence(reformed, reformed, true, EPOCH)));
		assertEquals(ClusterCommunicationCommandService.AuthorityStatus.READY,
			ProductionCommunicationAuthorityPolicy.validate(currentCommand,
				evidence(reformed, reformed, true, reformed)));
	}

	private static ClusterCommunicationCommandService service(
		ProductionCommunicationAuthorityPolicy.Evidence evidence) {
		return new ClusterCommunicationCommandService(new ClusterCommunicationDirectory(),
			authority -> ProductionCommunicationAuthorityPolicy.validate(authority, evidence),
			ClusterCommunicationCommandService.RoleTransitionPolicy.permissive());
	}

	private static ProductionCommunicationAuthorityPolicy.Evidence evidence(UUID runtimeEpoch,
		UUID computerEpoch, boolean coordinatorOnline, UUID panelEpoch) {
		ClusterBinding binding = new ClusterBinding(CLUSTER, 0, AUTHORITY, List.of());
		return new ProductionCommunicationAuthorityPolicy.Evidence(Optional.of(runtimeEpoch),
			List.of(new ProductionCommunicationAuthorityPolicy.AuthorityMember(
				ClusterMemberType.COMPUTER_COORDINATOR, COMPUTER, Optional.of(binding), true,
				Optional.of(new ProductionCommunicationAuthorityPolicy.ComputerCoordinator(
					computerEpoch.equals(runtimeEpoch), coordinatorOnline)))),
			List.of(new ProductionCommunicationAuthorityPolicy.LocalPanel(PANEL,
				Optional.of(CLUSTER), Optional.of(panelEpoch))));
	}

	private static UUID id(long value) { return new UUID(0, value); }
}
