package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import java.util.Objects;

/** Continuous signal connection, not an XBus rendezvous or a buffered message route. */
public record SimpleIoWire(Pin left, Pin right, boolean enabled) {
    public record Pin(String member, String endpoint, String pin) {
        public Pin { ClusterConfiguration.name(member); ClusterConfiguration.name(endpoint); ClusterConfiguration.name(pin); }
        public Pin rename(String from, String to) { return member.equals(from) ? new Pin(to, endpoint, pin) : this; }
    }
    public SimpleIoWire {
        Objects.requireNonNull(left); Objects.requireNonNull(right);
        if (left.member().equals(right.member()) && left.pin().equals(right.pin()))
            throw new IllegalArgumentException("A signal wire must join distinct physical pins");
    }
    public boolean touches(String member) { return left.member().equals(member) || right.member().equals(member); }
    public SimpleIoWire rename(String from, String to) { return new SimpleIoWire(left.rename(from, to), right.rename(from, to), enabled); }
}
