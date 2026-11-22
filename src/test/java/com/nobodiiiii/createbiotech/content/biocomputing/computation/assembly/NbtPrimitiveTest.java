package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NbtPrimitiveTest {
    static final ArchiveLimits LIMITS=new ArchiveLimits(100000,1000000,1000000);
    static final DataValue.NbtValue OLD=new DataValue.NbtValue(4,new DataValue.IntegerValue(123));
    static RegisterProgram pack(int tag) {
        return new RegisterProgram(List.of(new RegisterProgram.Code(new RegisterNbt.Instruction(RegisterNbt.Kind.PACK,1,0,tag),0,"pack")),
            List.of(RegisterNbt.bodyType(tag),DataType.Scalar.NBT),List.of(),Map.of("body",0,"old",1),Map.of("body",0,"old",1),1);
    }
    static RegisterState start(RegisterProgram p,DataValue body) {return p.begin(new DataValue.RecordValue(Map.of("body",body,"old",OLD)));}
    static RegisterState cold(RegisterState state) {
        var registers=new TreeMap<Integer,DataValue>();
        state.registers().forEach((r,v)->registers.put(r,ValueNbtCodec.decode(ValueNbtCodec.encode(v,LIMITS),LIMITS)));
        var progress=state.build().map(p->{var v=p.value();return RegisterConstruction.savedNative((int)((DataValue.IntegerValue)v.field("tag")).value(),((DataValue.IntegerValue)v.field("paid")).value());});
        return new RegisterState(state.image(),state.pc(),registers,true,Optional.empty(),state.calls(),state.transaction(),true,progress);
    }
    record Done(DataValue value,long work,long peakReserve) {}
    static Done run(int tag,DataValue body,boolean reload) {
        var p=pack(tag);var state=start(p,body);long work=0;long[] peak={0};
        while(!p.complete(state)) {
            assertEquals(OLD,state.registers().get(1));
            if(reload)state=cold(state);
            p.validate(state,work,false,n->peak[0]=Math.max(peak[0],n));
            state=p.step(state,Map.of(),Map.of(),++work,n->peak[0]=Math.max(peak[0],n)).registers();
            p.validate(state,work,false);assertTrue(work<20000);
        }
        assertEquals(body,state.registers().get(0));
        return new Done(state.registers().get(1),work,peak[0]);
    }
    static DataValue.RecordValue entry(String key,DataValue value) {return new DataValue.RecordValue(Map.of("key",new DataValue.TextValue(key),"value",value));}
    @Test void allNativeTagsAndRawUtf16HaveImmutableResultsAndEveryStepColdRestore() {
        var bodies=new LinkedHashMap<Integer,DataValue>();bodies.put(0,DataValue.NullValue.INSTANCE);
        bodies.put(1,new DataValue.IntegerValue(-128));bodies.put(2,new DataValue.IntegerValue(32767));bodies.put(3,new DataValue.IntegerValue(Integer.MIN_VALUE));bodies.put(4,new DataValue.IntegerValue(Long.MAX_VALUE));
        bodies.put(5,new DataValue.IntegerValue(0x7fc01234));bodies.put(6,new DataValue.IntegerValue(0x7ff8000000001234L));
        bodies.put(8,new DataValue.TextValue("\ud800\u0000".repeat(150)));
        for(int tag:List.of(7,11,12))bodies.put(tag,new DataValue.ListValue(List.of(new DataValue.IntegerValue(1),new DataValue.IntegerValue(-1))));
        bodies.put(9,new DataValue.ListValue(List.of(OLD,OLD)));
        bodies.put(10,new DataValue.ListValue(List.of(entry("",OLD),entry("prefix".repeat(40)+"a",OLD),entry("prefix".repeat(40)+"b",new DataValue.NbtValue(8,new DataValue.TextValue("\ud800"))))));
        for(var sample:bodies.entrySet()) {
            int tag=sample.getKey();var body=sample.getValue();var normal=run(tag,body,false);var resumed=run(tag,body,true);
            assertEquals(normal.value(),resumed.value());assertEquals(normal.work(),resumed.work());
            if(tag!=10)assertEquals(new DataValue.NbtValue(tag,body),normal.value());
            else assertEquals(List.of("","prefix".repeat(40)+"a","prefix".repeat(40)+"b"),((DataValue.RecordValue)((DataValue.NbtValue)normal.value()).value()).indexedFields().stream().map(Map.Entry::getKey).toList());
            if(Set.of(7,9,11,12).contains(tag))assertEquals(4,normal.work());else if(tag!=10)assertEquals(1,normal.work());
            assertEquals(4,pack(tag).codeWords());
        }
        for(int tag:List.of(7,9,10,11,12)) {var empty=run(tag,new DataValue.ListValue(List.of()),true);assertEquals(tag==10?3:2,empty.work());}
    }
    @Test void invalidNativePayloadsNeverPublishAndDuplicatesAreMetered() {
        var bad=new LinkedHashMap<Integer,DataValue>();bad.put(1,new DataValue.IntegerValue(128));bad.put(2,new DataValue.IntegerValue(32768));bad.put(3,new DataValue.IntegerValue(1L+Integer.MAX_VALUE));bad.put(5,new DataValue.IntegerValue(1L+Integer.MAX_VALUE));
        bad.put(7,new DataValue.ListValue(List.of(new DataValue.IntegerValue(-129))));bad.put(11,new DataValue.ListValue(List.of(new DataValue.IntegerValue(Long.MAX_VALUE))));
        bad.put(9,new DataValue.ListValue(List.of(OLD,new DataValue.NbtValue(8,new DataValue.TextValue("wrong")))));
        bad.put(10,new DataValue.ListValue(List.of(entry("same".repeat(50),OLD),entry("same".repeat(50),OLD))));
        for(var sample:bad.entrySet()) {
            var p=pack(sample.getKey());var state=start(p,sample.getValue());long work=0;boolean failed=false;
            while(!p.complete(state)) {var before=state;
                try {state=p.step(state,Map.of(),Map.of(),++work,x->{}).registers();p.validate(state,work,false);}
                catch(ComputationException e) {assertEquals(ComputationException.Code.INVALID_ARGUMENT,e.code());assertEquals(OLD,before.registers().get(1));failed=true;break;}
            }assertTrue(failed);if(sample.getKey()==10)assertTrue(work>200,"key comparison must not be hidden");
        }
        var end=new DataValue.NbtValue(0,DataValue.NullValue.INSTANCE);
        for(var sample:List.of(Map.entry(9,new DataValue.ListValue(List.of(end))),Map.entry(10,new DataValue.ListValue(List.of(entry("bad",end))))))assertThrows(ComputationException.class,()->run(sample.getKey(),sample.getValue(),true));
    }
    @Test void everyConstructionPhaseReservesBeforePublishingAndRejectsForgedProgress() {
        for(int tag:List.of(7,9,10,11,12)) {
            DataValue body=new DataValue.ListValue(tag==10?List.of(entry("a",OLD),entry("b",OLD)):tag==9?List.of(OLD,OLD):List.of(new DataValue.IntegerValue(1),new DataValue.IntegerValue(2)));
            var p=pack(tag);var state=start(p,body);long work=0;
            while(!p.complete(state)) {
                var before=state;long nextWork=work+1;
                var error=assertThrows(ComputationException.class,()->p.step(before,Map.of(),Map.of(),nextWork,n->{throw new ComputationException(ComputationException.Code.MEMORY_LIMIT,"owner","full");}));
                assertEquals(ComputationException.Code.MEMORY_LIMIT,error.code());assertEquals(OLD,before.registers().get(1));
                if(state.build().isPresent()) {
                    var live=state;long paid=work;
                    var reused=new RegisterState(state.image(),state.pc(),new TreeMap<>(state.registers()),true,Optional.empty(),state.calls(),state.transaction(),true,state.build());
                    assertThrows(ComputationException.class,()->p.validate(reused,paid,false));
                    for(var forged:List.of(RegisterConstruction.savedNative(tag,work+1),RegisterConstruction.savedNative(tag==7?9:7,work),new RegisterConstruction(0))) {
                        var invalid=new RegisterState(live.image(),0,live.registers(),true,Optional.empty(),live.calls(),live.transaction(),true,Optional.of(forged));
                        assertEquals(ComputationException.Code.INVALID_CHECKPOINT,assertThrows(ComputationException.class,()->p.validate(invalid,paid,false)).code());
                    }
                    var cold=cold(state);p.validate(cold,work,false);assertEquals(state.value(),cold.value());
                }
                state=p.step(state,Map.of(),Map.of(),++work,n->{}).registers();p.validate(state,work,false);
            }
        }
    }
    static DataValue read(RegisterNbt.Kind kind,DataValue.NbtValue input,long index,DataType output) {
        var op=new RegisterNbt.Instruction(kind,2,0,kind==RegisterNbt.Kind.CHILD || kind==RegisterNbt.Kind.KEY?1:-1);
        var p=new RegisterProgram(List.of(new RegisterProgram.Code(op,0,"read")),List.of(DataType.Scalar.NBT,DataType.Scalar.INTEGER,output),List.of(),Map.of("input",0,"index",1),Map.of("input",0,"index",1,"output",2),1);
        var state=p.begin(new DataValue.RecordValue(Map.of("input",input,"index",new DataValue.IntegerValue(index))));
        var reserves=new ArrayList<Long>();var result=p.step(state,Map.of(),Map.of(),1,reserves::add).registers();p.validate(result,1,false);
        assertEquals(List.of(result.registers().get(2).storageUnits()),reserves);return result.registers().get(2);
    }
    @Test void readsAreBoundedTypedAndPreserveNativeChildTagsAndMismatchShape() {
        for(int tag:List.of(7,11,12)) {var value=new DataValue.NbtValue(tag,new DataValue.ListValue(List.of(new DataValue.IntegerValue(-1))));assertEquals(new DataValue.NbtValue(tag==7?1:tag==11?3:4,new DataValue.IntegerValue(-1)),read(RegisterNbt.Kind.CHILD,value,0,DataType.Scalar.NBT));}
        var value=new DataValue.NbtValue(10,new DataValue.RecordValue(Map.of("\ud800",new DataValue.NbtValue(8,new DataValue.TextValue("x".repeat(10000))))));
        assertEquals(new DataValue.TextValue("\ud800"),read(RegisterNbt.Kind.KEY,value,0,DataType.Scalar.TEXT));
        assertEquals(new DataValue.IntegerValue(1),read(RegisterNbt.Kind.LENGTH,value,0,DataType.Scalar.INTEGER));
        for(long index:List.of(-1L,1L,Long.MAX_VALUE))assertEquals(ComputationException.Code.INDEX_OUT_OF_BOUNDS,assertThrows(ComputationException.class,()->read(RegisterNbt.Kind.CHILD,value,index,DataType.Scalar.NBT)).code());
        for(var kind:List.of(RegisterNbt.Kind.READ_TEXT,RegisterNbt.Kind.READ_INTEGER))assertEquals(new DataValue.RecordValue(Map.of("ok",new DataValue.BooleanValue(false),"value",DataValue.NullValue.INSTANCE)),read(kind,value,0,RegisterNbt.checked(kind==RegisterNbt.Kind.READ_TEXT?DataType.Scalar.TEXT:DataType.Scalar.INTEGER)));
        assertThrows(ComputationException.class,()->read(RegisterNbt.Kind.LENGTH,OLD,0,DataType.Scalar.INTEGER));
        assertThrows(ComputationException.class,()->read(RegisterNbt.Kind.KEY,new DataValue.NbtValue(9,new DataValue.ListValue(List.of(OLD))),0,DataType.Scalar.TEXT));
    }
 @Test void nativeOperationsRejectAnyAndWrongConcreteSignaturesAtInstallation(){
  for(var body:List.of(DataType.Scalar.ANY,DataType.Scalar.TEXT,new DataType.ListType(DataType.Scalar.ANY))) {
   assertThrows(IllegalArgumentException.class,()->new RegisterProgram(List.of(new RegisterProgram.Code(new RegisterNbt.Instruction(RegisterNbt.Kind.PACK,1,0,9),0,"bad")),List.of(body,DataType.Scalar.NBT),List.of(),Map.of("x",0),Map.of("x",0,"out",1),1));
  }
  for(int tag:List.of(-1,13,127))assertThrows(IllegalArgumentException.class,()->pack(tag));
  var old=RegisterConstructionTest.list(1);var initial=old.begin(new DataValue.RecordValue(Map.of("x",new DataValue.IntegerValue(1))));
  var forged=RegisterConstructionTest.copy(initial,0,initial.registers(),RegisterConstruction.savedNative(7,1));assertThrows(ComputationException.class,()->old.validate(forged,1,false));
 }
 @Test void logicalReservationOverflowIsRejectedInsteadOfBecomingNegative(){
  DataValue.NbtValue large=OLD;for(int i=0;i<60;i++)large=new DataValue.NbtValue(9,new DataValue.ListValue(List.of(large,large)));
  var body=new DataValue.ListValue(List.of(entry("a",large),entry("b",OLD)));var p=pack(10);var initial=start(p,body);
  assertThrows(ArithmeticException.class,()->{var state=initial;for(int i=0;!p.complete(state)&&i<100;i++)state=p.step(state,Map.of(),Map.of(),i+1,n->{}).registers();});
 }
 @Test void emptyContainerCompletionRejectsWorkBelowItsActualMinimum(){
  for(int tag:List.of(7,9,10,11,12)){
   var p=pack(tag);var state=start(p,new DataValue.ListValue(List.of()));long work=0;
   while(!p.complete(state))state=p.step(state,Map.of(),Map.of(),++work,n->{}).registers();
   assertEquals(tag==10?3:2,work);assertTrue(state.build().isEmpty());p.validate(state,work,false);
   var completed=state;long forgedWork=work-1;
   assertEquals(ComputationException.Code.INVALID_CHECKPOINT,assertThrows(ComputationException.class,()->p.validate(completed,forgedWork,false)).code());
  }
 }
}
