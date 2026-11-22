package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import java.util.UUID;

/** Evidence about production work only; never permission to stop a Computer's general computation. */
public interface ProductionQuiescence {
    boolean allRootsStopped();
    boolean nodeIdle(UUID computerId);
    boolean nodeRootFree(UUID computerId);
    boolean nodeMailboxEmpty(UUID computerId);
}
