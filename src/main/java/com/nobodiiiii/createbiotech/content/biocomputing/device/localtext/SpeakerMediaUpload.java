package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerFileRam;

/** Verifies the completed RAM buffer without creating a second copy or writing to disk. */
final class SpeakerMediaUpload {
    private SpeakerMediaUpload() {}

    static String finish(ComputerFileRam.Lease lease, String name) {
        var format = SpeakerMediaFormat.fromName(name);
        if (format != null && lease.seal(format::matches)) return "media_staged";
        lease.abort();
        return "media_invalid";
    }
}
