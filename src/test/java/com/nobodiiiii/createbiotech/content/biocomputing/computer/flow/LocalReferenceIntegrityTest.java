package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
class LocalReferenceIntegrityTest {
 static final ArchiveLimits ARCHIVE=SoftwareAnyAbiTest.ARCHIVE;
 static DataValue.RecordValue nested(){return new DataValue.RecordValue(Map.of("hidden",new DataValue.ListValue(List.of(LocalReferenceBoundaryTest.handle()))));}
 @Test void directXBusPayloadAndReceiptCannotExportNestedAuthority(){
  var payload=nested();var p=new FlowProgram(Map.of("message",new DataType.RecordType(Map.of("hidden",DataType.Scalar.ANY))),List.of(new FlowProgram.Write("send","x0","message",Optional.empty())));
  var state=new FlowRuntime.Checkpoint(UUID.randomUUID(),UUID.randomUUID(),0,FlowRuntime.Status.WAITING_WRITE,new DataValue.RecordValue(Map.of("message",payload)),0,false);
  assertThrows(ComputationException.class,()->FlowXBus.payload(p,state));
  var writer=new XBusReceipt.Position(state.computer(),state.flow(),0,"x0");var reader=new XBusReceipt.Position(UUID.randomUUID(),UUID.randomUUID(),0,"x0");
  assertThrows(ComputationException.class,()->new XBusReceipt(writer,reader,payload));
 }
 @Test void serialInputsAndPublicTransactionalMemoryRejectNestedAuthority(){
  var payload=nested();var ref=LocalReferenceBoundaryTest.handle();var external=new FlowProgram(Map.of("ref",ref.type()),List.of(),LocalCellReferenceTest.source().expressions(),FlowProgram.Execution.REGISTER_V1,FlowProgram.Compilation.SOFTWARE_V1);assertThrows(ComputationException.class,()->external.compile(LocalCellReferenceTest.LIMITS.hardware()));assertThrows(ComputationException.class,()->new FlowRuntime(UUID.randomUUID(),UUID.randomUUID(),external,new DataValue.RecordValue(Map.of("ref",ref)),LocalCellReferenceTest.LIMITS.hardware()));var limits=new SerialRecordCodec.Limits(1000000,1000000,ARCHIVE);
  assertAll(()->assertThrows(ComputationException.class,()->SerialEncoding.begin(payload,limits)),()->assertThrows(ComputationException.class,()->SerialEncoding.begin(payload,limits,units->{})),()->assertThrows(RuntimeException.class,()->SerialRecordCodec.encode(payload,limits)));
  var owner=UUID.randomUUID();var memory=RegisterMemory.initial(Map.of("cell",new ProgramDefinition.Cell(DataType.Scalar.ANY,DataValue.NullValue.INSTANCE)));
  assertAll(()->assertThrows(ComputationException.class,()->memory.publicStore(owner,0,payload)),()->assertThrows(ComputationException.class,()->memory.begin(owner).write(owner,0,payload)));
  assertEquals(DataValue.NullValue.INSTANCE,memory.publicLoad(owner,0));
 }
 @ParameterizedTest @ValueSource(strings={"computer","generation","image","context","cell","target","writable"})
 void privateCheckpointCorruptionNeverReconstitutesValidAuthority(String key){
  var host=LocalReferenceContractTest.run(LocalCellReferenceTest.LIMITS);var saved=host.flowSnapshot().orElseThrow();var tag=FlowBlockNbtCodec.encode(saved,ARCHIVE);int changes=0;
  for(Tag value:tag.getCompound("Data").getList("Values",Tag.TAG_COMPOUND)){
   var row=(CompoundTag)value;if(!row.getString("Kind").equals("local_ref"))continue;
   var descriptor=(DataValue.RecordValue)ValueNbtCodec.decode(row.getCompound("Descriptor"),ARCHIVE);
   var fields=new LinkedHashMap<>(descriptor.fields());
   DataValue bad=switch(key){case "computer","context"->new DataValue.TextValue(UUID.randomUUID().toString());case "generation"->new DataValue.IntegerValue(99);case "image"->new DataValue.TextValue("f".repeat(64));case "cell"->new DataValue.IntegerValue(99);case "target"->new DataValue.TextValue("TEXT");case "writable"->new DataValue.BooleanValue(!((DataValue.BooleanValue)fields.get(key)).value());default->throw new AssertionError();};
   fields.put(key,bad);row.put("Descriptor",ValueNbtCodec.encode(new DataValue.RecordValue(fields),ARCHIVE));changes++;
  }
  assertTrue(changes>0);assertThrows(RuntimeException.class,()->{var decoded=FlowBlockNbtCodec.decode(tag,ARCHIVE);FlowRuntime.restore(decoded.program(),decoded.state(),LocalCellReferenceTest.LIMITS.hardware());});
 }
 @Test void privateSchemaCannotBeDowngradedAndDescriptorShapeIsInert(){
  var saved=LocalReferenceContractTest.run(LocalCellReferenceTest.LIMITS).flowSnapshot().orElseThrow();var tag=FlowBlockNbtCodec.encode(saved,ARCHIVE);tag.putInt(FlowBlockNbtCodec.MARKER,37);
  assertThrows(ArchiveException.class,()->FlowBlockNbtCodec.decode(tag,ARCHIVE));
  var ref=LocalReferenceBoundaryTest.handle();var roundtrip=ValueNbtCodec.decode(ValueNbtCodec.encode(ref.descriptor(),ARCHIVE),ARCHIVE);assertEquals(ref.descriptor(),roundtrip);assertFalse(ref.type().accepts(roundtrip));assertEquals(0,roundtrip.localReferences());
 }
 @Test void standaloneFlowCodecWithoutBankCannotReconstituteReferenceAuthority(){var saved=LocalReferenceContractTest.run(LocalCellReferenceTest.LIMITS).flowSnapshot().orElseThrow();assertThrows(RuntimeException.class,()->FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(saved.program(),saved.state()),ARCHIVE));}
 @Test void nominalPermissionAndOwnerChecksPrecedeAnyMemoryMutation(){
  var memory=RegisterMemory.initial(Map.of("cell",new ProgramDefinition.Cell(DataType.Scalar.INTEGER,new DataValue.IntegerValue(7))));var computer=UUID.randomUUID();var context=UUID.randomUUID();var image="a".repeat(64);var regs=new HashMap<Integer,DataValue>();
  new RegisterReference(RegisterReference.Kind.ADDRESS,0,-1,0).execute(regs,memory,computer,3,image,context);var ref=(DataValue.LocalReference)regs.get(0);regs.put(1,new DataValue.IntegerValue(42));regs.put(2,ref.readonly());
  var write=new RegisterReference(RegisterReference.Kind.WRITE,2,1,-1);assertThrows(ComputationException.class,()->write.execute(regs,memory,computer,3,image,context));
  for(int i=0;i<4;i++){final int fault=i;assertThrows(ComputationException.class,()->RegisterReference.validate(ref,fault==0?UUID.randomUUID():computer,fault==1?4:3,fault==2?"b".repeat(64):image,fault==3?UUID.randomUUID():context,memory));}
  regs.put(2,ref.descriptor());assertThrows(ComputationException.class,()->write.execute(regs,memory,computer,3,image,context));assertEquals(new DataValue.IntegerValue(7),memory.publicLoad(context,0));
  assertThrows(ComputationException.class,()->new RegisterReference(RegisterReference.Kind.ADDRESS,0,-1,0).execute(regs,memory.begin(context),computer,3,image,context));
 }
}
