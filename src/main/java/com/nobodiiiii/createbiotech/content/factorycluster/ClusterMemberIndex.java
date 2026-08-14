package com.nobodiiiii.createbiotech.content.factorycluster;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import net.minecraft.server.MinecraftServer;

public final class ClusterMemberIndex {
	private static final Map<MinecraftServer, ServerIndex> SERVERS = new WeakHashMap<>();

	private ClusterMemberIndex() {}

	public static void register(MinecraftServer server, ClusterMember member) {
		serverIndex(server).register(member);
		ClusterBindingService.reconcileLoaded(server, member);
	}

	public static void unregister(MinecraftServer server, ClusterMember member) {
		ServerIndex index;
		synchronized (SERVERS) {
			index = SERVERS.get(server);
		}
		if (index != null)
			index.unregister(member);
	}

	public static void rebind(MinecraftServer server, ClusterMember member, Runnable mutation) {
		serverIndex(server).rebind(member, mutation);
	}

	public static List<ClusterMember> members(MinecraftServer server, UUID clusterId,
		ClusterMemberType type) {
		ServerIndex index;
		synchronized (SERVERS) {
			index = SERVERS.get(server);
		}
		return index == null ? List.of() : index.members(clusterId, type);
	}

	public static ConflictReport conflicts(MinecraftServer server, UUID clusterId) {
		ServerIndex index;
		synchronized (SERVERS) {
			index = SERVERS.get(server);
		}
		return index == null ? new ConflictReport(false, false, false, false)
			: index.conflicts(clusterId);
	}

	static StableLookup lookupStable(MinecraftServer server, ClusterMemberType type,
		UUID memberId) {
		ServerIndex index;
		synchronized (SERVERS) {
			index = SERVERS.get(server);
		}
		return index == null ? StableLookup.missing()
			: index.lookupStable(type, memberId);
	}

	private static ServerIndex serverIndex(MinecraftServer server) {
		synchronized (SERVERS) {
			return SERVERS.computeIfAbsent(server, ignored -> new ServerIndex());
		}
	}

	static final class ServerIndex {
		private final Map<UUID, Map<MemberKey, List<WeakReference<ClusterMember>>>> membersByCluster =
			new HashMap<>();

		ServerIndex() {}

		void register(ClusterMember member) {
			UUID clusterId = member.clusterId();
			if (clusterId == null)
				return;
			Map<MemberKey, List<WeakReference<ClusterMember>>> members =
				membersByCluster.computeIfAbsent(clusterId, ignored -> new HashMap<>());
			List<WeakReference<ClusterMember>> references = members.computeIfAbsent(
				new MemberKey(member.memberType(), member.memberId()),
				ignored -> new ArrayList<>());
			if (references.stream().map(WeakReference::get).noneMatch(indexed -> indexed == member))
				references.add(new WeakReference<>(member));
		}

		void unregister(ClusterMember member) {
			UUID clusterId = member.clusterId();
			if (clusterId == null)
				return;
			Map<MemberKey, List<WeakReference<ClusterMember>>> members =
				membersByCluster.get(clusterId);
			if (members == null)
				return;
			MemberKey key = new MemberKey(member.memberType(), member.memberId());
			List<WeakReference<ClusterMember>> references = members.get(key);
			if (references != null) {
				references.removeIf(reference -> {
					ClusterMember indexed = reference.get();
					return indexed == null || indexed == member;
				});
				if (references.isEmpty())
					members.remove(key);
			}
			if (members.isEmpty())
				membersByCluster.remove(clusterId);
		}

		void rebind(ClusterMember member, Runnable mutation) {
			unregister(member);
			try {
				mutation.run();
			} finally {
				register(member);
			}
		}

		List<ClusterMember> members(UUID clusterId, ClusterMemberType type) {
			Map<MemberKey, List<WeakReference<ClusterMember>>> members =
				membersByCluster.get(clusterId);
			if (members == null)
				return List.of();
			List<ClusterMember> loaded = new ArrayList<>();
			Iterator<Map.Entry<MemberKey, List<WeakReference<ClusterMember>>>> iterator =
				members.entrySet().iterator();
			while (iterator.hasNext()) {
				Map.Entry<MemberKey, List<WeakReference<ClusterMember>>> entry =
					iterator.next();
				List<ClusterMember> live = new ArrayList<>();
				entry.getValue().removeIf(reference -> {
					ClusterMember member = reference.get();
					if (member != null)
						live.add(member);
					return member == null;
				});
				if (entry.getValue().isEmpty()) {
					iterator.remove();
				} else if (entry.getKey().type() == type) {
					loaded.addAll(live);
				}
			}
			if (members.isEmpty())
				membersByCluster.remove(clusterId);
			return List.copyOf(loaded);
		}

		StableLookup lookupStable(ClusterMemberType type, UUID memberId) {
			MemberKey key = new MemberKey(type, memberId);
			ClusterMember found = null;
			Iterator<Map.Entry<UUID, Map<MemberKey, List<WeakReference<ClusterMember>>>>> clusters =
				membersByCluster.entrySet().iterator();
			while (clusters.hasNext()) {
				Map<MemberKey, List<WeakReference<ClusterMember>>> members =
					clusters.next().getValue();
				List<WeakReference<ClusterMember>> references = members.get(key);
				if (references == null)
					continue;
				Iterator<WeakReference<ClusterMember>> iterator = references.iterator();
				while (iterator.hasNext()) {
					ClusterMember member = iterator.next().get();
					if (member == null) {
						iterator.remove();
						continue;
					}
					if (found != null && found != member)
						return StableLookup.conflict();
					found = member;
				}
				if (references.isEmpty())
					members.remove(key);
				if (members.isEmpty())
					clusters.remove();
			}
			return found == null ? StableLookup.missing() : StableLookup.found(found);
		}

		ConflictReport conflicts(UUID clusterId) {
			List<ClusterMember> panels = members(clusterId, ClusterMemberType.PANEL);
			List<ClusterMember> patterns = members(clusterId, ClusterMemberType.PATTERN_CORE);
			List<ClusterMember> computers = members(clusterId,
				ClusterMemberType.COMPUTER_COORDINATOR);
			List<ClusterMember> all = new ArrayList<>(panels);
			all.addAll(patterns);
			all.addAll(computers);
			return new ConflictReport(patterns.size() > 1, computers.size() > 1,
				false, !all.isEmpty() && ClusterBindingService.loadedBindingConflict(all));
		}
	}

	private record MemberKey(ClusterMemberType type, UUID memberId) {}

	enum LookupStatus {
		FOUND,
		MISSING,
		CONFLICT
	}

	record StableLookup(LookupStatus status, @Nullable ClusterMember member) {
		private static StableLookup found(ClusterMember member) {
			return new StableLookup(LookupStatus.FOUND, member);
		}

		private static StableLookup missing() {
			return new StableLookup(LookupStatus.MISSING, null);
		}

		private static StableLookup conflict() {
			return new StableLookup(LookupStatus.CONFLICT, null);
		}
	}

	public record ConflictReport(boolean patternConflict, boolean computerConflict,
		boolean panelConflict, boolean bindingConflict) {
		public boolean blocksNewTasks() {
			return patternConflict || computerConflict || bindingConflict;
		}
	}
}
