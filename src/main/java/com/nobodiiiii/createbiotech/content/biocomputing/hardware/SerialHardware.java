package com.nobodiiiii.createbiotech.content.biocomputing.hardware;

import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialReceiveBuffer;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SharedSerialChannel;
import java.util.*;

/** Resident-owned physical interface capacity. No open-port grant, buffered data, RAM or protocol. */
public record SerialHardware(Map<String, Port> ports) {
    public static final SerialHardware NONE = new SerialHardware(Map.of());

    public SerialHardware {
        HardwarePins.validated(new ArrayList<>(ports.keySet()));
        var copy = new LinkedHashMap<String, Port>();
        ports.forEach((name, port) -> copy.put(name, Objects.requireNonNull(port)));
        ports = Collections.unmodifiableMap(copy);
    }

    /** Independently available circuits, not a full-duplex setting on a shared bus. */
    public record IndependentDuplex(long transmitBitsPerTick, long receiveBitsPerTick) {
        public IndependentDuplex {
            if (transmitBitsPerTick < 1 || receiveBitsPerTick < 1)
                throw new IllegalArgumentException("Positive independent transmitter and receiver rates required");
        }
    }

    /** Rate is bits per medium tick, never instructions per tick; capacities are encoded bytes. */
    public record Port(long bitsPerTick, int maxFrameBytes, int receiveFrames, long receiveBytes,
                       Optional<IndependentDuplex> duplex, int transmitFrames, long transmitBytes) {
        public Port(long bitsPerTick,int maxFrameBytes,int receiveFrames,long receiveBytes,Optional<IndependentDuplex> duplex) {
            this(bitsPerTick,maxFrameBytes,receiveFrames,receiveBytes,duplex,1,maxFrameBytes);
        }
        public Port(long bitsPerTick, int maxFrameBytes, int receiveFrames, long receiveBytes) {
            this(bitsPerTick, maxFrameBytes, receiveFrames, receiveBytes, Optional.empty());
        }
        public Port {
            Objects.requireNonNull(duplex);
            if (bitsPerTick < 1 || maxFrameBytes < 1 || receiveFrames < 1 || receiveBytes < maxFrameBytes || transmitFrames < 1 || transmitBytes < 1)
                throw new IllegalArgumentException("Invalid serial interface capacity");
        }
        public SharedSerialChannel.Hardware transmitter() {
            return new SharedSerialChannel.Hardware(bitsPerTick, maxFrameBytes);
        }
        public SerialReceiveBuffer.Limits receiveLimits() {
            return new SerialReceiveBuffer.Limits(maxFrameBytes, receiveFrames, receiveBytes);
        }
    }

    public void requireSeparateNames(HardwarePins pins) {
        if (!Collections.disjoint(ports.keySet(), pins.xbus()) || !Collections.disjoint(ports.keySet(), pins.simpleIo()))
            throw new IllegalArgumentException("Serial, XBus and Simple I/O names must be separate");
    }
}
