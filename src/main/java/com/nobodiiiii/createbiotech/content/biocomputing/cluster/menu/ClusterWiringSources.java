package com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import java.util.*;
import net.minecraft.core.BlockPos;

/** Public names plus orphaned declaration scopes; never an implicit port grant. */
public record ClusterWiringSources(long view, BlockPos position, Map<String, Boolean> endpoints) {
    public ClusterWiringSources {
        if (view <= 0) throw new IllegalArgumentException("stale");
        position = Objects.requireNonNull(position).immutable();
        var sorted = new TreeMap<String, Boolean>(endpoints);
        sorted.forEach((name, configured) -> { new EndpointConfiguration(name, true); Objects.requireNonNull(configured); });
        endpoints = Collections.unmodifiableMap(sorted);
    }
    public DataValue.RecordValue encode() {
        var names = new TreeMap<String, DataValue>();
        endpoints.forEach((name, configured) -> names.put(name, new DataValue.BooleanValue(configured)));
        return new DataValue.RecordValue(Map.of("view", new DataValue.IntegerValue(view),
            "position", new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(position.getX()),
                "y", new DataValue.IntegerValue(position.getY()), "z", new DataValue.IntegerValue(position.getZ()))),
            "endpoints", new DataValue.RecordValue(names)));
    }
    public static ClusterWiringSources decode(DataValue value) {
        if (!(value instanceof DataValue.RecordValue root) || !root.fields().keySet().equals(Set.of("view", "position", "endpoints"))
            || !(root.field("view") instanceof DataValue.IntegerValue view)
            || !(root.field("position") instanceof DataValue.RecordValue pos) || !pos.fields().keySet().equals(Set.of("x", "y", "z"))
            || !(root.field("endpoints") instanceof DataValue.RecordValue entries)) throw new IllegalArgumentException("invalid_selection");
        var names = new TreeMap<String, Boolean>();
        entries.fields().forEach((name, configured) -> {
            if (!(configured instanceof DataValue.BooleanValue flag)) throw new IllegalArgumentException("invalid_selection");
            names.put(name, flag.value());
        });
        return new ClusterWiringSources(view.value(), new BlockPos(coordinate(pos.field("x")), coordinate(pos.field("y")), coordinate(pos.field("z"))), names);
    }
    private static int coordinate(DataValue value) {
        if (!(value instanceof DataValue.IntegerValue n) || n.value() < Integer.MIN_VALUE || n.value() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("invalid_selection");
        return (int)n.value();
    }
}
