package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import java.util.*;

/** Passive, undirected pin connection. No payload, queue, direction or delivery acknowledgement. */
public record XBusWire(Pin left, Pin right, boolean enabled) {
    public static final String UNSUPPORTED_TOPOLOGY = "xbus_topology_unsupported";
    public record Pin(String member, String endpoint, String pin) {
        public Pin { ClusterConfiguration.name(member); ClusterConfiguration.name(endpoint); ClusterConfiguration.name(pin); }
        public Pin rename(String from, String to) { return member.equals(from) ? new Pin(to, endpoint, pin) : this; }
        // Endpoint names are grants, not distinct physical pins of the same Computer.
        public boolean samePin(Pin other) { return member.equals(other.member) && pin.equals(other.pin); }
    }
    public XBusWire {
        Objects.requireNonNull(left); Objects.requireNonNull(right);
        if (left.member().equals(right.member())) throw new IllegalArgumentException("Wire requires two distinct Computers");
    }
    public boolean touches(String member) { return left.member().equals(member) || right.member().equals(member); }
    public XBusWire rename(String from, String to) { return new XBusWire(left.rename(from, to), right.rename(from, to), enabled); }
    /** This slice implements isolated pairs only; never silently choose one branch by traversal order. */
    public boolean isolated(Collection<XBusWire> wires) {
        return enabled && wires.stream().filter(XBusWire::enabled).filter(w -> w.left.samePin(left) || w.right.samePin(left)
            || w.left.samePin(right) || w.right.samePin(right)).count() == 1;
    }
}
