package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import java.util.*;

/** One frozen continuous-signal sample over explicitly supplied physical pins and wires. No world lookup or execution. */
public final class SimpleIoNetwork {
    private SimpleIoNetwork() {}
    public record Pin(UUID computer, String name) {
        public Pin { Objects.requireNonNull(computer); FlowProgram.name(name); }
    }
    public record Wire(Pin left, Pin right) {
        public Wire {
            Objects.requireNonNull(left); Objects.requireNonNull(right);
            if (left.equals(right)) throw new IllegalArgumentException("A wire must join two distinct pins");
        }
    }
    public enum Availability { AVAILABLE, UNLOADED, CLOSED, MISSING, HARDWARE_MISMATCH, RESOURCE_LIMIT }
    public record Port(Availability availability, OptionalInt output) {
        public Port {
            Objects.requireNonNull(availability); Objects.requireNonNull(output);
            if (availability != Availability.AVAILABLE && output.isPresent())
                throw new IllegalArgumentException("An unavailable pin cannot contribute a guessed output");
            output.ifPresent(value -> { if (value < SimpleIoState.MIN || value > SimpleIoState.MAX) throw new IllegalArgumentException("Invalid signal level"); });
        }
        public static Port input() { return new Port(Availability.AVAILABLE, OptionalInt.empty()); }
        public static Port output(int value) { return new Port(Availability.AVAILABLE, OptionalInt.of(value)); }
        public static Port unavailable(Availability availability) {
            if (availability == Availability.AVAILABLE) throw new IllegalArgumentException("Expected unavailable status");
            return new Port(availability, OptionalInt.empty());
        }
    }
    public record Sample(OptionalInt level, Set<Availability> problems) {
        public Sample {
            Objects.requireNonNull(level); problems = Set.copyOf(problems);
            level.ifPresent(value -> { if (value < SimpleIoState.MIN || value > SimpleIoState.MAX) throw new IllegalArgumentException("Invalid sampled signal level"); });
            if (level.isPresent() != problems.isEmpty() || problems.contains(Availability.AVAILABLE))
                throw new IllegalArgumentException("A sample must contain either a level or unavailability diagnostics");
        }
    }
    public record Frame(Map<Pin, Sample> samples) {
        public Frame { samples = Map.copyOf(samples); }
        /** Missing/unavailable inputs stay missing, never becoming a valid zero. Inspect samples for exact diagnostics. */
        public Map<String, Integer> inputs(UUID computer) {
            var result = new LinkedHashMap<String, Integer>();
            samples.forEach((pin, sample) -> {
                if (pin.computer().equals(computer)) sample.level().ifPresent(value -> result.put(pin.name(), value));
            });
            return Map.copyOf(result);
        }
    }
    public static Frame sample(Map<Pin, Port> pins, Collection<Wire> wires) {
        pins = Map.copyOf(pins);
        var neighbours = new HashMap<Pin, Set<Pin>>();
        pins.keySet().forEach(pin -> neighbours.put(pin, new HashSet<>()));
        for (var wire : wires) {
            if (!pins.containsKey(wire.left()) || !pins.containsKey(wire.right()))
                throw new IllegalArgumentException("A wire endpoint has no explicit availability state");
            neighbours.get(wire.left()).add(wire.right()); neighbours.get(wire.right()).add(wire.left());
        }
        var result = new HashMap<Pin, Sample>(); var visited = new HashSet<Pin>();
        for (var start : pins.keySet()) {
            if (!visited.add(start)) continue;
            var component = new ArrayList<Pin>(); var queue = new ArrayDeque<Pin>(); queue.add(start);
            while (!queue.isEmpty()) {
                var pin = queue.removeFirst(); component.add(pin);
                for (var next : neighbours.get(pin)) if (visited.add(next)) queue.add(next);
            }
            var problems = EnumSet.noneOf(Availability.class);
            int maximum = 0, second = 0, maximumCount = 0;
            for (var pin : component) {
                var port = pins.get(pin);
                if (port.availability() != Availability.AVAILABLE) problems.add(port.availability());
                if (port.output().isEmpty()) continue;
                int value = port.output().getAsInt();
                if (value > maximum) { second = maximum; maximum = value; maximumCount = 1; }
                else if (value == maximum) maximumCount++;
                else second = Math.max(second, value);
            }
            for (var pin : component) {
                var output = pins.get(pin).output();
                // The original pin input excludes its own drive, even before a read clears that drive.
                int level = output.isPresent() && output.getAsInt() == maximum && maximumCount == 1 ? second : maximum;
                result.put(pin, new Sample(problems.isEmpty() ? OptionalInt.of(level) : OptionalInt.empty(), problems));
            }
        }
        return new Frame(result);
    }
}
