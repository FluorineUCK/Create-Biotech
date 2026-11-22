package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import com.nobodiiiii.createbiotech.content.biocomputing.network.carrier.CarrierFrequency;
import java.util.*;
import net.minecraft.core.BlockPos;

/**
 * Bounded in-game radio domain. Its host supplies the complete loaded roster and a propagation
 * and decoding policy. This is not a registered block, a channel-name transport, or a world scan.
 */
public final class RadioSerialNetwork {
    /** Abstract game units. Adjacent envelopes with no common width do not overlap. */
    public record Band(CarrierFrequency center, int width) {
        public Band {
            Objects.requireNonNull(center);
            if (width <= 0) throw new IllegalArgumentException("Invalid radio band");
        }
        public Band(int center, int width) { this(CarrierFrequency.whole(center), width); }

        boolean overlaps(Band other) {
            long commonDenominator = (long) center.denominator() * other.center.denominator();
            long separation = Math.abs((long) center.numerator() * other.center.denominator()
                - (long) other.center.numerator() * center.denominator());
            // Twice the cross-product difference fits in a long for nonnegative int components.
            // Compare against an integer width sum via division so width * denominator cannot overflow.
            return (2 * separation) / commonDenominator < (long) width + other.width;
        }
        /** Fraction of the emitted rectangular power spectrum that reaches this passband. */
        public int passedPower(Band emitted, int incident) {
            if (!overlaps(emitted) || incident == 0) return 0;
            double distance = Math.abs((long)center.numerator() * emitted.center.denominator()
                - (long)emitted.center.numerator() * center.denominator())
                / ((long)center.denominator() * (double)emitted.center.denominator());
            double overlap = Math.min(Math.min(width, emitted.width), ((long)width + emitted.width) / 2.0 - distance);
            return (int)Math.round(incident * Math.max(0, overlap) / emitted.width);
        }

        boolean containsTunedSignal(Band outgoing) {
            return center.equals(outgoing.center) && width >= outgoing.width;
        }
    }

    /** Symbol budget and information density do not determine the carrier or its band width. */
    public record Coding(String format, int symbolsPerTick, int bitsPerSymbol) {
        public Coding {
            if (Objects.requireNonNull(format).isBlank() || symbolsPerTick < 1 || bitsPerSymbol < 1)
                throw new IllegalArgumentException("Invalid radio coding");
        }
        long bitsPerTick() { return (long) symbolsPerTick * bitsPerSymbol; }
    }

    /**
     * Explicit radio attachment supplied by the host, not by a frame. A port used here must not
     * also be attached to the wired tick of the same host. Only local direct ports for this slice.
     */
    public record Radio(SerialDevice device, String port, BlockPos position, Band occupied, Band filter,
                        Coding coding, String decoder, boolean receiving, Optional<BlockPos> conditioner) {
        public Radio(SerialDevice device, String port, BlockPos position, Band occupied, Band filter, Coding coding, String decoder) {
            this(device, port, position, occupied, filter, coding, decoder, true, Optional.empty());
        }
        public Radio {
            Objects.requireNonNull(device);
            position = Objects.requireNonNull(position).immutable();
            Objects.requireNonNull(occupied);
            Objects.requireNonNull(filter);
            Objects.requireNonNull(coding);
            if (Objects.requireNonNull(port).isBlank() || Objects.requireNonNull(decoder).isBlank())
                throw new IllegalArgumentException("Radio port and decoder are required");
        }
        public Radio withReceiveFilter(Band band) {
            return withReceivePath(band, receiving, conditioner);
        }
        public Radio withReceivePath(Band band, boolean enabled, Optional<BlockPos> via) {
            return new Radio(device, port, position, occupied, band, coding, decoder, enabled, via);
        }
    }

    @FunctionalInterface
    public interface Propagation {
        /** Nonnegative received game units after propagation and filter response; zero means no contribution. */
        int strength(BlockPos source, BlockPos destination, Band occupied, Band filter);
    }

    @FunctionalInterface
    public interface Decoder {
        boolean canDecode(Coding coding, int wanted, long interference);
    }

    public enum Reception { ACCEPTED, INTERFERENCE, LOST_LOCK, RECEIVER_BUSY, INTERRUPTED, FRAME_TOO_LARGE, BUFFER_FULL }
    public enum ActivityPhase { TRANSMITTING, TRANSMITTED, INTERRUPTED }
    /** Local private hardware diagnosis; no payload or remote acknowledgement. */
    public record Observation(SharedSerialChannel.Port source, SharedSerialChannel.Port receiver,
                              Reception outcome, int wanted, long interference) {}
    /** Cosmetic beacon data, not a reception guarantee. */
    public record Activity(BlockPos position, Band occupied, ActivityPhase phase) {}
    public record Tick(Optional<String> fault, List<Activity> activity, List<Observation> receptions) {
        public Tick {
            fault = Objects.requireNonNull(fault);
            activity = List.copyOf(activity);
            receptions = List.copyOf(receptions);
        }
    }

    private record Endpoint(Radio radio, SerialDevice.Attachment local, SerialDevice.PhysicalSession session) {
        SharedSerialChannel.Port key() { return local.key(); }
        boolean accepts(Radio from) {
            return radio.receiving() && radio.filter().containsTunedSignal(from.occupied()) && radio.decoder().equals(from.coding().format());
        }
        void close(String reason) { radio.device().disconnect(radio.port(), reason); }
    }
    private static final class ObservationAttempt {
        final Endpoint receiver;
        Reception failure;
        int wanted;
        long interference;
        ObservationAttempt(Endpoint receiver) { this.receiver = receiver; }
        void fail(Reception cause) { if (failure == null) failure = cause; }
        void sample(int signal, long noise) {
            if (noise >= interference) {
                wanted = signal;
                interference = noise;
            }
        }
    }
    private static final class Emission {
        final Endpoint source;
        final byte[] frame;
        final long bitsPerTick;
        long remaining;
        final Map<SharedSerialChannel.Port, ObservationAttempt> attempts = new LinkedHashMap<>();
        Emission(Endpoint source, byte[] frame, long bitsPerTick, long remaining) {
            this.source = source; this.frame = frame.clone(); this.bitsPerTick = bitsPerTick; this.remaining = remaining;
        }
        Activity activity(ActivityPhase phase) {
            return new Activity(source.radio().position(), source.radio().occupied(), phase);
        }
        Observation observe(ObservationAttempt attempt, Reception outcome) {
            return new Observation(source.key(), attempt.receiver.key(), outcome, attempt.wanted, attempt.interference);
        }
    }

    private final SharedSerialChannel.Limits limits;
    private final Propagation propagation;
    private final Decoder decoder;
    private Map<SharedSerialChannel.Port, Endpoint> ports = Map.of();
    private final Map<SharedSerialChannel.Port, Emission> active = new LinkedHashMap<>();

    public RadioSerialNetwork(SharedSerialChannel.Limits limits, Propagation propagation, Decoder decoder) {
        this.limits = Objects.requireNonNull(limits);
        this.propagation = Objects.requireNonNull(propagation);
        this.decoder = Objects.requireNonNull(decoder);
    }

    public Tick tick(Collection<Radio> loaded) {
        if (loaded.size() > limits.maxPorts()) return rejectRoster("port_limit");
        var next = new LinkedHashMap<SharedSerialChannel.Port, Endpoint>();
        var identities = new HashMap<UUID, SerialDevice>();
        for (var radio : loaded) {
            var attachment = radio.device().radioAttachment(radio.port());
            if (attachment.isEmpty()) continue;
            var current = attachment.orElseThrow();
            var endpoint = new Endpoint(radio, current, radio.device().physicalSession(current));
            var previousOwner = identities.putIfAbsent(endpoint.key().device(), radio.device());
            if (previousOwner != null && previousOwner != radio.device()
                || next.putIfAbsent(endpoint.key(), endpoint) != null) return rejectRoster("duplicate_identity");
        }

        var activity = new ArrayList<Activity>();
        var observations = new ArrayList<Observation>();
        ports.forEach((key, old) -> {
            var attached = next.get(key);
            // Receiver filter changes break incoming lock (checked below), not this emitter's
            // in-flight or queued transmission. Other physical/session changes keep their fencing.
            if (old.equals(attached)) return;
            if (attached != null && old.local().equals(attached.local()) && old.session().equals(attached.session())
                && old.radio().withReceivePath(attached.radio().filter(), attached.radio().receiving(), attached.radio().conditioner()).equals(attached.radio())) return;
            // A port version change already aborted its former local transmission. Never
            // disconnect a fresh send queued by that same device after the new configuration.
            if (attached == null || old.radio().device() != attached.radio().device()
                || old.session().equals(attached.session())) old.close("topology_changed");
            var previousEmission = active.remove(key);
            if (previousEmission != null) interrupt(previousEmission, activity, observations);
        });
        ports = next;
        // One bounded snapshot of present hardware; admit every sender before judging any interval.
        for (var endpoint : ports.values()) {
            endpoint.radio().device().connect(endpoint.radio().port());
            endpoint.radio().device().start(endpoint.local(), this::transmit);
        }

        for (var emission : active.values()) {
            emission.attempts.forEach((key, attempt) -> {
                if (!attempt.receiver.equals(ports.get(key))) attempt.fail(Reception.LOST_LOCK);
            });
        }
        for (var receiver : ports.values()) sample(receiver);

        var iterator = active.values().iterator();
        while (iterator.hasNext()) {
            var emission = iterator.next();
            emission.remaining -= emission.bitsPerTick;
            if (emission.remaining > 0) {
                activity.add(emission.activity(ActivityPhase.TRANSMITTING));
                continue;
            }
            iterator.remove();
            emission.source.radio().device().complete(emission.source.radio().port(), SharedSerialChannel.Outcome.TRANSMITTED);
            activity.add(emission.activity(ActivityPhase.TRANSMITTED));
            for (var attempt : emission.attempts.values()) {
                Reception outcome = attempt.failure != null ? attempt.failure : deliver(emission, attempt.receiver);
                observations.add(emission.observe(attempt, outcome));
            }
        }
        return new Tick(Optional.empty(), activity, observations);
    }

    private SharedSerialChannel.Admission transmit(SharedSerialChannel.Port source, byte[] frame) {
        var endpoint = ports.get(source);
        if (endpoint == null) return SharedSerialChannel.Admission.UNKNOWN_PORT;
        if (active.containsKey(source)) return SharedSerialChannel.Admission.PORT_BUSY;
        if (frame.length == 0) return SharedSerialChannel.Admission.EMPTY_PAYLOAD;
        if (frame.length > Math.min(limits.maxFrameBytes(), endpoint.local().hardware().maxFrameBytes()))
            return SharedSerialChannel.Admission.PAYLOAD_TOO_LARGE;
        long rate = Math.min(limits.bitsPerStep(), Math.min(endpoint.local().hardware().bitsPerTick(),
            endpoint.radio().coding().bitsPerTick()));
        var emission = new Emission(endpoint, frame, rate, frame.length * 8L + limits.framingBits());
        // No joining in the middle of a frame. A changed receiver must wait for a new emission.
        ports.forEach((key, receiver) -> {
            if (!source.equals(key) && receiver.accepts(endpoint.radio()))
                emission.attempts.put(key, new ObservationAttempt(receiver));
        });
        active.put(source, emission);
        return SharedSerialChannel.Admission.STARTED;
    }

    private void sample(Endpoint receiver) {
        var attempts = new ArrayList<Emission>();
        for (var emission : active.values()) {
            var attempt = emission.attempts.get(receiver.key());
            if (attempt != null && attempt.failure == null) attempts.add(emission);
        }
        if (attempts.isEmpty()) return;
        if (active.containsKey(receiver.key())) {
            // Test-hardware contract: one half-duplex local RF resource per port.
            attempts.forEach(emission -> emission.attempts.get(receiver.key()).fail(Reception.RECEIVER_BUSY));
            return;
        }
        var signals = new HashMap<SharedSerialChannel.Port, Integer>();
        long sum = 0; // Bounded by int-valued received strength times the bounded number of active ports.
        for (var emission : active.values()) {
            var source = emission.source.radio();
            if (!source.occupied().overlaps(receiver.radio().filter())) continue;
            int strength = propagation.strength(source.position(), receiver.radio().position(),
                source.occupied(), receiver.radio().filter());
            if (strength < 0) throw new IllegalArgumentException("Negative received radio strength");
            signals.put(emission.source.key(), strength);
            sum += strength;
        }
        var clean = new ArrayList<ObservationAttempt>();
        for (var emission : attempts) {
            var attempt = emission.attempts.get(receiver.key());
            int wanted = signals.getOrDefault(emission.source.key(), 0);
            long interference = sum - wanted;
            if (wanted == 0 || !decoder.canDecode(emission.source.radio().coding(), wanted, interference)) {
                attempt.wanted = wanted;
                attempt.interference = interference;
                attempt.fail(wanted == 0 ? Reception.LOST_LOCK : Reception.INTERFERENCE);
            } else {
                attempt.sample(wanted, interference);
                clean.add(attempt);
            }
        }
        // A single tuner cannot accept two decodes in the same interval. Don't select a winner
        // using input order even if a supplied decoder policy regards both signals as sufficient.
        if (clean.size() > 1) clean.forEach(attempt -> attempt.fail(Reception.INTERFERENCE));
    }

    private Reception deliver(Emission emission, Endpoint receiver) {
        var admission = receiver.local().receiver().offer(emission.frame, emission.source.key());
        receiver.radio().device().received();
        return switch (admission) {
            case ACCEPTED -> Reception.ACCEPTED;
            case FRAME_TOO_LARGE -> Reception.FRAME_TOO_LARGE;
            case BUFFER_FULL -> Reception.BUFFER_FULL;
            case EMPTY_PAYLOAD -> throw new IllegalStateException("Nonempty radio frame required at admission");
        };
    }

    private Tick rejectRoster(String reason) {
        var activity = new ArrayList<Activity>();
        var observations = new ArrayList<Observation>();
        active.values().forEach(emission -> interrupt(emission, activity, observations));
        clear(reason);
        return new Tick(Optional.of(reason), activity, observations);
    }
    private static void interrupt(Emission emission, List<Activity> activity, List<Observation> observations) {
        activity.add(emission.activity(ActivityPhase.INTERRUPTED));
        emission.attempts.values().forEach(attempt -> observations.add(emission.observe(attempt, Reception.INTERRUPTED)));
    }
    private void clear(String reason) {
        ports.values().forEach(port -> port.close(reason));
        ports = Map.of();
        active.clear();
    }
    public void unload() { clear("unloaded"); }
}
