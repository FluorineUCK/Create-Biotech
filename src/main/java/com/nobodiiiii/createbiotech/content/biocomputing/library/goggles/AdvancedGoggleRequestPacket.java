package com.nobodiiiii.createbiotech.content.biocomputing.library.goggles;
import java.util.Objects;
import net.createmod.catnip.net.base.ServerboundPacketPayload;
import net.createmod.catnip.net.base.BasePacketPayload.PacketTypeProvider;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import com.nobodiiiii.createbiotech.network.CBPackets;
public record AdvancedGoggleRequestPacket(FactoryGoggleTarget target,long requestId) implements ServerboundPacketPayload {
 public static final int VERSION=1;
 public static final StreamCodec<RegistryFriendlyByteBuf,AdvancedGoggleRequestPacket> STREAM_CODEC=StreamCodec.of((b,p)->p.write(b),AdvancedGoggleRequestPacket::read);
 public AdvancedGoggleRequestPacket {Objects.requireNonNull(target);if(requestId<0)throw new IllegalArgumentException("negative request id");}
 public void write(RegistryFriendlyByteBuf b) { b.writeVarInt(VERSION);b.writeVarLong(requestId);target.write(b); }
 private static AdvancedGoggleRequestPacket read(RegistryFriendlyByteBuf b) { if(b.readVarInt()!=VERSION)throw new IllegalArgumentException("unsupported advanced request schema");long requestId=b.readVarLong();return new AdvancedGoggleRequestPacket(FactoryGoggleTarget.read(b),requestId); }
 @Override public void handle(ServerPlayer player) { FactoryGoggleServer.request(player,target,requestId); }
 @Override public PacketTypeProvider getTypeProvider() { return CBPackets.advancedGoggleRequestType(); }
}
