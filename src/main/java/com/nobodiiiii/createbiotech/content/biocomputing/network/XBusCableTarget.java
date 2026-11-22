package com.nobodiiiii.createbiotech.content.biocomputing.network;

import com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins;
import java.util.*;
import net.minecraft.core.BlockPos;

/** Fixed destination in the source's dimension, not a program-visible address or a connection grant. */
public record XBusCableTarget(BlockPos position, String domain, String endpoint, String pin, UUID device, UUID computer) {
    public XBusCableTarget {
        position = Objects.requireNonNull(position).immutable();
        new EndpointConfiguration(domain, true); new EndpointConfiguration(endpoint, true);
        new HardwarePins(List.of(pin), List.of());
        Objects.requireNonNull(device); Objects.requireNonNull(computer);
    }
}
