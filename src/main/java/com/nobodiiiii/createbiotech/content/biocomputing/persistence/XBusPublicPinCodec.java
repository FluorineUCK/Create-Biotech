package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveData.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;

/** Strict shared representation for the passive export table in saves and menu packets. */
public final class XBusPublicPinCodec {
    private XBusPublicPinCodec() {}
    public static DataValue value(Map<String, XBusPublicPin> pins) {
        return named(pins, pin -> obj("member", pin.target().member(), "endpoint", pin.target().endpoint(),
            "pin", pin.target().pin(), "enabled", pin.enabled()));
    }
    public static Map<String, XBusPublicPin> read(DataValue value) {
        return readNamed(value, pin -> {
            if (!fields(pin).keySet().equals(Set.of("member", "endpoint", "pin", "enabled")))
                throw ArchiveException.invalid("Unexpected public XBus pin fields");
            return new XBusPublicPin(new XBusWire.Pin(string(at(pin, "member")), string(at(pin, "endpoint")),
                string(at(pin, "pin"))), bool(at(pin, "enabled")));
        });
    }
}
