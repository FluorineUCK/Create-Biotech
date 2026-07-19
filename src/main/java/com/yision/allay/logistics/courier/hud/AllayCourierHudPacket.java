package com.yision.allay.logistics.courier.hud;

import com.yision.allay.client.gui.hud.AllayCourierHudOverlay;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

public class AllayCourierHudPacket {
	public static final int MAX_VISIBLE_ENTRIES = 5;

	private final List<AllayCourierHudEntry> entries;

	public AllayCourierHudPacket(List<AllayCourierHudEntry> entries) {
		this.entries = entries == null ? List.of() : entries.stream()
			.limit(MAX_VISIBLE_ENTRIES)
			.toList();
	}

	public AllayCourierHudPacket(FriendlyByteBuf buffer) {
		int count = buffer.readVarInt();
		List<AllayCourierHudEntry> decoded =
			new ArrayList<>(Math.max(0, Math.min(count, MAX_VISIBLE_ENTRIES)));
		for (int i = 0; i < count; i++) {
			java.util.UUID id = buffer.readUUID();
			boolean incoming = buffer.readBoolean();
			String address = buffer.readUtf(AllayCourierHudEntry.MAX_ADDRESS_LENGTH);
			int seconds = buffer.readVarInt();
			AllayCourierHudStatus status = AllayCourierHudStatus.byId(buffer.readVarInt());
			if (i < MAX_VISIBLE_ENTRIES) {
				decoded.add(new AllayCourierHudEntry(id, incoming, address, seconds, status));
			}
		}
		entries = List.copyOf(decoded);
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeVarInt(entries.size());
		for (AllayCourierHudEntry entry : entries) {
			buffer.writeUUID(entry.id());
			buffer.writeBoolean(entry.incoming());
			buffer.writeUtf(entry.address(), AllayCourierHudEntry.MAX_ADDRESS_LENGTH);
			buffer.writeVarInt(entry.etaSeconds());
			buffer.writeVarInt(entry.status().ordinal());
		}
	}

	public void handle(LocalPlayer player) {
		AllayCourierHudOverlay.update(entries);
	}
}
