package com.nobodiiiii.createbiotech.content.factorycluster;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.simibubi.create.Create;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class ClusterBindingService {
	private ClusterBindingService() {}

	public static BindResult bind(ServerPlayer player, ClusterMember source,
		ClusterMember target) {
		MinecraftServer server = player.getServer();
		List<ClusterMember> loadedMembers = loadedMembersForMerge(server, source, target);
		boolean conflict = hasStructuralConflict(server, source.clusterId())
			|| hasStructuralConflict(server, target.clusterId());
		BindResult result = bind(source, target, loadedMembers,
			id -> Create.LOGISTICS.mayAdministrate(id, player), conflict,
			() -> ClusterBindingSelection.clear(player));
		return notify(player, result);
	}

	/**
	 * Replaces the exact ordered binding list for every loaded participant after the
	 * authoritative member and all known prior authorities have been validated.
	 */
	public static BindResult replaceBindings(ServerPlayer player, ClusterMember source,
		List<LogisticsBinding> bindings) {
		MinecraftServer server = player.getServer();
		UUID clusterId = source.clusterId();
		List<ClusterMember> members = clusterId == null ? List.of()
			: loadedMembers(server, clusterId);
		BindResult result = replaceBindings(source, bindings, members,
			id -> Create.LOGISTICS.mayAdministrate(id, player),
			hasStructuralConflict(server, clusterId));
		return notify(player, result);
	}

	static BindResult validate(ClusterMember source, ClusterMember target,
		Predicate<UUID> mayAdministrate,
		Collection<ClusterMember> loadedClusterMembers) {
		return mergePlan(source, target, loadedClusterMembers, mayAdministrate).result();
	}

	static BindResult bind(ClusterMember source, ClusterMember target,
		Collection<ClusterMember> loadedClusterMembers,
		Predicate<UUID> mayAdministrate, boolean conflict,
		Runnable clearSelection) {
		if (source.bindingState() == null)
			return BindResult.NO_SOURCE;
		if (conflict)
			return BindResult.CONFLICT;

		BindingPlan plan = mergePlan(source, target, loadedClusterMembers,
			mayAdministrate);
		if (!plan.result().succeeded())
			return plan.result();
		commit(plan);
		clearSelection.run();
		return BindResult.OK;
	}

	static BindResult replaceBindings(ClusterMember source,
		List<LogisticsBinding> requestedBindings,
		Collection<ClusterMember> loadedClusterMembers,
		Predicate<UUID> mayAdministrate, boolean conflict) {
		ClusterBinding sourceState = source.bindingState();
		if (sourceState == null)
			return BindResult.NO_SOURCE;
		if (conflict)
			return BindResult.CONFLICT;

		List<ClusterMember> members = loadedClusterMembers.stream()
			.filter(member -> sourceState.clusterId().equals(member.clusterId()))
			.toList();
		List<ClusterMember> participants = participants(source, null, members);
		BindingPlan plan = replacementPlan(sourceState.clusterId(), participants,
			requestedBindings, mayAdministrate);
		if (!plan.result().succeeded())
			return plan.result();
		commit(plan);
		return BindResult.OK;
	}

	public static ReconcileResult reconcileLoaded(MinecraftServer server,
		ClusterMember loadingMember) {
		UUID clusterId = loadingMember.clusterId();
		return clusterId == null ? ReconcileResult.CONFLICT
			: reconcileLoaded(loadingMember, loadedMembers(server, clusterId));
	}

	static ReconcileResult reconcileLoaded(ClusterMember loadingMember,
		Collection<ClusterMember> loadedClusterMembers) {
		ClusterBinding loadingState = loadingMember.bindingState();
		if (loadingState == null || !loadingMember.hasValidBindingState()
			|| loadingState.authority() == null)
			return ReconcileResult.CONFLICT;

		List<ClusterMember> participants = participants(loadingMember, null,
			loadedClusterMembers).stream()
			.filter(member -> loadingState.clusterId().equals(member.clusterId()))
			.toList();
		AuthorityResolution canonical = resolveAuthority(loadingState, participants);
		if (canonical.result() != ReconcileResult.CURRENT)
			return canonical.result();
		ClusterBinding authoritativeState = canonical.state();

		List<ClusterMember> stale = new ArrayList<>();
		for (ClusterMember participant : participants) {
			ClusterBinding state = participant.bindingState();
			if (state == null || !participant.hasValidBindingState())
				return ReconcileResult.CONFLICT;
			AuthorityResolution resolution = resolveAuthority(state, participants);
			if (resolution.result() != ReconcileResult.CURRENT)
				return resolution.result();
			if (!canonical.authority().equals(resolution.authority())
				|| !authoritativeState.equals(resolution.state())
				|| state.revision() > authoritativeState.revision()
				|| (state.revision() == authoritativeState.revision()
					&& !state.equals(authoritativeState)))
				return ReconcileResult.CONFLICT;
			if (!state.equals(authoritativeState))
				stale.add(participant);
		}

		for (ClusterMember participant : stale)
			if (prepare(participant, authoritativeState) != BindResult.OK)
				return ReconcileResult.CONFLICT;
		stale.forEach(member -> member.commitClusterBinding(authoritativeState));
		return stale.isEmpty() ? ReconcileResult.CURRENT : ReconcileResult.UPDATED;
	}

	public static BindingAccess bindingAccess(MinecraftServer server,
		ClusterMember member) {
		return switch (reconcileLoaded(server, member)) {
			case CURRENT, UPDATED -> BindingAccess.READY;
			case AUTHORITY_OFFLINE -> BindingAccess.AUTHORITY_OFFLINE;
			case CONFLICT -> BindingAccess.CONFLICT;
		};
	}

	static boolean loadedBindingConflict(Collection<ClusterMember> loadedMembers) {
		List<ClusterMember> participants = List.copyOf(loadedMembers);
		if (participants.stream().anyMatch(member -> !member.hasValidBindingState()
			|| member.bindingState() == null || member.bindingState().authority() == null))
			return true;
		Set<ClusterAuthority> authorities = new java.util.HashSet<>();
		participants.forEach(member -> authorities.add(member.bindingState().authority()));
		if (authorities.size() != 1)
			return true;
		ClusterAuthority authorityId = authorities.iterator().next();
		ClusterMember authority = findAuthority(authorityId, participants);
		if (authority == null || !authorityId.identifies(authority))
			return true;
		ClusterBinding authoritativeState = authority.bindingState();
		if (authoritativeState == null || !authorityId.equals(authoritativeState.authority()))
			return true;
		return participants.stream().map(ClusterMember::bindingState)
			.anyMatch(state -> !state.equals(authoritativeState));
	}

	private static BindingPlan mergePlan(ClusterMember source, ClusterMember target,
		Collection<ClusterMember> loadedClusterMembers,
		Predicate<UUID> mayAdministrate) {
		List<ClusterMember> participants = participants(source, target,
			loadedClusterMembers);
		if (source.bindingState() == null)
			return BindingPlan.failed(BindResult.NO_SOURCE, participants);
		BindResult common = validateParticipants(source.memberAddress(), participants);
		if (!common.succeeded())
			return BindingPlan.failed(common, participants);
		BindResult authorityValidation = validateKnownAuthorities(participants);
		if (!authorityValidation.succeeded())
			return BindingPlan.failed(authorityValidation, participants);

		return proposedPlan(source.bindingState().clusterId(), participants,
			canonicalMergedBindings(participants),
			mayAdministrate, false);
	}

	private static BindingPlan replacementPlan(UUID clusterId,
		List<ClusterMember> participants, List<LogisticsBinding> requestedBindings,
		Predicate<UUID> mayAdministrate) {
		if (participants.isEmpty())
			return BindingPlan.failed(BindResult.NO_SOURCE, participants);
		BindResult common = validateParticipants(participants.getFirst().memberAddress(),
			participants);
		if (!common.succeeded())
			return BindingPlan.failed(common, participants);
		return proposedPlan(clusterId, participants, requestedBindings,
			mayAdministrate, true);
	}

	private static BindingPlan proposedPlan(UUID clusterId,
		List<ClusterMember> participants, List<LogisticsBinding> requestedBindings,
		Predicate<UUID> mayAdministrate, boolean allowEmpty) {
		List<LogisticsBinding> bindings = LogisticsBinding.normalize(requestedBindings);
		if (!allowEmpty && bindings.isEmpty())
			return BindingPlan.failed(BindResult.EMPTY_NETWORKS, participants);
		if (bindings.size() > ClusterBinding.MAX_BINDINGS)
			return BindingPlan.failed(BindResult.TOO_MANY_BINDINGS, participants);

		BindResult authorityValidation = validateKnownAuthorities(participants);
		if (!authorityValidation.succeeded())
			return BindingPlan.failed(authorityValidation, participants);
		ClusterAuthority authority = chooseAuthority(participants);
		if (authority == null)
			return BindingPlan.failed(BindResult.CONFLICT, participants);

		LinkedHashSet<UUID> allIds = new LinkedHashSet<>();
		participants.forEach(member -> member.logisticsBindings()
			.forEach(binding -> allIds.add(binding.logisticsId())));
		bindings.forEach(binding -> allIds.add(binding.logisticsId()));
		if (!allIds.stream().allMatch(mayAdministrate))
			return BindingPlan.failed(BindResult.PERMISSION, participants);

		long maximumRevision = participants.stream()
			.map(ClusterMember::bindingState)
			.filter(Objects::nonNull)
			.mapToLong(ClusterBinding::revision)
			.max().orElse(0);
		if (maximumRevision == Long.MAX_VALUE)
			return BindingPlan.failed(BindResult.STALE_BINDING, participants);
		ClusterBinding proposed = new ClusterBinding(clusterId, maximumRevision + 1,
			authority, bindings);

		BindResult preparation = BindResult.OK;
		for (ClusterMember participant : participants) {
			BindResult result = prepare(participant, proposed);
			if (!result.succeeded() && preparation.succeeded())
				preparation = result;
		}
		return preparation.succeeded()
			? new BindingPlan(BindResult.OK, participants, proposed)
			: BindingPlan.failed(preparation, participants);
	}

	private static BindResult validateParticipants(SpaceAddress sourceAddress,
		List<ClusterMember> participants) {
		if (participants.stream().anyMatch(member -> !member.hasValidBindingState()))
			return BindResult.CONFLICT;
		if (participants.stream().anyMatch(member -> !member.canRebind()))
			return BindResult.ACTIVE;
		if (participants.stream().anyMatch(member -> !sourceAddress.dimension()
			.equals(member.memberAddress().dimension())))
			return BindResult.DIMENSION;
		long patternCores = participants.stream()
			.filter(member -> member.memberType() == ClusterMemberType.PATTERN_CORE)
			.map(ClusterMember::memberId).distinct().count();
		long coordinators = participants.stream()
			.filter(member -> member.memberType() == ClusterMemberType.COMPUTER_COORDINATOR)
			.map(ClusterMember::memberId).distinct().count();
		return patternCores > 1 || coordinators > 1
			? BindResult.CONFLICT : BindResult.OK;
	}

	private static BindResult validateKnownAuthorities(List<ClusterMember> participants) {
		for (ClusterMember participant : participants) {
			ClusterBinding state = participant.bindingState();
			if (state == null)
				continue;
			if (state.authority() == null)
				return BindResult.STALE_BINDING;
			ClusterMember authority = findAuthority(state.authority(), participants);
			if (authority == null)
				return BindResult.AUTHORITY_OFFLINE;
			ClusterBinding authorityState = authority.bindingState();
			if (authorityState == null || !authority.hasValidBindingState()
				|| !state.authority().equals(authorityState.authority())
				|| !state.authority().identifies(authority)
				|| !state.clusterId().equals(authorityState.clusterId()))
				return BindResult.STALE_BINDING;
			if (state.revision() > authorityState.revision()
				|| (state.revision() == authorityState.revision()
					&& !state.equals(authorityState)))
				return BindResult.STALE_BINDING;
		}
		return BindResult.OK;
	}

	private static List<LogisticsBinding> canonicalMergedBindings(
		List<ClusterMember> participants) {
		Set<UUID> visitedClusters = new java.util.HashSet<>();
		List<LogisticsBinding> bindings = new ArrayList<>();
		for (ClusterMember participant : participants) {
			ClusterBinding state = participant.bindingState();
			if (state == null) {
				bindings.addAll(participant.logisticsBindings());
				continue;
			}
			if (!visitedClusters.add(state.clusterId()))
				continue;
			ClusterMember authority = findAuthority(state.authority(), participants);
			bindings.addAll(authority.bindingState().logisticsBindings());
		}
		return LogisticsBinding.normalize(bindings);
	}

	private static AuthorityResolution resolveAuthority(ClusterBinding startingState,
		List<ClusterMember> participants) {
		ClusterAuthority authority = startingState.authority();
		if (authority == null)
			return AuthorityResolution.conflict();
		Set<ClusterAuthority> visited = new java.util.HashSet<>();
		long minimumRevision = startingState.revision();
		while (visited.add(authority)) {
			ClusterMember authorityMember = findAuthority(authority, participants);
			if (authorityMember == null)
				return AuthorityResolution.offline();
			ClusterBinding state = authorityMember.bindingState();
			if (state == null || !authorityMember.hasValidBindingState()
				|| !startingState.clusterId().equals(state.clusterId())
				|| state.authority() == null || state.revision() < minimumRevision)
				return AuthorityResolution.conflict();
			ClusterAuthority next = state.authority();
			if (next.equals(authority))
				return AuthorityResolution.current(authority, state);
			minimumRevision = state.revision();
			authority = next;
		}
		return AuthorityResolution.conflict();
	}

	@Nullable
	private static ClusterMember findAuthority(ClusterAuthority authority,
		List<ClusterMember> participants) {
		return participants.stream().filter(authority::identifies).findFirst().orElse(null);
	}

	@Nullable
	private static ClusterAuthority chooseAuthority(List<ClusterMember> participants) {
		return participants.stream()
			.min(Comparator.comparingInt((ClusterMember member) ->
				authorityPriority(member.memberType()))
				.thenComparing(member -> member.memberId().toString()))
			.map(member -> new ClusterAuthority(member.memberType(), member.memberId()))
			.orElse(null);
	}

	private static int authorityPriority(ClusterMemberType type) {
		return switch (type) {
			case COMPUTER_COORDINATOR -> 0;
			case PATTERN_CORE -> 1;
			case PANEL -> 2;
		};
	}

	private static BindResult prepare(ClusterMember member, ClusterBinding proposed) {
		try {
			return switch (member.prepareClusterBinding(proposed)) {
				case READY -> BindResult.OK;
				case ACTIVE -> BindResult.ACTIVE;
				case CAPACITY -> BindResult.TOO_MANY_BINDINGS;
				case IDENTITY, REVISION -> BindResult.PARTICIPANT_REJECTED;
			};
		} catch (RuntimeException refused) {
			return BindResult.PARTICIPANT_REJECTED;
		}
	}

	private static void commit(BindingPlan plan) {
		plan.participants().forEach(member ->
			member.commitClusterBinding(plan.proposed()));
	}

	private static List<ClusterMember> loadedMembersForMerge(MinecraftServer server,
		ClusterMember source, ClusterMember target) {
		List<ClusterMember> members = new ArrayList<>();
		if (source.clusterId() != null)
			members.addAll(loadedMembers(server, source.clusterId()));
		if (target.clusterId() != null && !target.clusterId().equals(source.clusterId()))
			members.addAll(loadedMembers(server, target.clusterId()));
		return List.copyOf(members);
	}

	private static List<ClusterMember> loadedMembers(MinecraftServer server,
		UUID clusterId) {
		List<ClusterMember> members = new ArrayList<>();
		for (ClusterMemberType type : ClusterMemberType.values())
			members.addAll(ClusterMemberIndex.members(server, clusterId, type));
		return List.copyOf(members);
	}

	private static boolean hasStructuralConflict(MinecraftServer server,
		@Nullable UUID clusterId) {
		if (clusterId == null)
			return false;
		ClusterMemberIndex.ConflictReport report =
			ClusterMemberIndex.conflicts(server, clusterId);
		return report.patternConflict() || report.computerConflict();
	}

	private static List<ClusterMember> participants(ClusterMember source,
		@Nullable ClusterMember target,
		Collection<ClusterMember> loadedClusterMembers) {
		Set<ClusterMember> identities =
			Collections.newSetFromMap(new IdentityHashMap<>());
		List<ClusterMember> participants = new ArrayList<>();
		addParticipant(participants, identities, source);
		if (target != null)
			addParticipant(participants, identities, target);
		loadedClusterMembers.forEach(member ->
			addParticipant(participants, identities, member));
		return List.copyOf(participants);
	}

	private static void addParticipant(List<ClusterMember> participants,
		Set<ClusterMember> identities, ClusterMember member) {
		if (identities.add(member))
			participants.add(member);
	}

	private static BindResult notify(ServerPlayer player, BindResult result) {
		if (result.succeeded()) {
			player.displayClientMessage(Component.translatable(
				"create_biotech.factory_cluster.binding.success")
				.withStyle(ChatFormatting.GREEN), true);
		} else {
			player.displayClientMessage(Component.translatable(
				"create_biotech.factory_cluster.binding."
					+ result.name().toLowerCase(Locale.ROOT))
				.withStyle(ChatFormatting.RED), true);
		}
		return result;
	}

	public enum BindResult {
		OK,
		NO_SOURCE,
		ACTIVE,
		DIMENSION,
		PERMISSION,
		EMPTY_NETWORKS,
		TOO_MANY_BINDINGS,
		AUTHORITY_OFFLINE,
		STALE_BINDING,
		PARTICIPANT_REJECTED,
		CONFLICT;

		public boolean succeeded() {
			return this == OK;
		}
	}

	public enum ReconcileResult {
		CURRENT,
		UPDATED,
		AUTHORITY_OFFLINE,
		CONFLICT
	}

	public enum BindingAccess {
		READY,
		AUTHORITY_OFFLINE,
		CONFLICT
	}

	private record BindingPlan(BindResult result,
		List<ClusterMember> participants, @Nullable ClusterBinding proposed) {
		private static BindingPlan failed(BindResult result,
			List<ClusterMember> participants) {
			return new BindingPlan(result, participants, null);
		}
	}

	private record AuthorityResolution(ReconcileResult result,
		@Nullable ClusterAuthority authority, @Nullable ClusterBinding state) {
		private static AuthorityResolution current(ClusterAuthority authority,
			ClusterBinding state) {
			return new AuthorityResolution(ReconcileResult.CURRENT, authority, state);
		}

		private static AuthorityResolution offline() {
			return new AuthorityResolution(ReconcileResult.AUTHORITY_OFFLINE, null, null);
		}

		private static AuthorityResolution conflict() {
			return new AuthorityResolution(ReconcileResult.CONFLICT, null, null);
		}
	}
}
