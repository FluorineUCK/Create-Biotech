package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentRadioHardware.Oscillator;
import com.nobodiiiii.createbiotech.content.biocomputing.network.carrier.CarrierPortStatus;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.WitchReceiverFilter;
import com.nobodiiiii.createbiotech.content.biocomputing.device.carrier.WitchCarrierStage;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Direction;

/** The dial describes local hardware. Neither a peer address nor a wired port is an operand. */
final class RadioControlData {
    record Tune(int preset, boolean armed) {
        Tune { if (preset < 0) throw new IllegalArgumentException("radio_preset_out_of_range"); }
        DataValue.RecordValue encode() {
            return new DataValue.RecordValue(Map.of("preset",new DataValue.IntegerValue(preset),"armed",new DataValue.BooleanValue(armed)));
        }
        static Tune decode(DataValue.RecordValue fields) {
            if (!fields.fields().keySet().equals(Set.of("preset","armed"))) throw new IllegalArgumentException("invalid_radio_tune");
            return new Tune(integer(fields,"preset"),flag(fields,"armed"));
        }
    }

    /** A port edit contains only a local face. It cannot tune, arm, send, or set a frequency. */
    record CarrierInput(Optional<Direction> face) {
        private static final List<Direction> FACES = List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH,
            Direction.WEST, Direction.UP, Direction.DOWN);
        CarrierInput { Objects.requireNonNull(face); }
        CarrierInput next() {
            int i = face.map(FACES::indexOf).orElse(-1) + 1;
            return new CarrierInput(i == FACES.size() ? Optional.empty() : Optional.of(FACES.get(i)));
        }
        DataValue.RecordValue encode() {
            return new DataValue.RecordValue(Map.of("input", new DataValue.TextValue(CarrierPortStatus.inputName(face))));
        }
        static CarrierInput decode(DataValue.RecordValue fields) {
            if (!fields.fields().keySet().equals(Set.of("input"))) throw new IllegalArgumentException("invalid_carrier_input");
            return new CarrierInput(CarrierPortStatus.parseInput(string(fields,"input")));
        }
    }

    record Stage(WitchCarrierStage.Setting applied, int maxFactor, int divisor, int multiplier, String state, String frequency) {
        Stage {
            if (divisor < 1 || divisor > maxFactor || multiplier < 1 || multiplier > maxFactor)
                throw new IllegalArgumentException("invalid_witch_carrier_limit");
        }
        int suggestedFactor(WitchCarrierStage.Mode mode) {
            if (mode == applied.mode()) return applied.factor();
            return switch (mode) { case MULTIPLY -> multiplier; case DIVIDE -> divisor; case OFF -> 1; };
        }
    }

    static Optional<WitchCarrierStage.Setting> stageSelection(WitchCarrierStage.Mode mode,
            Direction input, Direction output, String factorText, int maxFactor) {
        if (mode == WitchCarrierStage.Mode.OFF)
            return Optional.of(WitchCarrierStage.Setting.OFF);
        if (input == output || !factorText.matches("[0-9]{1,10}")) return Optional.empty();
        try {
            int factor = Integer.parseInt(factorText);
            if (factor < 1 || factor > maxFactor) return Optional.empty();
            return Optional.of(new WitchCarrierStage.Setting(mode, Optional.of(input), Optional.of(output), factor));
        } catch (NumberFormatException overflow) { return Optional.empty(); }
    }

    record View(long revision, Optional<Oscillator> bands, int preset, boolean armed, String reception,
                long wanted, long interference, String diagnostic, String transmit, int frames, Optional<CarrierPortStatus> carrier,
                Optional<WitchReceiverFilter.Setting> witch, String witchLink, int effectiveWidth, Optional<Stage> stage, Optional<Direction> receiveSend) {
        static View decode(DataValue.RecordValue fields) {
            var equipped = flag(fields,"equipped");
            Optional<Oscillator> bands = equipped ? Optional.of(new Oscillator(integer(fields,"preset_count"),
                integer(fields,"first_center"),integer(fields,"center_spacing"),integer(fields,"transmit_width"),
                integer(fields,"receive_width"))) : Optional.empty();
            var raw = fields.fields().get("carrier");
            if (raw != null && (!equipped || !(raw instanceof DataValue.RecordValue)))
                throw new IllegalArgumentException("invalid_carrier_observation");
            var filter = fields.fields().get("witch_filter");
            if (filter != null && (equipped || !(filter instanceof DataValue.RecordValue)))
                throw new IllegalArgumentException("invalid_witch_filter");
            Optional<Stage> stage = Optional.empty();
            if (fields.fields().containsKey("witch_carrier")) {
                if (equipped || !(fields.fields().get("witch_carrier") instanceof DataValue.RecordValue setting))
                    throw new IllegalArgumentException("invalid_witch_carrier");
                int max = integer(fields,"witch_carrier_max_factor");
                if (max < 1) throw new IllegalArgumentException("invalid_witch_carrier_limit");
                stage = Optional.of(new Stage(WitchCarrierStage.Setting.decode(setting), max, integer(fields,"witch_carrier_default_divisor"),
                    integer(fields,"witch_carrier_default_multiplier"), string(fields,"witch_carrier_state"),
                    fields.fields().containsKey("witch_carrier_frequency") ? string(fields,"witch_carrier_frequency") : ""));
            }
            return new View(number(fields,"revision"), bands, integer(fields,"preset"), flag(fields,"armed"),
                string(fields,"reception"),number(fields,"wanted"),number(fields,"interference"),string(fields,"diagnostic"),
                equipped ? string(fields,"transmit") : "", equipped ? integer(fields,"frames") : 0,
                raw == null ? Optional.empty() : Optional.of(CarrierPortStatus.decode((DataValue.RecordValue)raw)),
                filter == null ? Optional.empty() : Optional.of(WitchReceiverFilter.Setting.decode((DataValue.RecordValue)filter)),
                fields.fields().containsKey("witch_link") ? string(fields,"witch_link") : "off",
                fields.fields().containsKey("effective_receive_width") ? integer(fields,"effective_receive_width") : bands.map(Oscillator::receiveWidth).orElse(0), stage, fields.fields().containsKey("receive_send") ? CarrierPortStatus.parseInput(string(fields,"receive_send")) : Optional.empty());
        }
        boolean selectable(int candidate) { return bands.isPresent() && candidate >= 0 && candidate < bands.orElseThrow().presets(); }
    }
    private static long number(DataValue.RecordValue f,String name) {
        if (f.fields().get(name) instanceof DataValue.IntegerValue n && n.value() >= 0) return n.value();
        throw new IllegalArgumentException("Invalid radio field: " + name);
    }
    private static int integer(DataValue.RecordValue f,String name) {
        long n=number(f,name); if(n>Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid radio field: "+name);
        return (int)n;
    }
    private static String string(DataValue.RecordValue f,String name) {
        if (f.fields().get(name) instanceof DataValue.TextValue t) return t.value();
        throw new IllegalArgumentException("Invalid radio field: " + name);
    }
    private static boolean flag(DataValue.RecordValue f,String name) {
        if (f.fields().get(name) instanceof DataValue.BooleanValue b) return b.value();
        throw new IllegalArgumentException("Invalid radio field: " + name);
    }
    private RadioControlData() {}
}
