package com.nobodiiiii.createbiotech.content.factorycluster.communication;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;

/**
 * Auditable classification of the exact server facts authorizing a Cluster communication CAS.
 * In particular, a loaded Computer with a valid binding and matching epoch is still not an
 * authority while its elected coordinator is offline.
 */
public final class ProductionCommunicationAuthorityPolicy {
	private ProductionCommunicationAuthorityPolicy() {}

	public static ClusterCommunicationCommandService.AuthorityStatus validate(
		ClusterCommunicationCommandService.CommandAuthority command, Evidence evidence) {
		ClusterCommunicationCommandService.CommandAuthority exactCommand =
			Objects.requireNonNull(command, "command");
		Evidence exactEvidence = Objects.requireNonNull(evidence, "evidence");
		if (exactEvidence.formedEpochId().isEmpty())
			return ClusterCommunicationCommandService.AuthorityStatus.UNFORMED;
		if (!exactEvidence.formedEpochId().orElseThrow().equals(exactCommand.epochId()))
			return ClusterCommunicationCommandService.AuthorityStatus.STALE_EPOCH;

		List<AuthorityMember> authorities = exactEvidence.authorityMembers().stream()
			.filter(member -> member.type() == exactCommand.authority().type()
				&& member.memberId().equals(exactCommand.authority().memberId()))
			.toList();
		if (authorities.size() != 1)
			return ClusterCommunicationCommandService.AuthorityStatus.AUTHORITY_OFFLINE;
		AuthorityMember authority = authorities.getFirst();
		if (!validBinding(authority, exactCommand))
			return ClusterCommunicationCommandService.AuthorityStatus.AUTHORITY_OFFLINE;
		List<AuthorityMember> coordinators = exactEvidence.authorityMembers().stream()
			.filter(member -> member.type() == ClusterMemberType.COMPUTER_COORDINATOR).toList();
		if (coordinators.size() != 1 || !validBinding(coordinators.getFirst(), exactCommand)
			|| coordinators.getFirst().computerCoordinator().isEmpty())
			return ClusterCommunicationCommandService.AuthorityStatus.AUTHORITY_OFFLINE;
		ComputerCoordinator coordinator = coordinators.getFirst().computerCoordinator().orElseThrow();
		if (!coordinator.exactEpoch() || !coordinator.online())
			return ClusterCommunicationCommandService.AuthorityStatus.AUTHORITY_OFFLINE;

		List<LocalPanel> localPanels = exactEvidence.localPanels().stream()
			.filter(panel -> panel.memberId().equals(exactCommand.localMemberId())).toList();
		if (localPanels.size() != 1
			|| !localPanels.getFirst().publishedClusterId()
				.equals(Optional.of(exactCommand.clusterId()))
			|| !localPanels.getFirst().publishedEpochId()
				.equals(Optional.of(exactCommand.epochId())))
			return ClusterCommunicationCommandService.AuthorityStatus.LOCAL_MEMBER_UNLOADED;
		return ClusterCommunicationCommandService.AuthorityStatus.READY;
	}

	private static boolean validBinding(AuthorityMember member,
		ClusterCommunicationCommandService.CommandAuthority command) {
		ClusterBinding binding = member.binding().orElse(null);
		return binding != null && member.bindingValid()
			&& binding.clusterId().equals(command.clusterId())
			&& binding.authority().equals(command.authority());
	}

	public record Evidence(Optional<UUID> formedEpochId,
		List<AuthorityMember> authorityMembers, List<LocalPanel> localPanels) {
		public Evidence {
			formedEpochId = Objects.requireNonNull(formedEpochId, "formedEpochId");
			authorityMembers = List.copyOf(Objects.requireNonNull(authorityMembers,
				"authorityMembers"));
			localPanels = List.copyOf(Objects.requireNonNull(localPanels, "localPanels"));
		}
	}

	public record AuthorityMember(ClusterMemberType type, UUID memberId,
		Optional<ClusterBinding> binding, boolean bindingValid,
		Optional<ComputerCoordinator> computerCoordinator) {
		public AuthorityMember {
			type = Objects.requireNonNull(type, "type");
			memberId = Objects.requireNonNull(memberId, "memberId");
			binding = Objects.requireNonNull(binding, "binding");
			computerCoordinator = Objects.requireNonNull(computerCoordinator,
				"computerCoordinator");
			if (type != ClusterMemberType.COMPUTER_COORDINATOR
				&& computerCoordinator.isPresent())
				throw new IllegalArgumentException(
					"non-Computer authority cannot include coordinator state");
		}
	}

	public record ComputerCoordinator(boolean exactEpoch, boolean online) {}

	public record LocalPanel(UUID memberId, Optional<UUID> publishedClusterId,
		Optional<UUID> publishedEpochId) {
		public LocalPanel {
			memberId = Objects.requireNonNull(memberId, "memberId");
			publishedClusterId = Objects.requireNonNull(publishedClusterId,
				"publishedClusterId");
			publishedEpochId = Objects.requireNonNull(publishedEpochId, "publishedEpochId");
			if (publishedClusterId.isPresent() != publishedEpochId.isPresent())
				throw new IllegalArgumentException("partial Panel publication identity");
		}
	}
}
