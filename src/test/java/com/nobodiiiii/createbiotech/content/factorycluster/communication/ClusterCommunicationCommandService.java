package com.nobodiiiii.createbiotech.content.factorycluster.communication;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;

/** Server-authoritative CAS commands for one Cluster's durable communication declaration. */
public final class ClusterCommunicationCommandService {
	private final ClusterCommunicationDirectory directory;
	private final AuthorityValidator authorityValidator;
	private final RoleTransitionPolicy roleTransitions;
	private final ChangeListener changes;

	public ClusterCommunicationCommandService(ClusterCommunicationDirectory directory,
		AuthorityValidator authorityValidator, RoleTransitionPolicy roleTransitions) {
		this(directory, authorityValidator, roleTransitions, ChangeListener.noop());
	}

	public ClusterCommunicationCommandService(ClusterCommunicationDirectory directory,
		AuthorityValidator authorityValidator, RoleTransitionPolicy roleTransitions,
		ChangeListener changes) {
		this.directory = Objects.requireNonNull(directory, "directory");
		this.authorityValidator = Objects.requireNonNull(authorityValidator, "authorityValidator");
		this.roleTransitions = Objects.requireNonNull(roleTransitions, "roleTransitions");
		this.changes = Objects.requireNonNull(changes, "changes");
	}

	public Optional<ClusterCommunicationConfiguration> configuration(UUID clusterId) {
		return directory.configuration(clusterId);
	}

	public ChangeResult changePrimary(CommandAuthority authority,
		CommunicationFrequency primaryFrequency, long expectedRevision) {
		primaryFrequency = Objects.requireNonNull(primaryFrequency, "primaryFrequency");
		AuthorityStatus status = authorityStatus(authority);
		if (status != AuthorityStatus.READY) return ChangeResult.from(status);
		ClusterCommunicationConfiguration current = directory.ensure(authority.clusterId());
		if (current.revision() != expectedRevision) return ChangeResult.STALE;
		if (current.primaryFrequency().equals(primaryFrequency)) return ChangeResult.NO_CHANGE;
		if (current.revision() == Long.MAX_VALUE) return ChangeResult.REVISION_EXHAUSTED;
		ClusterCommunicationConfiguration next = new ClusterCommunicationConfiguration(
			current.clusterId(), primaryFrequency, current.role(), current.revision() + 1);
		if (!directory.compareAndSet(current.clusterId(), expectedRevision, next))
			return ChangeResult.STALE;
		changes.changed(current, next);
		return ChangeResult.APPLIED;
	}

	public ChangeResult changeRole(CommandAuthority authority, ClusterServiceRole role,
		long expectedRevision) {
		role = Objects.requireNonNull(role, "role");
		AuthorityStatus status = authorityStatus(authority);
		if (status != AuthorityStatus.READY) return ChangeResult.from(status);
		ClusterCommunicationConfiguration current = directory.ensure(authority.clusterId());
		if (current.revision() != expectedRevision) return ChangeResult.STALE;
		if (current.role() == role) return ChangeResult.NO_CHANGE;
		RoleTransitionResult transition = roleTransitions.validate(authority, current, role);
		if (transition != RoleTransitionResult.ALLOWED) return ChangeResult.from(transition);
		if (current.revision() == Long.MAX_VALUE) return ChangeResult.REVISION_EXHAUSTED;
		ClusterCommunicationConfiguration next = new ClusterCommunicationConfiguration(
			current.clusterId(), current.primaryFrequency(), role, current.revision() + 1);
		if (!directory.compareAndSet(current.clusterId(), expectedRevision, next))
			return ChangeResult.STALE;
		changes.changed(current, next);
		return ChangeResult.APPLIED;
	}

	private AuthorityStatus authorityStatus(CommandAuthority authority) {
		authority = Objects.requireNonNull(authority, "authority");
		return Objects.requireNonNull(authorityValidator.validate(authority),
			"authority validator result");
	}

	@FunctionalInterface
	public interface AuthorityValidator {
		AuthorityStatus validate(CommandAuthority authority);
	}

	@FunctionalInterface
	public interface RoleTransitionPolicy {
		RoleTransitionResult validate(CommandAuthority authority,
			ClusterCommunicationConfiguration current, ClusterServiceRole nextRole);

		static RoleTransitionPolicy permissive() {
			return (authority, current, nextRole) -> RoleTransitionResult.ALLOWED;
		}
	}

	@FunctionalInterface
	public interface ChangeListener {
		void changed(ClusterCommunicationConfiguration previous,
			ClusterCommunicationConfiguration next);

		static ChangeListener noop() { return (previous, next) -> {}; }
	}

	/** Exact server-derived authority and the Panel/member issuing the local command. */
	public record CommandAuthority(UUID clusterId, UUID epochId, ClusterAuthority authority,
		UUID localMemberId) {
		public CommandAuthority {
			clusterId = Objects.requireNonNull(clusterId, "clusterId");
			epochId = Objects.requireNonNull(epochId, "epochId");
			authority = Objects.requireNonNull(authority, "authority");
			localMemberId = Objects.requireNonNull(localMemberId, "localMemberId");
		}
	}

	public enum AuthorityStatus {
		READY,
		UNFORMED,
		STALE_EPOCH,
		AUTHORITY_OFFLINE,
		LOCAL_MEMBER_UNLOADED
	}

	public enum RoleTransitionResult {
		ALLOWED,
		AGENT_FORMATION_REQUIRED,
		UNSAFE_REVOCATION
	}

	public enum ChangeResult {
		APPLIED,
		NO_CHANGE,
		STALE,
		UNFORMED,
		STALE_EPOCH,
		AUTHORITY_OFFLINE,
		LOCAL_MEMBER_UNLOADED,
		AGENT_FORMATION_REQUIRED,
		UNSAFE_REVOCATION,
		REVISION_EXHAUSTED;

		private static ChangeResult from(AuthorityStatus status) {
			return switch (status) {
				case READY -> throw new IllegalArgumentException("READY has no rejection result");
				case UNFORMED -> UNFORMED;
				case STALE_EPOCH -> STALE_EPOCH;
				case AUTHORITY_OFFLINE -> AUTHORITY_OFFLINE;
				case LOCAL_MEMBER_UNLOADED -> LOCAL_MEMBER_UNLOADED;
			};
		}

		private static ChangeResult from(RoleTransitionResult transition) {
			return switch (transition) {
				case ALLOWED -> throw new IllegalArgumentException("ALLOWED has no rejection result");
				case AGENT_FORMATION_REQUIRED -> AGENT_FORMATION_REQUIRED;
				case UNSAFE_REVOCATION -> UNSAFE_REVOCATION;
			};
		}
	}
}
