package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplicationTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplicationProtocolTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.SerialHardware;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.*;
import org.junit.jupiter.api.Test;

class StockApplicationSerialTest {
    static final SerialRecordCodec.Limits CODEC = new SerialRecordCodec.Limits(131072,16777216,new ArchiveLimits(65536,1048576,4194304));
    static final SerialHardware HARDWARE = new SerialHardware(Map.of("s0",new SerialHardware.Port(1024,131072,4,524288)));
    static final class Wire implements CableDiscovery.World {
        final SerialNetwork network = new SerialNetwork();
        final AtomicReference<SerialRecordCodec.Limits> receiverCodec = new AtomicReference<>(CODEC);
        final SerialDevice computer = new SerialDevice(()->HARDWARE,()->true,()->CODEC,()->{});
        final SerialDevice device = new SerialDevice(()->HARDWARE,()->true,receiverCodec::get,()->{});
        final Map<BlockPos,SerialDevice> devices = new LinkedHashMap<>(Map.of(new BlockPos(0,0,0),computer,new BlockPos(2,0,0),device));
        Wire() { computer.configure("s0",Direction.EAST,true);device.configure("s0",Direction.WEST,true); }
        public boolean loaded(BlockPos p) { return true; }
        public CableDiscovery.Cell cell(BlockPos p) { return new CableDiscovery.Cell(p.equals(new BlockPos(1,0,0)) ? CableDiscovery.Kind.CABLE : devices.containsKey(p)?CableDiscovery.Kind.ENDPOINT:CableDiscovery.Kind.EMPTY,null); }
        void tick() { network.tick(this,devices,new CommunicationNetworkLimits(64,4),new SharedSerialChannel.Limits(4,131072,1024,32)); }
        void transmit(SerialDevice destination, DataValue.RecordValue expected) {
            int ticks=(SerialRecordCodec.encode(expected,CODEC).length*8+32+1023)/1024;
            assertTrue(ticks>1);
            for(int n=1;n<ticks;n++){tick();assertEquals(0,destination.status("s0").receive().frames());}
            tick(); assertEquals(expected,destination.prepareReceive("s0").result().field("value"));
        }
        void command(DataValue.RecordValue frame) { assertEquals("queued",computer.send("s0",frame));transmit(device,frame); }
        Optional<StockApplication.Access> verified(SharedSerialChannel.Port port) { assertEquals(computer.attachments().getFirst().key(),port);return Optional.of(ACCESS); }
    }
    @Test void aThirdPeerObservesTheRealSharedFramesButCannotReuseTheirAuthority() {
        var wire = new Wire();
        var observer = new SerialDevice(() -> HARDWARE, () -> true, () -> CODEC, () -> {});
        observer.configure("s0",Direction.NORTH,true);
        wire.devices.put(new BlockPos(1,0,1),observer);
        var app = new StockApplicationSerial(initial());
        var trusted = wire.computer.attachments().getFirst().key();
        java.util.function.Function<SharedSerialChannel.Port, Optional<StockApplication.Access>> proof =
            port -> port.equals(trusted) ? Optional.of(ACCESS) : Optional.empty();
        var replace = command("replace",args(app.state(),"entries",new DataValue.ListValue(List.of(StockApplicationValues.entry(entry())))));
        wire.command(replace);
        assertEquals(replace,observer.poll("s0").orElseThrow(),"No promise of encryption or exclusive delivery");
        assertEquals("accepted",StockApplicationHost.receive(app,wire.device,proof,TARGET,LIMITS,()->{}));
        for (int i=0;i<100;i++) wire.tick();
        var observed = observer.poll("s0").orElseThrow();
        assertEquals(observed,wire.computer.poll("s0").orElseThrow());
        assertEquals(text("accepted"),observed.field("code"));
        var before = app.state();
        var fence = (DataValue.RecordValue)observed.field("state");
        var forged = command("clear",record("device",fence.field("device"),"session",fence.field("session"),"revision",fence.field("revision")));
        assertEquals("queued",observer.send("s0",forged));
        wire.transmit(wire.device,forged);
        assertEquals("permission",StockApplicationHost.receive(app,wire.device,proof,TARGET,LIMITS,()->{}));
        assertSame(before,app.state());
        for (int i=0;i<100;i++) wire.tick();
        var denial = observer.poll("s0").orElseThrow();
        assertEquals(Set.of("kind","version","target","request","code"),denial.fields().keySet());
    }
    @Test void unrelatedTypedAndNativeFramesNeverInvokeApplicationProofResolution() {
        var unrelatedKind=record("kind",text("other_command"),"target",TARGET);
        var unrelatedTarget=record("kind",text("stock_application_command"),"target",record("x",number(99)));
        for(var frame:List.of(unrelatedKind,unrelatedTarget)) {
            var wire=new Wire();var app=new StockApplicationSerial(initial());var before=app.state();wire.command(frame);
            assertTrue(app.prepare(wire.device.prepareReceive("s0"),port->{fail("Unrelated frame must not invoke application verifier");return Optional.of(ACCESS);},TARGET,LIMITS).isEmpty());
            assertSame(before,app.state());assertEquals(1,wire.device.status("s0").receive().frames());
            assertEquals("idle",wire.device.status("s0").transmit());
        }
        var wire=new Wire();var app=new StockApplicationSerial(initial());var nativeFrame=new DataValue.NbtValue(10,record("ordinary",new DataValue.NbtValue(8,text("native"))));
        var nativeLimits=new com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec.Limits(CODEC.maxEncodedBytes(),CODEC.maxDecodeHeapBytes(),CODEC.values());
        int bytes=com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec.encode(nativeFrame,nativeLimits).length;
        var send=wire.computer.prepareSend("s0",nativeFrame);assertEquals(SerialResult.send("queued"),send.result());assertTrue(send.publish());
        for(int n=0;n<(bytes*8+32+1023)/1024;n++)wire.tick();
        var received=wire.device.prepareReceive("s0",true);assertEquals(nativeFrame,received.result().field("value"));
        assertTrue(app.prepare(received,port->{fail("Native frame must not invoke application verifier");return Optional.of(ACCESS);},TARGET,LIMITS).isEmpty());
        assertEquals(1,wire.device.status("s0").receive().frames());assertEquals("idle",wire.device.status("s0").transmit());
    }
    @Test void addressedUnresolvablePeerReceivesARedactedDenialOverTheActualWire() {
        var wire=new Wire();var app=new StockApplicationSerial(initial());var before=app.state();wire.command(command("status",record()));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var prepared=app.prepare(wire.device.prepareReceive("s0"),port->{calls.incrementAndGet();return Optional.empty();},TARGET,LIMITS).orElseThrow();
        assertEquals(1,calls.get());assertEquals(text("permission"),prepared.reply().field("code"));
        assertEquals(Set.of("kind","version","target","request","code"),prepared.reply().fields().keySet());
        assertTrue(prepared.publish());assertEquals(2,calls.get());assertSame(before,app.state());
        assertEquals(0,wire.device.status("s0").receive().frames());wire.transmit(wire.computer,prepared.reply());
        assertEquals(prepared.reply(),wire.computer.poll("s0").orElseThrow());
    }
    @Test void disappearingApplicationProofInvalidatesAnAuthorizedPreparedReply() {
        var wire=new Wire();var app=new StockApplicationSerial(initial());wire.command(command("status",record()));
        var proof=new AtomicReference<Optional<StockApplication.Access>>(Optional.of(ACCESS));
        var prepared=app.prepare(wire.device.prepareReceive("s0"),port->proof.get(),TARGET,LIMITS).orElseThrow();
        assertTrue(prepared.reply().fields().containsKey("state"));
        proof.set(Optional.empty());assertFalse(prepared.publish());assertEquals(1,wire.device.status("s0").receive().frames());
        assertEquals("idle",wire.device.status("s0").transmit());
        var denied=app.prepare(wire.device.prepareReceive("s0"),port->proof.get(),TARGET,LIMITS).orElseThrow();
        assertFalse(denied.reply().fields().containsKey("state"));assertTrue(denied.publish());
        wire.transmit(wire.computer,denied.reply());assertEquals(text("permission"),((DataValue.RecordValue)wire.computer.poll("s0").orElseThrow()).field("code"));
    }
    @Test void realFiniteBytesPublishCatalogPollAndAckOnlyWithSuccessfulPreparedReply() {
        var wire=new Wire();var app=new StockApplicationSerial(initial());
        var replace=command("replace",args(app.state(),"entries",new DataValue.ListValue(List.of(StockApplicationValues.entry(entry())))));
        wire.command(replace);
        var prepared=app.prepare(wire.device.prepareReceive("s0"),wire::verified,TARGET,LIMITS).orElseThrow();
        assertTrue(app.state().catalog().isEmpty());assertTrue(prepared.publish());assertFalse(prepared.publish());
        wire.transmit(wire.computer,prepared.reply());assertEquals(prepared.reply(),wire.computer.poll("s0").orElseThrow());
        assertEquals("input_queued",app.confirm(ACCESS,PLAYER,request(app.state()),LIMITS).code());
        var pending=app.state();var poll=command("poll",args(pending));wire.command(poll);
        var read=app.prepare(wire.device.prepareReceive("s0"),wire::verified,TARGET,LIMITS).orElseThrow();
        assertTrue(read.publish()); assertSame(pending,app.state());wire.transmit(wire.computer,read.reply());
        assertEquals(StockApplicationValues.input(pending.inputs().getFirst()),((DataValue.RecordValue)wire.computer.poll("s0").orElseThrow()).field("input"));
        var ack=command("ack",args(pending,"sequence",number(1)));wire.command(ack);
        var acknowledge=app.prepare(wire.device.prepareReceive("s0"),wire::verified,TARGET,LIMITS).orElseThrow();
        var extra = command("status",record()); assertEquals("queued",wire.computer.send("s0",extra));
        int extraTicks=(SerialRecordCodec.encode(extra,CODEC).length*8+32+1023)/1024;for(int n=0;n<extraTicks;n++)wire.tick();
        assertFalse(acknowledge.publish());assertSame(pending,app.state());assertEquals(2,wire.device.status("s0").receive().frames());
        var current=app.prepare(wire.device.prepareReceive("s0"),wire::verified,TARGET,LIMITS).orElseThrow();
        assertTrue(current.publish());assertTrue(app.state().inputs().isEmpty());wire.transmit(wire.computer,current.reply());
        assertEquals(text("acknowledged"),((DataValue.RecordValue)wire.computer.poll("s0").orElseThrow()).field("code"));
    }
    @Test void serverConfirmationWinsOnceAndInvalidatesAnOlderPreparedCatalogPublication() {
        var start=initial();var catalog=StockApplication.replace(start,ACCESS,start.fence(),List.of(entry()),LIMITS).state();
        var wire=new Wire();var app=new StockApplicationSerial(catalog);
        wire.command(command("clear",args(catalog)));
        var clear=app.prepare(wire.device.prepareReceive("s0"),wire::verified,TARGET,LIMITS).orElseThrow();
        var request=request(catalog);
        assertEquals("input_queued",app.confirm(ACCESS,PLAYER,request,LIMITS).code());
        assertEquals("conflict",app.confirm(ACCESS,UUID.randomUUID(),request,LIMITS).code());
        assertEquals("existing",app.confirm(ACCESS,PLAYER,request,LIMITS).code());
        assertEquals(1,app.state().inputs().size());assertEquals(2,app.state().nextSequence());
        assertFalse(clear.publish());assertEquals(catalog.catalog(),app.state().catalog());
        assertEquals(1,wire.device.status("s0").receive().frames());
        assertEquals("idle",wire.device.status("s0").transmit());
    }
    @Test void delayedAckRestoredFromRealReceiveBufferCannotRetireTheNextCatalogInput() {
        var start = initial();
        var app = new StockApplicationSerial(StockApplication.replace(start,ACCESS,start.fence(),List.of(entry()),LIMITS).state());
        var wire = new Wire();
        var original = request(app.state());
        assertEquals("input_queued",app.confirm(ACCESS,PLAYER,original,LIMITS).code());
        var oldAck = command("ack",args(app.state(),"sequence",number(original.sequence())));
        wire.command(oldAck);
        var first = app.prepareOutput(wire.device.prepareReceive("s0"),wire::verified,TARGET,LIMITS).orElseThrow();
        assertEquals(text("acknowledged"),first.reply().field("code"));
        assertTrue(first.publish());
        wire.transmit(wire.computer,first.reply());
        assertEquals(first.reply(),wire.computer.poll("s0").orElseThrow());

        var next = request(app.state());
        assertEquals("input_queued",app.confirm(ACCESS,PLAYER,next,LIMITS).code());
        var pending = app.state();
        assertEquals("retired",app.confirm(ACCESS,PLAYER,original,LIMITS).code());
        assertSame(pending,app.state(),"Replaying the old confirmation cannot allocate another sequence");
        wire.command(oldAck); // The delayed duplicate really occupies the device's receive buffer.
        var savedDevice = wire.device.write();
        var savedComputer = wire.computer.write();
        var restored = new StockApplicationSerial(StockApplicationCodec.load(StockApplicationCodec.save(pending),UUID.randomUUID()));
        var resumed = new Wire();
        resumed.computer.read(savedComputer);
        resumed.device.read(savedDevice);
        assertEquals(1,resumed.device.status("s0").receive().frames());
        var retained = restored.state();
        var stale = restored.prepareOutput(resumed.device.prepareReceive("s0"),resumed::verified,TARGET,LIMITS).orElseThrow();
        assertEquals(text("stale_session"),stale.reply().field("code"));
        assertTrue(stale.publish());
        resumed.transmit(resumed.computer,stale.reply());
        assertEquals(stale.reply(),resumed.computer.poll("s0").orElseThrow());
        assertSame(retained,restored.state(),"A persisted old ACK must leave the new head untouched");

        // Even with an explicitly refreshed session, the old sequence cannot acknowledge the new head.
        resumed.command(command("ack",args(retained,"sequence",number(original.sequence()))));
        var wrongHead = restored.prepareOutput(resumed.device.prepareReceive("s0"),resumed::verified,TARGET,LIMITS).orElseThrow();
        assertEquals(text("stale_input"),wrongHead.reply().field("code"));
        assertTrue(wrongHead.publish());
        resumed.transmit(resumed.computer,wrongHead.reply());
        assertEquals(wrongHead.reply(),resumed.computer.poll("s0").orElseThrow());
        assertSame(retained,restored.state());
        assertEquals(next,retained.inputs().getFirst().request());
        resumed.command(command("ack",args(retained,"sequence",number(next.sequence()))));
        var current = restored.prepareOutput(resumed.device.prepareReceive("s0"),resumed::verified,TARGET,LIMITS).orElseThrow();
        assertEquals(text("acknowledged"),current.reply().field("code"));
        assertTrue(current.publish());
        assertFalse(current.publish());
        resumed.transmit(resumed.computer,current.reply());
        assertEquals(current.reply(),resumed.computer.poll("s0").orElseThrow());
        assertTrue(restored.state().inputs().isEmpty());
        assertEquals(3,restored.state().nextSequence());
    }
    @Test void oversizedBusyAndChangedAuthorityNeverPublishOrEatTheReceivedFrame() {
        var start=initial();var catalog=StockApplication.replace(start,ACCESS,start.fence(),List.of(entry()),LIMITS).state();
        var pending=StockApplication.confirm(catalog,ACCESS,PLAYER,request(catalog),LIMITS).state();
        var wire=new Wire();var app=new StockApplicationSerial(pending);var poll=command("poll",args(pending));wire.command(poll);
        wire.receiverCodec.set(new SerialRecordCodec.Limits(SerialRecordCodec.encode(poll,CODEC).length+1,CODEC.maxDecodeHeapBytes(),CODEC.values()));
        assertThrows(SerialRecordCodec.Failure.class,()->app.prepare(wire.device.prepareReceive("s0"),wire::verified,TARGET,LIMITS));
        assertSame(pending,app.state());assertEquals(1,wire.device.status("s0").receive().frames());
        wire.receiverCodec.set(CODEC);
        var access=new AtomicReference<>(ACCESS);
        var ready=app.prepare(wire.device.prepareReceive("s0"),port->Optional.of(access.get()),TARGET,LIMITS).orElseThrow();
        access.set(new StockApplication.Access(APP,true,true));assertFalse(ready.publish());assertSame(pending,app.state());
        assertEquals("queued",wire.device.send("s0",record("busy",text("occupied"))));
        assertThrows(IllegalArgumentException.class,()->app.prepare(wire.device.prepareReceive("s0"),wire::verified,TARGET,LIMITS));
        assertSame(pending,app.state());assertEquals(1,wire.device.status("s0").receive().frames());
    }
}


