package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SimpleIoNetworkTest {
    private static final ResourceLimits LIMITS = new ResourceLimits(100, 100000, 100);
    @Test void aForgedSampleCannotCarryAnOutOfRangeSignal() {
        assertThrows(IllegalArgumentException.class, () -> new SimpleIoNetwork.Sample(OptionalInt.of(16), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new SimpleIoNetwork.Sample(OptionalInt.of(-1), Set.of()));
    }
    @Test void anExplicitWireSamplesPreviousOutputsBeforeEitherComputerExecutesTheRound() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var aPin = new SimpleIoNetwork.Pin(a, "p0"); var bPin = new SimpleIoNetwork.Pin(b, "p0");
        var writer = new FlowRuntime(a, UUID.randomUUID(), new FlowProgram(Map.of("level", DataType.Scalar.INTEGER),
            List.of(new FlowProgram.SignalWrite("write", "p0", "level"))),
            new DataValue.RecordValue(Map.of("level", new DataValue.IntegerValue(8))), LIMITS);
        var reader = new FlowRuntime(b, UUID.randomUUID(), new FlowProgram(Map.of(), List.of(
            new FlowProgram.SignalRead("read1", "p0", "old"), new FlowProgram.SignalRead("read2", "p0", "new"))),
            new DataValue.RecordValue(Map.of()), LIMITS);
        var wires = List.of(new SimpleIoNetwork.Wire(aPin, bPin));
        var before = SimpleIoNetwork.sample(Map.of(aPin, SimpleIoNetwork.Port.input(), bPin, SimpleIoNetwork.Port.input()), wires);
        writer.advanceRound(before.inputs(a)); reader.advanceRound(before.inputs(b));
        assertEquals(new DataValue.IntegerValue(0), reader.checkpoint().locals().field("old"));
        var after = SimpleIoNetwork.sample(Map.of(aPin, SimpleIoNetwork.Port.output(8), bPin, SimpleIoNetwork.Port.input()), wires);
        reader.advanceRound(after.inputs(b));
        assertEquals(new DataValue.IntegerValue(8), reader.checkpoint().locals().field("new"));
        assertEquals(Map.of("p0", 8), writer.checkpoint().signals().outputs());
        assertEquals(0, before.inputs(b).get("p0"));
    }

    @Test void branchingUsesTheStrongestOtherDriverAndDoesNotConnectUnwiredPins() {
        var a = new SimpleIoNetwork.Pin(UUID.randomUUID(), "p0"); var b = new SimpleIoNetwork.Pin(UUID.randomUUID(), "p0");
        var c = new SimpleIoNetwork.Pin(UUID.randomUUID(), "p0"); var isolated = new SimpleIoNetwork.Pin(a.computer(), "p1");
        var pins = new LinkedHashMap<SimpleIoNetwork.Pin, SimpleIoNetwork.Port>();
        pins.put(a, SimpleIoNetwork.Port.output(8)); pins.put(b, SimpleIoNetwork.Port.output(5));
        pins.put(c, SimpleIoNetwork.Port.input()); pins.put(isolated, SimpleIoNetwork.Port.input());
        var wires = List.of(new SimpleIoNetwork.Wire(a,c), new SimpleIoNetwork.Wire(c,b));
        var frame = SimpleIoNetwork.sample(pins, wires);
        assertEquals(5, frame.inputs(a.computer()).get("p0"));
        assertEquals(8, frame.inputs(b.computer()).get("p0")); assertEquals(8, frame.inputs(c.computer()).get("p0"));
        assertEquals(0, frame.inputs(a.computer()).get("p1"));
        pins.put(b, SimpleIoNetwork.Port.output(8));
        assertEquals(8, SimpleIoNetwork.sample(pins, wires).inputs(a.computer()).get("p0"));
        var reversed = new LinkedHashMap<SimpleIoNetwork.Pin, SimpleIoNetwork.Port>();
        new ArrayList<>(pins.keySet()).reversed().forEach(pin -> reversed.put(pin, pins.get(pin)));
        assertEquals(SimpleIoNetwork.sample(pins, wires), SimpleIoNetwork.sample(reversed, wires.reversed()));
        assertEquals(5, frame.inputs(a.computer()).get("p0"), "Previously sampled frame remains a value copy");
    }
    @Test void unavailableConnectedPinsAreNotMisreportedAsZeroAndDisconnectedInputsAreValidZero() {
        var a = new SimpleIoNetwork.Pin(UUID.randomUUID(), "p0"); var b = new SimpleIoNetwork.Pin(UUID.randomUUID(), "p0");
        for (var status : SimpleIoNetwork.Availability.values()) {
            if (status == SimpleIoNetwork.Availability.AVAILABLE) continue;
            var pins = Map.of(a, SimpleIoNetwork.Port.input(), b, SimpleIoNetwork.Port.unavailable(status));
            var frame = SimpleIoNetwork.sample(pins, List.of(new SimpleIoNetwork.Wire(a,b)));
            assertEquals(Set.of(status), frame.samples().get(a).problems());
            assertTrue(frame.inputs(a.computer()).isEmpty());
            assertEquals(0, SimpleIoNetwork.sample(pins, List.of()).inputs(a.computer()).get("p0"));
        }
        assertThrows(IllegalArgumentException.class, () -> SimpleIoNetwork.sample(Map.of(a, SimpleIoNetwork.Port.input()), List.of(new SimpleIoNetwork.Wire(a,b))));
        assertThrows(UnsupportedOperationException.class, () -> SimpleIoNetwork.sample(Map.of(a, SimpleIoNetwork.Port.input()), List.of()).inputs(a.computer()).put("p0", 99));
    }

}
