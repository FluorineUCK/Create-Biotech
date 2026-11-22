package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RecipeAmountExecutionTest {
    private static ProgramDocument published() throws Exception {
        try (var in = RecipeAmountExecutionTest.class.getResourceAsStream("/assets/create_biotech/biocomputing/examples/recipe_page_d.bcp")) {
            assertNotNull(in);
            return ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(in.readAllBytes()).strip()), SerialFlowHostTest.ARCHIVE);
        }
    }
    @Test void actualPublicAssemblyMultipliesWithoutResolvingTagsOrMutatingSourceAcrossRestore() throws Exception {
      for(boolean sequenced:List.of(false,true)){
        var page = FoundationRecipeTest.projection(sequenced);
        var ingredient = ((DataValue.ListValue)page.field("inputs")).values().getFirst();
        var result = calculate(page, true);
        assertEquals(new DataValue.IntegerValue(6), result.field("count"));
        assertEquals(new DataValue.IntegerValue(3), result.field("batches"));
        assertEquals(ingredient, result.field("ingredient"));
        assertInstanceOf(DataValue.RecipeValue.class,result.field("recipe"));
        assertEquals(page, ((DataValue.RecipeValue)result.field("recipe")).projection());
        assertEquals(new DataValue.IntegerValue(2), ((DataValue.RecordValue)ingredient).field("count"));
      }
    }
    @Test void malformedAmountIsAnExplicitTypeCheckResultNotAnImplicitConversion() throws Exception {
        var page = record("inputs", new DataValue.ListValue(List.of(record("count", "2"))));
        assertEquals(record("ok", false, "value", null), calculate(page, false));
        assertEquals(record("ok",false,"value",null),calculate(record("inputs",new DataValue.ListValue(List.of(record("count",2)))),false));
    }
    @Test void dStartsBeforePausedAAndPollsBoundedlyAcrossPauseAndColdSaveThenCalculatesOnce()throws Exception{
        var limits=NativeLibraryOwnersTest.limits("cleric",5);var page=FoundationRecipeTest.projection(false);
        var d=NativeLibraryOwnersTest.install(published(),limits,null);
        var a=new ComputerBlockHost(()->{});var envelope=new DataType.RecordType(Map.of("value",DataType.Scalar.ANY));
        var input=record("message",record("value",page));
        a.installFlow(new FlowProgram(Map.of("message",envelope),List.of(new FlowProgram.Write("send","x0","message"))).withAssembly(),input,limits,true);
        var pausedA=a.write();d.setPaused(false,limits,true);int sleeps=0;boolean reloaded=false;
        for(int tick=0;tick<160;tick++){
            int status=NativeLibraryStartupTest.status(d,"x0",limits,a,"x0",limits);assertEquals(0,status);
            d.tick(limits,true,Map.of("x0",status));
            NativeLibraryStartupTest.network(a,"x0",limits,d,"x0",limits).rejectPaused(NativeLibraryStartupTest.pin(d,"x0"));
            var state=d.flowSnapshot().orElseThrow().state();
            assertNotEquals(FlowRuntime.Status.FAILED,state.status(),state.diagnostic().toString());
            assertNotEquals(FlowRuntime.Status.WAITING_READ,state.status(),"Do not admit READ while paused A is not ready");
            if(state.status()==FlowRuntime.Status.SLEEPING){
                sleeps++;assertTrue(state.sleepTicks()<=20);
                if(!reloaded){
                    d.setPaused(true,limits,true);var saved=d.write();
                    for(int n=0;n<25;n++)d.tick(limits,true,Map.of("x0",0));
                    assertEquals(saved,d.write(),"Pause preserves the same polling sleep/cost");
                    var next=new ComputerBlockHost(()->{});next.read(saved);next.tick(limits,true);
                    assertTrue(next.loaded(),next.diagnostic().toString());assertEquals(saved,next.write());d=next;
                    d.setPaused(false,limits,true);reloaded=true;
                }
            }
            assertEquals(pausedA,a.write(),"Waiting D must not start, consume or mutate A");
        }
        assertTrue(reloaded);assertTrue(sleeps>80);assertTrue(d.flowSnapshot().orElseThrow().state().work()<160,"No per-tick busy polling");
        a.setPaused(false,limits,true);int exchanges=0;
        for(int tick=2000;tick<4000;tick++){
            a.tick(limits,true,Map.of("x0",0));
            d.tick(limits,true,Map.of("x0",NativeLibraryStartupTest.status(d,"x0",limits,a,"x0",limits)));
            if(a.exchange("x0",limits,true,d,"x0",limits,true)==XBusExchange.Status.EXCHANGED)exchanges++;
            var state=d.flowSnapshot().orElseThrow().state();assertNotEquals(FlowRuntime.Status.FAILED,state.status(),state.diagnostic().toString());
            if(state.status()==FlowRuntime.Status.COMPLETE){
                var result=(DataValue.RecordValue)state.locals().field("result");
                assertEquals(new DataValue.IntegerValue(6),result.field("count"));assertInstanceOf(DataValue.RecipeValue.class,result.field("recipe"));
                assertEquals(page,((DataValue.RecipeValue)result.field("recipe")).projection());assertEquals(1,exchanges);assertEquals(input,a.flowSnapshot().orElseThrow().initialInputs());return;
            }
        }
        fail("Staggered public D failed to complete");
    }
    @Test void realReadFailureAfterReadyObservationIsStillTerminalAndNeverAutomaticallyRetried()throws Exception{
        var limits=NativeLibraryOwnersTest.limits("cleric",5);var d=NativeLibraryOwnersTest.install(published(),limits,null);
        var a=new ComputerBlockHost(()->{});var envelope=new DataType.RecordType(Map.of("value",DataType.Scalar.ANY));
        a.installFlow(new FlowProgram(Map.of("message",envelope),List.of(new FlowProgram.Write("send","x0","message"))).withAssembly(),
            record("message",record("value",FoundationRecipeTest.projection(false))),limits,true);
        a.setPaused(false,limits,true);d.setPaused(false,limits,true);
        for(int tick=0;tick<30&&d.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.WAITING_READ;tick++){
            a.tick(limits,true);d.tick(limits,true,Map.of("x0",NativeLibraryStartupTest.status(d,"x0",limits,a,"x0",limits)));
        }
        assertEquals(FlowRuntime.Status.WAITING_READ,d.flowSnapshot().orElseThrow().state().status());
        a.setPaused(true,limits,true);
        NativeLibraryStartupTest.network(a,"x0",limits,d,"x0",limits).rejectPaused(NativeLibraryStartupTest.pin(d,"x0"));
        var failed=d.flowSnapshot().orElseThrow().state();
        assertEquals(FlowRuntime.Status.FAILED,failed.status());assertEquals(ComputationException.Code.XBUS_PEER_PAUSED,failed.diagnostic().orElseThrow().code());
        assertFalse(failed.locals().fields().containsKey("result"));var saved=d.write();
        for(int tick=0;tick<50;tick++)d.tick(limits,true,Map.of("x0",0));
        assertEquals(saved,d.write());d=NativeLibraryOwnersTest.reload(d,limits,null);assertEquals(saved,d.write());
    }
    private static DataValue.RecordValue calculate(DataValue.RecordValue page, boolean restore) throws Exception {
        var limits = NativeLibraryOwnersTest.limits("cleric", 5);
        var d = NativeLibraryOwnersTest.install(published(), limits, null);
        var a = new ComputerBlockHost(() -> {});
        var envelope = new DataType.RecordType(Map.of("value", DataType.Scalar.ANY));
        var input = record("message", record("value", page));
        a.installFlow(new FlowProgram(Map.of("message", envelope), List.of(new FlowProgram.Write("send", "x0", "message"))).withAssembly(), input, limits, true);
        a.setPaused(false, limits, true); d.setPaused(false, limits, true);
        boolean restored = false;
        for (int tick = 0; tick < 2000; tick++) {
            a.tick(limits, true); d.tick(limits, true,Map.of("x0",NativeLibraryStartupTest.status(d,"x0",limits,a,"x0",limits)));
            a.exchange("x0", limits, true, d, "x0", limits, true);
            var state = d.flowSnapshot().orElseThrow().state();
            assertNotEquals(FlowRuntime.Status.FAILED, state.status(), state.diagnostic().toString());
            if (restore && !restored && state.work() >= 8 && state.status() != FlowRuntime.Status.COMPLETE) {
                d = NativeLibraryOwnersTest.reload(d, limits, null); restored = true;
            }
            if (state.status() == FlowRuntime.Status.COMPLETE) {
                assertEquals(input, a.flowSnapshot().orElseThrow().initialInputs());
                if (restore) assertTrue(restored);
                return (DataValue.RecordValue)state.locals().field("result");
            }
        }
        return fail("Published calculation failed to finish");
    }
}
