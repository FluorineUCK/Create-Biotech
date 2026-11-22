package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import java.util.*;
import net.minecraft.core.BlockPos;

/** Controller-owned internal route for a trusted coordinator; not a cable advertisement or mailbox. */
public record XBusWireBinding(End left, End right) {
    public XBusWireBinding { Objects.requireNonNull(left); Objects.requireNonNull(right); }
    public record End(BlockPos position, XBusPortState observed, XBusAccess access) {
        public End { position = Objects.requireNonNull(position).immutable(); Objects.requireNonNull(observed); Objects.requireNonNull(access); }
        public XBusNetwork.Pin pin() { return new XBusNetwork.Pin(observed.computer(), observed.pin()); }
    }
    public record Result(String code, Optional<XBusWireBinding> binding) {
        public Result {
            Objects.requireNonNull(code); Objects.requireNonNull(binding);
            if (code.equals("ready") != binding.isPresent()) throw new IllegalArgumentException("Invalid internal wire result");
        }
    }
    /** One controller capture; all requested wires pass the same final validation barrier. */
    public record BatchResult(String code, Map<String, XBusWireBinding> bindings) {
        public BatchResult {
            Objects.requireNonNull(code); bindings = Map.copyOf(bindings);
            if (code.equals("ready") == bindings.isEmpty()) throw new IllegalArgumentException("Invalid wire batch result");
        }
    }
    public List<XBusAccess> accesses() { return List.of(left.access(), right.access()); }
    public XBusNetwork.Link link() { return new XBusNetwork.Link(left.pin(), right.pin()); }
}
