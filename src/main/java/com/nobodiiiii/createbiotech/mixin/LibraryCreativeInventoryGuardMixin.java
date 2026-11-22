package com.nobodiiiii.createbiotech.mixin;

import com.nobodiiiii.createbiotech.content.biocomputing.library.inventory.LibraryCreativeItems;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class LibraryCreativeInventoryGuardMixin {
    @Shadow public ServerPlayer player;
    @Inject(method = "handleSetCreativeModeSlot", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
        shift = At.Shift.AFTER), cancellable = true)
    private void createBiotech$protectPhysicalArchive(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        if (LibraryCreativeItems.rejectLegacyReplacement(player, packet.slotNum(), packet.itemStack())) ci.cancel();
    }
}
