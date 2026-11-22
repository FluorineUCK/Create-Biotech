package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One physical shared channel, with one bounded ongoing emission per explicitly supplied hardware port.
 * The host collects all emissions for a medium step before advancing it; no master or waiting queue exists.
 * This core neither schedules world ticks nor promises reception, persistence or business completion.
 */
public final class SharedSerialChannel implements SerialChannel {
    /** Identity of a physical hardware port, not one of its public aliases or a player's frame address. */
    public record Port(UUID device, String name) {
        public Port {
            Objects.requireNonNull(device);
            Objects.requireNonNull(name);
            if (name.isBlank()) throw new IllegalArgumentException("Physical port name required");
        }
    }
    public record Hardware(long bitsPerStep, int maxFrameBytes) {
        public Hardware {
            if (bitsPerStep < 1 || maxFrameBytes < 1) throw new IllegalArgumentException("Positive hardware limits required");
        }
    }
    public record Limits(int maxPorts, int maxFrameBytes, long bitsPerStep, long framingBits) {
        public Limits {
            if (maxPorts < 1 || maxFrameBytes < 1 || bitsPerStep < 1 || framingBits < 0
                || framingBits > Long.MAX_VALUE - maxFrameBytes * 8L)
                throw new IllegalArgumentException("Invalid shared-channel limits");
        }
    }
    public enum Admission { STARTED, PORT_BUSY, UNKNOWN_PORT, PAYLOAD_TOO_LARGE, EMPTY_PAYLOAD }
    public enum Outcome { TRANSMITTED, COLLISION }

    /** TRANSMITTED means emission ended, not that any receiver accepted the record or performed its request. */
    public static final class Completion {
        private final Port source;
        private final Outcome outcome;
        private final byte[] payload;
        private Completion(Port source, Outcome outcome, byte[] payload) {
            this.source = source;
            this.outcome = outcome;
            this.payload = payload;
        }
        public Port source() { return source; }
        public Outcome outcome() { return outcome; }
        public Optional<byte[]> payload() { return payload == null ? Optional.empty() : Optional.of(payload.clone()); }
    }

    private final Limits limits;
    private final Map<Port, Hardware> hardware;
    private final Map<Port, SerialReceiveBuffer> receivers;
    private final Map<Port, Emission> active = new LinkedHashMap<>();

    /** Read-only physical activity, without bytes, interface identities or receive acknowledgements. */
    @Override public boolean transmitting() { return !active.isEmpty(); }
    @Override public boolean collided() { return active.values().stream().anyMatch(emission -> emission.collided); }

    public SharedSerialChannel(Limits limits, Map<Port, Hardware> hardware) {
        this(limits, hardware, Map.of());
    }

    /** Supplied buffers belong to their physical interfaces and survive rebuilding this component. */
    public SharedSerialChannel(Limits limits, Map<Port, Hardware> hardware, Map<Port, SerialReceiveBuffer> receivers) {
        this.limits = Objects.requireNonNull(limits);
        Objects.requireNonNull(hardware);
        if (hardware.size() > limits.maxPorts()) throw new IllegalArgumentException("Port topology exceeds limits");
        this.hardware = Map.copyOf(hardware);
        this.receivers = Map.copyOf(receivers);
        if (!this.hardware.keySet().containsAll(this.receivers.keySet()))
            throw new IllegalArgumentException("Receiver requires a declared physical port");
        var independent = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<SerialReceiveBuffer, Boolean>());
        for (var receiver : this.receivers.values())
            if (!independent.add(receiver)) throw new IllegalArgumentException("Physical ports cannot share a receive buffer");
    }

    @Override public Admission transmit(Port source, byte[] payload) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(payload);
        var port = hardware.get(source);
        if (port == null) return Admission.UNKNOWN_PORT;
        if (active.containsKey(source)) return Admission.PORT_BUSY;
        if (payload.length == 0) return Admission.EMPTY_PAYLOAD;
        if (payload.length > Math.min(port.maxFrameBytes(), limits.maxFrameBytes())) return Admission.PAYLOAD_TOO_LARGE;
        var emission = new Emission(payload.clone(), payload.length * 8L + limits.framingBits(),
            Math.min(port.bitsPerStep(), limits.bitsPerStep()));
        if (!active.isEmpty()) {
            emission.collided = true;
            active.values().forEach(other -> other.collided = true);
        }
        active.put(source, emission);
        return Admission.STARTED;
    }

    /** One explicit medium interval, not a Computer instruction or an implicit catch-up loop. */
    @Override public List<Completion> advance() {
        var result = new ArrayList<Completion>();
        var iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var emission = entry.getValue();
            if (emission.remainingBits > emission.bitsPerStep) {
                emission.remainingBits -= emission.bitsPerStep;
                continue;
            }
            result.add(new Completion(entry.getKey(), emission.collided ? Outcome.COLLISION : Outcome.TRANSMITTED,
                emission.collided ? null : emission.payload));
            receivers.forEach((port, receiver) -> {
                if (port.equals(entry.getKey())) return;
                if (emission.collided) receiver.collision();
                else receiver.offer(emission.payload, entry.getKey());
            });
            iterator.remove();
        }
        return List.copyOf(result);
    }

    private static final class Emission {
        private final byte[] payload;
        private final long bitsPerStep;
        private long remainingBits;
        private boolean collided;
        private Emission(byte[] payload, long remainingBits, long bitsPerStep) {
            this.payload = payload;
            this.remainingBits = remainingBits;
            this.bitsPerStep = bitsPerStep;
        }
    }
}
