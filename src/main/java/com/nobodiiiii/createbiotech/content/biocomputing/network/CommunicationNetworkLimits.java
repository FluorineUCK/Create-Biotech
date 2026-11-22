package com.nobodiiiii.createbiotech.content.biocomputing.network;

/** Server-owned discovery work limits, separate from peripheral reads and production scheduling. */
public record CommunicationNetworkLimits(int maxVisited, int maxEndpoints) {
    public CommunicationNetworkLimits {
        if (maxVisited < 1 || maxEndpoints < 1) throw new IllegalArgumentException("Discovery limits must be positive");
    }
}
