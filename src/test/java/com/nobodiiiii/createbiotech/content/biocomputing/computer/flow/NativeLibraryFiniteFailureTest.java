package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import java.util.*;
import java.util.function.*;
import net.minecraft.core.*;
import org.junit.jupiter.api.Test;

/** Public source/finite core coverage only: no world/book authorization or private RX injection. */
class NativeLibraryFiniteFailureTest {
    static final DataValue.RecordValue REQUEST = NativeLibraryFailureTest.packet();
    static class Rig {
        final ComputerHostLimits la = NativeLibraryOwnersTest.limits("cleric",5),
            lb = NativeLibraryOwnersTest.limits("cleric",5), lc = NativeLibraryOwnersTest.limits("cleric",5);
        final SerialDevice wireB = NativeLibraryOwnersTest.device(lb), wireL = NativeLibraryOwnersTest.device(lb);
        ComputerBlockHost a, b, c;
        final SerialNetwork network = new SerialNetwork();
        final BlockPos pb = new BlockPos(0,0,0), cable = new BlockPos(1,0,0), pl = new BlockPos(2,0,0);
        final Map<BlockPos,SerialDevice> devices = Map.of(pb,wireB,pl,wireL);
        boolean connected = true;
        int tick, outgoing, collisions;
        String lastTx = "idle";
        Consumer<Rig> beforeBRound;
        final long[] peakWork=new long[3];
        final CableDiscovery.World world = new CableDiscovery.World() {
            public boolean loaded(BlockPos p) { return true; }
            public CableDiscovery.Cell cell(BlockPos p) { return new CableDiscovery.Cell(p.equals(cable)&&connected?CableDiscovery.Kind.CABLE:devices.containsKey(p)?CableDiscovery.Kind.ENDPOINT:CableDiscovery.Kind.EMPTY,null); }
        };
        Rig(boolean fullQuery) {
            // Default master parser: 8 base CPU units × the global 8× execution multiplier.
            // The finite line, buffers, Sleep and disk still run on their own clocks.
            assertEquals(new ResourceLimits(256,65536,64),la.hardware());
            assertEquals(la.hardware(),lb.hardware()); assertEquals(la.hardware(),lc.hardware());
            wireB.configure("s0",Direction.EAST,true); wireL.configure("s0",Direction.WEST,true);
            b=NativeLibraryOwnersTest.install(NativeLibraryOwnersTest.document("b"),lb,wireB);
            c=NativeLibraryOwnersTest.install(NativeLibraryOwnersTest.document("c"),lc,null);
            if(fullQuery) a=NativeLibraryOwnersTest.install(NativeLibraryOwnersTest.document("a"),la,null);
            else { a=new ComputerBlockHost(()->{});a.installFlow(new FlowProgram(Map.of("packet",PACKET),List.of(new FlowProgram.Write("request","x0","packet"),new FlowProgram.Read("reply","x0","outcome",OUTCOME))).withAssembly(),record("packet",REQUEST),la,true); }
            a.setPaused(false,la,true);b.setPaused(false,lb,true);c.setPaused(false,lc,true);
            networkTick();
        }
        Map<String,Integer> samples(ComputerBlockHost h) {return h==b?Map.of("x0",NativeLibraryStartupTest.status(b,"x0",lb,a,"x0",la)):h==c?Map.of("x0",NativeLibraryStartupTest.status(c,"x0",lc,b,"x1",lb)):la.pins().unconnectedSignals();}
        void rounds(ComputerBlockHost h,ComputerHostLimits l,boolean hook) {
            var signals=samples(h);
            long before=state(h).work();
            if(!hook||beforeBRound==null)h.tick(tick,l,true,signals);
            else if(h.beginFlowTick(tick,l,true)) for(int i=0;i<l.hardware().workPerSlice();i++) {
                beforeBRound.accept(this);h=b;
                if(h.advanceFlowRound(tick,signals,l,true)==0) break;
            }
            long work=state(h).work()-before;assertTrue(work>=0&&work<=l.hardware().workPerSlice(),"No owner may gain another per-tick allowance");
            int member=h==a?0:hook?1:2;peakWork[member]=Math.max(peakWork[member],work);
            assertTrue(h.loaded(),h.diagnostic().toString());
            assertNotEquals(FlowRuntime.Status.FAILED,state(h).status(),state(h).diagnostic().toString());
        }
        void cpu() {
            tick++;rounds(a,la,false);rounds(b,lb,true);rounds(c,lc,false);
            a.exchange("x0",la,true,b,"x0",lb,true);b.exchange("x1",lb,true,c,"x0",lc,true);
        }
        void networkTick() {
            String tx=wireB.status("s0").transmit();if(tx.equals("queued")&&!lastTx.equals("queued"))outgoing++;lastTx=tx;
            for(var event:network.tick(world,devices,NativeLibraryOwnersTest.CONFIG.communication(false),NativeLibraryOwnersTest.CONFIG.serialMedium(false)))if(event.phase()==SerialNetwork.Phase.COLLISION)collisions++;
        }
        void step() { cpu();networkTick(); }
        void until(BooleanSupplier condition,int allowance) { for(int end=tick+allowance;!condition.getAsBoolean();){assertTrue(tick<end,()->"tick="+tick+" B="+NativeLibraryOwnersTest.summary(b)+" wire="+wireB.status("s0"));step();} }
        boolean complete() { return state(a).status()==FlowRuntime.Status.COMPLETE; }
        DataValue.RecordValue outcome() { return (DataValue.RecordValue)state(a).locals().field("outcome"); }
        DataValue.RecordValue takeCommand() {var received=wireL.prepareReceive("s0");assertEquals(new DataValue.TextValue("received"),received.result().field("status"));var p=(DataValue.RecordValue)received.result().field("value");assertTrue(received.publish());return p;}
        void awaitCommand() {until(()->wireL.status("s0").receive().frames()>0,2500);assertEquals(1,outgoing);assertEquals(REQUEST,takeCommand());}
        void send(DataValue frame) {var prepared=wireL.prepareSend("s0",frame);assertEquals(SerialResult.send("queued"),prepared.result());assertTrue(prepared.publish());}
        boolean bNative(RegisterSerial.Kind kind) {var s=state(b);if(s.assembly().isEmpty())return false;int pc=s.assembly().orElseThrow().pc();var image=FlowXBus.image(b.flowSnapshot().orElseThrow().program());return pc<image.code().size()&&image.code().get(pc).instruction() instanceof RegisterSerial.Instruction op&&op.kind()==kind;}
    }
    static FlowRuntime.Checkpoint state(ComputerBlockHost h) {return h.flowSnapshot().orElseThrow().state();}
    static boolean bool(DataValue v) {return ((DataValue.BooleanValue)v).value();}
    static DataValue.RecordValue reply() {return record("kind","library_reply","target",REQUEST.field("target"),"request",REQUEST.field("request"),"code","idle","sequence",0,"value",record("answer",43));}

    @Test void finiteUnrelatedAndStaleFramesAreIgnoredAcrossPollingRestoreWithoutResending() {
        var r=new Rig(false);r.awaitCommand();
        var noise=new ArrayList<DataValue.RecordValue>();noise.add(record("ordinary","record"));
        for(String missing:List.of("kind","target","request","code","sequence","value")){var fields=new LinkedHashMap<>(reply().fields());fields.remove(missing);noise.add(new DataValue.RecordValue(fields));}
        for(String stale:List.of("kind","target","request")){var fields=new LinkedHashMap<>(reply().fields());fields.put(stale,stale.equals("target")?record("x",99,"y",0,"z",0):new DataValue.TextValue("stale"));noise.add(new DataValue.RecordValue(fields));}
        int[] received={0};boolean[] restored={false};var inspected=new HashSet<DataValue>();
        r.beforeBRound=x->{var p=state(x.b).assembly().flatMap(RegisterState::serial);if(!restored[0]&&p.map(RegisterSerial.Progress::receiving).orElse(false)) {var packet=state(x.b).locals().field("packet");x.b=NativeLibraryOwnersTest.reload(x.b,x.lb,x.wireB);assertEquals(REQUEST,packet);assertEquals(packet,state(x.b).locals().field("packet"));restored[0]=true;}if(x.bNative(RegisterSerial.Kind.STATUS)&&state(x.b).locals().fields().get("received") instanceof DataValue.RecordValue rx&&bool(rx.field("ok"))) {assertFalse(bool(((DataValue.RecordValue)state(x.b).locals().field("match")).field("m")));assertFalse(bool(state(x.b).locals().field("matched")));assertEquals(REQUEST,((DataValue.RecordValue)state(x.b).locals().field("context")).field("p"));inspected.add(rx.field("value"));}};
        // A public receive claim and C's completed match prove each actual finite frame was inspected.
        for(var frame:noise) {
            r.send(frame);r.until(()->inspected.contains(frame),2200);
            received[0]++;assertFalse(r.complete());assertEquals(1,r.outgoing);
            r.until(()->r.wireB.status("s0").receive().frames()==0&&!Set.of("queued","transmitting").contains(r.wireL.status("s0").transmit()),500);
        }
        r.beforeBRound=null;r.send(reply());r.until(r::complete,2500);assertEquals(10,received[0]);assertTrue(restored[0]);assertTrue(bool(r.outcome().field("ok")));assertEquals(reply(),r.outcome().field("value"));assertEquals(1,r.outgoing);assertEquals(0,r.wireL.status("s0").receive().frames());
        System.out.println("unrelated frames inspected="+received[0]+" RX progress restored="+restored[0]+" original correlation="+REQUEST.field("request")+" originating commands="+r.outgoing+" peak work/tick="+Arrays.toString(r.peakWork));
    }
    @Test void matchingReplyWinsRealCableFaultButUnrelatedReplyFailsOnlyAfterSleep() {
        for(boolean matching:List.of(true,false)) {
            var r=new Rig(false);r.awaitCommand();var frame=matching?reply():record("ordinary","not-a-reply");
            int[] fault={-1},sleep={-1};long[] remaining={0};DataValue[] observedStatus={null};
            r.beforeBRound=x->{
                if(fault[0]<0&&state(x.b).locals().fields().get("received") instanceof DataValue.RecordValue received&&bool(received.field("ok"))
                        &&state(x.b).locals().fields().get("match") instanceof DataValue.RecordValue match) {
                    assertEquals(frame,received.field("value"));assertEquals(0,x.wireB.status("s0").receive().frames());
                    assertEquals(matching,bool(match.field("m")));
                    x.connected=false;x.networkTick();assertNotEquals("ready",x.wireB.status("s0").connection());fault[0]=x.tick;
                }
                if(fault[0]>=0&&sleep[0]<0&&state(x.b).status()==FlowRuntime.Status.SLEEPING) {
                    assertFalse(x.complete());assertTrue(state(x.b).sleepTicks()>0);sleep[0]=x.tick;remaining[0]=state(x.b).sleepTicks();
                    if(!matching)observedStatus[0]=state(x.b).locals().field("serial");
                }
            };
            r.send(frame);r.until(r::complete,3500);assertTrue(fault[0]>0);assertTrue(sleep[0]>=fault[0]);assertTrue(r.tick-sleep[0]>=remaining[0],"The retained 20-tick Sleep must elapse before returning");
            assertEquals(matching,bool(r.outcome().field("ok")));assertEquals(matching?frame:observedStatus[0],r.outcome().field("value"));assertEquals(1,r.outgoing);assertEquals(0,r.wireL.status("s0").receive().frames());
            if(!matching)assertNotEquals(frame,r.outcome().field("value"));
            System.out.println("fault match="+matching+" fault tick="+fault[0]+" sleep observed="+sleep[0]+" remaining="+remaining[0]+" return tick="+r.tick+" originating commands="+r.outgoing+" peak work/tick="+Arrays.toString(r.peakWork));
        }
    }

    static final class SleepProbe {
        int started=-1;long remaining;DataValue status;
        void observe(Rig r) {if(started<0&&state(r.b).status()==FlowRuntime.Status.SLEEPING) {assertFalse(r.complete());started=r.tick;remaining=state(r.b).sleepTicks();assertTrue(remaining>0);status=state(r.b).locals().field("serial");}}
        void requireElapsed(Rig r) {assertTrue(started>=0);assertTrue(r.tick-started>=remaining);assertEquals(status,r.outcome().field("value"));assertTrue(SerialResult.STATUS.accepts(status));}
    }
    @Test void overlappingFiniteSendsCollideWithoutDeliveringOrRetryingTheCommand() {
        var r=new Rig(false);boolean collided=false;
        for(int i=0;i<2500&&!collided;i++) {
            r.cpu();
            if(r.wireB.status("s0").transmit().equals("queued")) {r.send(record("contender","overlapping"));r.networkTick();collided=true;}
            else r.networkTick();
        }
        assertTrue(collided);assertTrue(r.collisions>0,"The real channel must report overlap, not a synthetic fault");
        var sleep=new SleepProbe();r.beforeBRound=x->{var serial=state(x.b).locals().fields().get("serial");if(serial instanceof DataValue.RecordValue status&&status.field("failure").equals(new DataValue.TextValue("collision")))sleep.observe(x);};
        r.until(r::complete,3000);assertFalse(bool(r.outcome().field("ok")));sleep.requireElapsed(r);
        assertEquals("collision",r.wireB.status("s0").failure());assertEquals("collision",r.wireL.status("s0").failure());assertEquals(1,r.outgoing);
        assertEquals(SerialResult.receive("empty",null),r.wireL.prepareReceive("s0").result());
        for(int i=0;i<100;i++)r.step();assertEquals(1,r.outgoing);assertEquals(0,r.wireL.status("s0").receive().frames(),"No command reaches a Library admission after collision or is retried");
        System.out.println("collision intervals="+r.collisions+" commands sent="+r.outgoing+" delivered=0 sleep remaining="+sleep.remaining);
    }

    @Test void emptyReceiveKeepsPollingButAnActualForeignFrameDecodeErrorEndsAfterSleep() {
        var r=new Rig(false);r.awaitCommand();var emptyPolls=new HashSet<Long>();DataValue.RecordValue[] receiveError={null};
        int[] sleepStarted={-1};long[] sleepRemaining={0};
        r.beforeBRound=x->{
            if(state(x.b).locals().fields().get("received") instanceof DataValue.RecordValue received&&!bool(received.field("ok"))) {
                String status=((DataValue.TextValue)received.field("status")).value();
                if(status.equals("empty")) {if(state(x.b).status()==FlowRuntime.Status.SLEEPING)emptyPolls.add(state(x.b).work());}
                else receiveError[0]=received;
            }
            if(receiveError[0]!=null&&sleepStarted[0]<0&&state(x.b).status()==FlowRuntime.Status.SLEEPING) {
                assertFalse(x.complete());sleepStarted[0]=x.tick;sleepRemaining[0]=state(x.b).sleepTicks();assertTrue(sleepRemaining[0]>0);
            }
        };
        r.until(()->emptyPolls.size()>=2,1500);assertFalse(r.complete());assertEquals(1,r.outgoing);
        // A valid native compound is sent publicly; B intentionally expects the typed-record format.
        r.send(new DataValue.NbtValue(10,record("foreign",new DataValue.NbtValue(8,new DataValue.TextValue("native-compound")))));
        r.until(r::complete,2200);assertNotNull(receiveError[0]);assertFalse(bool(r.outcome().field("ok")));
        assertTrue(sleepStarted[0]>=0);assertTrue(r.tick-sleepStarted[0]>=sleepRemaining[0]);assertEquals(1,r.outgoing);
        assertEquals(1,r.wireB.status("s0").receive().frames(),"A failed decoder does not silently consume/retry the foreign frame");
        var returned=(DataValue.RecordValue)r.outcome().field("value");
        assertEquals(receiveError[0],returned,"Return the actual receive error, not a healthy line-status snapshot");
        assertTrue(SerialResult.RECEIVE.accepts(returned));assertNotEquals(new DataValue.TextValue("empty"),returned.field("status"));
        for(int i=0;i<100;i++)r.step();assertEquals(1,r.outgoing);assertEquals(1,r.wireB.status("s0").receive().frames());
        System.out.println("empty polls="+emptyPolls.size()+" returned receive error="+returned+" sleep remaining="+sleepRemaining[0]);
    }

    /** Independent positioned source fixture: not an actual book/grant/authorization test. */
    static final class SourceFixture implements LibraryReadHead.Source {
        int reads,verifies;boolean failAdmission,failCompletion;
        public void validateTrack(LibraryTrackAddress t) {assertEquals(new LibraryTrackAddress(1,0,0),t);}
        public LibraryReadHead.Sample read(LibraryTrackAddress t,int slot,String entry) {
            reads++;assertEquals(2,slot);assertEquals("reading",entry);
            if(failAdmission)throw new LibraryBookAccess.Failure("access_denied");
            return new LibraryReadHead.Sample(record("book",17),record("value",43));
        }
        public void verify(LibraryTrackAddress t,int slot,String entry,DataValue.RecordValue fence) {
            verifies++;assertEquals(record("book",17),fence);
            if(failCompletion)throw new LibraryBookAccess.Failure("medium_changed");
        }
    }
    static final class DriveRig extends Rig {
        final SourceFixture source=new SourceFixture();
        final LibraryDriveLimits drive;
        LibraryReadHead.State head=LibraryReadHead.State.empty(),beforeDenied;
        final Map<String,Integer> commands=new HashMap<>(),accepted=new HashMap<>();
        final Set<DataValue> requests=new HashSet<>();
        int busy;boolean retainedBeforeAck;
        DriveRig(boolean slow) {super(true);var d=NativeLibraryOwnersTest.CONFIG.libraryDrive(false);drive=slow?new LibraryDriveLimits(2000,d.seekTicksPerBlock(),d.selectTicks(),d.readBaseTicks(),d.readBytesPerTick(),d.bufferBytes(),d.archive()):d;}
        @Override void step() {
            super.step();head=LibraryReadHead.advance(head,source,drive);
            if(wireL.status("s0").receive().frames()>0&&!Set.of("queued","transmitting").contains(wireL.status("s0").transmit())) {
                var cmd=LibraryDriveCommand.decode(takeCommand());assertTrue(requests.add(cmd.request()),"Every busy result query needs a fresh originating correlation");commands.merge(cmd.operation(),1,Integer::sum);
                if(!cmd.operation().equals("status"))assertEquals(head.sequence(),cmd.sequence(),"Polling and acknowledgement must preserve the retained mechanical sequence");
                if(cmd.operation().equals("ack")&&head.sequence()==3) {assertEquals("read",head.result().orElseThrow().code());assertEquals(record("value",43),state(a).locals().field("read_result"));retainedBeforeAck=true;}
                DataValue.RecordValue response;var before=head;
                try {var next=cmd.apply(head,source,drive);head=next.state();response=next.reply();if(response.field("code").equals(new DataValue.TextValue("accepted")))accepted.merge(cmd.operation(),1,Integer::sum);}
                catch(LibraryBookAccess.Failure error) {
                    if(error.code().equals("busy")) {assertEquals("result",cmd.operation(),"Only busy result polling may repeat");busy++;assertTrue(LibraryReadHead.busy(head));}
                    if(error.code().equals("access_denied")) {assertEquals("read",cmd.operation());beforeDenied=before;assertEquals(before,head);}
                    response=cmd.reply(head.sequence(),error.code(),DataValue.NullValue.INSTANCE);
                }
                send(response);
            }
        }
        void finish() {until(this::complete,14000);assertEquals(commands.values().stream().mapToInt(Integer::intValue).sum(),outgoing);assertEquals(outgoing,requests.size());}
    }
    @Test void actualMechanicalBusyRepeatsOnlyFreshResultQueriesAndRetainsReadBeforeFinalAck() {
        var r=new DriveRig(true);r.finish();assertTrue(r.busy>0,"Slow fixture must actually return busy, not merely make it possible");
        assertEquals(Map.of("seek",1,"select",1,"read",1),r.accepted);assertEquals(1,r.commands.get("status"));assertEquals(1,r.commands.get("seek"));assertEquals(1,r.commands.get("select"));assertEquals(1,r.commands.get("read"));assertEquals(3,r.commands.get("ack"));assertEquals(3+r.busy,r.commands.get("result"));
        assertTrue(bool(state(r.a).locals().field("success")));assertEquals(record("value",43),state(r.a).locals().field("read_result"));assertEquals(DataValue.NullValue.INSTANCE,state(r.a).locals().field("error"));assertTrue(r.retainedBeforeAck);assertEquals(3,r.head.sequence());assertTrue(r.head.result().isEmpty());assertEquals(1,r.source.reads);assertEquals(1,r.source.verifies);
        System.out.println("mechanical busy="+r.busy+" commands="+r.commands+" accepted="+r.accepted+" fresh requests="+r.requests.size()+" sequence="+r.head.sequence()+" retained before ack="+r.retainedBeforeAck+" peak work/tick="+Arrays.toString(r.peakWork));
    }

    @Test void sourceAdmissionAndCompletionFailuresRemainBusinessFailuresWithoutRetryOrAck() {
        for(boolean admission:List.of(true,false)) {
            var r=new DriveRig(false);r.source.failAdmission=admission;r.source.failCompletion=!admission;r.finish();
            String code=admission?"access_denied":"medium_changed";
            assertEquals(1,r.source.reads);assertEquals(admission?0:1,r.source.verifies);assertFalse(bool(state(r.a).locals().field("success")));assertEquals(DataValue.NullValue.INSTANCE,state(r.a).locals().field("read_result"));
            var error=(DataValue.RecordValue)state(r.a).locals().field("error");assertEquals(new DataValue.TextValue(code),error.field("code"));assertEquals(DataValue.NullValue.INSTANCE,error.field("value"));assertEquals(new DataValue.IntegerValue(admission?2:3),error.field("sequence"));
            assertEquals(1,r.commands.get("read"));assertEquals(2,r.commands.get("ack"));assertEquals(1,r.accepted.get("seek"));assertEquals(1,r.accepted.get("select"));assertEquals(admission?0:1,r.accepted.getOrDefault("read",0));assertEquals(admission?2:3,r.head.sequence());assertTrue(r.head.pending().isEmpty());assertFalse(r.retainedBeforeAck);
            if(admission) {assertNotNull(r.beforeDenied);assertEquals(r.beforeDenied,r.head);assertTrue(r.head.result().isEmpty(),"Rejected admission must not invent a retained mechanical operation/result");}
            else {assertEquals("medium_changed",r.head.result().orElseThrow().code());assertEquals(DataValue.NullValue.INSTANCE,r.head.result().orElseThrow().value());}
            var retained=r.head;var commands=Map.copyOf(r.commands);int sent=r.outgoing;
            for(int i=0;i<100;i++)r.step();assertEquals(retained,r.head);assertEquals(commands,r.commands);assertEquals(sent,r.outgoing);
            System.out.println("source failure="+code+" reads="+r.source.reads+" verifies="+r.source.verifies+" commands="+r.commands+" accepted="+r.accepted+" sequence="+r.head.sequence()+" retained result="+r.head.result()+" peak work/tick="+Arrays.toString(r.peakWork));
        }
    }

}
