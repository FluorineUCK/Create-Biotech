package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.network.CBPackets;
import java.util.Objects;
import java.util.UUID;
import net.createmod.catnip.net.base.BasePacketPayload.PacketTypeProvider;
import net.createmod.catnip.net.base.ServerboundPacketPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public record ProgramEditorRequest(int containerId, UUID nonce, long intent, long revision,
	boolean install, boolean resetMemory, byte[] archive) implements ServerboundPacketPayload {
	public ProgramEditorRequest {
		nonce = Objects.requireNonNull(nonce, "nonce"); archive = Objects.requireNonNull(archive, "archive").clone();
		if (containerId < 0 || intent < 0 || revision < 0 || archive.length > ProgramEditorWire.MAX_BYTES
			|| !install && archive.length != 0) throw new IllegalArgumentException("invalid program editor request");
	}
	public ProgramEditorRequest(RegistryFriendlyByteBuf buffer) {
		this(buffer.readVarInt(), buffer.readUUID(), buffer.readVarLong(), buffer.readVarLong(), buffer.readBoolean(),
			buffer.readBoolean(), readArchive(buffer));
	}
	private static byte[] readArchive(RegistryFriendlyByteBuf buffer) {
		int length = buffer.readVarInt();
		if (length < 0 || length > ProgramEditorWire.MAX_BYTES) throw new IllegalArgumentException("program archive is too large");
		byte[] archive = new byte[length]; buffer.readBytes(archive); return archive;
	}
	@Override public byte[] archive() { return archive.clone(); }
	public void write(RegistryFriendlyByteBuf buffer) {
		buffer.writeVarInt(containerId); buffer.writeUUID(nonce); buffer.writeVarLong(intent); buffer.writeVarLong(revision);
		buffer.writeBoolean(install); buffer.writeBoolean(resetMemory); buffer.writeVarInt(archive.length); buffer.writeBytes(archive);
	}
	@Override public PacketTypeProvider getTypeProvider() { return CBPackets.programEditorRequestType(); }
	@Override public void handle(ServerPlayer player) {
		if (player.containerMenu instanceof ComputerProgramMenu menu) menu.handle(player, this);
	}
}
