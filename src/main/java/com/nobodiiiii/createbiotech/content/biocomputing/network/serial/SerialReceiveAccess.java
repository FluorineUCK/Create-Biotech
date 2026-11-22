package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

/** Trusted host validation only; no frame consumption, transmission or configuration authority. */
public interface SerialReceiveAccess {
    SerialDevice.Receive beginReceive(String pin);
    boolean verifyReceiveCursor(SerialReceiveProof.Cursor cursor, String certificate);
    String receiveFailure(SerialReceiveProof.Cursor cursor);
    String signReceiveCursor(SerialReceiveProof.Cursor cursor);
}
