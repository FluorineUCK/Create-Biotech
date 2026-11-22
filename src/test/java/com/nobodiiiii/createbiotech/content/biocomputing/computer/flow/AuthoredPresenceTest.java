package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class AuthoredPresenceTest {
 static final ResourceLimits LIMITS=new ResourceLimits(256,65536,1);
 static FlowProgram program(){var key=new ModuleDefinition.Key("presence",1);return new FlowProgram(Map.of("container",DataType.Scalar.ANY,"key",DataType.Scalar.ANY),List.of(new FlowProgram.Invoke("call",key,new FlowCallBindings(Map.of("container","container","key","key"),Map.of("result","result"))),new FlowProgram.Procedure("definition",key,Map.of("container",DataType.Scalar.ANY,"key",DataType.Scalar.ANY),Map.of("result",DataType.Scalar.BOOLEAN),Map.of("result","found"),FlowBlockRules.NONE,Optional.of(new AssemblySource("REG found BOOLEAN\nHAS.FIELD found container key"))),new FlowProgram.EndProcedure("end"))).withAssembly();}
 static FlowRuntime start(DataValue container,DataValue key){return new FlowRuntime(UUID.randomUUID(),UUID.randomUUID(),program(),new DataValue.RecordValue(Map.of("container",container,"key",key)),LIMITS);}
 @Test void recordsMapsMissingEmptyAndPresentNullKeepDistinctSemanticsAndResumeEveryProgress(){
  String longKey="共同前缀😀".repeat(100);
  for(boolean map:List.of(false,true))for(String key:List.of("",longKey,longKey+"missing")) {
   var fields=new LinkedHashMap<String,DataValue>();fields.put(longKey+"different",DataValue.NullValue.INSTANCE);fields.put(longKey,DataValue.NullValue.INSTANCE);fields.put("",DataValue.NullValue.INSTANCE);
   var r=start(map?new DataValue.MapValue(fields):new DataValue.RecordValue(fields),new DataValue.TextValue(key));long work=0;boolean progressed=false;
   while(r.checkpoint().status()!=FlowRuntime.Status.COMPLETE){assertTrue(work++<10000);r.advance(1);var before=r.checkpoint();assertNotEquals(FlowRuntime.Status.FAILED,before.status(),before.diagnostic().toString());
    progressed|=before.assembly().orElseThrow().comparison().isPresent();
    var encoded=FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(program(),before),SerialFlowHostTest.ARCHIVE);var cold=FlowNbtCodec.decode(encoded,SerialFlowHostTest.ARCHIVE);r=FlowRuntime.restore(cold.program(),cold.state(),LIMITS);r.load();assertEquals(before,r.checkpoint());
   }
   assertTrue(progressed);assertEquals(new DataValue.BooleanValue(!key.endsWith("missing")),r.checkpoint().locals().field("result"));assertTrue(work>key.length()||key.isEmpty());
  }
  for(var empty:List.<DataValue>of(new DataValue.RecordValue(Map.of()),new DataValue.MapValue(Map.of()))){var r=start(empty,new DataValue.TextValue(""));while(r.checkpoint().status()!=FlowRuntime.Status.COMPLETE)r.advance(1);assertEquals(new DataValue.BooleanValue(false),r.checkpoint().locals().field("result"));}
 }
 @Test void wrongRuntimeContainerOrKeyIsTypedFailureNotFalse(){for(var args:List.of(List.<DataValue>of(new DataValue.IntegerValue(1),new DataValue.TextValue("x")),List.<DataValue>of(new DataValue.RecordValue(Map.of()),DataValue.NullValue.INSTANCE))){var r=start(args.get(0),args.get(1));for(int i=0;i<20&&r.checkpoint().status()!=FlowRuntime.Status.FAILED;i++)r.advance(1);assertEquals(ComputationException.Code.TYPE_MISMATCH,r.checkpoint().diagnostic().orElseThrow().code());}}
 @Test void corruptedPresenceCursorAndPaidCountRejectWithoutPublishingPartialOutput(){
  var r=start(new DataValue.RecordValue(Map.of("abcdef",DataValue.NullValue.INSTANCE)),new DataValue.TextValue("abcdef"));while(r.checkpoint().assembly().orElseThrow().comparison().isEmpty())r.advance(1);var before=r.checkpoint();
  var encoded=FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(program(),before),SerialFlowHostTest.ARCHIVE);var root=(DataValue.RecordValue)ValueNbtCodec.decode(encoded,SerialFlowHostTest.ARCHIVE);var saved=(DataValue.RecordValue)root.field("state");var a=(DataValue.RecordValue)saved.field("assembly");var c=(DataValue.RecordValue)a.field("comparison");
  for(var changed:List.of(patch(c,"paid",new DataValue.IntegerValue(Long.MAX_VALUE)),patch(c,"progress",new DataValue.RecordValue(Map.of("index",new DataValue.IntegerValue(9),"offset",new DataValue.IntegerValue(0),"result",new DataValue.IntegerValue(1)))))){
   var altered=patch(root,"state",patch(saved,"assembly",patch(a,"comparison",changed)));var raw=ValueNbtCodec.encode(altered,SerialFlowHostTest.ARCHIVE);
   assertThrows(RuntimeException.class,()->{var d=FlowNbtCodec.decode(raw,SerialFlowHostTest.ARCHIVE);FlowRuntime.restore(d.program(),d.state(),LIMITS);});assertFalse(before.locals().fields().containsKey("found"));
  }
 }
 @Test void pendingPresenceRequiresTemporaryAdmissionBeforeAnyCandidatePublication(){
  var r=start(new DataValue.RecordValue(Map.of("abcdef",DataValue.NullValue.INSTANCE)),new DataValue.TextValue("abcdef"));var image=program().compile(LIMITS).assembly().orElseThrow();
  while(!(image.code().get(r.checkpoint().assembly().orElseThrow().pc()).instruction() instanceof RegisterProgram.HasField))r.advance(1);
  var before=r.checkpoint().assembly().orElseThrow();assertThrows(ComputationException.class,()->image.step(before,Map.of(),Map.of(),r.checkpoint().work()+1,extra->{assertTrue(extra>0);throw new ComputationException(ComputationException.Code.MEMORY_LIMIT,"test","No temporary RAM");}));assertEquals(before,r.checkpoint().assembly().orElseThrow());assertTrue(before.comparison().isEmpty());
 }
 @Test void lexicalCommentsTabsAndJsonEscapesRemainDataAndOldVersionsRejectNewSurface(){String source="REG key TEXT\nREG answer BOOLEAN\nCONST\tkey \"hello ; \\\"world\\\"\" ; comment\nVALUE.EQUALS answer key key";var image=ProcedureAssembler.assemble(new AssemblySource(source),Map.of(),Map.of("r",DataType.Scalar.BOOLEAN),Map.of("r","answer"),24000,256);assertEquals(new DataValue.TextValue("hello ; \"world\""),image.program().constants().getFirst());for(int version:List.of(23,2,3))assertThrows(IllegalArgumentException.class,()->ProcedureAssembler.assemble(new AssemblySource(version,source),Map.of(),Map.of("r",DataType.Scalar.BOOLEAN),Map.of("r","answer"),24000,256));}
 static DataValue.RecordValue patch(DataValue.RecordValue r,String k,DataValue v){var m=new LinkedHashMap<>(r.fields());m.put(k,v);return new DataValue.RecordValue(m);}
}
