package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;

/** Portable values, never a remote receipt or reference to interface-owned bytes. */
public final class SerialResult {
    public static final DataType.RecordType SEND = new DataType.RecordType(Map.of("ok", DataType.Scalar.BOOLEAN, "status", DataType.Scalar.TEXT));
    public static final DataType.RecordType RECEIVE = new DataType.RecordType(Map.of("ok", DataType.Scalar.BOOLEAN,
        "status", DataType.Scalar.TEXT, "value", DataType.Scalar.ANY));
    public static final DataType.RecordType STATUS = new DataType.RecordType(Map.of("transmit", DataType.Scalar.TEXT,
        "connection", DataType.Scalar.TEXT, "failure", DataType.Scalar.TEXT, "receive_frames", DataType.Scalar.INTEGER,
        "receive_bytes", DataType.Scalar.INTEGER, "receive_faults", new DataType.ListType(DataType.Scalar.TEXT)));
    private SerialResult() {}
    public static DataValue.RecordValue send(String code) {
        return new DataValue.RecordValue(Map.of("ok", new DataValue.BooleanValue(code.equals("queued")), "status", new DataValue.TextValue(code)));
    }
    public static DataValue.RecordValue receive(String code, DataValue value) {
        return new DataValue.RecordValue(Map.of("ok", new DataValue.BooleanValue(code.equals("received")),
            "status", new DataValue.TextValue(code), "value", value == null ? DataValue.NullValue.INSTANCE : value));
    }
    public static DataValue.RecordValue status(SerialDevice.Status status) {
        return new DataValue.RecordValue(Map.of("transmit", new DataValue.TextValue(status.transmit()),
            "connection", new DataValue.TextValue(status.connection()), "failure", new DataValue.TextValue(status.failure()),
            "receive_frames", new DataValue.IntegerValue(status.receive().frames()), "receive_bytes", new DataValue.IntegerValue(status.receive().bytes()),
            "receive_faults", new DataValue.ListValue(status.receive().faults().stream().sorted()
                .<DataValue>map(f -> new DataValue.TextValue(f.name().toLowerCase(Locale.ROOT))).toList())));
    }
}
