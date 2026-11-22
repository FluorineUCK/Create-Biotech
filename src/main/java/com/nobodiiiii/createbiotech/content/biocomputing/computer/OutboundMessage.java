package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.OutboundProtocol;
import java.util.Objects;
import java.util.UUID;

/** Immutable view of committed outputs. Identity is system-owned; endpoint is a name, not a world handle. */
public record OutboundMessage(UUID eventId, int ordinal, String endpoint, DataValue.RecordValue payload, long sequence,
    OutboundProtocol protocol) {
    public OutboundMessage {
        Objects.requireNonNull(eventId); Objects.requireNonNull(endpoint); Objects.requireNonNull(payload);
        Objects.requireNonNull(protocol);
        if (ordinal < 0 || sequence < 0) throw new IllegalArgumentException("Negative message ordinal or sequence");
    }
    public OutboundMessage(UUID eventId, int ordinal, String endpoint, DataValue.RecordValue payload, long sequence) {
        this(eventId, ordinal, endpoint, payload, sequence, OutboundProtocol.Message.INSTANCE);
    }
    public OutboundMessage(UUID eventId, int ordinal, String endpoint, DataValue.RecordValue payload) {
        this(eventId, ordinal, endpoint, payload, ordinal);
    }
}
