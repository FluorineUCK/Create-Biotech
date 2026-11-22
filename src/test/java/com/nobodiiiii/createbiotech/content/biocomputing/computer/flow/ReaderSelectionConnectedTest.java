package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/** Six independent owners; only visible source executes application logic, including record packing. */
class ReaderSelectionConnectedTest {
    static final ComputerHostLimits LIMITS=NativeLibraryOwnersTest.limits("cleric",5);
    static final DataValue N=DataValue.NullValue.INSTANCE;
    static final DataValue COMPONENTS=MaterialSelectionExecutionTest.COMPONENTS;
    static DataValue.RecordValue material(String item,String tag){return record("item",item==null?N:new DataValue.TextValue(item),"tag",tag==null?N:new DataValue.TextValue(tag),"count",3,"components",COMPONENTS);}
    static ProgramDocument initial(ProgramDocument document,DataValue.RecordValue values){var w=new ProgramWorkspace(document);var b=w.flow().orElseThrow();w.setFlow(Optional.of(new FlowDocumentBody(b.inputs(),b.steps(),values,b.canvas(),b.inline(),b.execution())));return w.snapshot();}
    static long ownerUnits(FlowBlockHost.Saved saved,ComputerHostLimits limits){
        return NativeLibraryServiceSerialTest.units(saved,limits)
            + saved.cancellations().stream().mapToLong(c->c.value().storageUnits()).sum();
    }
    static ComputerHostLimits terminalLimits(long ram,int slots){
        return new ComputerHostLimits(new ResourceLimits(LIMITS.hardware().programUnits(),ram,LIMITS.hardware().workPerSlice()),
            LIMITS.archive(),LIMITS.mailbox(),LIMITS.pins(),LIMITS.serial(),slots,LIMITS.isa1a(),LIMITS.isa1b());
    }
    static final class Rig {
        final ComputerBlockHost[] h=new ComputerBlockHost[6];final long[] peaks=new long[6];final long[] taskWork=new long[6];
        final InventoryReadSafetyTest.RangeFixture fixture=InventoryReadSafetyTest.rangeFixture();
        final DataValue.RecordValue material;final DataValue.RecordValue address=BlockSnapshotReader.position(fixture.reader());
        final SerialDevice wireB=NativeLibraryOwnersTest.device(LIMITS),reader;
        final SerialNetwork network=new SerialNetwork();final Map<BlockPos,SerialDevice> devices;final CableDiscovery.World world;
        final UUID identity=UUID.randomUUID(),session=UUID.randomUUID();final List<DataValue.RecordValue> commands=new ArrayList<>();final List<Integer> readerFrames=new ArrayList<>();final Set<XBusReceipt> selections=new HashSet<>();
        ReaderSelectionTerminalFixture terminal;XBusHistorySavedData history=new XBusHistorySavedData();
        boolean holdReply,holdPanel,measureRounds;
        final boolean staggered;
        final String mode;
        DataValue.RecordValue rangeReply;
        UUID nonce=UUID.randomUUID();ComputerControlSession control=new ComputerControlSession(nonce);long intent,view;TerminalStatusView observed;int ticks;
        Rig(DataValue.RecordValue material)throws Exception{
            this(material, "normal");
        }
        Rig(DataValue.RecordValue material,String mode)throws Exception{
            this.mode=mode; this.staggered=mode.equals("staggered"); this.material=material;var config=NativeLibraryOwnersTest.CONFIG;
            reader=new SerialDevice(()->config.readerSerial(false),()->true,()->config.serialCodec(false),()->{});wireB.configure("s0",Direction.EAST,true);reader.configure("s0",Direction.WEST,true);
            h[0]=NativeLibraryOwnersTest.install(initial(ReaderSelectionDocuments.published("emitter"),record("target",address,"component",BuiltinReadComponents.INVENTORY_ITEM_TAGS,"offset",mode.equals("empty")?27:5,"count",2)),LIMITS,null);
            h[1]=NativeLibraryOwnersTest.install(ReaderDriverDocumentTest.load("b"),LIMITS,wireB);h[2]=NativeLibraryOwnersTest.install(ReaderDriverDocumentTest.load("c"),LIMITS,null);
            h[3]=NativeLibraryOwnersTest.install(initial(ReaderSelectionDocuments.published("preparer"),record("material",material,"poll_ticks",1)),LIMITS,null);
            terminal=new ReaderSelectionTerminalFixture(LIMITS);terminal.install(ReaderSelectionDocuments.published("terminal"));h[4]=terminal.host();
            h[5]=NativeLibraryOwnersTest.install(initial(MaterialSelectionDocuments.published("service"),record("poll_ticks",1)),LIMITS,null);
            if(!staggered) for(var owner:h)owner.setPaused(false,LIMITS,true);
            devices=Map.of(fixture.reader().west(2),wireB,fixture.reader(),reader);
            world=new CableDiscovery.World(){public boolean loaded(BlockPos p){return true;}public CableDiscovery.Cell cell(BlockPos p){return new CableDiscovery.Cell(p.equals(fixture.reader().west())?CableDiscovery.Kind.CABLE:devices.containsKey(p)?CableDiscovery.Kind.ENDPOINT:CableDiscovery.Kind.EMPTY,null);}};
        }
        void step(){
            var config=NativeLibraryOwnersTest.CONFIG;
            for(int i=0;i<6;i++){
                Map<String,Integer> signals=switch(i){case 1->Map.of("x0",NativeLibraryStartupTest.status(h[1],"x0",LIMITS,h[0],"x0",LIMITS));case 2->Map.of("x0",NativeLibraryStartupTest.status(h[2],"x0",LIMITS,h[1],"x1",LIMITS));case 3->Map.of("x0",NativeLibraryStartupTest.status(h[3],"x0",LIMITS,h[0],"x1",LIMITS));
                    case 4->Map.of("x0",NativeLibraryStartupTest.status(h[4],"x0",LIMITS,h[3],"x1",LIMITS),
                        "x1",NativeLibraryStartupTest.status(h[4],"x1",LIMITS,h[5],"x0",LIMITS));case 5->Map.of("x0",NativeLibraryStartupTest.status(h[5],"x0",LIMITS,h[4],"x1",LIMITS));default->Map.of();};
                if (!measureRounds) h[i].tick(LIMITS,true,signals);
                else if (h[i].beginFlowTick(ticks,LIMITS,true)) {
                    observe(i);
                    for (int round=0;round<LIMITS.hardware().workPerSlice();round++) {
                        long spent=h[i].advanceFlowRound(ticks,signals,LIMITS,true);
                        observe(i);
                        if (spent==0) break;
                    }
                }
            }
            exchange(0,"x0",1,"x0");exchange(1,"x1",2,"x0");exchange(0,"x1",3,"x0");if(!holdPanel) exchange(3,"x1",4,"x0");if (!holdReply || selections.isEmpty()) exchangeSelection();
            network.tick(world,devices,config.communication(false),config.serialMedium(false));
            if(reader.status("s0").receive().frames()>0){
                var received=reader.prepareReceive("s0");var packet=(DataValue.RecordValue)received.result().field("value");commands.add(packet);
                var protocol=new ReaderSamplingProtocol(identity,session,address,Set.of(BuiltinReadComponents.INVENTORY_ITEM_TAGS));
                var reply=protocol.respond(!mode.equals("denied"),packet,new ReadLimits(4096,32768,65536),ids->{fail("No implicit full read");return null;},(id,range)-> {
                    var actual=fixture.capture(id,range);
                    return mode.equals("malformed")?record("not_a_page",true):actual;
                }).orElseThrow();
                rangeReply=reply;
                int encoded=SerialRecordCodec.encode(reply,config.serialCodec(false)).length;
                assertTrue(encoded<=4096);
                readerFrames.add(encoded);
                assertTrue(received.replying(reply).publish());
            }
            for(int i=0;i<6;i++) observe(i);
            h[5].flowSnapshot().orElseThrow().receipts().stream().filter(r->r.payload().fields().keySet().equals(Set.of("i","t","n","c","p"))).forEach(selections::add);
            ticks++;
        }
        void observe(int i){
            var saved=h[i].flowSnapshot().orElseThrow();
            if(i!=3 || !mode.equals("malformed"))
                assertNotEquals(FlowRuntime.Status.FAILED,saved.state().status(),"owner="+i+" "+saved.state().diagnostic());
            peaks[i]=Math.max(peaks[i],ownerUnits(saved,LIMITS));
            assertTrue(peaks[i]<=LIMITS.hardware().memoryUnits());
            for(var task:saved.tasks().contexts())taskWork[i]=Math.max(taskWork[i],task.work());
        }
        void exchange(int a,String ap,int b,String bp){
            if(staggered){
                // Same all-peers-paused dispatch seam as the real ComputerExecutionCycle.
                var link=pairNetwork(a,ap,b,bp);
                if(link.rejectPaused(NativeLibraryStartupTest.pin(h[a],ap))==XBusExchange.Status.PAUSED
                    || link.rejectPaused(NativeLibraryStartupTest.pin(h[b],bp))==XBusExchange.Status.PAUSED) return;
            }
            NativeLibraryServiceSerialTest.exchange(h[a],ap,LIMITS,h[b],bp,LIMITS,history);
        }
        XBusAccess.Network pairNetwork(int a,String ap,int b,String bp){
            return XBusAccess.network(List.of(NativeLibraryServiceSerialTest.access(h[a],ap,LIMITS,history),
                NativeLibraryServiceSerialTest.access(h[b],bp,LIMITS,history)));
        }
        void resumeWithGap(int owner,int gap){
            if(owner==4) assertEquals("ok",action(ComputerControlSession.Action.RESUME,record()).code());
            else h[owner].setPaused(false,LIMITS,true);
            for(int i=0;i<gap;i++) step();
        }
        XBusAccess.Network selectionNetwork(){
            return pairNetwork(4,"x1",5,"x0");
        }
        void exchangeSelection(){
            var result=selectionNetwork().exchange(NativeLibraryStartupTest.pin(h[4],"x1"),NativeLibraryStartupTest.pin(h[5],"x0"));
            assertFalse(Set.of(XBusExchange.Status.UNAVAILABLE,XBusExchange.Status.RECOVERY_REQUIRED,XBusExchange.Status.HISTORY_CONFLICT,XBusExchange.Status.MEMORY_LIMIT,XBusExchange.Status.ARCHIVE_LIMIT).contains(result),result.toString());
        }
        FlowBlockHost.Saved terminalState(){return h[4].flowSnapshot().orElseThrow();}
        FlowRuntime.Checkpoint task(int index){return terminalState().tasks().contexts().get(index);}
        boolean finished(int index){return Set.of(FlowRuntime.Status.COMPLETE,FlowRuntime.Status.FAILED).contains(task(index).status());}
        DataValue.RecordValue result(int index){return assertInstanceOf(DataValue.RecordValue.class,task(index).locals().field("result"));}
        List<TerminalProjection.View> projection(){
            var pending=new ArrayDeque<TerminalProjection.View>();
            pending.push(TerminalProjection.project(observed,Map.of(),20,200));
            var found=new ArrayList<TerminalProjection.View>();
            while(!pending.isEmpty()){var v=pending.pop();found.add(v);for(int i=v.children().size()-1;i>=0;i--)pending.push(v.children().get(i));}
            return found;
        }
        void await(java.util.function.BooleanSupplier condition){for(int i=0;i<10000&&!condition.getAsBoolean();i++)step();assertTrue(condition.getAsBoolean(),()->"Application stalled "+Arrays.stream(h).map(x->x.flowSnapshot().orElseThrow().state()).map(s->s.status()+" pc="+s.assembly().orElseThrow().pc()+" diag="+s.diagnostic()).toList()+" T tasks="+terminalState().tasks().contexts().stream().map(s->s.status()+" pc="+s.assembly().orElseThrow().pc()+" diag="+s.diagnostic()).toList());}
        void page(){await(()->h[4].flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.COMPLETE);assertEquals(2,commands.size());assertEquals(List.of(5,6),fixture.slots());assertTrue(selections.isEmpty());}
        ComputerControlSession.Answer action(ComputerControlSession.Action action,DataValue.RecordValue input){return control.apply(nonce,++intent,terminal.computer.programRevision(),action,()->input,terminal.computer).orElseThrow();}
        TerminalStatusView read(){var before=h[4].flowSnapshot();var answer=action(ComputerControlSession.Action.READ_TERMINAL,record());assertEquals("ok",answer.code(),answer.detail());view=intent;observed=TerminalStatusView.decode(answer.dedicatedStatus().orElseThrow());assertEquals(before,h[4].flowSnapshot());return observed;}
        String button(int row){return projection().stream().filter(v->v.node().equals("select")).toList().get(row).instance();}
        ComputerControlSession.Answer click(int row){String instance=button(row);var resolved=TerminalInteraction.resolve(observed.terminal().orElseThrow(),observed.roots(),instance);assertEquals(ReaderSelectionDocuments.CHOOSE,((TerminalTarget.Task)resolved.target()).procedure());return action(ComputerControlSession.Action.TERMINAL_CLICK,new TerminalClick(view,instance).encode());}
        void coldTerminal(ComputerHostLimits limits)throws Exception{
            terminal=terminal.cold(limits);h[4]=terminal.host();
            resetSession();
        }
        void resetSession(){nonce=UUID.randomUUID();control=new ComputerControlSession(nonce);intent=0;view=0;observed=null;}
        void cold()throws Exception{
            for(int i=0;i<6;i++)if(i!=4)h[i]=NativeLibraryOwnersTest.reload(h[i],LIMITS,i==1?wireB:null);
            terminal=terminal.cold();h[4]=terminal.host();history=XBusHistorySavedData.load(history.save(new CompoundTag(),RegistryAccess.EMPTY),RegistryAccess.EMPTY);
            resetSession();
        }
    }
    @Test void actualPageAndTerminalClickChooseExactlyOneOriginalMaterialWithCompleteComponents()throws Exception{
        var input=material("minecraft:stone",null);var rig=new Rig(input);rig.measureRounds=true;rig.page();rig.read();rig.read();
        for(int i=0;i<10;i++)rig.step();assertTrue(rig.selections.isEmpty(),"Opening and refreshing never selects");
        var panel=(DataValue.RecordValue)rig.h[4].flowSnapshot().orElseThrow().state().locals().field("panel");var rows=(DataValue.ListValue)panel.field("r");assertEquals(2,rows.values().size());
        var first=assertInstanceOf(DataValue.RecordValue.class,rows.values().getFirst());
        assertEquals(input,first.field("m"));
        assertEquals(new DataValue.IntegerValue(5),((DataValue.RecordValue)first.field("p")).field("slot"));
        var request=record("i",input.field("item"),"t",input.field("tag"),"n",input.field("count"),"c",input.field("components"),"p",first.field("p"));
        rig.cold();rig.read();var main=rig.h[4].flowSnapshot().orElseThrow().state();assertEquals("ok",rig.click(0).code());assertEquals(main,rig.h[4].flowSnapshot().orElseThrow().state());
        rig.holdReply=true;
        rig.await(()->rig.task(0).status()==FlowRuntime.Status.WAITING_READ);rig.cold();
        rig.holdReply=false;
        rig.await(()->rig.finished(0));assertEquals(FlowRuntime.Status.COMPLETE,rig.task(0).status(),rig.task(0).diagnostic().toString());
        var task=rig.h[4].flowSnapshot().orElseThrow().tasks().contexts().getFirst();var result=(DataValue.RecordValue)task.locals().field("result");assertEquals(new DataValue.TextValue("ok"),result.field("c"));assertEquals(record("item","minecraft:stone","count",3,"components",COMPONENTS),result.field("i"));
        assertEquals(1,rig.selections.size());assertEquals(request,rig.selections.iterator().next().payload());assertEquals(input,rig.h[3].flowSnapshot().orElseThrow().initialInputs().field("material"));assertEquals(7,rig.fixture.seedCount());assertEquals(panel,rig.h[4].flowSnapshot().orElseThrow().state().locals().field("panel"));
        rig.read();assertEquals("ok",rig.action(ComputerControlSession.Action.TERMINAL_CLAIM,new TerminalTaskClaim(rig.view,0).encode()).code());assertTrue(rig.h[4].flowSnapshot().orElseThrow().tasks().contexts().isEmpty());assertEquals("invalid",rig.action(ComputerControlSession.Action.TERMINAL_CLAIM,new TerminalTaskClaim(rig.view,0).encode()).code());
        for(int i=0;i<20;i++)rig.step();assertEquals(2,rig.commands.size());assertEquals(1,rig.selections.size());
        System.out.println("READER_SELECTION_CONNECTED ticks="+rig.ticks+" peaks="+Arrays.toString(rig.peaks)+" mainWork="+Arrays.stream(rig.h).map(x->x.flowSnapshot().orElseThrow().state().work()).toList()+" taskMaxWork="+Arrays.toString(rig.taskWork)+" frames="+rig.readerFrames);
    }
    @Test void deniedRawReplyAndAnActuallyEmptyPageHaveNoSelectableRows() throws Exception {
        for(String mode:List.of("denied","empty")){
            var rig=new Rig(material("minecraft:stone",null),mode);
            rig.await(()->rig.terminalState().state().status()==FlowRuntime.Status.COMPLETE);
            rig.read();
            var output=(DataValue.RecordValue)rig.observed.roots().field("output");
            var panel=(DataValue.RecordValue)output.field("panel");
            assertEquals(rig.rangeReply,panel.field("e"));
            assertEquals(new DataValue.TextValue(mode.equals("denied")?"denied":"ok"),rig.rangeReply.field("code"));
            assertEquals(new DataValue.ListValue(List.of()),panel.field("r"));
            assertTrue(rig.projection().stream().noneMatch(v->v.node().equals("select")));
            assertEquals(mode.equals("denied")?1:2,rig.commands.size());
            assertTrue(rig.fixture.slots().isEmpty());
            assertTrue(rig.selections.isEmpty());
        }
    }

    @Test void malformedPurportedSuccessFailsBeforePublishingAnyPanel() throws Exception {
        var rig=new Rig(material("minecraft:stone",null),"malformed");
        rig.await(()->rig.h[3].flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.FAILED);
        var state=rig.h[3].flowSnapshot().orElseThrow().state();
        assertTrue(state.diagnostic().isPresent());
        assertEquals(2,rig.commands.size());
        assertEquals(new DataValue.TextValue("ok"),rig.rangeReply.field("code"));
        rig.read();
        assertEquals(N,rig.observed.roots().field("output"));
        assertTrue(rig.selections.isEmpty());
    }

    @Test void actualAirAndWrongTagDoNotResolveAndOldOrUsedViewDoesNotClick() throws Exception {
        var rig=new Rig(material(null,"minecraft:planks"));rig.page();rig.read();
        long stale=rig.view;String first=rig.button(0);rig.read();
        assertEquals("invalid",rig.action(ComputerControlSession.Action.TERMINAL_CLICK,new TerminalClick(stale,first).encode()).code());
        assertTrue(rig.terminalState().tasks().contexts().isEmpty());
        rig.read(); assertEquals("ok",rig.click(0).code());assertEquals("invalid",rig.click(0).code());
        rig.await(()->rig.finished(0));
        assertEquals(record("c","tag_mismatch","i",N),rig.result(0));
        rig.read();assertEquals("ok",rig.click(1).code());
        rig.await(()->rig.finished(1));
        assertEquals(record("c","tag_mismatch","i",N),rig.result(1),"Air has no tags and is not a selectable planks match");
        assertEquals(2,rig.selections.size());
        assertEquals(7,rig.fixture.seedCount());
    }

    @Test void freshDifferentRowWhileReplyPendingCannotReceiveFirstRowsSuccess() throws Exception {
        var rig=new Rig(material("minecraft:stone",null));rig.page();rig.read();rig.holdReply=true;
        assertEquals("ok",rig.click(0).code());
        rig.await(()->rig.task(0).status()==FlowRuntime.Status.WAITING_READ);
        rig.read(); assertEquals("ok",rig.click(1).code());
        for(int i=0;i<30;i++) rig.step();
        assertTrue(rig.finished(1),"Overlapping click must be rejected explicitly, not wait on the first candidate's pin");
        assertNotEquals(FlowRuntime.Status.FAILED,rig.task(0).status());
        assertEquals(1,rig.selections.size());
        rig.holdReply=false;rig.await(()->rig.finished(0));
        assertEquals(new DataValue.TextValue("ok"),rig.result(0).field("c"));
        assertEquals(record("c","busy","i",N),rig.result(1));
    }
    @Test void twoFreshClicksBeforeNextActivityCannotAliasTheUncorrelatedServiceReply() throws Exception {
        var rig=new Rig(material("minecraft:stone",null));rig.page();
        rig.read();assertEquals("ok",rig.click(0).code());
        rig.read();assertEquals("ok",rig.click(1).code());
        rig.await(()->rig.finished(0)&&rig.finished(1));
        assertEquals(new DataValue.TextValue("ok"),rig.result(0).field("c"));
        assertEquals(record("c","busy","i",N),rig.result(1),"Air row must never be reported as a success using the stone row's reply");
        assertEquals(1,rig.selections.size(),"Concurrent selection is rejected, not queued automatically");
    }
    @Test void terminalClickAtExactTaskAdmissionBudgetAndOneLess() throws Exception {
        var rig=new Rig(material("minecraft:stone",null));rig.page();
        var before=rig.terminalState();
        rig.read();assertEquals("ok",rig.click(0).code());
        long candidate=ownerUnits(rig.terminalState(),LIMITS);
        // A separate normal page ensures the reduced budget never borrows a task or result from the reference.
        var constrained=new Rig(material("minecraft:stone",null));constrained.page();
        assertEquals(ownerUnits(before,LIMITS),ownerUnits(constrained.terminalState(),LIMITS));
        constrained.coldTerminal(terminalLimits(candidate-1,LIMITS.taskSlots()));
        var lowBefore=constrained.terminalState();constrained.read();
        var rejected=constrained.click(0);
        assertEquals("memory_limit",rejected.code(),rejected.detail());
        assertEquals(lowBefore,constrained.terminalState());
        assertTrue(constrained.selections.isEmpty());
        constrained.coldTerminal(terminalLimits(candidate,LIMITS.taskSlots()));
        constrained.read();
        var accepted=constrained.click(0);
        assertEquals("ok",accepted.code(),accepted.detail());
        assertEquals(candidate,ownerUnits(constrained.terminalState(),LIMITS));
        System.out.println("READER_SELECTION_TASK_ADMISSION retained_before="+ownerUnits(before,LIMITS)+" exact="+candidate+" rejected="+(candidate-1));
    }

    @Test void exhaustedTaskSlotRejectsFreshClickBeforeAnyNewRequest() throws Exception {
        var rig=new Rig(material("minecraft:stone",null));rig.page();
        rig.coldTerminal(terminalLimits(LIMITS.hardware().memoryUnits(),2));
        rig.read();assertEquals("ok",rig.click(0).code());
        var before=rig.terminalState();
        rig.read();
        var blocked=rig.click(1);
        assertEquals("busy",blocked.code(),blocked.detail());
        assertEquals(before,rig.terminalState());
        assertTrue(rig.selections.isEmpty(),"A slot-limit rejection never starts another request");
    }
    @Test void transportFaultKeepsPublishedGateAcrossRestoreUntilExplicitRecovery() throws Exception {
        var rig=new Rig(material("minecraft:stone",null));rig.page();rig.read();rig.holdReply=true;
        assertEquals("ok",rig.click(0).code());
        rig.await(()->rig.task(0).status()==FlowRuntime.Status.WAITING_READ);
        rig.h[5].setPaused(true,LIMITS,true);
        assertEquals(XBusExchange.Status.PAUSED,
            rig.selectionNetwork().rejectPaused(NativeLibraryStartupTest.pin(rig.h[4],"x1")));
        rig.await(()->rig.finished(0));
        assertEquals(FlowRuntime.Status.FAILED,rig.task(0).status());
        rig.cold();var status=rig.read();
        assertEquals(new DataValue.BooleanValue(true),((DataValue.RecordValue)status.roots().field("memory")).field("choice_in_flight"));
        var task=(DataValue.RecordValue)((DataValue.ListValue)status.roots().field("tasks")).values().getFirst();
        assertNotEquals(N,task.field("failure"));
        assertEquals("ok",rig.click(1).code());
        rig.await(()->rig.finished(1));
        assertEquals(record("c","busy","i",N),rig.result(1));
        assertEquals(1,rig.selections.size(),"Uncorrelated old reply must not be reused by a newer choice");
    }
    @Test void downstreamFirstStaggeredResumeSurvivesPausedPeersThenShowsTheSameRealPage() throws Exception {
        var rig=new Rig(material("minecraft:stone",null),"staggered");
        for(int owner:new int[]{2,1,5,4,3}) rig.resumeWithGap(owner,40);
        assertTrue(rig.h[0].flowSnapshot().orElseThrow().state().paused());
        assertTrue(rig.commands.isEmpty());
        assertEquals(N,rig.read().roots().field("output"));
        assertTrue(rig.selections.isEmpty());
        rig.resumeWithGap(0,1);rig.page();rig.read();
        assertEquals("ok",rig.click(0).code());
        rig.await(()->rig.finished(0));
        assertEquals(record("c","ok","i",record("item","minecraft:stone","count",3,"components",COMPONENTS)),rig.result(0));
        assertEquals(1,rig.selections.size());
    }

    @Test void preparerAloneDoesNotClaimAnAwaitWhileItsEmitterIsPaused() throws Exception {
        var rig=new Rig(material("minecraft:stone",null),"staggered");
        rig.resumeWithGap(3,80);
        assertTrue(rig.h[0].flowSnapshot().orElseThrow().state().paused());
        assertTrue(rig.terminalState().state().paused());
        assertTrue(rig.commands.isEmpty());
        assertTrue(rig.selections.isEmpty());
    }
    @Test void preparerPauseAfterTerminalReadAdmissionRemainsARealPeerFault() throws Exception {
        var rig=new Rig(material("minecraft:stone",null));rig.holdPanel=true;
        rig.await(()->rig.terminalState().state().status()==FlowRuntime.Status.WAITING_READ);
        assertEquals(FlowRuntime.Status.WAITING_WRITE,rig.h[3].flowSnapshot().orElseThrow().state().status());
        rig.h[3].setPaused(true,LIMITS,true);
        assertEquals(XBusExchange.Status.PAUSED,rig.pairNetwork(3,"x1",4,"x0").rejectPaused(NativeLibraryStartupTest.pin(rig.h[4],"x0")));
        assertEquals(FlowRuntime.Status.FAILED,rig.terminalState().state().status());
        assertEquals(ComputationException.Code.XBUS_PEER_PAUSED,rig.terminalState().state().diagnostic().orElseThrow().code());
        assertEquals(N,rig.read().roots().field("output"));
        assertEquals(2,rig.commands.size());
        assertTrue(rig.selections.isEmpty());
    }
}
