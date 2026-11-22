package com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.OutboundMessage;
import java.util.*;
import org.junit.jupiter.api.Test;

class RequestReplyProtocolTest {
    private static final UUID SOURCE = new UUID(0, 1), EVENT = new UUID(0, 2);
    private static final DataValue.RecordValue PAYLOAD = new DataValue.RecordValue(Map.of(
        "kind", new DataValue.TextValue("REPLY"), "value", new DataValue.IntegerValue(7)));

    @Test void legacyConstructorsRemainOrdinaryEvenWhenPlayerFieldsResembleProtocol() {
        assertEquals(MessageProtocol.Message.INSTANCE, new MessageEnvelope(SOURCE, 4, EVENT, 0, PAYLOAD).protocol());
        assertEquals(OutboundProtocol.Message.INSTANCE, new OutboundMessage(EVENT, 0, "target", PAYLOAD).protocol());
        assertEquals(OutboundProtocol.Message.INSTANCE, new OutboundMessage(EVENT, 0, "target", PAYLOAD, 4).protocol());
    }

    @Test void incomingRequestMustMatchItsPhysicalSourceAndEffectIdentity() {
        var correlation = new RequestCorrelation(SOURCE, EVENT, 0);
        var protocol = new MessageProtocol.Request(new RequestContext(correlation, "source"));
        assertEquals(protocol, new MessageEnvelope(SOURCE, 4, EVENT, 0, PAYLOAD, protocol).protocol());
        assertThrows(IllegalArgumentException.class, () -> new MessageEnvelope(new UUID(0, 3), 4, EVENT, 0, PAYLOAD, protocol));
        assertThrows(IllegalArgumentException.class, () -> new MessageEnvelope(SOURCE, 4, new UUID(0, 3), 0, PAYLOAD, protocol));
        assertThrows(IllegalArgumentException.class, () -> new MessageEnvelope(SOURCE, 4, EVENT, 1, PAYLOAD, protocol));
        // A reply is a different effect from the original request, but retains its correlation.
        var reply = new MessageProtocol.Reply(correlation);
        assertEquals(reply, new MessageEnvelope(new UUID(0, 3), 8, new UUID(0, 4), 1, PAYLOAD, reply).protocol());
    }

    @Test void headersRejectNullMetadataAndOnlyObsoleteRepliesAreAcknowledgable() {
        assertThrows(NullPointerException.class, () -> new MessageProtocol.Request(null));
        assertThrows(NullPointerException.class, () -> new MessageProtocol.Reply(null));
        assertThrows(NullPointerException.class, () -> new OutboundProtocol.Request(null));
        assertThrows(NullPointerException.class, () -> new OutboundProtocol.Reply(null));
        assertThrows(NullPointerException.class, () -> new MessageEnvelope(SOURCE, 4, EVENT, 0, PAYLOAD, null));
        assertThrows(NullPointerException.class, () -> new OutboundMessage(EVENT, 0, "target", PAYLOAD, 4, null));
        assertTrue(AdmissionStatus.STALE_REPLY.accepted());
        assertFalse(AdmissionStatus.STALE.accepted()); assertFalse(AdmissionStatus.UNSUPPORTED.accepted());
    }
}
