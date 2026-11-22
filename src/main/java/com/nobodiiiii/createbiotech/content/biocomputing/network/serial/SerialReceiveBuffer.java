package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Interface-owned encoded frames, separate from a cable component, program RAM and business receipts. */
public final class SerialReceiveBuffer {
    public record Limits(int maxFrameBytes, int maxFrames, long maxBytes) {
        public Limits {
            if (maxFrameBytes < 1 || maxFrames < 1 || maxBytes < maxFrameBytes)
                throw new IllegalArgumentException("Receive capacity must fit at least one permitted frame");
        }
    }
    public enum Admission { ACCEPTED, FRAME_TOO_LARGE, BUFFER_FULL, EMPTY_PAYLOAD }
    public enum Fault { FRAME_TOO_LARGE, BUFFER_FULL, EMPTY_PAYLOAD, COLLISION }
    public record Status(int frames, long bytes, Set<Fault> faults) {
        public Status { faults = Set.copyOf(faults); }
    }

    private final Limits limits;
    /** Private physical provenance, never part of the wire record or asserted by its sender. */
    public record Frame(byte[] payload, Optional<SharedSerialChannel.Port> source, java.util.UUID identity) {
        public Frame(byte[] payload, Optional<SharedSerialChannel.Port> source) {this(payload,source,java.util.UUID.randomUUID());}
        public Frame { payload = payload.clone(); Objects.requireNonNull(source); Objects.requireNonNull(identity); }
        @Override public byte[] payload() { return payload.clone(); }
    }
    /** Borrow only an immutable view; no caller can mutate or borrow the owned byte array. */
    public static final class Head implements SerialDecoding.Input {
        private final Frame frame;
        private Head(Frame frame){this.frame=frame;}
        public java.util.UUID identity(){return frame.identity;}
        public int size(){return frame.payload.length;}
        public int byteAt(int index){return frame.payload[index]&255;}
    }
    public Optional<Head> head(){return Optional.ofNullable(frames.peekFirst()).map(Head::new);}
    boolean matches(Head head){return frames.peekFirst()!=null&&frames.peekFirst().identity.equals(head.identity());}
    /** Trusted atomic claim: no discarded payload clone at the owner publication boundary. */
    void consume(Head head){if(!matches(head))throw new IllegalStateException("stale");Frame frame=frames.removeFirst();bytes-=frame.payload.length;token=new Object();}
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private final EnumSet<Fault> faults = EnumSet.noneOf(Fault.class);
    private long bytes;
    private Object token = new Object();
    Object token() { return token; }

    private java.util.function.BooleanSupplier admit=()->true;
    void admitWhile(java.util.function.BooleanSupplier gate) { admit=Objects.requireNonNull(gate); }
    public SerialReceiveBuffer(Limits limits) { this.limits = Objects.requireNonNull(limits); }

    public Admission offer(byte[] payload) {
        return offer(new Frame(payload, Optional.empty()));
    }
    Admission offer(byte[] payload, SharedSerialChannel.Port source) {
        return offer(new Frame(payload, Optional.of(source)));
    }
    private Admission offer(Frame frame) {
        byte[] payload = frame.payload;
        Objects.requireNonNull(payload);
        token = new Object();
        if (payload.length == 0) {
            faults.add(Fault.EMPTY_PAYLOAD);
            return Admission.EMPTY_PAYLOAD;
        }
        if (payload.length > limits.maxFrameBytes()) {
            faults.add(Fault.FRAME_TOO_LARGE);
            return Admission.FRAME_TOO_LARGE;
        }
        if (!admit.getAsBoolean() || frames.size() >= limits.maxFrames() || payload.length > limits.maxBytes() - bytes) {
            faults.add(Fault.BUFFER_FULL);
            return Admission.BUFFER_FULL;
        }
        frames.addLast(frame);
        bytes += payload.length;
        return Admission.ACCEPTED;
    }

    /** Explicit hardware dequeue, not automatic program delivery or a durable acknowledgement. */
    public Optional<byte[]> poll() {
        Frame frame = frames.pollFirst();
        if (frame == null) return Optional.empty();
        token = new Object();
        bytes -= frame.payload.length;
        return Optional.of(frame.payload());
    }

    /** Decode or check destination capacity before consuming; callers never borrow the stored array. */
    public Optional<byte[]> peek() { return Optional.ofNullable(frames.peekFirst()).map(Frame::payload); }
    public Optional<SharedSerialChannel.Port> source() { return Optional.ofNullable(frames.peekFirst()).flatMap(Frame::source); }

    public java.util.List<byte[]> snapshot() { return frames.stream().map(Frame::payload).toList(); }
    public java.util.List<Frame> snapshotFrames() { return java.util.List.copyOf(frames); }

    /** Restoring is all-or-nothing; a hardware downgrade must not keep only a prefix of the old queue. */
    public static SerialReceiveBuffer restore(Limits limits, java.util.List<byte[]> frames, Set<Fault> faults) {
        return restoreFrames(limits, frames.stream().map(bytes -> new Frame(bytes, Optional.empty())).toList(), faults);
    }
    public static SerialReceiveBuffer restoreFrames(Limits limits, java.util.List<Frame> frames, Set<Fault> faults) {
        var restored = new SerialReceiveBuffer(limits);
        var identities=new java.util.HashSet<java.util.UUID>();
        for (var frame : frames)
            if (!identities.add(frame.identity())) throw new IllegalArgumentException("invalid_save");
            else if (restored.offer(frame) != Admission.ACCEPTED) throw new IllegalArgumentException("resource_limit");
        restored.faults.addAll(faults);
        return restored;
    }

    /** Local bounded fault latches, not an unbounded queue of rejected payloads or remote ACKs. */
    public void clearFaults() { if (!faults.isEmpty()) { faults.clear(); token = new Object(); } }

    void collision() { faults.add(Fault.COLLISION); token = new Object(); }

    public Status status() { return new Status(frames.size(), bytes, faults); }
}
