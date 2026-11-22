package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegisterRecipeFormatTest {
    static DataType.RecordType outcome(){return new DataType.RecordType(Map.of("ok",DataType.Scalar.BOOLEAN,"value",new DataType.OptionalType(DataType.Semantic.RECIPE)));}
    static RegisterProgram check(){return new RegisterProgram(List.of(new RegisterProgram.Code(new RegisterProgram.FormatCheck(1,0,"recipe",1),0,"check")),
        List.of(DataType.Scalar.ANY,outcome()),List.of(),Map.of("raw",0),Map.of("raw",0,"checked",1),1);}
    static RegisterState cold(RegisterState s,long paid,DataValue.RecordValue progress){
        return new RegisterState(s.image(),s.pc(),s.registers(),s.structured(),s.scan(),s.calls(),s.transaction(),s.constructionCapable(),s.build(),s.serialCapable(),s.serial(),true,Optional.of(new RegisterComparison(paid,progress)));
    }
    @Test void explicitCheckReturnsFalseForWrongShapesAndRetainsNominalIdentityOnRecheck(){
        var raw=FoundationRecipeTest.projection(false);var semantic=new DataValue.RecipeValue("recipe",1,raw);
        var bad=new LinkedHashMap<>(raw.fields());bad.put("outputs",new DataValue.ListValue(List.of()));
        for(DataValue value:List.of(raw,semantic,new DataValue.RecordValue(bad),new DataValue.IntegerValue(0),DataValue.NullValue.INSTANCE)){
            var p=check();var s=p.begin(new DataValue.RecordValue(Map.of("raw",value)));long work=0;
            while(!p.complete(s)){s=p.step(s);p.validate(s,++work,false);}
            var result=(DataValue.RecordValue)s.registers().get(1);boolean valid=value==raw||value==semantic;
            assertEquals(new DataValue.BooleanValue(valid),result.field("ok"));
            assertEquals(valid?semantic:DataValue.NullValue.INSTANCE,result.field("value"));
            assertTrue(work>=3);
        }
    }
    @Test void everyPaidBoundaryReplaysAndRejectsForgedWorkSourceFormatPcAndControls(){
        var p=check();var source=FoundationRecipeTest.projection(true);var s=p.begin(new DataValue.RecordValue(Map.of("raw",source)));long work=0;
        while(!p.complete(s)){
            s=p.step(s);work++;p.validate(s,work,false);
            if(s.comparison().isEmpty())continue;
            var live=s;var cursor=s.comparison().orElseThrow();var progress=(DataValue.RecordValue)cursor.value().field("progress");
            var restored=cold(s,cursor.paidWork(),progress);p.validate(restored,work,false);assertEquals(s.value(),restored.value());s=restored;
            long now=work;
            assertThrows(ComputationException.class,()->p.validate(cold(live,cursor.paidWork()+1,progress),now,false));
            for(var name:List.of("format","source","pc","semantic_version","frames")){
                var changed=new LinkedHashMap<>(progress.fields());
                changed.put(name,switch(name){case "format"->new DataValue.TextValue("future");case "source"->FoundationRecipeTest.projection(false);case "frames"->new DataValue.ListValue(List.of(new DataValue.IntegerValue(-1)));default->new DataValue.IntegerValue(-1);});
                assertThrows(RuntimeException.class,()->p.validate(cold(live,cursor.paidWork(),new DataValue.RecordValue(changed)),now,false));
            }
        }
    }
    @Test void publicationAndColdRestoreReserveTheirExactReportedTemporaryPeakWithoutMutatingOldState(){
        var p=check();var s=p.begin(new DataValue.RecordValue(Map.of("raw",FoundationRecipeTest.projection(false))));long work=0;
        while(s.comparison().isEmpty()||!s.comparison().orElseThrow().completeFormat(s)){s=p.step(s);p.validate(s,++work,false);}
        var complete=s;var before=s.value();long[] peak={0};p.step(s,Map.of(),Map.of(),work+1,n->peak[0]=Math.max(peak[0],n));
        assertTrue(peak[0]>0);
        long publicationWork=work+1;
        assertThrows(ComputationException.class,()->p.step(complete,Map.of(),Map.of(),publicationWork,n->{if(n>=peak[0])throw new ComputationException(ComputationException.Code.MEMORY_LIMIT,"publication","test");}));
        assertEquals(before,complete.value());assertFalse(complete.registers().containsKey(1));
        var c=complete.comparison().orElseThrow();var progress=(DataValue.RecordValue)c.value().field("progress");
        var restored=cold(complete,c.paidWork(),progress);long[] restorePeak={0};
        p.validate(restored,work,false,n->restorePeak[0]=Math.max(restorePeak[0],n));
        var tight=cold(complete,c.paidWork(),progress);long cycles=work;
        assertThrows(ComputationException.class,()->p.validate(tight,cycles,false,n->{if(n>=restorePeak[0])throw new ComputationException(ComputationException.Code.MEMORY_LIMIT,"restore","test");}));
        assertEquals(before,tight.value());
        assertTrue(p.complete(p.step(complete)));
    }
    @Test void shapedSlotCursorRestoresWithoutPublishingAPrefix() {
        var resultType=new DataType.RecordType(Map.of("ok",DataType.Scalar.BOOLEAN,"value",new DataType.OptionalType(DataType.Semantic.RECIPE_V2)));
        var p=new RegisterProgram(List.of(new RegisterProgram.Code(new RegisterProgram.FormatCheck(1,0,"recipe",2),0,"check")),
            List.of(DataType.Scalar.ANY,resultType),List.of(),Map.of("raw",0),Map.of("raw",0,"checked",1),1);
        var raw=FoundationRecipeTest.shapedProjection();
        var s=p.begin(new DataValue.RecordValue(Map.of("raw",raw)));long work=0;boolean restoredSlot=false;
        while(!p.complete(s)){
            s=p.step(s);work++;
            if(restoredSlot || s.comparison().isEmpty())continue;
            var cursor=s.comparison().orElseThrow();
            var progress=(DataValue.RecordValue)cursor.value().field("progress");
            var frames=((DataValue.ListValue)progress.field("frames")).values();
            if(frames.isEmpty() || !(frames.getFirst() instanceof DataValue.RecordValue top) ||
                !top.field("kind").equals(new DataValue.IntegerValue(10)) || !top.field("index").equals(new DataValue.IntegerValue(1)))continue;
            assertFalse(s.registers().containsKey(1));
            var forged=new LinkedHashMap<>(progress.fields());forged.put("semantic_version",new DataValue.IntegerValue(1));
            var invalid=cold(s,cursor.paidWork(),new DataValue.RecordValue(forged));long spent=work;
            assertThrows(ComputationException.class,()->p.validate(invalid,spent,false));
            s=cold(s,cursor.paidWork(),progress);p.validate(s,work,false);restoredSlot=true;
        }
        assertTrue(restoredSlot);
        assertEquals(new DataValue.RecipeValue("recipe",2,raw),((DataValue.RecordValue)s.registers().get(1)).field("value"));
    }
    @Test void opaqueProjectionIsMasterOnlyAndRejectsNullAndRecordImpostors(){
        var p=new RegisterProgram(List.of(new RegisterProgram.Code(new RegisterProgram.FormatProject(1,0,"recipe",1),0,"project")),
            List.of(DataType.Scalar.ANY,DataType.Scalar.ANY),List.of(),Map.of("raw",0),Map.of("raw",0,"out",1),1);
        assertTrue(p.advancedTypeLocation().isPresent());assertTrue(p.instructionFamilies().contains(InstructionFamily.ISA_1B));
        assertFalse(p.instructionFamilies().contains(InstructionFamily.ISA_1A));
        for(var bad:List.<DataValue>of(DataValue.NullValue.INSTANCE,FoundationRecipeTest.projection(false),new DataValue.RecipeValue("recipe",2,FoundationRecipeTest.projection(false))))
            assertEquals(ComputationException.Code.TYPE_MISMATCH,assertThrows(ComputationException.class,()->p.step(p.begin(new DataValue.RecordValue(Map.of("raw",bad))))).code());
        var recipe=new DataValue.RecipeValue("recipe",1,FoundationRecipeTest.projection(false));var next=p.step(p.begin(new DataValue.RecordValue(Map.of("raw",recipe))));
        assertSame(recipe.projection(),next.registers().get(1));
    }
    @Test void largeNativeComponentsAndDeepNonRecipeDataStayBoundedWithoutHiddenConversions(){
        var raw=FoundationRecipeTest.projection(true);
        DataValue nativeData=new DataValue.NbtValue(8,new DataValue.TextValue("components".repeat(1000)));
        for(int i=0;i<500;i++)nativeData=new DataValue.NbtValue(10,new DataValue.RecordValue(Map.of("nested",nativeData)));
        var output=(DataValue.RecordValue)((DataValue.ListValue)raw.field("outputs")).values().getFirst();
        var changedOutput=new LinkedHashMap<>(output.fields());changedOutput.put("components",nativeData);
        var changed=new LinkedHashMap<>(raw.fields());changed.put("outputs",new DataValue.ListValue(List.of(new DataValue.RecordValue(changedOutput))));
        var value=new DataValue.RecordValue(changed);var p=check();var s=p.begin(new DataValue.RecordValue(Map.of("raw",value)));int work=0;
        while(!p.complete(s)){s=p.step(s);assertTrue(++work<1000);}
        assertEquals(value,((DataValue.RecipeValue)((DataValue.RecordValue)s.registers().get(1)).field("value")).projection());
        DataValue deep=value;for(int i=0;i<3000;i++)deep=new DataValue.ListValue(List.of(deep));
        var invalid=p.begin(new DataValue.RecordValue(Map.of("raw",deep)));for(int i=0;i<3;i++)invalid=p.step(invalid);
        assertTrue(p.complete(invalid));assertEquals(new DataValue.BooleanValue(false),((DataValue.RecordValue)invalid.registers().get(1)).field("ok"));
    }
    @Test void serializedControlsAndResultStoragePreflightsEqualActualLogicalUnits(){
        var p=check();var state=p.begin(new DataValue.RecordValue(Map.of("raw",FoundationRecipeTest.projection(true))));
        var task=RegisterFormatCheck.start(state.registers().get(0),1);
        while(true){
            assertEquals(task.progress(state).storageUnits(),task.progressUnits(state));
            if(task.done())break;task=task.advance();
        }
        assertEquals(task.result().storageUnits(),task.resultUnits());
        var proof=RecipeValidation.begin("recipe",1,FoundationRecipeTest.projection(true));
        while(true){assertEquals(proof.controls().storageUnits(),proof.controlsStorageUnits());if(proof.done())break;proof=proof.advance();}
    }
    @Test void v5OnlyGrammarAndUnknownFormatsAreLocatedAndOldValueCheckStaysNominal(){
        String source="TYPE R FORMAT recipe 1\nTYPE maybe OPTIONAL R\nTYPE result RECORD ok BOOLEAN value maybe\nREG checked result\nFMT.CHECK checked raw recipe 1";
        for(int version:List.of(23,2,3,4))assertThrows(IllegalArgumentException.class,()->ProcedureAssembler.assemble(new AssemblySource(version,source),Map.of("raw",DataType.Scalar.ANY),Map.of(),Map.of(),10000,1000));
        assertDoesNotThrow(()->ProcedureAssembler.assemble(new AssemblySource(source),Map.of("raw",DataType.Scalar.ANY),Map.of(),Map.of(),10000,1000));
        for(String bad:List.of(source.replace("recipe 1","future 1"),source.replace("recipe 1","recipe 3"),"REG raw RECIPE")){
            var error=assertThrows(ComputationException.class,()->ProcedureAssembler.assemble(new AssemblySource(bad),Map.of("raw",DataType.Scalar.ANY),Map.of(),Map.of(),10000,1000));
            assertTrue(error.getMessage().contains("line"));
        }
        var nominal=new RegisterProgram(List.of(new RegisterProgram.Code(new RegisterProgram.ValueCheck(1,0),0,"nominal")),List.of(DataType.Scalar.ANY,outcome()),List.of(),Map.of("raw",0),Map.of("raw",0,"result",1),1);
        var s=nominal.begin(new DataValue.RecordValue(Map.of("raw",FoundationRecipeTest.projection(false))));while(!nominal.complete(s))s=nominal.step(s);
        assertEquals(new DataValue.BooleanValue(false),((DataValue.RecordValue)s.registers().get(1)).field("ok"));
    }
}
