package com.nobodiiiii.createbiotech.content.factorycluster.communication;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;

class ClusterCommunicationConfigurationTest {
	private static final UUID CLUSTER = id(1);
	private static final UUID EPOCH = id(2);
	private static final UUID PANEL = id(3);
	private static final ClusterCommunicationCommandService.CommandAuthority FORMED =
		new ClusterCommunicationCommandService.CommandAuthority(CLUSTER, EPOCH,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, id(4)), PANEL);
	private static final ClusterCommunicationCommandService.CommandAuthority UNFORMED =
		new ClusterCommunicationCommandService.CommandAuthority(CLUSTER, EPOCH,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, id(4)), PANEL);

	@Test
	void onlyFormedClusterCanChangeRevisionFencedConfiguration() {
		ClusterCommunicationDirectory directory = new ClusterCommunicationDirectory();
		ClusterCommunicationCommandService service = new ClusterCommunicationCommandService(directory,
			authority -> authority == FORMED
				? ClusterCommunicationCommandService.AuthorityStatus.READY
				: ClusterCommunicationCommandService.AuthorityStatus.UNFORMED,
			ClusterCommunicationCommandService.RoleTransitionPolicy.permissive());

		ClusterCommunicationConfiguration initial = directory.ensure(CLUSTER);
		assertEquals(ClusterCommunicationCommandService.ChangeResult.UNFORMED,
			service.changePrimary(UNFORMED, CommunicationFrequency.of("A"), initial.revision()));
		assertEquals(ClusterCommunicationCommandService.ChangeResult.APPLIED,
			service.changeRole(FORMED, ClusterServiceRole.AGENT, initial.revision()));
		assertEquals(ClusterCommunicationCommandService.ChangeResult.STALE,
			service.changeRole(FORMED, ClusterServiceRole.STANDARD, initial.revision()));
		assertEquals(new ClusterCommunicationConfiguration(CLUSTER, CommunicationFrequency.UNCONFIGURED,
			ClusterServiceRole.AGENT, 1), directory.configuration(CLUSTER).orElseThrow());
	}

	@Test
	void exactAuthorityEpochAndLoadedLocalMemberAreRequiredBeforeAnyCas() {
		ClusterCommunicationDirectory directory = new ClusterCommunicationDirectory();
		ClusterCommunicationCommandService service = new ClusterCommunicationCommandService(directory,
			authority -> switch (authority.epochId()) {
				case UUID ignored when authority.localMemberId().equals(id(10)) ->
					ClusterCommunicationCommandService.AuthorityStatus.STALE_EPOCH;
				case UUID ignored when authority.localMemberId().equals(id(11)) ->
					ClusterCommunicationCommandService.AuthorityStatus.AUTHORITY_OFFLINE;
				case UUID ignored when authority.localMemberId().equals(id(12)) ->
					ClusterCommunicationCommandService.AuthorityStatus.LOCAL_MEMBER_UNLOADED;
				default -> ClusterCommunicationCommandService.AuthorityStatus.READY;
			}, ClusterCommunicationCommandService.RoleTransitionPolicy.permissive());
		ClusterCommunicationConfiguration initial = directory.ensure(CLUSTER);

		assertEquals(ClusterCommunicationCommandService.ChangeResult.STALE_EPOCH,
			service.changePrimary(withLocalMember(10), CommunicationFrequency.of("A"), initial.revision()));
		assertEquals(ClusterCommunicationCommandService.ChangeResult.AUTHORITY_OFFLINE,
			service.changePrimary(withLocalMember(11), CommunicationFrequency.of("A"), initial.revision()));
		assertEquals(ClusterCommunicationCommandService.ChangeResult.LOCAL_MEMBER_UNLOADED,
			service.changePrimary(withLocalMember(12), CommunicationFrequency.of("A"), initial.revision()));
		assertEquals(initial, directory.configuration(CLUSTER).orElseThrow());
	}

	@Test
	void durableDirectoryUsesExactRevisionCasAndCurrentSchemaValues() {
		ClusterCommunicationDirectory directory = new ClusterCommunicationDirectory();
		ClusterCommunicationConfiguration initial = directory.ensure(CLUSTER);
		ClusterCommunicationConfiguration changed = new ClusterCommunicationConfiguration(CLUSTER,
			CommunicationFrequency.of("  Raw #Frequency  "), ClusterServiceRole.STANDARD, 1);

		assertTrue(directory.compareAndSet(CLUSTER, initial.revision(), changed));
		assertFalse(directory.compareAndSet(CLUSTER, initial.revision(), changed));
		assertEquals(changed, directory.configuration(CLUSTER).orElseThrow());
		assertEquals(CommunicationFrequency.of("  Raw #Frequency  "), directory.primaryFrequency(CLUSTER));
		assertEquals(CommunicationFrequency.UNCONFIGURED, directory.primaryFrequency(id(99)));
	}

	private static ClusterCommunicationCommandService.CommandAuthority withLocalMember(long id) {
		return new ClusterCommunicationCommandService.CommandAuthority(CLUSTER, EPOCH,
			FORMED.authority(), ClusterCommunicationConfigurationTest.id(id));
	}

	private static UUID id(long value) { return new UUID(0, value); }
}
