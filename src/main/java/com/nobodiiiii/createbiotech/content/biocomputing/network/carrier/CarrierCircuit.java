package com.nobodiiiii.createbiotech.content.biocomputing.network.carrier;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** A bounded, directed trace of real output faces. The caller supplies only loaded hardware. */
public final class CarrierCircuit {
    private CarrierCircuit() {}

    public record Pin(BlockPos position, Direction face) {
        public Pin {
            position = Objects.requireNonNull(position).immutable();
            Objects.requireNonNull(face);
        }

        public static Pin opposite(BlockPos localPosition, Direction inputFace) {
            return new Pin(localPosition.relative(inputFace), inputFace.getOpposite());
        }
    }

    public sealed interface Hop permits Terminal, Link {}

    public record Terminal(CarrierClock.Output output) implements Hop {
        public Terminal { Objects.requireNonNull(output); }
    }

    public record Link(Pin upstream, UnaryOperator<CarrierClock.Output> transform) implements Hop {
        public Link {
            Objects.requireNonNull(upstream);
            Objects.requireNonNull(transform);
        }

        public static Link pass(Pin upstream) { return new Link(upstream, UnaryOperator.identity()); }
    }

    public static CarrierClock.Output sample(Pin output, int maxVisited, Function<Pin, Hop> observe) {
        if (maxVisited < 1) throw new IllegalArgumentException("Invalid clock path limit");
        var seen = new HashSet<Pin>();
        var stages = new ArrayDeque<UnaryOperator<CarrierClock.Output>>();
        var current = Objects.requireNonNull(output);
        while (true) {
            if (!seen.add(current)) {
                var fault = CarrierClock.Output.unavailable(CarrierClock.State.LOOP);
                // The cycle genuinely has no reference. Let reached converters drop their lock.
                for (var stage : stages) stage.apply(fault);
                return fault;
            }
            if (seen.size() > maxVisited) {
                // An individual path limit does not mean a reached component lost its own reference.
                return CarrierClock.Output.unavailable(CarrierClock.State.PATH_LIMIT);
            }
            switch (Objects.requireNonNull(observe.apply(current))) {
                case Terminal terminal -> {
                    var resolved = terminal.output();
                    for (var stage : stages) resolved = stage.apply(resolved);
                    return resolved;
                }
                case Link link -> {
                    stages.addFirst(link.transform());
                    current = link.upstream();
                }
            }
        }
    }
}
