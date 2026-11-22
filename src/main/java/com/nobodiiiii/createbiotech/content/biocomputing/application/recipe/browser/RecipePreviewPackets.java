package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser;

import java.util.Objects;
import java.util.UUID;
import net.createmod.catnip.net.base.BasePacketPayload.PacketTypeProvider;
import net.createmod.catnip.net.base.ClientboundPacketPayload;
import net.createmod.catnip.net.base.ServerboundPacketPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternStorageCoreMenu;
import com.nobodiiiii.createbiotech.network.CBPackets;

/** Read-only request against the current server recipe manager, scoped to one open editor. */
public final class RecipePreviewPackets {
    private RecipePreviewPackets() {}
    public enum Outcome { READY, MISSING, DENIED, UNSUPPORTED_RECIPE, UNSUPPORTED_SELECTOR, UNSUPPORTED_OUTPUT, TOO_LARGE, BUSY,
        GRID_REQUIRED, REMAINDER, CUSTOM_REMAINDER }
    public record Request(int container, UUID session, UUID intent, ResourceLocation recipe) implements ServerboundPacketPayload {
        public Request(RegistryFriendlyByteBuf b) { this(b.readVarInt(), b.readUUID(), b.readUUID(), b.readResourceLocation()); }
        public void write(RegistryFriendlyByteBuf b) {
            b.writeVarInt(container); b.writeUUID(session); b.writeUUID(intent); b.writeResourceLocation(recipe);
        }
        @Override public PacketTypeProvider getTypeProvider() { return CBPackets.recipePreviewRequestType(); }
        @Override public void handle(ServerPlayer player) {
            if (player.containerMenu instanceof PatternStorageCoreMenu menu) menu.preview(player, this);
        }
        public Reply result(Outcome code) { return new Reply(container, session, intent, code, ""); }
    }
    public record Reply(int container, UUID session, UUID intent, Outcome outcome, String json) implements ClientboundPacketPayload {
        public Reply {
            Objects.requireNonNull(outcome);
            if (json.length() > PatternEditRequestPacket.MAX_JSON || (outcome == Outcome.READY) == json.isEmpty())
                throw new IllegalArgumentException("Invalid recipe preview");
        }
        public Reply(RegistryFriendlyByteBuf b) { this(b.readVarInt(), b.readUUID(), b.readUUID(), b.readEnum(Outcome.class), b.readUtf(PatternEditRequestPacket.MAX_JSON)); }
        public void write(RegistryFriendlyByteBuf b) {
            b.writeVarInt(container); b.writeUUID(session); b.writeUUID(intent); b.writeEnum(outcome); b.writeUtf(json, PatternEditRequestPacket.MAX_JSON);
        }
        @Override public PacketTypeProvider getTypeProvider() { return CBPackets.recipePreviewReplyType(); }
        @Override @net.neoforged.api.distmarker.OnlyIn(net.neoforged.api.distmarker.Dist.CLIENT)
        public void handle(net.minecraft.client.player.LocalPlayer player) {
            if (player.containerMenu instanceof PatternStorageCoreMenu menu) menu.receivePreview(this);
        }
    }
}
