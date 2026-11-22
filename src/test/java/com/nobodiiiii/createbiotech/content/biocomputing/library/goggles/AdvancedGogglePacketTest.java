package com.nobodiiiii.createbiotech.content.biocomputing.library.goggles;

import com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.AdvancedGoggleRequestPacket;
import com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.AdvancedGoggleResponsePacket;
import com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleClient;
import com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleInformation;
import com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleProxy;
import com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleServer;
import com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleTarget;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.RegistryAccess;

class AdvancedGogglePacketTest {
 @BeforeAll static void bootstrap() throws Exception {
  Class<?> type=Class.forName("net.neoforged.fml.loading.LoadingModList");
  if(type.getMethod("get").invoke(null)==null) type.getMethod("of",java.util.List.class,java.util.List.class,java.util.List.class,java.util.List.class,java.util.Map.class).invoke(null,java.util.List.of(),java.util.List.of(),java.util.List.of(),java.util.List.of(),java.util.Map.of());
  net.minecraft.SharedConstants.tryDetectVersion(); var f=net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");f.setAccessible(true);f.setBoolean(null,true);
 }
 @Test void requestAndResponseUseRealBoundedCodecs() {
  var t=FactoryGoggleProjectionServiceTest.target(3); var b=buffer();
  try { var request=new AdvancedGoggleRequestPacket(t,7); AdvancedGoggleRequestPacket.STREAM_CODEC.encode(b,request); assertEquals(request,AdvancedGoggleRequestPacket.STREAM_CODEC.decode(b));
   var response=new AdvancedGoggleResponsePacket(FactoryGoggleProjectionServiceTest.projection(t),7); AdvancedGoggleResponsePacket.STREAM_CODEC.encode(b,response);assertEquals(response,AdvancedGoggleResponsePacket.STREAM_CODEC.decode(b));assertEquals(false,call(b,"isReadable"));
  } finally { call(b,"release"); }
 }
 @Test void oldWireSchemaAndOversizedCollectionAreRejected() {
  var b=buffer();
  try { write(b,"writeVarInt",int.class,0);assertThrows(IllegalArgumentException.class,()->AdvancedGoggleRequestPacket.STREAM_CODEC.decode(b)); call(b,"clear");
   write(b,"writeVarInt",int.class,AdvancedGoggleResponsePacket.VERSION); write(b,"writeVarLong",long.class,7L);FactoryGoggleProjectionServiceTest.target(3).write(b);write(b,"writeVarLong",long.class,100L);write(b,"writeVarInt",int.class,5);write(b,"writeVarInt",int.class,2);write(b,"writeVarInt",int.class,3);
   assertThrows(IllegalArgumentException.class,()->AdvancedGoggleResponsePacket.STREAM_CODEC.decode(b));
  } finally { call(b,"release"); }
 }

 static RegistryFriendlyByteBuf buffer() {try {var type=Class.forName("io.netty.buffer.ByteBuf");var raw=Class.forName("io.netty.buffer.Unpooled").getMethod("buffer").invoke(null);return (RegistryFriendlyByteBuf)RegistryFriendlyByteBuf.class.getConstructor(type,RegistryAccess.class).newInstance(raw,RegistryAccess.EMPTY);}catch(Exception e){throw new AssertionError(e);}}
 static Object call(Object b,String method) {try{return b.getClass().getMethod(method).invoke(b);}catch(Exception e){throw new AssertionError(e);}}
 static void write(Object b,String method,Class<?> type,Object value) {try{b.getClass().getMethod(method,type).invoke(b,value);}catch(Exception e){throw new AssertionError(e);}}

 @Test void productionTypedPacketRegistrationUsesTheseExactCodecs() throws Exception {
  var registryType=Class.forName("net.createmod.catnip.net.base.CatnipPacketRegistry");
  var registry=registryType.getConstructor(String.class,String.class).newInstance("create_biotech","test");
  var register=com.nobodiiiii.createbiotech.network.CBPackets.class.getDeclaredMethod("registerPanelPackets",registryType);register.setAccessible(true);register.invoke(null,registry);
  var t=FactoryGoggleProjectionServiceTest.target(3);var request=new AdvancedGoggleRequestPacket(t,7);var response=new AdvancedGoggleResponsePacket(FactoryGoggleProjectionServiceTest.projection(t),7);
  var b=buffer();try {
   assertEquals(request,com.nobodiiiii.createbiotech.network.CBPackets.roundTripPanelForTest(request,b));
   assertEquals(response,com.nobodiiiii.createbiotech.network.CBPackets.roundTripPanelForTest(response,b));
  }finally {call(b,"release");}

 }
 @Test void realBlockEntityClientPacketsRetainOnlyExplicitAdvancedMetadataAndInvalidateIt() throws Exception {
  for(var name:java.util.List.of("biocomputing.library.LibraryBlockEntity")) {
   var type=Class.forName("com.nobodiiiii.createbiotech.content."+name);
   var ctor=type.getDeclaredConstructor(net.minecraft.world.level.block.entity.BlockEntityType.class,net.minecraft.core.BlockPos.class,net.minecraft.world.level.block.state.BlockState.class);ctor.setAccessible(true);
   Object be=ctor.newInstance(net.minecraft.world.level.block.entity.BlockEntityType.CHEST,net.minecraft.core.BlockPos.ZERO,net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
   var write=type.getDeclaredMethod("write",net.minecraft.nbt.CompoundTag.class,net.minecraft.core.HolderLookup.Provider.class,boolean.class);write.setAccessible(true);
   var read=type.getDeclaredMethod("read",net.minecraft.nbt.CompoundTag.class,net.minecraft.core.HolderLookup.Provider.class,boolean.class);read.setAccessible(true);
   var tag=new net.minecraft.nbt.CompoundTag();write.invoke(be,tag,RegistryAccess.EMPTY,true);
   var target=FactoryGoggleProjectionServiceTest.target(3);tag.put(FactoryGoggleTarget.ROOT,target.save());read.invoke(be,tag,RegistryAccess.EMPTY,true);
   var info=(FactoryGoggleInformation)be;assertEquals(target,info.advancedGoggleMetadata().clientTarget().orElseThrow());
   assertFalse(info.normalGoggles().save().toString().contains(target.incarnation().toString()));
   tag.remove(FactoryGoggleTarget.ROOT);read.invoke(be,tag,RegistryAccess.EMPTY,true);assertTrue(info.advancedGoggleMetadata().clientTarget().isEmpty());
  }
 }

 @Test void productionAuthorityAndOverlayPathsStayReadOnlyAndExact() throws Exception {
  var root=java.nio.file.Path.of("src/main/java/com/nobodiiiii/createbiotech");
  var server=java.nio.file.Files.readString(root.resolve("content/biocomputing/library/goggles/FactoryGoggleServer.java"));
  for(var call:java.util.List.of("isSameThread()","isWearingGoggles(player)","isShiftKeyDown()","player.pick(player.blockInteractionRange(),1,false)","FactoryGoggleProxy.resolve","resolveBlockEntity(player.server)","advancedGoggleMetadata().capture(library)","SERVICE.request"))assertTrue(server.contains(call),call);
  assertTrue(server.contains("LibraryGoggleDetails.capture(library,target,interval)"));
  assertFalse(server.contains("FactoryOrchestrationServer"));
  var overlay=java.nio.file.Files.readString(root.resolve("mixin/client/GoggleOverlayRendererMixin.java"));assertTrue(overlay.contains("FactoryGoggleClient.append"));
  var client=java.nio.file.Files.readString(root.resolve("client/CreateBiotechClient.java"));assertTrue(client.contains("FactoryGoggleClient::tick"));
 }
}
