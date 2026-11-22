package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import java.util.List;

/** One active segment, called only after bounded physical discovery and admission. */
interface SerialChannel {
    SharedSerialChannel.Admission transmit(SharedSerialChannel.Port source, byte[] payload);
    List<SharedSerialChannel.Completion> advance();
    boolean transmitting();
    boolean collided();
}
