package com.nobodiiiii.createbiotech.content.biocomputing.network.carrier;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CarrierCircuitTest {
    private static CarrierCircuit.Pin output(int x) {
        return new CarrierCircuit.Pin(new BlockPos(x, 0, 0), Direction.EAST);
    }

    @Test void resolvesUpstreamBeforeTransformingAndReportsBrokenReference() {
        var source = output(0);
        var divider = output(1);
        var wire = output(2);
        var multiplier = output(3);
        var input = new CarrierClock.Divider(3);
        var locked = new CarrierClock.Multiplier(2, 2);
        Map<CarrierCircuit.Pin, CarrierCircuit.Hop> physical = new HashMap<>();
        physical.put(source, new CarrierCircuit.Terminal(CarrierClock.Output.valid(CarrierFrequency.whole(100))));
        physical.put(divider, new CarrierCircuit.Link(source, value -> input.output(value.frequency())));
        physical.put(wire, CarrierCircuit.Link.pass(divider));
        physical.put(multiplier, new CarrierCircuit.Link(wire, value -> locked.tick(value.frequency())));
        var view = (java.util.function.Function<CarrierCircuit.Pin, CarrierCircuit.Hop>) pin ->
            physical.getOrDefault(pin, new CarrierCircuit.Terminal(CarrierClock.Output.unavailable(CarrierClock.State.UNAVAILABLE)));

        assertEquals(CarrierClock.State.ACQUIRING, CarrierCircuit.sample(multiplier, 4, view).state());
        assertEquals(Optional.of(new CarrierFrequency(200, 3)), CarrierCircuit.sample(multiplier, 4, view).frequency());
        physical.remove(source);
        assertTrue(CarrierCircuit.sample(multiplier, 4, view).frequency().isEmpty());
        physical.put(source, new CarrierCircuit.Terminal(CarrierClock.Output.valid(CarrierFrequency.whole(100))));
        assertEquals(CarrierClock.State.ACQUIRING, CarrierCircuit.sample(multiplier, 4, view).state());
        assertEquals(CarrierClock.State.PATH_LIMIT, CarrierCircuit.sample(multiplier, 3, view).state());
        assertEquals(Optional.of(new CarrierFrequency(200, 3)), CarrierCircuit.sample(multiplier, 4, view).frequency());
    }

    @Test void reportsCycleWithoutFollowingItAgain() {
        var a = output(0);
        var b = output(1);
        var visited = new AtomicInteger();
        var result = CarrierCircuit.sample(a, 8, pin -> {
            visited.incrementAndGet();
            return CarrierCircuit.Link.pass(pin.equals(a) ? b : a);
        });
        assertEquals(CarrierClock.State.LOOP, result.state());
        assertEquals(2, visited.get());
    }
}
