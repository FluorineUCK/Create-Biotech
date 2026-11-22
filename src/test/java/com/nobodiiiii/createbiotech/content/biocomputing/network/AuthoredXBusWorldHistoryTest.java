package com.nobodiiiii.createbiotech.content.biocomputing.network;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
class AuthoredXBusWorldHistoryTest {
 @Test void publicAAuthoredWaitingInstructionIsVisibleToProductionPinObservation() throws Exception {
  var l=NativeLibraryOwnersTest.limits("cleric",5);
  ProgramDocument d;try(var in=getClass().getResourceAsStream("/assets/create_biotech/biocomputing/examples/library_read_a.bcp")){d=ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim()),l.archive());}
  var h=new ComputerBlockHost(()->{});var body=d.flow().orElseThrow();h.installFlow(body.program(d.archive()),body.initial(),l,true);h.setPaused(false,l,true);
  for(int i=0;i<100&&!FlowPinClaims.waiting(h.flowSnapshot().orElseThrow().state());i++)h.tick(l,true);
  var saved=h.flowSnapshot().orElseThrow();assertEquals(FlowRuntime.Status.WAITING_WRITE,saved.state().status());assertTrue(saved.position(saved.state(),"x0").instruction()>=0);
  assertEquals(XBusPortState.Mode.WRITE,XBusPortState.of(saved,l.pins(),"x0").orElseThrow().mode());
 }
 static final com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits HOST=XBusCableRouteTest.HOST;
 static FlowProgram nativeProgram(boolean writer,String pin) {
  var key=new ModuleDefinition.Key("native_transfer",1);var inputs=writer?Map.<String,DataType>of("packet",XBusCableRouteTest.PACKET):Map.<String,DataType>of();
  var outputs=writer?Map.<String,DataType>of():Map.<String,DataType>of("packet",XBusCableRouteTest.PACKET);var returns=writer?Map.<String,String>of():Map.of("packet","packet");
  String source=writer?"XBUS.WRITE "+pin+" packet":"REG packet OUTPUT packet\nXBUS.READ packet "+pin;
  return new FlowProgram(inputs,List.of(new FlowProgram.Invoke("call",key,new FlowCallBindings(writer?Map.of("packet","packet"):Map.of(),returns)),new FlowProgram.Procedure("native",key,inputs,outputs,returns,FlowBlockRules.NONE,Optional.of(new com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource(source))),new FlowProgram.EndProcedure("end"))).withAssembly();
 }
 static void installNative(XBusCableRouteTest.Peer p,boolean writer,String pin) {
  p.host.reset(HOST,true);p.host.installFlow(nativeProgram(writer,pin),writer?XBusCableRouteTest.INPUTS:XBusCableRouteTest.EMPTY,HOST,true);p.host.setPaused(false,HOST,true);
  for(int i=0;i<40&&!FlowPinClaims.waiting(p.host.flowSnapshot().orElseThrow().state());i++)p.host.tick(HOST,true);
  assertTrue(FlowPinClaims.waiting(p.host.flowSnapshot().orElseThrow().state()));
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
 void actualCableNetworkRetainsEveryAuthoredWriterIdentityAndRepairsAfterHistoryReload(boolean confirmed) {
  var f=new XBusCableNetworkTest.Fixture();var a=f.peers.get(f.a.target().position());var b=f.peers.get(f.b.target().position());var c=f.peers.get(f.c.target().position());installNative(a,true,"x0");installNative(c,true,"x0");var old=a.host.write();
  var capture=XBusCableNetwork.capture(f.links,XBusCableRouteTest.SCAN,f::resolve);assertEquals("ready",capture.code());
  if (confirmed) assertEquals(XBusExchange.Status.EXCHANGED,capture.network().orElseThrow().exchange(f.pin(f.a),f.pin(f.b)));
  else {
   a.onDirty=()->{throw new IllegalStateException("unconfirmed native publication");};
   assertThrows(IllegalStateException.class,()->capture.network().orElseThrow().exchange(f.pin(f.a),f.pin(f.b)));
   a.onDirty=()->{};
  }
  var encoded=f.history.save(new CompoundTag(),null);assertEquals(confirmed?8:7,encoded.getInt("Version"));var claim=encoded.getList("Claims",10).getCompound(0);assertTrue(claim.getCompound("Writer").contains("Instruction",Tag.TAG_INT));assertTrue(claim.getList("OtherWriters",10).getCompound(0).contains("Instruction",Tag.TAG_INT));assertFalse(claim.getCompound("Reader").contains("Instruction"));
  f.history=XBusHistorySavedData.load(encoded,null);assertEquals(encoded,f.history.save(new CompoundTag(),null));f.peers.values().forEach(p->p.history=f.history);
  var reader=b.host.write();a.host.read(old);a.host.setPaused(false,HOST,true);var repaired=XBusCableNetwork.capture(f.links,XBusCableRouteTest.SCAN,f::resolve);assertEquals(confirmed?"retired":"recovery_required",repaired.code());
  if(confirmed) assertTrue(repaired.network().isEmpty());
  else assertEquals(XBusExchange.Status.EXCHANGED,repaired.network().orElseThrow().exchange(f.pin(f.a),f.pin(f.b)));
  assertEquals(reader,b.host.write());
  b.host.setPaused(true,HOST,true);var paused=b.host.write();var retained=f.history.save(new CompoundTag(),null);f.history=XBusHistorySavedData.load(retained,null);assertTrue(f.history.observe(f.peers.values().stream().map(p->p.host.flowSnapshot().orElseThrow()).toList(),4096,65536).isEmpty());assertEquals(paused,b.host.write());
 }

 static void nativeHost(ComputerBlockHost h,boolean writer) {h.reset(HOST,true);h.installFlow(nativeProgram(writer,"x0"),writer?XBusCableRouteTest.INPUTS:XBusCableRouteTest.EMPTY,HOST,true);h.setPaused(false,HOST,true);for(int i=0;i<40&&!FlowPinClaims.waiting(h.flowSnapshot().orElseThrow().state());i++)h.tick(HOST,true);assertTrue(FlowPinClaims.waiting(h.flowSnapshot().orElseThrow().state()));}
 static void validAndOpaqueTamper(CompoundTag good) {
  assertEquals(good,XBusHistorySavedData.load(good,null).save(new CompoundTag(),null));
  var locations=new ArrayList<java.util.function.Function<CompoundTag,CompoundTag>>();collectPositions(good,new ArrayList<>(),locations);
  assertFalse(locations.isEmpty());
  for(var location:locations)for(String fault:List.of("negative","long","string","extra","downgrade")) {
   var bad=good.copy();var position=location.apply(bad);
   switch(fault) {case "negative"->position.putInt("Instruction",-1);case "long"->position.putLong("Instruction",1);case "string"->position.putString("Instruction","1");case "extra"->position.putInt("Extra",0);case "downgrade"->bad.putInt("Version",6);}
   var opaque=XBusHistorySavedData.load(bad,null);assertEquals(Optional.of(XBusExchange.Status.UNAVAILABLE),opaque.observe(List.of(),100,100));assertEquals(bad,opaque.save(new CompoundTag(),null),fault);
  }
 }
 static void collectPositions(Tag tag,List<Object> path,List<java.util.function.Function<CompoundTag,CompoundTag>> locations) {
  if(tag instanceof CompoundTag c) {
   if(c.contains("Instruction")) {var keys=List.copyOf(path);locations.add(root->{Tag node=root;for(Object key:keys)node=key instanceof String name?((CompoundTag)node).get(name):((ListTag)node).get((Integer)key);return (CompoundTag)node;});}
   for(String key:c.getAllKeys()){var next=new ArrayList<>(path);next.add(key);collectPositions(c.get(key),next,locations);}
  } else if(tag instanceof ListTag list)for(int i=0;i<list.size();i++){var next=new ArrayList<>(path);next.add(i);collectPositions(list.get(i),next,locations);}
 }
 @Test void authoredOccupancyAndCancellationKeepEveryIdentityAndRejectMalformedInstructionFields() {
  var f=new XBusOccupancyHistoryTest.Fixture();nativeHost(f.hosts.getFirst(),true);nativeHost(f.hosts.get(2),true);
  assertEquals(Optional.of(f.pin(0)),f.network().selectWriter(f.pins()).writer());var held=f.disk();assertEquals(7,held.getInt("Version"));var occupancy=held.getList("Occupancies",10).getCompound(0);assertTrue(occupancy.getCompound("Writer").contains("Instruction",Tag.TAG_INT));assertEquals(2,occupancy.getList("Writers",10).size());validAndOpaqueTamper(held);
  f.history=XBusHistorySavedData.load(held,null);f.hosts.getFirst().reset(HOST,true);assertEquals(XBusExchange.Status.RETIRED,f.network().settleRetired());var cancelled=f.disk();assertEquals(7,cancelled.getInt("Version"));var proof=cancelled.getList("Cancellations",10).getCompound(0);assertTrue(proof.getCompound("Holder").contains("Instruction",Tag.TAG_INT));assertEquals(2,proof.getList("Writers",10).size());validAndOpaqueTamper(cancelled);
  assertEquals(FlowRuntime.Status.FAILED,f.saved().get(2).state().status());assertTrue(f.saved().get(2).receipts().isEmpty());
 }
 @Test void actualAuthoredTaskReaderAndMixedOtherWriterRoundTripWithoutLosingSlotsOrPc() {
  var l=NativeLibraryOwnersTest.limits("cleric",5);var reader=new ComputerBlockHost(()->{});reader.installFlow(nativeProgram(false,"x0"),XBusCableRouteTest.EMPTY,l,true);reader.setPaused(false,l,true);for(int i=0;i<20&&!FlowPinClaims.waiting(reader.flowSnapshot().orElseThrow().state());i++)reader.tick(l,true);
  var child=reader.startFlowTask(new ModuleDefinition.Key("native_transfer",1),XBusCableRouteTest.EMPTY,l,true);for(int i=0;i<30&&reader.flowSnapshot().orElseThrow().pinClaims().entries().size()<2;i++)reader.tick(l,true);var r=reader.flowSnapshot().orElseThrow();assertEquals(2,r.pinClaims().entries().size());
  var f=new XBusOccupancyHistoryTest.Fixture();nativeHost(f.hosts.getFirst(),true);var w=f.saved().getFirst();var old=f.saved().get(2);var receipt=new XBusReceipt(w.position(w.state(),"x0"),r.position(r.state(),"x0"),(DataValue.RecordValue)XBusCableRouteTest.INPUTS.field("packet"),List.of(old.position(old.state(),"x0")),List.of(r.position(r.context(child).orElseThrow(),"x0")));
  var history=new XBusHistorySavedData();assertTrue(history.reserve(receipt,List.of(w,old,r),100,100).rejection().isEmpty());var disk=history.save(new CompoundTag(),null);var claim=disk.getList("Claims",10).getCompound(0);var task=claim.getList("OtherReaders",10).getCompound(0);assertEquals(1,task.getInt("Slot"));assertTrue(task.contains("Instruction",Tag.TAG_INT));assertFalse(claim.getList("OtherWriters",10).getCompound(0).contains("Instruction"));validAndOpaqueTamper(disk);
  var replay=disk.copy();var conflicting=replay.getList("Claims",10).getCompound(0).copy();var position=conflicting.getCompound("Writer");position.putInt("Instruction",position.getInt("Instruction")+1);replay.getList("Claims",10).add(conflicting);var opaque=XBusHistorySavedData.load(replay,null);assertEquals(Optional.of(XBusExchange.Status.UNAVAILABLE),opaque.observe(List.of(),100,100));assertEquals(replay,opaque.save(new CompoundTag(),null));
  var pair=new XBusHistorySavedData();assertTrue(pair.reserve(w,"x0",r,"x0",100,100).rejection().isEmpty());assertTrue(pair.save(new CompoundTag(),null).getList("Claims",10).getCompound(0).getCompound("Writer").contains("Instruction",Tag.TAG_INT));
 }

}
