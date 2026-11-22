package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import java.util.*;

/** Selection metadata only; client coordinates, UUIDs and endpoint grants are deliberately absent. */
public final class WiringControlData {
    private WiringControlData() {}
    public record Add(long view, String name, String localPin, int peer) {
        public Add {
            validate(view, name); new HardwarePins(List.of(localPin), List.of());
            if (peer < 0) throw new IllegalArgumentException("invalid_selection");
        }
        public DataValue.RecordValue encode() {
            return new DataValue.RecordValue(Map.of("view", new DataValue.IntegerValue(view), "name", new DataValue.TextValue(name),
                "local_pin", new DataValue.TextValue(localPin), "peer", new DataValue.IntegerValue(peer)));
        }
        public static Add decode(DataValue.RecordValue value) {
            if (!value.fields().keySet().equals(Set.of("view", "name", "local_pin", "peer"))
                || !(value.field("view") instanceof DataValue.IntegerValue view)
                || !(value.field("name") instanceof DataValue.TextValue name)
                || !(value.field("local_pin") instanceof DataValue.TextValue pin)
                || !(value.field("peer") instanceof DataValue.IntegerValue peer)
                || peer.value() < 0 || peer.value() > Integer.MAX_VALUE) throw new IllegalArgumentException("invalid_selection");
            return new Add(view.value(), name.value(), pin.value(), (int)peer.value());
        }
    }
    public record Remove(long view, String name) {
        public Remove { validate(view, name); }
        public DataValue.RecordValue encode() {
            return new DataValue.RecordValue(Map.of("view", new DataValue.IntegerValue(view), "name", new DataValue.TextValue(name)));
        }
        public static Remove decode(DataValue.RecordValue value) {
            if (!value.fields().keySet().equals(Set.of("view", "name"))
                || !(value.field("view") instanceof DataValue.IntegerValue view)
                || !(value.field("name") instanceof DataValue.TextValue name)) throw new IllegalArgumentException("invalid_selection");
            return new Remove(view.value(), name.value());
        }
    }
    public record SetEnabled(long view, String name, boolean enabled) {
        public SetEnabled { validate(view, name); }
        public DataValue.RecordValue encode() {
            return new DataValue.RecordValue(Map.of("view", new DataValue.IntegerValue(view), "name", new DataValue.TextValue(name),
                "enabled", new DataValue.BooleanValue(enabled)));
        }
        public static SetEnabled decode(DataValue.RecordValue value) {
            if (!value.fields().keySet().equals(Set.of("view", "name", "enabled"))
                || !(value.field("view") instanceof DataValue.IntegerValue view)
                || !(value.field("name") instanceof DataValue.TextValue name)
                || !(value.field("enabled") instanceof DataValue.BooleanValue enabled)) throw new IllegalArgumentException("invalid_selection");
            return new SetEnabled(view.value(), name.value(), enabled.value());
        }
    }
    private static void validate(long view, String name) {
        if (view <= 0) throw new IllegalArgumentException("stale");
        new EndpointConfiguration(name, true);
    }
}
