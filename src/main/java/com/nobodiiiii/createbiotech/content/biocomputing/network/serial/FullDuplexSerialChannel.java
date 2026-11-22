package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Two point-to-point directions. A port never transmits on its own receive direction. */
final class FullDuplexSerialChannel implements SerialChannel {
    private final SharedSerialChannel.Port first;
    private final SharedSerialChannel.Port second;
    private final SharedSerialChannel firstToSecond;
    private final SharedSerialChannel secondToFirst;

    FullDuplexSerialChannel(SharedSerialChannel.Limits limits, SerialDevice.Attachment first,
                            SerialDevice.Attachment second) {
        this.first = first.key();
        this.second = second.key();
        if (this.first.equals(this.second) || first.receiver() == second.receiver())
            throw new IllegalArgumentException("Full duplex requires separate physical interfaces");
        firstToSecond = lane(limits, first, second);
        secondToFirst = lane(limits, second, first);
    }

    private static SharedSerialChannel lane(SharedSerialChannel.Limits limits, SerialDevice.Attachment source,
                                            SerialDevice.Attachment recipient) {
        var tx = source.hardware().duplex().orElseThrow();
        var rx = recipient.hardware().duplex().orElseThrow();
        var emitter = new SharedSerialChannel.Hardware(Math.min(tx.transmitBitsPerTick(), rx.receiveBitsPerTick()),
            source.hardware().maxFrameBytes());
        // The one-lane engine is never offered work from its recipient. This buffer still owns
        // its normal frame/queue admission and observes overflow separately from TX completion.
        return new SharedSerialChannel(limits, Map.of(source.key(), emitter, recipient.key(), recipient.hardware().transmitter()),
            Map.of(recipient.key(), recipient.receiver()));
    }

    @Override public SharedSerialChannel.Admission transmit(SharedSerialChannel.Port source, byte[] payload) {
        if (first.equals(source)) return firstToSecond.transmit(source, payload);
        if (second.equals(source)) return secondToFirst.transmit(source, payload);
        return SharedSerialChannel.Admission.UNKNOWN_PORT;
    }

    @Override public List<SharedSerialChannel.Completion> advance() {
        var firstDirection = firstToSecond.advance();
        var secondDirection = secondToFirst.advance();
        if (firstDirection.isEmpty()) return secondDirection;
        if (secondDirection.isEmpty()) return firstDirection;
        var result = new ArrayList<>(firstDirection);
        result.addAll(secondDirection);
        return List.copyOf(result);
    }

    @Override public boolean transmitting() { return firstToSecond.transmitting() || secondToFirst.transmitting(); }
    @Override public boolean collided() { return false; }
}
