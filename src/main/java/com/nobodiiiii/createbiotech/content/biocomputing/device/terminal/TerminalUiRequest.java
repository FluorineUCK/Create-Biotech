package com.nobodiiiii.createbiotech.content.biocomputing.device.terminal;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/** The live server menu, not the packet, supplies the device, layout version, and authorization choices. */
public record TerminalUiRequest(int containerId, UUID nonce, Action action, int choice, String instance, String text) {
    public enum Action { REFRESH, MANAGE, VIEW, GRANT, REVOKE, CLICK, SUBMIT, CC_ALLOW_READ, CC_REVOKE_READ,
        CC_ALLOW_PUBLISH, CC_REVOKE_PUBLISH }
    static final int MAX_CHOICES = 128;

    public TerminalUiRequest {
        Objects.requireNonNull(nonce);
        Objects.requireNonNull(action);
        Objects.requireNonNull(instance);
        Objects.requireNonNull(text);
        boolean interaction = action == Action.CLICK || action == Action.SUBMIT;
        if (containerId < 0
            || (action == Action.GRANT ? choice < 0 || choice >= MAX_CHOICES
                : action == Action.CC_ALLOW_READ || action == Action.CC_ALLOW_PUBLISH ? choice < 0 : choice != -1)
            || (interaction ? instance.isEmpty() || instance.length() > TerminalUiState.MAX_INSTANCE_CHARS : !instance.isEmpty())
            || (action == Action.SUBMIT ? text.length() > TerminalUiState.MAX_INPUT_CHARS : !text.isEmpty()))
            throw new IllegalArgumentException("invalid_terminal_request");
    }

    public TerminalUiRequest(RegistryFriendlyByteBuf wire) {
        this(wire.readVarInt(), wire.readUUID(), wire.readEnum(Action.class), wire.readVarInt(),
            wire.readUtf(TerminalUiState.MAX_INSTANCE_CHARS), wire.readUtf(TerminalUiState.MAX_INPUT_CHARS));
    }

    public void write(RegistryFriendlyByteBuf wire) {
        wire.writeVarInt(containerId);
        wire.writeUUID(nonce);
        wire.writeEnum(action);
        wire.writeVarInt(choice);
        wire.writeUtf(instance, TerminalUiState.MAX_INSTANCE_CHARS);
        wire.writeUtf(text, TerminalUiState.MAX_INPUT_CHARS);
    }

    public void handle(ServerPlayer player) {
        if (player.containerMenu instanceof TerminalUiMenu menu) menu.handle(player, this);
    }
}
