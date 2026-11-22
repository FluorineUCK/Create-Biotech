package com.nobodiiiii.createbiotech.content.biocomputing.library.goggles;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryGoggleDetails;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.network.CBPackets;
import com.simibubi.create.content.equipment.goggles.GogglesItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
/** Minecraft-thread authority adapter. Entity.pick uses Sable's projected eye and Level.clip. */
public final class FactoryGoggleServer {
 private static final FactoryGoggleProjectionService SERVICE=new FactoryGoggleProjectionService();
 private FactoryGoggleServer() {}
 public static void request(ServerPlayer player,FactoryGoggleTarget target,long requestId) {
  if(!player.server.isSameThread())throw new IllegalStateException("advanced capture off server thread");
  if(!GogglesItem.isWearingGoggles(player)||!player.isShiftKeyDown())return;
  var level=player.serverLevel();if(!level.dimension().equals(target.address().dimension()))return;
  var hit=player.pick(player.blockInteractionRange(),1,false);
  if (!(hit instanceof BlockHitResult block) || hit.getType()!=HitResult.Type.BLOCK || !level.hasChunkAt(block.getBlockPos())) return;
  // Sable hits are local. Only resolve the device at the server's own projected hit, never an address supplied by the client.
  var worldHit=SubLevelCompat.toWorld(level,block.getBlockPos(),hit.getLocation());
  if (worldHit.distanceTo(SubLevelCompat.getEyePositionInterpolated(player,1)) > player.blockInteractionRange()+1e-6) return;
  var pointed=SpaceAddress.capture(level,FactoryGoggleProxy.resolve(level,block.getBlockPos()));
  if (!pointed.equals(target.address())) return;
  var be=pointed.resolveBlockEntity(player.server);if (!(be instanceof LibraryBlockEntity library) || library.isRemoved()) return;
  var actual=library.advancedGoggleMetadata().capture(library);
  int interval=LibraryGoggleDetails.interval();
  SERVICE.request(player,new FactoryGoggleProjectionService.Context(true,true,true,true,pointed,actual),target,player.server.overworld().getGameTime(),interval,()->LibraryGoggleDetails.capture(library,target,interval))
   .projection().ifPresent(value->CBPackets.sendToPlayer(new AdvancedGoggleResponsePacket(value,requestId),player));
 }
}
