package com.nobodiiiii.createbiotech.content.biocomputing.library.goggles;
import java.util.*;
import net.createmod.catnip.net.base.ClientboundPacketPayload;
import net.createmod.catnip.net.base.BasePacketPayload.PacketTypeProvider;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.client.player.LocalPlayer;
import com.nobodiiiii.createbiotech.network.CBPackets;
public record AdvancedGoggleResponsePacket(FactoryAdvancedGoggleProjection projection,long requestId) implements ClientboundPacketPayload {
 public static final int VERSION=1;
 public static final StreamCodec<RegistryFriendlyByteBuf,AdvancedGoggleResponsePacket> STREAM_CODEC=StreamCodec.of((b,p)->p.write(b),AdvancedGoggleResponsePacket::read);
 public AdvancedGoggleResponsePacket {Objects.requireNonNull(projection);if(requestId<0)throw new IllegalArgumentException("negative request id");}
 public void write(RegistryFriendlyByteBuf b) {b.writeVarInt(VERSION);b.writeVarLong(requestId);projection.target().write(b);b.writeVarLong(projection.sampleTick());b.writeVarInt(projection.intervalTicks());b.writeVarInt(projection.rowLimit());for(var list:projection.collections()){b.writeVarInt(list.size());for(var text:list)b.writeUtf(text,FactoryAdvancedGoggleProjection.MAX_TEXT);} }
 private static AdvancedGoggleResponsePacket read(RegistryFriendlyByteBuf b) {
  if(b.readVarInt()!=VERSION)throw new IllegalArgumentException("unsupported advanced response schema");
  long requestId=b.readVarLong();var target=FactoryGoggleTarget.read(b);long tick=b.readVarLong();int interval=b.readVarInt(),limit=b.readVarInt();
  if(limit<1||limit>FactoryAdvancedGoggleProjection.MAX_ROWS)throw new IllegalArgumentException("invalid advanced budget");
  var lists=new ArrayList<List<String>>();int remaining=limit;for(int i=0;i<8;i++){int count=b.readVarInt();if(count<0||count>remaining)throw new IllegalArgumentException("oversized advanced collection");remaining-=count;var list=new ArrayList<String>(count);for(int n=0;n<count;n++)list.add(b.readUtf(FactoryAdvancedGoggleProjection.MAX_TEXT));lists.add(list);}
  return new AdvancedGoggleResponsePacket(new FactoryAdvancedGoggleProjection(target,tick,interval,limit,lists.get(0),lists.get(1),lists.get(2),lists.get(3),lists.get(4),lists.get(5),lists.get(6),lists.get(7)),requestId);
 }
 @Override public void handle(LocalPlayer player) { FactoryGoggleClient.accept(projection,requestId); }
 @Override public PacketTypeProvider getTypeProvider() {return CBPackets.advancedGoggleResponseType();}
}
