package com.nobodiiiii.createbiotech.content.biocomputing.computer;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
public final class FisherAdmissionFixture {
    public static String send(ComputerBlockHost host, String entry, ComputerHostLimits limits, boolean available) {
        return host.admitFisherEvent(entry,limits,available);
    }
}
