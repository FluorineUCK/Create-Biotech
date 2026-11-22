package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import java.util.Objects;

/** A passive alias of an existing member pin, not a wire, queue or extra hardware port. */
public record XBusPublicPin(XBusWire.Pin target, boolean enabled) {
    public XBusPublicPin { Objects.requireNonNull(target); }
    public XBusPublicPin renameMember(String from, String to) { return new XBusPublicPin(target.rename(from, to), enabled); }
}
