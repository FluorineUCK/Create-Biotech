package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;

class MaterialSelectionExecutionTest {
    static final ComputerHostLimits LIMITS = NativeLibraryOwnersTest.limits("cleric",5);
    static final DataValue N = DataValue.NullValue.INSTANCE;
    static final DataValue COMPONENTS = new DataValue.NbtValue(10, record("raw", new DataValue.NbtValue(6,
        new DataValue.IntegerValue(Long.MIN_VALUE)), "bytes", new DataValue.NbtValue(7, new DataValue.ListValue(List.of(
            new DataValue.IntegerValue(-128), new DataValue.IntegerValue(0), new DataValue.IntegerValue(127))))));
    static DataValue.RecordValue material(DataValue item, DataValue tag, long count) {
        return record("item", item, "tag", tag, "count", count, "components", COMPONENTS);
    }
    static DataValue.TextValue text(String s) { return new DataValue.TextValue(s); }
    static DataValue.RecordValue choice(String item, String... tags) {
        return record("slot", 17, "item", text(item), "tags", new DataValue.ListValue(Arrays.stream(tags).map(DataValue.TextValue::new).map(x -> (DataValue)x).toList()));
    }
    static class Rig {
        ComputerBlockHost[] h = new ComputerBlockHost[2];
        final DataValue.ListValue materials, choices;
        long[] peak = new long[2]; int exchanges, ticks;
        Rig(List<? extends DataValue> materials, List<? extends DataValue> choices) throws Exception {
            this.materials = new DataValue.ListValue(new ArrayList<>(materials)); this.choices = new DataValue.ListValue(new ArrayList<>(choices));
            for (int i=0;i<2;i++) {
                var doc = MaterialSelectionDocuments.published(i==0?"coordinator":"service"); var body = doc.flow().orElseThrow();
                var initial = new LinkedHashMap<>(body.initial().fields());
                if (i==0) { initial.put("materials",this.materials); initial.put("choices",this.choices); }
                h[i] = new ComputerBlockHost(()->{}); h[i].installFlow(body.program(doc.archive()),new DataValue.RecordValue(initial),LIMITS,true);
            }
            h[1].setPaused(false,LIMITS,true);
        }
        void step(boolean reload) {
            for (int i=0;i<2;i++) h[i].tick(LIMITS,true,Map.of("x0",NativeLibraryStartupTest.status(h[i],"x0",LIMITS,h[1-i],"x0",LIMITS)));
            if (h[0].exchange("x0",LIMITS,true,h[1],"x0",LIMITS,true)==XBusExchange.Status.EXCHANGED) exchanges++;
            ticks++;
            for (int i=0;i<2;i++) {
                var s=h[i].flowSnapshot().orElseThrow();
                peak[i]=Math.max(peak[i],s.state().controlUnits()+s.initialInputs().storageUnits()+s.control().storageUnits()+s.program().usage(LIMITS.hardware()).constantUnits());
                assertNotEquals(FlowRuntime.Status.FAILED,s.state().status(),"role="+i+" "+s.state().diagnostic());
                if(reload) {
                    boolean paused=s.state().paused(); h[i].setPaused(true,LIMITS,true); var saved=h[i].write();
                    var cold=new ComputerBlockHost(()->{});cold.read(saved);cold.tick(LIMITS,true);
                    assertTrue(cold.loaded(),cold.diagnostic().toString());assertEquals(saved,cold.write());
                    h[i]=cold;h[i].setPaused(paused,LIMITS,true);
                }
            }
        }
        DataValue.RecordValue finish(boolean everyTick) {
            for (int n=0;n<60;n++) { step(n==20); assertNotEquals(FlowRuntime.Status.WAITING_READ,h[1].flowSnapshot().orElseThrow().state().status()); }
            assertEquals(0,h[0].flowSnapshot().orElseThrow().state().work());
            h[0].setPaused(false,LIMITS,true);
            for(int n=0;n<30000;n++) {
                step(everyTick||n%31==0);var state=h[0].flowSnapshot().orElseThrow().state();
                if(state.status()==FlowRuntime.Status.COMPLETE) {
                    assertEquals(materials,state.locals().field("materials"));assertEquals(choices,state.locals().field("choices"));
                    var result=(DataValue.RecordValue)state.locals().field("result");
                    System.out.println("SELECTION_RUN rows="+materials.values().size()+" code="+result.field("code")+" ticks="+ticks+" work="+Arrays.stream(h).map(x->x.flowSnapshot().orElseThrow().state().work()).toList()+" peakCommitted="+Arrays.toString(peak)+" exchanges="+exchanges);
                    return result;
                }
                assertEquals(N,com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalStatusView.ofFlowOwner(Optional.empty(),h[0].flowSnapshot()).field("output"),"Private callee registers are not a published partial result");
            }
            return fail("No selection completion");
        }
    }
    @Test void exactChoicesTagsDuplicatesNativeComponentsAndLateRejectionsRunOnlyPublicISA() throws Exception {
        var exact=material(text("minecraft:stone"),N,2);var tag=material(N,text("c:stones"),3);
        var rows=List.of(exact,exact,tag,tag);var choices=List.of(N,choice("minecraft:stone"),choice("minecraft:granite","z:other","c:stones"),choice("minecraft:andesite","c:stones"));
        var rig=new Rig(rows,choices);var result=rig.finish(true);
        assertEquals(new DataValue.BooleanValue(true),result.field("ok"));assertEquals(text("ok"),result.field("code"));
        var out=(DataValue.ListValue)result.field("items");assertEquals(4,out.values().size());
        for(int i=0;i<4;i++) { var r=(DataValue.RecordValue)out.values().get(i);assertEquals(text(List.of("minecraft:stone","minecraft:stone","minecraft:granite","minecraft:andesite").get(i)),r.field("item"));assertEquals(rows.get(i).field("count"),r.field("count"));assertEquals(COMPONENTS,r.field("components")); }
        assertEquals(8,rig.exchanges);
        var cases=List.of(
            new Case(tag,N,"selection_required"),new Case(exact,choice("minecraft:dirt"),"choice_mismatch"),
            new Case(tag,choice("minecraft:stone","c:stone"),"tag_mismatch"),
            new Case(material(N,N,1),N,"invalid_material"),new Case(material(text("x:y"),text("c:z"),1),N,"invalid_material"),
            new Case(material(text("x:y"),N,0),N,"invalid_material"),new Case(material(text("x:y"),N,-1),N,"invalid_material"),
            new Case(material(text(""),N,1),N,"invalid_material"),new Case(material(text("minecraft:air"),N,1),N,"invalid_material"),
            new Case(tag,choice("","c:stones"),"invalid_material"),new Case(tag,choice("minecraft:air","c:stones"),"invalid_material"));
        for(var c:cases) { var rejected=new Rig(List.of(exact,c.row),List.of(N,c.option)).finish(false);assertEquals(new DataValue.BooleanValue(false),rejected.field("ok"));assertEquals(text(c.code),rejected.field("code"));assertEquals(N,rejected.field("items")); }
    }
    record Case(DataValue.RecordValue row,DataValue option,String code) {}
    @Test void emptyAndCountMismatchDoNotContactServiceAndWorkGrowsOnRealTagLoops() throws Exception {
        var emptyRig=new Rig(List.of(),List.of());var empty=emptyRig.finish(true);assertEquals(new DataValue.ListValue(List.of()),empty.field("items"));assertEquals(0,emptyRig.exchanges);
        var mismatchRig=new Rig(List.of(material(text("x:y"),N,1)),List.of());var mismatch=mismatchRig.finish(true);assertEquals(text("count_mismatch"),mismatch.field("code"));assertEquals(N,mismatch.field("items"));assertEquals(0,mismatchRig.exchanges);
        long prior=-1;
        for(int n:List.of(1,17)) {
            var rows=new ArrayList<DataValue>();var choices=new ArrayList<DataValue>();
            var tags=new ArrayList<String>();for(int t=0;t<n;t++)tags.add("c:tag_"+t);
            for(int i=0;i<n;i++){rows.add(material(N,text(tags.getLast()),i+1));choices.add(choice("minecraft:stone",tags.toArray(String[]::new)));}
            var rig=new Rig(rows,choices);var result=rig.finish(n==1);assertEquals(new DataValue.BooleanValue(true),result.field("ok"));
            long work=Arrays.stream(rig.h).mapToLong(x->x.flowSnapshot().orElseThrow().state().work()).sum();assertTrue(work>prior);prior=work;
            for(long peak:rig.peak)assertTrue(peak<65536);
        }
    }
    @Test void pausedPeerAfterAdmittedReadKeepsTheNormalFaultAndNeverFabricatesSuccess() throws Exception {
        var rig=new Rig(List.of(material(text("x:y"),N,1)),List.of(N));rig.h[0].setPaused(false,LIMITS,true);
        for(int n=0;n<1000;n++){for(int i=0;i<2;i++)rig.h[i].tick(LIMITS,true,Map.of("x0",NativeLibraryStartupTest.status(rig.h[i],"x0",LIMITS,rig.h[1-i],"x0",LIMITS)));if(rig.h[1].flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.WAITING_READ)break;}
        assertEquals(FlowRuntime.Status.WAITING_READ,rig.h[1].flowSnapshot().orElseThrow().state().status());
        rig.h[0].setPaused(true,LIMITS,true);NativeLibraryStartupTest.network(rig.h[0],"x0",LIMITS,rig.h[1],"x0",LIMITS).rejectPaused(NativeLibraryStartupTest.pin(rig.h[1],"x0"));
        var state=rig.h[1].flowSnapshot().orElseThrow().state();assertEquals(FlowRuntime.Status.FAILED,state.status());assertEquals(ComputationException.Code.XBUS_PEER_PAUSED,state.diagnostic().orElseThrow().code());
        assertEquals(N,com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalStatusView.ofFlowOwner(Optional.empty(),rig.h[0].flowSnapshot()).field("output"));var saved=rig.h[1].write();
        for(int n=0;n<50;n++)rig.h[1].tick(LIMITS,true);assertEquals(saved,rig.h[1].write());
    }
}
