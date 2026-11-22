package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
class MixedAuthoredOwnerCodecTest {
 static final com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits LIMITS=com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture.withIsa1a(new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(SerialFlowHostTest.LIMITS.hardware(),SerialFlowHostTest.LIMITS.archive(),SerialFlowHostTest.LIMITS.mailbox(),SerialFlowHostTest.LIMITS.pins(),SerialFlowHostTest.LIMITS.serial(),4));
 static final DataType.RecordType TYPE=new DataType.RecordType(Map.of("n",DataType.Scalar.INTEGER));
 static final DataValue.RecordValue VALUE=new DataValue.RecordValue(Map.of("n",new DataValue.IntegerValue(42)));
 static DataValue.RecordValue empty(){return new DataValue.RecordValue(Map.of());}
 static FlowProgram program(boolean writer,boolean authored,boolean legacy){
  Map<String,DataType> inputs=writer?Map.of("payload",TYPE):Map.of();
  var ordinary=new FlowProgram(inputs,writer?List.of(new FlowProgram.Write("write","x0","payload")):List.of(new FlowProgram.Read("read","x0","received",TYPE)));
  if(!authored)return legacy?ordinary:ordinary.withAssembly();
  var key=new ModuleDefinition.Key("transfer",1);var outputs=writer?Map.<String,DataType>of():Map.<String,DataType>of("received",TYPE);var bindings=writer?Map.<String,String>of():Map.of("received","received");
  return new FlowProgram(inputs,List.of(new FlowProgram.Invoke("call",key,new FlowCallBindings(writer?Map.of("payload","payload"):Map.of(),bindings)),new FlowProgram.Procedure("definition",key,inputs,outputs,bindings,FlowBlockRules.NONE,Optional.of(new AssemblySource(writer?"XBUS.WRITE x0 payload":"REG received OUTPUT received\nXBUS.READ received x0"))),new FlowProgram.EndProcedure("end"))).withAssembly();
 }
 static ComputerBlockHost install(FlowProgram p){var h=new ComputerBlockHost(()->{});h.installFlow(p,p.inputs().isEmpty()?empty():new DataValue.RecordValue(Map.of("payload",VALUE)),LIMITS,true);h.setPaused(false,LIMITS,true);for(int i=0;i<30&&!FlowPinClaims.waiting(h.flowSnapshot().orElseThrow().state());i++)h.tick(LIMITS,true);assertTrue(FlowPinClaims.waiting(h.flowSnapshot().orElseThrow().state()));return h;}
 static ComputerBlockHost restore(net.minecraft.nbt.Tag raw){var h=new ComputerBlockHost(()->{});h.read(raw);h.setPaused(false,LIMITS,true);assertTrue(h.loaded(),()->h.diagnostic().toString());return h;}
 static XBusExchange.Status exchange(ComputerBlockHost a,ComputerBlockHost b){return a.exchange("x0",LIMITS,true,b,"x0",LIMITS,true);}
 static final ModuleDefinition.Key TASK=new ModuleDefinition.Key("task_transfer",1);
 static ComputerBlockHost taskPeer(boolean writer,boolean legacy){var base=program(writer,false,legacy);var steps=new ArrayList<>(base.steps());var results=writer?Map.<String,DataType>of():Map.<String,DataType>of("received",TYPE);steps.add(new FlowProgram.Procedure("task",TASK,base.inputs(),results,writer?Map.of():Map.of("received","received")));steps.add(writer?new FlowProgram.Write("task_write","x0","payload"):new FlowProgram.Read("task_read","x0","received",TYPE));steps.add(new FlowProgram.EndProcedure("task_end"));var p=new FlowProgram(base.inputs(),steps);if(!legacy)p=p.withAssembly();var h=install(p);h.startFlowTask(TASK,writer?new DataValue.RecordValue(Map.of("payload",VALUE)):empty(),LIMITS,true);for(int i=0;i<30&&h.flowSnapshot().orElseThrow().pinClaims().entries().size()<2;i++)h.tick(LIMITS,true);assertEquals(2,h.flowSnapshot().orElseThrow().pinClaims().entries().size());return h;}
 static ComputerBlockHost.XBusBinding binding(ComputerBlockHost h){return new ComputerBlockHost.XBusBinding(h,"x0",LIMITS,true);}
 @Test void mixedGroupReceiptsKeepLegacyAndRegisterTaskStateAndAllParticipantMetadata(){for(boolean legacy:List.of(false,true)){
  var writer=install(program(true,true,false));var reader=taskPeer(false,legacy);var oldRead=reader.write();var before=reader.flowSnapshot().orElseThrow();
  assertEquals(XBusExchange.Status.EXCHANGED,ComputerBlockHost.exchangeGroup(List.of(binding(writer),binding(reader)),binding(writer)));var expectedR=reader.flowSnapshot().orElseThrow();var expectedW=writer.flowSnapshot().orElseThrow();assertTrue(expectedR.receipts().getFirst().grouped());assertTrue(expectedR.receipts().getFirst().positions().stream().anyMatch(p->p.slot()>0));assertEquals(before.program(),expectedR.program());
  reader=restore(reader.write());writer=restore(writer.write());assertEquals(expectedR,reader.flowSnapshot().orElseThrow());assertEquals(expectedW,writer.flowSnapshot().orElseThrow());reader=restore(oldRead);assertEquals(XBusExchange.Status.EXCHANGED,ComputerBlockHost.exchangeGroup(List.of(binding(writer),binding(reader)),binding(writer)));assertEquals(expectedR,reader.flowSnapshot().orElseThrow());assertNotEquals(XBusExchange.Status.EXCHANGED,exchange(writer,reader));
 }}
 @Test void mixedCancellationProofKeepsRemoteAuthoredPcAndOldLocalTaskIdentities(){for(boolean legacy:List.of(false,true)){
  var holder=install(program(true,true,false));var local=taskPeer(true,legacy);var held=holder.flowSnapshot().orElseThrow();var holderPosition=held.position(held.state(),"x0");var old=local.flowSnapshot().orElseThrow();var positions=new HashSet<XBusReceipt.Position>();positions.add(holderPosition);for(var claim:old.pinClaims().entries())positions.add(old.position(old.context(claim.flow()).orElseThrow(),"x0"));
  holder.reset(LIMITS,true);var proof=FlowControl.Peer.of(holder.flowSnapshot().orElseThrow());var witness=new XBusCancellation(holderPosition,positions,proof);
  assertEquals(XBusExchange.Status.RETIRED,ComputerBlockHost.prepareCancellation(List.of(binding(holder),binding(local)),witness).commit());var expected=local.flowSnapshot().orElseThrow();assertEquals(List.of(witness),expected.cancellations());assertEquals(FlowRuntime.Status.FAILED,expected.state().status());for(var task:expected.tasks().contexts())assertEquals(FlowRuntime.Status.FAILED,task.status());assertTrue(expected.receipts().isEmpty());
  local=restore(local.write());assertEquals(expected,local.flowSnapshot().orElseThrow());assertEquals(old.program(),local.flowSnapshot().orElseThrow().program());assertEquals(witness,local.flowSnapshot().orElseThrow().cancellations().getFirst());assertEquals(37,((CompoundTag)local.write()).getInt(FlowBlockNbtCodec.MARKER));
 }}
 @Test void ordinaryAndLegacyLocalOwnersRetainAuthoredRemotePcInBothDirectionsAndRepairEitherOlderSide(){
  for(boolean legacy:List.of(false,true))for(boolean authoredWriter:List.of(false,true))for(boolean oldWriter:List.of(false,true)){
   var writer=install(program(true,authoredWriter,false));var reader=install(program(false,!authoredWriter,legacy));if(!authoredWriter)writer=install(program(true,false,legacy));
   var beforeW=writer.write();var beforeR=reader.write();var old=authoredWriter?reader:writer;var oldBefore=old.flowSnapshot().orElseThrow();var oldRaw=(CompoundTag)old.write();int oldVersion=oldRaw.getInt(FlowBlockNbtCodec.MARKER);assertTrue(oldVersion<37);
   assertEquals(XBusExchange.Status.EXCHANGED,exchange(writer,reader));var afterW=writer.flowSnapshot().orElseThrow();var afterR=reader.flowSnapshot().orElseThrow();
   var afterOld=(authoredWriter?reader:writer).flowSnapshot().orElseThrow();assertEquals(oldBefore.program(),afterOld.program());assertEquals(oldBefore.state().assembly().map(RegisterState::image),afterOld.state().assembly().map(RegisterState::image));assertEquals(oldBefore.program().usage(LIMITS.hardware()),afterOld.program().usage(LIMITS.hardware()));
   var receipt=afterOld.receipts().getFirst();assertTrue(receipt.positions().stream().anyMatch(p->p.instruction()>=0));
   writer=restore(writer.write());reader=restore(reader.write());assertEquals(afterW,writer.flowSnapshot().orElseThrow());assertEquals(afterR,reader.flowSnapshot().orElseThrow());assertEquals(37,((CompoundTag)(authoredWriter?reader:writer).write()).getInt(FlowBlockNbtCodec.MARKER));
   assertEquals(oldVersion,((CompoundTag)restore(oldRaw).write()).getInt(FlowBlockNbtCodec.MARKER));
   writer=restore(oldWriter?beforeW:writer.write());reader=restore(oldWriter?reader.write():beforeR);assertEquals(XBusExchange.Status.EXCHANGED,exchange(writer,reader));assertEquals(afterW,writer.flowSnapshot().orElseThrow());assertEquals(afterR,reader.flowSnapshot().orElseThrow());assertNotEquals(XBusExchange.Status.EXCHANGED,exchange(writer,reader));
  }
 }
}
