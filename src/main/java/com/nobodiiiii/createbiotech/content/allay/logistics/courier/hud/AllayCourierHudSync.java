package com.nobodiiiii.createbiotech.content.allay.logistics.courier.hud;

import com.nobodiiiii.createbiotech.network.CBPackets;
import com.nobodiiiii.createbiotech.content.allay.logistics.courier.AllayCourierTask;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AllayCourierHudSync {
	private static final int SYNC_INTERVAL_TICKS = 10;
	private static final int TERMINAL_DISPLAY_TICKS = 60;
	private static final Map<UUID, List<AllayCourierHudEntry>> LAST_SENT_BY_PLAYER = new HashMap<>();
	private static final Map<UUID, Map<UUID, TerminalEntry>> TERMINAL_BY_PLAYER = new HashMap<>();

	private AllayCourierHudSync() {}

	public static void clear() {
		LAST_SENT_BY_PLAYER.clear();
		TERMINAL_BY_PLAYER.clear();
	}

	public static void onDelivered(MinecraftServer server, AllayCourierTask task) {
		onTerminal(server, task, AllayCourierHudStatus.DELIVERED);
	}

	public static void onFailed(MinecraftServer server, AllayCourierTask task) {
		onTerminal(server, task, AllayCourierHudStatus.FAILED);
	}

	private static void onTerminal(MinecraftServer server, AllayCourierTask task,
		AllayCourierHudStatus status) {
		UUID playerId = task.hudTrackingPlayerId();
		if (playerId == null) {
			return;
		}
		AllayCourierHudEntry visibleEntry = LAST_SENT_BY_PLAYER.getOrDefault(playerId, List.of())
			.stream()
			.filter(entry -> entry.id().equals(task.id()))
			.findFirst()
			.orElseGet(() -> new AllayCourierHudEntry(
				task.id(), task.hudIncoming(), task.hudCounterpartyAddress(), 0,
				AllayCourierHudStatus.IN_TRANSIT));
		AllayCourierHudEntry terminalEntry = new AllayCourierHudEntry(
			visibleEntry.id(), visibleEntry.incoming(), visibleEntry.address(), 0, status);
		TERMINAL_BY_PLAYER.computeIfAbsent(playerId, ignored -> new HashMap<>())
			.put(task.id(), new TerminalEntry(terminalEntry,
				server.getTickCount() + TERMINAL_DISPLAY_TICKS));
	}

	public static void sync(MinecraftServer server, List<AllayCourierTask> tasks) {
		if (server.getTickCount() % SYNC_INTERVAL_TICKS != 0) {
			return;
		}

		int currentTick = server.getTickCount();
		TERMINAL_BY_PLAYER.values().forEach(entries ->
			entries.values().removeIf(entry -> currentTick >= entry.expiresAtTick()));
		TERMINAL_BY_PLAYER.entrySet().removeIf(entry -> entry.getValue().isEmpty());

		Map<UUID, List<AllayCourierHudEntry>> entriesByPlayer = new HashMap<>();
		for (AllayCourierTask task : tasks) {
			if (task.isRemoved()) {
				continue;
			}
			UUID playerId = task.hudTrackingPlayerId();
			if (playerId == null) {
				continue;
			}
			int remainingTicks = task.estimateRemainingTicks(server);
			if (remainingTicks < 0) {
				continue;
			}
			int etaSeconds = Math.max(0, (remainingTicks + 19) / 20);
			AllayCourierHudEntry entry =
				new AllayCourierHudEntry(
					task.id(), task.hudIncoming(), task.hudCounterpartyAddress(), etaSeconds,
					AllayCourierHudStatus.IN_TRANSIT);
			entriesByPlayer.computeIfAbsent(playerId, ignored -> new ArrayList<>()).add(entry);
		}

		Set<UUID> onlinePlayers = new HashSet<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			UUID playerId = player.getUUID();
			onlinePlayers.add(playerId);
			List<AllayCourierHudEntry> activeEntries =
				new ArrayList<>(entriesByPlayer.getOrDefault(playerId, List.of()));
			activeEntries.sort(Comparator.comparingInt(AllayCourierHudEntry::etaSeconds));

			Map<UUID, AllayCourierHudEntry> activeById = new HashMap<>();
			activeEntries.forEach(entry -> activeById.put(entry.id(), entry));
			Map<UUID, TerminalEntry> terminalById =
				TERMINAL_BY_PLAYER.getOrDefault(playerId, Map.of());
			List<AllayCourierHudEntry> entries = new ArrayList<>();
			Set<UUID> addedIds = new HashSet<>();

			for (AllayCourierHudEntry previousEntry :
				LAST_SENT_BY_PLAYER.getOrDefault(playerId, List.of())) {
				TerminalEntry terminalEntry = terminalById.get(previousEntry.id());
				AllayCourierHudEntry nextEntry = terminalEntry != null
					? terminalEntry.entry()
					: activeById.get(previousEntry.id());
				if (nextEntry != null && addedIds.add(nextEntry.id())) {
					entries.add(nextEntry);
				}
			}

			List<TerminalEntry> terminalEntries = new ArrayList<>(terminalById.values());
			terminalEntries.sort(Comparator.comparingInt(TerminalEntry::expiresAtTick));
			for (TerminalEntry terminalEntry : terminalEntries) {
				if (entries.size() >= AllayCourierHudPacket.MAX_VISIBLE_ENTRIES) {
					break;
				}
				if (addedIds.add(terminalEntry.entry().id())) {
					entries.add(terminalEntry.entry());
				}
			}

			for (AllayCourierHudEntry activeEntry : activeEntries) {
				if (entries.size() >= AllayCourierHudPacket.MAX_VISIBLE_ENTRIES) {
					break;
				}
				if (addedIds.add(activeEntry.id())) {
					entries.add(activeEntry);
				}
			}
			entries = List.copyOf(entries);

			if (!entries.equals(LAST_SENT_BY_PLAYER.get(playerId))) {
				CBPackets.sendToPlayer(new AllayCourierHudPacket(entries), player);
				LAST_SENT_BY_PLAYER.put(playerId, entries);
			}
		}

		LAST_SENT_BY_PLAYER.keySet().removeIf(playerId -> !onlinePlayers.contains(playerId));
		TERMINAL_BY_PLAYER.keySet().removeIf(playerId -> !onlinePlayers.contains(playerId));
	}

	private record TerminalEntry(AllayCourierHudEntry entry, int expiresAtTick) {}
}
