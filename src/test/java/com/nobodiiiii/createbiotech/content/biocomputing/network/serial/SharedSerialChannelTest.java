package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SharedSerialChannelTest {
    private static final SharedSerialChannel.Port A = new SharedSerialChannel.Port(new UUID(0, 1), "a");
    private static final SharedSerialChannel.Port B = new SharedSerialChannel.Port(new UUID(0, 2), "b");
    private static final SharedSerialChannel.Port C = new SharedSerialChannel.Port(new UUID(0, 3), "c");
    private static final SerialRecordCodec.Limits RECORDS = new SerialRecordCodec.Limits(
        4096, 100_000, new ArchiveLimits(100, 1000, 2000));

    @Test void completeRecordReachesOnlyOtherExplicitReceiversWithoutAcknowledgingTheirAcceptance() {
        var a = new SerialReceiveBuffer(new SerialReceiveBuffer.Limits(4096, 1, 4096));
        var b = new SerialReceiveBuffer(new SerialReceiveBuffer.Limits(4096, 1, 4096));
        var channel = new SharedSerialChannel(new SharedSerialChannel.Limits(2, 4096, 8, 0),
            Map.of(A, new SharedSerialChannel.Hardware(8, 4096), B, new SharedSerialChannel.Hardware(8, 4096)),
            Map.of(A, a, B, b));
        var value = new DataValue.RecordValue(Map.of("reading", new DataValue.IntegerValue(12)));
        byte[] bytes = SerialRecordCodec.encode(value, RECORDS);
        assertEquals(SharedSerialChannel.Admission.STARTED, channel.transmit(A, bytes));
        for (int i = 1; i < bytes.length; i++) {
            assertTrue(channel.advance().isEmpty());
            assertTrue(b.poll().isEmpty(), "Partial frames never become received records");
        }
        assertEquals(SharedSerialChannel.Outcome.TRANSMITTED, channel.advance().getFirst().outcome());
        assertTrue(a.poll().isEmpty(), "The interface has no implicit local echo");
        assertEquals(1, b.status().frames());
        assertEquals(bytes.length, b.status().bytes());
        assertEquals(value, SerialRecordCodec.decode(b.poll().orElseThrow(), RECORDS));
        assertEquals(0, b.status().bytes());
        assertTrue(b.poll().isEmpty(), "No duplicate receive or hidden retry");
    }

    @Test void branchReceiversHaveIndependentCapacityAndLocalFaultsNeverBecomeAnImplicitSenderAck() {
        var b = new SerialReceiveBuffer(new SerialReceiveBuffer.Limits(4, 1, 4));
        var c = new SerialReceiveBuffer(new SerialReceiveBuffer.Limits(4, 2, 8));
        var channel = new SharedSerialChannel(new SharedSerialChannel.Limits(3, 4, 32, 0),
            Map.of(A, new SharedSerialChannel.Hardware(32, 4), B, new SharedSerialChannel.Hardware(32, 4),
                C, new SharedSerialChannel.Hardware(32, 4)), Map.of(B, b, C, c));
        for (byte value : new byte[] {1, 2}) {
            channel.transmit(A, new byte[] {value});
            assertEquals(SharedSerialChannel.Outcome.TRANSMITTED, channel.advance().getFirst().outcome(),
                "Source emission outcome is not a receiver or business acknowledgement");
        }
        assertEquals(1, b.status().frames()); assertEquals(2, c.status().frames());
        assertEquals(java.util.Set.of(SerialReceiveBuffer.Fault.BUFFER_FULL), b.status().faults());
        assertTrue(c.status().faults().isEmpty());
        byte[] firstB = b.poll().orElseThrow(); firstB[0] = 99;
        assertArrayEquals(new byte[] {1}, c.poll().orElseThrow(), "Receivers never share mutable arrays");
        assertArrayEquals(new byte[] {2}, c.poll().orElseThrow());
        var frozen = b.status();
        channel.transmit(A, new byte[] {3}); channel.advance();
        assertEquals(frozen.faults(), b.status().faults(), "Successful traffic does not hide an unread overflow");
        b.clearFaults();
        assertTrue(b.status().faults().isEmpty());
        assertFalse(frozen.faults().isEmpty(), "Status snapshots cannot be mutated by hardware progression");
        assertEquals(1, b.status().frames(), "Clearing diagnostics must not consume frames");
        assertThrows(UnsupportedOperationException.class, () -> frozen.faults().clear());
    }

    @Test void collidingFramesRaiseReceiverFaultsWithoutReplacingPreviouslyReceivedData() {
        for (var order : List.of(List.of(A, B), List.of(B, A))) {
            var c = new SerialReceiveBuffer(new SerialReceiveBuffer.Limits(4, 3, 12));
            c.offer(new byte[] {7});
            var channel = new SharedSerialChannel(new SharedSerialChannel.Limits(3, 4, 8, 0),
                Map.of(A, new SharedSerialChannel.Hardware(8, 4), B, new SharedSerialChannel.Hardware(8, 4),
                    C, new SharedSerialChannel.Hardware(8, 4)), Map.of(C, c));
            channel.transmit(order.getFirst(), new byte[] {1, 2});
            channel.transmit(order.getLast(), new byte[] {3});
            assertEquals(SharedSerialChannel.Outcome.COLLISION, channel.advance().getFirst().outcome());
            assertTrue(c.status().faults().stream().anyMatch(fault -> fault.name().equals("COLLISION")));
            assertEquals(SharedSerialChannel.Outcome.COLLISION, channel.advance().getFirst().outcome());
            assertEquals(1, c.status().frames());
            assertArrayEquals(new byte[] {7}, c.poll().orElseThrow());
            assertTrue(c.poll().isEmpty(), "Neither collided payload nor a prefix is a received frame");
        }
    }

    @Test void undeclaredOrSharedReceiverBindingsAreRejectedRatherThanPoolingPhysicalHardware() {
        var ports = Map.of(A, new SharedSerialChannel.Hardware(8, 4), B, new SharedSerialChannel.Hardware(8, 4));
        var limits = new SharedSerialChannel.Limits(2, 4, 8, 0);
        var buffer = new SerialReceiveBuffer(new SerialReceiveBuffer.Limits(4, 1, 4));
        assertThrows(IllegalArgumentException.class, () -> new SharedSerialChannel(limits, ports, Map.of(C, buffer)));
        assertThrows(IllegalArgumentException.class, () -> new SharedSerialChannel(limits, ports, Map.of(A, buffer, B, buffer)));
        assertTrue(buffer.poll().isEmpty(), "Rejected topology has no receive side effects");
    }

    @Test void receivedFramesBelongToTheInterfaceNotTheCableAndFrameSizeDifferencesRemainIndependent() {
        var b = new SerialReceiveBuffer(new SerialReceiveBuffer.Limits(1, 1, 1));
        var c = new SerialReceiveBuffer(new SerialReceiveBuffer.Limits(4, 2, 8));
        var ports = Map.of(A, new SharedSerialChannel.Hardware(32, 4), B, new SharedSerialChannel.Hardware(32, 4),
            C, new SharedSerialChannel.Hardware(32, 4));
        var limits = new SharedSerialChannel.Limits(3, 4, 32, 0);
        var original = new SharedSerialChannel(limits, ports, Map.of(B, b, C, c));
        original.transmit(A, new byte[] {1, 2});
        var completed = original.advance().getFirst();
        assertEquals(SharedSerialChannel.Outcome.TRANSMITTED, completed.outcome());
        completed.payload().orElseThrow()[0] = 99;
        assertTrue(b.poll().isEmpty());
        assertEquals(java.util.Set.of(SerialReceiveBuffer.Fault.FRAME_TOO_LARGE), b.status().faults());
        var rebuilt = new SharedSerialChannel(limits, ports, Map.of(B, b, C, c));
        assertTrue(rebuilt.advance().isEmpty());
        assertArrayEquals(new byte[] {1, 2}, c.poll().orElseThrow(), "Changing the cable must not own or clear interface frames");
        rebuilt.transmit(A, new byte[] {3}); rebuilt.advance();
        assertArrayEquals(new byte[] {3}, b.poll().orElseThrow());
        assertEquals(java.util.Set.of(SerialReceiveBuffer.Fault.FRAME_TOO_LARGE), b.status().faults());
        assertArrayEquals(new byte[] {3}, c.poll().orElseThrow());
    }

    @Test void eitherPortCanSendButTheWholeRecordNeedsItsRealEncodedTransmissionTime() {
        var channel = new SharedSerialChannel(new SharedSerialChannel.Limits(2, 4096, 17, 13),
            Map.of(A, new SharedSerialChannel.Hardware(31, 4096), B, new SharedSerialChannel.Hardware(7, 4096)));
        var record = new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(12)));
        byte[] bytes = SerialRecordCodec.encode(record, RECORDS);
        for (var source : List.of(A, B)) {
            long rate = source.equals(A) ? 17 : 7;
            long bits = bytes.length * 8L + 13;
            long steps = bits / rate + (bits % rate == 0 ? 0 : 1);
            assertEquals(SharedSerialChannel.Admission.STARTED, channel.transmit(source, bytes));
            for (long i = 1; i < steps; i++) assertTrue(channel.advance().isEmpty(), "Partial frames are not records");
            var result = channel.advance();
            assertEquals(1, result.size());
            assertEquals(source, result.getFirst().source());
            assertEquals(SharedSerialChannel.Outcome.TRANSMITTED, result.getFirst().outcome());
            assertEquals(record, SerialRecordCodec.decode(result.getFirst().payload().orElseThrow(), RECORDS));
            assertTrue(channel.advance().isEmpty(), "Completion must not be replayed or retried");
        }
    }

    @Test void overlappingEmissionsAllCollideIndependentOfSubmissionOrderAndAreNotRetried() {
        for (var order : List.of(List.of(A, B), List.of(B, A))) {
            var channel = channel(8);
            for (var source : order) assertEquals(SharedSerialChannel.Admission.STARTED, channel.transmit(source, new byte[] {1, 2}));
            assertTrue(channel.advance().isEmpty(), "A collision does not give away free transmission time");
            var results = channel.advance();
            assertEquals(2, results.size());
            assertEquals(java.util.Set.of(A, B), results.stream().map(SharedSerialChannel.Completion::source).collect(java.util.stream.Collectors.toSet()));
            for (var result : results) {
                assertEquals("COLLISION", result.outcome().name());
                assertTrue(result.payload().isEmpty(), "A damaged frame must not become a typed record");
            }
            assertTrue(channel.advance().isEmpty(), "No hidden retry or winning sender");
            assertEquals(SharedSerialChannel.Admission.STARTED, channel.transmit(B, new byte[] {3}));
            assertEquals(SharedSerialChannel.Outcome.TRANSMITTED, channel.advance().getFirst().outcome());
        }
    }

    @Test void anEmptyNonRecordCannotCreateAZeroBitEmissionOrDisruptTheOtherPort() {
        var channel = channel(8);
        channel.transmit(A, new byte[] {1});
        assertEquals("EMPTY_PAYLOAD", channel.transmit(B, new byte[0]).name());
        var result = channel.advance();
        assertEquals(1, result.size());
        assertEquals(SharedSerialChannel.Outcome.TRANSMITTED, result.getFirst().outcome());
    }

    @Test void joiningMidFrameDamagesBothFramesEvenIfOneFinishesEarlier() {
        var channel = channel(8);
        channel.transmit(A, new byte[] {1, 2, 3});
        assertTrue(channel.advance().isEmpty());
        channel.transmit(B, new byte[] {9});
        var first = channel.advance();
        assertEquals(1, first.size());
        assertEquals(B, first.getFirst().source());
        assertEquals(SharedSerialChannel.Outcome.COLLISION, first.getFirst().outcome());
        var last = channel.advance();
        assertEquals(A, last.getFirst().source());
        assertEquals(SharedSerialChannel.Outcome.COLLISION, last.getFirst().outcome());
        assertTrue(first.getFirst().payload().isEmpty());
        assertTrue(last.getFirst().payload().isEmpty());
    }

    @Test void busyPortDoesNotOverwriteAndRejectedAttemptsDoNotBecomePhysicalEmissions() {
        var channel = new SharedSerialChannel(new SharedSerialChannel.Limits(2, 4, 8, 0),
            Map.of(A, new SharedSerialChannel.Hardware(8, 2), B, new SharedSerialChannel.Hardware(8, 4)));
        assertEquals(SharedSerialChannel.Admission.PAYLOAD_TOO_LARGE, channel.transmit(A, new byte[3]));
        byte[] input = {1, 2};
        assertEquals(SharedSerialChannel.Admission.STARTED, channel.transmit(A, input));
        input[0] = 99;
        assertEquals(SharedSerialChannel.Admission.PORT_BUSY, channel.transmit(A, new byte[] {9}));
        assertEquals(SharedSerialChannel.Admission.UNKNOWN_PORT,
            channel.transmit(new SharedSerialChannel.Port(new UUID(0, 3), "c"), new byte[] {3}));
        assertEquals(SharedSerialChannel.Admission.PAYLOAD_TOO_LARGE, channel.transmit(B, new byte[5]));
        assertTrue(channel.advance().isEmpty());
        var completion = channel.advance().getFirst();
        assertEquals(SharedSerialChannel.Outcome.TRANSMITTED, completion.outcome());
        byte[] output = completion.payload().orElseThrow();
        assertArrayEquals(new byte[] {1, 2}, output);
        output[0] = 77;
        assertArrayEquals(new byte[] {1, 2}, completion.payload().orElseThrow());
        assertTrue(channel.advance().isEmpty());
    }

    @Test void portCountAndNumericBudgetsDoNotTruncateTopologyOrOverflowTransmissionTime() {
        var ports = Map.of(A, new SharedSerialChannel.Hardware(8, 4), B, new SharedSerialChannel.Hardware(8, 4));
        assertThrows(IllegalArgumentException.class,
            () -> new SharedSerialChannel(new SharedSerialChannel.Limits(1, 4, 8, 0), ports));
        assertThrows(IllegalArgumentException.class, () -> new SharedSerialChannel.Hardware(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new SharedSerialChannel.Hardware(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new SharedSerialChannel.Limits(2, 4, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new SharedSerialChannel.Limits(2, 4, 8, -1));
        assertThrows(IllegalArgumentException.class, () -> new SharedSerialChannel.Limits(2, 4, 8, Long.MAX_VALUE));
        var channel = new SharedSerialChannel(new SharedSerialChannel.Limits(1, 1, Long.MAX_VALUE - 1, Long.MAX_VALUE - 8),
            Map.of(A, new SharedSerialChannel.Hardware(Long.MAX_VALUE, 1)));
        channel.transmit(A, new byte[] {1});
        assertTrue(channel.advance().isEmpty(), "The final bit must not disappear through rounding or overflow");
        assertEquals(SharedSerialChannel.Outcome.TRANSMITTED, channel.advance().getFirst().outcome());
    }

    private static SharedSerialChannel channel(long rate) {
        return new SharedSerialChannel(new SharedSerialChannel.Limits(2, 4096, rate, 0),
            Map.of(A, new SharedSerialChannel.Hardware(rate, 4096), B, new SharedSerialChannel.Hardware(rate, 4096)));
    }
}
