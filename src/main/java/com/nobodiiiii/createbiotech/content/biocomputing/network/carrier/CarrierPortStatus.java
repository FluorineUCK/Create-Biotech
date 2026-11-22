package com.nobodiiiii.createbiotech.content.biocomputing.network.carrier;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Direction;

/** Observation of this Computer's rf0 input. This is not an instruction to drive a frequency. */
public record CarrierPortStatus(Optional<Direction> input, CarrierClock.Output output) {
    public CarrierPortStatus {
        Objects.requireNonNull(input);
        Objects.requireNonNull(output);
    }

    public static String inputName(Optional<Direction> face) {
        return face.map(Direction::getSerializedName).orElse("internal");
    }

    public static Optional<Direction> parseInput(String name) {
        if ("internal".equals(name)) return Optional.empty();
        for (var face : Direction.values())
            if (face.getSerializedName().equals(name)) return Optional.of(face);
        throw new IllegalArgumentException("invalid_carrier_input");
    }

    public DataValue.RecordValue encode() {
        var fields = new LinkedHashMap<String, DataValue>();
        fields.put("input", new DataValue.TextValue(inputName(input)));
        fields.put("state", new DataValue.TextValue(output.state().name().toLowerCase(Locale.ROOT)));
        output.frequency().ifPresent(f -> {
            fields.put("numerator", new DataValue.IntegerValue(f.numerator()));
            fields.put("denominator", new DataValue.IntegerValue(f.denominator()));
        });
        return new DataValue.RecordValue(fields);
    }

    public static CarrierPortStatus decode(DataValue.RecordValue raw) {
        if (!(raw.fields().get("input") instanceof DataValue.TextValue input)
                || !(raw.fields().get("state") instanceof DataValue.TextValue label))
            throw new IllegalArgumentException("invalid_carrier_observation");
        var face = parseInput(input.value());
        var state = CarrierClock.State.valueOf(label.value().toUpperCase(Locale.ROOT));
        boolean locked = state == CarrierClock.State.LOCKED;
        if (!raw.fields().keySet().equals(locked ? Set.of("input","state","numerator","denominator") : Set.of("input","state")))
            throw new IllegalArgumentException("invalid_carrier_observation");
        return new CarrierPortStatus(face, new CarrierClock.Output(state, locked
            ? Optional.of(new CarrierFrequency(component(raw,"numerator"),component(raw,"denominator")))
            : Optional.empty()));
    }

    private static int component(DataValue.RecordValue raw, String key) {
        if (raw.fields().get(key) instanceof DataValue.IntegerValue n && n.value() >= 0 && n.value() <= Integer.MAX_VALUE)
            return (int)n.value();
        throw new IllegalArgumentException("invalid_carrier_observation");
    }
}
