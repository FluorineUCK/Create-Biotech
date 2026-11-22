package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialReceiveBuffer.Admission.*;
import org.junit.jupiter.api.Test;

class SerialReceiveBufferTest {
    @Test void callerMutationsAndClearingFaultsNeverRewriteRetainedFrames() {
        var buffer = new SerialReceiveBuffer(new SerialReceiveBuffer.Limits(2, 2, 4));
        byte[] source = {1, 2};
        assertEquals(ACCEPTED, buffer.offer(source));
        assertEquals(ACCEPTED, buffer.offer(source));
        source[0] = 99;
        assertEquals(FRAME_TOO_LARGE, buffer.offer(new byte[3]));
        assertEquals(EMPTY_PAYLOAD, buffer.offer(new byte[0]));
        assertEquals(BUFFER_FULL, buffer.offer(new byte[] {7}));
        assertEquals(java.util.Set.of(SerialReceiveBuffer.Fault.FRAME_TOO_LARGE, SerialReceiveBuffer.Fault.EMPTY_PAYLOAD,
            SerialReceiveBuffer.Fault.BUFFER_FULL), buffer.status().faults());
        buffer.clearFaults();
        assertEquals(4, buffer.status().bytes());
        assertTrue(buffer.status().faults().isEmpty());
        byte[] first = buffer.poll().orElseThrow();
        assertArrayEquals(new byte[] {1, 2}, first);
        first[0] = 77;
        assertArrayEquals(new byte[] {1, 2}, buffer.poll().orElseThrow());
    }

    @Test void capacityIsExplicitValidAndByteAccountingDoesNotOverflowAtLongCeilings() {
        assertThrows(IllegalArgumentException.class, () -> new SerialReceiveBuffer.Limits(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new SerialReceiveBuffer.Limits(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new SerialReceiveBuffer.Limits(2, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new SerialReceiveBuffer.Limits(1, 1, -1));
        assertThrows(NullPointerException.class, () -> new SerialReceiveBuffer(null));
        var buffer = new SerialReceiveBuffer(new SerialReceiveBuffer.Limits(3, 2, Long.MAX_VALUE));
        assertEquals(ACCEPTED, buffer.offer(new byte[] {1, 2, 3}));
        assertEquals(ACCEPTED, buffer.offer(new byte[] {4, 5}));
        assertEquals(5, buffer.status().bytes());
        assertEquals(BUFFER_FULL, buffer.offer(new byte[] {6}));
        buffer.poll(); buffer.poll();
        assertEquals(0, buffer.status().bytes());
    }

    @Test void bothByteAndFrameLimitsRejectTheWholeNewFrameWithoutOverwritingOldData() {
        var byCount = new SerialReceiveBuffer(new SerialReceiveBuffer.Limits(4, 1, 20));
        assertEquals(ACCEPTED, byCount.offer(new byte[] {1}));
        assertEquals(BUFFER_FULL, byCount.offer(new byte[] {2}));
        assertEquals(1, byCount.status().frames());
        assertEquals(1, byCount.status().bytes());
        assertArrayEquals(new byte[] {1}, byCount.poll().orElseThrow());
        assertTrue(byCount.poll().isEmpty());
        assertEquals(ACCEPTED, byCount.offer(new byte[] {3}));

        var byBytes = new SerialReceiveBuffer(new SerialReceiveBuffer.Limits(4, 4, 5));
        assertEquals(ACCEPTED, byBytes.offer(new byte[] {1, 2, 3}));
        assertEquals(ACCEPTED, byBytes.offer(new byte[] {4, 5}));
        assertEquals(BUFFER_FULL, byBytes.offer(new byte[] {6}));
        assertEquals(FRAME_TOO_LARGE, byBytes.offer(new byte[5]));
        assertEquals(EMPTY_PAYLOAD, byBytes.offer(new byte[0]));
        assertEquals(2, byBytes.status().frames());
        assertEquals(5, byBytes.status().bytes());
        assertArrayEquals(new byte[] {1, 2, 3}, byBytes.poll().orElseThrow());
        assertEquals(ACCEPTED, byBytes.offer(new byte[] {6, 7, 8}));
        assertArrayEquals(new byte[] {4, 5}, byBytes.poll().orElseThrow());
        assertArrayEquals(new byte[] {6, 7, 8}, byBytes.poll().orElseThrow());
        assertEquals(0, byBytes.status().bytes());
    }
}
