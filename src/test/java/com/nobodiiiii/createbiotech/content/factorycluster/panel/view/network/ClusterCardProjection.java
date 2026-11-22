package com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterServiceRole;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.WorldFact;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetBoundaryService;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerMonitorSnapshot;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.LogisticsNetworkDirectoryEntry;

/** Public card for one reachable Cluster, or one deliberately opaque subnet boundary. */
public record ClusterCardProjection(String clusterId, ClusterServiceRole role,
	FormationState formationState, OperationalState operationalState, int usedSlots,
	int totalSlots, int activeTasks, int waitingTasks, LinkType linkType,
	String primaryFrequency, List<String> logisticsAliases, boolean remote,
	boolean subnetBoundary, Set<Capability> capabilities,
	Set<SubnetBoundaryService.PublicService> publicServices) {
	private static final String SUBNET_PREFIX = "subnet-";

	public ClusterCardProjection {
		clusterId = Objects.requireNonNull(clusterId, "clusterId");
		role = Objects.requireNonNull(role, "role");
		formationState = Objects.requireNonNull(formationState, "formationState");
		operationalState = Objects.requireNonNull(operationalState, "operationalState");
		linkType = Objects.requireNonNull(linkType, "linkType");
		primaryFrequency = Objects.requireNonNull(primaryFrequency, "primaryFrequency");
		logisticsAliases = sanitizeAliases(logisticsAliases);
		capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
		publicServices = Set.copyOf(Objects.requireNonNull(publicServices, "publicServices"));
		if (clusterId.length() > WorldFact.MAX_TEXT_LENGTH
			|| primaryFrequency.length() > CommunicationFrequency.MAX_LENGTH
			|| usedSlots < 0 || totalSlots < 0 || usedSlots > totalSlots
			|| activeTasks < 0 || waitingTasks < 0)
			throw new IllegalArgumentException("invalid communication card state");
		if (subnetBoundary) {
			ClusterServiceRole expectedRole = publicServices.contains(
				SubnetBoundaryService.PublicService.AGENT)
				? ClusterServiceRole.AGENT : ClusterServiceRole.STANDARD;
			if (!isCanonicalSubnetId(clusterId) || linkType != LinkType.SUBNET || !remote
				|| role != expectedRole
				|| formationState != FormationState.BOUNDARY
				|| operationalState != OperationalState.ONLINE
				|| usedSlots != 0 || totalSlots != 0 || activeTasks != 0 || waitingTasks != 0
				|| !logisticsAliases.isEmpty() || !capabilities.equals(Set.of(Capability.VIEW))
				|| !CommunicationFrequency.of(primaryFrequency).configured())
				throw new IllegalArgumentException("subnet card exposes non-public state");
		} else {
			if (!isCanonicalShortId(clusterId) || linkType == LinkType.SUBNET
				|| formationState == FormationState.BOUNDARY || !publicServices.isEmpty())
				throw new IllegalArgumentException("invalid Cluster communication card identity");
			Set<Capability> permitted = remote ? Set.of(Capability.VIEW)
				: EnumSet.of(Capability.VIEW, Capability.EDIT_DECLARATION,
					Capability.EDIT_LISTENERS);
			if (!capabilities.contains(Capability.VIEW) || !permitted.containsAll(capabilities)
				|| !remote && !capabilities.contains(Capability.EDIT_DECLARATION)
				|| remote && !capabilities.equals(Set.of(Capability.VIEW)))
				throw new IllegalArgumentException("communication card capability is not local-only");
		}
	}

	public static ClusterCardProjection cluster(UUID clusterId, ClusterServiceRole role,
		FormationState formationState, OperationalState operationalState, int usedSlots,
		int totalSlots, int activeTasks, int waitingTasks, LinkType linkType,
		CommunicationFrequency primaryFrequency, List<String> logisticsAliases, boolean remote,
		boolean listenerEditing) {
		EnumSet<Capability> capabilities = EnumSet.of(Capability.VIEW);
		if (!remote) {
			capabilities.add(Capability.EDIT_DECLARATION);
			if (listenerEditing) capabilities.add(Capability.EDIT_LISTENERS);
		}
		return new ClusterCardProjection(shortId(clusterId), role, formationState, operationalState,
			usedSlots, totalSlots, activeTasks, waitingTasks, linkType, primaryFrequency.value(),
			logisticsAliases, remote, false, capabilities, Set.of());
	}

	public static ClusterCardProjection subnet(UUID boundaryId,
		CommunicationFrequency listenerFrequency,
		Set<SubnetBoundaryService.PublicService> services) {
		services = Set.copyOf(Objects.requireNonNull(services, "services"));
		ClusterServiceRole role = services.contains(SubnetBoundaryService.PublicService.AGENT)
			? ClusterServiceRole.AGENT : ClusterServiceRole.STANDARD;
		return new ClusterCardProjection(SUBNET_PREFIX + shortId(boundaryId), role,
			FormationState.BOUNDARY, OperationalState.ONLINE, 0, 0, 0, 0, LinkType.SUBNET,
			listenerFrequency.value(), List.of(), true, true, Set.of(Capability.VIEW), services);
	}

	public static String shortId(UUID id) {
		String compact = Objects.requireNonNull(id, "id").toString().replace("-", "");
		return compact.substring(compact.length() - 8);
	}

	private static boolean isCanonicalShortId(String value) {
		if (value.length() != 8) return false;
		for (int index = 0; index < value.length(); index++) {
			char digit = value.charAt(index);
			if (!(digit >= '0' && digit <= '9' || digit >= 'a' && digit <= 'f')) return false;
		}
		return true;
	}

	public static boolean isCanonicalSubnetId(String value) {
		return value != null && value.startsWith(SUBNET_PREFIX)
			&& value.length() == SUBNET_PREFIX.length() + 8
			&& isCanonicalShortId(value.substring(SUBNET_PREFIX.length()));
	}

	private static List<String> sanitizeAliases(List<String> aliases) {
		aliases = List.copyOf(Objects.requireNonNull(aliases, "logisticsAliases"));
		if (aliases.size() > ClusterBinding.MAX_BINDINGS)
			throw new IllegalArgumentException("too many communication card aliases");
		return aliases.stream().map(LogisticsNetworkDirectoryEntry::canonicalAlias)
			.map(ComputerMonitorSnapshot::safeText)
			.map(LogisticsNetworkDirectoryEntry::canonicalAlias).toList();
	}

	public enum FormationState { FORMED, UNFORMED, BOUNDARY }
	public enum OperationalState { ONLINE, DEGRADED, OFFLINE }
	public enum LinkType { ADJACENT, ORDINARY, EXTENDED, RELAY, SUBNET }
	public enum Capability { VIEW, EDIT_DECLARATION, EDIT_LISTENERS }
}
