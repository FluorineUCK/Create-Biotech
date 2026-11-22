package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.util.*;
import java.nio.file.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
/** Full public six-owner ISA + finite real serial transport + actual durable journal/material decoder.
 * Only native world extraction is substituted; the separate native fixture covers that effect boundary. */
class RoutedProductionExecutionTest {
 static final ComputerHostLimits LIMITS=NativeLibraryOwnersTest.limits("cleric",5);
 static final BiocomputingConfig CONFIG=NativeLibraryOwnersTest.CONFIG;
 static final NativeNbtCodec.Limits NBT=new NativeNbtCodec.Limits(CONFIG.serialCodec(false).maxEncodedBytes(),CONFIG.serialCodec(false).maxDecodeHeapBytes(),CONFIG.serialCodec(false).values());
 static final DataValue.RecordValue TARGET=record("x",2,"y",0,"z",0);
 static final String JOB="stable-routed-job";
 @TempDir Path temp;
 @BeforeAll static void bootstrap()throws Exception{
  Class<?> type=Class.forName("net.neoforged.fml.loading.LoadingModList");if(type.getMethod("get").invoke(null)==null)type.getMethod("of",List.class,List.class,List.class,List.class,Map.class).invoke(null,List.of(),List.of(),List.of(),List.of(),Map.of());net.minecraft.SharedConstants.tryDetectVersion();var field=net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");field.setAccessible(true);field.setBoolean(null,true);new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE);
 }
 static DataValue.TextValue text(String s){return new DataValue.TextValue(s);}
 static DataValue.ListValue items(String marker){var custom=new CompoundTag();custom.putString("marker",marker);custom.putDouble("raw",-0.0d);var patch=new CompoundTag();patch.put("minecraft:custom_data",custom);var nativePatch=NativeNbtCodec.snapshot(patch,NBT);return new DataValue.ListValue(List.of(record("item","minecraft:stone","count",2,"components",nativePatch),record("item","minecraft:stone","count",1,"components",nativePatch)));}
 static ProgramDocument load(String id)throws Exception{try(var in=RoutedProductionExecutionTest.class.getResourceAsStream("/assets/create_biotech/biocomputing/examples/"+id+".bcp")){assertNotNull(in,id);return ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(in.readAllBytes()).strip()),SerialFlowHostTest.ARCHIVE);}}
 class Device implements AutoCloseable {
  final UUID source=new UUID(5,6),identity=new UUID(1,2);UUID session=new UUID(3,4);long revision=1;String address="workshop";
  boolean allowed=true,legacyStatus;java.util.function.UnaryOperator<DataValue.RecordValue> statusTransform;Runnable afterStatus,afterSubmit;
  int preparations,dispatches;DataValue.RecordValue prepared;final Path directory;ProductionOrderFileJournal journal;ProductionOrderService service;
  final Backend backend=new Backend(){
   public boolean authorized(UUID source){return allowed&&Device.this.source.equals(source);}public boolean supports(String e,int v){return e.equals("create.packaging")&&v==1;}
   public Preparation prepare(UUID source,DataValue.RecordValue data){ProductionMaterials.decode(data,RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY),NBT,CONFIG.productionNativeRequestEntries.getDefault());preparations++;prepared=data;return Preparation.ready(new Plan("create.packaging",1,List.of(record("network",text(new UUID(7,8).toString()),"address",text(address),"revision",revision,"data",data))));}
   public Dispatch dispatch(Operation operation,DataValue.RecordValue command){if(!authorized(operation.order().source())||!text(address).equals(command.field("address"))||!new DataValue.IntegerValue(revision).equals(command.field("revision")))return Dispatch.rejected("grant_changed");dispatches++;return Dispatch.accepted(record("native_order",17));}
   public Observation observe(Operation operation,DataValue.RecordValue receipt){return Observation.pending();}
  };
  Device()throws Exception{directory=Files.createDirectory(temp.resolve(UUID.randomUUID().toString()));journal=ProductionOrderFileJournal.initialize(directory,CONFIG.productionArchive(false));service=new ProductionOrderService(journal,backend,CONFIG.productionOrders(false));}
  ProductionPortProtocol protocol(){return legacyStatus?new ProductionPortProtocol(identity,session,revision,TARGET,service):new ProductionPortProtocol(identity,session,revision,TARGET,address,service);}
  DataValue.RecordValue respond(DataValue.RecordValue packet){var reply=protocol().respond(allowed?source:null,packet).orElseThrow();if(text("status").equals(packet.field("operation"))){if(statusTransform!=null)reply=statusTransform.apply(reply);if(afterStatus!=null){var hook=afterStatus;afterStatus=null;hook.run();}}else if(text("submit").equals(packet.field("operation"))&&afterSubmit!=null){var hook=afterSubmit;afterSubmit=null;hook.run();}return reply;}
  void rebind(String route){address=route;revision++;session=UUID.randomUUID();}
  void reload(){journal.close();journal=ProductionOrderFileJournal.open(directory,CONFIG.productionArchive(false));service=new ProductionOrderService(journal,backend,CONFIG.productionOrders(false));session=UUID.randomUUID();}
  @Override public void close(){journal.close();}
 }
 class Rig {
  final ComputerHostLimits limits;final Device device;final boolean query;final DataValue.RecordValue initial;final ComputerBlockHost[] h=new ComputerBlockHost[6];final long[] peaks=new long[6],nextPreflights=new long[6];
  final SerialDevice wireB,wirePort;
  final SerialNetwork network=new SerialNetwork();XBusHistorySavedData history=new XBusHistorySavedData();
  final Map<BlockPos,SerialDevice> devices;final CableDiscovery.World world;
  final List<DataValue.RecordValue> commands=new ArrayList<>(),actualReplies=new ArrayList<>();final ArrayDeque<DataValue.RecordValue> replies=new ArrayDeque<>();
  DataValue.RecordValue previousReply;int ticks,reloads,maxRequestBytes,maxReplyBytes,maxBuiltPacketBytes,maxIncrementalPacketBytes;DataValue measuredPacket;boolean restoredDecoder;
  Rig(Device device,String expected,DataValue.ListValue items)throws Exception{this(device,expected,items,false);}
  Rig(Device device,String expected,DataValue.ListValue items,boolean query)throws Exception{this(device,expected,items,query,8);}
  Rig(Device device,String expected,DataValue.ListValue items,boolean query,long work)throws Exception{
   var selected=RoutedProductionExecutionTest.LIMITS;limits=new ComputerHostLimits(new ResourceLimits(256,65536,work),selected.archive(),selected.mailbox(),selected.pins(),selected.serial(),selected.taskSlots(),selected.isa1a(),selected.isa1b());
   wireB=NativeLibraryOwnersTest.device(limits);wirePort=new SerialDevice(()->CONFIG.productionSerial(false),()->true,()->CONFIG.serialCodec(false),()->{});
   this.device=device;this.query=query;initial=query?record("target",TARGET,"job",JOB):record("target",TARGET,"job",JOB,"expected_address",expected,"items",items,"poll_ticks",20);
   for(int i=0;i<6;i++){String name=query&&i==0?"production_poll_a":List.of("routed_production_a","routed_production_e","routed_production_d","redstone_output_b","production_c","routed_production_f").get(i);var d=load(name);var body=d.flow().orElseThrow();h[i]=new ComputerBlockHost(()->{},p->{},i==3?wireB:null);h[i].installFlow(body.program(d.archive()),i==0?initial:body.initial(),limits,true);}
   for(int i:query?List.of(4,3):List.of(5,1,2,4,3))h[i].setPaused(false,limits,true);
   assertEquals(new ResourceLimits(256,65536,work),limits.hardware());wireB.configure("s0",Direction.EAST,true);wirePort.configure("s0",Direction.WEST,true);
   devices=Map.of(BlockPos.ZERO,wireB,new BlockPos(2,0,0),wirePort);world=new CableDiscovery.World(){public boolean loaded(BlockPos p){return true;}public CableDiscovery.Cell cell(BlockPos p){return new CableDiscovery.Cell(p.equals(new BlockPos(1,0,0))?CableDiscovery.Kind.CABLE:devices.containsKey(p)?CableDiscovery.Kind.ENDPOINT:CableDiscovery.Kind.EMPTY,null);}};
  }
  int status(int left,String lp,int right,String rp){return NativeLibraryStartupTest.status(h[left],lp,limits,h[right],rp,limits);}
  void step(boolean restore){
   for(int i=0;i<6;i++)nextPreflights[i]=Math.max(nextPreflights[i],RoutedPolicyMemoryProbe.observedNextPreflight(h[i].flowSnapshot().orElseThrow(),limits));
   h[5].tick(limits,true,Map.of("x0",status(5,"x0",1,"x1")));
   h[0].tick(limits,true,Map.of("x0",status(0,"x0",2,"x0"),"x1",status(0,"x1",1,"x0")));
   h[1].tick(limits,true,Map.of("x0",status(1,"x0",0,"x1")));
   h[2].tick(limits,true,Map.of("x0",status(2,"x0",0,"x0"),"x1",status(2,"x1",3,"x0")));
   h[3].tick(limits,true,Map.of("x0",query?status(3,"x0",0,"x0"):status(3,"x0",2,"x1"),"x1",status(3,"x1",4,"x0")));
   h[4].tick(limits,true,Map.of("x0",status(4,"x0",3,"x1")));
   if(query)NativeLibraryServiceSerialTest.exchange(h[0],"x0",limits,h[3],"x0",limits,history);else{NativeLibraryServiceSerialTest.exchange(h[1],"x1",limits,h[5],"x0",limits,history);NativeLibraryServiceSerialTest.exchange(h[0],"x0",limits,h[2],"x0",limits,history);NativeLibraryServiceSerialTest.exchange(h[0],"x1",limits,h[1],"x0",limits,history);NativeLibraryServiceSerialTest.exchange(h[2],"x1",limits,h[3],"x0",limits,history);}NativeLibraryServiceSerialTest.exchange(h[3],"x1",limits,h[4],"x0",limits,history);
   network.tick(world,devices,CONFIG.communication(false),CONFIG.serialMedium(false));
   if(wirePort.status("s0").receive().frames()>0){var received=wirePort.prepareReceive("s0");var packet=(DataValue.RecordValue)received.result().field("value");commands.add(packet);assertTrue(commands.size()<=2,"No hidden retry/poll");maxRequestBytes=Math.max(maxRequestBytes,wirePort.beginReceive("s0").head().size());var reply=device.respond(packet);actualReplies.add(reply);assertTrue(received.publish());maxReplyBytes=Math.max(maxReplyBytes,SerialRecordCodec.encode(reply,CONFIG.serialCodec(false)).length);if(previousReply!=null&&text("submit").equals(packet.field("operation")))replies.add(previousReply);replies.add(reply);}
   device.service.advance();
   if(!replies.isEmpty()&&wireB.status("s0").receive().frames()==0&&!Set.of("queued","transmitting").contains(wirePort.status("s0").transmit()))assertEquals("queued",wirePort.send("s0",replies.removeFirst()));
   var built=h[2].flowSnapshot().orElseThrow().state().locals().fields().get("packet");if(built instanceof DataValue.RecordValue packet&&text("production_command").equals(packet.fields().get("kind"))&&!packet.equals(measuredPacket)){measuredPacket=packet;maxBuiltPacketBytes=Math.max(maxBuiltPacketBytes,SerialRecordCodec.encode(packet,CONFIG.serialCodec(false)).length);maxIncrementalPacketBytes=Math.max(maxIncrementalPacketBytes,RoutedProductionFrameProbe.incrementalBytes(packet,CONFIG.serialCodec(false)));}
   ticks++;
   for(int i=0;i<6;i++){var saved=h[i].flowSnapshot().orElseThrow();assertNotEquals(FlowRuntime.Status.FAILED,saved.state().status(),"role="+i+" "+saved.state().diagnostic());peaks[i]=Math.max(peaks[i],RoutedPolicyMemoryProbe.committed(saved,limits));assertTrue(peaks[i]<=65536);}
   boolean decoder=h[3].flowSnapshot().orElseThrow().tasks().contexts().stream().anyMatch(s->s.assembly().flatMap(runtime->runtime.serial()).filter(progress->progress.receiving()).isPresent());
   if(restore){for(int i=0;i<6;i++){boolean paused=h[i].flowSnapshot().orElseThrow().state().paused();h[i]=NativeLibraryOwnersTest.reload(h[i],limits,i==3?wireB:null);h[i].setPaused(paused,limits,true);}history=XBusHistorySavedData.load(history.save(new CompoundTag(),RegistryAccess.EMPTY),RegistryAccess.EMPTY);reloads++;restoredDecoder|=decoder;}
  }
  DataValue.RecordValue finish(boolean everyBoundary){
   for(int n=0;n<60;n++){step(n==20);for(int i:List.of(1,2))assertNotEquals(FlowRuntime.Status.WAITING_READ,h[i].flowSnapshot().orElseThrow().state().status(),"Do not READ paused A");}assertEquals(0,h[0].flowSnapshot().orElseThrow().state().work());h[0].setPaused(false,limits,true);
   for(int n=0;n<(limits.hardware().workPerSlice()==1?50000:20000);n++){step(everyBoundary||n%29==0);var state=h[0].flowSnapshot().orElseThrow().state();if(state.status()==FlowRuntime.Status.COMPLETE){assertEquals(initial,h[0].flowSnapshot().orElseThrow().initialInputs());for(var entry:initial.fields().entrySet())assertEquals(entry.getValue(),state.locals().field(entry.getKey()));var result=(DataValue.RecordValue)state.locals().field(query?"reply":"result");System.out.println("ROUTED_RUN local="+result.field(query?"code":"c")+" frames="+commands.size()+" maxRequest="+maxRequestBytes+" maxCompactPacket="+maxBuiltPacketBytes+" maxIncrementalPacket="+maxIncrementalPacketBytes+" maxReply="+maxReplyBytes+" words="+Arrays.stream(h).map(x->x.flowSnapshot().orElseThrow().program().usage(limits.hardware()).programUnits()).toList()+" work="+Arrays.stream(h).map(x->x.flowSnapshot().orElseThrow().state().work()).toList()+" sampledFullOwnerCommitted="+Arrays.toString(peaks)+" observedNextPreflight="+Arrays.toString(nextPreflights)+" reloads="+reloads+" decoder="+restoredDecoder);return result;}assertEquals(DataValue.NullValue.INSTANCE,TerminalStatusView.ofFlowOwner(Optional.empty(),h[0].flowSnapshot()).field("output"));}
   return fail("Routed driver did not finish");
  }
 }
 @Test void matchedRouteUsesOnlyPublicIsaAndPreservesSelectedDuplicatesAndNativeComponents()throws Exception{
  try(var device=new Device()){var selected=items("before");var rig=new Rig(device,"workshop",selected);var result=rig.finish(true);assertEquals(text("checked_reply"),result.field("c"));assertEquals(text("workshop"),result.field("a"));assertEquals(text("workshop"),result.field("o"));var raw=(DataValue.RecordValue)result.field("r");assertEquals(text("accepted"),raw.field("code"));assertEquals(List.of(text("status"),text("submit")),rig.commands.stream().map(c->c.field("operation")).toList());assertNotEquals(rig.commands.getFirst().field("request"),rig.commands.getLast().field("request"));var data=(DataValue.RecordValue)((DataValue.RecordValue)rig.commands.getLast().field("arguments")).field("data");assertEquals(selected,data.field("items"));assertEquals(data,device.prepared);var decoded=ProductionMaterials.decode(data,RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY),NBT,CONFIG.productionNativeRequestEntries.getDefault());assertEquals(List.of(3L),new ArrayList<>(decoded.values()));var nativeData=decoded.keySet().iterator().next().stack().get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();assertEquals("before",nativeData.getString("marker"));var originalPatch=(CompoundTag)NativeNbtCodec.toTag((DataValue.NbtValue)((DataValue.RecordValue)selected.values().getFirst()).field("components"),NBT);var originalNative=originalPatch.getCompound("minecraft:custom_data");assertEquals(originalNative,nativeData);assertEquals(Double.doubleToRawLongBits(originalNative.getDouble("raw")),Double.doubleToRawLongBits(nativeData.getDouble("raw")));System.out.println("ROUTED_NATIVE_BITS original="+Double.doubleToRawLongBits(originalNative.getDouble("raw"))+" decoded="+Double.doubleToRawLongBits(nativeData.getDouble("raw"))+" (vanilla putDouble fixture normalization)");assertEquals(rig.actualReplies.getLast(),raw);assertEquals(1,device.preparations);assertEquals(1,device.dispatches);assertTrue(rig.restoredDecoder);assertTrue(rig.maxRequestBytes<=4096&&rig.maxReplyBytes<=4096);}
 }
 @Test void mismatchedMissingMalformedAndDeniedCurrentRoutesNeverSubmitOrPrepare()throws Exception{
  var selected=items("before");
  for(String kind:List.of("mismatch","legacy","non_text","empty","too_long","bad_state","denied","busy"))try(var device=new Device()){
   if(kind.equals("legacy"))device.legacyStatus=true;if(kind.equals("denied"))device.allowed=false;
   if(List.of("non_text","empty","too_long","bad_state","busy").contains(kind))device.statusTransform=reply->{var fields=new LinkedHashMap<>(reply.fields());if(kind.equals("busy"))fields.put("code",text("busy"));else if(kind.equals("bad_state"))fields.put("state",text("malformed"));else{var state=new LinkedHashMap<>(((DataValue.RecordValue)fields.get("state")).fields());state.put("address",kind.equals("non_text")?new DataValue.IntegerValue(7):text(kind.equals("empty")?"":"x".repeat(26)));fields.put("state",new DataValue.RecordValue(state));}return new DataValue.RecordValue(fields);};
   var rig=new Rig(device,kind.equals("mismatch")?"elsewhere":"workshop",selected);var result=rig.finish(false);
   String local=kind.equals("mismatch")?"route_mismatch":List.of("denied","busy").contains(kind)?"device_reply":"route_unavailable";
   assertEquals(text(local),result.field("c"),kind);assertEquals(rig.actualReplies.getLast(),result.field("r"));assertEquals(1,rig.commands.size());assertEquals(0,device.preparations);assertEquals(0,device.dispatches);assertTrue(device.journal.load().orders().isEmpty());
  }
 }
 @Test void sessionChangedAfterStatusDoesNotCreateAnOrderOrAutomaticallyRetry()throws Exception{
  try(var device=new Device()){device.afterStatus=()->device.rebind("changed-route");var rig=new Rig(device,"workshop",items("before"));var result=rig.finish(false);assertEquals(text("device_reply"),result.field("c"));assertEquals(text("session_changed"),((DataValue.RecordValue)result.field("r")).field("code"));assertEquals(rig.actualReplies.getLast(),result.field("r"));assertEquals(2,rig.commands.size());assertEquals(0,device.preparations);assertEquals(0,device.dispatches);assertTrue(device.journal.load().orders().isEmpty());for(int n=0;n<60;n++)rig.step(false);assertEquals(2,rig.commands.size());}
 }
 @Test void durableOriginalOrderSurvivesRebindAndQueryWithFreshCorrelationsWithoutAnotherEffect()throws Exception{
  try(var device=new Device()){
   var selected=items("before");var firstRig=new Rig(device,"workshop",selected);var first=firstRig.finish(false);var original=(DataValue.RecordValue)first.field("r");
   var repeatedRig=new Rig(device,"workshop",selected);repeatedRig.previousReply=original;var repeated=repeatedRig.finish(false);var raw=(DataValue.RecordValue)repeated.field("r");assertEquals(text("existing"),raw.field("code"));assertNotEquals(original.field("request"),raw.field("request"));assertEquals(repeatedRig.commands.getLast().field("request"),raw.field("request"));assertEquals(repeatedRig.actualReplies.getLast(),raw);
   var conflictRig=new Rig(device,"workshop",items("changed-components"));var conflict=conflictRig.finish(false);assertEquals(text("request_conflict"),((DataValue.RecordValue)conflict.field("r")).field("code"));assertEquals(text("checked_reply"),conflict.field("c"),"Only the route matches; raw conflict remains a business rejection");
   var saved=ProductionOrderArchive.encode(device.journal.load());device.reload();assertEquals(saved,ProductionOrderArchive.encode(device.journal.load()));device.rebind("new-workshop");
   var historicalRig=new Rig(device,"new-workshop",selected);historicalRig.previousReply=original;var historical=historicalRig.finish(false);var existing=(DataValue.RecordValue)historical.field("r");assertEquals(text("existing"),existing.field("code"));assertEquals(text("historical_route_mismatch"),historical.field("c"));assertEquals(text("new-workshop"),historical.field("e"));assertEquals(text("new-workshop"),historical.field("a"));assertEquals(text("workshop"),historical.field("o"));assertEquals(text("workshop"),existing.field("address"));assertEquals(historicalRig.actualReplies.getLast(),existing);
   var queryRig=new Rig(device,"new-workshop",selected,true);var query=queryRig.finish(false);assertEquals(text("ok"),query.field("code"));assertEquals(text("waiting"),query.field("phase"));assertEquals(text("workshop"),query.field("address"));assertEquals(1,queryRig.commands.size());assertEquals(text("order"),queryRig.commands.getFirst().field("operation"));
   assertEquals(1,device.preparations);assertEquals(1,device.dispatches);assertEquals(1,device.journal.load().orders().size());
  }
 }
 @Test void queuedGrantChangeAndOverframeTransportKeepTheirActualErrorsWithoutBlindRetry()throws Exception{
  try(var device=new Device()){device.afterSubmit=()->device.rebind("changed-before-dispatch");var rig=new Rig(device,"workshop",items("before"));var result=rig.finish(false);assertEquals(text("accepted"),((DataValue.RecordValue)result.field("r")).field("code"));var order=device.journal.load().orders().values().iterator().next();assertEquals(Phase.FAILED,order.phase());assertEquals("grant_changed",order.diagnostic());assertEquals(0,device.dispatches);assertEquals(text("workshop"),result.field("o"));}
  try(var device=new Device()){var rig=new Rig(device,"workshop",items("x".repeat(500)));var result=rig.finish(false);assertEquals(text("transport_error"),result.field("c"));assertTrue(EditorValueFormat.format(result.field("r")).contains("payload_too_large"));assertTrue(rig.maxIncrementalPacketBytes>4096&&rig.maxIncrementalPacketBytes<65536);assertEquals(1,rig.commands.size());assertEquals(0,device.preparations);assertEquals(0,device.dispatches);for(int n=0;n<60;n++)rig.step(false);assertEquals(1,rig.commands.size());}
 }
 @Test void admittedPausedPeerIsAnOrdinaryPersistedFaultNotAnAutomaticRetry()throws Exception{
  try(var device=new Device()){
   var rig=new Rig(device,"workshop",items("before"));rig.h[0].setPaused(false,LIMITS,true);
   for(int n=0;n<100;n++){rig.h[0].tick(LIMITS,true,Map.of("x0",rig.status(0,"x0",2,"x0"),"x1",rig.status(0,"x1",1,"x0")));rig.h[2].tick(LIMITS,true,Map.of("x0",rig.status(2,"x0",0,"x0"),"x1",rig.status(2,"x1",3,"x0")));if(rig.h[2].flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.WAITING_READ)break;}
   assertEquals(FlowRuntime.Status.WAITING_READ,rig.h[2].flowSnapshot().orElseThrow().state().status());rig.h[0].setPaused(true,LIMITS,true);NativeLibraryStartupTest.network(rig.h[0],"x0",LIMITS,rig.h[2],"x0",LIMITS).rejectPaused(NativeLibraryStartupTest.pin(rig.h[2],"x0"));var state=rig.h[2].flowSnapshot().orElseThrow().state();assertEquals(FlowRuntime.Status.FAILED,state.status());assertEquals(ComputationException.Code.XBUS_PEER_PAUSED,state.diagnostic().orElseThrow().code());var saved=rig.h[2].write();for(int n=0;n<50;n++)rig.h[2].tick(LIMITS,true);assertEquals(saved,rig.h[2].write());var cold=new ComputerBlockHost(()->{});cold.read(saved);cold.tick(LIMITS,true);assertTrue(cold.loaded());assertEquals(saved,cold.write());assertEquals(0,device.preparations);assertEquals(0,device.dispatches);assertEquals(DataValue.NullValue.INSTANCE,TerminalStatusView.ofFlowOwner(Optional.empty(),rig.h[0].flowSnapshot()).field("output"));
  }
 }

 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={" "," bad ","bad\nroute"})
 void matchingCanonicalInvalidStatusMustNotReachSubmit(String invalid)throws Exception{
  try(var device=new Device()){
   device.statusTransform=reply->{var fields=new LinkedHashMap<>(reply.fields());var state=new LinkedHashMap<>(((DataValue.RecordValue)fields.get("state")).fields());state.put("address",text(invalid));fields.put("state",new DataValue.RecordValue(state));return new DataValue.RecordValue(fields);};
   var rig=new Rig(device,invalid,items("before"));var result=rig.finish(false);
   assertEquals(rig.actualReplies.getLast(),result.field("r"),"Original device reply is never rewritten");
   assertEquals(1,rig.commands.size(),"Malformed route and matching expectation must stop after status");assertEquals(0,device.preparations);assertEquals(0,device.dispatches);assertTrue(device.journal.load().orders().isEmpty());
  }
 }
 @Test void completedNativeExchangeNeedsExplicitResetBeforeReinstall()throws Exception{
  try(var device=new Device()){
   var rig=new Rig(device,"workshop",items("before"));rig.finish(false);
   var before=rig.h[0].flowSnapshot().orElseThrow();assertEquals(FlowRuntime.Status.COMPLETE,before.state().status());
   System.out.println("NATIVE_REINSTALL_BOUNDARY complete="+before.state().status()+" receipts="+before.receipts().size()+" tasks="+before.tasks().contexts().size());
   var doc=RoutedProductionDocuments.published("a");var body=doc.flow().orElseThrow();
   var saved=rig.h[0].write();var busy=assertThrows(ComputationException.class,()->rig.h[0].installFlow(body.program(doc.archive()),rig.initial,LIMITS,true));
   assertEquals(ComputationException.Code.BUSY,busy.code());assertEquals(saved,rig.h[0].write(),"Refused install cannot retire history implicitly");
   rig.h[0].setPaused(true,LIMITS,true);rig.h[0].reset(LIMITS,true);
   assertTrue(rig.h[0].flowSnapshot().orElseThrow().receipts().isEmpty(),"Public reset retires the completed exchanges");
   var changed=new LinkedHashMap<>(rig.initial.fields());changed.put("expected_address",text("new-workshop"));
   rig.h[0].installFlow(body.program(doc.archive()),new DataValue.RecordValue(changed),LIMITS,true);
   assertTrue(rig.h[0].flowSnapshot().orElseThrow().state().paused());assertEquals(new DataValue.RecordValue(changed),rig.h[0].flowSnapshot().orElseThrow().initialInputs());
   assertEquals(1,device.preparations);assertEquals(1,device.dispatches);
  }
 }


 @Test void sixPublicOwnersMakeProgressWithWorkOneAndCompleteFullOwnerAccounting()throws Exception{
  try(var device=new Device()){var rig=new Rig(device,"workshop",items("budget-one"),false,1);var result=rig.finish(false);assertEquals(text("checked_reply"),result.field("c"));assertEquals(1,device.preparations);assertEquals(1,device.dispatches);assertTrue(rig.nextPreflights[5]>0&&rig.nextPreflights[5]<=65536);for(long peak:rig.peaks)assertTrue(peak>0&&peak<=65536);}
 }
}
