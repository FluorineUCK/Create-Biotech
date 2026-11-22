package com.nobodiiiii.createbiotech.content.factorycluster.diagnostics;

import com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleInformation;
import com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleProxy;
import com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleState;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
class FactoryGoggleProjectionWiringTest {
 @Test void actualBlockEntitiesSyncAndReadOnlyTooltipUsesTheClientProjection() throws Exception {
  net.minecraft.SharedConstants.tryDetectVersion();
  var bootstrap=net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped"); bootstrap.setAccessible(true); bootstrap.setBoolean(null,true);
  Class<?> loading=Class.forName("net.neoforged.fml.loading.LoadingModList");
  if(loading.getMethod("get").invoke(null)==null) loading.getMethod("of", List.class,List.class,List.class,List.class,Map.class).invoke(null,List.of(),List.of(),List.of(),List.of(),Map.of());
  for (String name : List.of("biocomputing.library.LibraryBlockEntity")) {
   Class<?> type=Class.forName("com.nobodiiiii.createbiotech.content."+name);
   var constructor=type.getDeclaredConstructor(BlockEntityType.class, BlockPos.class, BlockState.class); constructor.setAccessible(true);
   Object server=constructor.newInstance(BlockEntityType.CHEST, BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
   Object client=constructor.newInstance(BlockEntityType.CHEST, BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
   var write=type.getDeclaredMethod("write", CompoundTag.class, HolderLookup.Provider.class, boolean.class); write.setAccessible(true);
   var read=type.getDeclaredMethod("read", CompoundTag.class, HolderLookup.Provider.class, boolean.class); read.setAccessible(true);
   CompoundTag tag=new CompoundTag(); write.invoke(server, tag, RegistryAccess.EMPTY, true);
   assertTrue(tag.contains(FactoryGoggleState.ROOT), name+tag);
   read.invoke(client, tag, RegistryAccess.EMPTY, true);
   var lines=new ArrayList<net.minecraft.network.chat.Component>();
   assertTrue(((FactoryGoggleInformation)client).addToGoggleTooltip(lines, false));
   assertEquals(((FactoryGoggleInformation)server).normalGoggles(), ((FactoryGoggleInformation)client).normalGoggles());
   var sneaking=new ArrayList<net.minecraft.network.chat.Component>();
   ((FactoryGoggleInformation)client).addToGoggleTooltip(sneaking, true); assertEquals(lines, sneaking);
  }
 }
 @Test void currentMaterialPortAndComputerUseNativeGogglesNotOldProductionChannels() {
  for (var type : List.of(com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.MaterialRequestPortBlockEntity.class,
      com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity.class)) {
   assertTrue(com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation.class.isAssignableFrom(type));
   assertFalse(FactoryGoggleInformation.class.isAssignableFrom(type));
  }
 }
 @Test void upperHalvesProxyOnlyToMatchingLowerAndEmbeddedFacesStayLocal() throws Exception {
  net.minecraft.SharedConstants.tryDetectVersion();
  var flag=net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped"); flag.setAccessible(true); flag.setBoolean(null,true);
  Class<?> loading=Class.forName("net.neoforged.fml.loading.LoadingModList");
  if(loading.getMethod("get").invoke(null)==null) loading.getMethod("of", List.class,List.class,List.class,List.class,Map.class).invoke(null,List.of(),List.of(),List.of(),List.of(),Map.of());
  var half=net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF;
  var facing=net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;
  for(var block:List.of(new com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock(net.minecraft.world.level.block.state.BlockBehaviour.Properties.of()),new com.nobodiiiii.createbiotech.content.biocomputing.block.LibraryCoreBlock(net.minecraft.world.level.block.state.BlockBehaviour.Properties.of()))) {
   var lower=block.defaultBlockState(); var upper=lower.setValue(half,net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
   var positions=new HashMap<BlockPos,BlockState>(); positions.put(BlockPos.ZERO,lower); positions.put(BlockPos.ZERO.above(),upper);
   assertEquals(BlockPos.ZERO,FactoryGoggleProxy.resolve(pos->positions.getOrDefault(pos,Blocks.AIR.defaultBlockState()),BlockPos.ZERO.above()));
   positions.put(BlockPos.ZERO,lower.setValue(facing,Direction.SOUTH));
   assertEquals(BlockPos.ZERO.above(),FactoryGoggleProxy.resolve(pos->positions.getOrDefault(pos,Blocks.AIR.defaultBlockState()),BlockPos.ZERO.above()));
  }
  var panel=new com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.MaterialRequestPortBlock(net.minecraft.world.level.block.state.BlockBehaviour.Properties.of());
  assertEquals(BlockPos.ZERO,FactoryGoggleProxy.resolve(pos->panel.defaultBlockState(),BlockPos.ZERO));
 }
 @Test void registeredComputerDoesNotShipTheHistoricalWorker() throws Exception {
  // The old decoder and its writer fixtures live on the test classpath, never in the main source set.
  var main = Path.of(com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity.class
      .getProtectionDomain().getCodeSource().getLocation().toURI());
  assertTrue(Files.isRegularFile(main.resolve("com/nobodiiiii/createbiotech/content/biocomputing/computer/ComputerBlockEntity.class")));
  for (var name : List.of("runtime/FactoryOrchestrationServer", "orchestration/engine/FactoryComputeWorker"))
   assertFalse(Files.exists(main.resolve("com/nobodiiiii/createbiotech/content/factorycluster/" + name + ".class")));
 }


}
