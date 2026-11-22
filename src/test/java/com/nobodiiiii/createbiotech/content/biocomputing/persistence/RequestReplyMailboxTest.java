package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveData.*;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RequestReplyMailboxTest {
    private static final UUID SOURCE = new UUID(0, 1), EVENT = new UUID(0, 2);
    private static final RequestCorrelation KEY = new RequestCorrelation(SOURCE, EVENT, 0);

    @Test void schemaTwoPreservesAllHeadersThroughValueNbtAndChargesThem() {
        var state = MailboxState.empty();
        var protocols = List.of(MessageProtocol.Message.INSTANCE,
            new MessageProtocol.Request(new RequestContext(KEY, "source")), new MessageProtocol.Reply(KEY));
        for (int i = 0; i < protocols.size(); i++) {
            var message = new MessageEnvelope(SOURCE, i, EVENT, 0, obj("value", i), protocols.get(i));
            var admitted = state.admit(message, MailboxLimits.DEFAULT);
            assertEquals(AdmissionStatus.ACCEPTED, admitted.status()); state = admitted.state();
        }
        var value = MailboxArchiveCodec.encode(state);
        assertEquals(new DataValue.IntegerValue(2), value.field("version"));
        assertEquals(state, MailboxArchiveCodec.decode(ValueNbtCodec.decode(ValueNbtCodec.encode(value,
            ValueNbtCodecTest.LIMITS), ValueNbtCodecTest.LIMITS)));
        assertEquals(value.storageUnits(), state.storageUnits());
        var ordinary = new MessageEnvelope(SOURCE, 0, EVENT, 0, obj("value", 0));
        var request = new MessageEnvelope(SOURCE, 0, EVENT, 0, obj("value", 0), protocols.get(1));
        long difference = MailboxState.empty().admit(request, MailboxLimits.DEFAULT).state().storageUnits()
            - MailboxState.empty().admit(ordinary, MailboxLimits.DEFAULT).state().storageUnits();
        assertEquals(protocols.get(1).value().storageUnits() - protocols.getFirst().value().storageUnits(), difference);
    }

    @Test void literalSchemaOneHasNoHeaderAndNeverInfersOneFromPlayerData() {
        var identity = obj("source", SOURCE.toString(), "sequence", 0, "event_id", EVENT.toString(), "ordinal", 0);
        var envelope = new LinkedHashMap<>(identity.fields()); envelope.put("payload", obj("kind", "REQUEST", "value", 7));
        var literal = obj("version", 1, "queued", new DataValue.ListValue(List.of(new DataValue.RecordValue(envelope))),
            "receipts", new DataValue.ListValue(List.of(identity)));
        var restored = MailboxArchiveCodec.decode(literal);
        assertEquals(MessageProtocol.Message.INSTANCE, restored.queued().getFirst().protocol());
        assertEquals(obj("kind", "REQUEST", "value", 7), restored.queued().getFirst().payload());
    }

    @Test void literalSchemaTwoRejectsUnknownHeadersExtraFieldsAndForgedRequestIdentity() {
        var context = obj("correlation", obj("requester", SOURCE.toString(), "event_id", EVENT.toString(), "ordinal", 0),
            "return_endpoint", "source");
        assertEquals(new MessageProtocol.Request(new RequestContext(KEY, "source")),
            MailboxArchiveCodec.decode(literal(obj("kind", "REQUEST", "context", context))).queued().getFirst().protocol());
        assertThrows(ArchiveException.class, () -> MailboxArchiveCodec.decode(literal(obj("kind", "UNKNOWN"))));
        assertThrows(ArchiveException.class, () -> MailboxArchiveCodec.decode(literal(obj("kind", "MESSAGE", "context", context))));
        var forged = obj("correlation", obj("requester", new UUID(0, 3).toString(), "event_id", EVENT.toString(), "ordinal", 0),
            "return_endpoint", "source");
        assertThrows(ArchiveException.class, () -> MailboxArchiveCodec.decode(literal(obj("kind", "REQUEST", "context", forged))));
        assertThrows(ArchiveException.class, () -> MailboxArchiveCodec.decode(literal(obj("kind", "REQUEST"))));
    }

    private static DataValue.RecordValue literal(DataValue protocol) {
        var identity = obj("source", SOURCE.toString(), "sequence", 0, "event_id", EVENT.toString(), "ordinal", 0);
        var envelope = new LinkedHashMap<>(identity.fields()); envelope.put("payload", obj("value", 7)); envelope.put("protocol", protocol);
        return obj("version", 2, "queued", new DataValue.ListValue(List.of(new DataValue.RecordValue(envelope))),
            "receipts", new DataValue.ListValue(List.of(identity)));
    }
}
