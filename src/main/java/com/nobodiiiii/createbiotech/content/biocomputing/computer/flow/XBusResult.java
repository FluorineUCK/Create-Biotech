package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;

/** An explicitly requested local operation result, never an on-wire envelope or receipt. */
public final class XBusResult {
    private XBusResult() {}
    public static final String PEER_PAUSED = "xbus_peer_paused";
    public static DataType.RecordType readType(DataType payload) {
        var fields = new LinkedHashMap<>(type().fields());
        fields.put("value", new DataType.OptionalType(payload));
        return new DataType.RecordType(fields);
    }
    public static DataType.RecordType type() {
        return new DataType.RecordType(Map.of("ok", DataType.Scalar.BOOLEAN, "error", new DataType.OptionalType(DataType.Scalar.ERROR)));
    }
    public static DataValue.RecordValue value(DataValue.RecordValue payload, boolean read, boolean paused) {
        if (paused && payload != null || !read && payload != null || read && !paused && payload == null)
            throw new IllegalArgumentException("Invalid XBus operation result");
        var fields = new LinkedHashMap<String, DataValue>();
        fields.put("ok", new DataValue.BooleanValue(!paused));
        fields.put("error", paused ? new DataValue.ErrorValue(PEER_PAUSED, "XBus peer paused") : DataValue.NullValue.INSTANCE);
        if (read) fields.put("value", payload == null ? DataValue.NullValue.INSTANCE : payload);
        return new DataValue.RecordValue(fields);
    }
}
