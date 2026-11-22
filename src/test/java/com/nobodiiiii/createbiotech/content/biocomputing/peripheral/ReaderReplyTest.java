package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.SerialHardware;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReaderReplyTest {
    @Test void unrepresentableRefusalTerminatesOnlyItsOwnFrameWithoutRetryingOrClearingTheQueue() {
        var codec = new SerialRecordCodec.Limits(16384, 65536, new ArchiveLimits(4096, 32768, 65536));
        var address = new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(1)));
        var malformed = new DataValue.RecordValue(Map.of("kind", new DataValue.TextValue("reader_command"), "target", address,
            "request", new DataValue.TextValue("r".repeat(128))));
        var bytes = SerialRecordCodec.encode(malformed, codec);
        var hardware = new SerialHardware(Map.of("s0", new SerialHardware.Port(128, bytes.length, 2, bytes.length * 2L)));
        var device = new SerialDevice(() -> hardware, () -> true, () -> codec, () -> {});
        device.configure("s0", Direction.WEST, true);
        var receiver = device.attachments().getFirst().receiver();
        assertEquals(SerialReceiveBuffer.Admission.ACCEPTED, receiver.offer(bytes));
        assertEquals(SerialReceiveBuffer.Admission.ACCEPTED, receiver.offer(bytes));
        var frame = device.prepareReceive("s0");
        var reply = new ReaderSamplingProtocol(UUID.randomUUID(), UUID.randomUUID(), address, Set.of())
            .respond(false, malformed, ids -> { fail("Malformed source must not sample"); return null; }).orElseThrow();
        assertTrue(SerialRecordCodec.encode(reply, codec).length > bytes.length, "Even the error cannot fit this real hardware frame bound");
        assertEquals(Optional.of("reply_unrepresentable"), ReaderSerialBehaviour.publishReply(frame, reply));
        assertEquals(1, device.status("s0").receive().frames(), "Only this terminally rejected command is consumed");
        assertNotEquals("queued", device.status("s0").transmit(), "Do not claim an error reply was sent");
        assertEquals(malformed, device.prepareReceive("s0").result().field("value"), "Following frame and history survive");
        assertFalse(frame.publish(), "Terminal receipt cannot be replayed");
    }
}
