package com.nobodiiiii.createbiotech.content.factorycluster;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import com.simibubi.create.Create;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class ClusterBindingService {
	private ClusterBindingService() {}

	public static BindResult bind(ServerPlayer player, ClusterMember source,
		ClusterMember target) {
		UUID clusterId = source.clusterId();
		MinecraftServer server = player.getServer();
		boolean conflict = clusterId != null
			&& ClusterMemberIndex.conflicts(server, clusterId).blocksNewTasks();
		List<ClusterMember> loadedMembers = clusterId == null
			? List.of() : loadedMembers(server, clusterId);
		BindResult result = bind(source, target, loadedMembers,
			id -> Create.LOGISTICS.mayAdministrate(id, player), conflict,
			() -> ClusterBindingSelection.clear(player));
		if (result.succeeded())
			player.displayClientMessage(Component.translatable(
				"create_biotech.factory_cluster.binding.success")
				.withStyle(ChatFormatting.GREEN), true);
		else
			refuse(player, result);
		return result;
	}

	static BindResult validate(ClusterMember source, ClusterMember target,
		Predicate<UUID> mayAdministrate,
		Collection<ClusterMember> loadedClusterMembers) {
		return plan(source, target, loadedClusterMembers, mayAdministrate).result();
	}

	static BindResult bind(ClusterMember source, ClusterMember target,
		Collection<ClusterMember> loadedClusterMembers,
		Predicate<UUID> mayAdministrate, boolean conflict,
		Runnable clearSelection) {
		if (source.clusterId() == null)
			return BindResult.NO_SOURCE;
		if (conflict)
			return BindResult.CONFLICT;

		BindingPlan plan = plan(source, target, loadedClusterMembers,
			mayAdministrate);
		if (!plan.result().succeeded())
			return plan.result();
		for (ClusterMember participant : plan.participants())
			participant.applyClusterBinding(source.clusterId(), plan.bindings());
		clearSelection.run();
		return BindResult.OK;
	}

	private static List<ClusterMember> loadedMembers(MinecraftServer server,
		UUID clusterId) {
		List<ClusterMember> members = new ArrayList<>();
		for (ClusterMemberType type : ClusterMemberType.values())
			members.addAll(ClusterMemberIndex.members(server, clusterId, type));
		return List.copyOf(members);
	}

	private static BindingPlan plan(ClusterMember source, ClusterMember target,
		Collection<ClusterMember> loadedClusterMembers,
		Predicate<UUID> mayAdministrate) {
		List<ClusterMember> participants = participants(source, target,
			loadedClusterMembers);
		if (source.clusterId() == null)
			return BindingPlan.failed(BindResult.NO_SOURCE, participants);
		if (participants.stream().anyMatch(member -> !member.canRebind()))
			return BindingPlan.failed(BindResult.ACTIVE, participants);
		if (participants.stream().anyMatch(member -> !source.memberAddress().dimension()
			.equals(member.memberAddress().dimension())))
			return BindingPlan.failed(BindResult.DIMENSION, participants);

		List<LogisticsBinding> combined = new ArrayList<>();
		participants.forEach(member -> combined.addAll(member.logisticsBindings()));
		List<LogisticsBinding> bindings = LogisticsBinding.normalize(combined);
		if (bindings.isEmpty())
			return BindingPlan.failed(BindResult.EMPTY_NETWORKS, participants);

		LinkedHashSet<UUID> allIds = new LinkedHashSet<>();
		participants.forEach(member -> member.logisticsBindings()
			.forEach(binding -> allIds.add(binding.logisticsId())));
		bindings.forEach(binding -> allIds.add(binding.logisticsId()));
		return allIds.stream().allMatch(mayAdministrate)
			? new BindingPlan(BindResult.OK, participants, bindings)
			: BindingPlan.failed(BindResult.PERMISSION, participants);
	}

	private static List<ClusterMember> participants(ClusterMember source,
		ClusterMember target, Collection<ClusterMember> loadedClusterMembers) {
		Set<ClusterMember> identities =
			Collections.newSetFromMap(new IdentityHashMap<>());
		List<ClusterMember> participants = new ArrayList<>();
		addParticipant(participants, identities, source);
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

	private static BindResult refuse(ServerPlayer player, BindResult result) {
		player.displayClientMessage(Component.translatable(
			"create_biotech.factory_cluster.binding."
				+ result.name().toLowerCase(Locale.ROOT))
			.withStyle(ChatFormatting.RED), true);
		return result;
	}

	public enum BindResult {
		OK,
		NO_SOURCE,
		ACTIVE,
		DIMENSION,
		PERMISSION,
		EMPTY_NETWORKS,
		CONFLICT;

		public boolean succeeded() {
			return this == OK;
		}
	}

	private record BindingPlan(BindResult result,
		List<ClusterMember> participants, List<LogisticsBinding> bindings) {
		private static BindingPlan failed(BindResult result,
			List<ClusterMember> participants) {
			return new BindingPlan(result, participants, List.of());
		}
	}
}
