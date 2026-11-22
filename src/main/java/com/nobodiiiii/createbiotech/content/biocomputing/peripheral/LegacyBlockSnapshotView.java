package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.LinkedHashMap;
import java.util.Map;

/** Compatibility projection only: all fields are the same immutable values, never new world reads. */
final class LegacyBlockSnapshotView {
    private LegacyBlockSnapshotView() {}
    static DataValue.RecordValue of(DataValue.RecordValue components) {
        var fields = new LinkedHashMap<String, DataValue>();
        DataValue block = value(components, BuiltinReadComponents.BLOCK);
        if (block instanceof DataValue.RecordValue description) {
            fields.put("block", description.field("id")); fields.put("position", description.field("position"));
            fields.put("state", description.field("state"));
        } else {
            fields.put("block", block); fields.put("position", block); fields.put("state", block);
        }
        fields.put("data", value(components, BuiltinReadComponents.PUBLIC_DATA));
        fields.put("inventory", value(components, BuiltinReadComponents.INVENTORY));
        fields.put("fluid", value(components, BuiltinReadComponents.FLUID));
        fields.put("energy", value(components, BuiltinReadComponents.ENERGY));
        fields.put("create", new DataValue.RecordValue(Map.of("kinetics", value(components, CreateReadComponents.KINETICS),
            "logistics", value(components, CreateReadComponents.LOGISTICS), "fluid_transport", CapabilitySnapshotValues.unsupported())));
        fields.put("components", components);
        return new DataValue.RecordValue(fields);
    }
    private static DataValue value(DataValue.RecordValue components, String id) {
        return components.fields().getOrDefault(id, CapabilitySnapshotValues.unsupported());
    }
}
