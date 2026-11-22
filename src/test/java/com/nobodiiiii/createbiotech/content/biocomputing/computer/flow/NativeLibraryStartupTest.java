package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.BiocomputingAuthoredLibraryReadGameTests.readyToRebuildRead;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryReadHead;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryTrackAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Public document startup policy; status comes from real, current pin observations. */
class NativeLibraryStartupTest {
    // Controlled device states test the rebuild gate, not physical book/device execution.
    static LibraryReadHead.State retainedResult(Optional<LibraryReadHead.Result> result) {
        return new LibraryReadHead.State(3,Optional.of(new LibraryTrackAddress(1,0,0)),2,Optional.empty(),result);
    }
    static LibraryReadHead.State retainedRead() {
        return retainedResult(Optional.of(new LibraryReadHead.Result("read",new DataValue.RecordValue(Map.of("value",new DataValue.IntegerValue(43))))));
    }
    @Test void rebuildRequiresCurrentReadAndBothOuterServiceYields() {
        var lb=NativeLibraryOwnersTest.limits("cleric",5);var lc=NativeLibraryOwnersTest.limits("cleric",5);
        var b=NativeLibraryOwnersTest.install(NativeLibraryOwnersTest.document("b"),lb,NativeLibraryOwnersTest.device(lb));
        var c=NativeLibraryOwnersTest.install(NativeLibraryOwnersTest.document("c"),lc,null);
        var beforeB=b.flowSnapshot().orElseThrow();var beforeC=c.flowSnapshot().orElseThrow();
        b.setPaused(false,lb,true);c.setPaused(false,lc,true);
        for(int tick=0;tick<10;tick++) {b.tick(tick,lb,true,Map.of("x0",0));c.tick(tick,lc,true,Map.of("x0",0));}
        var idleB=b.flowSnapshot().orElseThrow();var idleC=c.flowSnapshot().orElseThrow();
        for(var idle:List.of(idleB,idleC)) {
            assertEquals(FlowRuntime.Status.SLEEPING,idle.state().status());
            assertEquals("service_yield",idle.program().steps().get(idle.state().cursor()).sourceId());
            assertTrue(idle.state().calls().frames().isEmpty());
            assertTrue(idle.state().assembly().orElseThrow().calls().orElseThrow().frames().isEmpty());
        }
        assertTrue(readyToRebuildRead(idleB,idleC,retainedRead()));
        assertFalse(readyToRebuildRead(beforeB,idleC,retainedRead()));
        assertFalse(readyToRebuildRead(idleB,beforeC,retainedRead()));
        assertFalse(readyToRebuildRead(idleB,idleC,retainedResult(Optional.empty())),"A retained local/history flag cannot replace the current device result after ACK");
        assertFalse(readyToRebuildRead(idleB,idleC,retainedResult(Optional.of(new LibraryReadHead.Result("denied",DataValue.NullValue.INSTANCE)))));
        System.out.println("QUIESCENT outer B/C service_yield accepted; absent/denied current result rejected");
    }
    @Test void transactionSleepsCannotTriggerOwnerReconstruction() {
        var seen=new TreeSet<Integer>();Set<Integer> expected=null;
        for(boolean disconnect:List.of(false,true)) {
            var r=new NativeLibraryFiniteFailureTest.Rig(false);
            r.until(()->NativeLibraryFiniteFailureTest.state(r.c).status()==FlowRuntime.Status.SLEEPING,100);
            var idleC=r.c.flowSnapshot().orElseThrow();
            assertEquals("service_yield",idleC.program().steps().get(idleC.state().cursor()).sourceId());
            var image=FlowXBus.image(r.b.flowSnapshot().orElseThrow().program());
            expected=new TreeSet<>();
            for(int pc=0;pc<image.code().size();pc++) if(image.code().get(pc).instruction() instanceof RegisterProgram.Sleep
                && r.b.flowSnapshot().orElseThrow().program().steps().get(image.source(pc)) instanceof FlowProgram.Procedure)expected.add(pc);
            r.beforeBRound=x->{
                var b=x.b.flowSnapshot().orElseThrow();var s=b.state();
                if(s.status()!=FlowRuntime.Status.SLEEPING || !(b.program().steps().get(s.cursor()) instanceof FlowProgram.Procedure))return;
                var registers=s.assembly().orElseThrow();
                assertTrue(s.calls().frames().isEmpty(),"Legacy call stack does not expose assembly activations");
                assertFalse(registers.calls().orElseThrow().frames().isEmpty(),"Actual transaction has an ISA activation");
                assertEquals("implementation",b.program().steps().get(s.cursor()).sourceId());
                assertEquals("transaction",b.program().steps().get(registers.calls().orElseThrow().frames().getLast().source()).sourceId());
                assertFalse(readyToRebuildRead(b,idleC,retainedRead()),"Transaction Sleep at "+image.location(registers.pc())+" is not an outer service yield");
                if(seen.add(registers.pc()))System.out.println("QUIESCENT rejected transaction Sleep pc="+registers.pc()+" source="+s.cursor()+" location="+image.location(registers.pc())+" ISA frames="+registers.calls().orElseThrow().frames().size());
            };
            r.awaitCommand();
            if(disconnect) {r.connected=false;r.networkTick();}
            else {r.until(()->!seen.isEmpty(),500);r.send(NativeLibraryFiniteFailureTest.reply());}
            r.until(r::complete,3000);
            assertEquals(new DataValue.BooleanValue(!disconnect),r.outcome().field("ok"));
        }
        assertEquals(3,expected.size(),"Cover all three public B transaction Sleep sites");
        assertEquals(expected,seen,"Every actual transaction Sleep must be excluded from reconstruction");
    }

    static XBusAccess.Network network(ComputerBlockHost left,String lp,ComputerHostLimits ll,
            ComputerBlockHost right,String rp,ComputerHostLimits rl) {
        return XBusAccess.network(List.of(access(left,lp,ll),access(right,rp,rl)));
    }
    static XBusAccess access(ComputerBlockHost h,String port,ComputerHostLimits l) {
        return new XBusAccess(h,XBusPortState.of(h.flowSnapshot().orElseThrow(),l.pins(),port).orElseThrow(),()->l,Optional::empty);
    }
    static XBusNetwork.Pin pin(ComputerBlockHost h,String p) {return new XBusNetwork.Pin(h.flowSnapshot().orElseThrow().state().computer(),p);}
    static int status(ComputerBlockHost h,String p,ComputerHostLimits l,ComputerBlockHost peer,String pp,ComputerHostLimits pl) {
        return network(h,p,l,peer,pp,pl).statusCode(pin(h,p));
    }
    @Test void downstreamServicesYieldAcrossStaggeredPausedStartupWithoutSerialOrReceive() {
        var la=NativeLibraryOwnersTest.limits("cleric",5);var lb=NativeLibraryOwnersTest.limits("cleric",5);var lc=NativeLibraryOwnersTest.limits("cleric",5);
        var serial=NativeLibraryOwnersTest.device(lb);
        var a=NativeLibraryOwnersTest.install(NativeLibraryOwnersTest.document("a"),la,null);
        var b=NativeLibraryOwnersTest.install(NativeLibraryOwnersTest.document("b"),lb,serial);
        var c=NativeLibraryOwnersTest.install(NativeLibraryOwnersTest.document("c"),lc,null);
        c.setPaused(false,lc,true);
        int cSleeps=0,bSleeps=0;
        for(int tick=0;tick<400;tick++) {
            if(tick==200)b.setPaused(false,lb,true);
            assertEquals(0,status(c,"x0",lc,b,"x1",lb));
            assertEquals(0,status(b,"x0",lb,a,"x0",la));
            c.tick(tick,lc,true,Map.of("x0",status(c,"x0",lc,b,"x1",lb)));
            b.tick(tick,lb,true,Map.of("x0",status(b,"x0",lb,a,"x0",la)));
            network(b,"x1",lb,c,"x0",lc).rejectPaused(pin(c,"x0"));
            network(a,"x0",la,b,"x0",lb).rejectPaused(pin(b,"x0"));
            var cs=c.flowSnapshot().orElseThrow().state();var bs=b.flowSnapshot().orElseThrow().state();
            assertNotEquals(FlowRuntime.Status.FAILED,cs.status(),cs.diagnostic().toString());
            assertNotEquals(FlowRuntime.Status.FAILED,bs.status(),bs.diagnostic().toString());
            assertNotEquals(FlowRuntime.Status.WAITING_READ,cs.status());
            assertNotEquals(FlowRuntime.Status.WAITING_READ,bs.status());
            if(cs.status()==FlowRuntime.Status.SLEEPING)cSleeps++;
            if(bs.status()==FlowRuntime.Status.SLEEPING)bSleeps++;
            assertEquals(0,serial.status("s0").transmit().equals("queued")?1:0);
            assertEquals(0,serial.status("s0").receive().frames());
            assertTrue(a.flowSnapshot().orElseThrow().state().paused());
            assertEquals(0,a.flowSnapshot().orElseThrow().state().work());
        }
        assertTrue(cSleeps>20);assertTrue(bSleeps>20);
        assertTrue(c.flowSnapshot().orElseThrow().state().work()>10);
        assertTrue(b.flowSnapshot().orElseThrow().state().work()>10);
    }

    @Test void everyServiceIterationWaitsThenHandlesTwoFiniteTransactionsWithoutIdleFrames() {
        var r=new NativeLibraryFiniteFailureTest.Rig(false);
        for(int transaction=0;transaction<2;transaction++) {
            r.until(()->r.wireL.status("s0").receive().frames()>0,2500);
            assertEquals(NativeLibraryFiniteFailureTest.REQUEST,r.takeCommand());
            r.send(NativeLibraryFiniteFailureTest.reply());r.until(r::complete,3000);
            assertEquals(new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.BooleanValue(true),r.outcome().field("ok"));
            r.a.setPaused(true,r.la,true);
            int sent=r.outgoing;int sleepingB=0,sleepingC=0;
            for(int tick=0;tick<200;tick++) {
                r.step();
                network(r.a,"x0",r.la,r.b,"x0",r.lb).rejectPaused(pin(r.b,"x0"));
                assertNotEquals(FlowRuntime.Status.FAILED,NativeLibraryFiniteFailureTest.state(r.b).status());
                assertNotEquals(FlowRuntime.Status.FAILED,NativeLibraryFiniteFailureTest.state(r.c).status());
                assertNotEquals(FlowRuntime.Status.WAITING_READ,NativeLibraryFiniteFailureTest.state(r.b).status());
                assertNotEquals(FlowRuntime.Status.WAITING_READ,NativeLibraryFiniteFailureTest.state(r.c).status());
                if(NativeLibraryFiniteFailureTest.state(r.b).status()==FlowRuntime.Status.SLEEPING)sleepingB++;
                if(NativeLibraryFiniteFailureTest.state(r.c).status()==FlowRuntime.Status.SLEEPING)sleepingC++;
            }
            assertTrue(sleepingB>20);assertTrue(sleepingC>20);assertEquals(sent,r.outgoing);
            assertEquals(transaction+1,sent);assertEquals(0,r.wireL.status("s0").receive().frames());assertEquals(0,r.wireB.status("s0").receive().frames());
            if(transaction==0) {r.a.reset(r.la,true);r.a.setPaused(false,r.la,true);}
        }
        System.out.println("STARTUP finite transactions=2 idleTicks=400 peak work/tick="+Arrays.toString(r.peakWork));
    }
    @Test void explicitUnguardedReadStillFailsAgainstPausedPeerWithoutWakingOrPublishingToIt() {
        var l=NativeLibraryOwnersTest.limits("cleric",5);
        var peer=NativeLibraryOwnersTest.install(NativeLibraryOwnersTest.document("b"),l,null);
        var reader=new ComputerBlockHost(()->{});
        reader.installFlow(new FlowProgram(Map.of(),List.of(new FlowProgram.Read("explicit_read","x0","value",com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.PACKET))).withAssembly(),com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record(),l,true);
        reader.setPaused(false,l,true);reader.tick(l,true);
        var before=peer.flowSnapshot().orElseThrow();
        assertEquals(XBusExchange.Status.PAUSED,network(reader,"x0",l,peer,"x0",l).rejectPaused(pin(reader,"x0")));
        assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException.Code.XBUS_PEER_PAUSED,reader.flowSnapshot().orElseThrow().state().diagnostic().orElseThrow().code());
        assertEquals(before,peer.flowSnapshot().orElseThrow());assertTrue(reader.flowSnapshot().orElseThrow().receipts().isEmpty());
    }
}
