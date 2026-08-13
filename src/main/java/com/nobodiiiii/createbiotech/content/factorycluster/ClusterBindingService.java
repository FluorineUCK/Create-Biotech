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
		if (clusterId == null)
			return refuse(player, BindResult.NO_SOURCE);

		MinecraftServer server = player.getServer();
		if (ClusterMemberIndex.conflicts(server, clusterId).blocksNewTasks())
			return refuse(player, BindResult.CONFLICT);

		List<ClusterMember> loadedMembers = loadedMembers(server, clusterId);
		BindResult result = validate(source, target,
			id -> Create.LOGISTICS.mayAdministrate(id, player), loadedMembers);
		if (!result.succeeded())
			return refuse(player, result);

		List<LogisticsBinding> bindings = mergedBindings(source, target);
		Set<ClusterMember> applied =
			Collections.newSetFromMap(new IdentityHashMap<>());
		for (ClusterMember member : loadedMembers)
			if (applied.add(member))
				member.applyClusterBinding(clusterId, bindings);
		if (applied.add(source))
			source.applyClusterBinding(clusterId, bindings);
		if (applied.add(target))
			target.applyClusterBinding(clusterId, bindings);

		ClusterBindingSelection.clear(player);
		player.displayClientMessage(Component.translatable(
			"create_biotech.factory_cluster.binding.success")
			.withStyle(ChatFormatting.GREEN), true);
		return BindResult.OK;
	}

	static BindResult validate(ClusterMember source, ClusterMember target,
		Predicate<UUID> mayAdministrate,
		Collection<ClusterMember> loadedClusterMembers) {
		if (source.clusterId() == null)
			return BindResult.NO_SOURCE;
		if (!source.canRebind() || !target.canRebind()
			|| loadedClusterMembers.stream().anyMatch(member -> !member.canRebind()))
			return BindResult.ACTIVE;
		if (!source.memberAddress().dimension()
			.equals(target.memberAddress().dimension()))
			return BindResult.DIMENSION;

		LinkedHashSet<UUID> allIds = new LinkedHashSet<>();
		source.logisticsBindings()
			.forEach(binding -> allIds.add(binding.logisticsId()));
		target.logisticsBindings()
			.forEach(binding -> allIds.add(binding.logisticsId()));
		if (allIds.isEmpty())
			return BindResult.EMPTY_NETWORKS;
		return allIds.stream().allMatch(mayAdministrate)
			? BindResult.OK : BindResult.PERMISSION;
	}

	private static List<ClusterMember> loadedMembers(MinecraftServer server,
		UUID clusterId) {
		List<ClusterMember> members = new ArrayList<>();
		for (ClusterMemberType type : ClusterMemberType.values())
			members.addAll(ClusterMemberIndex.members(server, clusterId, type));
		return List.copyOf(members);
	}

	private static List<LogisticsBinding> mergedBindings(ClusterMember source,
		ClusterMember target) {
		List<LogisticsBinding> combined = new ArrayList<>(source.logisticsBindings());
		combined.addAll(target.logisticsBindings());
		return LogisticsBinding.normalize(combined);
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
}
