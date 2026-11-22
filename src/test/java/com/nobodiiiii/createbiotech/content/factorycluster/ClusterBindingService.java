package com.nobodiiiii.createbiotech.content.factorycluster;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBinding;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBindingTransactions;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionCoordinator;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionMemberRegistry;
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

	static BindResult replaceFormedBindings(ClusterMember panel,
		List<LogisticsBinding> bindings, Collection<ClusterMember> participants,
		Predicate<UUID> mayAdministrate, boolean conflict, boolean formedAuthority) {
		return bindResult(transactions().replaceFormed(panel, networks(bindings), participants, mayAdministrate, conflict, formedAuthority));
	}

	static BindResult validate(ClusterMember source, ClusterMember target,
		Predicate<UUID> mayAdministrate,
		Collection<ClusterMember> loadedClusterMembers) {
		return bindResult(transactions().validate(source, target, mayAdministrate, loadedClusterMembers));
	}

	static BindResult bind(ClusterMember source, ClusterMember target,
		Collection<ClusterMember> loadedClusterMembers,
		Predicate<UUID> mayAdministrate, boolean conflict,
		Runnable clearSelection) {
		return bindResult(transactions().bind(source, target, loadedClusterMembers, mayAdministrate, conflict, clearSelection));
	}

	static BindResult replaceBindings(ClusterMember source,
		List<LogisticsBinding> requestedBindings,
		Collection<ClusterMember> loadedClusterMembers,
		Predicate<UUID> mayAdministrate, boolean conflict) {
		return bindResult(transactions().replace(source, networks(requestedBindings), loadedClusterMembers, mayAdministrate, conflict));
	}

	public static ReconcileResult reconcileLoaded(MinecraftServer server,
		ClusterMember loadingMember) {
		UUID group = loadingMember.clusterId();
		return group == null ? ReconcileResult.CONFLICT : ReconcileResult.valueOf(transactions().reconcile(
			loadingMember, loadedMembers(server, group), authority -> lookup(server, authority)).name());
	}

	static ReconcileResult reconcileLoaded(ClusterMember loadingMember,
		Collection<ClusterMember> loadedClusterMembers) {
		return ReconcileResult.valueOf(transactions().reconcile(loadingMember, loadedClusterMembers).name());
	}

	public static BindingAccess bindingAccess(MinecraftServer server,
		ClusterMember member) {
		return switch (reconcileLoaded(server, member)) {
			case CURRENT, UPDATED -> BindingAccess.READY;
			case AUTHORITY_OFFLINE -> BindingAccess.AUTHORITY_OFFLINE;
			case CONFLICT -> BindingAccess.CONFLICT;
		};
	}

	/**
	 * Pure, fail-closed proof for read-only projections. Unlike {@link #bindingAccess},
	 * this never prepares or reconciles a loaded replica: every currently loaded
	 * participant must already equal the stable authority's exact binding snapshot.
	 */
	public static BindingAccess snapshotBindingAccess(MinecraftServer server,
		ClusterMember member) {
		Objects.requireNonNull(server, "server"); Objects.requireNonNull(member, "member");
		UUID group = member.clusterId();
		if (group == null || ClusterMemberIndex.conflicts(server, group).blocksNewTasks()) return BindingAccess.CONFLICT;
		return BindingAccess.valueOf(transactions().snapshotAccess(member, loadedMembers(server, group),
			authority -> lookup(server, authority)).name());
	}

	static BindingAccess snapshotBindingAccess(ClusterMember member,
		Collection<ClusterMember> loadedClusterMembers) {
		return BindingAccess.valueOf(transactions().snapshotAccess(member, loadedClusterMembers).name());
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

    private static ProductionBindingTransactions<ClusterMember> transactions() {
        return new ProductionBindingTransactions<>(new ParticipantAccess());
    }
    private static BindResult bindResult(ProductionBindingTransactions.Result result) { return BindResult.valueOf(result.name()); }
    private static List<UUID> networks(List<LogisticsBinding> bindings) {
        return bindings == null ? null : bindings.stream().filter(Objects::nonNull).map(LogisticsBinding::logisticsNetworkId).toList();
    }
    private static ProductionBinding.Role role(ClusterMemberType type) {
        return switch (type) {
            case PANEL -> ProductionBinding.Role.PANEL;
            case PATTERN_CORE -> ProductionBinding.Role.LIBRARY;
            case COMPUTER_COORDINATOR -> ProductionBinding.Role.COORDINATOR;
        };
    }
    private static ClusterMemberType type(ProductionBinding.Role role) {
        return switch (role) {
            case PANEL -> ClusterMemberType.PANEL;
            case LIBRARY -> ClusterMemberType.PATTERN_CORE;
            case COORDINATOR -> ClusterMemberType.COMPUTER_COORDINATOR;
        };
    }
    private static ProductionMemberRegistry.Lookup<ClusterMember> lookup(MinecraftServer server, ProductionBinding.Authority authority) {
        var found = ClusterMemberIndex.lookupStable(server, type(authority.role()), authority.memberId());
        return new ProductionMemberRegistry.Lookup<>(ProductionMemberRegistry.LookupStatus.valueOf(found.status().name()), found.member());
    }


    /** Call-scoped value bridge: preserve the exact immutable proposal through every prepare and commit. */
    private static final class ParticipantAccess implements ProductionBindingTransactions.Access<ClusterMember> {
        private final Map<ClusterBinding, ProductionBinding> projected = new IdentityHashMap<>();
        private final Map<ProductionBinding, ClusterBinding> nativeValues = new IdentityHashMap<>();
        @Nullable ProductionBinding project(@Nullable ClusterBinding value) {
            if (value == null) return null;
            return projected.computeIfAbsent(value, binding -> {
                var result = binding.productionBinding(); nativeValues.put(result, binding); return result;
            });
        }
        private ClusterBinding nativeValue(ProductionBinding value) {
            return nativeValues.computeIfAbsent(value, ClusterBinding::fromProduction);
        }
        @Override public UUID groupId(ClusterMember member) { return member.clusterId(); }
        @Override public ProductionBinding.Role role(ClusterMember member) { return ClusterBindingService.role(member.memberType()); }
        @Override public UUID memberId(ClusterMember member) { return member.memberId(); }
        @Override public boolean validBinding(ClusterMember member) { return member.hasValidBindingState(); }
        @Override public ProductionBinding binding(ClusterMember member) { return project(member.bindingState()); }
        @Override public String dimensionId(ClusterMember member) { return member.memberAddress().dimension().location().toString(); }
        @Override public List<UUID> logisticsIds(ClusterMember member) { return networks(member.logisticsBindings()); }
        @Override public boolean canRebind(ClusterMember member) { return member.canRebind(); }
        @Override public ProductionCoordinator.Preparation prepareRebind(ClusterMember member, ProductionBinding proposed) {
            return ProductionCoordinator.Preparation.valueOf(member.prepareClusterBinding(nativeValue(proposed)).name());
        }
        @Override public ProductionCoordinator.Preparation prepareLogistics(ClusterMember member, ProductionBinding proposed) {
            return ProductionCoordinator.Preparation.valueOf(member.prepareLogisticsBinding(nativeValue(proposed)).name());
        }
        @Override public void commit(ClusterMember member, ProductionBinding prepared) { member.commitClusterBinding(nativeValue(prepared)); }
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

}
