package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.SerialHardware;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class SerialDeviceTest {
    @Test void anotherIncidentInvalidatesAPreparedClaimEvenWhenItsFaultKindWasAlreadyLatched() {
        var device = new SerialDevice(() -> HARDWARE, () -> true, () -> CODEC, () -> {});
        device.configure("s0", Direction.EAST, true); var receiver = device.attachments().getFirst().receiver();
        receiver.collision(); var claim = device.prepareStatus("s0", true);
        receiver.collision(); var before = device.write();
        assertFalse(claim.publish(), "Do not clear a newer collision using an older status snapshot");
        assertEquals(before, device.write());
        var current = device.prepareStatus("s0", true);
        assertEquals(new DataValue.ListValue(List.of(new DataValue.TextValue("collision"))), current.result().field("receive_faults"));
        assertTrue(current.publish()); assertTrue(receiver.status().faults().isEmpty());
        assertFalse(current.publish(), "A consumed receipt cannot be published twice");
    }
    @Test void claimIsLocalAndCanInspectClosedHardwareButNeverChangesAnUnavailableDeviceOrTransmission() {
        var available = new java.util.concurrent.atomic.AtomicBoolean(true);
        var device = new SerialDevice(() -> HARDWARE, available::get, () -> CODEC, () -> {});
        device.configure("s0", Direction.EAST, true); var receiver = device.attachments().getFirst().receiver();
        receiver.offer(new byte[0]); assertEquals("queued", device.send("s0", RECORD));
        var before = device.write(); available.set(false);
        var unavailable = device.prepareStatus("s0", true);
        assertEquals(new DataValue.TextValue("unavailable"), unavailable.result().field("connection"));
        assertTrue(unavailable.publish()); assertEquals(before, device.write());
        available.set(true); var active = device.prepareStatus("s0", true);
        assertTrue(active.publish()); assertTrue(receiver.status().faults().isEmpty());
        assertEquals("queued", device.status("s0").transmit());
        receiver.offer(new byte[0]); device.configure("s0", Direction.EAST, false);
        var closed = device.prepareStatus("s0", true); assertTrue(closed.publish());
        assertEquals(new DataValue.TextValue("closed"), closed.result().field("connection"));
        assertEquals("configuration_changed", device.status("s0").failure());
        assertTrue(receiver.status().faults().isEmpty());
        var clean = device.write(); assertTrue(device.prepareStatus("absent", true).publish()); assertEquals(clean, device.write());
    }
    @Test void explicitReceiveModeNeverGuessesWhetherAnArchiveShapedCompoundIsApplicationData() {
        var device = new SerialDevice(() -> HARDWARE, () -> true, () -> CODEC, () -> {});
        device.configure("s0", Direction.EAST, true);
        device.attachments().getFirst().receiver().offer(SerialRecordCodec.encode(RECORD, CODEC));
        var nativeRead = device.prepareReceive("s0", true);
        assertInstanceOf(DataValue.NbtValue.class, nativeRead.result().field("value"));
        assertEquals(RECORD, device.prepareReceive("s0").result().field("value"));
        assertTrue(nativeRead.publish()); assertEquals(0, device.status("s0").receive().frames());
    }
    @Test void nativeFrameUsesActualWireTimeAndSurvivesDeviceReloadWithoutTypedEnvelope() {
        var limits = new com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec.Limits(
            CODEC.maxEncodedBytes(), CODEC.maxDecodeHeapBytes(), CODEC.values());
        var tag = new net.minecraft.nbt.CompoundTag(); tag.putIntArray("samples", new int[]{7, 12}); tag.putByte("flag", (byte)1);
        var value = com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec.snapshot(tag, limits);
        var sender = new SerialDevice(() -> HARDWARE, () -> true, () -> CODEC, () -> {});
        var receiver = new SerialDevice(() -> HARDWARE, () -> true, () -> CODEC, () -> {});
        sender.configure("s0", Direction.EAST, true); receiver.configure("s0", Direction.WEST, true);
        var send = sender.prepareSend("s0", value);
        assertEquals(SerialResult.send("queued"), send.result());
        assertEquals("idle", sender.status("s0").transmit());
        assertTrue(send.publish());
        var a = sender.attachments().getFirst(); var b = receiver.attachments().getFirst();
        var channel = new SharedSerialChannel(new SharedSerialChannel.Limits(2, 4096, 128, 32),
            Map.of(a.key(), a.hardware().transmitter(), b.key(), b.hardware().transmitter()), Map.of(b.key(), b.receiver()));
        sender.start(a, channel);
        assertTrue(channel.advance().isEmpty(), "Native data is not an instantaneous side channel");
        while (channel.advance().isEmpty()) { }
        var bytes = b.receiver().peek().orElseThrow();
        assertEquals(value, com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec.decode(bytes, limits));
        assertThrows(SerialRecordCodec.Failure.class, () -> SerialRecordCodec.decode(bytes, CODEC));
        var saved = receiver.write(); receiver.read(saved);
        assertTrue(receiver.diagnostic().isEmpty());
        assertArrayEquals(bytes, receiver.attachments().getFirst().receiver().peek().orElseThrow());
        assertNotEquals(new DataValue.TextValue("received"), receiver.prepareReceive("s0").result().field("status"));
        assertEquals(1, receiver.status("s0").receive().frames(), "Wrong format does not consume the frame");
        var receive = receiver.prepareReceive("s0", true);
        assertEquals(value, receive.result().field("value"));
        assertEquals(Optional.of(a.key()), receive.source());
        assertTrue(receive.publish()); assertFalse(receive.publish());
        assertEquals(0, receiver.status("s0").receive().frames());
    }
    @Test void commandAndReplyPublishTogetherAndDoNotConsumeWhenTheReplyIsOversizedOrStale() {
        var changes = new java.util.concurrent.atomic.AtomicInteger();
        var device = new SerialDevice(() -> HARDWARE, () -> true, () -> CODEC, changes::incrementAndGet);
        device.configure("s0", Direction.EAST, true);
        var receiver = device.attachments().getFirst().receiver(); receiver.offer(SerialRecordCodec.encode(RECORD, CODEC));
        var receive = device.prepareReceive("s0"); var before = device.write(); int notifications = changes.get();
        var large = new DataValue.RecordValue(Map.of("value", new DataValue.TextValue("x".repeat(10000))));
        assertThrows(SerialRecordCodec.Failure.class, () -> receive.replying(large)); assertEquals(before, device.write());
        var exchange = receive.replying(RECORD); assertEquals(before, device.write());
        assertTrue(exchange.publish()); assertFalse(receive.publish()); assertFalse(exchange.publish());
        assertEquals(0, receiver.status().frames()); assertEquals("queued", device.status("s0").transmit());
        assertEquals(notifications, changes.get(), "Owner dirties only after publishing its own continuation");
        receiver.offer(SerialRecordCodec.encode(RECORD, CODEC));
        assertThrows(IllegalArgumentException.class, () -> device.prepareReceive("s0").replying(RECORD));
        assertEquals(1, receiver.status().frames(), "Busy TX does not eat the next command");
        var stale = device.prepareReceive("s0"); device.configure("s0", Direction.WEST, false);
        assertThrows(IllegalArgumentException.class, () -> stale.replying(RECORD));
        assertEquals(1, receiver.status().frames());
    }
    @Test void physicalSenderSurvivesBufferSaveAndHardwareChangeButIsNotPartOfThePlayersRecord() {
        var selected = new java.util.concurrent.atomic.AtomicReference<>(HARDWARE);
        var sender = new SerialDevice(() -> HARDWARE, () -> true, () -> CODEC, () -> {});
        var receiver = new SerialDevice(selected::get, () -> true, () -> CODEC, () -> {});
        sender.configure("s0", Direction.EAST, true); receiver.configure("s0", Direction.WEST, true);
        var a = sender.attachments().getFirst(); var b = receiver.attachments().getFirst();
        var channel = new SharedSerialChannel(new SharedSerialChannel.Limits(2, 4096, 128, 32),
            Map.of(a.key(), a.hardware().transmitter(), b.key(), b.hardware().transmitter()), Map.of(b.key(), b.receiver()));
        channel.transmit(a.key(), SerialRecordCodec.encode(RECORD, CODEC));
        while (channel.advance().isEmpty()) { }
        assertEquals(Optional.of(a.key()), receiver.prepareReceive("s0").source());
        assertEquals(RECORD, receiver.prepareReceive("s0").result().field("value"));
        var saved = receiver.write(); receiver.read(saved);
        selected.set(new SerialHardware(Map.of("s0", new SerialHardware.Port(64, 4096, 3, 12288))));
        var prepared = receiver.prepareReceive("s0");
        assertEquals(Optional.of(a.key()), prepared.source()); assertTrue(prepared.publish());
        receiver.attachments().getFirst().receiver().offer(SerialRecordCodec.encode(RECORD, CODEC));
        assertTrue(receiver.prepareReceive("s0").source().isEmpty(), "Legacy/unattributed bytes acquire no authority");
    }
    @Test void changedConfigurationReceiveQueueOrAvailabilityInvalidatesPreparationWithoutLosingHistory() {
        var available = new java.util.concurrent.atomic.AtomicBoolean(true);
        var device = new SerialDevice(() -> HARDWARE, available::get, () -> CODEC, () -> {});
        device.configure("s0", Direction.EAST, true);
        var send = device.prepareSend("s0", RECORD);
        device.configure("s0", Direction.WEST, true); device.configure("s0", Direction.EAST, true);
        assertFalse(send.publish()); assertEquals("idle", device.status("s0").transmit());
        var empty = device.prepareReceive("s0"); var receiver = device.attachments().getFirst().receiver();
        receiver.offer(SerialRecordCodec.encode(RECORD, CODEC)); assertFalse(empty.publish());
        var receive = device.prepareReceive("s0"); receiver.offer(SerialRecordCodec.encode(RECORD, CODEC));
        assertFalse(receive.publish()); assertEquals(2, receiver.status().frames());
        var queued = device.prepareSend("s0", RECORD); available.set(false); assertFalse(queued.publish());
        available.set(true); device.unload(); assertFalse(queued.publish());
        assertEquals(2, receiver.status().frames());
    }
    @Test void preparedFrameOperationsHaveNoEffectUntilLocalProgramPublicationAndCannotBeReused() {
        var device = new SerialDevice(() -> HARDWARE, () -> true, () -> CODEC, () -> {});
        device.configure("s0", Direction.EAST, true);
        var before = device.write();
        var send = device.prepareSend("s0", RECORD);
        assertEquals(new DataValue.TextValue("queued"), send.result().field("status"));
        assertEquals(before, device.write()); assertEquals("idle", device.status("s0").transmit());
        assertTrue(send.publish()); assertFalse(send.publish());
        assertEquals("queued", device.status("s0").transmit());
        var receiver = device.attachments().getFirst().receiver(); receiver.offer(SerialRecordCodec.encode(RECORD, CODEC));
        var read = device.prepareReceive("s0");
        assertEquals(RECORD, read.result().field("value")); assertEquals(1, receiver.status().frames());
        assertTrue(read.publish()); assertEquals(0, receiver.status().frames()); assertFalse(read.publish());
        assertEquals(new DataValue.TextValue("empty"), device.prepareReceive("s0").result().field("status"));
    }
    @org.junit.jupiter.api.Test void residentBreakpointClaimsOnceAndNeverReplaysOrRetries() {
        var serial=new SerialDevice(()->HARDWARE,()->true,()->CODEC,()->{});
        serial.configure("s0",Direction.EAST,true);serial.connect("s0");
        var port=serial.attachments().getFirst();var rx=port.receiver();
        var encoded=SerialRecordCodec.encode(RECORD,CODEC);rx.offer(encoded);rx.offer(encoded);
        var latch=new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentBreakpoint();
        latch.select("s0");assertTrue(latch.sample(serial,List.of("s0")));
        assertEquals(1,rx.status().frames());assertEquals("queued",latch.outcome());assertTrue(latch.halted());
        var copy=new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentBreakpoint();
        copy.load(latch.save());assertEquals(latch.captured(),copy.captured());
        assertFalse(copy.sample(serial,List.of("s0")));assertEquals(1,rx.status().frames());
        var sent=new java.util.ArrayList<DataValue.RecordValue>();
        serial.start(port,(key,bytes)->{sent.add(SerialRecordCodec.decode(bytes,CODEC));return SharedSerialChannel.Admission.STARTED;});
        assertEquals(List.of(new DataValue.RecordValue(Map.of("text",new DataValue.TextValue(latch.captured().orElseThrow())))),sent);
        copy.reset();assertTrue(copy.sample(serial,List.of("s0")));assertEquals("busy",copy.outcome());
        assertEquals(0,rx.status().frames());assertFalse(copy.sample(serial,List.of("s0")));
    }
    @org.junit.jupiter.api.Test void burstReservesActualEncodedBytesIncludingTheInFlightFrame() {
        int bytes=SerialRecordCodec.encode(RECORD,CODEC).length;
        var hardware=new SerialHardware(Map.of("s0",new SerialHardware.Port(128,4096,2,8192,Optional.empty(),4,bytes*3L)));
        var device=new SerialDevice(()->hardware,()->true,()->CODEC,()->{});
        device.configure("s0",Direction.EAST,true);device.connect("s0");
        var a=device.attachments().getFirst();assertEquals("queued",device.send("s0",RECORD));
        var started=new java.util.ArrayList<byte[]>();
        java.util.function.BiFunction<SharedSerialChannel.Port,byte[],SharedSerialChannel.Admission> wire=(port,frame)->{
            started.add(frame.clone());return SharedSerialChannel.Admission.STARTED;};
        device.start(a,wire);assertEquals("queued",device.send("s0",RECORD));assertEquals("queued",device.send("s0",RECORD));
        assertEquals("tx_buffer_full",device.send("s0",RECORD));device.start(a,wire);assertEquals(1,started.size());
        device.complete("s0",SharedSerialChannel.Outcome.COLLISION);
        device.start(a,wire);device.complete("s0",SharedSerialChannel.Outcome.TRANSMITTED);
        device.start(a,wire);device.complete("s0",SharedSerialChannel.Outcome.TRANSMITTED);
        device.start(a,wire);assertEquals(3,started.size(),"Collision does not put the colliding frame back in the queue");
    }
    @Test void fourPreparedFramesUseOneWireInOrderAndTheFifthDoesNotEnter() {
        var hardware = new SerialHardware(Map.of("s0", new SerialHardware.Port(128,4096,2,8192,Optional.empty(),4,4096)));
        var device = new SerialDevice(()->hardware,()->true,()->CODEC,()->{});
        device.configure("s0",Direction.EAST,true); device.connect("s0");
        var expected = new java.util.ArrayList<DataValue.RecordValue>();
        for (int i=1;i<=5;i++) {
            var frame = new DataValue.RecordValue(Map.of("n",new DataValue.IntegerValue(i)));
            var prepared = device.prepareSend("s0",frame);
            assertEquals(new DataValue.TextValue(i<=4?"queued":"tx_buffer_full"),prepared.result().field("status"));
            assertTrue(prepared.publish());
            if (i<=4) expected.add(frame);
        }
        var actual = new java.util.ArrayList<DataValue.RecordValue>();
        var port = device.attachments().getFirst();
        for (int i=0;i<5;i++) {
            device.start(port,(id,data)->{actual.add(SerialRecordCodec.decode(data,CODEC));return SharedSerialChannel.Admission.STARTED;});
            if (i<4) device.complete("s0",i==0?SharedSerialChannel.Outcome.COLLISION:SharedSerialChannel.Outcome.TRANSMITTED);
        }
        assertEquals(expected,actual);
    }
    static final SerialHardware HARDWARE = new SerialHardware(Map.of("s0", new SerialHardware.Port(128, 4096, 2, 8192)));
    static final SerialRecordCodec.Limits CODEC = new SerialRecordCodec.Limits(65536, 1048576, new ArchiveLimits(262144, 2097152, 16777216));
    static final DataValue.RecordValue RECORD = new DataValue.RecordValue(Map.of("reading", new DataValue.IntegerValue(37)));

    @Test void localSubmissionNeedsExplicitFaceGrantAndDoesNotPretendItWasReceived() {
        var device = new SerialDevice(() -> HARDWARE, () -> true, () -> CODEC, () -> {});
        assertNull(device.write(), "Default hardware specifications do not create a grant or stored identity");
        assertEquals("closed", device.send("s0", RECORD));
        assertEquals("missing_port", device.send("s9", RECORD));
        assertEquals("missing_port", device.status("s9").connection());
        device.configure("s0", Direction.EAST, true);
        assertEquals("queued", device.send("s0", RECORD));
        assertEquals("busy", device.send("s0", RECORD));
        assertEquals("queued", device.status("s0").transmit());
        assertEquals(0, device.status("s0").receive().frames());
        assertTrue(device.poll("s0").isEmpty());
        assertEquals(Direction.EAST, device.attachments().getFirst().side());
        assertThrows(IllegalArgumentException.class, () -> device.configure("x0", Direction.WEST, true));
    }

    @Test void completeReceiveFramesSurviveReloadButAnUnfinishedEmissionIsNotReplayed() {
        var device = new SerialDevice(() -> HARDWARE, () -> true, () -> CODEC, () -> {});
        device.configure("s0", Direction.WEST, true);
        device.attachments().getFirst().receiver().offer(SerialRecordCodec.encode(RECORD, CODEC));
        assertEquals("queued", device.send("s0", RECORD));
        var saved = device.write();
        var restored = new SerialDevice(() -> HARDWARE, () -> true, () -> CODEC, () -> {});
        restored.read(saved);
        assertEquals(device.attachments().getFirst().identity(), restored.attachments().getFirst().identity());
        assertEquals("interrupted", restored.status("s0").transmit());
        assertEquals(1, restored.status("s0").receive().frames());
        assertEquals(RECORD, restored.poll("s0").orElseThrow());
        assertTrue(restored.poll("s0").isEmpty());
        assertEquals("queued", device.status("s0").transmit(), "Reading a save does not mutate its source device");
    }
    @Test void corruptOrFutureSavesAreOpaqueAndCannotBeOverwrittenByConfiguration() {
        var original = new SerialDevice(() -> HARDWARE, () -> true, () -> CODEC, () -> {});
        original.configure("s0", Direction.EAST, true);
        for (String invalid : List.of("Version", "Enabled", "Side", "Transmit", "Frames")) {
            var raw = (net.minecraft.nbt.CompoundTag)original.write();
            if (invalid.equals("Version")) raw.putInt("Version", 99);
            else raw.getCompound("Ports").getCompound("s0").putInt(invalid, 7);
            var restored = new SerialDevice(() -> HARDWARE, () -> true, () -> CODEC, () -> {}); restored.read(raw);
            assertTrue(restored.attachments().isEmpty()); assertTrue(restored.diagnostic().isPresent());
            assertThrows(IllegalArgumentException.class, () -> restored.configure("s0", Direction.WEST, false));
            assertEquals(raw, restored.write());
        }
    }
    @Test void hardwareDowngradeRetainsTheWholeSavedQueueAndRestoringCapacityMakesItReadableAgain() {
        var selected = new java.util.concurrent.atomic.AtomicReference<>(HARDWARE);
        var a = new SerialDevice(selected::get, () -> true, () -> CODEC, () -> {}); a.configure("s0", Direction.EAST, true);
        var receiver = a.attachments().getFirst().receiver(); receiver.offer(SerialRecordCodec.encode(RECORD, CODEC)); receiver.offer(SerialRecordCodec.encode(RECORD, CODEC));
        var saved = a.write();
        selected.set(new SerialHardware(Map.of("s0", new SerialHardware.Port(128, 4096, 1, 4096))));
        assertTrue(a.attachments().isEmpty()); assertEquals("resource_limit", a.diagnostic().orElseThrow()); assertEquals(saved, a.write());
        var b = new SerialDevice(selected::get, () -> true, () -> CODEC, () -> {}); b.read(saved);
        assertTrue(b.attachments().isEmpty()); assertEquals(saved, b.write());
        selected.set(HARDWARE);
        for (var device : List.of(a, b)) { assertEquals(RECORD, device.poll("s0").orElseThrow()); assertEquals(RECORD, device.poll("s0").orElseThrow()); }
    }
    @Test void decodingFailureDoesNotRemoveTheHeadFrame() {
        var limits = new java.util.concurrent.atomic.AtomicReference<>(CODEC);
        var a = new SerialDevice(() -> HARDWARE, () -> true, limits::get, () -> {}); a.configure("s0", Direction.EAST, true);
        a.attachments().getFirst().receiver().offer(SerialRecordCodec.encode(RECORD, CODEC));
        limits.set(new SerialRecordCodec.Limits(65536, 1, CODEC.values()));
        var saved = a.write(); assertThrows(SerialRecordCodec.Failure.class, () -> a.poll("s0")); assertEquals(saved, a.write());
        limits.set(CODEC); assertEquals(RECORD, a.poll("s0").orElseThrow());
    }
}
