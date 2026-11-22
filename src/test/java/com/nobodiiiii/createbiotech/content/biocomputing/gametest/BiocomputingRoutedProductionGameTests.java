package com.nobodiiiii.createbiotech.content.biocomputing.gametest;
import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.AuthoredLibraryWorldFixture.*;
import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterMemberEditorAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.registry.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.*;
import com.simibubi.create.content.logistics.packagerLink.*;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.*;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

/** Existing native Create dispatch fixture pattern; ordinary public programs alone build every request/check.
 * Player supplies a selected exact list. This does NOT claim automatic recipe/selection composition. */
@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class BiocomputingRoutedProductionGameTests {
 static DataValue.RecordValue record(Object... pairs){var fields=new LinkedHashMap<String,DataValue>();for(int i=0;i<pairs.length;i+=2)fields.put((String)pairs[i],pairs[i+1] instanceof DataValue v?v:new DataValue.IntegerValue(((Number)pairs[i+1]).longValue()));return new DataValue.RecordValue(fields);}
 static DataValue.TextValue text(String s){return new DataValue.TextValue(s);}
 static ProgramDocument document(String id){java.util.function.Function<net.minecraft.resources.ResourceLocation,java.io.InputStream> resources=p->BiocomputingRoutedProductionGameTests.class.getClassLoader().getResourceAsStream("assets/"+p.getNamespace()+"/"+p.getPath());return ProgramExampleCatalog.preview(ProgramExampleCatalog.load(resources).stream().filter(e->e.id().equals(id)).findFirst().orElseThrow(),resources,CBConfigs.SERVER.biocomputing.current(true).archive());}
 static ProgramDocument inputs(ProgramDocument doc,DataValue.RecordValue initial){var body=doc.flow().orElseThrow();return new ProgramDocument(doc.archive(),doc.positions(),doc.terminal(),Optional.of(new FlowDocumentBody(body.inputs(),body.steps(),initial,body.canvas(),body.inline(),body.execution())));}
 static DataValue.RecordValue result(ComputerBlockEntity a,String field){return (DataValue.RecordValue)host(a).flowSnapshot().orElseThrow().state().locals().field(field);}
 static void await(GameTestHelper h,List<ComputerBlockEntity> active,Runnable finish){h.runAfterDelay(5,()->{for(var c:active)h.assertTrue(host(c).flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.FAILED,"Actual public ISA fault: "+host(c).flowSnapshot().orElseThrow().state().diagnostic());if(host(active.getFirst()).flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.COMPLETE)finish.run();else await(h,active,finish);});}
 @GameTest(template="empty",timeoutTicks=16000)
 public static void realSelectedMaterialsKeepFrozenRouteAcrossRebindReloadAndLegacyQuery(GameTestHelper h){
  var level=h.getLevel();var min=h.absolutePos(new BlockPos(1,1,1));var max=min.offset(6,3,4);var controllerPos=min.offset(6,1,2);var portPos=controllerPos.east(4);
  var names=List.of("a","e","d","b","c","f");var computers=new ArrayList<ComputerBlockEntity>();var members=new LinkedHashMap<String,ClusterConfiguration.Member>();
  for(int i=0;i<6;i++){var c=computer(h,i<3?min.offset(1+i*2,1,1):min.offset(1+(i-3)*2,1,3),VillagerProfession.CLERIC,5);computers.add(c);var empty=new ProgramWorkspace();empty.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(Map.of(),List.of()).withAssembly(),record())));host(c).installDocument(empty.snapshot(),ComputerRuntime.MemoryPolicy.RESET);host(c).configureEndpoint(new EndpointConfiguration(names.get(i),true));members.put(names.get(i),new ClusterConfiguration.Member(host(c).communicationIdentity().orElseThrow(),c.getBlockPos()));}
  for(var p:BlockPos.betweenClosed(min,max))if(p.getX()==min.getX()||p.getX()==max.getX()||p.getY()==min.getY()||p.getY()==max.getY()||p.getZ()==min.getZ()||p.getZ()==max.getZ())level.setBlock(p,CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(),3);
  level.setBlock(controllerPos,CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING,Direction.EAST),3);var controller=(ClusterControllerBlockEntity)level.getBlockEntity(controllerPos);
  var actor=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"routed-production"));actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(controllerPos.east()));
  var bc=new XBusWire(new XBusWire.Pin("b","b","x1"),new XBusWire.Pin("c","c","x0"),true);
  var wires=Map.of("ad",new XBusWire(new XBusWire.Pin("a","a","x0"),new XBusWire.Pin("d","d","x0"),true),"ae",new XBusWire(new XBusWire.Pin("a","a","x1"),new XBusWire.Pin("e","e","x0"),true),"db",new XBusWire(new XBusWire.Pin("d","d","x1"),new XBusWire.Pin("b","b","x0"),true),"bc",bc,"ef",new XBusWire(new XBusWire.Pin("e","e","x1"),new XBusWire.Pin("f","f","x0"),true));
  h.assertValueEqual(controller.configure(new ClusterConfiguration(members,Map.of(),Map.of(),wires),actor),"configured","Explicit A/E/F/D/B/C cooperation, not automatic routing");
  var b=computers.get(3);host(b).configureSerialGateway("s0",Optional.of(SerialGatewayAccess.authorize(new ClusterMemberEditorAccess(controller,b),actor,"s0","production")));host(b).configureSerial(new SerialControlData.Configuration("s0",Direction.EAST,true));
  for(int x=1;x<4;x++)level.setBlock(controllerPos.east(x),CBBlocks.DATA_CABLE.get().defaultBlockState(),3);
  var panel=CBBlocks.MATERIAL_REQUEST_PORT.get().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.WEST);level.setBlock(portPos,panel,3);var portHost=(SmartBlockEntity)level.getBlockEntity(portPos);var port=portHost.getBehaviour(ProductionPortBehaviour.TYPE);
  var packagerPos=portPos.south(3);level.setBlock(packagerPos.west(),Blocks.CHEST.defaultBlockState(),3);level.setBlock(packagerPos,AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING,Direction.EAST),3);level.setBlock(packagerPos.north(),AllBlocks.STOCK_LINK.getDefaultState().setValue(PackagerLinkBlock.FACE,AttachFace.WALL).setValue(PackagerLinkBlock.FACING,Direction.NORTH),3);
  var link=(PackagerLinkBlockEntity)level.getBlockEntity(packagerPos.north());var network=UUID.randomUUID();LogisticallyLinkedBehaviour.remove(link.behaviour);link.behaviour.freqId=network;LogisticallyLinkedBehaviour.keepAlive(link.behaviour);
  var custom=new CompoundTag();custom.putString("marker","selected-native");custom.putDouble("raw",-0.0d);var source=new ItemStack(Items.STONE,64);source.set(DataComponents.CUSTOM_DATA,CustomData.of(custom));var sourceDescription=source.copyWithCount(1);
  var chest=(Container)level.getBlockEntity(packagerPos.west());chest.setItem(0,source);var packager=(PackagerBlockEntity)level.getBlockEntity(packagerPos);
  var codec=CBConfigs.SERVER.biocomputing.serialCodec(CBConfigs.SERVER_SPEC.isLoaded());var nbt=new NativeNbtCodec.Limits(codec.maxEncodedBytes(),codec.maxDecodeHeapBytes(),codec.values());var nativePatch=DataComponentPatch.CODEC.encodeStart(level.registryAccess().createSerializationContext(NbtOps.INSTANCE),source.getComponentsPatch()).getOrThrow();
  var selected=new DataValue.ListValue(List.of(record("item",text("minecraft:stone"),"count",2,"components",NativeNbtCodec.snapshot(nativePatch,nbt)),record("item",text("minecraft:stone"),"count",2,"components",NativeNbtCodec.snapshot(nativePatch,nbt))));
  var target=record("x",portPos.getX(),"y",portPos.getY(),"z",portPos.getZ());var initial=record("target",target,"job",text("routed-native-stable"),"expected_address",text("workshop"),"items",selected,"poll_ticks",20);
  var docs=List.of(inputs(document("routed_production_a"),initial),document("routed_production_e"),document("routed_production_d"),document("production_b"),document("production_c"),document("routed_production_f"));
  for(int i=0;i<6;i++){host(computers.get(i)).installDocument(docs.get(i),ComputerRuntime.MemoryPolicy.RESET);h.assertValueEqual(host(computers.get(i)).hardware().limits().hardware(),new ResourceLimits(256,65536,8),"Real independent default budget");}
  var tuned=new ItemStack(AllBlocks.STOCK_LINK.asItem());var frequency=new CompoundTag();frequency.putUUID("Freq",network);tuned.set(DataComponents.BLOCK_ENTITY_DATA,CustomData.of(frequency));actor.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,tuned);
  h.runAfterDelay(30,()->{
   actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(portPos));h.assertValueEqual(port.configure(actor,port.revision(),Optional.of(port.discover(actor).getFirst()),"workshop"),"configured","Explicit physical source grant and intended route");
   host(computers.get(5)).setPaused(false); // Explicitly start policy F before E, while A is paused.
   h.runAfterDelay(4,()->{for(int i:List.of(1,2,4,3))host(computers.get(i)).setPaused(false);
    h.runAfterDelay(40,()->{
     h.assertValueEqual(host(computers.getFirst()).flowSnapshot().orElseThrow().state().work(),0L,"A remains explicitly paused during service startup");for(int i:List.of(1,2))h.assertTrue(host(computers.get(i)).flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.WAITING_READ,"Service does not READ paused A");host(computers.getFirst()).setPaused(false);
     await(h,computers,()->{
      var first=result(computers.getFirst(),"result");var raw=(DataValue.RecordValue)first.field("r");h.assertValueEqual(first.field("c"),text("checked_reply"),"Local route check, not manufacturing completion");h.assertValueEqual(raw.field("code"),text("accepted"),"Actual native port admission");h.assertValueEqual(first.field("a"),text("workshop"),"Authorized current route");h.assertValueEqual(first.field("o"),text("workshop"),"Actual frozen command route");h.assertValueEqual(chest.getItem(0).getCount(),60,"Exactly four actual selected items extracted once");h.assertValueEqual(PackageItem.getAddress(packager.heldBox),"workshop","Real Create package destination");
      var contents=PackageItem.getContents(packager.heldBox);int count=0;for(int slot=0;slot<contents.getSlots();slot++){var stack=contents.getStackInSlot(slot);if(!stack.isEmpty()){h.assertTrue(ItemStack.isSameItemSameComponents(sourceDescription,stack),"Actual native component patch remains exact");count+=stack.getCount();}}h.assertValueEqual(count,4,"Both duplicate input rows requested, no hidden scaling");
      h.assertValueEqual(host(computers.getFirst()).flowSnapshot().orElseThrow().initialInputs(),initial,"Selected source list unchanged");var originalBox=packager.heldBox.copy();var oldCorrelation=raw.field("request");
      h.assertValueEqual(port.configure(actor,port.revision(),Optional.of(port.discover(actor).getFirst()),"new-workshop"),"configured","Explicit route change rotates session, not order history");
      // COMPLETE may still retain XBus receipts. Retire them through the public reset before changing inputs/program.
      host(computers.getFirst()).setPaused(true);host(computers.getFirst()).reset();
      h.assertTrue(host(computers.getFirst()).flowSnapshot().orElseThrow().receipts().isEmpty(),"Explicit reset retires completed exchange history");
      var changed=new LinkedHashMap<>(initial.fields());changed.put("expected_address",text("new-workshop"));host(computers.getFirst()).installDocument(inputs(document("routed_production_a"),new DataValue.RecordValue(changed)),ComputerRuntime.MemoryPolicy.RESET);
      h.runAfterDelay(4,()->{host(computers.getFirst()).setPaused(false);await(h,computers,()->{
       var historical=result(computers.getFirst(),"result");var existing=(DataValue.RecordValue)historical.field("r");h.assertValueEqual(historical.field("c"),text("historical_route_mismatch"),"Old order is NOT new-route success");h.assertValueEqual(existing.field("code"),text("existing"),"Original raw code is not overwritten");h.assertValueEqual(historical.field("e"),text("new-workshop"),"New player expectation");h.assertValueEqual(historical.field("a"),text("new-workshop"),"New authorized current route");h.assertValueEqual(historical.field("o"),text("workshop"),"Old frozen route remains visible");h.assertTrue(!oldCorrelation.equals(existing.field("request")),"Fresh independent wire correlation");h.assertValueEqual(chest.getItem(0).getCount(),60,"No second extraction after rebind");h.assertTrue(ItemStack.matches(originalBox,packager.heldBox),"Existing package is not rewritten or redispatched");
       var saved=portHost.saveWithoutMetadata(level.registryAccess());level.removeBlock(portPos,false);level.setBlock(portPos,panel,3);var restoredHost=(SmartBlockEntity)level.getBlockEntity(portPos);restoredHost.loadWithComponents(saved,level.registryAccess());var restored=restoredHost.getBehaviour(ProductionPortBehaviour.TYPE);
       // Explicitly stop/reset and rewire for the unchanged read-only public query. No hidden protocol bridge.
       for(var c:computers){host(c).setPaused(true);host(c).reset();host(c).setPaused(true);}
       actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(controllerPos.east()));
       h.assertValueEqual(controller.configure(new ClusterConfiguration(members,Map.of(),Map.of(),Map.of("ab",new XBusWire(new XBusWire.Pin("a","a","x0"),new XBusWire.Pin("b","b","x0"),true),"bc",bc)),actor),"configured","Explicit direct query A-B-C wiring");
       host(b).configureSerialGateway("s0",Optional.of(SerialGatewayAccess.authorize(new ClusterMemberEditorAccess(controller,b),actor,"s0","production")));actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(portPos));
       h.assertValueEqual(restored.configure(actor,restored.revision(),Optional.of(restored.discover(actor).getFirst()),"new-workshop"),"configured","Appropriate current authorization after host restore/rewire");
       host(computers.getFirst()).installDocument(inputs(document("production_poll_a"),record("target",target,"job",text("routed-native-stable"))),ComputerRuntime.MemoryPolicy.RESET);
       h.runAfterDelay(4,()->{for(int i:List.of(4,3,0))host(computers.get(i)).setPaused(false);await(h,List.of(computers.getFirst(),b,computers.get(4)),()->{
        var query=result(computers.getFirst(),"reply");h.assertValueEqual(query.field("code"),text("ok"),"Actual unchanged public query");h.assertValueEqual(query.field("phase"),text("waiting"),"No invented manufacturing completion");h.assertValueEqual(query.field("address"),text("workshop"),"Reloaded order retains original frozen route");h.assertValueEqual(restored.status().field("address"),text("new-workshop"),"Host current route remains different");h.assertValueEqual(chest.getItem(0).getCount(),60,"Query/reload never extract again");h.assertTrue(ItemStack.matches(originalBox,packager.heldBox),"Original native package retained");for(var c:computers)host(c).setPaused(true);h.succeed();
       });});
      });});
     });
    });
   });
  });
 }
}
