package com.nobodiiiii.createbiotech.content.factorycluster;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.server.MinecraftServer;

public final class ClusterMemberIndex {
	private static final Map<MinecraftServer, ServerIndex> SERVERS = new WeakHashMap<>();

	private ClusterMemberIndex() {}

	public static void register(MinecraftServer server, ClusterMember member) {
		serverIndex(server).register(member);
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
		return index == null ? new ConflictReport(false, false, false)
			: index.conflicts(clusterId);
	}

	private static ServerIndex serverIndex(MinecraftServer server) {
		synchronized (SERVERS) {
			return SERVERS.computeIfAbsent(server, ignored -> new ServerIndex());
		}
	}

	static final class ServerIndex {
		private final Map<UUID, Map<MemberKey, WeakReference<ClusterMember>>> membersByCluster =
			new HashMap<>();

		ServerIndex() {}

		void register(ClusterMember member) {
			UUID clusterId = member.clusterId();
			if (clusterId == null)
				return;
			Map<MemberKey, WeakReference<ClusterMember>> members =
				membersByCluster.computeIfAbsent(clusterId, ignored -> new HashMap<>());
			members.put(new MemberKey(member.memberType(), member.memberId()),
				new WeakReference<>(member));
		}

		void unregister(ClusterMember member) {
			UUID clusterId = member.clusterId();
			if (clusterId == null)
				return;
			Map<MemberKey, WeakReference<ClusterMember>> members = membersByCluster.get(clusterId);
			if (members == null)
				return;
			MemberKey key = new MemberKey(member.memberType(), member.memberId());
			WeakReference<ClusterMember> reference = members.get(key);
			if (reference != null && reference.get() == member)
				members.remove(key);
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
			Map<MemberKey, WeakReference<ClusterMember>> members = membersByCluster.get(clusterId);
			if (members == null)
				return List.of();
			List<ClusterMember> loaded = new ArrayList<>();
			Iterator<Map.Entry<MemberKey, WeakReference<ClusterMember>>> iterator =
				members.entrySet().iterator();
			while (iterator.hasNext()) {
				Map.Entry<MemberKey, WeakReference<ClusterMember>> entry = iterator.next();
				ClusterMember member = entry.getValue().get();
				if (member == null) {
					iterator.remove();
				} else if (entry.getKey().type() == type) {
					loaded.add(member);
				}
			}
			if (members.isEmpty())
				membersByCluster.remove(clusterId);
			return List.copyOf(loaded);
		}

		ConflictReport conflicts(UUID clusterId) {
			boolean patternConflict =
				members(clusterId, ClusterMemberType.PATTERN_CORE).size() > 1;
			boolean computerConflict =
				members(clusterId, ClusterMemberType.COMPUTER_COORDINATOR).size() > 1;
			return new ConflictReport(patternConflict, computerConflict, false);
		}
	}

	private record MemberKey(ClusterMemberType type, UUID memberId) {}

	public record ConflictReport(boolean patternConflict, boolean computerConflict,
		boolean panelConflict) {
		public boolean blocksNewTasks() {
			return patternConflict || computerConflict;
		}
	}
}
