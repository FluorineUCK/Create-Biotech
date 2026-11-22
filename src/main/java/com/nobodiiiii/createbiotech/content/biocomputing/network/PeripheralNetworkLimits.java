package com.nobodiiiii.createbiotech.content.biocomputing.network;

import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.ReadLimits;
import java.util.Objects;

public record PeripheralNetworkLimits(int maxVisited, int maxReaders, ReadLimits snapshot) {
    public PeripheralNetworkLimits {
        if (maxVisited < 1 || maxReaders < 1) throw new IllegalArgumentException("Positive topology limits required");
        Objects.requireNonNull(snapshot);
    }
}
