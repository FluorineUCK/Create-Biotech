package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.network.CBPackets;
import java.util.Objects;
import java.util.UUID;
import net.createmod.catnip.net.base.BasePacketPayload.PacketTypeProvider;
import net.createmod.catnip.net.base.ServerboundPacketPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public record ProgramControlRequest(int containerId, UUID nonce, long intent, long revision,
    ComputerControlSession.Action action, byte[] inputs) implements ServerboundPacketPayload {
    private static final int MAX_SMALL_SELECTION_BYTES = 512; // Bounded menu selection, not a runtime resource.

    public ProgramControlRequest {
        nonce = Objects.requireNonNull(nonce); action = Objects.requireNonNull(action);
        inputs = Objects.requireNonNull(inputs).clone();
        if (containerId < 0 || intent < 0 || revision < 0 || inputs.length > ProgramEditorWire.MAX_BYTES
            || (action == ComputerControlSession.Action.CONFIGURE_RECEIVE_LOOP || action == ComputerControlSession.Action.CONFIGURE_RADIO || action == ComputerControlSession.Action.CONFIGURE_CARRIER_INPUT
                || action == ComputerControlSession.Action.CONFIGURE_WITCH_CARRIER || action == ComputerControlSession.Action.CONFIGURE_WITCH_FILTER || action == ComputerControlSession.Action.READ_TASK_DEBUG) && inputs.length > MAX_SMALL_SELECTION_BYTES
            || (action == ComputerControlSession.Action.TERMINAL_KEY || action == ComputerControlSession.Action.TERMINAL_KEY_STATE) && inputs.length > 256
            || !action.carriesInputs() && inputs.length != 0) throw new IllegalArgumentException("invalid control request");
    }
    public ProgramControlRequest(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), buffer.readUUID(), buffer.readVarLong(), buffer.readVarLong(),
            action(buffer.readVarInt()), bytes(buffer));
    }
    private static ComputerControlSession.Action action(int ordinal) {
        var values = ComputerControlSession.Action.values();
        if (ordinal < 0 || ordinal >= values.length) throw new IllegalArgumentException("invalid control action");
        return values[ordinal];
    }
    private static byte[] bytes(RegistryFriendlyByteBuf buffer) {
        int length = buffer.readVarInt();
        if (length < 0 || length > ProgramEditorWire.MAX_BYTES) throw new IllegalArgumentException("control inputs too large");
        byte[] value = new byte[length]; buffer.readBytes(value); return value;
    }
    @Override public byte[] inputs() { return inputs.clone(); }
    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId); buffer.writeUUID(nonce); buffer.writeVarLong(intent); buffer.writeVarLong(revision);
        buffer.writeVarInt(action.ordinal()); buffer.writeVarInt(inputs.length); buffer.writeBytes(inputs);
    }
    @Override public PacketTypeProvider getTypeProvider() { return CBPackets.programControlRequestType(); }
    @Override public void handle(ServerPlayer player) {
        if (player.containerMenu instanceof ComputerProgramMenu menu) menu.handle(player, this);
    }
}
