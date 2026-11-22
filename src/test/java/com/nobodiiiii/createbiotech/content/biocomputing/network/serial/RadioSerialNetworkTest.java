package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.SerialHardware;
import com.nobodiiiii.createbiotech.content.biocomputing.network.carrier.CarrierFrequency;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDeviceTest.CODEC;
import static com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDeviceTest.RECORD;
import static org.junit.jupiter.api.Assertions.*;

class RadioSerialNetworkTest {
    private static final SharedSerialChannel.Limits LIMITS = new SharedSerialChannel.Limits(4, 4096, 512, 8);
    private static final RadioSerialNetwork.Coding ROBUST = new RadioSerialNetwork.Coding("qa-robust", 32, 1);
    private static final RadioSerialNetwork.Coding DENSE = new RadioSerialNetwork.Coding("qa-dense", 32, 2);
    private static final DataValue.RecordValue OTHER = new DataValue.RecordValue(Map.of("other", new DataValue.IntegerValue(9)));

    // All strengths and decode criteria are test fixtures, not shipping radio balance or a range formula.
    private static RadioSerialNetwork network() {
        return new RadioSerialNetwork(LIMITS,
            (source, receiver, occupied, filter) -> source.getX() == 10 ? (receiver.getX() == 2 ? 20 : 60) : 90,
            (coding, wanted, unwanted) -> wanted > unwanted * (coding.equals(DENSE) ? 3 : 2));
    }

    @Test void changingOnlyTheEmitterReceiveFilterNeverInterruptsItsOutgoingFrame() {
        var tx = radio(0,100,6,12,ROBUST,128,4096,2);
        var rx = radio(1,100,6,12,ROBUST,128,4096,2);
        var net = network();
        assertEquals("queued", tx.device().send("s0", RECORD));
        net.tick(List.of(tx,rx));
        var narrower = WitchReceiverFilter.Width.NARROW.apply(tx, 6);
        assertEquals(6,narrower.filter().width());
        assertEquals(tx.occupied(),narrower.occupied());
        assertEquals(RadioSerialNetwork.Reception.ACCEPTED,
            observation(finish(net,List.of(narrower,rx),100),rx).outcome());
        assertEquals(RECORD,rx.device().poll("s0").orElseThrow());
    }

    @Test void onlyExplicitlyConditionedReceiverRejectsAdjacentNoise() {
        var tx = radio(0,100,6,12,ROBUST,128,4096,2);
        var rx = radio(1,100,6,12,ROBUST,128,4096,2);
        var other = radio(3,100,6,12,ROBUST,128,4096,2);
        var interferer = radio(10,108,6,12,DENSE,128,4096,2);
        var narrow = WitchReceiverFilter.Width.NARROW.apply(rx, 6);
        tx.device().send("s0",RECORD);
        interferer.device().send("s0",OTHER);
        var outcomes = finish(network(),List.of(tx,narrow,other,interferer),100);
        assertEquals(RadioSerialNetwork.Reception.ACCEPTED,observation(outcomes,rx).outcome());
        assertEquals(RadioSerialNetwork.Reception.INTERFERENCE,observation(outcomes,other).outcome());
        assertEquals(12,WitchReceiverFilter.Width.STANDARD.apply(rx, 6).filter().width());
        assertEquals(24,WitchReceiverFilter.Width.WIDE.apply(rx, 6).filter().width());
    }

    @Test void noCablesTheSameTypedRecordArrivesOnlyAfterAllEncodedRadioBits() {
        int bits = bits(RECORD);
        var occupancy = new ArrayList<Integer>();
        for (var coding : List.of(ROBUST, DENSE)) {
            var tx = radio(0, 100, 10, 20, coding, 128, 4096, 2);
            var rx = radio(1, 100, 10, 20, coding, 128, 4096, 2);
            var net = network();
            assertEquals("queued", tx.device().send("s0", RECORD));
            var elapsed = duration(bits, 32 * coding.bitsPerSymbol());
            occupancy.add(elapsed);
            for (int t = 1; t <= elapsed; t++) {
                var tick = net.tick(List.of(tx, rx));
                assertTrue(tick.fault().isEmpty());
                assertEquals(1, tick.activity().size());
                assertEquals(t == elapsed ? RadioSerialNetwork.ActivityPhase.TRANSMITTED
                    : RadioSerialNetwork.ActivityPhase.TRANSMITTING, tick.activity().getFirst().phase());
                assertEquals(t < elapsed ? "transmitting" : "transmitted", tx.device().status("s0").transmit());
                assertEquals(t == elapsed ? Optional.of(RECORD) : Optional.empty(), rx.device().poll("s0"));
                assertEquals(t == elapsed ? 1 : 0, tick.receptions().size());
            }
            assertTrue(net.tick(List.of(tx, rx)).activity().isEmpty(), "No implicit retransmission");
        }
        System.out.println("Same in-game carrier, " + bits + " encoded bits: " + occupancy + " ticks with fixture coding");
        assertTrue(occupancy.get(1) < occupancy.get(0));
    }

    @Test void overlappingUnmatchedCarrierDegradesOnlyTheAffectedReceiverAndNeverPublishesBadFrames() {
        for (int interfererCenter : List.of(130, 111)) {
            for (boolean reversed : List.of(false, true)) {
                var tx = radio(0, 100, 10, 20, ROBUST, 128, 4096, 2);
                var rx = radio(1, 100, 10, 20, ROBUST, 128, 4096, 2);
                var filtered = radio(2, 100, 10, 20, ROBUST, 128, 4096, 2);
                // Different carrier and decoder: cannot be consumed as a valid record on the target tuner.
                var adjacent = radio(10, interfererCenter, 10, 20, DENSE, 128, 4096, 2);
                assertEquals("queued", tx.device().send("s0", RECORD));
                assertEquals("queued", adjacent.device().send("s0", OTHER));
                var net = network();
                List<RadioSerialNetwork.Radio> radios = List.of(tx, rx, filtered, adjacent);
                if (reversed) radios = radios.reversed();
                var observations = finish(net, radios, 100);
                var atTarget = observation(observations, rx);
                var atOtherLocation = observation(observations, filtered);
                assertEquals("transmitted", tx.device().status("s0").transmit(), "Transmission is not an RX acknowledgement");
                assertEquals(90, atTarget.wanted());
                assertEquals(RadioSerialNetwork.Reception.ACCEPTED, atOtherLocation.outcome());
                assertEquals(RECORD, filtered.device().poll("s0").orElseThrow());
                if (interfererCenter == 130) {
                    assertEquals(RadioSerialNetwork.Reception.ACCEPTED, atTarget.outcome());
                    assertEquals(0, atTarget.interference());
                    assertEquals(RECORD, rx.device().poll("s0").orElseThrow());
                } else {
                    assertEquals(RadioSerialNetwork.Reception.INTERFERENCE, atTarget.outcome());
                    assertEquals(60, atTarget.interference());
                    assertEquals(20, atOtherLocation.interference());
                    assertTrue(rx.device().poll("s0").isEmpty());
                }
                assertEquals(2, observations.size(), "The interfering frame is not visible as decoded data");
            }
        }
    }

    @Test void fractionalCarriersTuneExactlyAndOnlyInterfereWithOverlappingBands() {
        var senderCenter = new CarrierFrequency(200, 3);
        var sameRatio = new CarrierFrequency(400, 6);
        var sameIntegerPart = new CarrierFrequency(199, 3);

        for (boolean overlaps : List.of(false, true)) {
            var tx = radio(0, senderCenter, 1, 1, ROBUST, 128, 4096, 2);
            var rx = radio(1, sameRatio, 1, 1, ROBUST, 128, 4096, 2);
            var wrongTuning = radio(2, sameIntegerPart, 1, 1, ROBUST, 128, 4096, 2);
            // +1/3 overlaps. +1 exactly touches the filter edge, but does not overlap.
            var neighbor = radio(10, new CarrierFrequency(overlaps ? 201 : 203, 3),
                1, 1, DENSE, 128, 4096, 2);

            assertEquals("queued", tx.device().send("s0", RECORD));
            assertEquals("queued", neighbor.device().send("s0", OTHER));
            var observations = finish(network(), List.of(tx, rx, wrongTuning, neighbor), 100);

            var outcome = observation(observations, rx);
            assertEquals(overlaps ? RadioSerialNetwork.Reception.INTERFERENCE : RadioSerialNetwork.Reception.ACCEPTED,
                outcome.outcome());
            assertEquals(overlaps ? 60 : 0, outcome.interference());
            assertEquals(overlaps ? Optional.empty() : Optional.of(RECORD), rx.device().poll("s0"));
            assertTrue(wrongTuning.device().poll("s0").isEmpty(), "Truncating both centers to 66 cannot make them tune");
            assertEquals(1, observations.size());
        }
    }

    @Test void aReceiverThatRetunesUnloadsOrStartsLateCannotRecoverAnAlreadyStartedFrame() {
        for (String event : List.of("retune", "unload", "late")) {
            var tx = radio(0, 100, 10, 20, ROBUST, 128, 4096, 2);
            var rx = radio(1, 100, 10, 20, ROBUST, 128, 4096, 2);
            var net = network();
            assertEquals("queued", tx.device().send("s0", RECORD));
            net.tick(event.equals("late") ? List.of(tx) : List.of(tx, rx));
            if (event.equals("retune")) {
                var changed = new RadioSerialNetwork.Radio(rx.device(), rx.port(), rx.position(), rx.occupied(),
                    new RadioSerialNetwork.Band(110, 20), ROBUST, rx.decoder());
                net.tick(List.of(tx, changed));
            } else if (event.equals("unload")) net.tick(List.of(tx));
            var outcomes = finish(net, List.of(tx, rx), 100);
            assertTrue(rx.device().poll("s0").isEmpty(), event);
            if (event.equals("late")) assertTrue(outcomes.isEmpty());
            else assertEquals(RadioSerialNetwork.Reception.LOST_LOCK, observation(outcomes, rx).outcome());
            assertEquals("queued", tx.device().send("s0", OTHER));
            assertEquals(RadioSerialNetwork.Reception.ACCEPTED, observation(finish(net, List.of(tx, rx), 100), rx).outcome());
            assertEquals(OTHER, rx.device().poll("s0").orElseThrow());
        }
    }

    @Test void twoIndividuallyDecodableOverlapsCanCombineToSpoilTheSameReception() {
        for (int contributors : List.of(1, 2)) {
            var net = new RadioSerialNetwork(LIMITS,
                (source, receiver, occupied, filter) -> source.equals(BlockPos.ZERO) ? 90 : 30,
                (coding, wanted, unwanted) -> wanted > 2 * unwanted);
            var tx = radio(0, 100, 10, 20, ROBUST, 128, 4096, 2);
            var rx = radio(1, 100, 10, 20, ROBUST, 128, 4096, 2);
            var radios = new ArrayList<>(List.of(tx, rx));
            tx.device().send("s0", RECORD);
            for (int i = 0; i < contributors; i++) {
                // One emitter on either side of the desired carrier.
                var noise = radio(10 + i, i == 0 ? 111 : 89, 10, 20, DENSE, 128, 4096, 2);
                noise.device().send("s0", OTHER);
                radios.add(noise);
            }
            var result = observation(finish(net, radios, 100), rx);
            assertEquals(contributors * 30L, result.interference());
            assertEquals(contributors == 1 ? RadioSerialNetwork.Reception.ACCEPTED
                : RadioSerialNetwork.Reception.INTERFERENCE, result.outcome());
            assertEquals(contributors == 1 ? Optional.of(RECORD) : Optional.empty(), rx.device().poll("s0"));
        }
    }

    @Test void boundedReceiveQueueAndSlowHardwareAreNotReplacedByWirelessResources() {
        int tooSmall = SerialRecordCodec.encode(RECORD, CODEC).length - 1;
        var tx = radio(0, 100, 10, 20, DENSE, 24, 4096, 2);
        var rx = radio(1, 100, 10, 20, DENSE, 128, 4096, 1);
        var narrow = radio(2, 100, 10, 20, DENSE, 128, tooSmall, 2);
        var net = network();
        assertEquals("queued", tx.device().send("s0", RECORD));
        for (int t = 1; t < duration(bits(RECORD), 24); t++)
            assertTrue(net.tick(List.of(tx, rx, narrow)).receptions().isEmpty());
        var outcomes = net.tick(List.of(tx, rx, narrow)).receptions();
        assertEquals(RadioSerialNetwork.Reception.FRAME_TOO_LARGE, observation(outcomes, narrow).outcome());
        assertEquals(RadioSerialNetwork.Reception.ACCEPTED, observation(outcomes, rx).outcome());
        assertEquals("queued", tx.device().send("s0", OTHER));
        assertEquals(RadioSerialNetwork.Reception.BUFFER_FULL, observation(finish(net, List.of(tx, rx, narrow), 150), rx).outcome());
        assertEquals(RECORD, rx.device().poll("s0").orElseThrow());
        assertTrue(rx.device().poll("s0").isEmpty());
    }

    @Test void rosterOverLimitFailsClosedRatherThanIgnoringAnInterferer() {
        var net = network();
        var tx = radio(0, 100, 10, 20, ROBUST, 128, 4096, 2);
        var rx = radio(1, 100, 10, 20, ROBUST, 128, 4096, 2);
        tx.device().send("s0", RECORD);
        net.tick(List.of(tx, rx));
        var many = new ArrayList<>(List.of(tx, rx));
        for (int n = 2; n < 5; n++) many.add(radio(n, 100, 10, 20, ROBUST, 128, 4096, 2));
        var fault = net.tick(many);
        assertEquals(Optional.of("port_limit"), fault.fault());
        assertEquals("interrupted", tx.device().status("s0").transmit());
        assertEquals(RadioSerialNetwork.Reception.INTERRUPTED, observation(fault.receptions(), rx).outcome());
        assertTrue(finish(net, List.of(tx, rx), 100).isEmpty());
        assertTrue(rx.device().poll("s0").isEmpty());
    }

    @Test void aLocalPortSwitchInvalidatesOnlyOldRadioWorkAndPreservesReceivedFrames() {
        for (boolean sender : List.of(false, true)) {
            var tx = radio(0, 100, 10, 20, ROBUST, 128, 4096, 2);
            var rx = radio(1, 100, 10, 20, ROBUST, 128, 4096, 2);
            var devices = List.of(tx, rx);
            var net = network();
            var before = new DataValue.RecordValue(Map.of("already_received", new DataValue.BooleanValue(true)));
            assertEquals("queued", tx.device().send("s0", before));
            assertEquals(RadioSerialNetwork.Reception.ACCEPTED,
                observation(finish(net, devices, 100), rx).outcome());
            assertEquals("queued", tx.device().send("s0", RECORD));
            assertEquals(RadioSerialNetwork.ActivityPhase.TRANSMITTING, net.tick(devices).activity().getFirst().phase());
            var switched = sender ? tx.device() : rx.device();
            switched.configure("s0", Direction.EAST, false);
            switched.configure("s0", Direction.EAST, true);
            if (sender) assertEquals("queued", tx.device().send("s0", OTHER));
            var outcomes = finish(net, devices, 100);
            assertEquals(sender ? RadioSerialNetwork.Reception.INTERRUPTED : RadioSerialNetwork.Reception.LOST_LOCK,
                observation(outcomes, rx).outcome());
            assertEquals(Optional.of(before), rx.device().poll("s0"), "a complete queued frame remains readable");
            assertEquals(sender ? Optional.of(OTHER) : Optional.empty(), rx.device().poll("s0"));
            assertTrue(rx.device().poll("s0").isEmpty(), "the old partial frame was not delivered");
            assertEquals("queued", tx.device().send("s0", OTHER));
            assertEquals(RadioSerialNetwork.Reception.ACCEPTED,
                observation(finish(net, devices, 100), rx).outcome());
            assertEquals(Optional.of(OTHER), rx.device().poll("s0"));
        }
    }


    @Test void restoreDoesNotReplayAirborneFramesButLeavesCompleteNativeNbtAvailableOnce() {
        var tx = radio(0, 100, 10, 20, ROBUST, 128, 4096, 2);
        var rx = radio(1, 100, 10, 20, ROBUST, 128, 4096, 2);
        var net = network();
        var tag = new CompoundTag();
        tag.putIntArray("sensor_data", new int[]{7, 11});
        var value = NativeNbtCodec.snapshot(tag, new NativeNbtCodec.Limits(
            CODEC.maxEncodedBytes(), CODEC.maxDecodeHeapBytes(), CODEC.values()));
        var send = tx.device().prepareSend("s0", value);
        assertEquals(SerialResult.send("queued"), send.result());
        assertTrue(send.publish());
        assertEquals(RadioSerialNetwork.Reception.ACCEPTED, observation(finish(net, List.of(tx, rx), 100), rx).outcome());
        assertEquals("queued", tx.device().send("s0", OTHER));
        net.tick(List.of(tx, rx));
        var senderSave = tx.device().write();
        var receiverSave = rx.device().write();
        net.unload();
        var nextTx = radio(0, 100, 10, 20, ROBUST, 128, 4096, 2);
        var nextRx = radio(1, 100, 10, 20, ROBUST, 128, 4096, 2);
        nextTx.device().read(senderSave);
        nextRx.device().read(receiverSave);
        assertEquals("interrupted", nextTx.device().status("s0").transmit());
        assertEquals("reloaded", nextTx.device().status("s0").failure());
        assertTrue(finish(network(), List.of(nextTx, nextRx), 100).isEmpty());
        var read = nextRx.device().prepareReceive("s0", true);
        assertEquals(SerialResult.receive("received", value), read.result());
        assertTrue(read.publish());
        assertEquals(0, nextRx.device().status("s0").receive().frames());
    }

    private static RadioSerialNetwork.Radio radio(int x, int center, int width, int filter,
            RadioSerialNetwork.Coding coding, int bitRate, int bytes, int frames) {
        return radio(x, CarrierFrequency.whole(center), width, filter, coding, bitRate, bytes, frames);
    }
    private static RadioSerialNetwork.Radio radio(int x, CarrierFrequency center, int width, int filter,
            RadioSerialNetwork.Coding coding, int bitRate, int bytes, int frames) {
        var hardware = new SerialHardware(Map.of("s0", new SerialHardware.Port(bitRate, bytes, frames, (long) bytes * frames)));
        var dev = new SerialDevice(() -> hardware, () -> true, () -> CODEC, () -> {});
        dev.configure("s0", Direction.EAST, true);
        return new RadioSerialNetwork.Radio(dev, "s0", new BlockPos(x, 0, 0), new RadioSerialNetwork.Band(center, width),
            new RadioSerialNetwork.Band(center, filter), coding, coding.format());
    }
    private static int bits(DataValue.RecordValue record) { return SerialRecordCodec.encode(record, CODEC).length * 8 + (int) LIMITS.framingBits(); }
    private static int duration(int bits, int perTick) { return (bits + perTick - 1) / perTick; }
    private static List<RadioSerialNetwork.Observation> finish(RadioSerialNetwork net, List<RadioSerialNetwork.Radio> radios, int steps) {
        var results = new ArrayList<RadioSerialNetwork.Observation>();
        for (int t = 0; t < steps; t++) {
            var step = net.tick(radios);
            assertTrue(step.fault().isEmpty());
            results.addAll(step.receptions());
        }
        return results;
    }
    private static RadioSerialNetwork.Observation observation(List<RadioSerialNetwork.Observation> values,
            RadioSerialNetwork.Radio receiver) {
        var port = receiver.device().attachments().getFirst().key();
        return values.stream().filter(v -> v.receiver().equals(port)).findFirst().orElseThrow();
    }
}
