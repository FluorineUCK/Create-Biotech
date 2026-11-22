package com.nobodiiiii.createbiotech.content.biocomputing.hardware;

import com.nobodiiiii.createbiotech.content.biocomputing.network.carrier.CarrierClock;
import com.nobodiiiii.createbiotech.content.biocomputing.network.carrier.CarrierFrequency;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.RadioSerialNetwork;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SharedSerialChannel;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Base half-duplex transceiver offered by a physical wandering trader, not a processor profile. */
public record ResidentRadioHardware(Oscillator oscillator, Link link, SerialHardware.Port interfaceLimit) {
    public static final String PORT = "rf0";
    private static final String CODING = "resident_base_v1";
    static final int MAX_ACTIVE_PORTS = 128; // Safety bound on quadratic interference sampling.
    static final int MAX_PRESETS = 4096; // Upper bound of the finite oscillator table, not a frequency limit.
    private static final ResourceLocation TRADER = ResourceLocation.withDefaultNamespace("wandering_trader");

    public ResidentRadioHardware {
        Objects.requireNonNull(oscillator);
        Objects.requireNonNull(link);
        if (Objects.requireNonNull(interfaceLimit).duplex().isPresent())
            throw new IllegalArgumentException("The base radio is half duplex");
    }

    /** Zero-based preset index in a server-configured finite bank; actual centers are modeled game units. */
    public record Oscillator(int presets, int first, int spacing, int transmitWidth, int receiveWidth) {
        public Oscillator {
            if (presets < 1 || presets > MAX_PRESETS || first < 0 || spacing < 1 || transmitWidth < 1
                || receiveWidth < transmitWidth || first + (presets - 1L) * spacing > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Invalid radio oscillator");
        }

        private int center(int preset) {
            if (preset < 0 || preset >= presets) throw new IllegalArgumentException("radio_preset_out_of_range");
            return first + preset * spacing;
        }

        public CarrierClock.Oscillator reference(int preset) {
            return new CarrierClock.Oscillator(frequency(preset));
        }

        public RadioSerialNetwork.Band emission(int preset) { return emission(frequency(preset)); }
        public RadioSerialNetwork.Band reception(int preset) { return reception(frequency(preset)); }

        private CarrierFrequency frequency(int preset) { return CarrierFrequency.whole(center(preset)); }
        private RadioSerialNetwork.Band emission(CarrierFrequency center) {
            return new RadioSerialNetwork.Band(center, transmitWidth);
        }
        private RadioSerialNetwork.Band reception(CarrierFrequency center) {
            return new RadioSerialNetwork.Band(center, receiveWidth);
        }
    }

    /** Strength is an abstract tolerance model in game units, not real-world RF propagation. */
    public record Link(int symbolsPerTick, int bitsPerSymbol, int rangeBlocks, int minimumSignalToNoise,
                       int activePorts, long framingBits) {
        public Link {
            if (symbolsPerTick < 1 || bitsPerSymbol < 1 || rangeBlocks < 1
                || minimumSignalToNoise < 1 || activePorts < 2 || activePorts > MAX_ACTIVE_PORTS || framingBits < 0)
                throw new IllegalArgumentException("Invalid radio link parameters");
        }

        private long codingRate() { return (long) symbolsPerTick * bitsPerSymbol; }

        private int strength(BlockPos source, BlockPos destination) {
            double x = (double) source.getX() - destination.getX();
            double y = (double) source.getY() - destination.getY();
            double z = (double) source.getZ() - destination.getZ();
            double distanceSquared = x * x + y * y + z * z;
            if (distanceSquared >= (double) rangeBlocks * rangeBlocks) return 0;
            return rangeBlocks - (int) Math.floor(Math.sqrt(distanceSquared));
        }

        private boolean decodes(int wanted, long noise) {
            // wanted > noise * ratio without overflowing when many stations contribute.
            return wanted > 0 && noise <= (wanted - 1L) / minimumSignalToNoise;
        }
    }

    private static boolean isTrader(ResidentHardwareIdentity resident) {
        return resident != null && TRADER.equals(resident.entityType());
    }

    /** Resolve the additional port alongside existing pins, never steal a configured wired name. */
    public SerialHardware expose(ResidentHardwareIdentity resident, SerialHardware wired, HardwarePins pins) {
        if (!isTrader(resident)) return wired;
        var ports = new LinkedHashMap<>(wired.ports());
        if (ports.putIfAbsent(PORT, interfaceLimit) != null)
            throw new IllegalArgumentException("radio_port_conflict");
        var hardware = new SerialHardware(ports);
        hardware.requireSeparateNames(pins);
        return hardware;
    }

    public boolean supports(HardwareSelection selection) {
        return selection.ready() && selection.resident().filter(ResidentRadioHardware::isTrader).isPresent()
            && interfaceLimit.equals(selection.limits().serial().ports().get(PORT));
    }

    /** Caller supplies a physically present, local station and explicit tuning; the radio domain checks the actual open port. */
    public Optional<RadioSerialNetwork.Radio> attached(HardwareSelection selection, SerialDevice device, BlockPos origin, int preset) {
        return attached(selection, device, origin, preset, Optional.empty());
    }

    /**
     * An empty external input means there is no physical connection. A present but invalid clock
     * never falls back to the internal preset. Frame and connection history remains in the domain.
     */
    public Optional<RadioSerialNetwork.Radio> attached(HardwareSelection selection, SerialDevice device, BlockPos origin,
            int preset, Optional<CarrierClock.Output> externalInput) {
        if (!supports(selection)) return Optional.empty();
        var center = externalInput.isPresent() ? externalInput.orElseThrow().frequency()
            : Optional.of(oscillator.frequency(preset));
        return center.map(frequency -> new RadioSerialNetwork.Radio(device, PORT, origin, oscillator.emission(frequency),
            oscillator.reception(frequency), new RadioSerialNetwork.Coding(CODING, link.symbolsPerTick(), link.bitsPerSymbol()), CODING));
    }

    /** Each actual loaded world domain must own its own network, never share a global frequency registry. */
    public RadioSerialNetwork newDomain() {
        var limits = new SharedSerialChannel.Limits(link.activePorts(), interfaceLimit.maxFrameBytes(),
            link.codingRate(), link.framingBits());
        return new RadioSerialNetwork(limits, (source, destination, occupied, filter) -> filter.passedPower(occupied, link.strength(source, destination)),
            (coding, wanted, noise) -> link.decodes(wanted, noise));
    }
}

